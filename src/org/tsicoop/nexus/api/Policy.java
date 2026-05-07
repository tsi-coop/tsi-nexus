package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

/**
 * TSI Nexus: Policy Manifest CRUD
 *
 * GET  /api/policy  → all policies + stats
 * POST /api/policy  { action:"upsert", policy_id, action_type, description,
 *                     query_logic, error_message, execution_mode }
 * POST /api/policy  { action:"toggle", policy_id }
 * POST /api/policy  { action:"delete", policy_id }
 * POST /api/policy  { action:"test",   policy_id, test_id }
 */
public class Policy implements Action {

    /* ── GET ─────────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            ensureDescriptionColumn(conn);

            JSONArray policies = new JSONArray();
            long total = 0, active = 0, guardrail = 0, analytics = 0;

            String sql = "SELECT policy_id, action_type, COALESCE(description,'') AS description, " +
                         "query_logic, error_message, execution_mode, is_active " +
                         "FROM policy_manifest ORDER BY action_type, policy_id";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject p = new JSONObject();
                    String mode = rs.getString("execution_mode");
                    boolean isActive = rs.getBoolean("is_active");
                    p.put("policy_id",      rs.getString("policy_id"));
                    p.put("action_type",    rs.getString("action_type"));
                    p.put("description",    rs.getString("description"));
                    p.put("query_logic",    rs.getString("query_logic"));
                    p.put("error_message",  rs.getString("error_message"));
                    p.put("execution_mode", mode);
                    p.put("is_active",      isActive);
                    policies.add(p);
                    total++;
                    if (isActive)                active++;
                    if ("GUARDRAIL".equals(mode)) guardrail++;
                    else                          analytics++;
                }
            }

            JSONObject stats = new JSONObject();
            stats.put("total",     total);
            stats.put("active",    active);
            stats.put("guardrail", guardrail);
            stats.put("analytics", analytics);

            JSONObject out = new JSONObject();
            out.put("success",      true);
            out.put("policies",     policies);
            out.put("stats",        stats);
            out.put("action_types", loadActionTypes(conn));
            OutputProcessor.send(res, 200, out);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Policy fetch failed", e.getMessage(), req.getRequestURI());
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
            JSONObject input = InputProcessor.getInput(req);
            String action = str(input, "action");
            conn = pool.getConnection();

            switch (action) {
                case "upsert": upsert(conn, req, res, input); break;
                case "toggle": toggle(conn, req, res, input); break;
                case "delete": deletePolicy(conn, req, res, input); break;
                case "test":   testPolicy(conn, req, res, input);   break;
                default: OutputProcessor.errorResponse(res, 400, "Bad request", "Unknown action: " + action, req.getRequestURI());
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
    private void upsert(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String policyId    = str(in, "policy_id").toUpperCase();
        String actionType  = str(in, "action_type").toUpperCase();
        String queryLogic  = str(in, "query_logic");
        String errorMsg    = str(in, "error_message");
        String execMode    = str(in, "execution_mode");
        String description = str(in, "description");

        if (policyId.isEmpty() || actionType.isEmpty() || queryLogic.isEmpty() || errorMsg.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request",
                "policy_id, action_type, query_logic and error_message are required", req.getRequestURI()); return;
        }
        if (!"GUARDRAIL".equals(execMode) && !"ANALYTICS".equals(execMode)) execMode = "GUARDRAIL";

        ensureDescriptionColumn(conn);

        String sql =
            "INSERT INTO policy_manifest (policy_id, action_type, description, query_logic, error_message, execution_mode, is_active) " +
            "VALUES (?, ?, ?, ?, ?, ?, TRUE) " +
            "ON CONFLICT (policy_id) DO UPDATE SET " +
            "  action_type = EXCLUDED.action_type, description = EXCLUDED.description, " +
            "  query_logic = EXCLUDED.query_logic, error_message = EXCLUDED.error_message, " +
            "  execution_mode = EXCLUDED.execution_mode";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, policyId);
            ps.setString(2, actionType);
            ps.setString(3, description);
            ps.setString(4, queryLogic);
            ps.setString(5, errorMsg);
            ps.setString(6, execMode);
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success",   true);
        result.put("policy_id", policyId);
        OutputProcessor.send(res, 200, result);
    }

    /* ── toggle ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void toggle(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String policyId = str(in, "policy_id");
        if (policyId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "policy_id is required", req.getRequestURI()); return;
        }
        boolean newState;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE policy_manifest SET is_active = NOT is_active WHERE policy_id = ? RETURNING is_active")) {
            ps.setString(1, policyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { OutputProcessor.errorResponse(res, 404, "Not found", policyId, req.getRequestURI()); return; }
                newState = rs.getBoolean("is_active");
            }
        }
        JSONObject result = new JSONObject();
        result.put("success",   true);
        result.put("is_active", newState);
        OutputProcessor.send(res, 200, result);
    }

    /* ── delete ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void deletePolicy(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String policyId = str(in, "policy_id");
        if (policyId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "policy_id is required", req.getRequestURI()); return;
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM policy_manifest WHERE policy_id = ?")) {
            ps.setString(1, policyId);
            if (ps.executeUpdate() == 0) { OutputProcessor.errorResponse(res, 404, "Not found", policyId, req.getRequestURI()); return; }
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── test ────────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void testPolicy(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String policyId = str(in, "policy_id");
        String testId   = str(in, "test_id").replaceFirst("^@", "");

        if (policyId.isEmpty() || testId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "policy_id and test_id are required", req.getRequestURI()); return;
        }

        String queryLogic, errorMessage, execMode;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT query_logic, error_message, execution_mode FROM policy_manifest WHERE policy_id = ?")) {
            ps.setString(1, policyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { OutputProcessor.errorResponse(res, 404, "Not found", policyId, req.getRequestURI()); return; }
                queryLogic   = rs.getString("query_logic");
                errorMessage = rs.getString("error_message");
                execMode     = rs.getString("execution_mode");
            }
        }

        JSONObject result = new JSONObject();
        try (PreparedStatement ps = conn.prepareStatement(queryLogic)) {
            ps.setString(1, testId);
            try (ResultSet rs = ps.executeQuery()) {
                int count = rs.next() ? rs.getInt(1) : 0;
                boolean blocked = "GUARDRAIL".equals(execMode) && count > 0;
                result.put("success", true);
                result.put("count",   count);
                if (blocked) {
                    result.put("verdict", "BLOCKED");
                    result.put("message", errorMessage);
                } else if ("ANALYTICS".equals(execMode)) {
                    result.put("verdict", "ANALYTICS");
                    result.put("message", "Query returned " + count + " row(s)");
                } else {
                    result.put("verdict", "ALLOWED");
                    result.put("message", "Entity passes this policy");
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("verdict", "ERROR");
            result.put("message", e.getMessage());
        }
        OutputProcessor.send(res, 200, result);
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private JSONArray loadActionTypes(Connection conn) {
        JSONArray types = new JSONArray();
        Set<String> seen = new HashSet<>();

        // 1. Commands registered in command_manifest
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT action_type, label FROM command_manifest WHERE is_active = TRUE ORDER BY action_type")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString("action_type");
                    if (t != null && !t.isBlank() && seen.add(t)) {
                        JSONObject entry = new JSONObject();
                        entry.put("action_type", t);
                        entry.put("label", rs.getString("label"));
                        entry.put("source", "command");
                        types.add(entry);
                    }
                }
            }
        } catch (Exception ignore) {}

        // 2. Form schemas registered in interaction_schema
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT action_type, label FROM interaction_schema WHERE is_active = TRUE ORDER BY action_type")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString("action_type");
                    if (t != null && !t.isBlank() && seen.add(t)) {
                        JSONObject entry = new JSONObject();
                        entry.put("action_type", t);
                        entry.put("label", rs.getString("label"));
                        entry.put("source", "schema");
                        types.add(entry);
                    }
                }
            }
        } catch (Exception ignore) {}

        // 3. Fallback: distinct types already used in existing policies
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT DISTINCT action_type FROM policy_manifest ORDER BY action_type")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString("action_type");
                    if (t != null && !t.isBlank() && seen.add(t)) {
                        JSONObject entry = new JSONObject();
                        entry.put("action_type", t);
                        entry.put("label", t);
                        entry.put("source", "existing");
                        types.add(entry);
                    }
                }
            }
        } catch (Exception ignore) {}

        return types;
    }

    private void ensureDescriptionColumn(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement(
                "ALTER TABLE policy_manifest ADD COLUMN IF NOT EXISTS description TEXT DEFAULT ''")) {
            ps.execute();
        } catch (Exception ignore) {}
    }

    private String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v != null ? v.toString().trim() : "";
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
