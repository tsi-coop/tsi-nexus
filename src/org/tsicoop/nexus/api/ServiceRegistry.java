package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * TSI Nexus: External Service Registry
 *
 * GET  /api/services → all registered services + stats
 * POST /api/services { action:"upsert",    service_id?, identifier, api_base_url, auth_config }
 * POST /api/services { action:"ping",      service_id }  → live HTTP health check
 * POST /api/services { action:"set_status",service_id, status }
 * POST /api/services { action:"delete",    service_id }
 */
public class ServiceRegistry implements Action {

    /* ── GET ─────────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONArray services = new JSONArray();
            long total = 0, active = 0, degraded = 0, offline = 0;

            String sql = "SELECT service_id::text, identifier, api_base_url, " +
                         "COALESCE(auth_config::text, '{}') AS auth_config, " +
                         "status, uptime_percentage, service_type, entity_type, trigger_action, " +
                         "to_char(last_health_check, 'DD Mon YYYY HH24:MI') AS last_check_fmt " +
                         "FROM service_registry ORDER BY identifier ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                JSONParser parser = new JSONParser();
                while (rs.next()) {
                    JSONObject svc = new JSONObject();
                    svc.put("service_id",       rs.getString("service_id"));
                    svc.put("identifier",        rs.getString("identifier"));
                    svc.put("api_base_url",      rs.getString("api_base_url"));
                    svc.put("auth_config",       parser.parse(rs.getString("auth_config")));
                    String status = rs.getString("status");
                    svc.put("status",            status != null ? status : "Active");
                    svc.put("uptime_percentage", rs.getDouble("uptime_percentage"));
                    svc.put("last_check_fmt",    rs.getString("last_check_fmt"));
                    String svcType = rs.getString("service_type");
                    svc.put("service_type",   svcType != null ? svcType : "PULL");
                    svc.put("entity_type",    rs.getString("entity_type"));
                    svc.put("trigger_action", rs.getString("trigger_action"));
                    services.add(svc);
                    total++;
                    if ("Active".equals(status))   active++;
                    else if ("Degraded".equals(status)) degraded++;
                    else if ("Offline".equals(status))  offline++;
                }
            }

            JSONObject stats = new JSONObject();
            stats.put("total",    total);
            stats.put("active",   active);
            stats.put("degraded", degraded);
            stats.put("offline",  offline);

            JSONObject out = new JSONObject();
            out.put("success",  true);
            out.put("services", services);
            out.put("stats",    stats);
            OutputProcessor.send(res, 200, out);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Fetch failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── POST dispatcher ─────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            JSONObject input = InputProcessor.getInput(req);
            String action = str(input, "action");

            switch (action) {
                case "upsert":     upsertService(conn, req, res, input); break;
                case "ping":       pingService(conn, req, res, input);   break;
                case "set_status": setStatus(conn, req, res, input);     break;
                case "delete":     deleteService(conn, req, res, input); break;
                default: OutputProcessor.errorResponse(res, 400, "Bad request",
                        "Unknown action: " + action, req.getRequestURI());
            }
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Operation failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── upsert ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void upsertService(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String serviceId     = str(in, "service_id");
        String identifier    = str(in, "identifier").toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        String apiBaseUrl    = str(in, "api_base_url");
        String authConfig    = str(in, "auth_config");
        String serviceType   = str(in, "service_type");
        String entityType    = str(in, "entity_type");
        String triggerAction = str(in, "trigger_action").toUpperCase();

        if (identifier.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request",
                "identifier is required", req.getRequestURI()); return;
        }
        if (authConfig.isEmpty()) authConfig = "{}";
        if (serviceType.isEmpty()) serviceType = "PULL";
        if (!serviceType.equals("PULL") && !serviceType.equals("PUSH") && !serviceType.equals("INGEST")) {
            OutputProcessor.errorResponse(res, 400, "Bad request",
                "service_type must be PULL, PUSH, or INGEST", req.getRequestURI()); return;
        }

        String resultId;
        if (serviceId.isEmpty()) {
            String sql = "INSERT INTO service_registry (identifier, api_base_url, auth_config, service_type, entity_type, trigger_action) " +
                         "VALUES (?, ?, ?::jsonb, ?, ?, ?) RETURNING service_id::text";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, identifier);
                ps.setString(2, apiBaseUrl);
                ps.setString(3, authConfig);
                ps.setString(4, serviceType);
                ps.setString(5, entityType);
                ps.setString(6, triggerAction);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); resultId = rs.getString(1); }
            }
        } else {
            String sql = "UPDATE service_registry SET identifier=?, api_base_url=?, auth_config=?::jsonb, " +
                         "service_type=?, entity_type=?, trigger_action=? " +
                         "WHERE service_id=?::uuid RETURNING service_id::text";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, identifier);
                ps.setString(2, apiBaseUrl);
                ps.setString(3, authConfig);
                ps.setString(4, serviceType);
                ps.setString(5, entityType);
                ps.setString(6, triggerAction);
                ps.setString(7, serviceId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        OutputProcessor.errorResponse(res, 404, "Not found", serviceId, req.getRequestURI()); return;
                    }
                    resultId = rs.getString(1);
                }
            }
        }

        JSONObject result = new JSONObject();
        result.put("success",    true);
        result.put("service_id", resultId);
        OutputProcessor.send(res, 200, result);
    }

    /* ── ping ────────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void pingService(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String serviceId = str(in, "service_id");
        if (serviceId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "service_id is required", req.getRequestURI()); return;
        }

        String apiUrl = null;
        double currentUptime = 100.0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT api_base_url, uptime_percentage FROM service_registry WHERE service_id = ?::uuid")) {
            ps.setString(1, serviceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    OutputProcessor.errorResponse(res, 404, "Not found", serviceId, req.getRequestURI()); return;
                }
                apiUrl        = rs.getString("api_base_url");
                currentUptime = rs.getDouble("uptime_percentage");
            }
        }

        String newStatus;
        long   latencyMs = -1;
        String detail    = "";
        try {
            long start = System.currentTimeMillis();
            HttpURLConnection hc = (HttpURLConnection) new URL(apiUrl).openConnection();
            hc.setConnectTimeout(5000);
            hc.setReadTimeout(5000);
            hc.setRequestMethod("GET");
            hc.setInstanceFollowRedirects(false);
            int code = hc.getResponseCode();
            latencyMs = System.currentTimeMillis() - start;
            hc.disconnect();
            detail = "HTTP " + code + " in " + latencyMs + "ms";
            if (latencyMs >= 2000 || code >= 500) newStatus = "Degraded";
            else newStatus = "Active";
        } catch (Exception e) {
            newStatus = "Offline";
            detail    = e.getMessage() != null ? e.getMessage() : "Connection failed";
        }

        double pingScore = "Active".equals(newStatus) ? 100.0 : "Degraded".equals(newStatus) ? 50.0 : 0.0;
        double newUptime = Math.round((currentUptime * 0.9 + pingScore * 0.1) * 100.0) / 100.0;

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE service_registry SET status=?, last_health_check=NOW(), uptime_percentage=? " +
                "WHERE service_id=?::uuid")) {
            ps.setString(1, newStatus);
            ps.setDouble(2, newUptime);
            ps.setString(3, serviceId);
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success",          true);
        result.put("status",           newStatus);
        result.put("latency_ms",       latencyMs);
        result.put("uptime_percentage",newUptime);
        result.put("detail",           detail);
        OutputProcessor.send(res, 200, result);
    }

    /* ── set_status ──────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void setStatus(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String serviceId = str(in, "service_id");
        String status    = str(in, "status");
        if (serviceId.isEmpty() || status.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request",
                "service_id and status are required", req.getRequestURI()); return;
        }
        if (!status.equals("Active") && !status.equals("Degraded") && !status.equals("Offline")) {
            OutputProcessor.errorResponse(res, 400, "Bad request",
                "status must be Active, Degraded, or Offline", req.getRequestURI()); return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE service_registry SET status=? WHERE service_id=?::uuid")) {
            ps.setString(1, status);
            ps.setString(2, serviceId);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", serviceId, req.getRequestURI()); return;
            }
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("status",  status);
        OutputProcessor.send(res, 200, result);
    }

    /* ── delete ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void deleteService(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String serviceId = str(in, "service_id");
        if (serviceId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "service_id is required", req.getRequestURI()); return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM service_registry WHERE service_id=?::uuid")) {
            ps.setString(1, serviceId);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", serviceId, req.getRequestURI()); return;
            }
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v != null ? v.toString().trim() : "";
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
