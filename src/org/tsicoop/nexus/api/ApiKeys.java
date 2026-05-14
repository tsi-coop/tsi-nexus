package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * TSI Nexus: API Key Management
 *
 * GET  /api/apikeys  → all registered apps (no secrets)
 * POST /api/apikeys  { action:"create",   app_name, scopes[] }  → returns api_key + api_secret ONCE
 * POST /api/apikeys  { action:"rotate",   app_id }              → returns new api_secret ONCE
 * POST /api/apikeys  { action:"revoke",   app_id }
 * POST /api/apikeys  { action:"activate", app_id }
 * POST /api/apikeys  { action:"delete",   app_id }
 */
public class ApiKeys implements Action {

    private static final SecureRandom RNG = new SecureRandom();

    /* ── GET ─────────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONArray apps = new JSONArray();
            long total = 0, active = 0, revoked = 0;

            String sql = "SELECT app_id::text, app_name, api_key, authorized_scopes, status, " +
                         "to_char(created_at, 'DD Mon YYYY') AS created_fmt " +
                         "FROM app_access_registry ORDER BY created_at DESC";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject app = new JSONObject();
                    app.put("app_id",   rs.getString("app_id"));
                    app.put("app_name", rs.getString("app_name"));
                    app.put("api_key",  rs.getString("api_key"));
                    app.put("status",   rs.getString("status"));
                    app.put("created",  rs.getString("created_fmt"));

                    JSONArray scopes = new JSONArray();
                    Array scopeArr = rs.getArray("authorized_scopes");
                    if (scopeArr != null) {
                        for (Object s : (Object[]) scopeArr.getArray()) scopes.add(s.toString());
                    }
                    app.put("scopes", scopes);
                    apps.add(app);

                    total++;
                    if ("Active".equals(rs.getString("status")))  active++;
                    else                                           revoked++;
                }
            }

            JSONObject stats = new JSONObject();
            stats.put("total",   total);
            stats.put("active",  active);
            stats.put("revoked", revoked);

            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("apps",    apps);
            out.put("stats",   stats);
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
                case "create":   create(conn, req, res, input);                break;
                case "rotate":   rotate(conn, req, res, input);                break;
                case "revoke":   setStatus(conn, req, res, input, "Revoked"); break;
                case "activate": setStatus(conn, req, res, input, "Active");  break;
                case "delete":   deleteApp(conn, req, res, input);             break;
                default: OutputProcessor.errorResponse(res, 400, "Bad request", "Unknown action: " + action, req.getRequestURI());
            }
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Operation failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── create ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void create(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String appName = str(in, "app_name");
        if (appName.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "app_name is required", req.getRequestURI()); return;
        }

        JSONArray scopesJson = in.get("scopes") instanceof JSONArray ? (JSONArray) in.get("scopes") : new JSONArray();
        String[] scopes = new String[scopesJson.size()];
        for (int i = 0; i < scopesJson.size(); i++) scopes[i] = scopesJson.get(i).toString();

        String apiKey    = generateKey();
        String secret    = generateSecret();
        String secretHash = sha256(secret);

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO app_access_registry (app_name, api_key, api_secret_hash, authorized_scopes, status) " +
                "VALUES (?, ?, ?, ?, 'Active')")) {
            ps.setString(1, appName);
            ps.setString(2, apiKey);
            ps.setString(3, secretHash);
            ps.setArray(4, conn.createArrayOf("text", scopes));
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success",    true);
        result.put("api_key",    apiKey);
        result.put("api_secret", secret);   // plaintext returned ONCE, never stored
        OutputProcessor.send(res, 200, result);
    }

    /* ── rotate secret ───────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void rotate(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String appId = str(in, "app_id");
        if (appId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "app_id is required", req.getRequestURI()); return;
        }

        String secret     = generateSecret();
        String secretHash = sha256(secret);

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE app_access_registry SET api_secret_hash = ? WHERE app_id = ?::uuid")) {
            ps.setString(1, secretHash);
            ps.setString(2, appId);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", appId, req.getRequestURI()); return;
            }
        }

        JSONObject result = new JSONObject();
        result.put("success",    true);
        result.put("api_secret", secret);   // plaintext returned ONCE
        OutputProcessor.send(res, 200, result);
    }

    /* ── revoke / activate ───────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void setStatus(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in, String status) throws Exception {
        String appId = str(in, "app_id");
        if (appId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "app_id is required", req.getRequestURI()); return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE app_access_registry SET status = ? WHERE app_id = ?::uuid")) {
            ps.setString(1, status);
            ps.setString(2, appId);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", appId, req.getRequestURI()); return;
            }
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("status",  status);
        OutputProcessor.send(res, 200, result);
    }

    /* ── delete ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void deleteApp(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String appId = str(in, "app_id");
        if (appId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "app_id is required", req.getRequestURI()); return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM app_access_registry WHERE app_id = ?::uuid")) {
            ps.setString(1, appId);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", appId, req.getRequestURI()); return;
            }
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── key generation & hashing ────────────────────────────────────────── */

    private String generateKey() {
        byte[] bytes = new byte[16];
        RNG.nextBytes(bytes);
        return "nxs_" + SecurityUtil.toHex(bytes);
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return SecurityUtil.toHex(bytes);
    }

    private String sha256(String input) throws Exception {
        return SecurityUtil.sha256(input);
    }

    private String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v != null ? v.toString().trim() : "";
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
