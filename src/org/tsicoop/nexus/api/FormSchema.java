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

/**
 * TSI Nexus: Interaction Schema (Form) Management
 *
 * GET  /api/schemas → all schemas + stats
 * POST /api/schemas { action:"upsert", schema_id, label, applies_to, action_type, fields, state_patch, stream_tmpl }
 * POST /api/schemas { action:"toggle", schema_id }
 * POST /api/schemas { action:"delete", schema_id }
 */
public class FormSchema implements Action {

    /* ── GET ─────────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONArray schemas = new JSONArray();
            long total = 0, active = 0;

            String sql = "SELECT schema_id, label, applies_to, action_type, fields, state_patch, stream_tmpl, is_active, " +
                         "to_char(created_at, 'DD Mon YYYY') AS created_fmt " +
                         "FROM interaction_schema ORDER BY created_at ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                JSONParser parser = new JSONParser();
                while (rs.next()) {
                    JSONObject s = new JSONObject();
                    s.put("schema_id",   rs.getString("schema_id"));
                    s.put("label",       rs.getString("label"));
                    s.put("applies_to",  rs.getString("applies_to"));
                    s.put("action_type", rs.getString("action_type"));
                    s.put("fields",      parser.parse(rs.getString("fields")));
                    String rawPatch = rs.getString("state_patch");
                    s.put("state_patch", rawPatch != null ? parser.parse(rawPatch) : new JSONObject());
                    s.put("stream_tmpl", rs.getString("stream_tmpl"));
                    s.put("is_active",   rs.getBoolean("is_active"));
                    s.put("created",     rs.getString("created_fmt"));
                    schemas.add(s);
                    total++;
                    if (rs.getBoolean("is_active")) active++;
                }
            }

            JSONObject stats = new JSONObject();
            stats.put("total",    total);
            stats.put("active",   active);
            stats.put("inactive", total - active);

            JSONObject out = new JSONObject();
            out.put("success", true);
            out.put("schemas", schemas);
            out.put("stats",   stats);
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
                case "upsert":    upsertSchema(conn, req, res, input);  break;
                case "toggle":    toggleSchema(conn, req, res, input);  break;
                case "delete":    deleteSchema(conn, req, res, input);  break;
                case "generate":  generateSchema(req, res, input);      break;
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
    private void upsertSchema(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String schemaId   = str(in, "schema_id").toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        String label      = str(in, "label");
        String appliesTo  = str(in, "applies_to");
        String actionType = str(in, "action_type").toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        String streamTmpl = str(in, "stream_tmpl");

        Object fieldsObj  = in.getOrDefault("fields",      new JSONArray());
        Object patchObj   = in.getOrDefault("state_patch", new JSONObject());
        String fieldsJson = fieldsObj instanceof JSONArray  ? ((JSONArray)  fieldsObj).toJSONString() : "[]";
        String patchJson  = patchObj  instanceof JSONObject ? ((JSONObject) patchObj).toJSONString()  : "{}";

        if (schemaId.isEmpty() || label.isEmpty() || appliesTo.isEmpty() || actionType.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request",
                "schema_id, label, applies_to and action_type are required", req.getRequestURI()); return;
        }

        String sql = "INSERT INTO interaction_schema (schema_id, label, applies_to, action_type, fields, state_patch, stream_tmpl) " +
                     "VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) " +
                     "ON CONFLICT (schema_id) DO UPDATE SET label=EXCLUDED.label, applies_to=EXCLUDED.applies_to, " +
                     "action_type=EXCLUDED.action_type, fields=EXCLUDED.fields, " +
                     "state_patch=EXCLUDED.state_patch, stream_tmpl=EXCLUDED.stream_tmpl";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaId);
            ps.setString(2, label);
            ps.setString(3, appliesTo);
            ps.setString(4, actionType);
            ps.setString(5, fieldsJson);
            ps.setString(6, patchJson);
            ps.setString(7, streamTmpl);
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success",   true);
        result.put("schema_id", schemaId);
        OutputProcessor.send(res, 200, result);
    }

    /* ── toggle ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void toggleSchema(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String schemaId = str(in, "schema_id");
        if (schemaId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "schema_id is required", req.getRequestURI()); return;
        }

        boolean current = false;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT is_active FROM interaction_schema WHERE schema_id = ?")) {
            ps.setString(1, schemaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { OutputProcessor.errorResponse(res, 404, "Not found", schemaId, req.getRequestURI()); return; }
                current = rs.getBoolean("is_active");
            }
        }

        boolean newState = !current;
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE interaction_schema SET is_active = ? WHERE schema_id = ?")) {
            ps.setBoolean(1, newState);
            ps.setString(2, schemaId);
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success",   true);
        result.put("is_active", newState);
        OutputProcessor.send(res, 200, result);
    }

    /* ── delete ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void deleteSchema(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String schemaId = str(in, "schema_id");
        if (schemaId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "schema_id is required", req.getRequestURI()); return;
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM interaction_schema WHERE schema_id = ?")) {
            ps.setString(1, schemaId);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", schemaId, req.getRequestURI()); return;
            }
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── generate ────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void generateSchema(HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String prompt     = str(in, "prompt");
        String entityType = str(in, "entity_type");
        String attributes = str(in, "attributes");

        if (prompt.isEmpty() || entityType.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "prompt and entity_type are required", req.getRequestURI());
            return;
        }

        JSONObject generated = Intelligence.generateSchema(prompt, entityType, attributes);
        if (generated == null) {
            OutputProcessor.errorResponse(res, 500, "AI Error", "Failed to generate schema", req.getRequestURI());
            return;
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("schema",  generated);
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
