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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * TSI Nexus: Universal Intent Resolver
 *
 * The bridge between Natural Language / Commands and the Liquid Interface.
 * Vertical-agnostic: available commands, their routing, and the LLM prompt
 * are all driven by rows in command_manifest — zero hardcoding here.
 */
public class Intent implements Action {

    private static final String VLLM_URL;
    private static final String VLLM_MODEL;

    static {
        String url   = System.getenv("VLLM_URL");
        String model = System.getenv("VLLM_MODEL");
        VLLM_URL   = (url   != null && !url.isEmpty())   ? url.replaceAll("/$", "") : null;
        VLLM_MODEL = (model != null && !model.isEmpty()) ? model : null;
    }

    // Conversational Intelligence v0.2 - routes questions that fit neither a /command nor a
    // plain-name lookup (see classifyIntelligenceQuery, called only from the existing
    // no-command/no-handle fallback branch in resolveToAdaptiveUI).
    private static final String INTELLIGENCE_ROUTE_PROMPT =
        "You are the query router for TSI Nexus. Classify the user's question into exactly one route " +
        "and extract structured parameters. Today's date is {TODAY_ISO}.\n\n" +
        "Known entities:\n{ENTITY_LIST}\n\n" +
        "Routes:\n" +
        "  AUDIT      - why/when/whether an action was blocked, approved, or executed (a specific past\n" +
        "               decision, optionally time-boxed). Extract actor_handle + date_from/date_to.\n" +
        "  ANALYTICS  - filter, rank, count, or aggregate entities by concrete criteria (skills, status,\n" +
        "               scores, relationships, time windows).\n" +
        "  SIMILARITY - find entities \"similar to\"/\"like\"/\"resembling\" a named entity, based on\n" +
        "               free-text/profile characteristics rather than exact filters.\n" +
        "  NONE       - anything else (greetings, unresolvable, out of scope).\n\n" +
        "Rules:\n" +
        "  1. Output ONLY a single JSON object, no markdown, no explanation.\n" +
        "  2. actor_handle must be an exact @handle from the entity list above, or null.\n" +
        "  3. date_from/date_to are YYYY-MM-DD or null. \"yesterday\" = TODAY-1. \"this week\" = Monday of\n" +
        "     the current week through TODAY.\n" +
        "  4. cleaned_question is the user's question verbatim, for ANALYTICS/SIMILARITY routes.\n\n" +
        "Output shape:\n" +
        "{\"route\":\"AUDIT|ANALYTICS|SIMILARITY|NONE\",\"actor_handle\":null,\"date_from\":null,\"date_to\":null,\"cleaned_question\":null}";

