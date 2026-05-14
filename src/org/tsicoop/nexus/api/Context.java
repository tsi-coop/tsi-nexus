package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;

/**
 * TSI Nexus: Universal Context Resolver
 * Assembles the "Institutional Memory" by weaving together the State Store (Postgres),
 * the Social/Industrial Graph (Relationships), and the Interaction Stream (History).
 */
public class Context implements Action {

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String externalId = (String) input.get("external_id");
            String cleanId = externalId.startsWith("@") ? externalId.substring(1) : externalId;
            JSONObject result = assembleFullContext(cleanId);
            if (InputProcessor.isApiKeyRequest(req)) {
                JSONObject ctx = (JSONObject) result.get("context");
                if (ctx != null) ctx.remove("template_html");
            }
            OutputProcessor.send(res, 200, result);
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Context Retrieval Failed", e.getMessage(), req.getRequestURI());
        }
    }

    /**
     * The "Graph-Walk" Method.
     * Fetches the Twin's state and its immediate network of relationships.
     */
    private JSONObject assembleFullContext(String externalId) throws SQLException {
        Connection conn = null; PreparedStatement pstmt = null; ResultSet rs = null; PoolDB pool = new PoolDB();
        JSONObject response = new JSONObject();
        JSONObject context = new JSONObject();

        try {
            conn = pool.getConnection();
            
            // Universal Query: Joins the Twin with its Graph Relationships
            // Uses a Left Join to ensure we get the twin even if it has no links yet.
            String sql = "SELECT t.id, t.type, t.current_state, t.updated_at, " +
                         "(SELECT json_agg(json_build_object(" +
                         "'type', r.relationship_type, " +
                         "'to', t2.external_id, " +
                         "'to_type', t2.type, " +
                         "'to_name', COALESCE(NULLIF(t2.current_state->>'name',''), NULLIF(t2.current_state->>'system_name',''), " +
                         "NULLIF(t2.current_state->>'label',''), NULLIF(t2.current_state->>'title',''), " +
                         "NULLIF(t2.current_state->>'role',''), t2.external_id))) " +
                          "FROM twin_relationships r JOIN digital_twins t2 ON r.to_twin_id = t2.id " +
                          "WHERE r.from_twin_id = t.id) as out_links " +
                         "FROM digital_twins t WHERE t.external_id = ?";

            pstmt = conn.prepareStatement(sql);
            pstmt.setString(1, externalId);
            rs = pstmt.executeQuery();

            if (rs.next()) {
                UUID internalId = (UUID) rs.getObject("id");
                context.put("twin_id", internalId.toString());
                context.put("target", "@" + externalId);
                context.put("type", rs.getString("type"));
                context.put("state", rs.getString("current_state"));
                context.put("last_updated", rs.getTimestamp("updated_at").toString());
                
                // Attach Graph Data (The 'Links' that define institutional role)
                context.put("graph_links", rs.getString("out_links"));

                // Attach Interaction Stream (The 'History' that builds trust)
                context.put("recent_interactions", fetchInteractionStream(conn, internalId));

                String entityType = rs.getString("type");
                String tplHtml = fetchTemplateForType(conn, entityType, externalId);
                if (tplHtml != null) context.put("template_html", tplHtml);

                JSONObject live = callPullServices(conn, entityType, externalId);
                if (live != null) context.put("live_data", live);

                response.put("success", true);
                response.put("context", context);
            } else {
                response.put("success", false);
                response.put("reason", "Entity not found in Institutional Memory.");
            }
        } finally {
            pool.cleanup(rs, pstmt, conn);
        }
        return response;
    }

    /**
     * Fetches recent semantic history for this specific node.
     */
    private JSONArray fetchInteractionStream(Connection conn, UUID twinId) throws SQLException {
        JSONArray logs = new JSONArray();
        String sql = "SELECT content, created_at FROM interaction_stream " +
                     "WHERE owner_id = ? ORDER BY created_at DESC LIMIT 5";
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, twinId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    JSONObject entry = new JSONObject();
                    entry.put("content", rs.getString("content"));
                    entry.put("timestamp", rs.getTimestamp("created_at").toString());
                    logs.add(entry);
                }
            }
        }
        return logs;
    }

    private String fetchTemplateForType(Connection conn, String entityType, String externalId) {
        // Conditioned templates first (most specific), unconditioned fallback last
        String sql = "SELECT html_content, condition_sql FROM liquid_templates " +
                     "WHERE entity_type=? AND is_active=TRUE " +
                     "ORDER BY (condition_sql IS NOT NULL AND condition_sql <> '') DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityType);
            try (ResultSet rs = ps.executeQuery()) {
                String fallback = null;
                while (rs.next()) {
                    String html      = rs.getString("html_content");
                    String condition = rs.getString("condition_sql");
                    if (condition == null || condition.isBlank()) {
                        if (fallback == null) fallback = html;
                        continue;
                    }
                    try (PreparedStatement cps = conn.prepareStatement(condition)) {
                        cps.setString(1, externalId);
                        try (ResultSet crs = cps.executeQuery()) {
                            if (crs.next()) return html;
                        }
                    } catch (Exception ignore) {} // malformed condition_sql — skip
                }
                return fallback;
            }
        } catch (Exception ignore) {}
        return null;
    }

    @SuppressWarnings("unchecked")
    // Circuit breaker: long[0]=consecutive failures, long[1]=tripped-until epoch ms
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> circuitState =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final int  CIRCUIT_FAIL_THRESHOLD = 3;
    private static final long CIRCUIT_COOLDOWN_MS    = 30_000;

    private JSONObject callPullServices(Connection conn, String entityType, String externalId) {
        JSONObject merged = new JSONObject();
        String sql = "SELECT api_base_url, auth_config::text, COALESCE(timeout_ms, 2000) AS timeout_ms " +
                     "FROM service_registry " +
                     "WHERE service_type='PULL' AND entity_type=? AND status='Active'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entityType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String baseUrl = rs.getString("api_base_url");
                    String url     = baseUrl + "/" + externalId;

                    // Circuit breaker check
                    long[] state = circuitState.getOrDefault(baseUrl, new long[]{0, 0});
                    if (state[1] > System.currentTimeMillis()) {
                        System.err.println("[PULL] Circuit open: " + baseUrl + " — skipping");
                        continue;
                    }

                    JSONObject cfg = (JSONObject) new JSONParser().parse(rs.getString("auth_config"));
                    String header  = (String) cfg.get("header");
                    String secret  = (String) cfg.get("secret");
                    Duration timeout = Duration.ofMillis(rs.getInt("timeout_ms"));
                    try {
                        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                            .connectTimeout(timeout).build();
                        HttpRequest request = HttpRequest.newBuilder()
                            .GET().uri(URI.create(url))
                            .timeout(timeout)
                            .setHeader(header, secret).build();
                        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                        if (resp.statusCode() < 400) {
                            Object parsed = new JSONParser().parse(resp.body());
                            if (parsed instanceof JSONObject) merged.putAll((JSONObject) parsed);
                        }
                        // Success — reset circuit
                        circuitState.remove(baseUrl);
                    } catch (Exception e) {
                        System.err.println("[PULL] " + url + ": " + e.getMessage());
                        long[] s = circuitState.computeIfAbsent(baseUrl, k -> new long[]{0, 0});
                        s[0]++;
                        if (s[0] >= CIRCUIT_FAIL_THRESHOLD) {
                            s[1] = System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS;
                            System.err.println("[PULL] Circuit tripped: " + baseUrl +
                                               " after " + s[0] + " failures — cooldown 30s");
                        }
                    }
                }
            }
        } catch (Exception ignore) {}
        return merged.isEmpty() ? null : merged;
    }

    @Override public void get(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void put(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void delete(HttpServletRequest req, HttpServletResponse res) {}
    @Override public boolean validate(String m, HttpServletRequest req, HttpServletResponse res) { return true; }
}
