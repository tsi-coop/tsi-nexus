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
                case "upsert":      upsertTemplate(conn, req, res, input);   break;
                case "toggle":      toggleTemplate(conn, req, res, input);    break;
                case "delete":      deleteTemplate(conn, req, res, input);    break;
                case "generate":    generateTemplate(conn, req, res, input);  break;
                case "get_samples": getSamples(conn, req, res, input);        break;
                case "preview":     previewTemplate(conn, req, res, input);   break;
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

    /* ── generate ───────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void generateTemplate(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String prompt     = str(in, "prompt");
        String entityType = str(in, "entity_type");
        String attributes = str(in, "attributes"); // Optional list of attributes for context

        if (prompt.isEmpty() || entityType.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "prompt and entity_type are required", req.getRequestURI());
            return;
        }

        JSONObject generated = Intelligence.generateTemplate(prompt, entityType, attributes);
        if (generated == null) {
            OutputProcessor.errorResponse(res, 500, "AI Error", "Failed to generate template", req.getRequestURI());
            return;
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("template", generated);
        OutputProcessor.send(res, 200, result);
    }

    /* ── preview ────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void getSamples(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String type = str(in, "entity_type");
        if (type.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "entity_type is required", req.getRequestURI());
            return;
        }

        JSONArray samples = new JSONArray();
        String sql = "SELECT external_id, current_state->>'name' as name FROM digital_twins WHERE type = ? LIMIT 5";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject s = new JSONObject();
                    s.put("external_id", rs.getString("external_id"));
                    s.put("name", rs.getString("name"));
                    samples.add(s);
                }
            }
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("samples", samples);
        OutputProcessor.send(res, 200, result);
    }

    @SuppressWarnings("unchecked")
    private void previewTemplate(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String externalId = str(in, "external_id");
        if (externalId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "external_id is required", req.getRequestURI());
            return;
        }

        // Fetch the full context for the entity
        Context contextResolver = new Context();
        // Context.assembleFullContext is private, but we can call the action's logic or refactor it.
        // Actually, Context.post already sends a response.
        // I'll just use the same logic here or call a helper if I move it.
        // For now, I'll just fetch the context manually to be safe and simple.
        
        JSONObject context = fetchContext(conn, externalId);
        if (context == null) {
            OutputProcessor.errorResponse(res, 404, "Not found", "Entity context not found", req.getRequestURI());
            return;
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("context", context);
        OutputProcessor.send(res, 200, result);
    }

    @SuppressWarnings("unchecked")
    private JSONObject fetchContext(Connection conn, String externalId) throws Exception {
        String cleanId = externalId.startsWith("@") ? externalId.substring(1) : externalId;
        String sql = "SELECT type, current_state FROM digital_twins WHERE external_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cleanId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    JSONObject ctx = new JSONObject();
                    ctx.put("external_id", externalId);
                    ctx.put("type", rs.getString("type"));
                    ctx.put("state", org.json.simple.JSONValue.parse(rs.getString("current_state")));
                    return ctx;
                }
            }
        }
        return null;
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
