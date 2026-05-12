package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;

/**
 * GET  /api/tuning  → vLLM connectivity status + institutional vocabulary
 * POST /api/tuning  { action:"add_term",    term, definition }
 * POST /api/tuning  { action:"delete_term", term }
 */
public class Tuning implements Action {

    /* ── GET ─────────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            String vllmUrl   = System.getenv("VLLM_URL");
            String vllmModel = System.getenv("VLLM_MODEL");
            boolean online   = false;
            if (vllmUrl != null && !vllmUrl.isBlank()) {
                online = pingVllm(vllmUrl.replaceAll("/$", ""));
            }

            JSONObject out = new JSONObject();
            out.put("success",     true);
            out.put("vocab",       loadVocab(conn));
            out.put("commands",    loadCommands(conn));
            out.put("templates",   loadTemplates(conn));
            out.put("schemas",     loadSchemas(conn));
            out.put("vllm_online", online);
            out.put("vllm_url",    vllmUrl   != null ? vllmUrl   : "");
            out.put("vllm_model",  vllmModel != null ? vllmModel : "");
            OutputProcessor.send(res, 200, out);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Tuning fetch failed", e.getMessage(), req.getRequestURI());
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
            JSONObject input = InputProcessor.getInput(req);
            String action = str(input, "action");
            conn = pool.getConnection();

            switch (action) {
                case "add_term":      addTerm(conn, req, res, input);      break;
                case "delete_term":   deleteTerm(conn, req, res, input);   break;
                case "add_command":   addCommand(conn, req, res, input);   break;
                case "link_command":  linkCommand(conn, req, res, input);  break;
                case "delete_command":deleteCommand(conn, req, res, input);break;
                case "add_policy":    addPolicy(conn, req, res, input);    break;
                case "delete_policy": deletePolicy(conn, req, res, input); break;
                case "toggle_policy": togglePolicy(conn, req, res, input); break;
                default: OutputProcessor.errorResponse(res, 400, "Bad request", "Unknown action: " + action, req.getRequestURI());
            }
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Operation failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── add / update vocabulary term ───────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void addTerm(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String term = str(in, "term");
        String def  = str(in, "definition");
        if (term.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "term is required", req.getRequestURI()); return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE root_organisation SET domain_slang = domain_slang || jsonb_build_object(?::text, ?::text)")) {
            ps.setString(1, term);
            ps.setString(2, def);
            ps.executeUpdate();
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("term", term);
        OutputProcessor.send(res, 200, result);
    }

    /* ── delete vocabulary term ──────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void deleteTerm(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String term = str(in, "term");
        if (term.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "term is required", req.getRequestURI()); return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE root_organisation SET domain_slang = domain_slang - ?")) {
            ps.setString(1, term);
            ps.executeUpdate();
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── load vocab from org ─────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private JSONObject loadVocab(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT domain_slang FROM root_organisation LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String raw = rs.getString("domain_slang");
                if (raw != null) {
                    Object parsed = new JSONParser().parse(raw);
                    if (parsed instanceof JSONObject) return (JSONObject) parsed;
                }
            }
        } catch (Exception ignore) {}
        return new JSONObject();
    }

    /* ── load institution commands ───────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private JSONArray loadCommands(Connection conn) {
        JSONArray arr = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT command_id::text, command_verb, label, hint, args_hint, action_type, entity_type, component_type, " +
                "linked_template::text, linked_form " +
                "FROM command_manifest WHERE is_active = TRUE ORDER BY command_verb");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("command_id",     rs.getString("command_id"));
                o.put("command_verb",   rs.getString("command_verb"));
                o.put("label",          rs.getString("label"));
                o.put("hint",           rs.getString("hint"));
                o.put("args_hint",      rs.getString("args_hint"));
                o.put("action_type",    rs.getString("action_type"));
                o.put("entity_type",    rs.getString("entity_type"));
                o.put("component_type", rs.getString("component_type"));
                String lt = rs.getString("linked_template");
                if (lt != null) o.put("linked_template", lt);
                String lf = rs.getString("linked_form");
                if (lf != null) o.put("linked_form", lf);
                o.put("policies", loadPoliciesForAction(conn, rs.getString("action_type")));
                arr.add(o);
            }
        } catch (Exception ignore) {}
        return arr;
    }

    @SuppressWarnings("unchecked")
    private JSONArray loadPoliciesForAction(Connection conn, String actionType) {
        JSONArray arr = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT policy_id, description, is_active FROM policy_manifest WHERE action_type=? ORDER BY policy_id")) {
            ps.setString(1, actionType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject p = new JSONObject();
                    p.put("policy_id",   rs.getString("policy_id"));
                    p.put("description", rs.getString("description"));
                    p.put("is_active",   rs.getBoolean("is_active"));
                    arr.add(p);
                }
            }
        } catch (Exception ignore) {}
        return arr;
    }

    /* ── load templates list ─────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private JSONArray loadTemplates(Connection conn) {
        JSONArray arr = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT template_id::text, name, entity_type FROM liquid_templates WHERE is_active=TRUE ORDER BY name");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("template_id",  rs.getString("template_id"));
                o.put("name",         rs.getString("name"));
                o.put("entity_type",  rs.getString("entity_type"));
                arr.add(o);
            }
        } catch (Exception ignore) {}
        return arr;
    }

    /* ── load schemas list ───────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private JSONArray loadSchemas(Connection conn) {
        JSONArray arr = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT schema_id, label, action_type FROM interaction_schema WHERE is_active=TRUE ORDER BY label");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("schema_id",   rs.getString("schema_id"));
                o.put("label",       rs.getString("label"));
                o.put("action_type", rs.getString("action_type"));
                arr.add(o);
            }
        } catch (Exception ignore) {}
        return arr;
    }

    /* ── add command ─────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void addCommand(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String verb          = str(in, "command_verb").toLowerCase().replaceAll("[^a-z0-9_]", "");
        String label         = str(in, "label");
        String actionType    = str(in, "action_type").toUpperCase();
        String componentType = str(in, "component_type");
        String hint          = str(in, "hint");
        String argsHint      = str(in, "args_hint");
        String linkedForm    = str(in, "linked_form");
        String linkedTemplate = str(in, "linked_template");

        if (verb.isEmpty() || label.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "command_verb and label are required", req.getRequestURI()); return;
        }
        if (linkedForm.isEmpty() && linkedTemplate.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "At least one of linked_form or linked_template is required", req.getRequestURI()); return;
        }
        if (actionType.isEmpty()) actionType = verb.toUpperCase();
        if (componentType.isEmpty()) componentType = linkedForm.isEmpty() ? "universal_action_confirm" : "interaction_capture_form";

        String entityType = str(in, "entity_type").toLowerCase();

        String sql = "INSERT INTO command_manifest (command_verb, label, action_type, entity_type, component_type, hint, args_hint, linked_form, linked_template) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::uuid) " +
                     "ON CONFLICT (command_verb) DO UPDATE SET label=EXCLUDED.label, action_type=EXCLUDED.action_type, " +
                     "entity_type=EXCLUDED.entity_type, component_type=EXCLUDED.component_type, hint=EXCLUDED.hint, args_hint=EXCLUDED.args_hint, " +
                     "linked_form=EXCLUDED.linked_form, linked_template=EXCLUDED.linked_template RETURNING command_id::text";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, verb);
            ps.setString(2, label);
            ps.setString(3, actionType);
            ps.setString(4, entityType);
            ps.setString(5, componentType);
            ps.setString(6, hint);
            ps.setString(7, argsHint);
            ps.setString(8, linkedForm.isEmpty() ? null : linkedForm);
            ps.setString(9, linkedTemplate.isEmpty() ? null : linkedTemplate);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); }
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("command_verb", verb);
        OutputProcessor.send(res, 200, result);
    }

    /* ── link command to form/template ──────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void linkCommand(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String verb           = str(in, "command_verb");
        String linkedForm     = str(in, "linked_form");
        String linkedTemplate = str(in, "linked_template");
        if (verb.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "command_verb is required", req.getRequestURI()); return;
        }
        String sql = "UPDATE command_manifest SET " +
                     "linked_form=?, linked_template=?::uuid WHERE command_verb=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, linkedForm.isEmpty() ? null : linkedForm);
            ps.setString(2, linkedTemplate.isEmpty() ? null : linkedTemplate);
            ps.setString(3, verb);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", "Command not found: " + verb, req.getRequestURI()); return;
            }
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── delete command ──────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void deleteCommand(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String verb = str(in, "command_verb");
        if (verb.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "command_verb is required", req.getRequestURI()); return;
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM command_manifest WHERE command_verb=?")) {
            ps.setString(1, verb);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", "Command not found: " + verb, req.getRequestURI()); return;
            }
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── policy management ───────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void addPolicy(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String policyId     = str(in, "policy_id").toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        String actionType   = str(in, "action_type").toUpperCase();
        String description  = str(in, "description");
        String queryLogic   = str(in, "query_logic");
        String errorMessage = str(in, "error_message");
        String execMode     = str(in, "execution_mode");
        if (execMode.isEmpty()) execMode = "GUARDRAIL";

        if (policyId.isEmpty() || actionType.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "policy_id and action_type are required", req.getRequestURI()); return;
        }
        if (queryLogic.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "query_logic is required", req.getRequestURI()); return;
        }

        String sql = "INSERT INTO policy_manifest (policy_id, action_type, description, query_logic, error_message, execution_mode, is_active) " +
                     "VALUES (?, ?, ?, ?, ?, ?, TRUE) " +
                     "ON CONFLICT (policy_id) DO UPDATE SET action_type=EXCLUDED.action_type, description=EXCLUDED.description, " +
                     "query_logic=EXCLUDED.query_logic, error_message=EXCLUDED.error_message, execution_mode=EXCLUDED.execution_mode";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, policyId);
            ps.setString(2, actionType);
            ps.setString(3, description);
            ps.setString(4, queryLogic);
            ps.setString(5, errorMessage);
            ps.setString(6, execMode);
            ps.executeUpdate();
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("policy_id", policyId);
        OutputProcessor.send(res, 200, result);
    }

    @SuppressWarnings("unchecked")
    private void deletePolicy(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String policyId = str(in, "policy_id");
        if (policyId.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "policy_id is required", req.getRequestURI()); return;
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM policy_manifest WHERE policy_id=?")) {
            ps.setString(1, policyId);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", "Policy not found: " + policyId, req.getRequestURI()); return;
            }
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    @SuppressWarnings("unchecked")
    private void togglePolicy(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String policyId = str(in, "policy_id");
        Object isActiveVal = in.get("is_active");
        if (policyId.isEmpty() || isActiveVal == null) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "policy_id and is_active are required", req.getRequestURI()); return;
        }
        boolean isActive = Boolean.parseBoolean(isActiveVal.toString());
        try (PreparedStatement ps = conn.prepareStatement("UPDATE policy_manifest SET is_active=? WHERE policy_id=?")) {
            ps.setBoolean(1, isActive);
            ps.setString(2, policyId);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", "Policy not found: " + policyId, req.getRequestURI()); return;
            }
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── helpers ─────────────────────────────────────────────────────────── */

    private boolean pingVllm(String baseUrl) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
            HttpRequest request = HttpRequest.newBuilder()
                .GET()
                .uri(URI.create(baseUrl + "/v1/models"))
                .timeout(Duration.ofSeconds(2))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private String str(JSONObject o, String key) {
        Object v = o.get(key);
        return v != null ? v.toString().trim() : "";
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
