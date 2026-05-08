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
 * TSI Nexus: Liquid Template Registry
 *
 * GET  /api/templates → all templates + stats
 * POST /api/templates { action:"upsert",  template_id?, name, entity_type, html_content, condition_sql }
 * POST /api/templates { action:"toggle",  template_id }
 * POST /api/templates { action:"delete",  template_id }
 */
public class Templates implements Action {

    /* ── GET ─────────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONArray templates = new JSONArray();
            long total = 0, active = 0;

            String sql = "SELECT template_id::text, name, entity_type, html_content, condition_sql, is_active, " +
                         "to_char(created_at, 'DD Mon YYYY') AS created_fmt, " +
                         "to_char(updated_at, 'DD Mon YYYY HH24:MI') AS updated_fmt " +
                         "FROM liquid_templates ORDER BY created_at ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject t = new JSONObject();
                    t.put("template_id",   rs.getString("template_id"));
                    t.put("name",          rs.getString("name"));
                    t.put("entity_type",   rs.getString("entity_type"));
                    t.put("html_content",  rs.getString("html_content"));
                    t.put("condition_sql", rs.getString("condition_sql"));
                    t.put("is_active",     rs.getBoolean("is_active"));
                    t.put("created",       rs.getString("created_fmt"));
                    t.put("updated",       rs.getString("updated_fmt"));
                    templates.add(t);
                    total++;
                    if (rs.getBoolean("is_active")) active++;
                }
            }

            JSONObject stats = new JSONObject();
            stats.put("total",    total);
            stats.put("active",   active);
            stats.put("inactive", total - active);

            JSONObject out = new JSONObject();
            out.put("success",   true);
            out.put("templates", templates);
            out.put("stats",     stats);
            OutputProcessor.send(res, 200, out);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Fetch failed", e.getMessage(), req.getRequestURI());
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
            conn = pool.getConnection();
            JSONObject input = InputProcessor.getInput(req);
            String action = str(input, "action");

            switch (action) {
                case "upsert": upsertTemplate(conn, req, res, input); break;
                case "toggle": toggleTemplate(conn, req, res, input); break;
                case "delete": deleteTemplate(conn, req, res, input); break;
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
    private void upsertTemplate(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String templateId   = str(in, "template_id");
        String name         = str(in, "name");
        String entityType   = str(in, "entity_type");
        String htmlContent  = str(in, "html_content");
        String conditionSql = str(in, "condition_sql");

        if (name.isEmpty() || entityType.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "name and entity_type are required", req.getRequestURI()); return;
        }

        String resultId;
        if (templateId.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO liquid_templates (name, entity_type, html_content, condition_sql) " +
                    "VALUES (?, ?, ?, ?) RETURNING template_id::text")) {
                ps.setString(1, name);
                ps.setString(2, entityType);
                ps.setString(3, htmlContent);
                ps.setString(4, conditionSql);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); resultId = rs.getString(1); }
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE liquid_templates SET name=?, entity_type=?, html_content=?, condition_sql=?, updated_at=NOW() " +
                    "WHERE template_id=?::uuid RETURNING template_id::text")) {
                ps.setString(1, name);
                ps.setString(2, entityType);
                ps.setString(3, htmlContent);
                ps.setString(4, conditionSql);
                ps.setString(5, templateId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { OutputProcessor.errorResponse(res, 404, "Not found", templateId, req.getRequestURI()); return; }
                    resultId = rs.getString(1);
                }
            }
        }

        JSONObject result = new JSONObject();
        result.put("success",     true);
        result.put("template_id", resultId);
        OutputProcessor.send(res, 200, result);
    }

    /* ── toggle ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void toggleTemplate(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String templateId = str(in, "template_id");
        if (templateId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "template_id is required", req.getRequestURI()); return;
        }

        boolean current = false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT is_active FROM liquid_templates WHERE template_id = ?::uuid")) {
            ps.setString(1, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { OutputProcessor.errorResponse(res, 404, "Not found", templateId, req.getRequestURI()); return; }
                current = rs.getBoolean("is_active");
            }
        }

        boolean newState = !current;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE liquid_templates SET is_active=?, updated_at=NOW() WHERE template_id=?::uuid")) {
            ps.setBoolean(1, newState);
            ps.setString(2, templateId);
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success",   true);
        result.put("is_active", newState);
        OutputProcessor.send(res, 200, result);
    }

    /* ── delete ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void deleteTemplate(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String templateId = str(in, "template_id");
        if (templateId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "template_id is required", req.getRequestURI()); return;
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM liquid_templates WHERE template_id = ?::uuid")) {
            ps.setString(1, templateId);
            if (ps.executeUpdate() == 0) { OutputProcessor.errorResponse(res, 404, "Not found", templateId, req.getRequestURI()); return; }
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v != null ? v.toString().trim() : "";
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
