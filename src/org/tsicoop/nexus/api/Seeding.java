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

/**
 * TSI Nexus: Seeding & Scenario Engine
 *
 * GET  /api/seeding                 → stats, entity types, session history
 * POST /api/seeding { action }
 *   action=start  — run full synthesis from institutional DNA
 *   action=clear  — delete all SEED_* twins, interactions, templates, forms
 *   action=expand — natural-language expansion of existing seeded data
 */
public class Seeding implements Action {

    private static final String VLLM_URL;
    private static final String VLLM_MODEL;

    static {
        String u = System.getenv("VLLM_URL");
        String m = System.getenv("VLLM_MODEL");
        VLLM_URL   = (u != null && !u.isEmpty()) ? u.replaceAll("/$", "") : null;
        VLLM_MODEL = (m != null && !m.isEmpty()) ? m : null;
    }

    /* ── GET ───────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();
            ensureSchema(conn);

            // Entity types currently in the graph
            JSONArray entityTypes = new JSONArray();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT type, COUNT(*) AS cnt FROM digital_twins GROUP BY type ORDER BY type");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject t = new JSONObject();
                    t.put("type",  rs.getString("type"));
                    t.put("count", rs.getLong("cnt"));
                    entityTypes.add(t);
                }
            }

            // Seeded data counters
            JSONObject stats = new JSONObject();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM digital_twins WHERE external_id LIKE 'SEED_%'");
                 ResultSet rs = ps.executeQuery()) {
                stats.put("seeded_twins", rs.next() ? rs.getLong(1) : 0L);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM interaction_stream ist " +
                    "WHERE ist.owner_id IN (SELECT id FROM digital_twins WHERE external_id LIKE 'SEED_%')");
                 ResultSet rs = ps.executeQuery()) {
                stats.put("seeded_interactions", rs.next() ? rs.getLong(1) : 0L);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM liquid_templates WHERE name LIKE '[SIM]%'");
                 ResultSet rs = ps.executeQuery()) {
                stats.put("seeded_templates", rs.next() ? rs.getLong(1) : 0L);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM interaction_schema WHERE schema_id LIKE 'SIM_%'");
                 ResultSet rs = ps.executeQuery()) {
                stats.put("seeded_forms", rs.next() ? rs.getLong(1) : 0L);
            }

            // Session history
            JSONArray sessions = new JSONArray();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT session_id::text, industry_context, status, " +
                    "twins_seeded, interactions_seeded, templates_seeded, forms_seeded, " +
                    "to_char(created_at, 'DD Mon YYYY HH24:MI') AS created, error_message " +
                    "FROM seeding_sessions ORDER BY created_at DESC LIMIT 10");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject s = new JSONObject();
                    s.put("session_id",           rs.getString("session_id"));
                    s.put("industry_context",     rs.getString("industry_context"));
                    s.put("status",               rs.getString("status"));
                    s.put("twins_seeded",         rs.getInt("twins_seeded"));
                    s.put("interactions_seeded",  rs.getInt("interactions_seeded"));
                    s.put("templates_seeded",     rs.getInt("templates_seeded"));
                    s.put("forms_seeded",         rs.getInt("forms_seeded"));
                    s.put("created",              rs.getString("created"));
                    s.put("error_message",        rs.getString("error_message"));
                    sessions.add(s);
                }
            }

            JSONObject out = new JSONObject();
            out.put("success",      true);
            out.put("entity_types", entityTypes);
            out.put("stats",        stats);
            out.put("sessions",     sessions);
            out.put("ai_available", VLLM_URL != null && VLLM_MODEL != null);
            OutputProcessor.send(res, 200, out);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Fetch failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── POST ──────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONObject body   = (JSONObject) new JSONParser().parse(req.getReader());
            String    action  = str(body, "action");

            switch (action == null ? "" : action) {
                case "start":  startSeeding(conn, body, req, res);  break;
                case "clear":  clearSeeded(conn, req, res);          break;
                case "expand": expandSeeding(conn, body, req, res);  break;
                default:
                    OutputProcessor.errorResponse(res, 400, "Bad request", "Unknown action: " + action, req.getRequestURI());
            }
        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Action failed", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── start seeding ─────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void startSeeding(Connection conn, JSONObject body,
                               HttpServletRequest req, HttpServletResponse res) throws Exception {

        String     context  = str(body, "industry_context");
        String     flavor   = str(body, "data_flavor");
        JSONArray  types    = arrOf(body, "entity_types");
        JSONObject counts   = objOf(body, "entity_counts");
        int        edgePct  = num(body, "edge_pct", 5);
        JSONArray  generate = arrOf(body, "generate");
        if (generate.isEmpty()) { generate = defaultGenerate(); }

        if (context == null || context.isBlank())
            throw new IllegalArgumentException("industry_context is required");
        if (types.isEmpty())
            throw new IllegalArgumentException("At least one entity type is required");
        if (VLLM_URL == null || VLLM_MODEL == null)
            throw new IllegalStateException("AI engine not configured — set VLLM_URL and VLLM_MODEL");

        // Create session record
        String sessionId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO seeding_sessions (industry_context, data_flavor, entity_types, edge_cases_pct, status) " +
                "VALUES (?, ?, ?::jsonb, ?, 'running') RETURNING session_id::text");
             ) {
            ps.setString(1, context.trim());
            ps.setString(2, flavor != null ? flavor.trim() : "");
            ps.setString(3, types.toJSONString());
            ps.setInt(4, edgePct);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                sessionId = rs.getString(1);
            }
        }

        int twinsSeeded = 0, interactionsSeeded = 0, templatesSeeded = 0, formsSeeded = 0;
        List<String[]> twinList = new ArrayList<>();
        StringBuilder log = new StringBuilder();

        try {
            if (generate.contains("twins")) {
                log.append("[1/4] Generating digital twins...\n");
                twinList = generateTwins(conn, context, flavor, types, counts, edgePct);
                twinsSeeded = twinList.size();
                log.append("      → ").append(twinsSeeded).append(" twins created\n");
            }
            if (generate.contains("interactions") && !twinList.isEmpty()) {
                log.append("[2/4] Generating interaction history...\n");
                interactionsSeeded = generateInteractions(conn, context, flavor, twinList, edgePct);
                log.append("      → ").append(interactionsSeeded).append(" interactions created\n");
            }
            if (generate.contains("templates")) {
                log.append("[3/4] Generating Liquid templates...\n");
                templatesSeeded = generateTemplates(conn, context, flavor, types);
                log.append("      → ").append(templatesSeeded).append(" templates created\n");
            }
            if (generate.contains("forms")) {
                log.append("[4/4] Generating form schemas...\n");
                formsSeeded = generateForms(conn, context, flavor, types);
                log.append("      → ").append(formsSeeded).append(" forms created\n");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE seeding_sessions SET status='complete', twins_seeded=?, interactions_seeded=?, " +
                    "templates_seeded=?, forms_seeded=?, completed_at=now() WHERE session_id=?::uuid")) {
                ps.setInt(1, twinsSeeded);
                ps.setInt(2, interactionsSeeded);
                ps.setInt(3, templatesSeeded);
                ps.setInt(4, formsSeeded);
                ps.setString(5, sessionId);
                ps.executeUpdate();
            }

        } catch (Exception e) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE seeding_sessions SET status='failed', error_message=? WHERE session_id=?::uuid")) {
                ps.setString(1, e.getMessage());
                ps.setString(2, sessionId);
                ps.executeUpdate();
            }
            throw e;
        }

        JSONObject out = new JSONObject();
        out.put("success",               true);
        out.put("session_id",            sessionId);
        out.put("twins_seeded",          twinsSeeded);
        out.put("interactions_seeded",   interactionsSeeded);
        out.put("templates_seeded",      templatesSeeded);
        out.put("forms_seeded",          formsSeeded);
        out.put("log",                   log.toString());
        OutputProcessor.send(res, 200, out);
    }

    /* ── clear seeded data ─────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void clearSeeded(Connection conn, HttpServletRequest req, HttpServletResponse res) throws Exception {
        long interactions, twins, templates, forms;

        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM interaction_stream WHERE owner_id IN " +
                "(SELECT id FROM digital_twins WHERE external_id LIKE 'SEED_%')")) {
            interactions = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM digital_twins WHERE external_id LIKE 'SEED_%'")) {
            twins = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM liquid_templates WHERE name LIKE '[SIM]%'")) {
            templates = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM interaction_schema WHERE schema_id LIKE 'SIM_%'")) {
            forms = ps.executeUpdate();
        }

        JSONObject out = new JSONObject();
        out.put("success",               true);
        out.put("cleared_twins",         twins);
        out.put("cleared_interactions",  interactions);
        out.put("cleared_templates",     templates);
        out.put("cleared_forms",         forms);
        OutputProcessor.send(res, 200, out);
    }

    /* ── expand with natural-language instruction ──────────────────── */

