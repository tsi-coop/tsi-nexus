package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

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
 * GET  /api/tuning  → all configured purposes + vLLM connectivity status
 * POST /api/tuning  { action:"upsert",  purpose_key, assigned_model, system_prompt, latency_target_ms }
 * POST /api/tuning  { action:"delete",  purpose_key }
 * POST /api/tuning  { action:"test",    purpose_key, test_input }
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

            JSONArray purposes = new JSONArray();
            String sql = "SELECT tuning_id::text, purpose_key, assigned_model, system_prompt, " +
                         "COALESCE(latency_target_ms, 3000) AS latency_target_ms, is_active " +
                         "FROM intelligence_tuning ORDER BY purpose_key";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject p = new JSONObject();
                    p.put("tuning_id",        rs.getString("tuning_id"));
                    p.put("purpose_key",       rs.getString("purpose_key"));
                    p.put("assigned_model",    rs.getString("assigned_model"));
                    p.put("system_prompt",     rs.getString("system_prompt"));
                    p.put("latency_target_ms", rs.getInt("latency_target_ms"));
                    p.put("is_active",         rs.getBoolean("is_active"));
                    purposes.add(p);
                }
            }

            String vllmUrl   = System.getenv("VLLM_URL");
            String vllmModel = System.getenv("VLLM_MODEL");
            boolean online   = false;
            if (vllmUrl != null && !vllmUrl.isBlank()) {
                online = pingVllm(vllmUrl.replaceAll("/$", ""));
            }

            JSONObject out = new JSONObject();
            out.put("success",     true);
            out.put("purposes",    purposes);
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
                case "upsert": upsert(conn, req, res, input);        break;
                case "delete": deletePurpose(conn, req, res, input); break;
                case "test":   testPurpose(conn, req, res, input);   break;
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
    private void upsert(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String purposeKey = str(in, "purpose_key").toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        String model      = str(in, "assigned_model");
        String prompt     = str(in, "system_prompt");
        String latStr     = str(in, "latency_target_ms");
        int    latency    = latStr.isEmpty() ? 3000 : Integer.parseInt(latStr);

        if (purposeKey.isEmpty() || model.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request",
                "purpose_key and assigned_model are required", req.getRequestURI()); return;
        }

        String sql =
            "INSERT INTO intelligence_tuning (purpose_key, assigned_model, system_prompt, latency_target_ms, is_active) " +
            "VALUES (?, ?, ?, ?, TRUE) " +
            "ON CONFLICT (purpose_key) DO UPDATE SET " +
            "  assigned_model = EXCLUDED.assigned_model, " +
            "  system_prompt = EXCLUDED.system_prompt, " +
            "  latency_target_ms = EXCLUDED.latency_target_ms";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, purposeKey);
            ps.setString(2, model);
            ps.setString(3, prompt);
            ps.setInt(4, latency);
            ps.executeUpdate();
        }

        JSONObject result = new JSONObject();
        result.put("success",     true);
        result.put("purpose_key", purposeKey);
        OutputProcessor.send(res, 200, result);
    }

    /* ── delete ──────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void deletePurpose(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String purposeKey = str(in, "purpose_key");
        if (purposeKey.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "purpose_key is required", req.getRequestURI()); return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM intelligence_tuning WHERE purpose_key = ?")) {
            ps.setString(1, purposeKey);
            if (ps.executeUpdate() == 0) {
                OutputProcessor.errorResponse(res, 404, "Not found", purposeKey, req.getRequestURI()); return;
            }
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        OutputProcessor.send(res, 200, result);
    }

    /* ── test ────────────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void testPurpose(Connection conn, HttpServletRequest req, HttpServletResponse res, JSONObject in) throws Exception {
        String purposeKey = str(in, "purpose_key");
        String testInput  = str(in, "test_input");

        if (purposeKey.isEmpty()) {
            OutputProcessor.errorResponse(res, 400, "Bad request", "purpose_key is required", req.getRequestURI()); return;
        }

        String vllmUrl = System.getenv("VLLM_URL");
        if (vllmUrl == null || vllmUrl.isBlank()) {
            OutputProcessor.errorResponse(res, 503, "Offline",
                "VLLM_URL is not configured — set it in docker-compose.yml", req.getRequestURI()); return;
        }
        vllmUrl = vllmUrl.replaceAll("/$", "");

        String envModel   = System.getenv("VLLM_MODEL");
        String model      = (envModel != null && !envModel.isBlank()) ? envModel : "google/gemma-3-12b-it";
        String sysPrompt  = "You are a helpful assistant. Respond concisely.";

        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT system_prompt, assigned_model FROM intelligence_tuning WHERE purpose_key = ?")) {
            ps.setString(1, purposeKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String sp = rs.getString("system_prompt");
                    String am = rs.getString("assigned_model");
                    if (sp != null && !sp.isBlank()) sysPrompt = sp;
                    if (am != null && !am.isBlank()) model     = am;
                } else {
                    OutputProcessor.errorResponse(res, 404, "Not found", purposeKey, req.getRequestURI()); return;
                }
            }
        }

        String userContent = testInput.isEmpty()
            ? "Confirm you are operational and describe your assigned role in one sentence."
            : testInput;

        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject(); sys.put("role", "system"); sys.put("content", sysPrompt);     messages.add(sys);
        JSONObject usr = new JSONObject(); usr.put("role", "user");   usr.put("content", userContent);   messages.add(usr);

        JSONObject body = new JSONObject();
        body.put("model",       model);
        body.put("messages",    messages);
        body.put("max_tokens",  256);
        body.put("temperature", 0.3);

        long start = System.currentTimeMillis();
        HttpClient http = new HttpClient();
        JSONObject llmResp = http.sendPost(vllmUrl + "/v1/chat/completions", body, "Authorization", "Bearer dummy");
        long latencyMs = System.currentTimeMillis() - start;

        String content = "";
        try {
            JSONArray choices = (JSONArray) llmResp.get("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject msg = (JSONObject) ((JSONObject) choices.get(0)).get("message");
                if (msg != null) content = (String) msg.get("content");
            }
        } catch (Exception ignore) {}

        JSONObject result = new JSONObject();
        result.put("success",    true);
        result.put("response",   content != null ? content.trim() : "");
        result.put("latency_ms", latencyMs);
        result.put("model",      model);
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
