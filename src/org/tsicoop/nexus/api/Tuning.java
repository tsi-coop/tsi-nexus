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
 * TSI Nexus: Intelligence Setup API
 *
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
            StandardCommands.ensure(conn);

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
            StandardCommands.ensure(conn);

            switch (action) {
                case "add_term":         addTerm(conn, req, res, input);         break;
                case "delete_term":      deleteTerm(conn, req, res, input);      break;
                case "save_command":     saveCommand(conn, req, res, input);     break;
                case "delete_command":   deleteCommand(conn, req, res, input);   break;
                case "generate_commands": generateCommands(conn, req, res);      break;
                default: OutputProcessor.errorResponse(res, 400, "Bad request", "Unknown action: " + action, req.getRequestURI());
            }
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Operation failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── save command manifest entry ────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void saveCommand(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String verb     = str(in, "command_verb");
        String label    = str(in, "label");
        String hint     = str(in, "hint");
        String args     = str(in, "args_hint");
        String action   = str(in, "action_type");
        String comp     = str(in, "component_type");
        boolean multi   = bool(in, "multi_target");
        boolean value   = bool(in, "has_value");

        if (verb.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "command_verb is required", req.getRequestURI()); return;
        }

        String sql = "INSERT INTO command_manifest (command_verb, label, hint, args_hint, action_type, component_type, multi_target, has_value) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (command_verb) DO UPDATE SET " +
                     "label=EXCLUDED.label, hint=EXCLUDED.hint, args_hint=EXCLUDED.args_hint, " +
                     "action_type=EXCLUDED.action_type, component_type=EXCLUDED.component_type, " +
                     "multi_target=EXCLUDED.multi_target, has_value=EXCLUDED.has_value";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, verb);
            ps.setString(2, label);
            ps.setString(3, hint);
            ps.setString(4, args);
            ps.setString(5, action);
            ps.setString(6, comp);
            ps.setBoolean(7, multi);
            ps.setBoolean(8, value);
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── delete command manifest entry ───────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void deleteCommand(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String verb = str(in, "command_verb");
        if (verb.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "command_verb is required", req.getRequestURI()); return;
        }
        if (StandardCommands.isMandatory(verb)) {
            OutputProcessor.errorResponse(res, 400, "Protected command", "/" + verb + " is a mandatory standard command and cannot be deleted.", req.getRequestURI());
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM command_manifest WHERE command_verb = ?")) {
            ps.setString(1, verb);
            ps.executeUpdate();
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── load command manifest ───────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private JSONArray loadCommands(Connection conn) throws Exception {
        JSONArray arr = new JSONArray();
        String sql = "SELECT command_verb, label, hint, args_hint, action_type, component_type, multi_target, has_value " +
                     "FROM command_manifest ORDER BY command_verb";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("command_verb",   rs.getString("command_verb"));
                o.put("label",          rs.getString("label"));
                o.put("hint",           rs.getString("hint"));
                o.put("args_hint",      rs.getString("args_hint"));
                o.put("action_type",    rs.getString("action_type"));
                o.put("component_type", rs.getString("component_type"));
                o.put("multi_target",   rs.getBoolean("multi_target"));
                o.put("has_value",      rs.getBoolean("has_value"));
                arr.add(o);
            }
        }
        return arr;
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

    @SuppressWarnings("unchecked")
    private void generateCommands(Connection conn, HttpServletRequest req, HttpServletResponse res) throws Exception {
        String vllmUrl   = System.getenv("VLLM_URL");
        String vllmModel = System.getenv("VLLM_MODEL");
        if (vllmUrl == null || vllmModel == null) {
            throw new IllegalStateException("AI engine not configured");
        }

        // Fetch context: org config + vocab + entity types
        String orgContext = "";
        try (PreparedStatement ps = conn.prepareStatement("SELECT name, config FROM root_organisation LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) orgContext = rs.getString("name") + " " + rs.getString("config");
        }
        
        JSONObject vocab = loadVocab(conn);
        StringBuilder types = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT type FROM digital_twins WHERE type != 'system'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) types.append(rs.getString("type")).append(", ");
        }

        String prompt =
            "You are the institutional intelligence architect for a management platform.\n\n" +
            "ORGANISATION CONTEXT: " + orgContext + "\n" +
            "INSTITUTIONAL VOCABULARY: " + vocab.toJSONString() + "\n" +
            "EXISTING ENTITY TYPES: " + types.toString() + "\n\n" +
            "Generate 3-5 realistic institutional slash-commands for this organisation.\n" +
            "Rules:\n" +
            "- command_verb MUST be snake_case (e.g. disburse, collect, field_visit)\n" +
            "- label is human-readable title (e.g. Release Funds)\n" +
            "- args_hint shows usage (e.g. @target [amount])\n" +
            "- component_type is 'universal_action_confirm' or 'interaction_capture_form'\n" +
            "- action_type is uppercase verb for policy matching (e.g. DISBURSE)\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"commands\":[{\"command_verb\":\"...\",\"label\":\"...\",\"args_hint\":\"...\",\n" +
            "\"hint\":\"...\",\"component_type\":\"...\",\"action_type\":\"...\"},...]}";

        JSONObject generated = extractJson(callAI(prompt, vllmUrl, vllmModel));
        JSONArray  items     = (JSONArray) generated.get("commands");

        int count = 0;
        if (items != null) {
            for (Object obj : items) {
                JSONObject c = (JSONObject) obj;
                String verb  = str(c, "command_verb");
                if (verb.isEmpty()) continue;
                
                String sql = "INSERT INTO command_manifest (command_verb, label, hint, args_hint, action_type, component_type) " +
                             "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (command_verb) DO NOTHING";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, verb);
                    ps.setString(2, str(c, "label"));
                    ps.setString(3, str(c, "hint"));
                    ps.setString(4, str(c, "args_hint"));
                    ps.setString(5, str(c, "action_type").toUpperCase());
                    ps.setString(6, str(c, "component_type"));
                    count += ps.executeUpdate();
                }
            }
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("count",   count);
        OutputProcessor.send(res, 200, result);
    }

    /* ── AI calls ────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private String callAI(String prompt, String url, String model) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model",       model);
        body.put("temperature", 0.7);
        body.put("messages",    new JSONArray());
        
        JSONObject sys = new JSONObject();
        sys.put("role",    "system");
        sys.put("content", "You are a synthetic institutional data generator. Always respond with valid JSON only. No markdown, no explanation.");
        ((JSONArray)body.get("messages")).add(sys);

        JSONObject user = new JSONObject();
        user.put("role",    "user");
        user.put("content", prompt);
        ((JSONArray)body.get("messages")).add(user);

        HttpClient http = new HttpClient();
        JSONObject response = http.sendPost(url.replaceAll("/$", "") + "/v1/chat/completions", body, "Authorization", "Bearer dummy");
        JSONArray  choices  = (JSONArray) response.get("choices");
        if (choices == null || choices.isEmpty()) throw new RuntimeException("AI returned no choices");
        return (String) ((JSONObject) ((JSONObject) choices.get(0)).get("message")).get("content");
    }

    private JSONObject extractJson(String content) throws Exception {
        String s = content.trim();
        if (s.contains("```")) {
            int first = s.indexOf("```");
            int second = s.indexOf("```", first + 3);
            if (second > first) s = s.substring(first + 3, second).trim();
            if (s.startsWith("json")) s = s.substring(4).trim();
        }
        int start = s.indexOf('{');
        int end   = s.lastIndexOf('}');
        return (JSONObject) new JSONParser().parse(s.substring(start, end + 1));
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

    private boolean bool(JSONObject o, String key) {
        Object v = o.get(key);
        return v instanceof Boolean ? (Boolean) v : false;
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
