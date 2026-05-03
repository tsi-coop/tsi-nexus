package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.HttpClient;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

/**
 * Calls a vLLM-hosted model via its OpenAI-compatible /v1/chat/completions endpoint.
 * Configure via environment variables:
 *   VLLM_URL   — base URL of the vLLM server (e.g. http://192.168.1.10:8000)
 *   VLLM_MODEL — model name as registered in vLLM (e.g. google/gemma-3-12b-it)
 */
public class Intelligence {

    private static final String VLLM_URL;
    private static final String VLLM_MODEL;

    static {
        String url = System.getenv("VLLM_URL");
        String model = System.getenv("VLLM_MODEL");
        VLLM_URL = (url != null && !url.isEmpty()) ? url.replaceAll("/$", "") : null;
        VLLM_MODEL = (model != null && !model.isEmpty()) ? model : "google/gemma-3-12b-it";
    }

    private static final String SYSTEM_PROMPT =
        "You are an institutional intelligence engine for TSI Nexus, a microfinance cooperative platform. " +
        "You analyze portfolio metrics, member loan history, and field interaction logs to generate concise, " +
        "actionable narrative insights for field officers and branch managers. " +
        "Tone: professional and direct. Length: under 200 words. " +
        "Structure: one paragraph identifying the root cause, one sentence with a concrete recommendation. " +
        "Cite specific member IDs (e.g., @anita_02) and interaction events as evidence where relevant.";

    @SuppressWarnings("unchecked")
    public static String generateNarrative(String actionType, JSONArray metrics, JSONArray memberContexts) {
        if (VLLM_URL == null) return "";
        try {
            JSONArray messages = new JSONArray();

            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", SYSTEM_PROMPT);
            messages.add(sysMsg);

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", buildPrompt(actionType, metrics, memberContexts));
            messages.add(userMsg);

            JSONObject body = new JSONObject();
            body.put("model", VLLM_MODEL);
            body.put("messages", messages);
            body.put("max_tokens", 1024);
            body.put("temperature", 0.7);

            System.out.println("[Intelligence] POST " + VLLM_URL + "/v1/chat/completions model=" + VLLM_MODEL);
            HttpClient http = new HttpClient();
            JSONObject response = http.sendPost(
                VLLM_URL + "/v1/chat/completions",
                body,
                "Authorization", "Bearer dummy"
            );
            System.out.println("[Intelligence] response keys=" + response.keySet());

            // OpenAI-compatible response: choices[0].message.content
            JSONArray choices = (JSONArray) response.get("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject message = (JSONObject) ((JSONObject) choices.get(0)).get("message");
                if (message != null) {
                    String content = (String) message.get("content");
                    return content != null ? content.trim() : "";
                }
            }
            return "";
        } catch (Exception e) {
            System.err.println("[Intelligence] ERROR calling vLLM: " + e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    private static String buildPrompt(String actionType, JSONArray metrics, JSONArray memberContexts) {
        StringBuilder sb = new StringBuilder();

        if ("COMPARE".equalsIgnoreCase(actionType)) {
            sb.append("COMPARATIVE PERFORMANCE ANALYSIS\n\n");
            sb.append("Aggregated portfolio metrics per entity:\n");
        } else {
            sb.append("ENTITY PERFORMANCE ANALYSIS\n\n");
            sb.append("Portfolio metrics:\n");
        }

        for (Object m : metrics) {
            sb.append(m.toString()).append("\n");
        }

        if (memberContexts != null && !memberContexts.isEmpty()) {
            sb.append("\nMember-level data (state + recent interaction stream):\n");
            for (Object mc : memberContexts) {
                JSONObject member = (JSONObject) mc;
                sb.append("\n--- ").append(member.get("external_id")).append(" ---\n");
                sb.append("State: ").append(member.get("state")).append("\n");
                JSONArray interactions = (JSONArray) member.get("recent_interactions");
                if (interactions != null && !interactions.isEmpty()) {
                    sb.append("Recent interactions:\n");
                    for (Object i : interactions) {
                        JSONObject entry = (JSONObject) i;
                        sb.append("  [").append(entry.get("timestamp")).append("] ")
                          .append(entry.get("content")).append("\n");
                    }
                }
            }
        }

        if ("COMPARE".equalsIgnoreCase(actionType)) {
            sb.append("\nExplain WHY these entities show different performance figures. " +
                      "Cite specific member cases and interaction events as evidence. " +
                      "End with one concrete recommendation for the underperforming entity.");
        } else {
            sb.append("\nProvide a performance assessment. Highlight any risks or opportunities " +
                      "visible in the member data and interaction history.");
        }

        return sb.toString();
    }
}
