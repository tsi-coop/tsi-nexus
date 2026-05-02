package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * TSI Nexus: Universal Governance & Policy Engine
 * A vertical-agnostic enforcer that uses the 'policy_manifest' table 
 * to validate actions across different institutional domains.
 */
public class Governance implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = req.getHeader("X-DX-FUNCTION");

            if ("validate_and_execute".equalsIgnoreCase(func)) {
                JSONObject result = processGuardedAction(input);
                OutputProcessor.send(res, 200, result);
            } else {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Invalid function.", req.getRequestURI());
            }
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

            // 1. DYNAMIC POLICY GUARD
            // This replaces hardcoded 'if' statements with database-driven rules.
            String violation = checkPolicyManifest(conn, actionType, params);
            if (violation != null) {
                JSONObject fail = new JSONObject();
                fail.put("success", false);
                fail.put("reason", violation);
                return fail;
            }

            // 2. UNIVERSAL STATE MUTATION
            // Uses the JSONB merge operator (||) to update the Digital Twin state.
            executeStateChange(conn, params);

            // 3. AUDIT LOGGING
            logAudit(conn, UUID.fromString(actorStr), intentRaw, actionType, params);

            conn.commit();
            
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

    /**
     * The Policy Engine: Executes SQL guardrails defined in the policy_manifest table.
     */
    private String checkPolicyManifest(Connection conn, String action, JSONObject params) throws SQLException {
        String targetId = ((String) params.get("target_external_id")).replaceFirst("^@", "");
        
        // Fetch rules assigned to this specific action type (e.g., DISBURSE, REPAIR)
        String sql = "SELECT query_logic, error_message FROM policy_manifest WHERE action_type = ? AND is_active = TRUE";
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, action.toUpperCase());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String query = rs.getString("query_logic");
                    String error = rs.getString("error_message");

                    // Run the guardrail query against the current state/graph
                    try (PreparedStatement guardPstmt = conn.prepareStatement(query)) {
                        guardPstmt.setString(1, targetId);
                        try (ResultSet guardRs = guardPstmt.executeQuery()) {
                            // If the query returns ANY count > 0, the policy is violated.
                            if (guardRs.next() && guardRs.getInt(1) > 0) {
                                return error;
                            }
                        }
                    }
                }
            }
        }
        return null; // Passes all guardrails
    }

    private void executeStateChange(Connection conn, JSONObject params) throws SQLException {
        String targetId = ((String) params.get("target_external_id")).replaceFirst("^@", "");
        JSONObject newData = (JSONObject) params.get("new_data");

        // Atomic JSONB merge: preserves existing fields while updating/adding new ones.
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

    @Override public void get(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}