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
import java.util.ArrayList;
import java.util.List;

/**
 * TSI Nexus: Institutional Audit Trail
 *
 * GET /api/audit → paginated action_audit_log with actor + policy join
 *   ?page=1
 *   ?limit=50  (max 200)
 *   ?actor=@handle    filter by digital_twins.external_id
 *   ?policy_id=X      filter by policy_manifest.policy_id
 *   ?from=YYYY-MM-DD
 *   ?to=YYYY-MM-DD
 *   ?search=text      ILIKE on intent_raw
 */
public class Audit implements Action {

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            /* ── parse params ─────────────────────────────────────────── */
            int page  = parseIntParam(req.getParameter("page"),  1);
            int limit = parseIntParam(req.getParameter("limit"), 50);
            if (page  < 1)   page  = 1;
            if (limit < 1)   limit = 1;
            if (limit > 200) limit = 200;

            String actorParam    = req.getParameter("actor");
            String policyParam   = req.getParameter("policy_id");
            String fromParam     = req.getParameter("from");
            String toParam       = req.getParameter("to");
            String searchParam   = req.getParameter("search");

            String actorFilter = null;
            if (actorParam != null && !actorParam.trim().isEmpty())
                actorFilter = actorParam.trim().replaceAll("^@+", "");

            /* ── dynamic WHERE ────────────────────────────────────────── */
            List<String> conditions = new ArrayList<>();
            List<Object> params     = new ArrayList<>();

            if (actorFilter != null && !actorFilter.isEmpty()) {
                conditions.add("dt.external_id = ?");
                params.add(actorFilter);
            }
            if (policyParam != null && !policyParam.trim().isEmpty()) {
                conditions.add("aal.policy_id = ?");
                params.add(policyParam.trim());
            }
            if (fromParam != null && !fromParam.trim().isEmpty()) {
                conditions.add("aal.created_at >= ?::date");
                params.add(fromParam.trim());
            }
            if (toParam != null && !toParam.trim().isEmpty()) {
                conditions.add("aal.created_at < (?::date + INTERVAL '1 day')");
                params.add(toParam.trim());
            }
            if (searchParam != null && !searchParam.trim().isEmpty()) {
                conditions.add("aal.intent_raw ILIKE ?");
                params.add("%" + searchParam.trim() + "%");
            }

            String where  = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
            int    offset = (page - 1) * limit;

            /* ── main query ───────────────────────────────────────────── */
            String sql =
                "SELECT aal.audit_id::text, " +
                "aal.actor_id::text, " +
                "COALESCE(dt.external_id, aal.action_executed->>'entity') AS external_id, " +
                "dt.type AS entity_type, " +
                "COALESCE(dt.current_state->>'name', dt2.current_state->>'name') AS actor_name, " +
                "aal.intent_raw, " +
                "COALESCE(aal.action_executed::text, '{}') AS action_executed, " +
                "aal.policy_id, pm.action_type AS policy_label, " +
                "to_char(aal.created_at, 'DD Mon YYYY') AS created_fmt, " +
                "to_char(aal.created_at, 'HH24:MI:SS')  AS created_time " +
                "FROM action_audit_log aal " +
                "LEFT JOIN digital_twins dt  ON dt.id = aal.actor_id " +
                "LEFT JOIN digital_twins dt2 ON dt2.external_id = aal.action_executed->>'entity' " +
                "LEFT JOIN policy_manifest pm ON pm.policy_id = aal.policy_id " +
                where + " " +
                "ORDER BY aal.created_at DESC " +
                "LIMIT ? OFFSET ?";

            JSONArray entries = new JSONArray();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                for (Object p : params) ps.setObject(idx++, p);
                ps.setInt(idx++, limit);
                ps.setInt(idx,   offset);

                JSONParser parser = new JSONParser();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JSONObject e = new JSONObject();
                        e.put("audit_id",     rs.getString("audit_id"));
                        e.put("actor_id",     rs.getString("actor_id"));
                        e.put("external_id",  rs.getString("external_id"));
                        e.put("actor_name",   rs.getString("actor_name"));
                        e.put("entity_type",  rs.getString("entity_type"));
                        e.put("intent_raw",   rs.getString("intent_raw"));
                        e.put("policy_id",    rs.getString("policy_id"));
                        e.put("policy_label", rs.getString("policy_label"));
                        e.put("created_fmt",  rs.getString("created_fmt"));
                        e.put("created_time", rs.getString("created_time"));

                        // Parse action_executed and derive outcome
                        String actionStr = rs.getString("action_executed");
                        String outcome   = "unknown";
                        try {
                            JSONObject action = (JSONObject) parser.parse(actionStr);
                            e.put("action_executed", action);
                            Object sv = action.get("success");
                            if (Boolean.TRUE.equals(sv))  outcome = "success";
                            else if (Boolean.FALSE.equals(sv)) outcome = "denied";
                        } catch (Exception ignored) {
                            e.put("action_executed", new JSONObject());
                        }
                        e.put("outcome", outcome);
                        entries.add(e);
                    }
                }
            }

            /* ── stats (global, ignores page filters) ─────────────────── */
            JSONObject stats = new JSONObject();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) AS total, " +
                    "COUNT(*) FILTER (WHERE created_at >= CURRENT_DATE) AS today, " +
                    "COUNT(DISTINCT actor_id) AS actors " +
                    "FROM action_audit_log");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    stats.put("total",  rs.getLong("total"));
                    stats.put("today",  rs.getLong("today"));
                    stats.put("actors", rs.getLong("actors"));
                }
            }

            /* ── distinct policy ids for filter dropdown ──────────────── */
            JSONArray policies = new JSONArray();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT DISTINCT aal.policy_id, pm.action_type AS label " +
                    "FROM action_audit_log aal " +
                    "LEFT JOIN policy_manifest pm ON pm.policy_id = aal.policy_id " +
                    "WHERE aal.policy_id IS NOT NULL ORDER BY aal.policy_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject p = new JSONObject();
                    p.put("policy_id", rs.getString("policy_id"));
                    p.put("label",     rs.getString("label"));
                    policies.add(p);
                }
            }

            JSONObject out = new JSONObject();
            out.put("success",  true);
            out.put("entries",  entries);
            out.put("stats",    stats);
            out.put("policies", policies);
            out.put("page",     page);
            out.put("limit",    limit);
            out.put("has_more", entries.size() == limit);
            OutputProcessor.send(res, 200, out);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Fetch failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    private int parseIntParam(String value, int def) {
        if (value == null || value.trim().isEmpty()) return def;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return def; }
    }

    @Override public void post(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
