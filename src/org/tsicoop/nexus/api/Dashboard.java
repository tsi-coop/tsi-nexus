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
 * TSI Nexus: Admin Dashboard API
 * 
 * GET /api/dashboard -> { 
 *   metrics: { twins, intents_today, blocks_today, health },
 *   recent_stream: [],
 *   recent_audit: [],
 *   activity_chart: []
 * }
 */
public class Dashboard implements Action {

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("metrics", getMetrics(conn));
            out.put("recent_stream", getRecentStream(conn));
            out.put("recent_audit", getRecentAudit(conn));
            out.put("activity_chart", getActivityChart(conn));

            OutputProcessor.send(res, 200, out);
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Dashboard load failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    @SuppressWarnings("unchecked")
    private JSONObject getMetrics(Connection conn) throws Exception {
        JSONObject m = new JSONObject();

        // Total Twins (Entities)
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM digital_twins");
             ResultSet rs = ps.executeQuery()) {
            m.put("twins", rs.next() ? rs.getLong(1) : 0L);
        }

        // Total Relationships
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM twin_relationships");
             ResultSet rs = ps.executeQuery()) {
            m.put("relationships", rs.next() ? rs.getLong(1) : 0L);
        }

        // Total Policies
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM policy_manifest");
             ResultSet rs = ps.executeQuery()) {
            m.put("policies", rs.next() ? rs.getLong(1) : 0L);
        }

        // Intents Today
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM interaction_stream WHERE created_at >= CURRENT_DATE");
             ResultSet rs = ps.executeQuery()) {
            m.put("intents_today", rs.next() ? rs.getLong(1) : 0L);
        }

        // Governance Blocks Today
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM action_audit_log " +
                "WHERE created_at >= CURRENT_DATE AND (action_executed->>'success')::boolean = false");
             ResultSet rs = ps.executeQuery()) {
            m.put("blocks_today", rs.next() ? rs.getLong(1) : 0L);
        }

        return m;
    }

    @SuppressWarnings("unchecked")
    private JSONArray getRecentStream(Connection conn) throws Exception {
        JSONArray arr = new JSONArray();
        String sql = "SELECT ist.id, dt.external_id, dt.current_state->>'name' AS entity_name, " +
                     "ist.content, ist.intent_mapped, " +
                     "to_char(ist.created_at, 'HH24:MI') AS time_at " +
                     "FROM interaction_stream ist " +
                     "LEFT JOIN digital_twins dt ON dt.id = ist.owner_id " +
                     "ORDER BY ist.created_at DESC LIMIT 5";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("id", rs.getLong("id"));
                o.put("actor", rs.getString("external_id"));
                o.put("actor_name", rs.getString("entity_name"));
                o.put("content", rs.getString("content"));
                o.put("intent", rs.getString("intent_mapped"));
                o.put("time", rs.getString("time_at"));
                arr.add(o);
            }
        }
        return arr;
    }

    @SuppressWarnings("unchecked")
    private JSONArray getRecentAudit(Connection conn) throws Exception {
        JSONArray arr = new JSONArray();
        String sql = "SELECT aal.audit_id::text, " +
                     "COALESCE(dt.external_id, aal.action_executed->>'entity') AS external_id, " +
                     "COALESCE(dt.current_state->>'name', dt2.current_state->>'name') AS actor_name, " +
                     "aal.intent_raw, aal.policy_id, " +
                     "(aal.action_executed->>'success')::boolean AS success, " +
                     "to_char(aal.created_at, 'HH24:MI') AS time_at " +
                     "FROM action_audit_log aal " +
                     "LEFT JOIN digital_twins dt  ON dt.id = aal.actor_id " +
                     "LEFT JOIN digital_twins dt2 ON dt2.external_id = aal.action_executed->>'entity' " +
                     "ORDER BY aal.created_at DESC LIMIT 5";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("id", rs.getString("audit_id"));
                o.put("actor", rs.getString("external_id"));
                o.put("actor_name", rs.getString("actor_name"));
                o.put("intent", rs.getString("intent_raw"));
                o.put("policy", rs.getString("policy_id"));
                o.put("success", rs.getBoolean("success"));
                o.put("time", rs.getString("time_at"));
                arr.add(o);
            }
        }
        return arr;
    }

    @SuppressWarnings("unchecked")
    private JSONArray getActivityChart(Connection conn) throws Exception {
        JSONArray arr = new JSONArray();
        // Last 14 days activity
        String sql = "SELECT d::date AS date, COUNT(ist.id) AS cnt " +
                     "FROM generate_series(CURRENT_DATE - 13, CURRENT_DATE, '1 day'::interval) d " +
                     "LEFT JOIN interaction_stream ist ON ist.created_at::date = d::date " +
                     "GROUP BY d::date ORDER BY d::date";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("date", rs.getString("date"));
                o.put("count", rs.getLong("cnt"));
                arr.add(o);
            }
        }
        return arr;
    }

    @Override public void post(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