    @SuppressWarnings("unchecked")
    private void expandSeeding(Connection conn, JSONObject body,
                                HttpServletRequest req, HttpServletResponse res) throws Exception {
        String instruction = str(body, "instruction");
        String context     = str(body, "industry_context");
        if (instruction == null || instruction.isBlank())
            throw new IllegalArgumentException("instruction is required");
        if (VLLM_URL == null || VLLM_MODEL == null)
            throw new IllegalStateException("AI engine not configured");

        // Summarise current seeded state for context
        StringBuilder currentState = new StringBuilder();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT type, COUNT(*) AS cnt FROM digital_twins WHERE external_id LIKE 'SEED_%' GROUP BY type");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next())
                currentState.append(rs.getString("type")).append(": ").append(rs.getLong("cnt")).append(", ");
        }

        String prompt =
            "You are expanding an existing synthetic institutional dataset.\n\n" +
            "INDUSTRY CONTEXT: " + (context != null ? context : "institutional enterprise") + "\n" +
            "CURRENT SEEDED ENTITIES: " + currentState + "\n" +
            "EXPANSION INSTRUCTION: " + instruction + "\n\n" +
            "Generate additional digital twins to satisfy the instruction. " +
            "All external_id values MUST start with 'SEED_' followed by type and a 3-digit number. " +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"twins\":[{\"external_id\":\"SEED_type_NNN\",\"type\":\"...\",\"state\":{...}}]}";

