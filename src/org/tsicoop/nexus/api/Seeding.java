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
 *   action=clear  — delete seeded twins and generated configuration, keeping mandatory commands
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
            StandardCommands.ensure(conn);

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
                    "SELECT COUNT(*) FROM twin_relationships WHERE from_twin_id IN " +
                    "(SELECT id FROM digital_twins WHERE external_id LIKE 'SEED_%') " +
                    "OR to_twin_id IN (SELECT id FROM digital_twins WHERE external_id LIKE 'SEED_%')");
                 ResultSet rs = ps.executeQuery()) {
                stats.put("seeded_relationships", rs.next() ? rs.getLong(1) : 0L);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM interaction_stream ist " +
                    "WHERE ist.owner_id IN (SELECT id FROM digital_twins WHERE external_id LIKE 'SEED_%')");
                 ResultSet rs = ps.executeQuery()) {
                stats.put("seeded_interactions", rs.next() ? rs.getLong(1) : 0L);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM liquid_templates");
                 ResultSet rs = ps.executeQuery()) {
                stats.put("seeded_templates", rs.next() ? rs.getLong(1) : 0L);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM interaction_schema");
                 ResultSet rs = ps.executeQuery()) {
                stats.put("seeded_forms", rs.next() ? rs.getLong(1) : 0L);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM policy_manifest");
                 ResultSet rs = ps.executeQuery()) {
                stats.put("seeded_policies", rs.next() ? rs.getLong(1) : 0L);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM command_manifest WHERE command_verb NOT IN " +
                    "('analyze','compare','approve','reject','escalate','hold','record')")) {
                try (ResultSet rs = ps.executeQuery()) {
                    stats.put("seeded_commands", rs.next() ? rs.getLong(1) : 0L);
                }
            }

            // Session history
            JSONArray sessions = new JSONArray();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT session_id::text, industry_context, status, " +
                    "twins_seeded, relationships_seeded, interactions_seeded, templates_seeded, forms_seeded, policies_seeded, commands_seeded, " +
                    "to_char(created_at, 'DD Mon YYYY HH24:MI') AS created, error_message " +
                    "FROM seeding_sessions ORDER BY created_at DESC LIMIT 10");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject s = new JSONObject();
                    s.put("session_id",           rs.getString("session_id"));
                    s.put("industry_context",     rs.getString("industry_context"));
                    s.put("status",               rs.getString("status"));
                    s.put("twins_seeded",         rs.getInt("twins_seeded"));
                    s.put("relationships_seeded", rs.getInt("relationships_seeded"));
                    s.put("interactions_seeded",  rs.getInt("interactions_seeded"));
                    s.put("templates_seeded",     rs.getInt("templates_seeded"));
                    s.put("forms_seeded",         rs.getInt("forms_seeded"));
                    s.put("policies_seeded",      rs.getInt("policies_seeded"));
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
            StandardCommands.ensure(conn);

            JSONObject body   = InputProcessor.getInput(req);
            if (body == null) {
                OutputProcessor.errorResponse(res, 400, "Bad request", "Invalid or missing JSON body", req.getRequestURI());
                return;
            }
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
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            OutputProcessor.errorResponse(res, 500, "Action failed", msg, req.getRequestURI());
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

        int twinsSeeded = 0, relationshipsSeeded = 0, interactionsSeeded = 0, templatesSeeded = 0, forms_seeded = 0, policiesSeeded = 0, commandsSeeded = 0;
        List<String[]> twinList = new ArrayList<>();
        StringBuilder log = new StringBuilder();

        try {
            if (generate.contains("twins")) {
                log.append("[1/7] Generating digital twins...\n");
                twinList = generateTwins(conn, context, flavor, types, counts, edgePct);
                twinsSeeded = twinList.size();
                log.append("      → ").append(twinsSeeded).append(" twins created\n");
            }
            if (generate.contains("relationships") && !twinList.isEmpty()) {
                log.append("[2/7] Generating context relationships...\n");
                relationshipsSeeded = generateRelationships(conn, context, flavor, twinList);
                log.append("      → ").append(relationshipsSeeded).append(" relationships created\n");
            }
            if (generate.contains("interactions") && !twinList.isEmpty()) {
                log.append("[3/7] Generating interaction history...\n");
                interactionsSeeded = generateInteractions(conn, context, flavor, twinList, edgePct);
                log.append("      → ").append(interactionsSeeded).append(" interactions created\n");
            }
            if (generate.contains("templates")) {
                log.append("[4/7] Generating Liquid templates...\n");
                templatesSeeded = generateTemplates(conn, context, flavor, types);
                log.append("      → ").append(templatesSeeded).append(" templates created\n");
            }
            if (generate.contains("forms")) {
                log.append("[5/7] Generating form schemas...\n");
                forms_seeded = generateForms(conn, context, flavor, types);
                log.append("      → ").append(forms_seeded).append(" forms created\n");
            }
            if (generate.contains("policies")) {
                log.append("[6/7] Generating policy manifests...\n");
                policiesSeeded = generatePolicies(conn, context, flavor, types);
                log.append("      → ").append(policiesSeeded).append(" policies created\n");
            }
            if (generate.contains("commands")) {
                log.append("[7/7] Generating command manifest...\n");
                commandsSeeded = generateCommands(conn, context, flavor, types);
                log.append("      → ").append(commandsSeeded).append(" commands created\n");
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE seeding_sessions SET status='complete', twins_seeded=?, relationships_seeded=?, interactions_seeded=?, " +
                    "templates_seeded=?, forms_seeded=?, policies_seeded=?, commands_seeded=?, completed_at=now() WHERE session_id=?::uuid")) {
                ps.setInt(1, twinsSeeded);
                ps.setInt(2, relationshipsSeeded);
                ps.setInt(3, interactionsSeeded);
                ps.setInt(4, templatesSeeded);
                ps.setInt(5, forms_seeded);
                ps.setInt(6, policiesSeeded);
                ps.setInt(7, commandsSeeded);
                ps.setString(8, sessionId);
                ps.executeUpdate();
            }

        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE seeding_sessions SET status='failed', error_message=? WHERE session_id=?::uuid")) {
                ps.setString(1, errorMsg);
                ps.setString(2, sessionId);
                ps.executeUpdate();
            }
            throw e;
        }

        JSONObject out = new JSONObject();
        out.put("success",               true);
        out.put("session_id",            sessionId);
        out.put("twins_seeded",          twinsSeeded);
        out.put("relationships_seeded",  relationshipsSeeded);
        out.put("interactions_seeded",   interactionsSeeded);
        out.put("templates_seeded",      templatesSeeded);
        out.put("forms_seeded",          forms_seeded);
        out.put("policies_seeded",       policiesSeeded);
        out.put("commands_seeded",       commandsSeeded);
        out.put("log",                   log.toString());
        OutputProcessor.send(res, 200, out);
    }

    /* ── clear seeded data ─────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void clearSeeded(Connection conn, HttpServletRequest req, HttpServletResponse res) throws Exception {
        long audits, interactions, twins, templates, forms, policies, relationships, commands;

        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM action_audit_log")) {
            audits = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM interaction_stream WHERE owner_id IN " +
                "(SELECT id FROM digital_twins WHERE external_id LIKE 'SEED_%')")) {
            interactions = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM twin_relationships WHERE from_twin_id IN " +
                "(SELECT id FROM digital_twins WHERE external_id LIKE 'SEED_%') " +
                "OR to_twin_id IN (SELECT id FROM digital_twins WHERE external_id LIKE 'SEED_%')")) {
            relationships = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM digital_twins WHERE external_id LIKE 'SEED_%'")) {
            twins = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM liquid_templates")) {
            templates = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM interaction_schema")) {
            forms = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM policy_manifest")) {
            policies = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM command_manifest WHERE command_verb NOT IN " +
                "('analyze','compare','approve','reject','escalate','hold','record')")) {
            commands = ps.executeUpdate();
        }
        StandardCommands.ensure(conn);

        JSONObject out = new JSONObject();
        out.put("success",               true);
        out.put("cleared_audits",        audits);
        out.put("cleared_twins",         twins);
        out.put("cleared_relationships", relationships);
        out.put("cleared_interactions",  interactions);
        out.put("cleared_templates",     templates);
        out.put("cleared_forms",         forms);
        out.put("cleared_policies",      policies);
        out.put("cleared_commands",      commands);
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

    private int generateRelationships(Connection conn, String context, String flavor,
                                       List<String[]> twins) throws Exception {
        // Cap to 30 entities for prompt safety
        List<String[]> sample = twins.size() > 30 ? twins.subList(0, 30) : twins;
        StringBuilder  twinList = new StringBuilder();
        for (String[] t : sample) twinList.append(t[0]).append(" (").append(t[1]).append("), ");

        String prompt =
            "You are generating realistic digital twin relationships for an institutional context graph.\n\n" +
            "INDUSTRY CONTEXT: " + context + "\n" +
            (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
            "ENTITY IDs: " + twinList + "\n\n" +
            "Generate 15-25 relationship links (edges) between the entities above.\n" +
            "relationship_type: uppercase, e.g. MEMBER_OF, GUARANTEES, WORKS_AT, SPOUSE_OF, CHILD_OF, MANAGES, REFERRED_BY\n" +
            "metadata: small JSON object with 1-2 relevant fields (e.g. joined_date, relationship_strength, or shared_resource)\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"relationships\":[{\"from_id\":\"SEED_member_001\",\"to_id\":\"SEED_group_001\"," +
            "\"type\":\"MEMBER_OF\",\"metadata\":{\"role\":\"chairperson\"}},...]}";

        JSONObject generated = extractJson(callAI(prompt));
        JSONArray  items     = arrOf(generated, "relationships");

        int count = 0;
        for (Object obj : items) {
            JSONObject item   = (JSONObject) obj;
            String     from   = str(item, "from_id");
            String     to     = str(item, "to_id");
            String     type   = str(item, "type");
            JSONObject meta   = objOf(item, "metadata");
            if (from == null || to == null || type == null) continue;

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO twin_relationships (from_twin_id, to_twin_id, relationship_type, metadata) " +
                    "SELECT f.id, t.id, ?, ?::jsonb " +
                    "FROM digital_twins f, digital_twins t " +
                    "WHERE f.external_id = ? AND t.external_id = ?")) {
                ps.setString(1, type.toUpperCase());
                ps.setString(2, meta.toJSONString());
                ps.setString(3, from);
                ps.setString(4, to);
                count += ps.executeUpdate();
            }
        }
        return count;
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
            "name should be a concise descriptive title. Do not add generated-data prefixes.\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"templates\":[{\"name\":\"Member Profile\",\"entity_type\":\"member\"," +
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
            if (name == null || name.isBlank()) continue;
            name = stripSeedPrefix(name);
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
            "schema_id must be snake_case and descriptive. Do not add generated-data prefixes.\n" +
            "Field types: text, email, number, date, select, textarea, checkbox.\n" +
            "For select fields, include: \"options\":[{\"value\":\"v\",\"label\":\"Label\"}]\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"forms\":[{\"schema_id\":\"member_kyc\",\"label\":\"Member KYC Form\"," +
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
            String actionType = f.containsKey("action_type") ? (String) f.get("action_type") : "RECORD";
            JSONArray fields  = arrOf(f, "fields");
            if (schemaId == null || schemaId.isBlank()) continue;
            schemaId = stripSeedPrefix(schemaId).toLowerCase().replaceAll("[^a-z0-9_]", "_");
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

    @SuppressWarnings("unchecked")
    private int generateCommands(Connection conn, String context, String flavor, JSONArray types) throws Exception {
        StringBuilder typeList = new StringBuilder();
        for (Object t : types) typeList.append(t).append(", ");

        String prompt =
            "You are generating institutional slash-commands for a management platform.\n\n" +
            "INDUSTRY CONTEXT: " + context + "\n" +
            (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
            "ENTITY TYPES: " + typeList + "\n\n" +
            "Generate 3-5 realistic institutional commands.\n" +
            "Rules:\n" +
            "- command_verb must be snake_case without leading slash or generated-data prefixes (e.g. disburse, collect, schedule_visit)\n" +
            "- do not generate these reserved standard commands: analyze, compare, approve, reject, escalate, hold, record\n" +
            "- label is human-readable title (e.g. Disburse Loan)\n" +
            "- args_hint shows usage (e.g. @target [amount])\n" +
            "- component_type is 'universal_action_confirm' or 'interaction_capture_form'\n" +
            "- action_type is the uppercase verb used in policy_manifest (e.g. DISBURSE)\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"commands\":[{\"command_verb\":\"disburse\",\"label\":\"...\",\"args_hint\":\"...\",\n" +
            "\"hint\":\"...\",\"component_type\":\"...\",\"action_type\":\"...\"},...]}";

        JSONObject generated = extractJson(callAI(prompt));
        JSONArray  items     = arrOf(generated, "commands");

        int count = 0;
        for (Object obj : items) {
            JSONObject c      = (JSONObject) obj;
            String verb       = str(c, "command_verb");
            String label      = str(c, "label");
            String args       = str(c, "args_hint");
            String hint       = str(c, "hint");
            String compType   = str(c, "component_type");
            String actionType = str(c, "action_type");

            if (verb == null || verb.isBlank()) continue;
            verb = stripSeedPrefix(verb).toLowerCase().replaceAll("[^a-z0-9_]", "_");
            if (verb.isBlank() || StandardCommands.isMandatory(verb)) continue;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO command_manifest (command_verb, label, args_hint, hint, component_type, action_type, is_active) " +
                    "VALUES (?, ?, ?, ?, ?, ?, true) ON CONFLICT (command_verb) DO NOTHING")) {
                ps.setString(1, verb);
                ps.setString(2, label != null ? label : verb);
                ps.setString(3, args  != null ? args  : "");
                ps.setString(4, hint  != null ? hint  : "");
                ps.setString(5, compType != null ? compType : "universal_action_confirm");
                ps.setString(6, actionType != null ? stripSeedPrefix(actionType).toUpperCase() : verb.toUpperCase());
                count += ps.executeUpdate();
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int generatePolicies(Connection conn, String context, String flavor, JSONArray types) throws Exception {
        StringBuilder typeList = new StringBuilder();
        for (Object t : types) typeList.append(t).append(", ");

        String prompt =
            "You are generating governance policy manifests for an institutional management system.\n\n" +
            "INDUSTRY CONTEXT: " + context + "\n" +
            (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
            "ENTITY TYPES: " + typeList + "\n\n" +
            "Generate 3-5 realistic PostgreSQL-based policies (GUARDRAIL mode).\n" +
            "SCHEMA:\n" +
            "  digital_twins(external_id TEXT, type TEXT, current_state JSONB)\n" +
            "  interaction_stream(owner_id UUID, intent_mapped TEXT, created_at TIMESTAMPTZ)\n\n" +
            "Rules:\n" +
            "- policy_id must be uppercase and descriptive without generated-data prefixes (e.g. BLOCK_INACTIVE_MEMBER)\n" +
            "- action_type should be uppercase verbs (e.g. DISBURSE, ONBOARD, COLLECT)\n" +
            "- query_logic MUST return COUNT(*). Use ? as placeholder for external_id.\n" +
            "- query_logic example: SELECT COUNT(*) FROM digital_twins WHERE external_id = ? AND current_state->>'status' = 'inactive'\n" +
            "- error_message is what the user sees when blocked.\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"policies\":[{\"policy_id\":\"BLOCK_INACTIVE_MEMBER\",\"action_type\":\"...\",\"description\":\"...\",\n" +
            "\"query_logic\":\"SELECT COUNT(*) FROM...\",\"error_message\":\"...\"},...]}";

        JSONObject generated = extractJson(callAI(prompt));
        JSONArray  policies  = arrOf(generated, "policies");

        int count = 0;
        for (Object obj : policies) {
            JSONObject p      = (JSONObject) obj;
            String policyId   = str(p, "policy_id");
            String actionType = str(p, "action_type");
            String desc       = str(p, "description");
            String query      = str(p, "query_logic");
            String error      = str(p, "error_message");

            if (policyId == null || policyId.isBlank()) continue;
            policyId = stripSeedPrefix(policyId).toUpperCase().replaceAll("[^A-Z0-9_]", "_");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO policy_manifest (policy_id, action_type, description, query_logic, error_message, execution_mode, is_active) " +
                    "VALUES (?, ?, ?, ?, ?, 'GUARDRAIL', true) ON CONFLICT (policy_id) DO NOTHING")) {
                ps.setString(1, policyId);
                ps.setString(2, actionType != null ? actionType.toUpperCase() : "GENERAL");
                ps.setString(3, desc != null ? desc : "Simulated policy");
                ps.setString(4, query);
                ps.setString(5, error);
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
        body.put("temperature", 0.7);
        body.put("max_tokens",  8192);

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
        if (content == null || content.isBlank())
            throw new RuntimeException("AI returned empty content");
            
        String s = content.trim();
        // Strip markdown code fences
        if (s.contains("```")) {
            int first = s.indexOf("```");
            int second = s.indexOf("```", first + 3);
            if (second > first) {
                s = s.substring(first + 3, second).trim();
                if (s.startsWith("json")) s = s.substring(4).trim();
                else if (s.indexOf('\n') > 0 && s.indexOf('\n') < 10) s = s.substring(s.indexOf('\n')).trim();
            }
        }
        
        // Find outermost JSON structure
        int start = s.indexOf('{');
        int end   = s.lastIndexOf('}');
        if (start < 0) throw new RuntimeException("No JSON found in response");
        
        s = s.substring(start, end + 1);
        
        try {
            return (JSONObject) new JSONParser().parse(s);
        } catch (Exception e) {
            // Case: AI returned multiple objects: {..}, {..} or {..}{..}
            try {
                String wrapped = s.replaceAll("}\\s*\\{", "},{");
                Object multi = new JSONParser().parse("[" + wrapped + "]");
                if (multi instanceof JSONArray) {
                    JSONArray arr = (JSONArray) multi;
                    JSONObject merged = new JSONObject();
                    for (Object obj : arr) {
                        if (obj instanceof JSONObject) {
                            JSONObject o = (JSONObject) obj;
                            for (Object key : o.keySet()) {
                                Object val = o.get(key);
                                if (val instanceof JSONArray) {
                                    JSONArray existing = (JSONArray) merged.get(key);
                                    if (existing == null) {
                                        existing = new JSONArray();
                                        merged.put(key, existing);
                                    }
                                    existing.addAll((JSONArray) val);
                                } else {
                                    merged.put(key, val);
                                }
                            }
                        }
                    }
                    System.out.println("[Seeding] Successfully merged multi-object AI response.");
                    return merged;
                }
            } catch (Exception ignore) {}
            
            // Final attempt: repair truncated JSON
            try {
                String repaired = repairJson(s);
                return (JSONObject) new JSONParser().parse(repaired);
            } catch (Exception e2) {
                System.err.println("[Seeding] Failed to parse/repair JSON. Length: " + s.length());
                System.err.println("[Seeding] Raw start: " + (s.length() > 100 ? s.substring(0, 100) : s));
                System.err.println("[Seeding] Raw end: " + (s.length() > 100 ? s.substring(s.length() - 100) : s));
                throw new RuntimeException("AI response was not valid JSON: " + e.getMessage());
            }
        }
    }

    private String repairJson(String s) {
        StringBuilder stack = new StringBuilder();
        boolean inString = false;
        boolean escape = false;
        
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; }
            else if (c == '\\') { escape = true; }
            else if (c == '\"') { inString = !inString; }
            else if (!inString) {
                if (c == '{') stack.append('{');
                else if (c == '[') stack.append('[');
                else if (c == '}') {
                    if (stack.length() > 0 && stack.charAt(stack.length() - 1) == '{')
                        stack.deleteCharAt(stack.length() - 1);
                }
                else if (c == ']') {
                    if (stack.length() > 0 && stack.charAt(stack.length() - 1) == '[')
                        stack.deleteCharAt(stack.length() - 1);
                }
            }
            clean.append(c);
        }
        
        if (inString) clean.append('\"');
        
        String res = clean.toString().trim();
        while (res.endsWith(",") || res.endsWith("{") || res.endsWith("[")) {
            if (res.endsWith("{") || res.endsWith("[")) {
                if (stack.length() > 0) stack.deleteCharAt(stack.length() - 1);
            }
            res = res.substring(0, res.length() - 1).trim();
        }
        
        StringBuilder repaired = new StringBuilder(res);
        for (int i = stack.length() - 1; i >= 0; i--) {
            char open = stack.charAt(i);
            if (open == '{') repaired.append('}');
            else if (open == '[') repaired.append(']');
        }
        
        return repaired.toString();
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

    private String stripSeedPrefix(String value) {
        if (value == null) return null;
        return value.trim()
            .replaceFirst("^\\[SIM\\]\\s*", "")
            .replaceFirst("(?i)^SIM[_\\s-]+", "");
    }

    @SuppressWarnings("unchecked")
    private JSONArray defaultGenerate() {
        JSONArray a = new JSONArray();
        a.add("twins"); a.add("relationships"); a.add("interactions"); a.add("templates"); a.add("forms"); a.add("policies"); a.add("commands");
        return a;
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
