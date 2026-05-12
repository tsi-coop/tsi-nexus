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
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.UUID;

/**
 * TSI Nexus: Universal Governance & Policy Engine
 * A vertical-agnostic enforcer that uses the 'policy_manifest' table 
 * to validate actions across different institutional domains.
 */
public class Governance implements Action {

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            // Inject actor_id from JWT when caller doesn't supply it explicitly
            if (input.get("actor_id") == null) {
                JSONObject caller = InputProcessor.getAdminAuthToken(req, res);
                if (caller != null && caller.get("twin_id") != null) {
                    input.put("actor_id", caller.get("twin_id"));
                }
            }
            OutputProcessor.send(res, 200, processGuardedAction(input));
        } catch (Exception e) {
            e.printStackTrace();
            JSONObject errorJson = new JSONObject();
            errorJson.put("success", false);
            errorJson.put("reason", "Governance Failure: " + e.getMessage());
            OutputProcessor.send(res, 500, errorJson);
        }
    }

    private JSONObject processGuardedAction(JSONObject input) throws Exception {
        Connection conn = null; 
        PoolDB pool = new PoolDB();
        
        String actionType = (String) input.get("action_type");
        JSONObject params = (JSONObject) input.get("params");
        String intentRaw = (String) input.get("intent_raw");
        String actorStr = (String) input.getOrDefault("actor_id", "00000000-0000-0000-0000-000000000000");

        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            String executionMode = fetchExecutionMode(conn, actionType);
            if ("ANALYTICS".equals(executionMode)) {
                JSONObject result = executeAnalysis(conn, actionType, params);
                conn.commit();
                return result;
            }

            // 1. DYNAMIC POLICY GUARD
            String violation = checkPolicyManifest(conn, actionType, params);
            if (violation != null) {
                JSONObject fail = new JSONObject();
                fail.put("success", false);
                fail.put("reason", violation);
                return fail;
            }

            // 2. UNIVERSAL STATE MUTATION
            executeStateChange(conn, params);

            // 3. AUDIT LOGGING
            String targetExternalId = params.get("target_external_id") != null
                ? ((String) params.get("target_external_id")).replaceFirst("^@", "") : "";
            logAudit(conn, UUID.fromString(actorStr), intentRaw, actionType, params);

            conn.commit();

            // 4. PUSH notifications to external systems (fire-and-forget)
            String ft = actionType; String fe = targetExternalId;
            new Thread(() -> callPushServices(ft, fe)).start();

            JSONObject success = new JSONObject();
            success.put("success", true);
            success.put("message", "Action Authorized & Finalized.");
            return success;

        } catch (Exception e) {
            if (conn != null) conn.rollback();
            throw e;
        } finally {
            pool.cleanup(null, null, conn);
        }
    }

    private String fetchExecutionMode(Connection conn, String actionType) throws SQLException {
        String sql = "SELECT execution_mode FROM policy_manifest WHERE action_type = ? AND is_active = TRUE LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, actionType.toUpperCase());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getString("execution_mode");
            }
        }
        return "GUARDRAIL";
    }

    private String checkPolicyManifest(Connection conn, String action, JSONObject params) throws SQLException {
        String sql = "SELECT query_logic, error_message FROM policy_manifest WHERE action_type = ? AND is_active = TRUE";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, action.toUpperCase());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String query = rs.getString("query_logic");
                    String error = rs.getString("error_message");

                    try (PreparedStatement guardPstmt = conn.prepareStatement(query)) {
                        boolean multiTarget = params.containsKey("target_1");
                        if (multiTarget) {
                            String t1 = ((String) params.get("target_1")).replaceFirst("^@", "");
                            String t2 = ((String) params.get("target_2")).replaceFirst("^@", "");
                            guardPstmt.setString(1, t1);
                            guardPstmt.setString(2, t2);
                        } else {
                            Object targetObj = params.get("target_external_id");
                            if (targetObj == null) {
                                throw new SQLException("Target ID is missing for action: " + action);
                            }
                            String targetId = ((String) targetObj).replaceFirst("^@", "");
                            guardPstmt.setString(1, targetId);
                        }

                        try (ResultSet guardRs = guardPstmt.executeQuery()) {
                            if (guardRs.next() && guardRs.getInt(1) > 0) return error;
                        }
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private JSONObject executeAnalysis(Connection conn, String action, JSONObject params) throws SQLException {
        String sql = "SELECT query_logic, error_message FROM policy_manifest WHERE action_type = ? AND is_active = TRUE LIMIT 1";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, action.toUpperCase());
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String query = rs.getString("query_logic");
                    String label = rs.getString("error_message");
                    JSONArray rows = new JSONArray();
                    boolean multiTarget = params.containsKey("target_1");
                    String t1 = null, t2 = null, targetId = null;
                    if (multiTarget) {
                        t1 = ((String) params.get("target_1")).replaceFirst("^@", "");
                        t2 = ((String) params.get("target_2")).replaceFirst("^@", "");
                    } else {
                        targetId = ((String) params.get("target_external_id")).replaceFirst("^@", "");
                    }
                    try (PreparedStatement analysisPstmt = conn.prepareStatement(query)) {
                        if (multiTarget) {
                            analysisPstmt.setString(1, t1);
                            analysisPstmt.setString(2, t2);
                        } else {
                            analysisPstmt.setString(1, targetId);
                        }
                        try (ResultSet dataRs = analysisPstmt.executeQuery()) {
                            ResultSetMetaData meta = dataRs.getMetaData();
                            int colCount = meta.getColumnCount();
                            while (dataRs.next()) {
                                JSONObject row = new JSONObject();
                                for (int i = 1; i <= colCount; i++) {
                                    row.put(meta.getColumnName(i), dataRs.getObject(i));
                                }
                                rows.add(row);
                            }
                        }
                    }
                    JSONObject result = new JSONObject();
                    result.put("success", true);
                    result.put("reason", label);
                    result.put("data", rows);
                    return result;
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("reason", "Analysis complete.");
        result.put("data", new JSONArray());
        return result;
    }

    private void executeStateChange(Connection conn, JSONObject params) throws SQLException {
        // Skip mutation for read-only actions (Analytics/Comparison)
        if (params.get("new_data") == null) return;

        String targetId = ((String) params.get("target_external_id")).replaceFirst("^@", "");
        JSONObject newData = (JSONObject) params.get("new_data");

        String sql = "UPDATE digital_twins SET current_state = COALESCE(current_state, '{}'::jsonb) || ?::jsonb, " +
                     "updated_at = NOW(), version_count = version_count + 1 WHERE external_id = ?";
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newData.toJSONString());
            pstmt.setString(2, targetId);
            if (pstmt.executeUpdate() == 0) throw new SQLException("Twin " + targetId + " not found.");
        }
    }

    private void logAudit(Connection conn, UUID actorId, String intent, String action, JSONObject params) throws SQLException {
        String sql = "INSERT INTO action_audit_log (actor_id, intent_raw, action_executed, created_at) VALUES (?, ?, ?::jsonb, NOW())";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, actorId);
            pstmt.setString(2, intent);
            pstmt.setString(3, params.toJSONString());
            pstmt.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private void callPushServices(String actionType, String externalId) {
        PoolDB pool = null; Connection conn = null;
        try {
            pool = new PoolDB(); conn = pool.getConnection();
            String sql = "SELECT api_base_url, auth_config::text FROM service_registry " +
                         "WHERE service_type='PUSH' AND trigger_action=? AND status='Active'";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, actionType.toUpperCase());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JSONObject cfg = (JSONObject) new JSONParser().parse(rs.getString("auth_config"));
                        JSONObject body = new JSONObject();
                        body.put("action_type", actionType);
                        body.put("external_id", externalId);
                        body.put("timestamp",   java.time.Instant.now().toString());
                        try {
                            new HttpClient().sendPost(
                                rs.getString("api_base_url"), body,
                                (String) cfg.get("header"), (String) cfg.get("secret")
                            );
                        } catch (Exception e) {
                            System.err.println("[PUSH] " + rs.getString("api_base_url") + ": " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[PUSH] callPushServices failed: " + e.getMessage());
        } finally { if (pool != null) pool.cleanup(null, null, conn); }
    }

    @Override public void get(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
