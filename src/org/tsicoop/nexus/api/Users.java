package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * TSI Nexus: Admin User Management
 *
 * GET  /api/users  → list all users (no passwords), stats
 * POST /api/users  { action:"create",         name, email, password, role }
 * POST /api/users  { action:"update",         user_id, name, role }
 * POST /api/users  { action:"reset_password", user_id, password }
 * POST /api/users  { action:"toggle",         user_id }   — flip is_active
 * POST /api/users  { action:"delete",         user_id }
 */
public class Users implements Action {

    /* ── GET ─────────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONArray users = new JSONArray();
            long total = 0, admins = 0, staff = 0, inactive = 0;

            String sql = "SELECT u.user_id::text, u.name, u.email, u.role, u.is_active, " +
                         "to_char(u.created_at, 'DD Mon YYYY') AS created_fmt, " +
                         "u.twin_id::text, dt.external_id AS twin_external_id, dt.type AS twin_type " +
                         "FROM nexus_users u " +
                         "LEFT JOIN digital_twins dt ON dt.id = u.twin_id " +
                         "ORDER BY u.created_at ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject u = new JSONObject();
                    u.put("user_id",          rs.getString("user_id"));
                    u.put("name",             rs.getString("name"));
                    u.put("email",            rs.getString("email"));
                    u.put("role",             rs.getString("role"));
                    u.put("is_active",        rs.getBoolean("is_active"));
                    u.put("created",          rs.getString("created_fmt"));
                    u.put("twin_id",          rs.getString("twin_id"));
                    u.put("twin_external_id", rs.getString("twin_external_id"));
                    u.put("twin_type",        rs.getString("twin_type"));
                    users.add(u);

                    total++;
                    if (!rs.getBoolean("is_active")) inactive++;
                    else if ("admin".equals(rs.getString("role"))) admins++;
                    else staff++;
                }
            }

            JSONObject stats = new JSONObject();
            stats.put("total",    total);
            stats.put("admins",   admins);
            stats.put("staff",    staff);
            stats.put("inactive", inactive);

            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("users",   users);
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
                case "create":         createUser(conn, req, res, input);         break;
                case "update":         updateUser(conn, req, res, input);         break;
                case "reset_password": resetPassword(conn, req, res, input);      break;
                case "toggle":         toggleUser(conn, req, res, input);         break;
                case "delete":         deleteUser(conn, req, res, input);         break;
                case "link_twin":      linkTwin(conn, req, res, input);           break;
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
    private void createUser(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String name            = str(in, "name");
        String email           = str(in, "email").toLowerCase();
        String password        = str(in, "password");
        String role            = str(in, "role");
        String twinExternalId  = str(in, "twin_external_id").replaceFirst("^@", "");

        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "name, email, and password are required", req.getRequestURI()); return;
        }
        if (!role.equals("admin") && !role.equals("staff")) role = "staff";
        if (password.length() < 8) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "Password must be at least 8 characters", req.getRequestURI()); return;
        }

        // Resolve twin UUID if an external_id was provided
        String twinId = null;
        if (!twinExternalId.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id::text FROM digital_twins WHERE external_id = ?")) {
                ps.setString(1, twinExternalId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        OutputProcessor.errorResponse(res, 404, "Not found", "Digital Twin not found: @" + twinExternalId, req.getRequestURI()); return;
                    }
                    twinId = rs.getString("id");
                }
            }
        }

        String hash = new PasswordHasher().hashPassword(password);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO nexus_users (name, email, password_hash, role, twin_id) VALUES (?, ?, ?, ?, ?::uuid)")) {
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, hash);
            ps.setString(4, role);
            ps.setString(5, twinId);
            ps.executeUpdate();
        }

        if (twinId != null) insertSystemAccessEdge(conn, twinId);

        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── link / unlink twin ──────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void linkTwin(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String userId         = str(in, "user_id");
        String twinExternalId = str(in, "twin_external_id").replaceFirst("^@", "");

        if (userId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "user_id is required", req.getRequestURI()); return;
        }

        // Resolve the user's current twin_id so we can clean up the old edge
        String currentTwinId = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT twin_id::text FROM nexus_users WHERE user_id = ?::uuid")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    OutputProcessor.errorResponse(res, 404, "Not found", userId, req.getRequestURI()); return;
                }
                currentTwinId = rs.getString("twin_id");
            }
        }

        // Empty twin_external_id means unlink
        if (twinExternalId.isEmpty()) {
            if (currentTwinId != null) removeSystemAccessEdge(conn, currentTwinId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE nexus_users SET twin_id = NULL WHERE user_id = ?::uuid")) {
                ps.setString(1, userId);
                ps.executeUpdate();
            }
            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("linked",  false);
            OutputProcessor.send(res, 200, result);
            return;
        }

        // Resolve new twin UUID
        String twinId = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id::text FROM digital_twins WHERE external_id = ?")) {
            ps.setString(1, twinExternalId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    OutputProcessor.errorResponse(res, 404, "Not found", "Digital Twin not found: @" + twinExternalId, req.getRequestURI()); return;
                }
                twinId = rs.getString("id");
            }
        }

        // Swap edges: remove old, add new
        if (currentTwinId != null && !currentTwinId.equals(twinId)) removeSystemAccessEdge(conn, currentTwinId);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE nexus_users SET twin_id = ?::uuid WHERE user_id = ?::uuid")) {
            ps.setString(1, twinId);
            ps.setString(2, userId);
            ps.executeUpdate();
        }
        insertSystemAccessEdge(conn, twinId);

        JSONObject result = new JSONObject();
        result.put("success",          true);
        result.put("linked",           true);
        result.put("twin_external_id", "@" + twinExternalId);
        OutputProcessor.send(res, 200, result);
    }

    /* ── update name / role ──────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void updateUser(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String userId = str(in, "user_id");
        String name   = str(in, "name");
        String role   = str(in, "role");

        if (userId.isEmpty() || name.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "user_id and name are required", req.getRequestURI()); return;
        }
        if (!role.equals("admin") && !role.equals("staff")) role = "staff";

        if ("staff".equals(role) && isLastActiveAdmin(conn, userId)) {
            OutputProcessor.errorResponse(res, 409, "Conflict", "Cannot demote the last active admin", req.getRequestURI()); return;
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE nexus_users SET name = ?, role = ? WHERE user_id = ?::uuid")) {
            ps.setString(1, name);
            ps.setString(2, role);
            ps.setString(3, userId);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", userId, req.getRequestURI()); return;
            }
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── reset password ──────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void resetPassword(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String userId   = str(in, "user_id");
        String password = str(in, "password");

        if (userId.isEmpty() || password.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "user_id and password are required", req.getRequestURI()); return;
        }
        if (password.length() < 8) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "Password must be at least 8 characters", req.getRequestURI()); return;
        }

        String hash = new PasswordHasher().hashPassword(password);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE nexus_users SET password_hash = ? WHERE user_id = ?::uuid")) {
            ps.setString(1, hash);
            ps.setString(2, userId);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", userId, req.getRequestURI()); return;
            }
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── toggle active status ────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void toggleUser(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String userId = str(in, "user_id");
        if (userId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "user_id is required", req.getRequestURI()); return;
        }

        boolean currentlyActive = false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT is_active FROM nexus_users WHERE user_id = ?::uuid")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    OutputProcessor.errorResponse(res, 404, "Not found", userId, req.getRequestURI()); return;
                }
                currentlyActive = rs.getBoolean("is_active");
            }
        }

        if (currentlyActive && isLastActiveAdmin(conn, userId)) {
            OutputProcessor.errorResponse(res, 409, "Conflict", "Cannot deactivate the last active admin", req.getRequestURI()); return;
        }

        boolean newState = !currentlyActive;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE nexus_users SET is_active = ? WHERE user_id = ?::uuid")) {
            ps.setBoolean(1, newState);
            ps.setString(2, userId);
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success",   true);
        result.put("is_active", newState);
        OutputProcessor.send(res, 200, result);
    }

    /* ── delete ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void deleteUser(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String userId = str(in, "user_id");
        if (userId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "user_id is required", req.getRequestURI()); return;
        }

        JSONObject caller = InputProcessor.getAdminAuthToken(req, res);
        if (caller == null) return;

        String callerEmail = caller.get("email").toString();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT email FROM nexus_users WHERE user_id = ?::uuid")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    OutputProcessor.errorResponse(res, 404, "Not found", userId, req.getRequestURI()); return;
                }
                if (rs.getString("email").equalsIgnoreCase(callerEmail)) {
                    OutputProcessor.errorResponse(res, 409, "Conflict", "You cannot delete your own account", req.getRequestURI()); return;
                }
            }
        }

        if (isLastActiveAdmin(conn, userId)) {
            OutputProcessor.errorResponse(res, 409, "Conflict", "Cannot delete the last active admin", req.getRequestURI()); return;
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM nexus_users WHERE user_id = ?::uuid")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── system access edge helpers ─────────────────────────────────────── */

    private void insertSystemAccessEdge(Connection conn, String twinId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO twin_relationships (from_twin_id, to_twin_id, relationship_type) " +
                "VALUES (?::uuid, '00000000-0000-0000-0000-000000000000'::uuid, 'HAS_SYSTEM_ACCESS') " +
                "ON CONFLICT DO NOTHING")) {
            ps.setString(1, twinId);
            ps.executeUpdate();
        }
    }

    private void removeSystemAccessEdge(Connection conn, String twinId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM twin_relationships " +
                "WHERE from_twin_id = ?::uuid " +
                "AND to_twin_id = '00000000-0000-0000-0000-000000000000'::uuid " +
                "AND relationship_type = 'HAS_SYSTEM_ACCESS'")) {
            ps.setString(1, twinId);
            ps.executeUpdate();
        }
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private boolean isLastActiveAdmin(Connection conn, String userId) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM nexus_users WHERE role = 'admin' AND is_active = TRUE AND user_id != ?::uuid")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 0;
            }
        }
    }

    private String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v != null ? v.toString().trim() : "";
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
