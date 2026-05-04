package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TSI Nexus: Universal Intent Resolver
 * The bridge between Natural Language/Commands and the Liquid Interface.
 * Designed to be vertical-agnostic: handles any entity type or action via metadata.
 */
public class Intent implements Action {

    private static final String VLLM_URL;
    private static final String VLLM_MODEL;

    static {
        String url = System.getenv("VLLM_URL");
        String model = System.getenv("VLLM_MODEL");
        VLLM_URL = (url != null && !url.isEmpty()) ? url.replaceAll("/$", "") : null;
        VLLM_MODEL = (model != null && !model.isEmpty()) ? model : "google/gemma-3-12b-it";
    }

    private static final String INTENT_SYSTEM_PROMPT =
        "You are the command parser for TSI Nexus, an institutional intelligence platform.\n" +
        "Translate the user's natural language into a single structured command.\n\n" +
        "Available commands:\n" +
        "  /analyze @<id>               — analyze a single entity's performance\n" +
        "  /compare @<id1> @<id2>       — benchmark two entities side by side\n" +
        "  /disburse @<member_id> <amt> — disburse a loan amount to a member\n" +
        "  /capture @<member_id>        — open an interaction capture form for a member\n\n" +
        "Rules:\n" +
        "1. Output ONLY the command string. No explanation, no markdown, no punctuation.\n" +
        "2. Map names or descriptions to the correct @handle from the entity list provided.\n" +
        "3. The @handle must match exactly what is listed — do not invent handles.\n" +
        "4. If intent is unclear or no matching entity exists, output: /unknown";

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        try {
            JSONObject input = InputProcessor.getInput(req);
            String rawIntent = (String) input.get("intent");

            String llmCommand = llmParseIntent(rawIntent);
            String intentToProcess = (llmCommand != null) ? llmCommand : rawIntent;

            JSONObject result = resolveToAdaptiveUI(intentToProcess);
            result.put("intent_captured", rawIntent);
            if (llmCommand != null) result.put("llm_command", llmCommand);

            OutputProcessor.send(res, 200, result);
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Intent Resolution Failed", e.getMessage(), req.getRequestURI());
        }
    }

    /**
     * Calls the LLM to convert natural language into a /command string.
     * Returns null if LLM is unavailable, the input is already a command, or parsing fails.
     */
    @SuppressWarnings("unchecked")
    private String llmParseIntent(String rawInput) {
        if (VLLM_URL == null || rawInput == null) return null;
        if (rawInput.trim().startsWith("/")) return null; // already a command

        try {
            String entityList = fetchEntityList();

            String userContent = "Known entities:\n" + entityList + "\nUser said: \"" + rawInput + "\"";

            JSONArray messages = new JSONArray();
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", INTENT_SYSTEM_PROMPT);
            messages.add(sysMsg);
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userContent);
            messages.add(userMsg);

            JSONObject body = new JSONObject();
            body.put("model", VLLM_MODEL);
            body.put("messages", messages);
            body.put("max_tokens", 64);
            body.put("temperature", 0.1);

            System.out.println("[Intent] LLM parsing: \"" + rawInput + "\"");
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
                    if (content != null) {
                        // Take only the first line; LLM may add extra commentary
                        String parsed = content.trim().split("\\n")[0].trim();
                        System.out.println("[Intent] LLM resolved to: " + parsed);
                        if (parsed.startsWith("/") && !parsed.startsWith("/unknown")) {
                            return parsed;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Intent] LLM parse failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Fetches all known entity handles and names from the DB to ground the LLM prompt.
     */
    private String fetchEntityList() {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            StringBuilder sb = new StringBuilder();
            String sql = "SELECT type, external_id, current_state->>'name' AS name " +
                         "FROM digital_twins WHERE type != 'system' ORDER BY type, external_id";
            try (PreparedStatement pstmt = conn.prepareStatement(sql);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    sb.append("@").append(rs.getString("external_id"))
                      .append(" (").append(rs.getString("type")).append(")");
                    String name = rs.getString("name");
                    if (name != null && !name.isEmpty()) sb.append(" — ").append(name);
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            System.err.println("[Intent] fetchEntityList failed: " + e.getMessage());
            return "";
        } finally {
            pool.cleanup(null, null, conn);
        }
    }

    /**
     * Maps a (possibly LLM-resolved) command or raw intent to UI components.
     */
    @SuppressWarnings("unchecked")
    private JSONObject resolveToAdaptiveUI(String intent) {
        JSONObject response = new JSONObject();
        JSONArray components = new JSONArray();
        String cleanIntent = intent.trim();

        // 1. DISAMBIGUATION / CONTEXT LOOKUP (@target)
        if (cleanIntent.contains("@")) {
            List<String> targets = extractAllTargets(cleanIntent);
            for (String target : targets) {
                components.add(createComponent("universal_context_card", "{\"target\":\"" + target + "\"}"));
            }
        }

        // 2. UNIVERSAL COMMAND PATTERN (/command @target value)
        if (cleanIntent.startsWith("/")) {
            String[] parts = cleanIntent.split("\\s+");
            if (parts.length >= 2) {
                String actionVerb = parts[0].substring(1).toUpperCase();

                if ("CAPTURE".equalsIgnoreCase(actionVerb)) {
                    // Capture opens a schema-driven form — not a governance confirmation card
                    JSONObject props = new JSONObject();
                    props.put("target", extractTarget(cleanIntent));
                    props.put("intent_raw", cleanIntent);
                    components.add(createComponent("interaction_capture_form", props.toJSONString()));
                } else {
                    JSONObject props = new JSONObject();
                    props.put("action_type", actionVerb);
                    props.put("intent_raw", cleanIntent);

                    if ("COMPARE".equalsIgnoreCase(actionVerb)) {
                        List<String> targets = extractAllTargets(cleanIntent);
                        if (targets.size() >= 2) {
                            props.put("target_1", targets.get(0));
                            props.put("target_2", targets.get(1));
                        }
                    } else {
                        String target = extractTarget(cleanIntent);
                        String value = "";
                        for (String part : parts) {
                            if (part.matches("\\d+")) value = part;
                        }
                        props.put("target_external_id", target);
                        props.put("value", value);
                    }

                    components.add(createComponent("universal_action_confirm", props.toJSONString()));
                }
            }
        }

        // 3. NO HANDLE: fuzzy name search → disambiguation → semantic fallback
        else if (!cleanIntent.contains("@") && !cleanIntent.startsWith("/")) {
            String searchTerm = extractSearchTerm(cleanIntent);
            // Fuzzy search is only meaningful for name-like inputs (short, no question mark).
            // Questions and multi-word sentences fall straight to semantic search.
            boolean nameLike = !cleanIntent.contains("?")
                            && cleanIntent.trim().split("\\s+").length <= 4
                            && searchTerm.split("\\s+").length <= 2
                            && !searchTerm.isEmpty();
            List<JSONObject> matches = nameLike ? fuzzySearchEntities(searchTerm) : new ArrayList<>();

            if (matches.size() == 1) {
                String handle = (String) matches.get(0).get("handle");
                System.out.println("[Intent] Auto-resolved '" + searchTerm + "' → " + handle);
                components.add(createComponent("universal_context_card",
                    "{\"target\":\"" + handle + "\",\"auto_resolved\":true,\"query\":\"" + searchTerm + "\"}"));
            } else if (matches.size() > 1) {
                JSONObject props = new JSONObject();
                props.put("query", searchTerm);
                JSONArray matchArray = new JSONArray();
                for (JSONObject m : matches) matchArray.add(m);
                props.put("matches", matchArray);
                components.add(createComponent("nexus_disambiguation_card", props.toJSONString()));
            } else {
                JSONObject props = new JSONObject();
                props.put("query", cleanIntent);
                components.add(createComponent("nexus_semantic_results", props.toJSONString()));
            }
        }

        response.put("success", true);
        response.put("components", components);
        return response;
    }

    // Strips command verbs, stop words, and numbers to isolate the entity name.
    private String extractSearchTerm(String rawIntent) {
        return rawIntent
            .replaceAll("(?i)\\b(analyze|compare|disburse|capture|verify|kyc|show|check|get|find|loan|status|group|branch|portfolio|performance|for|the|me|to|from|in|of|and|or|with|a|an|is|are|can|how|what|who|does|do|did|has|have|had|his|her|their|this|that|week|month|today|yesterday)\\b", " ")
            .replaceAll("\\d+", " ")
            .replaceAll("[^a-zA-Z\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    // Uses pg_trgm word_similarity for name-based lookup — stays fast at lakh scale.
    @SuppressWarnings("unchecked")
    private List<JSONObject> fuzzySearchEntities(String searchTerm) {
        List<JSONObject> results = new ArrayList<>();
        if (searchTerm == null || searchTerm.trim().isEmpty()) return results;

        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            String sql =
                "SELECT type, external_id, current_state->>'name' AS name " +
                "FROM digital_twins " +
                "WHERE type != 'system' " +
                "  AND current_state->>'name' IS NOT NULL " +
                "  AND word_similarity(?, current_state->>'name') > 0.4 " +
                "ORDER BY word_similarity(?, current_state->>'name') DESC " +
                "LIMIT 5";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, searchTerm);
                pstmt.setString(2, searchTerm);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        JSONObject match = new JSONObject();
                        match.put("handle", "@" + rs.getString("external_id"));
                        match.put("type", rs.getString("type"));
                        match.put("name", rs.getString("name"));
                        results.add(match);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Intent] fuzzySearch failed: " + e.getMessage());
        } finally {
            pool.cleanup(null, null, conn);
        }
        return results;
    }

    private JSONObject createComponent(String type, String propsJson) {
        JSONObject comp = new JSONObject();
        comp.put("component_type", type);
        comp.put("props", propsJson);
        return comp;
    }

    private String extractTarget(String intent) {
        List<String> targets = extractAllTargets(intent);
        return targets.isEmpty() ? "unknown" : targets.get(0);
    }

    private List<String> extractAllTargets(String intent) {
        List<String> targets = new ArrayList<>();
        Pattern pattern = Pattern.compile("@([\\w\\.]+)");
        Matcher matcher = pattern.matcher(intent);
        while (matcher.find()) {
            targets.add("@" + matcher.group(1));
        }
        return targets;
    }

    @Override public void get(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void put(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void delete(HttpServletRequest req, HttpServletResponse res) {}
    @Override public boolean validate(String m, HttpServletRequest req, HttpServletResponse res) { return true; }
}