        JSONObject generated = extractJson(callAI(prompt));
        JSONArray  twinsArr  = arrOf(generated, "twins");

        int inserted = 0;
        for (Object obj : twinsArr) {
            JSONObject t = (JSONObject) obj;
            String extId = str(t, "external_id");
            String type  = str(t, "type");
            JSONObject state = objOf(t, "state");
            if (extId == null || !extId.startsWith("SEED_")) continue;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO digital_twins (external_id, type, current_state) VALUES (?, ?, ?::jsonb) " +
                    "ON CONFLICT (external_id) DO NOTHING")) {
                ps.setString(1, extId);
                ps.setString(2, type != null ? type : "entity");
                ps.setString(3, state.toJSONString());
                inserted += ps.executeUpdate();
            }
        }

        JSONObject out = new JSONObject();
        out.put("success",     true);
        out.put("twins_added", inserted);
        OutputProcessor.send(res, 200, out);
    }

    /* ── AI generation phases ──────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private List<String[]> generateTwins(Connection conn, String context, String flavor,
                                          JSONArray types, JSONObject counts, int edgePct) throws Exception {
        StringBuilder spec = new StringBuilder();
        for (Object t : types) {
            String type  = (String) t;
            int    count = counts.containsKey(type) ? ((Number) counts.get(type)).intValue() : 10;
            spec.append(type).append(": ").append(count).append(" entities\n");
        }

        String prompt =
            "You are generating synthetic digital twins for an institutional management platform.\n\n" +
            "INDUSTRY CONTEXT: " + context + "\n" +
            (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
            "EDGE CASES: " + edgePct + "% of entities should represent problem cases " +
            "(overdue payments, inactive status, flagged, at-risk, suspended)\n\n" +
            "ENTITIES TO GENERATE:\n" + spec + "\n" +
            "Rules:\n" +
            "- external_id MUST start with 'SEED_' then type, then zero-padded 3-digit number (e.g. SEED_member_001)\n" +
            "- state must contain: name, status, and 4-6 industry-specific fields\n" +
            "- Use realistic local names, phone formats, and domain values\n" +
            "- Vary the data — do not repeat the same names or values\n\n" +
            "Return ONLY valid JSON, no markdown, no explanation:\n" +
            "{\"twins\":[{\"external_id\":\"SEED_member_001\",\"type\":\"member\"," +
            "\"state\":{\"name\":\"...\",\"status\":\"active\",...}},...]}";

        JSONObject generated = extractJson(callAI(prompt));
        JSONArray  twinsArr  = arrOf(generated, "twins");

        List<String[]> inserted = new ArrayList<>();
        for (Object obj : twinsArr) {
            JSONObject t     = (JSONObject) obj;
            String     extId = str(t, "external_id");
            String     type  = str(t, "type");
            JSONObject state = objOf(t, "state");
            if (extId == null || !extId.startsWith("SEED_")) continue;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO digital_twins (external_id, type, current_state) VALUES (?, ?, ?::jsonb) " +
                    "ON CONFLICT (external_id) DO NOTHING RETURNING external_id")) {
                ps.setString(1, extId);
                ps.setString(2, type != null ? type : "entity");
                ps.setString(3, state.toJSONString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) inserted.add(new String[]{ extId, type });
                }
            }
        }
        return inserted;
    }

    private int generateInteractions(Connection conn, String context, String flavor,
                                      List<String[]> twins, int edgePct) throws Exception {
        // Cap to 20 entities for prompt safety
        List<String[]> sample = twins.size() > 20 ? twins.subList(0, 20) : twins;
        StringBuilder  twinList = new StringBuilder();
        for (String[] t : sample) twinList.append(t[0]).append(" (").append(t[1]).append("), ");

        String prompt =
            "You are generating realistic interaction history for an institutional AI platform.\n\n" +
            "INDUSTRY CONTEXT: " + context + "\n" +
            (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
            "EDGE CASES: " + edgePct + "% should be problem / complaint / risk interactions\n\n" +
            "ENTITY IDs: " + twinList + "\n\n" +
            "Generate 20-30 interaction entries. Each must reference one of the entity external_ids above.\n" +
            "content: realistic text — field visit note, loan inquiry, meeting minute, complaint, or status update (1-3 sentences)\n" +
            "intent_mapped: snake_case label, e.g. field_visit, loan_inquiry, complaint, payment_made, " +
            "group_meeting, risk_flag, profile_update, kyc_check\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"interactions\":[{\"owner_external_id\":\"SEED_member_001\"," +
            "\"content\":\"...\",\"intent_mapped\":\"field_visit\"},...]}";

        JSONObject generated = extractJson(callAI(prompt));
        JSONArray  items     = arrOf(generated, "interactions");

        int count = 0;
        for (Object obj : items) {
            JSONObject item  = (JSONObject) obj;
            String ownerExtId = str(item, "owner_external_id");
            String content    = str(item, "content");
            String intent     = item.containsKey("intent_mapped") ? (String) item.get("intent_mapped") : "general";
            if (ownerExtId == null || content == null) continue;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO interaction_stream (owner_id, content, intent_mapped) " +
                    "SELECT id, ?, ? FROM digital_twins WHERE external_id = ?")) {
                ps.setString(1, content);
                ps.setString(2, intent);
                ps.setString(3, ownerExtId);
                count += ps.executeUpdate();
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int generateTemplates(Connection conn, String context, String flavor, JSONArray types) throws Exception {
        StringBuilder typeList = new StringBuilder();
        for (Object t : types) typeList.append(t).append(", ");

        String prompt =
            "You are generating Liquid HTML dashboard templates for an institutional management system.\n\n" +
            "INDUSTRY CONTEXT: " + context + "\n" +
            (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
            "ENTITY TYPES: " + typeList + "\n\n" +
            "Generate one Liquid template per entity type. Each is a compact HTML card for a profile dashboard.\n" +
            "Use Liquid variables like {{ actor.state.name }}, {{ actor.state.status }}, {{ actor.external_id }}.\n" +
            "Keep HTML simple: a card div with a title, status badge, and 3-5 key data fields.\n" +
            "name MUST start with '[SIM] ' followed by a descriptive title.\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"templates\":[{\"name\":\"[SIM] Member Profile\",\"entity_type\":\"member\"," +
            "\"html_content\":\"<div>...</div>\",\"condition_sql\":\"\"},...]}";

        JSONObject generated  = extractJson(callAI(prompt));
        JSONArray  templates  = arrOf(generated, "templates");

        int count = 0;
        for (Object obj : templates) {
            JSONObject t     = (JSONObject) obj;
            String name      = str(t, "name");
            String entType   = str(t, "entity_type");
            String html      = t.containsKey("html_content") ? (String) t.get("html_content") : "<div>Template</div>";
            String condSql   = t.containsKey("condition_sql") ? (String) t.get("condition_sql") : "";
            if (name == null || !name.startsWith("[SIM]")) continue;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO liquid_templates (name, entity_type, html_content, condition_sql, is_active) " +
                    "VALUES (?, ?, ?, ?, true) ON CONFLICT DO NOTHING")) {
                ps.setString(1, name);
                ps.setString(2, entType != null ? entType : "general");
                ps.setString(3, html);
                ps.setString(4, condSql);
                count += ps.executeUpdate();
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int generateForms(Connection conn, String context, String flavor, JSONArray types) throws Exception {
        StringBuilder typeList = new StringBuilder();
        for (Object t : types) typeList.append(t).append(", ");

        String prompt =
            "You are generating data capture form schemas for an institutional management system.\n\n" +
            "INDUSTRY CONTEXT: " + context + "\n" +
            (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
            "ENTITY TYPES: " + typeList + "\n\n" +
            "Generate 2-3 realistic form schemas aligned to the industry context and policy needs.\n" +
            "schema_id MUST start with 'SIM_' and be snake_case.\n" +
            "Field types: text, email, number, date, select, textarea, checkbox.\n" +
            "For select fields, include: \"options\":[{\"value\":\"v\",\"label\":\"Label\"}]\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"forms\":[{\"schema_id\":\"SIM_member_kyc\",\"label\":\"Member KYC Form\"," +
            "\"applies_to\":\"member\",\"action_type\":\"KYC_SUBMIT\"," +
            "\"fields\":[{\"name\":\"full_name\",\"label\":\"Full Name\",\"type\":\"text\",\"required\":true},...]},...]}";

        JSONObject generated = extractJson(callAI(prompt));
        JSONArray  forms     = arrOf(generated, "forms");

        int count = 0;
        for (Object obj : forms) {
            JSONObject f      = (JSONObject) obj;
            String schemaId   = str(f, "schema_id");
            String label      = f.containsKey("label")       ? (String) f.get("label")       : "Simulated Form";
            String appliesTo  = f.containsKey("applies_to")  ? (String) f.get("applies_to")  : "general";
            String actionType = f.containsKey("action_type") ? (String) f.get("action_type") : "SIM_ACTION";
            JSONArray fields  = arrOf(f, "fields");
            if (schemaId == null || !schemaId.startsWith("SIM_")) continue;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO interaction_schema (schema_id, label, applies_to, action_type, fields, is_active) " +
                    "VALUES (?, ?, ?, ?, ?::jsonb, true) ON CONFLICT (schema_id) DO NOTHING")) {
                ps.setString(1, schemaId);
                ps.setString(2, label);
                ps.setString(3, appliesTo);
                ps.setString(4, actionType.toUpperCase());
                ps.setString(5, fields.toJSONString());
                count += ps.executeUpdate();
            }
        }
        return count;
    }

    /* ── AI call ───────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private String callAI(String prompt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model",       VLLM_MODEL);
        body.put("temperature", 0.8);
        body.put("max_tokens",  4096);

        JSONArray  messages = new JSONArray();

        JSONObject sys = new JSONObject();
        sys.put("role",    "system");
        sys.put("content", "You are a synthetic institutional data generator. " +
                           "Always respond with valid JSON only. " +
                           "No markdown code fences, no explanation, no extra text before or after the JSON.");
        messages.add(sys);

        JSONObject user = new JSONObject();
        user.put("role",    "user");
        user.put("content", prompt);
        messages.add(user);

        body.put("messages", messages);

        System.out.println("[Seeding] POST " + VLLM_URL + "/v1/chat/completions");
        HttpClient     http     = new HttpClient();
        JSONObject     response = http.sendPost(VLLM_URL + "/v1/chat/completions", body, "Authorization", "Bearer dummy");
        JSONArray      choices  = (JSONArray) response.get("choices");
        if (choices == null || choices.isEmpty()) throw new RuntimeException("AI returned no choices");
        JSONObject message = (JSONObject) ((JSONObject) choices.get(0)).get("message");
        if (message == null) throw new RuntimeException("AI response missing message");
        String content = (String) message.get("content");
        return content != null ? content.trim() : "";
    }

    private JSONObject extractJson(String content) throws Exception {
        String s = content.trim();
        // Strip markdown code fences
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl >= 0) s = s.substring(nl + 1);
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end).trim();
        }
        // Find outermost JSON object
        int start = s.indexOf('{');
        int end   = s.lastIndexOf('}');
        if (start >= 0 && end > start) s = s.substring(start, end + 1);
        return (JSONObject) new JSONParser().parse(s);
    }

    /* ── schema bootstrap ──────────────────────────────────────────── */

    private void ensureSchema(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS seeding_sessions (" +
                "  session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(), " +
                "  industry_context TEXT NOT NULL, " +
                "  data_flavor TEXT, " +
                "  entity_types JSONB, " +
                "  edge_cases_pct INTEGER DEFAULT 5, " +
                "  status TEXT DEFAULT 'running', " +
                "  twins_seeded INTEGER DEFAULT 0, " +
                "  interactions_seeded INTEGER DEFAULT 0, " +
                "  templates_seeded INTEGER DEFAULT 0, " +
                "  forms_seeded INTEGER DEFAULT 0, " +
                "  error_message TEXT, " +
                "  created_at TIMESTAMPTZ DEFAULT now(), " +
                "  completed_at TIMESTAMPTZ" +
                ")")) {
            ps.execute();
        }
    }

    /* ── utility helpers ───────────────────────────────────────────── */

    private String str(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof String ? ((String) v).trim() : null;
    }

    private int num(JSONObject o, String k, int def) {
        Object v = o.get(k);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    @SuppressWarnings("unchecked")
    private JSONArray arrOf(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONArray ? (JSONArray) v : new JSONArray();
    }

    @SuppressWarnings("unchecked")
    private JSONObject objOf(JSONObject o, String k) {
        Object v = o.get(k);
        return v instanceof JSONObject ? (JSONObject) v : new JSONObject();
    }

    @SuppressWarnings("unchecked")
    private JSONArray defaultGenerate() {
        JSONArray a = new JSONArray();
        a.add("twins"); a.add("interactions"); a.add("templates"); a.add("forms");
        return a;
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
