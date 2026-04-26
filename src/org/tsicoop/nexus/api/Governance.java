package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * TSI Nexus: Governance & Policy Guard
 * Revised to ensure valid JSON output even on execution failure.
 */
public class Governance implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        // Global catch ensures we don't send an "Empty Response" to Liquid
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = req.getHeader("X-DX-FUNCTION");

            if (func == null || func.trim().isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad Request", "Missing function header.", req.getRequestURI());
                return;
            }

            if ("validate_and_execute".equalsIgnoreCase(func)) {
                JSONObject result = processGuardedAction(input);
                OutputProcessor.send(res, 200, result);
            } else {
                OutputProcessor.errorResponse(res, 400, "Unknown Function", func, req.getRequestURI());
            }
        } catch (Exception e) {
            e.printStackTrace();
            // Critical: Always return a JSON object so the browser fetch() doesn't crash
            JSONObject errorJson = new JSONObject();
            errorJson.put("success", false);
            errorJson.put("reason", "Internal Nexus Error: " + e.getMessage());
            OutputProcessor.send(res, 500, errorJson);
        }
    }

    private JSONObject processGuardedAction(JSONObject input) throws Exception {
        Connection conn = null; 
        PoolDB pool = new PoolDB();
        
        // Safety check for actor_id
        String actorStr = (String) input.getOrDefault("actor_id", "00000000-0000-0000-0000-000000000000");
        UUID actorId = UUID.fromString(actorStr);
        
        String actionType = (String) input.get("action_type");
        JSONObject params = (JSONObject) input.get("params");
        String intentRaw = (String) input.get("intent_raw");

        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false);

            // 1. Policy Guard (Deterministic validation)
            if (!isActionAllowed(conn, actionType, params)) {
                JSONObject fail = new JSONObject();
                fail.put("success", false);
                fail.put("reason", "Policy Violation: Unauthorized action.");
                return fail;
            }

            // 2. Execute Mutation (JSONB Merge)
            executeStateChange(conn, params);

            // 3. Audit Log
            logAudit(conn, actorId, intentRaw, actionType, params);

            conn.commit();
            
            JSONObject success = new JSONObject();
            success.put("success", true);
            success.put("message", "Nexus State Mutated.");
            return success;

        } catch (Exception e) {
            if (conn != null) conn.rollback();
            throw e; // Caught by the global try-catch in post()
        } finally { 
            pool.cleanup(null, null, conn); 
        }
    }

    private boolean isActionAllowed(Connection conn, String actionType, JSONObject params) {
        // Prototype logic: allow all
        return true; 
    }

    private void executeStateChange(Connection conn, JSONObject params) throws SQLException {
        String targetExternalId = ((String) params.get("target_external_id")).replaceFirst("^@", "");
        JSONObject newData = (JSONObject) params.get("new_data");

        // COALESCE ensures the merge works even if current_state is initially NULL
        String sql = "UPDATE digital_twins SET current_state = COALESCE(current_state, '{}'::jsonb) || ?::jsonb, " +
                     "updated_at = NOW() WHERE external_id = ?";
        
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newData.toJSONString());
            pstmt.setString(2, targetExternalId);
            int rows = pstmt.executeUpdate();
            
            if (rows == 0) {
                throw new SQLException("Twin lookup failed for ID: " + targetExternalId);
            }
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

    @Override public void get(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void put(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void delete(HttpServletRequest req, HttpServletResponse res) {}
    @Override public boolean validate(String m, HttpServletRequest req, HttpServletResponse res) { return true; }
}