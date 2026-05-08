package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * TSI Nexus: User Authentication & Platform Setup
 *
 * GET  /api/nexus/setup            → { initialized: bool } — used by setup.html on load
 * POST /api/nexus/setup            → create root org + first admin user
 * POST /api/nexus/auth             → verify credentials, return JWT
 */
public class User implements Action {

    /* ── GET /api/nexus/setup : initialization probe ─────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            JSONObject result = new JSONObject();
            result.put("initialized", isInitialized(conn));
            OutputProcessor.send(res, 200, result);
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Check failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── POST : route by path ─────────────────────────────────────────────── */

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        String path = req.getServletPath();
        if (path.endsWith("/setup")) {
            handleSetup(req, res);
        } else if (path.endsWith("/auth")) {
            handleAuth(req, res);
        } else {
            OutputProcessor.errorResponse(res, 404, "Not found", "Unknown endpoint", path);
        }
    }

    /* ── POST /api/nexus/setup ────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void handleSetup(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            JSONObject input = InputProcessor.getInput(req);

            String institutionName = (String) input.get("institution_name");
            String adminName       = (String) input.get("admin_name");
            String adminEmail      = (String) input.get("admin_email");
            String password        = (String) input.get("password");

            if (institutionName == null || institutionName.isBlank() ||
                adminName == null || adminName.isBlank() ||
                adminEmail == null || adminEmail.isBlank() ||
                password == null || password.isBlank()) {
                OutputProcessor.errorResponse(res, 400, "Bad request", "All fields are required", req.getRequestURI());
                return;
            }

            conn = pool.getConnection();
            conn.setAutoCommit(false);

            if (isInitialized(conn)) {
                OutputProcessor.errorResponse(res, 409, "Conflict", "Platform is already initialized", req.getRequestURI());
                return;
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE root_organisation SET name = ?")) {
                ps.setString(1, institutionName);
                ps.executeUpdate();
            }

            String hash = new PasswordHasher().hashPassword(password);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO nexus_users (name, email, password_hash, role) VALUES (?, ?, ?, 'admin')")) {
                ps.setString(1, adminName);
                ps.setString(2, adminEmail);
                ps.setString(3, hash);
                ps.executeUpdate();
            }

            conn.commit();

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("message", "Platform initialized successfully");
            OutputProcessor.send(res, 200, result);

        } catch (Exception e) {
            try { if (conn != null) conn.rollback(); } catch (Exception ignore) {}
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Setup failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── POST /api/nexus/auth ─────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void handleAuth(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            JSONObject input = InputProcessor.getInput(req);

            String email    = (String) input.get("email");
            String password = (String) input.get("password");

            if (email == null || email.isBlank() || password == null || password.isBlank()) {
                OutputProcessor.errorResponse(res, 400, "Bad request", "Email and password are required", req.getRequestURI());
                return;
            }

            conn = pool.getConnection();

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, password_hash, role, twin_id::text FROM nexus_users WHERE email = ? AND is_active = TRUE")) {
                ps.setString(1, email.trim().toLowerCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        OutputProcessor.errorResponse(res, 401, "Unauthorized", "Invalid credentials", req.getRequestURI());
                        return;
                    }
                    String name   = rs.getString("name");
                    String hash   = rs.getString("password_hash");
                    String role   = rs.getString("role");
                    String twinId = rs.getString("twin_id");

                    if (!new PasswordHasher().checkPassword(password, hash)) {
                        OutputProcessor.errorResponse(res, 401, "Unauthorized", "Invalid credentials", req.getRequestURI());
                        return;
                    }

                    String token = JWTUtil.generateToken(email.trim().toLowerCase(), name, role, twinId);
                    JSONObject result = new JSONObject();
                    result.put("success", true);
                    result.put("token",   token);
                    result.put("name",    name);
                    result.put("role",    role);
                    if (twinId != null) result.put("twin_id", twinId);
                    OutputProcessor.send(res, 200, result);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Auth failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── helpers ──────────────────────────────────────────────────────────── */

    private boolean isInitialized(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM nexus_users");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
