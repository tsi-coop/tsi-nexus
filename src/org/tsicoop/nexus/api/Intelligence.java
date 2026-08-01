package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.HttpClient;
import org.tsicoop.nexus.framework.PoolDB;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Calls a vLLM-hosted model via its OpenAI-compatible /v1/chat/completions endpoint.
 * Configure via environment variables:
 *   VLLM_URL   — base URL of the vLLM server (e.g. http://192.168.1.10:8000)
 *   VLLM_MODEL — model name as registered in vLLM (e.g. google/gemma-4-E4B-it)
 */
public class Intelligence {

    private static final String VLLM_URL;
    private static final String VLLM_MODEL;

    static {
        String url = System.getenv("VLLM_URL");
        String model = System.getenv("VLLM_MODEL");
        VLLM_URL = (url != null && !url.isEmpty()) ? url.replaceAll("/$", "") : null;
        VLLM_MODEL = (model != null && !model.isEmpty()) ? model : null;
    }

    // Conversational Intelligence v0.2 - generic, institution-agnostic narrator used by
    // Commentary.java to explain any rendered Liquid card in plain English. Deliberately
    // carries no sector-specific language; institutional vocabulary (if configured) is the
    // only per-deployment customization, injected the same way as everywhere else in Nexus.
    private static final String CARD_NARRATIVE_PROMPT =
        "You are the institutional intelligence engine for TSI Nexus. You analyze entity " +
        "records, relationships, and interaction history to generate concise, actionable " +
        "narrative insights for the people using this platform. " +
        "Tone: professional and direct. Length: under 120 words. " +
        "Structure: one to two sentences identifying what matters most in the data, followed " +
        "by one sentence with a concrete takeaway or recommendation if the data supports one. " +
        "Cite specific entity IDs and evidence from the data provided where relevant. " +
        "Do not invent facts that are not present in the data provided.";

    /**
     * Generates a short plain-English narrative for a single rendered Liquid card
     * (context card, action confirmation, capture form, disambiguation, analytics
     * results, or audit narrative). cardType and payload are whatever the client
     * already has for that card - no additional data is fetched here.
     */
    @SuppressWarnings("unchecked")
    public static String generateCardNarrative(String cardType, JSONObject payload) {
        if (VLLM_URL == null || VLLM_MODEL == null) return "";
        try {
            String vocab = loadVocabSection();
            String systemPrompt = CARD_NARRATIVE_PROMPT +
                (vocab.isEmpty() ? "" : "\n\nInstitutional vocabulary:\n" + vocab);

            JSONArray messages = new JSONArray();

            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", "Card type: " + cardType + "\n\nData:\n" +
                (payload != null ? payload.toJSONString() : "{}"));
            messages.add(userMsg);

            JSONObject body = new JSONObject();
            body.put("model", VLLM_MODEL);
            body.put("messages", messages);
            // Reasoning models can spend much of the budget on reasoning_content before ever
            // emitting the narrative itself - keep this generous.
            body.put("max_tokens", 900);
            body.put("temperature", 0.4);

            System.out.println("[Intelligence] generateCardNarrative POST " + VLLM_URL + "/v1/chat/completions model=" + VLLM_MODEL + " cardType=" + cardType);
            HttpClient http = new HttpClient();
            JSONObject response = http.sendPost(
                VLLM_URL + "/v1/chat/completions",
                body,
                "Authorization", "Bearer dummy"
            );

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
            System.err.println("[Intelligence] generateCardNarrative ERROR calling vLLM: " + e.getMessage());
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    public static JSONObject generateTemplate(String userPrompt, String entityType, String attributes) {
        if (VLLM_URL == null || VLLM_MODEL == null) return null;
        try {
            String vocab = loadVocabSection();
            String systemPrompt =
                "You are an expert UI developer for TSI Nexus, an institutional intelligence platform. " +
                "Generate a Liquid template with Tailwind CSS based on the user's description.\n\n" +
                "CONTEXT:\n" +
                "- Entity Type: " + entityType + "\n" +
                "- Available Attributes: " + attributes + "\n" +
                (vocab.isEmpty() ? "" : "- Institutional vocabulary:\n" + vocab + "\n") + "\n" +
                "RULES:\n" +
                "- Use Tailwind CSS for styling.\n" +
                "- Use Liquid syntax for variables (e.g., {{ entity.name }}, {{ entity.current_state.attribute_name }}).\n" +
                "- Return ONLY a JSON object with 'name' and 'html_content'.\n" +
                "- 'name' should be a concise title for the template.\n" +
                "- 'html_content' should be the full Liquid template.\n" +
                "- CRITICAL: Use single quotes for ALL HTML element attributes (e.g., class='p-4' not class=\"p-4\"). This prevents JSON escaping issues.\n" +
                "- Do not include markdown formatting in the response.";

            JSONArray messages = new JSONArray();
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);

            JSONObject body = new JSONObject();
            body.put("model", VLLM_MODEL);
            body.put("messages", messages);
            body.put("temperature", 0.7);

            HttpClient http = new HttpClient();
            JSONObject response = http.sendPost(VLLM_URL + "/v1/chat/completions", body, "Authorization", "Bearer dummy");

            JSONArray choices = (JSONArray) response.get("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject message = (JSONObject) ((JSONObject) choices.get(0)).get("message");
                if (message != null) {
                    String content = (String) message.get("content");
                    return extractJson(content);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static JSONObject generateSchema(String userPrompt, String entityType, String attributes) {
        if (VLLM_URL == null || VLLM_MODEL == null) return null;
        try {
            String vocab = loadVocabSection();
            String systemPrompt =
                "You are an expert form designer for TSI Nexus, an institutional intelligence platform. " +
                "Generate an interaction form schema based on the user's description.\n\n" +
                "CONTEXT:\n" +
                "- Entity Type: " + entityType + "\n" +
                "- Available State Attributes: " + (attributes.isEmpty() ? "none specified" : attributes) + "\n" +
                (vocab.isEmpty() ? "" : "- Institutional vocabulary:\n" + vocab + "\n") + "\n" +
                "Return ONLY a valid JSON object with exactly these fields:\n" +
                "{\n" +
                "  \"schema_id\": \"UPPER_SNAKE_CASE_ID\",\n" +
                "  \"label\": \"Human readable form title\",\n" +
                "  \"action_type\": \"UPPER_SNAKE_CASE_EVENT_TYPE\",\n" +
                "  \"stream_tmpl\": \"action recorded for {entity_id}: {field_key}={field_value}\",\n" +
                "  \"fields\": [\n" +
                "    { \"key\": \"field_key\", \"label\": \"Field Label\", \"type\": \"text|number|select|date|textarea|checkbox\", \"required\": true, \"hint\": \"optional hint\", \"options\": [\"only for select type\"], \"state_key\": \"optional_state_attr\" }\n" +
                "  ],\n" +
                "  \"state_patch\": { \"state_attr\": \"{{ field_key }}\" }\n" +
                "}\n" +
                "Do not include markdown formatting. Use standard JSON with double-quoted strings.";

            JSONArray messages = new JSONArray();
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            messages.add(userMsg);

            JSONObject body = new JSONObject();
            body.put("model", VLLM_MODEL);
            body.put("messages", messages);
            body.put("temperature", 0.3);
            body.put("max_tokens", 2048);

            HttpClient http = new HttpClient();
            System.out.println("[Intelligence] generateSchema POST " + VLLM_URL + "/v1/chat/completions model=" + VLLM_MODEL);
            JSONObject response = http.sendPost(VLLM_URL + "/v1/chat/completions", body, "Authorization", "Bearer dummy");
            System.out.println("[Intelligence] generateSchema response keys=" + response.keySet());

            JSONArray choices = (JSONArray) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                System.err.println("[Intelligence] generateSchema: no choices in response: " + response.toJSONString());
                return null;
            }
            JSONObject message = (JSONObject) ((JSONObject) choices.get(0)).get("message");
            if (message == null) {
                System.err.println("[Intelligence] generateSchema: null message in first choice");
                return null;
            }
            String content = (String) message.get("content");
            System.out.println("[Intelligence] generateSchema raw content: " + content);
            JSONObject parsed = extractJson(content);
            if (parsed == null) System.err.println("[Intelligence] generateSchema: extractJson returned null");
            return parsed;
        } catch (Exception e) {
            System.err.println("[Intelligence] generateSchema error: " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    static String loadVocabSection() {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT domain_slang FROM root_organisation LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String raw = rs.getString("domain_slang");
                    if (raw != null) {
                        Object parsed = new JSONParser().parse(raw);
                        if (parsed instanceof JSONObject) {
                            JSONObject vocab = (JSONObject) parsed;
                            if (vocab.isEmpty()) return "";
                            StringBuilder sb = new StringBuilder();
                            for (Object key : vocab.keySet()) {
                                sb.append("  ").append(key).append(" = ").append(vocab.get(key)).append("\n");
                            }
                            return sb.toString().trim();
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Intelligence] loadVocabSection failed: " + e.getMessage());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
        return "";
    }

    private static JSONObject extractJson(String content) {
        if (content == null) return null;
        try {
            String s = content.trim();
            if (s.startsWith("```")) {
                int nl = s.indexOf('\n'), fence = s.lastIndexOf("```");
                if (nl != -1 && fence > nl) s = s.substring(nl + 1, fence).trim();
            }
            int start = s.indexOf('{'), end = s.lastIndexOf('}');
            if (start != -1 && end != -1 && end >= start) {
                try {
                    return (JSONObject) new JSONParser().parse(s.substring(start, end + 1));
                } catch (Exception e) {
                    return extractJsonFallback(s);
                }
            }
        } catch (Exception e) {
            System.err.println("[Intelligence] Failed to parse AI JSON response: " + content);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject extractJsonFallback(String content) {
        try {
            java.util.regex.Matcher nameMatcher = java.util.regex.Pattern
                .compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(content);
            String name = nameMatcher.find() ? nameMatcher.group(1) : "Generated Template";

            int htmlKey   = content.indexOf("\"html_content\"");
            if (htmlKey == -1) return null;
            int colon     = content.indexOf(':', htmlKey);
            int valStart  = content.indexOf('"', colon + 1) + 1;
            int lastBrace = content.lastIndexOf('}');
            int valEnd    = content.lastIndexOf('"', lastBrace - 1);
            if (valStart <= 0 || valEnd <= valStart) return null;

            String html = content.substring(valStart, valEnd)
                .replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"");

            JSONObject result = new JSONObject();
            result.put("name", name);
            result.put("html_content", html);
            System.out.println("[Intelligence] Used fallback JSON extraction for template");
            return result;
        } catch (Exception e) {
            System.err.println("[Intelligence] Fallback extraction failed: " + e.getMessage());
            return null;
        }
    }
}
