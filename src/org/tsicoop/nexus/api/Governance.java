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
 * TSI Nexus: Governance & Policy Guard
 * Ensures every "Context-Aware Action" is validated and audited.
 */
public class Governance implements Action {

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String func = req.getHeader("X-DX-FUNCTION");

            switch (func.toLowerCase()) {
                case "validate_and_execute":
                    // The core gatekeeper for any state-changing action
                    OutputProcessor.send(res, 200, processGuardedAction(input));
                    break;

                default:
                    OutputProcessor.errorResponse(res, 400, "Unknown Function", func, req.getRequestURI());
            }
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Governance Breach", e.getMessage(), req.getRequestURI());
        }
    }

    /**
     * The Policy Guard handshake.
     * Checks Postgres logic before allowing a Digital Twin update.
     */
    private JSONObject processGuardedAction(JSONObject input) throws SQLException {
        Connection conn = null; PreparedStatement pstmt = null; PoolDB pool = new PoolDB();
        
        UUID actorId = UUID.fromString((String) input.get("actor_id"));
        String actionType = (String) input.get("action_type"); // e.g., UPDATE_LIMIT
        JSONObject params = (JSONObject) input.get("params");
        String intentRaw = (String) input.get("intent_raw");

        try {
            conn = pool.getConnection();
            conn.setAutoCommit(false); // Transactions are vital for Governance

            // 1. Check Policy Guard Rules
            if (!isActionAllowed(conn, actionType, params)) {
                return new JSONObject() {{ 
                    put("success", false); 
                    put("reason", "Policy Violation: Action exceeds institutional boundaries."); 
                }};
            }

            // 2. Execute the Action (e.g., Update Digital Twin)
            executeStateChange(conn, params);

            // 3. Log to the Action Audit Log (The "Black Box")
            logAudit(conn, actorId, intentRaw, actionType, params);

            conn.commit();
            return new JSONObject() {{ put("success", true); put("message", "Action Executed and Audited."); }};

        } catch (Exception e) {
            if (conn != null) conn.rollback();
            throw new SQLException("Governance execution failed: " + e.getMessage());
        } finally { 
            pool.cleanup(null, pstmt, conn); 
        }
    }

    private boolean isActionAllowed(Connection conn, String actionType, JSONObject params) throws SQLException {
        // Prototype logic: Query policy_guard_rules table
        // For example, if action is 'disburse' and value > 100000, check if 'LARGE_LOAN' policy is active
        return true; // Simplified for prototype
    }

    private void executeStateChange(Connection conn, JSONObject params) throws SQLException {
        String sql = "UPDATE digital_twins SET current_state = current_state || ?::jsonb, updated_at = NOW() WHERE id = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, params.get("new_data").toString());
            pstmt.setObject(2, UUID.fromString(params.get("twin_id").toString()));
            pstmt.executeUpdate();
        }
    }

    private void logAudit(Connection conn, UUID actorId, String intent, String action, JSONObject params) throws SQLException {
        String sql = "INSERT INTO action_audit_log (actor_id, intent_raw, action_executed, created_at) VALUES (?, ?, ?, NOW())";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, actorId);
            pstmt.setString(2, intent);
            pstmt.setObject(3, params);
            pstmt.executeUpdate();
        }
    }

    @Override public void get(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void put(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void delete(HttpServletRequest req, HttpServletResponse res) {}
    @Override public boolean validate(String method, HttpServletRequest req, HttpServletResponse res) { return true; }
}
