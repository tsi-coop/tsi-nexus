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

            String sql = "SELECT user_id::text, name, email, role, is_active, " +
                         "to_char(created_at, 'DD Mon YYYY') AS created_fmt " +
                         "FROM nexus_users ORDER BY created_at ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject u = new JSONObject();
                    u.put("user_id",   rs.getString("user_id"));
                    u.put("name",      rs.getString("name"));
                    u.put("email",     rs.getString("email"));
                    u.put("role",      rs.getString("role"));
                    u.put("is_active", rs.getBoolean("is_active"));
                    u.put("created",   rs.getString("created_fmt"));
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
        String name     = str(in, "name");
        String email    = str(in, "email").toLowerCase();
        String password = str(in, "password");
        String role     = str(in, "role");

        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "name, email, and password are required", req.getRequestURI()); return;
        }
        if (!role.equals("admin") && !role.equals("staff")) role = "staff";
        if (password.length() < 8) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "Password must be at least 8 characters", req.getRequestURI()); return;
        }

        String hash = new PasswordHasher().hashPassword(password);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO nexus_users (name, email, password_hash, role) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, hash);
            ps.setString(4, role);
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
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
