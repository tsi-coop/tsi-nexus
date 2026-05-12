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
                case "add_term":    addTerm(conn, req, res, input);    break;
                case "delete_term": deleteTerm(conn, req, res, input); break;
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

    /* ── load standard commands ──────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private JSONArray loadCommands(Connection conn) {
        JSONArray arr = new JSONArray();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT command_verb, label, hint, args_hint, action_type, component_type " +
                "FROM command_manifest WHERE is_active = TRUE ORDER BY command_verb");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject o = new JSONObject();
                o.put("command_verb",   rs.getString("command_verb"));
                o.put("label",          rs.getString("label"));
                o.put("hint",           rs.getString("hint"));
                o.put("args_hint",      rs.getString("args_hint"));
                o.put("action_type",    rs.getString("action_type"));
                o.put("component_type", rs.getString("component_type"));
                arr.add(o);
            }
        } catch (Exception ignore) {}
        return arr;
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
