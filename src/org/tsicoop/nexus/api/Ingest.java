package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * GET  /api/ingest?identifier=X&limit=N  → recent ingest events for that source (all sources if omitted)
 * POST /api/ingest { identifier, external_id, data }  → patch twin state, append stream entry, return diff
 */
public class Ingest implements Action {

    /* ── GET: ingest event history ───────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String identifier = req.getParameter("identifier");
            int limit = 50;
            try { limit = Integer.parseInt(req.getParameter("limit")); } catch (Exception ignore) {}
            if (limit < 1 || limit > 200) limit = 50;

            JSONArray events = new JSONArray();

            if (identifier != null && !identifier.isBlank()) {
                String sql =
                    "SELECT ist.content, to_char(ist.created_at,'DD Mon YYYY HH24:MI:SS') AS ts, " +
                    "dt.external_id, dt.type " +
                    "FROM interaction_stream ist " +
                    "JOIN digital_twins dt ON dt.id = ist.owner_id " +
                    "WHERE ist.intent_mapped = ? " +
                    "ORDER BY ist.created_at DESC LIMIT ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, identifier.toLowerCase());
                    ps.setInt(2, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) events.add(eventRow(rs, false));
                    }
                }
            } else {
                // All ingest events — scope to known INGEST source identifiers
                String sql =
                    "SELECT ist.content, to_char(ist.created_at,'DD Mon YYYY HH24:MI:SS') AS ts, " +
                    "dt.external_id, dt.type, ist.intent_mapped " +
                    "FROM interaction_stream ist " +
                    "JOIN digital_twins dt ON dt.id = ist.owner_id " +
                    "WHERE ist.intent_mapped IN " +
                    "  (SELECT LOWER(identifier) FROM service_registry WHERE service_type='INGEST' AND status='Active') " +
                    "ORDER BY ist.created_at DESC LIMIT ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) events.add(eventRow(rs, true));
                    }
                }
            }

            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("events", events);
            out.put("count",  (long) events.size());
            OutputProcessor.send(res, 200, out);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Fetch failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── POST: ingest state update ───────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            JSONObject input  = InputProcessor.getInput(req);
            String identifier = str(input, "identifier");
            String externalId = str(input, "external_id");
            JSONObject data   = (JSONObject) input.get("data");

            if (identifier.isEmpty() || externalId.isEmpty() || data == null || data.isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad request",
                    "identifier, external_id, and data are required", req.getRequestURI()); return;
            }

            conn = pool.getConnection();

            // 1. Look up INGEST service — auth_config + stream_tmpl in one query
            String authConfigJson = null;
            String streamTmpl     = "";
            String lookupSql =
                "SELECT auth_config::text, COALESCE(stream_tmpl,'') AS stream_tmpl " +
                "FROM service_registry " +
                "WHERE identifier=? AND service_type='INGEST' AND status='Active'";
            try (PreparedStatement ps = conn.prepareStatement(lookupSql)) {
                ps.setString(1, identifier.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        authConfigJson = rs.getString("auth_config");
                        streamTmpl     = rs.getString("stream_tmpl");
                    }
                }
            }
            if (authConfigJson == null) {
                OutputProcessor.errorResponse(res, 403, "Forbidden",
                    "Unknown or inactive ingest source", req.getRequestURI()); return;
            }

            // 2. Validate auth header
            JSONObject cfg      = (JSONObject) new JSONParser().parse(authConfigJson);
            String headerName   = (String) cfg.get("header");
            String headerSecret = (String) cfg.get("secret");
            String provided     = req.getHeader(headerName);
            if (provided == null || !provided.equals(headerSecret)) {
                OutputProcessor.errorResponse(res, 403, "Forbidden",
                    "Invalid ingest key", req.getRequestURI()); return;
            }

            // 3. Capture before-state for diff (only the keys being updated)
            JSONObject beforeSnap = new JSONObject();
            String selectSql = "SELECT current_state::text FROM digital_twins WHERE external_id=?";
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setString(1, externalId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        OutputProcessor.errorResponse(res, 404, "Not found",
                            "Entity not found: " + externalId, req.getRequestURI()); return;
                    }
                    String raw = rs.getString("current_state");
                    if (raw != null) {
                        JSONObject current = (JSONObject) new JSONParser().parse(raw);
                        for (Object k : data.keySet()) {
                            Object prev = current.get(k);
                            if (prev != null) beforeSnap.put(k, prev);
                        }
                    }
                }
            }

            // 4. Patch twin state
            String patchSql =
                "UPDATE digital_twins SET current_state = COALESCE(current_state,'{}') || ?::jsonb " +
                "WHERE external_id=?";
            try (PreparedStatement ps = conn.prepareStatement(patchSql)) {
                ps.setString(1, data.toJSONString());
                ps.setString(2, externalId);
                ps.executeUpdate();
            }

            // 5. Build stream entry — use stream_tmpl if configured, fall back to ad-hoc
            String content = streamTmpl.isEmpty()
                ? buildAdHocEntry(identifier, data)
                : applyTemplate(streamTmpl, externalId, data);

            // 6. Append to interaction_stream
            String streamSql =
                "INSERT INTO interaction_stream (owner_id, content, intent_mapped) " +
                "SELECT id, ?, ? FROM digital_twins WHERE external_id=?";
            try (PreparedStatement ps = conn.prepareStatement(streamSql)) {
                ps.setString(1, content);
                ps.setString(2, identifier.toLowerCase());
                ps.setString(3, externalId);
                ps.executeUpdate();
            }

            // 7. Build changed_keys list
            JSONArray changedKeys = new JSONArray();
            for (Object k : data.keySet()) changedKeys.add(k);

            JSONObject result = new JSONObject();
            result.put("success",        true);
            result.put("external_id",    externalId);
            result.put("fields_updated", (long) data.size());
            result.put("changed_keys",   changedKeys);
            result.put("before",         beforeSnap);
            result.put("after",          data);
            result.put("stream_entry",   content);
            OutputProcessor.send(res, 200, result);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Ingest failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private String applyTemplate(String tmpl, String externalId, JSONObject data) {
        String s = tmpl.replace("{external_id}", externalId);
        for (Object k : data.keySet()) s = s.replace("{" + k + "}", String.valueOf(data.get(k)));
        return s;
    }

    private String buildAdHocEntry(String identifier, JSONObject data) {
        StringBuilder sb = new StringBuilder("Ingest via ").append(identifier.toUpperCase()).append(": ");
        boolean first = true;
        for (Object k : data.keySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(k).append("=").append(data.get(k));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private JSONObject eventRow(ResultSet rs, boolean includeSource) throws Exception {
        JSONObject e = new JSONObject();
        e.put("external_id",  rs.getString("external_id"));
        e.put("entity_type",  rs.getString("type"));
        e.put("content",      rs.getString("content"));
        e.put("timestamp",    rs.getString("ts"));
        if (includeSource) e.put("source", rs.getString("intent_mapped").toUpperCase());
        return e;
    }

    private String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v != null ? v.toString().trim() : "";
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