    @Override
    public void post(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONObject input  = InputProcessor.getInput(req);
            String rawIntent  = (String) input.get("intent");

            List<JSONObject> commands = loadCommands(conn);
            String vocabSection = loadVocabSection(conn);
            String llmCommand = llmParseIntent(rawIntent, commands, vocabSection);
            String intentToProcess = (llmCommand != null) ? llmCommand : rawIntent;

            JSONObject result = resolveToAdaptiveUI(intentToProcess, commands);
            result.put("intent_captured", rawIntent);
            if (llmCommand != null) result.put("llm_command", llmCommand);

            // Log action intents immediately — analytics/action commands have no other logging point
            JSONArray comps = (JSONArray) result.get("components");
            if (comps != null) logActionIntents(conn, comps, rawIntent, InputProcessor.getTwinId(req));

            OutputProcessor.send(res, 200, result);
        } catch (Exception e) {
            OutputProcessor.errorResponse(res, 500, "Intent Resolution Failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── Load commands from DB ────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private List<JSONObject> loadCommands(Connection conn) throws Exception {
        List<JSONObject> commands = new ArrayList<>();
        String sql = "SELECT command_verb, label, args_hint, hint, component_type, action_type, entity_type, multi_target, has_value, " +
                     "linked_template::text, linked_form " +
                     "FROM command_manifest WHERE is_active = TRUE ORDER BY command_verb";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject cmd = new JSONObject();
                cmd.put("command_verb",     rs.getString("command_verb"));
                cmd.put("label",            rs.getString("label"));
                cmd.put("args_hint",        rs.getString("args_hint"));
                cmd.put("hint",             rs.getString("hint"));
                cmd.put("component_type",   rs.getString("component_type"));
                cmd.put("action_type",      rs.getString("action_type"));
                cmd.put("entity_type",      rs.getString("entity_type"));
                cmd.put("multi_target",     rs.getBoolean("multi_target"));
                cmd.put("has_value",        rs.getBoolean("has_value"));
                String lt = rs.getString("linked_template");
                if (lt != null) cmd.put("linked_template", lt);
                String lf = rs.getString("linked_form");
                if (lf != null) cmd.put("linked_form", lf);
                commands.add(cmd);
            }
        }
        return commands;
    }

    /* ── LLM: natural language → /command ────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private String llmParseIntent(String rawInput, List<JSONObject> commands, String vocabSection) {
        if (VLLM_URL == null || VLLM_MODEL == null || rawInput == null) return null;
        if (rawInput.trim().startsWith("/")) return null;

        try {
            String systemPrompt = buildSystemPrompt(commands, vocabSection);
            String entityList   = fetchEntityList();
            String userContent  = "Known entities:\n" + entityList + "\nUser said: \"" + rawInput + "\"";

            JSONArray messages = new JSONArray();
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userContent);
            messages.add(userMsg);

            JSONObject body = new JSONObject();
            body.put("model", VLLM_MODEL);
            body.put("messages", messages);
            // Generous headroom: the configured model may be a reasoning model that emits a
            // separate reasoning_content field before content - a small max_tokens can be
            // exhausted mid-reasoning, leaving content empty even though the call "succeeded".
            body.put("max_tokens", 300);
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

    /* ── LLM: unresolved question → AUDIT / ANALYTICS / SIMILARITY / NONE ───── */

    @SuppressWarnings("unchecked")
    private JSONObject classifyIntelligenceQuery(String rawInput, String entityList) {
        if (VLLM_URL == null || VLLM_MODEL == null || rawInput == null) return null;

        try {
            String todayIso = java.time.LocalDate.now().toString();
            String systemPrompt = INTELLIGENCE_ROUTE_PROMPT
                .replace("{TODAY_ISO}", todayIso)
                .replace("{ENTITY_LIST}", entityList != null ? entityList : "");

            JSONArray messages = new JSONArray();
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", rawInput);
            messages.add(userMsg);

            JSONObject body = new JSONObject();
            body.put("model", VLLM_MODEL);
            body.put("messages", messages);
            // See llmParseIntent above - same reasoning-model headroom concern, and this prompt
            // carries the full entity list, so it needs a lot more room to reason through it.
            // (Observed: 1024 was still not enough on a ~180-entity deployment - the model
            // burned most of the budget on reasoning_content and the JSON got cut off mid-value.)
            body.put("max_tokens", 3072);
            body.put("temperature", 0.1);

            System.out.println("[Intent] classifying intelligence query: \"" + rawInput + "\"");
            HttpClient http = new HttpClient();
            JSONObject response = http.sendPost(
                VLLM_URL + "/v1/chat/completions",
                body,
                "Authorization", "Bearer dummy"
            );

            JSONArray choices = (JSONArray) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                System.err.println("[Intent] classifyIntelligenceQuery: no choices in response: " + response.toJSONString());
                return null;
            }
            JSONObject message = (JSONObject) ((JSONObject) choices.get(0)).get("message");
            if (message == null) {
                System.err.println("[Intent] classifyIntelligenceQuery: null message in first choice");
                return null;
            }
            String content = (String) message.get("content");
            if (content == null) {
                System.err.println("[Intent] classifyIntelligenceQuery: null content in message");
                return null;
            }

            String stripped = stripMarkdownFences(content).trim();
            int start = stripped.indexOf('{');
            int end   = stripped.lastIndexOf('}');
            if (start < 0 || end < 0 || end < start) {
                System.err.println("[Intent] classifyIntelligenceQuery: no JSON object found in content: " + content);
                return null;
            }

            Object parsed = new JSONParser().parse(stripped.substring(start, end + 1));
            JSONObject routed = parsed instanceof JSONObject ? (JSONObject) parsed : null;
            if (routed != null) System.out.println("[Intent] classified route: " + routed.get("route"));
            else System.err.println("[Intent] classifyIntelligenceQuery: parsed content was not a JSON object: " + content);
            return routed;
        } catch (Exception e) {
            System.err.println("[Intent] classifyIntelligenceQuery failed: " + e.getMessage());
            return null;
        }
    }

    private String stripMarkdownFences(String text) {
        Matcher m = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE).matcher(text.trim());
        return m.find() ? m.group(1).trim() : text.trim();
    }

    private String buildSystemPrompt(List<JSONObject> commands, String vocabSection) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the command parser for TSI Nexus, an institutional intelligence platform.\n");
        sb.append("Translate the user's natural language into a single structured command.\n\n");
        if (vocabSection != null && !vocabSection.isEmpty()) {
            sb.append("Institutional vocabulary (expand abbreviations using these definitions):\n");
            sb.append(vocabSection).append("\n\n");
        }
        sb.append("Available commands:\n");
        for (JSONObject cmd : commands) {
            sb.append("  /").append(cmd.get("command_verb"))
              .append(" ").append(cmd.get("args_hint"))
              .append("  - ").append(cmd.get("hint")).append("\n");
        }
        sb.append("\nRules:\n");
        sb.append("1. Output ONLY the command string. No explanation, no markdown, no punctuation.\n");
        sb.append("2. Map names or descriptions to the correct @handle from the entity list provided.\n");
        sb.append("3. The @handle must match exactly what is listed - do not invent handles.\n");
        sb.append("4. If intent is unclear or no matching entity exists, output: /unknown");
        return sb.toString();
    }

    /* ── Map command → UI components ─────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private JSONObject resolveToAdaptiveUI(String intent, List<JSONObject> commands) {
        JSONObject response  = new JSONObject();
        JSONArray components = new JSONArray();
        String cleanIntent   = intent.trim();

        // 1. Context card for each @target mentioned
        if (cleanIntent.contains("@")) {
            for (String target : extractAllTargets(cleanIntent)) {
                components.add(createComponent("universal_context_card",
                    "{\"target\":\"" + target + "\"}"));
            }
        }

        // 2. Command routing — driven entirely by command_manifest
        if (cleanIntent.startsWith("/")) {
            String[] parts = cleanIntent.split("\\s+");
            String verb = parts[0].substring(1).toLowerCase();
            JSONObject cmd = findCommand(commands, verb);
            if (cmd == null) {
                JSONObject props = new JSONObject();
                props.put("query", cleanIntent);
                components.add(createComponent("nexus_semantic_results", props.toJSONString()));
                response.put("success", true);
                response.put("components", components);
                return response;
            }

            String componentType = (String) cmd.get("component_type");
            boolean multiTarget  = Boolean.TRUE.equals(cmd.get("multi_target"));

            JSONObject props = new JSONObject();
            String actionType = cmd.get("action_type") != null ? (String) cmd.get("action_type") : verb.toUpperCase();
            props.put("action_type", actionType);
            props.put("intent_raw",  cleanIntent);
            if (cmd.get("linked_template") != null) props.put("linked_template", cmd.get("linked_template"));
            if (cmd.get("linked_form")     != null) props.put("linked_form",     cmd.get("linked_form"));
            if (cmd.get("entity_type")     != null) props.put("entity_type",     cmd.get("entity_type"));

            // Resolve name → @handle when no @handle is present in the command
            String target = extractTarget(cleanIntent);
            if ("unknown".equals(target)) {
                String namePart = extractSearchTerm(cleanIntent, commands);
                if (!namePart.isEmpty()) {
                    List<JSONObject> matches = fuzzySearchEntities(namePart);
                    if (matches.size() == 1) {
                        target = (String) matches.get(0).get("handle");
                        props.put("auto_resolved_name", matches.get(0).get("name"));
                    } else if (matches.size() > 1) {
                        JSONObject dProps = new JSONObject();
                        dProps.put("query",            namePart);
                        dProps.put("original_command", verb);
                        JSONArray matchArray = new JSONArray();
                        for (JSONObject m : matches) matchArray.add(m);
                        dProps.put("matches", matchArray);
                        components.add(createComponent("nexus_disambiguation_card", dProps.toJSONString()));
                        response.put("success", true);
                        response.put("components", components);
                        return response;
                    }
                }
            }

            if ("interaction_capture_form".equals(componentType)) {
                props.put("target", target);
                components.add(createComponent("interaction_capture_form", props.toJSONString()));
            } else {
                if (multiTarget) {
                    List<String> targets = extractAllTargets(cleanIntent);
                    if (targets.size() >= 2) {
                        props.put("target_1", targets.get(0));
                        props.put("target_2", targets.get(1));
                    }
                } else {
                    props.put("target_external_id", target);
                    String value = "";
                    for (String part : parts) {
                        if (part.matches("\\d+")) { value = part; break; }
                    }
                    props.put("value", value);
                }
                components.add(createComponent("universal_action_confirm", props.toJSONString()));
            }
        }

        // 3. No command, no handle — fuzzy name search or semantic fallback
        else if (!cleanIntent.contains("@")) {
            String searchTerm = extractSearchTerm(cleanIntent, commands);
            boolean nameLike  = !cleanIntent.contains("?")
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
                // Neither a matched command nor a name lookup - try routing to one of the
                // Conversational Intelligence paths (AUDIT / ANALYTICS / SIMILARITY) before
                // falling back to the semantic-results dead end.
                JSONObject routed = classifyIntelligenceQuery(cleanIntent, fetchEntityList());
                String route = routed != null ? (String) routed.get("route") : null;

                if ("AUDIT".equals(route)) {
                    JSONObject props = new JSONObject();
                    props.put("actor_handle", routed.get("actor_handle"));
                    props.put("date_from",    routed.get("date_from"));
                    props.put("date_to",      routed.get("date_to"));
                    props.put("question",     cleanIntent);
                    components.add(createComponent("nexus_audit_narrative", props.toJSONString()));
                } else if ("ANALYTICS".equals(route) || "SIMILARITY".equals(route)) {
                    JSONObject props = new JSONObject();
                    Object cleaned = routed.get("cleaned_question");
                    props.put("question", cleaned != null ? cleaned : cleanIntent);
                    props.put("mode", route);
                    components.add(createComponent("nexus_analytical_results", props.toJSONString()));
                } else {
                    JSONObject props = new JSONObject();
                    props.put("query", cleanIntent);
                    components.add(createComponent("nexus_semantic_results", props.toJSONString()));
                }
            }
        }

        response.put("success", true);
        response.put("components", components);
        return response;
    }

    private JSONObject findCommand(List<JSONObject> commands, String verb) {
        for (JSONObject cmd : commands) {
            if (verb.equalsIgnoreCase((String) cmd.get("command_verb"))) return cmd;
        }
        return null;
    }

    /* ── Stream logging ───────────────────────────────────────────────────── */

    private void logActionIntents(Connection conn, JSONArray components, String rawIntent, String actorTwinId) {
        for (Object obj : components) {
            JSONObject comp = (JSONObject) obj;
            if (!"universal_action_confirm".equals(comp.get("component_type"))) continue;
            try {
                JSONObject props = (JSONObject) new JSONParser().parse((String) comp.get("props"));
                String targetId = (String) props.get("target_external_id");
                if (targetId == null || targetId.isEmpty() || "unknown".equals(targetId)) continue;
                String clean = targetId.replaceFirst("^@", "");
                UUID ownerId;
                String entityName = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, current_state->>'name' FROM digital_twins WHERE external_id = ?")) {
                    ps.setString(1, clean);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) continue;
                        ownerId = UUID.fromString(rs.getString(1));
                        entityName = rs.getString(2);
                    }
                }
                UUID actorId = null;
                if (actorTwinId != null && !actorTwinId.isBlank()) {
                    try { actorId = UUID.fromString(actorTwinId); } catch (Exception ignored) {}
                }
                String actionType = (String) props.get("action_type");
                // Replace @handle or bare handle in raw intent with the human name
                String display = rawIntent != null ? rawIntent : actionType;
                if (entityName != null) {
                    display = display.replaceAll("(?i)@?" + java.util.regex.Pattern.quote(clean), entityName).trim();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO interaction_stream (owner_id, actor_id, content, intent_mapped, created_at) VALUES (?, ?, ?, ?, NOW())")) {
                    ps.setObject(1, ownerId);
                    ps.setObject(2, actorId);
                    ps.setString(3, display);
                    ps.setString(4, actionType);
                    ps.executeUpdate();
                }
            } catch (Exception e) {
                System.err.println("[Intent] stream log failed: " + e.getMessage());
            }
        }
    }

    /* ── DB helpers ───────────────────────────────────────────────────────── */

    private String loadVocabSection(Connection conn) {
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
        } catch (Exception e) {
            System.err.println("[Intent] loadVocabSection failed: " + e.getMessage());
        }
        return "";
    }

    private String fetchEntityList() {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            StringBuilder sb = new StringBuilder();
            String sql = "SELECT type, external_id, current_state->>'name' AS name " +
                         "FROM digital_twins WHERE type != 'system' ORDER BY type, external_id";
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
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

    // Strips command verbs (from DB) and generic English stop words to isolate entity names.
    private String extractSearchTerm(String rawIntent, List<JSONObject> commands) {
        String verbPattern = commands.stream()
            .map(c -> (String) c.get("command_verb"))
            .collect(Collectors.joining("|"));
        return rawIntent
            .replaceAll("(?i)\\b(" + verbPattern + "|show|check|get|find|status|performance|for|the|me|to|from|in|of|and|or|with|a|an|is|are|can|how|what|who|does|do|did|has|have|had|his|her|their|this|that|week|month|today|yesterday)\\b", " ")
            .replaceAll("\\d+", " ")
            .replaceAll("[^a-zA-Z\\s]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

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
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, searchTerm);
                ps.setString(2, searchTerm);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JSONObject match = new JSONObject();
                        match.put("handle", "@" + rs.getString("external_id"));
                        match.put("type",   rs.getString("type"));
                        match.put("name",   rs.getString("name"));
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
        Matcher matcher = Pattern.compile("@([\\w\\.]+)").matcher(intent);
        while (matcher.find()) targets.add("@" + matcher.group(1));
        return targets;
    }

    @Override public void get(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void put(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void delete(HttpServletRequest req, HttpServletResponse res) {}
    @Override public boolean validate(String m, HttpServletRequest req, HttpServletResponse res) { return true; }
}
