package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GET  /api/seeding                 → stats, entity types, session history
 * POST /api/seeding { action }
 *   action=suggest_types — AI-suggested entity types from institutional context
 *   action=start         — run full synthesis from institutional DNA
 *   action=clear         — delete seeded twins and generated configuration
 *   action=expand        — natural-language expansion of existing seeded data
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

    /* ── GET ───────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void get(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

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

            // Mock config download: GET /api/seeding?format=mock[&session_id=xxx]
            String format    = req.getParameter("format");
            String dlSession = req.getParameter("session_id");
            if ("mock".equals(format)) {
                downloadMockConfig(conn, req, res, dlSession); // null = latest session
                return;
            }

            // Session history
            JSONArray sessions = new JSONArray();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT session_id::text, industry_context, status, " +
                    "twins_seeded, relationships_seeded, interactions_seeded, templates_seeded, forms_seeded, policies_seeded, commands_seeded, " +
                    "to_char(created_at, 'DD Mon YYYY HH24:MI') AS created, error_message, " +
                    "mock_data IS NOT NULL AS has_mock_data " +
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
                    s.put("commands_seeded",      rs.getInt("commands_seeded"));
                    s.put("created",              rs.getString("created"));
                    s.put("error_message",        rs.getString("error_message"));
                    s.put("has_mock_data",        rs.getBoolean("has_mock_data"));
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

    /* ── POST ──────────────────────────────────────────────────────────── */

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            pool = new PoolDB();
            conn = pool.getConnection();

            JSONObject body   = InputProcessor.getInput(req);
            if (body == null) {
                OutputProcessor.errorResponse(res, 400, "Bad request", "Invalid or missing JSON body", req.getRequestURI());
                return;
            }
            String action = str(body, "action");

            switch (action == null ? "" : action) {
                case "suggest_types":    suggestTypes(conn, body, req, res);    break;
                case "suggest_commands": suggestCommands(conn, body, req, res); break;
                case "start":            startSeeding(conn, body, req, res);    break;
                case "clear":            clearSeeded(conn, req, res);           break;
                case "expand":           expandSeeding(conn, body, req, res);   break;
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

    /* ── suggest entity types ──────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void suggestTypes(Connection conn, JSONObject body,
                               HttpServletRequest req, HttpServletResponse res) throws Exception {
        String context  = str(body, "industry_context");
        String typeHint = str(body, "type_hint");
        if ((context == null || context.isBlank()) && (typeHint == null || typeHint.isBlank()))
            throw new IllegalArgumentException("industry_context or type_hint is required");
        if (VLLM_URL == null || VLLM_MODEL == null)
            throw new IllegalStateException("AI engine not configured — set VLLM_URL and VLLM_MODEL");

        String prompt =
            "Given the following institutional context, suggest 3-6 entity types that should be modeled as digital twins.\n\n" +
            "INDUSTRY CONTEXT: " + (context != null ? context : "") + "\n" +
            (typeHint != null && !typeHint.isBlank() ? "USER HINT: " + typeHint + "\n" : "") +
            "\nRules:\n" +
            "- entity type names must be singular, lowercase, snake_case (e.g. member, loan_officer, branch)\n" +
            "- suggest realistic counts appropriate for the described institution\n" +
            "- count range: 5–50 per type\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"types\":[{\"name\":\"member\",\"count\":20},{\"name\":\"officer\",\"count\":5},...]}";

        JSONObject generated = extractJson(callAI(prompt));
        JSONArray  types     = arrOf(generated, "types");

        JSONObject out = new JSONObject();
        out.put("success", true);
        out.put("types",   types);
        OutputProcessor.send(res, 200, out);
    }

    /* ── start seeding ─────────────────────────────────────────────────── */

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
                "VALUES (?, ?, ?::jsonb, ?, 'running') RETURNING session_id::text")) {
            ps.setString(1, context.trim());
            ps.setString(2, flavor != null ? flavor.trim() : "");
            ps.setString(3, types.toJSONString());
            ps.setInt(4, edgePct);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                sessionId = rs.getString(1);
            }
        }

        int twinsSeeded = 0, relationshipsSeeded = 0, interactionsSeeded = 0,
            templatesSeeded = 0, formsSeeded = 0, commandsSeeded = 0, policiesSeeded = 0;
        List<String[]> twinList = new ArrayList<>();
        JSONObject mockConfig = new JSONObject();
        JSONArray commandsPayload = arrOf(body, "commands");
        StringBuilder log = new StringBuilder();

        try {
            if (generate.contains("twins")) {
                log.append("[1/8] Generating digital twins...\n");
                twinList = generateTwins(conn, context, flavor, types, counts, edgePct);
                twinsSeeded = twinList.size();
                log.append("      → ").append(twinsSeeded).append(" twins created\n");
            }
            if (generate.contains("relationships") && !twinList.isEmpty()) {
                log.append("[2/8] Generating context relationships...\n");
                relationshipsSeeded = generateRelationships(conn, context, flavor, twinList);
                log.append("      → ").append(relationshipsSeeded).append(" relationships created\n");
            }
            if (generate.contains("interactions") && !twinList.isEmpty()) {
                log.append("[3/8] Generating interaction history...\n");
                interactionsSeeded = generateInteractions(conn, context, flavor, twinList, edgePct);
                log.append("      → ").append(interactionsSeeded).append(" interactions created\n");
            }
            if (generate.contains("mock_services")) {
                log.append("[4/8] Generating mock service config...\n");
                mockConfig = generateMockServices(conn, context, flavor, types);
                JSONObject svcs = objOf(mockConfig, "services");
                log.append("      → ").append(svcs.size()).append(" services configured\n");
            }
            if (generate.contains("templates")) {
                log.append("[5/8] Generating context cards...\n");
                templatesSeeded = generateTemplates(conn, context, flavor, types, mockConfig);
                log.append("      → ").append(templatesSeeded).append(" templates created\n");
            }
            if (generate.contains("forms")) {
                log.append("[6/8] Generating input manifests...\n");
                formsSeeded = generateForms(conn, context, flavor, types, mockConfig);
                log.append("      → ").append(formsSeeded).append(" forms created\n");
            }
            if (generate.contains("commands")) {
                log.append("[7/8] Generating commands & guardrails...\n");
                commandsSeeded = generateCommands(conn, context, flavor, types, commandsPayload);
                log.append("      → ").append(commandsSeeded).append(" commands created\n");
            }
            if (generate.contains("policies")) {
                log.append("[8/8] Generating additional policy manifests...\n");
                policiesSeeded = generatePolicies(conn, context, flavor, types);
                log.append("      → ").append(policiesSeeded).append(" policies created\n");
            }

            String mockDataJson = mockConfig.isEmpty() ? null : mockConfig.toJSONString();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE seeding_sessions SET status='complete', twins_seeded=?, relationships_seeded=?, interactions_seeded=?, " +
                    "templates_seeded=?, forms_seeded=?, policies_seeded=?, commands_seeded=?, mock_data=?::jsonb, completed_at=now() WHERE session_id=?::uuid")) {
                ps.setInt(1, twinsSeeded);
                ps.setInt(2, relationshipsSeeded);
                ps.setInt(3, interactionsSeeded);
                ps.setInt(4, templatesSeeded);
                ps.setInt(5, formsSeeded);
                ps.setInt(6, policiesSeeded);
                ps.setInt(7, commandsSeeded);
                ps.setString(8, mockDataJson);
                ps.setString(9, sessionId);
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
        out.put("forms_seeded",          formsSeeded);
        out.put("commands_seeded",       commandsSeeded);
        out.put("policies_seeded",       policiesSeeded);
        out.put("mock_services",         (long) objOf(mockConfig, "services").size());
        out.put("log",                   log.toString());
        OutputProcessor.send(res, 200, out);
    }

    /* ── clear seeded data ─────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void clearSeeded(Connection conn, HttpServletRequest req, HttpServletResponse res) throws Exception {
        long audits, interactions, twins, templates, forms, commands, policies, relationships;

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
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM liquid_templates")) {
            templates = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM interaction_schema")) {
            forms = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM command_manifest")) {
            commands = ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM policy_manifest")) {
            policies = ps.executeUpdate();
        }

        JSONObject out = new JSONObject();
        out.put("success",               true);
        out.put("cleared_audits",        audits);
        out.put("cleared_twins",         twins);
        out.put("cleared_relationships", relationships);
        out.put("cleared_interactions",  interactions);
        out.put("cleared_templates",     templates);
        out.put("cleared_forms",         forms);
        out.put("cleared_commands",      commands);
        out.put("cleared_policies",      policies);
        OutputProcessor.send(res, 200, out);
    }

    /* ── expand with natural-language instruction ──────────────────────── */

    @SuppressWarnings("unchecked")
    private void expandSeeding(Connection conn, JSONObject body,
                                HttpServletRequest req, HttpServletResponse res) throws Exception {
        String instruction = str(body, "instruction");
        String context     = str(body, "industry_context");
        if (instruction == null || instruction.isBlank())
            throw new IllegalArgumentException("instruction is required");
        if (VLLM_URL == null || VLLM_MODEL == null)
            throw new IllegalStateException("AI engine not configured");

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

    /* ── AI generation phases ──────────────────────────────────────────── */

    private static final int TWINS_BATCH = 20; // max entities per AI call

    @SuppressWarnings("unchecked")
    private List<String[]> generateTwins(Connection conn, String context, String flavor,
                                          JSONArray types, JSONObject counts, int edgePct) throws Exception {
        List<String[]> inserted = new ArrayList<>();

        for (Object typeObj : types) {
            String type  = (String) typeObj;
            int    total = counts.containsKey(type) ? ((Number) counts.get(type)).intValue() : 10;
            int    done  = 0;

            while (done < total) {
                int batch  = Math.min(TWINS_BATCH, total - done);
                int offset = done + 1; // starting index for external_id numbering

                String prompt =
                    "You are generating synthetic digital twins for an institutional management platform.\n\n" +
                    "INDUSTRY CONTEXT: " + context + "\n" +
                    (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
                    "EDGE CASES: " + edgePct + "% of entities should represent problem cases " +
                    "(overdue payments, inactive status, flagged, at-risk, suspended)\n\n" +
                    "Generate exactly " + batch + " entities of type '" + type + "'.\n" +
                    "Rules:\n" +
                    "- external_id MUST be 'SEED_" + type + "_NNN' where NNN is zero-padded from " +
                      String.format("%03d", offset) + " to " + String.format("%03d", offset + batch - 1) + "\n" +
                    "- state must contain: name, status, and 4-6 industry-specific fields\n" +
                    "- Use realistic local names and domain values; vary across all entries\n\n" +
                    "Return ONLY valid JSON, no markdown, no explanation:\n" +
                    "{\"twins\":[{\"external_id\":\"SEED_" + type + "_" + String.format("%03d", offset) +
                    "\",\"type\":\"" + type + "\",\"state\":{\"name\":\"...\",\"status\":\"active\",...}},...]}";

                try {
                    JSONObject generated = extractJson(callAI(prompt));
                    JSONArray  twinsArr  = arrOf(generated, "twins");
                    int batchInserted = 0;
                    for (Object obj : twinsArr) {
                        JSONObject t     = (JSONObject) obj;
                        String     extId = str(t, "external_id");
                        String     tType = str(t, "type");
                        JSONObject state = objOf(t, "state");
                        if (extId == null || !extId.startsWith("SEED_")) continue;
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO digital_twins (external_id, type, current_state) VALUES (?, ?, ?::jsonb) " +
                                "ON CONFLICT (external_id) DO NOTHING RETURNING external_id")) {
                            ps.setString(1, extId);
                            ps.setString(2, tType != null ? tType : type);
                            ps.setString(3, state.toJSONString());
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    inserted.add(new String[]{ extId, tType != null ? tType : type });
                                    batchInserted++;
                                }
                            }
                        }
                    }
                    done += batchInserted > 0 ? batchInserted : batch; // advance even if partial
                } catch (Exception e) {
                    System.err.println("[Seeding] generateTwins batch failed for type " + type + ": " + e.getMessage());
                    done += batch; // skip this batch and continue
                }
            }
        }
        return inserted;
    }

    private int generateRelationships(Connection conn, String context, String flavor,
                                       List<String[]> twins) throws Exception {
        List<String[]> sample = twins.size() > 30 ? twins.subList(0, 30) : twins;
        StringBuilder  twinList = new StringBuilder();
        for (String[] t : sample) twinList.append(t[0]).append(" (").append(t[1]).append("), ");
        Map<String, String> typeById = new HashMap<>();
        for (String[] t : twins) typeById.put(t[0], t[1] != null ? t[1].toLowerCase() : "");

        String prompt =
            "You are generating realistic digital twin relationships for an institutional context graph.\n\n" +
            "INDUSTRY CONTEXT: " + context + "\n" +
            (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
            "ENTITY IDs: " + twinList + "\n\n" +
            "Generate 15-25 relationship links (edges) between the entities above.\n" +
            "Use relationship types that make sense for the two entity types.\n" +
            "Valid examples:\n" +
            "- officer WORKS_AT branch or office, never system\n" +
            "- member MEMBER_OF group\n" +
            "- officer MANAGES member/group/branch\n" +
            "- member GUARANTEES member\n" +
            "- member APPLIED_FOR product/service/system only if the target is an actual application destination\n" +
            "Do not create Officer WORKS_AT System, Member WORKS_AT System, or any WORKS_AT edge to a system entity.\n" +
            "Do not link human actors to systems unless the relationship verb clearly describes usage or access, such as USES_SYSTEM or HAS_SYSTEM_ACCESS.\n" +
            "relationship_type must be uppercase, e.g. MEMBER_OF, GUARANTEES, WORKS_AT, MANAGES, REFERRED_BY, USES_SYSTEM\n" +
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
            type = type.toUpperCase();
            if (!isPlausibleRelationship(typeById.get(from), type, typeById.get(to))) continue;

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO twin_relationships (from_twin_id, to_twin_id, relationship_type, metadata) " +
                    "SELECT f.id, t.id, ?, ?::jsonb " +
                    "FROM digital_twins f, digital_twins t " +
                    "WHERE f.external_id = ? AND t.external_id = ?")) {
                ps.setString(1, type);
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

    /* ── generate mock service config ─────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private JSONObject generateMockServices(Connection conn, String context, String flavor, JSONArray types) throws Exception {
        StringBuilder typeList = new StringBuilder();
        for (Object t : types) typeList.append(t).append(", ");

        String prompt =
            "You are configuring external service integrations for an institutional intelligence platform.\n\n" +
            "INDUSTRY CONTEXT: " + context + "\n" +
            (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
            "ENTITY TYPES: " + typeList + "\n\n" +
            "For each entity type, identify the most relevant external data service that would typically be integrated " +
            "(e.g. credit bureau for members, HR system for officers, property registry for assets).\n" +
            "Generate 3-5 data fields per service that it would expose for that entity type.\n\n" +
            "Field definitions:\n" +
            "- int: { \"type\":\"int\", \"min\":N, \"max\":N } — integer in range\n" +
            "- enum: { \"type\":\"enum\", \"values\":[\"a\",\"b\",\"c\"] } — pick one from list\n" +
            "- date: { \"type\":\"date\" } — recent ISO date\n" +
            "- text: { \"type\":\"text\" } — short descriptive string\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"services\":{\"member\":{\"service_name\":\"Credit Bureau\",\"fields\":{" +
            "\"credit_score\":{\"type\":\"int\",\"min\":300,\"max\":850}," +
            "\"bureau_status\":{\"type\":\"enum\",\"values\":[\"Clear\",\"Flagged\",\"Under Review\"]}," +
            "\"last_bureau_check\":{\"type\":\"date\"}}}}}";

        JSONObject generated = extractJson(callAI(prompt));
        JSONObject services  = objOf(generated, "services");

        // Register PULL services in service_registry (idempotent via ON CONFLICT)
        for (Object key : services.keySet()) {
            String entityType = (String) key;
            JSONObject svc    = (JSONObject) services.get(key);
            String svcName    = svc.containsKey("service_name") ? (String) svc.get("service_name") : entityType.toUpperCase() + "_SERVICE";
            String identifier = ("MOCK_" + svcName.toUpperCase().replaceAll("[^A-Z0-9_]", "_"));
            String apiBaseUrl = "http://host.docker.internal:9090/" + entityType;
            String authConfig = "{\"header\":\"X-Mock-Key\",\"secret\":\"nexus-demo\"}";

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO service_registry (identifier, api_base_url, auth_config, service_type, entity_type, status) " +
                    "VALUES (?, ?, ?::jsonb, 'PULL', ?, 'Active') " +
                    "ON CONFLICT (identifier) DO UPDATE SET " +
                    "api_base_url=EXCLUDED.api_base_url, entity_type=EXCLUDED.entity_type, status='Active'")) {
                ps.setString(1, identifier);
                ps.setString(2, apiBaseUrl);
                ps.setString(3, authConfig);
                ps.setString(4, entityType);
                ps.executeUpdate();
            }
        }

        // Collect seeded external_ids per entity type for INGEST push config
        JSONObject externalIdsByType = new JSONObject();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT type, external_id FROM digital_twins WHERE external_id LIKE 'SEED_%' ORDER BY external_id")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString("type");
                    String e = rs.getString("external_id");
                    if (!externalIdsByType.containsKey(t)) externalIdsByType.put(t, new JSONArray());
                    ((JSONArray) externalIdsByType.get(t)).add(e);
                }
            }
        }

        // Register INGEST services and extend each service entry with ingest config
        for (Object key : services.keySet()) {
            String entityType      = (String) key;
            JSONObject svc         = (JSONObject) services.get(key);
            String svcName         = svc.containsKey("service_name") ? (String) svc.get("service_name") : entityType.toUpperCase() + "_SERVICE";
            String ingestId        = "MOCK_" + svcName.toUpperCase().replaceAll("[^A-Z0-9_]", "_") + "_INGEST";
            String ingestAuth      = "{\"header\":\"X-Ingest-Key\",\"secret\":\"ingest-demo\"}";

            // Build stream_tmpl from field names: "Credit Bureau update for {external_id}: credit_score={credit_score}, ..."
            JSONObject fields = objOf(svc, "fields");
            StringBuilder tmpl = new StringBuilder(svcName).append(" update for {external_id}");
            if (!fields.isEmpty()) {
                tmpl.append(": ");
                boolean first = true;
                for (Object fk : fields.keySet()) {
                    if (!first) tmpl.append(", ");
                    first = false;
                    tmpl.append(fk).append("={").append(fk).append("}");
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO service_registry (identifier, api_base_url, auth_config, service_type, entity_type, stream_tmpl, status) " +
                    "VALUES (?, '', ?::jsonb, 'INGEST', ?, ?, 'Active') " +
                    "ON CONFLICT (identifier) DO UPDATE SET " +
                    "entity_type=EXCLUDED.entity_type, stream_tmpl=EXCLUDED.stream_tmpl, status='Active'")) {
                ps.setString(1, ingestId);
                ps.setString(2, ingestAuth);
                ps.setString(3, entityType);
                ps.setString(4, tmpl.toString());
                ps.executeUpdate();
            }

            // Extend service entry in mock-data.json with ingest push config
            svc.put("ingest_identifier",       ingestId);
            svc.put("ingest_auth_header",       "X-Ingest-Key");
            svc.put("ingest_auth_secret",       "ingest-demo");
            svc.put("ingest_interval_seconds",  30L);
            svc.put("external_ids", externalIdsByType.containsKey(entityType)
                ? externalIdsByType.get(entityType) : new JSONArray());
        }

        // Build full mock-data.json structure
        JSONObject mockConfig = new JSONObject();
        mockConfig.put("port",             9090L);
        mockConfig.put("auth_header",      "X-Mock-Key");
        mockConfig.put("auth_secret",      "nexus-demo");
        mockConfig.put("nexus_ingest_url", "http://host.docker.internal:8080/api/ingest");
        mockConfig.put("services",         services);
        return mockConfig;
    }

    /* ── download mock config as file ─────────────────────────────────── */

    private void downloadMockConfig(Connection conn, HttpServletRequest req,
                                     HttpServletResponse res, String sessionId) throws Exception {
        String sql = (sessionId != null && !sessionId.isBlank())
            ? "SELECT mock_data::text FROM seeding_sessions WHERE session_id=?::uuid"
            : "SELECT mock_data::text FROM seeding_sessions WHERE mock_data IS NOT NULL ORDER BY created_at DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (sessionId != null && !sessionId.isBlank()) ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getString("mock_data") == null) {
                    OutputProcessor.errorResponse(res, 404, "Not found",
                        "No mock config found. Run seeding first.", req.getRequestURI());
                    return;
                }
                byte[] bytes = rs.getString("mock_data").getBytes(StandardCharsets.UTF_8);
                res.setContentType("application/json");
                res.setHeader("Content-Disposition", "attachment; filename=mock-data.json");
                res.setContentLength(bytes.length);
                res.getOutputStream().write(bytes);
                res.getOutputStream().flush();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private int generateTemplates(Connection conn, String context, String flavor, JSONArray types, JSONObject mockConfig) throws Exception {
        int total = 0;
        JSONObject mockServices = objOf(mockConfig, "services");
        for (Object typeObj : types) {
            String type = typeObj.toString();

            // Build live fields section for this entity type if mock config exists
            String liveFieldsSection = "";
            if (mockServices.containsKey(type)) {
                JSONObject svc = (JSONObject) mockServices.get(type);
                JSONObject fields = objOf(svc, "fields");
                if (!fields.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("EXTERNAL LIVE DATA: This entity type has a registered PULL service that exposes these fields:\n");
                    for (Object fk : fields.keySet()) sb.append("  - {{ entity.live.").append(fk).append(" }}\n");
                    sb.append("Include 1-2 of these live data variables in the template to show real-time enrichment.\n");
                    liveFieldsSection = sb.toString();
                }
            }

            String prompt =
                "You are generating a Liquid HTML dashboard template for an institutional management system.\n\n" +
                "INDUSTRY CONTEXT: " + context + "\n" +
                (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
                "ENTITY TYPE: " + type + "\n" +
                liveFieldsSection + "\n" +
                "Generate exactly ONE template for this entity type. It is a compact HTML card for a profile dashboard.\n" +
                "Use Liquid variables: {{ entity.external_id }}, {{ entity.current_state.name }}, {{ entity.current_state.status }}, etc.\n" +
                "State fields use the prefix {{ entity.current_state.FIELD_KEY }} — use exact field keys from the domain model.\n" +
                (liveFieldsSection.isEmpty() ? "" : "Also use the live data variables listed above where relevant.\n") +
                "Keep HTML simple: a card div with a title, status badge, and 3-5 key data fields. No CSS styles.\n" +
                "name should be a concise descriptive title (e.g. 'Member Profile').\n\n" +
                "Return ONLY valid JSON, no markdown:\n" +
                "{\"templates\":[{\"name\":\"Member Profile\",\"entity_type\":\"" + type + "\"," +
                "\"html_content\":\"<div><h3>{{ entity.current_state.name }}</h3></div>\",\"condition_sql\":\"\"}]}";

            try {
                JSONObject generated = extractJson(callAI(prompt));
                JSONArray  templates = arrOf(generated, "templates");
                for (Object obj : templates) {
                    JSONObject t   = (JSONObject) obj;
                    String name    = str(t, "name");
                    String entType = str(t, "entity_type");
                    String html    = t.containsKey("html_content") ? (String) t.get("html_content") : "<div>Template</div>";
                    String condSql = t.containsKey("condition_sql") ? (String) t.get("condition_sql") : "";
                    if (name == null || name.isBlank()) continue;
                    name = stripSeedPrefix(name);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO liquid_templates (name, entity_type, html_content, condition_sql, is_active) " +
                            "VALUES (?, ?, ?, ?, true) ON CONFLICT DO NOTHING")) {
                        ps.setString(1, name);
                        ps.setString(2, entType != null ? entType : type);
                        ps.setString(3, html);
                        ps.setString(4, condSql);
                        total += ps.executeUpdate();
                    }
                }
            } catch (Exception e) {
                System.err.println("[Seeding] generateTemplates failed for type " + type + ": " + e.getMessage());
            }
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private int generateForms(Connection conn, String context, String flavor, JSONArray types, JSONObject mockConfig) throws Exception {
        int total = 0;
        JSONObject mockServices = objOf(mockConfig, "services");
        for (Object typeObj : types) {
            String type = typeObj.toString();

            // Build prefill hint for this entity type
            String prefillSection = "";
            if (mockServices.containsKey(type)) {
                JSONObject svc = (JSONObject) mockServices.get(type);
                JSONObject fields = objOf(svc, "fields");
                if (!fields.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("PRE-FILL AVAILABLE: The following live data fields can pre-populate form inputs " +
                              "using \"prefill\":\"live.FIELD_KEY\" on a field definition:\n");
                    for (Object fk : fields.keySet()) sb.append("  - live.").append(fk).append("\n");
                    sb.append("Add \"prefill\":\"live.FIELD_KEY\" to fields whose values can be sourced from live data.\n");
                    prefillSection = sb.toString();
                }
            }

            String prompt =
                "You are generating data capture form schemas for an institutional management system.\n\n" +
                "INDUSTRY CONTEXT: " + context + "\n" +
                (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
                "ENTITY TYPE: " + type + "\n" +
                prefillSection + "\n" +
                "Generate exactly 1 realistic form schema for this entity type aligned to the industry context.\n" +
                "schema_id must be snake_case (e.g. member_kyc). Do not add generated-data prefixes.\n" +
                "Include 3-5 fields. Field types: text, email, number, date, select, textarea, checkbox.\n" +
                "For select fields, include: \"options\":[{\"value\":\"v\",\"label\":\"Label\"}]\n" +
                (prefillSection.isEmpty() ? "" : "For applicable fields, add \"prefill\":\"live.FIELD_KEY\".\n") +
                "The stream_tmpl must use single-brace tokens matching field keys, e.g. \"{field_key} updated to {value}\".\n" +
                "\nReturn ONLY valid JSON, no markdown:\n" +
                "{\"forms\":[{\"schema_id\":\"" + type + "_update\",\"label\":\"" + type + " Update Form\"," +
                "\"applies_to\":\"" + type + "\",\"action_type\":\"" + type.toUpperCase() + "_UPDATE\"," +
                "\"stream_tmpl\":\"" + type + " record updated: name={name}\"," +
                "\"fields\":[{\"key\":\"name\",\"label\":\"Full Name\",\"type\":\"text\",\"required\":true}]}]}";

            try {
                JSONObject generated = extractJson(callAI(prompt));
                JSONArray  forms     = arrOf(generated, "forms");
                for (Object obj : forms) {
                    JSONObject f      = (JSONObject) obj;
                    String schemaId   = str(f, "schema_id");
                    String label      = f.containsKey("label")       ? (String) f.get("label")       : type + " Form";
                    String appliesTo  = f.containsKey("applies_to")  ? (String) f.get("applies_to")  : type;
                    String actionType = f.containsKey("action_type") ? (String) f.get("action_type") : type.toUpperCase() + "_RECORD";
                    JSONArray fields  = arrOf(f, "fields");
                    if (schemaId == null || schemaId.isBlank()) continue;
                    schemaId = stripSeedPrefix(schemaId).toLowerCase().replaceAll("[^a-z0-9_]", "_");

                    // Build stream_tmpl: use LLM-generated one or derive from action_type + field keys
                    String streamTmpl = f.containsKey("stream_tmpl") ? str(f, "stream_tmpl") : "";
                    if (streamTmpl.isEmpty()) {
                        StringBuilder sb = new StringBuilder(actionType.toLowerCase().replace('_', ' '));
                        for (Object fo : fields) {
                            String fk = str((JSONObject) fo, "key");
                            if (!fk.isEmpty()) { sb.append(": ").append(fk).append("={").append(fk).append("}"); break; }
                        }
                        streamTmpl = sb.toString();
                    }

                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO interaction_schema (schema_id, label, applies_to, action_type, fields, stream_tmpl, is_active) " +
                            "VALUES (?, ?, ?, ?, ?::jsonb, ?, true) ON CONFLICT (schema_id) DO NOTHING")) {
                        ps.setString(1, schemaId);
                        ps.setString(2, label);
                        ps.setString(3, appliesTo);
                        ps.setString(4, actionType.toUpperCase());
                        ps.setString(5, fields.toJSONString());
                        ps.setString(6, streamTmpl);
                        total += ps.executeUpdate();
                    }
                }
            } catch (Exception e) {
                System.err.println("[Seeding] generateForms failed for type " + type + ": " + e.getMessage());
            }
        }
        return total;
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

    /* ── suggest commands ─────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private void suggestCommands(Connection conn, JSONObject body,
                                  HttpServletRequest req, HttpServletResponse res) throws Exception {
        String context = str(body, "industry_context");
        JSONArray types = arrOf(body, "entity_types");
        if (VLLM_URL == null || VLLM_MODEL == null)
            throw new IllegalStateException("AI engine not configured — set VLLM_URL and VLLM_MODEL");

        StringBuilder typeList = new StringBuilder();
        for (Object t : types) typeList.append(t).append(", ");

        String prompt =
            "You are designing command verbs for an institutional operations platform.\n\n" +
            "INDUSTRY CONTEXT: " + (context != null ? context : "institutional enterprise") + "\n" +
            "ENTITY TYPES: " + typeList + "\n\n" +
            "Suggest 3-6 institution-specific command verbs that field staff would use daily.\n" +
            "These are slash commands like /disburse, /onboard, /verify.\n\n" +
            "Rules:\n" +
            "- verb must be a single lowercase word (no spaces)\n" +
            "- label is 2-3 words, title case\n" +
            "- description explains what the command does in one sentence\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"commands\":[{\"verb\":\"disburse\",\"label\":\"Disburse Loan\",\"description\":\"Release approved loan funds to a member\"},...]}";

        JSONObject generated = extractJson(callAI(prompt));
        JSONArray commands   = arrOf(generated, "commands");

        JSONObject out = new JSONObject();
        out.put("success",  true);
        out.put("commands", commands);
        OutputProcessor.send(res, 200, out);
    }

    /* ── generate commands with paired policies ────────────────────────── */

    @SuppressWarnings("unchecked")
    private int generateCommands(Connection conn, String context, String flavor,
                                  JSONArray types, JSONArray userCommands) throws Exception {
        StringBuilder typeList = new StringBuilder();
        for (Object t : types) typeList.append(t).append(", ");

        String commandHint = "";
        if (userCommands != null && !userCommands.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object o : userCommands) {
                JSONObject c = (JSONObject) o;
                sb.append(str(c, "verb")).append(" (").append(str(c, "label")).append("), ");
            }
            commandHint = "USER-SPECIFIED COMMANDS: " + sb + "\n";
        }

        String prompt =
            "You are configuring commands and guardrail policies for an institutional operations platform.\n\n" +
            "INDUSTRY CONTEXT: " + context + "\n" +
            (flavor != null && !flavor.isBlank() ? "DATA FLAVOR: " + flavor + "\n" : "") +
            "ENTITY TYPES: " + typeList + "\n" +
            commandHint + "\n" +
            "Generate 3-6 institution-specific command definitions. For each command, include 1-2 GUARDRAIL policies.\n\n" +
            "Rules:\n" +
            "- verb: lowercase single word\n" +
            "- action_type: uppercase version of verb\n" +
            "- entity_type: the primary entity type this command acts on — must be one of the ENTITY TYPES listed above\n" +
            "- component_type: 'interaction_capture_form' if the command requires data entry, else 'universal_action_confirm'\n" +
            "- policy query_logic MUST use: SELECT COUNT(*) FROM digital_twins WHERE external_id=? AND ...\n" +
            "- policy error_message is what the user sees when blocked\n\n" +
            "Return ONLY valid JSON, no markdown:\n" +
            "{\"commands\":[{\"verb\":\"disburse\",\"label\":\"Disburse Loan\",\"action_type\":\"DISBURSE\"," +
            "\"entity_type\":\"member\",\"component_type\":\"interaction_capture_form\",\"hint\":\"Release approved loan funds\"," +
            "\"policies\":[{\"policy_id\":\"BLOCK_INACTIVE_DISBURSE\",\"description\":\"Block inactive members\"," +
            "\"query_logic\":\"SELECT COUNT(*) FROM digital_twins WHERE external_id=? AND current_state->>'status'='inactive'\"," +
            "\"error_message\":\"Member is inactive.\"}]},...]}";

        JSONObject generated = extractJson(callAI(prompt));
        JSONArray  commands  = arrOf(generated, "commands");

        int count = 0;
        for (Object obj : commands) {
            JSONObject cmd    = (JSONObject) obj;
            String verb       = str(cmd, "verb");
            String label      = str(cmd, "label");
            String actionType = str(cmd, "action_type");
            String compType   = str(cmd, "component_type");
            String hint       = str(cmd, "hint");
            JSONArray policies = arrOf(cmd, "policies");

            if (verb == null || verb.isBlank()) continue;
            verb       = verb.toLowerCase().replaceAll("[^a-z0-9_]", "");
            actionType = (actionType != null ? actionType : verb.toUpperCase()).toUpperCase();
            if (compType == null || compType.isBlank()) compType = "universal_action_confirm";

            String entityType = str(cmd, "entity_type");
            if (entityType != null) entityType = entityType.toLowerCase().trim();

            // linked_form: try exact action_type match first, fall back to entity_type match
            String linkedForm = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT schema_id FROM interaction_schema WHERE action_type=? LIMIT 1")) {
                ps.setString(1, actionType);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) linkedForm = rs.getString("schema_id");
                }
            }
            if (linkedForm == null && entityType != null && !entityType.isBlank()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT schema_id FROM interaction_schema WHERE applies_to=? LIMIT 1")) {
                    ps.setString(1, entityType);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) linkedForm = rs.getString("schema_id");
                    }
                }
            }

            // linked_template: look up by entity_type
            String linkedTemplate = null;
            if (entityType != null && !entityType.isBlank()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT template_id::text FROM liquid_templates WHERE entity_type=? AND is_active=true LIMIT 1")) {
                    ps.setString(1, entityType);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) linkedTemplate = rs.getString("template_id");
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO command_manifest (command_verb, label, action_type, component_type, hint, entity_type, linked_form, linked_template) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?::uuid) " +
                    "ON CONFLICT (command_verb) DO UPDATE SET label=EXCLUDED.label, action_type=EXCLUDED.action_type, " +
                    "component_type=EXCLUDED.component_type, hint=EXCLUDED.hint, entity_type=EXCLUDED.entity_type, " +
                    "linked_form=EXCLUDED.linked_form, linked_template=EXCLUDED.linked_template")) {
                ps.setString(1, verb);
                ps.setString(2, label != null ? label : verb);
                ps.setString(3, actionType);
                ps.setString(4, compType);
                ps.setString(5, hint != null ? hint : "");
                ps.setString(6, entityType != null ? entityType : "");
                ps.setString(7, linkedForm);
                if (linkedTemplate != null) {
                    ps.setObject(8, java.util.UUID.fromString(linkedTemplate));
                } else {
                    ps.setNull(8, java.sql.Types.OTHER);
                }
                count += ps.executeUpdate();
            }

            for (Object po : policies) {
                JSONObject p  = (JSONObject) po;
                String pId    = str(p, "policy_id");
                String desc   = str(p, "description");
                String query  = str(p, "query_logic");
                String error  = str(p, "error_message");
                if (pId == null || pId.isBlank()) continue;
                pId = pId.toUpperCase().replaceAll("[^A-Z0-9_]", "_");
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO policy_manifest (policy_id, action_type, description, query_logic, error_message, execution_mode, is_active) " +
                        "VALUES (?, ?, ?, ?, ?, 'GUARDRAIL', true) ON CONFLICT (policy_id) DO NOTHING")) {
                    ps.setString(1, pId);
                    ps.setString(2, actionType);
                    ps.setString(3, desc != null ? desc : "");
                    ps.setString(4, query);
                    ps.setString(5, error);
                    ps.executeUpdate();
                }
            }
        }
        return count;
    }

    /* ── AI call ───────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private String callAI(String prompt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model",       VLLM_MODEL);
        body.put("temperature", 0.7);
        body.put("max_tokens",  8192);

        JSONArray messages = new JSONArray();

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

    @SuppressWarnings("unchecked")
    private JSONObject extractJson(String content) throws Exception {
        if (content == null || content.isBlank())
            throw new RuntimeException("AI returned empty content");

        String s = content.trim();
        System.out.println("[Seeding] extractJson raw head: " + s.substring(0, Math.min(200, s.length())).replace('\n',' '));

        // 1. Strip markdown code fences
        if (s.contains("```")) {
            int first = s.indexOf("```");
            int second = s.indexOf("```", first + 3);
            if (second > first) {
                s = s.substring(first + 3, second).trim();
                if (s.startsWith("json")) s = s.substring(4).trim();
                else if (s.indexOf('\n') > 0 && s.indexOf('\n') < 10) s = s.substring(s.indexOf('\n')).trim();
            }
        }

        int start = s.indexOf('{');
        if (start < 0) throw new RuntimeException("No JSON object found in AI response");

        // 2. Try to extract first balanced brace block (most reliable)
        String firstBlock = extractFirstBalancedBlock(s, start);
        if (firstBlock != null) {
            try {
                Object parsed = new JSONParser().parse(firstBlock);
                if (parsed instanceof JSONObject) {
                    return (JSONObject) parsed;
                } else if (parsed instanceof JSONArray) {
                    return wrapArray((JSONArray) parsed);
                }
            } catch (Exception ignore) {
                // fall through to repair
                try {
                    String repaired = repairJson(firstBlock);
                    Object parsed2 = new JSONParser().parse(repaired);
                    if (parsed2 instanceof JSONObject) return (JSONObject) parsed2;
                } catch (Exception ignore2) {}
            }
        }

        // 3. Try from first { to last } (original approach)
        int end = s.lastIndexOf('}');
        if (end > start) {
            String slice = s.substring(start, end + 1);
            try {
                return (JSONObject) new JSONParser().parse(slice);
            } catch (Exception e1) {
                // 4. Multi-object merge
                try {
                    String wrapped = slice.replaceAll("}\\s*\\{", "},{");
                    Object multi = new JSONParser().parse("[" + wrapped + "]");
                    if (multi instanceof JSONArray) return wrapArray((JSONArray) multi);
                } catch (Exception ignore) {}
                // 5. Repair
                try {
                    return (JSONObject) new JSONParser().parse(repairJson(slice));
                } catch (Exception e2) {
                    System.err.println("[Seeding] All JSON parse attempts failed. Content length: " + s.length());
                    System.err.println("[Seeding] Tail: " + s.substring(Math.max(0, s.length()-200)).replace('\n',' '));
                    throw new RuntimeException("AI response was not valid JSON: " + e1);
                }
            }
        }

        throw new RuntimeException("No parseable JSON object in AI response");
    }

    private String extractFirstBalancedBlock(String s, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private JSONObject wrapArray(JSONArray arr) {
        JSONObject merged = new JSONObject();
        for (Object obj : arr) {
            if (obj instanceof JSONObject) {
                JSONObject o = (JSONObject) obj;
                for (Object key : o.keySet()) {
                    Object val = o.get(key);
                    if (val instanceof JSONArray) {
                        JSONArray existing = (JSONArray) merged.get(key);
                        if (existing == null) { existing = new JSONArray(); merged.put(key, existing); }
                        existing.addAll((JSONArray) val);
                    } else {
                        merged.put(key, val);
                    }
                }
            }
        }
        return merged;
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
                else if (c == '}') { if (stack.length() > 0 && stack.charAt(stack.length()-1) == '{') stack.deleteCharAt(stack.length()-1); }
                else if (c == ']') { if (stack.length() > 0 && stack.charAt(stack.length()-1) == '[') stack.deleteCharAt(stack.length()-1); }
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

    /* ── utility helpers ───────────────────────────────────────────────── */

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

    private boolean isPlausibleRelationship(String fromType, String relType, String toType) {
        String from = fromType != null ? fromType.toLowerCase() : "";
        String rel  = relType  != null ? relType.toUpperCase() : "";
        String to   = toType   != null ? toType.toLowerCase() : "";
        if (from.isBlank() || to.isBlank() || rel.isBlank()) return false;
        if ("WORKS_AT".equals(rel)) return !"system".equals(to) && isActorType(from) && isPlaceType(to);
        if ("MANAGES".equals(rel)) return isActorType(from) && !"system".equals(to);
        if ("MEMBER_OF".equals(rel)) return !"system".equals(from) && isGroupType(to);
        if ("GUARANTEES".equals(rel)) return isPersonType(from) && isPersonType(to);
        if ("APPLIED_FOR".equals(rel)) return isPersonType(from) && isApplicationTargetType(to);
        if ("USES_SYSTEM".equals(rel) || "HAS_SYSTEM_ACCESS".equals(rel)) return isActorType(from) && "system".equals(to);
        if ("system".equals(from) || "system".equals(to)) return rel.contains("SYSTEM") || rel.contains("ACCESS") || rel.contains("INTEGRATES");
        return true;
    }

    private boolean isActorType(String type) {
        return isPersonType(type) || type.contains("officer") || type.contains("staff") || type.contains("agent") ||
               type.contains("manager") || type.contains("employee") || type.contains("coordinator");
    }
    private boolean isPersonType(String type) {
        return type.contains("member") || type.contains("customer") || type.contains("client") ||
               type.contains("borrower") || type.contains("applicant") || type.contains("person");
    }
    private boolean isPlaceType(String type) {
        return type.contains("branch") || type.contains("office") || type.contains("department") ||
               type.contains("region") || type.contains("location") || type.contains("center") || type.contains("centre");
    }
    private boolean isGroupType(String type) {
        return type.contains("group") || type.contains("team") || type.contains("cohort") ||
               type.contains("committee") || type.contains("household");
    }
    private boolean isApplicationTargetType(String type) {
        return type.contains("product") || type.contains("service") || type.contains("loan") ||
               type.contains("scheme") || type.contains("program") || type.contains("programme") || type.contains("system");
    }

    @SuppressWarnings("unchecked")
    private JSONArray defaultGenerate() {
        JSONArray a = new JSONArray();
        a.add("twins"); a.add("relationships"); a.add("interactions");
        a.add("mock_services"); a.add("templates"); a.add("forms");
        a.add("commands"); a.add("policies");
        return a;
    }

    @Override public void put(HttpServletRequest q, HttpServletResponse s) {}
    @Override public void delete(HttpServletRequest q, HttpServletResponse s) {}
    @Override public boolean validate(String m, HttpServletRequest q, HttpServletResponse s) { return true; }
}
