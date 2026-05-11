package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * TSI Nexus: Data Extraction & Reporting
 *
 * GET /api/reports?type=summary         → platform-wide KPI stats
 * GET /api/reports?type=twins           → all digital twins (?entity_type=member)
 * GET /api/reports?type=stream          → interaction stream (?from=&to=&owner=)
 * GET /api/reports?type=audit           → action audit log  (?from=&to=&policy_id=)
 * GET /api/reports?type=policy_summary  → per-policy event counts
 *
 * All data-export types return { success, count, rows[] } JSON.
 * CSV conversion and download are handled client-side.
 */
public class Reports implements Action {

    private static final int MAX_ROWS = 10_000;

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String type = req.getParameter("type");
            if (type == null || type.isBlank()) type = "summary";

            switch (type.trim().toLowerCase()) {
                case "summary":        summary(conn, req, res);        break;
                case "twins":          twins(conn, req, res);          break;
                case "stream":         stream(conn, req, res);         break;
                case "audit":          audit(conn, req, res);          break;
                case "policy_summary": policySummary(conn, req, res);  break;
                case "relationships":  relationships(conn, req, res);  break;
                default:
                    OutputProcessor.errorResponse(res, 400, "Bad request",
                        "Unknown type: " + type, req.getRequestURI());
            }
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Report failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── summary ─────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void summary(Connection conn, HttpServletRequest req, HttpServletResponse res) throws Exception {

        // Twins by type
        JSONObject twins = new JSONObject();
        JSONArray byType = new JSONArray();
        long totalTwins = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT type, COUNT(*) AS cnt FROM digital_twins GROUP BY type ORDER BY type");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("type",  rs.getString("type"));
                row.put("count", rs.getLong("cnt"));
                byType.add(row);
                totalTwins += rs.getLong("cnt");
            }
        }
        twins.put("total",   totalTwins);
        twins.put("by_type", byType);

        // Stream stats
        JSONObject stream = new JSONObject();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) AS total, " +
                "COUNT(*) FILTER (WHERE created_at >= CURRENT_DATE) AS today, " +
                "COUNT(*) FILTER (WHERE created_at >= date_trunc('week', CURRENT_DATE)) AS this_week " +
                "FROM interaction_stream");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                stream.put("total",     rs.getLong("total"));
                stream.put("today",     rs.getLong("today"));
                stream.put("this_week", rs.getLong("this_week"));
            }
        }

        // Audit stats
        JSONObject auditStats = new JSONObject();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) AS total, " +
                "COUNT(*) FILTER (WHERE created_at >= CURRENT_DATE) AS today " +
                "FROM action_audit_log");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                auditStats.put("total", rs.getLong("total"));
                auditStats.put("today", rs.getLong("today"));
            }
        }

        // Policy stats
        JSONObject policyStats = new JSONObject();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) AS total, COUNT(*) FILTER (WHERE is_active) AS active FROM policy_manifest");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                policyStats.put("total",  rs.getLong("total"));
                policyStats.put("active", rs.getLong("active"));
            }
        }

        // Stream activity last 30 days (daily buckets for sparkline)
        JSONArray activity = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT to_char(d::date, 'DD Mon') AS day, COALESCE(cnt,0) AS cnt " +
                "FROM generate_series(CURRENT_DATE - 29, CURRENT_DATE, '1 day'::interval) d " +
                "LEFT JOIN (" +
                "  SELECT date_trunc('day', created_at) AS bucket, COUNT(*) AS cnt " +
                "  FROM interaction_stream " +
                "  WHERE created_at >= CURRENT_DATE - 29 " +
                "  GROUP BY bucket" +
                ") sub ON sub.bucket = d::date " +
                "ORDER BY d");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject pt = new JSONObject();
                pt.put("day", rs.getString("day"));
                pt.put("cnt", rs.getLong("cnt"));
                activity.add(pt);
            }
        }

        JSONObject out = new JSONObject();
        out.put("success",  true);
        out.put("twins",    twins);
        out.put("stream",   stream);
        out.put("audit",    auditStats);
        out.put("policies", policyStats);
        out.put("activity", activity);
        OutputProcessor.send(res, 200, out);
    }

    /* ── twins ────────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void twins(Connection conn, HttpServletRequest req, HttpServletResponse res) throws Exception {
        String entityType = req.getParameter("entity_type");

        String sql = "SELECT external_id, type, version_count, current_state::text AS state, " +
                     "COALESCE(NULLIF(current_state->>'name',''), NULLIF(current_state->>'system_name',''), " +
                     "NULLIF(current_state->>'label',''), NULLIF(current_state->>'title',''), " +
                     "NULLIF(current_state->>'role',''), '') AS display_name, " +
                     "to_char(created_at, 'YYYY-MM-DD HH24:MI:SS') AS created, " +
                     "to_char(updated_at, 'YYYY-MM-DD HH24:MI:SS') AS updated " +
                     "FROM digital_twins" +
                     (entityType != null && !entityType.isBlank() ? " WHERE type = ?" : "") +
                     " ORDER BY type, external_id LIMIT " + MAX_ROWS;

        JSONArray rows = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (entityType != null && !entityType.isBlank()) ps.setString(1, entityType.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject row = new JSONObject();
                    row.put("external_id",   rs.getString("external_id"));
                    row.put("name",          rs.getString("display_name"));
                    row.put("type",          rs.getString("type"));
                    row.put("version_count", rs.getInt("version_count"));
                    row.put("current_state", rs.getString("state"));
                    row.put("created",       rs.getString("created"));
                    row.put("updated",       rs.getString("updated"));
                    rows.add(row);
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("success", true);
        out.put("count",   rows.size());
        out.put("rows",    rows);
        OutputProcessor.send(res, 200, out);
    }

    /* ── stream ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void stream(Connection conn, HttpServletRequest req, HttpServletResponse res) throws Exception {
        List<String> conditions = new ArrayList<>();
        List<Object> params     = new ArrayList<>();

        String owner  = req.getParameter("owner");
        String from   = req.getParameter("from");
        String to     = req.getParameter("to");

        if (owner != null && !owner.isBlank()) {
            conditions.add("dt.external_id = ?");
            params.add(owner.trim().replaceAll("^@+", ""));
        }
        if (from != null && !from.isBlank()) {
            conditions.add("ist.created_at >= ?::date");
            params.add(from.trim());
        }
        if (to != null && !to.isBlank()) {
            conditions.add("ist.created_at < (?::date + INTERVAL '1 day')");
            params.add(to.trim());
        }

        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        String sql = "SELECT ist.id, COALESCE(dt.external_id,'') AS external_id, " +
                     "COALESCE(dt.type,'') AS entity_type, " +
                     "ist.content, COALESCE(ist.intent_mapped,'') AS intent_mapped, " +
                     "to_char(ist.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created " +
                     "FROM interaction_stream ist " +
                     "LEFT JOIN digital_twins dt ON dt.id = ist.owner_id " +
                     where + " ORDER BY ist.id DESC LIMIT " + MAX_ROWS;

        JSONArray rows = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Object p : params) ps.setObject(idx++, p);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject row = new JSONObject();
                    row.put("id",           rs.getLong("id"));
                    row.put("external_id",  rs.getString("external_id"));
                    row.put("entity_type",  rs.getString("entity_type"));
                    row.put("content",      rs.getString("content"));
                    row.put("intent_mapped",rs.getString("intent_mapped"));
                    row.put("created",      rs.getString("created"));
                    rows.add(row);
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("success", true);
        out.put("count",   rows.size());
        out.put("rows",    rows);
        OutputProcessor.send(res, 200, out);
    }

    /* ── audit ────────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void audit(Connection conn, HttpServletRequest req, HttpServletResponse res) throws Exception {
        List<String> conditions = new ArrayList<>();
        List<Object> params     = new ArrayList<>();

        String policyId = req.getParameter("policy_id");
        String from     = req.getParameter("from");
        String to       = req.getParameter("to");

        if (policyId != null && !policyId.isBlank()) {
            conditions.add("aal.policy_id = ?");
            params.add(policyId.trim());
        }
        if (from != null && !from.isBlank()) {
            conditions.add("aal.created_at >= ?::date");
            params.add(from.trim());
        }
        if (to != null && !to.isBlank()) {
            conditions.add("aal.created_at < (?::date + INTERVAL '1 day')");
            params.add(to.trim());
        }

        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        String sql = "SELECT aal.audit_id::text, " +
                     "COALESCE(dt.external_id,'') AS external_id, " +
                     "COALESCE(dt.type,'') AS entity_type, " +
                     "COALESCE(aal.intent_raw,'') AS intent_raw, " +
                     "COALESCE(aal.policy_id,'') AS policy_id, " +
                     "CASE WHEN (aal.action_executed->>'success')::boolean = true THEN 'authorised' " +
                     "     WHEN (aal.action_executed->>'success')::boolean = false THEN 'denied' " +
                     "     ELSE 'unknown' END AS outcome, " +
                     "to_char(aal.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created " +
                     "FROM action_audit_log aal " +
                     "LEFT JOIN digital_twins dt ON dt.id = aal.actor_id " +
                     where + " ORDER BY aal.created_at DESC LIMIT " + MAX_ROWS;

        JSONArray rows = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            for (Object p : params) ps.setObject(idx++, p);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject row = new JSONObject();
                    row.put("audit_id",    rs.getString("audit_id"));
                    row.put("external_id", rs.getString("external_id"));
                    row.put("entity_type", rs.getString("entity_type"));
                    row.put("intent_raw",  rs.getString("intent_raw"));
                    row.put("policy_id",   rs.getString("policy_id"));
                    row.put("outcome",     rs.getString("outcome"));
                    row.put("created",     rs.getString("created"));
                    rows.add(row);
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("success", true);
        out.put("count",   rows.size());
        out.put("rows",    rows);
        OutputProcessor.send(res, 200, out);
    }

    /* ── policy_summary ──────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void policySummary(Connection conn, HttpServletRequest req, HttpServletResponse res) throws Exception {
        String sql =
            "SELECT aal.policy_id, COUNT(*) AS total, " +
            "COUNT(*) FILTER (WHERE (aal.action_executed->>'success')::boolean = true)  AS authorised, " +
            "COUNT(*) FILTER (WHERE (aal.action_executed->>'success')::boolean = false) AS denied " +
            "FROM action_audit_log aal " +
            "WHERE aal.policy_id IS NOT NULL " +
            "GROUP BY aal.policy_id ORDER BY total DESC";

        JSONArray rows = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("policy_id",  rs.getString("policy_id"));
                row.put("total",      rs.getLong("total"));
                row.put("authorised", rs.getLong("authorised"));
                row.put("denied",     rs.getLong("denied"));
                rows.add(row);
            }
        }

        JSONObject out = new JSONObject();
        out.put("success", true);
        out.put("count",   rows.size());
        out.put("rows",    rows);
        OutputProcessor.send(res, 200, out);
    }

    /* ── relationships ───────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void relationships(Connection conn, HttpServletRequest req, HttpServletResponse res) throws Exception {
        String fromType = req.getParameter("from_type");
        String relType  = req.getParameter("rel_type");
        String toType   = req.getParameter("to_type");

        String sql = "SELECT t1.external_id AS from_ext, " +
                     "COALESCE(NULLIF(t1.current_state->>'name',''), NULLIF(t1.current_state->>'system_name',''), " +
                     "NULLIF(t1.current_state->>'label',''), NULLIF(t1.current_state->>'title',''), " +
                     "NULLIF(t1.current_state->>'role',''), '') AS from_name, " +
                     "t2.external_id AS to_ext, " +
                     "COALESCE(NULLIF(t2.current_state->>'name',''), NULLIF(t2.current_state->>'system_name',''), " +
                     "NULLIF(t2.current_state->>'label',''), NULLIF(t2.current_state->>'title',''), " +
                     "NULLIF(t2.current_state->>'role',''), '') AS to_name, " +
                     "t1.type AS from_type, t2.type AS to_type, " +
                     "tr.metadata::text AS meta " +
                     "FROM twin_relationships tr " +
                     "JOIN digital_twins t1 ON tr.from_twin_id = t1.id " +
                     "JOIN digital_twins t2 ON tr.to_twin_id = t2.id " +
                     "WHERE t1.type = ? AND tr.relationship_type = ? AND t2.type = ? " +
                     "ORDER BY t1.external_id, t2.external_id LIMIT " + MAX_ROWS;

        JSONArray rows = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fromType != null ? fromType.trim() : "");
            ps.setString(2, relType != null ? relType.trim() : "");
            ps.setString(3, toType != null ? toType.trim() : "");

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject row = new JSONObject();
                    row.put("from_external_id", rs.getString("from_ext"));
                    row.put("from_name",        rs.getString("from_name"));
                    row.put("from_type",        rs.getString("from_type"));
                    row.put("to_external_id",   rs.getString("to_ext"));
                    row.put("to_name",          rs.getString("to_name"));
                    row.put("to_type",          rs.getString("to_type"));
                    String metaStr = rs.getString("meta");
                    if (metaStr != null && !metaStr.isBlank()) {
                        row.put("metadata", org.json.simple.JSONValue.parse(metaStr));
                    } else {
                        row.put("metadata", new JSONObject());
                    }
                    rows.add(row);
                }
            }
        }

        JSONObject out = new JSONObject();
        out.put("success", true);
        out.put("count",   rows.size());
        out.put("rows",    rows);
        OutputProcessor.send(res, 200, out);
    }

    @Override public void post(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
