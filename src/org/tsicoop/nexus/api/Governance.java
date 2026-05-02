package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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

            // Branch on execution_mode from policy_manifest — no hardcoded action types
            if ("ANALYTICS".equals(fetchExecutionMode(conn, actionType))) {
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
                        // Logic safely scoped by action type to avoid NullPointerExceptions
                        if ("COMPARE".equalsIgnoreCase(action)) {
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
                            // Standard guardrails block if any count > 0 is returned
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
                    String t1 = null, t2 = null, targetId = null;
                    if ("COMPARE".equalsIgnoreCase(action)) {
                        t1 = ((String) params.get("target_1")).replaceFirst("^@", "");
                        t2 = ((String) params.get("target_2")).replaceFirst("^@", "");
                    } else {
                        targetId = ((String) params.get("target_external_id")).replaceFirst("^@", "");
                    }
                    try (PreparedStatement analysisPstmt = conn.prepareStatement(query)) {
                        if ("COMPARE".equalsIgnoreCase(action)) {
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

                    // Gather member contexts for narrative generation
                    JSONArray memberContexts = new JSONArray();
                    if ("COMPARE".equalsIgnoreCase(action)) {
                        memberContexts.addAll(fetchMemberContexts(conn, t1));
                        memberContexts.addAll(fetchMemberContexts(conn, t2));
                    } else {
                        memberContexts.addAll(fetchMemberContexts(conn, targetId));
                    }

                    String narrative = Intelligence.generateNarrative(action, rows, memberContexts);

                    JSONObject result = new JSONObject();
                    result.put("success", true);
                    result.put("reason", label);
                    result.put("data", rows);
                    if (narrative != null && !narrative.isEmpty()) result.put("narrative", narrative);
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

    @SuppressWarnings("unchecked")
    private List<JSONObject> fetchMemberContexts(Connection conn, String targetExternalId) throws SQLException {
        String memberSql =
            "WITH target AS (SELECT id FROM digital_twins WHERE external_id = ?)," +
            "hop1 AS (SELECT DISTINCT CASE WHEN r.from_twin_id = t.id THEN r.to_twin_id ELSE r.from_twin_id END AS node_id " +
            "         FROM target t JOIN twin_relationships r ON r.from_twin_id = t.id OR r.to_twin_id = t.id)," +
            "hop2 AS (SELECT DISTINCT CASE WHEN r.from_twin_id = h.node_id THEN r.to_twin_id ELSE r.from_twin_id END AS node_id " +
            "         FROM hop1 h JOIN twin_relationships r ON r.from_twin_id = h.node_id OR r.to_twin_id = h.node_id " +
            "         WHERE (CASE WHEN r.from_twin_id = h.node_id THEN r.to_twin_id ELSE r.from_twin_id END) " +
            "               NOT IN (SELECT id FROM target))," +
            "all_reach AS (SELECT node_id FROM hop1 UNION SELECT node_id FROM hop2) " +
            "SELECT m.id, m.external_id, m.current_state " +
            "FROM all_reach ar JOIN digital_twins m ON m.id = ar.node_id AND m.type = 'member'";

        List<JSONObject> members = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(memberSql)) {
            pstmt.setString(1, targetExternalId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    JSONObject member = new JSONObject();
                    member.put("external_id", rs.getString("external_id"));
                    member.put("state", rs.getString("current_state"));
                    member.put("recent_interactions", fetchMemberInteractions(conn, (UUID) rs.getObject("id")));
                    members.add(member);
                }
            }
        }
        return members;
    }

    @SuppressWarnings("unchecked")
    private JSONArray fetchMemberInteractions(Connection conn, UUID memberId) throws SQLException {
        JSONArray interactions = new JSONArray();
        String sql = "SELECT content, created_at FROM interaction_stream WHERE owner_id = ? ORDER BY created_at DESC LIMIT 5";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, memberId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    JSONObject entry = new JSONObject();
                    entry.put("content", rs.getString("content"));
                    entry.put("timestamp", rs.getTimestamp("created_at").toString());
                    interactions.add(entry);
                }
            }
        }
        return interactions;
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

    @Override public void get(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}