package org.tsicoop.nexus.api;

import org.tsicoop.nexus.framework.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TSI Nexus: Live Analytical Query Engine (Conversational Intelligence v0.2)
 *
 * POST /api/analytics { "question": "...", "mode": "ANALYTICS" | "SIMILARITY" }
 *
 * Generates SQL from a live user question (no pre-authored policy_manifest row, no fixed
 * target) using the same schema-introspection approach Policy.java uses for admin-authored
 * guardrails, then executes it through a sandboxed, read-only, statement-timeout-bounded
 * connection (nexus_readonly / ReadOnlyPoolDB). Every call - accepted or rejected - is logged
 * to action_audit_log, the same table Governance.java already writes to, so ad-hoc queries
 * (including rejected/injection attempts) are visible in the existing /api/audit admin page.
 *
 * SIMILARITY mode runs the same sandboxed pipeline twice: a broad, hard-filter-only candidate
 * pool (LIMIT 200), then one LLM re-rank call over that pool against the original question.
 */
public class Analytics implements Action {

    private static final int MAX_ANALYTICS_ROWS  = 50;
    private static final int MAX_SIMILARITY_POOL = 200;
    private static final int MAX_COLUMNS         = 12;
    private static final int MAX_CELL_CHARS      = 500;

    private static final String VLLM_URL;
    private static final String VLLM_MODEL;

    static {
        String url   = System.getenv("VLLM_URL");
        String model = System.getenv("VLLM_MODEL");
        VLLM_URL   = (url   != null && !url.isEmpty())   ? url.replaceAll("/$", "") : null;
        VLLM_MODEL = (model != null && !model.isEmpty()) ? model : null;
    }

    private static final String[] BANNED_KEYWORDS = {
        "INSERT","UPDATE","DELETE","DROP","ALTER","TRUNCATE","GRANT","REVOKE",
        "CREATE","EXECUTE","CALL","COPY","VACUUM","REINDEX","SET","RESET","LISTEN","NOTIFY",
        "DO","MERGE","LOCK","BEGIN","COMMIT","ROLLBACK","PG_SLEEP","PG_READ_FILE","DBLINK"
    };

    private static final String ANALYTICS_SQL_PROMPT =
        "You are the Nexus Analytics Query Engine. Translate a natural language question into a " +
        "single read-only PostgreSQL query plus a one-line recap of what it does.\n\n" +
        "SCHEMA (core tables):\n" +
        "  digital_twins(id UUID, type TEXT, external_id TEXT, current_state JSONB, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ)\n" +
        "  twin_relationships(rel_id UUID, from_twin_id UUID, to_twin_id UUID, relationship_type TEXT, metadata JSONB, created_at TIMESTAMPTZ)\n" +
        "  interaction_stream(id BIGSERIAL, owner_id UUID, content TEXT, intent_mapped TEXT, created_at TIMESTAMPTZ)\n" +
        "  twin_state_history(history_id BIGSERIAL, twin_id UUID, snapshot JSONB, reason TEXT, created_at TIMESTAMPTZ)\n\n" +
        "The \"Live entity types\" section below lists each type's fields as name(count/total) - " +
        "count is how many of that type's rows actually have that key. Two names can appear for " +
        "the same concept when data was seeded inconsistently (e.g. margin_pct(27/35) and " +
        "margin_percentage(8/35)) - always prefer the name with the higher count.\n\n" +
        "RULES:\n" +
        "  - Read-only. The query MUST start with SELECT or WITH. No other statement type.\n" +
        "  - No semicolons, no SQL comments (-- or /* */), no DDL/DML keywords.\n" +
        "  - Access JSONB fields with ->> . Cast to numeric when comparing: (current_state->>'field')::numeric\n" +
        "  - Join via digital_twins.id <-> twin_relationships.from_twin_id/to_twin_id, or via external_id.\n" +
        "  - Relationship direction is always subject->object, matching how the relationship_type verb " +
        "reads left to right: for 'X FULFILLS Y' or 'X MANAGES Y', from_twin_id is X's id and to_twin_id " +
        "is Y's id. Do not second-guess this - it is fixed, not something to infer per-query.\n" +
        "  - Always include a LIMIT clause of 50 or fewer rows, sized to the question (e.g. LIMIT 5 for 'top 5').\n" +
        "  - PostgreSQL sorts NULL first on DESC and last on ASC by default. When ranking/ordering by a " +
        "field whose count is less than the entity's total (not present on every row), add " +
        "\"field IS NOT NULL\" to the WHERE clause so rows missing that field never rank ahead of rows " +
        "that actually have a value.\n" +
        "  - Prefer named, human-readable output columns, e.g. current_state->>'name' AS name.\n" +
        "  - If \"top\"/\"best\"/\"highest\" is asked with no named metric, pick the single most obviously " +
        "relevant numeric field yourself (highest count if several seem equally relevant) and note the " +
        "choice in the recap - do not deliberate between candidates, there is no wrong choice worth " +
        "reasoning at length about.\n" +
        "  - CRITICAL: only reference field names that literally appear in the fields=[...] list for that " +
        "entity type. Never invent a field name that sounds plausible for the concept (e.g. don't use " +
        "'monthly_revenue' unless that exact key is listed) - if no listed field matches what the question " +
        "asks for, pick the closest listed field instead and reflect that substitution in the recap.\n" +
        "  - Output ONLY a single JSON object, no markdown, no explanation:\n" +
        "    {\"sql\": \"SELECT ...\", \"recap\": \"One sentence describing what was filtered/ranked.\"}";

    private static final String SIMILARITY_POOL_PROMPT =
        ANALYTICS_SQL_PROMPT + "\n\n" +
        "This query builds a CANDIDATE POOL for a later semantic re-rank step, not the final " +
        "answer. Do not attempt to filter by free-text similarity/personality/experience " +
        "criteria in the question (e.g. \"similar to X\") - SQL cannot evaluate that. Only apply " +
        "hard filters that ARE expressible in SQL (entity type, explicit status fields). Always " +
        "use LIMIT 200.";

    private static final String RERANK_PROMPT =
        "You are ranking candidate records against a user's similarity question. Given the " +
        "original question and a JSON array of candidate rows, select the 5 to 10 best matches " +
        "and explain why each matches, referencing specific fields from the row.\n" +
        "Output ONLY a single JSON object, no markdown, no explanation:\n" +
        "{\"matches\":[{\"external_id\":\"...\",\"justification\":\"...\"}]}";

    private static final AtomicBoolean roleBootstrapped = new AtomicBoolean(false);

    @Override
    @SuppressWarnings("unchecked")
    public void post(HttpServletRequest req, HttpServletResponse res) {
        PoolDB pool = null;
        Connection conn = null;
        try {
            JSONObject input = InputProcessor.getInput(req);
            String question = input.get("question") != null ? String.valueOf(input.get("question")).trim() : "";
            String mode = input.get("mode") != null ? String.valueOf(input.get("mode")).trim().toUpperCase() : "ANALYTICS";
            if (question.isEmpty()) {
                OutputProcessor.errorResponse(res, 400, "Bad request", "question is required", req.getRequestURI());
                return;
            }
            if (!"ANALYTICS".equals(mode) && !"SIMILARITY".equals(mode)) mode = "ANALYTICS";

            JSONObject caller = InputProcessor.getAdminAuthToken(req, res);
            UUID actorUuid = null, userUuid = null;

            pool = new PoolDB();
            conn = pool.getConnection();

            ensureReadOnlyRole(conn);

            if (caller != null) {
                actorUuid = resolveActorId(conn, caller.get("twin_id"));
                userUuid  = resolveUserId(conn, caller.get("user_id"));
            }

            JSONObject generated = generateSql(conn, question, "SIMILARITY".equals(mode) ? SIMILARITY_POOL_PROMPT : ANALYTICS_SQL_PROMPT);
            String sql   = (String) generated.get("sql");
            String recap = (String) generated.get("recap");
            if (sql == null || sql.isBlank()) {
                logAnalyticsAudit(conn, actorUuid, userUuid, mode, question, null, false, "Query generation returned no SQL");
                OutputProcessor.errorResponse(res, 502, "No output", "Intelligence Module returned no query", req.getRequestURI());
                return;
            }

            try {
                validateReadOnlySql(sql);
            } catch (SecurityException se) {
                logAnalyticsAudit(conn, actorUuid, userUuid, mode, question, sql, false, se.getMessage());
                JSONObject fail = new JSONObject();
                fail.put("success", false);
                fail.put("error", se.getMessage());
                OutputProcessor.send(res, 400, fail);
                return;
            }

            int rowCap = "SIMILARITY".equals(mode) ? MAX_SIMILARITY_POOL : MAX_ANALYTICS_ROWS;
            String limitedSql = enforceLimit(sql, rowCap);

            JSONArray rows;
            try {
                rows = executeReadOnly(limitedSql, rowCap);
            } catch (SQLException se) {
                String friendly = "57014".equals(se.getSQLState())
                    ? "Query took too long and was cancelled (3s limit)."
                    : "Query execution failed: " + se.getMessage();
                logAnalyticsAudit(conn, actorUuid, userUuid, mode, question, limitedSql, false, friendly);
                JSONObject fail = new JSONObject();
                fail.put("success", false);
                fail.put("error", friendly);
                OutputProcessor.send(res, "57014".equals(se.getSQLState()) ? 504 : 500, fail);
                return;
            }

            boolean truncated = rows.size() >= rowCap;
            JSONArray columns = columnsOf(rows);

            if ("SIMILARITY".equals(mode)) {
                JSONArray reranked = reRank(question, rows);
                rows = reranked;
                columns = columnsOf(rows);
            }

            logAnalyticsAudit(conn, actorUuid, userUuid, mode, question, limitedSql, true, null);

            JSONObject result = new JSONObject();
            result.put("success", true);
            result.put("mode", mode);
            result.put("recap", recap != null ? recap : "");
            result.put("sql_generated", sql);
            result.put("columns", columns);
            result.put("rows", rows);
            result.put("row_count", rows.size());
            result.put("truncated", truncated);
            OutputProcessor.send(res, 200, result);

        } catch (Exception e) {
            e.printStackTrace();
            OutputProcessor.errorResponse(res, 500, "Analytics Failure", e.getMessage(), req.getRequestURI());
        } finally {
            if (pool != null) pool.cleanup(null, null, conn);
        }
    }

    /* ── Self-healing read-only role bootstrap ──────────────────────────── */

    private void ensureReadOnlyRole(Connection privilegedConn) {
        if (roleBootstrapped.get()) return;
        synchronized (roleBootstrapped) {
            if (roleBootstrapped.get()) return;
            String roUser = SystemConfig.getAppConfig().getProperty("framework.db.ro.user");
            String roPass = SystemConfig.getAppConfig().getProperty("framework.db.ro.password");
            try (Statement stmt = privilegedConn.createStatement()) {
                stmt.execute(
                    "DO $$ BEGIN " +
                    "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + roUser + "') THEN " +
                    "CREATE ROLE " + roUser + " LOGIN PASSWORD 'bootstrap_placeholder'; " +
                    "END IF; END $$;"
                );
                stmt.execute("DO $$ BEGIN EXECUTE format('GRANT CONNECT ON DATABASE %I TO " + roUser + "', current_database()); END $$;");
                stmt.execute("GRANT USAGE ON SCHEMA public TO " + roUser);
                stmt.execute("GRANT SELECT ON digital_twins, twin_relationships, interaction_stream, twin_state_history TO " + roUser);
                stmt.execute("ALTER ROLE " + roUser + " SET statement_timeout = '3000ms'");
            } catch (Exception e) {
                System.err.println("[Analytics] ensureReadOnlyRole bootstrap (role/grants) failed: " + e.getMessage());
            }
            // ALTER ROLE ... PASSWORD is a utility statement - Postgres does not support bind
            // parameters for it over the wire protocol (only DML does), so the password has to
            // be inlined. roPass is operator-controlled (POSTGRES_RO_PASSWD env var), not
            // request input, but it's still escaped defensively.
            try (Statement stmt = privilegedConn.createStatement()) {
                String escapedPass = roPass != null ? roPass.replace("'", "''") : "";
                stmt.execute("ALTER ROLE " + roUser + " WITH PASSWORD '" + escapedPass + "'");
            } catch (Exception e) {
                System.err.println("[Analytics] ensureReadOnlyRole password sync failed: " + e.getMessage());
            }
            roleBootstrapped.set(true);
        }
    }

    /* ── SQL generation ──────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private JSONObject generateSql(Connection conn, String question, String systemPrompt) throws Exception {
        JSONObject empty = new JSONObject();
        if (VLLM_URL == null || VLLM_MODEL == null) return empty;

        String schemaContext = buildSchemaContext(conn);
        String vocabSection   = loadVocabSection(conn);

        StringBuilder userMsg = new StringBuilder();
        userMsg.append("Question: ").append(question).append("\n");
        if (!schemaContext.isEmpty()) userMsg.append("\nLive entity types in this deployment:\n").append(schemaContext);
        if (!vocabSection.isEmpty()) userMsg.append("\nInstitutional vocabulary:\n").append(vocabSection);

        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject(); sys.put("role", "system"); sys.put("content", systemPrompt); messages.add(sys);
        JSONObject usr = new JSONObject(); usr.put("role", "user");   usr.put("content", userMsg.toString()); messages.add(usr);

        JSONObject body = new JSONObject();
        body.put("model", VLLM_MODEL);
        body.put("messages", messages);
        // Reasoning models can spend a large share of the budget on reasoning_content before
        // ever emitting the SQL/recap JSON - keep this generous rather than tuned to answer size.
        // Observed: a single-join question ("candidates placed this month") still hit
        // finish_reason=length at 1536 tokens, entirely inside reasoning_content, before the
        // model ever reached the actual JSON output.
        body.put("max_tokens", 4096);
        body.put("temperature", 0.1);
        // Defense-in-depth against reasoning loops observed on relationship-join questions
        // (the model re-asking itself the same already-answered question dozens of times,
        // e.g. "Is X a column? Yes." repeated near-verbatim until max_tokens was exhausted).
        // The direction-convention rule above targets the actual uncertainty that triggers it;
        // this discourages the repetition pattern itself if it still occurs for other reasons.
        body.put("frequency_penalty", 0.4);

        System.out.println("[Analytics] generateSql POST " + VLLM_URL + "/v1/chat/completions model=" + VLLM_MODEL + " question=\"" + question + "\"");
        HttpClient http = new HttpClient();
        JSONObject llmResponse = http.sendPost(VLLM_URL + "/v1/chat/completions", body, "Authorization", "Bearer dummy");
        String content = extractContent(llmResponse);
        if (content == null || content.isBlank()) {
            // Observed on this deployment's (heavily quantized) reasoning model: it can reach
            // and re-state the fully correct final SQL several times inside reasoning_content,
            // then loop restating it verbatim and never emit the actual content field before
            // hitting max_tokens. The query is real and correct when this happens - recover it
            // instead of failing outright. It still goes through the exact same
            // validateReadOnlySql/enforceLimit/ReadOnlyPoolDB sandbox as any other generated SQL.
            String reasoning = extractReasoningContent(llmResponse);
            JSONObject recovered = recoverSqlFromReasoning(reasoning);
            if (recovered != null) {
                System.out.println("[Analytics] generateSql: content was empty, recovered SQL from reasoning_content");
                return recovered;
            }
            System.err.println("[Analytics] generateSql: no content in response: " + llmResponse.toJSONString());
            return empty;
        }

        JSONObject parsed = extractJson(content);
        if (parsed == null) {
            System.err.println("[Analytics] generateSql: no JSON object found in content: " + content);
        }
        return parsed != null ? parsed : empty;
    }

    private String extractReasoningContent(JSONObject llmResponse) {
        try {
            JSONArray choices = (JSONArray) llmResponse.get("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject msg = (JSONObject) ((JSONObject) choices.get(0)).get("message");
                if (msg != null) return (String) msg.get("reasoning_content");
            }
        } catch (Exception ignore) {}
        return null;
    }

    // Takes the LAST SELECT/WITH ... LIMIT n statement mentioned in a reasoning trace - the
    // model consistently restates its converged-on answer right before looping, so the last
    // occurrence is the one to trust, not the first (which may be an earlier abandoned draft).
    @SuppressWarnings("unchecked")
    private JSONObject recoverSqlFromReasoning(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) return null;

        // The model consistently restates its converged-on answer as a single backtick-fenced
        // line ("Final SQL:\n    `SELECT ... LIMIT N`"). Match confined to ONE LINE (no DOTALL)
        // and prefer the backtick-fenced form - an earlier, looser version of this regex used
        // "." with DOTALL, which let a SELECT...LIMIT match bridge across unrelated paragraphs
        // of reasoning prose (including restated prompt rules and other fields' backtick-quoted
        // names) whenever an early abandoned draft happened to precede a much later LIMIT.
        String sql = lastMatch(reasoning, "(?im)`((?:SELECT|WITH)\\b[^`\\n]*?\\bLIMIT\\s+\\d+)`");
        if (sql == null) {
            sql = lastMatch(reasoning, "(?im)^\\s*((?:SELECT|WITH)\\b[^\\n]*?\\bLIMIT\\s+\\d+)\\s*$");
        }
        if (sql == null) return null;

        sql = sql.replaceAll("\\s+", " ").trim();
        // A genuine single statement here is short and never contains a literal backtick
        // (Postgres doesn't use them) - either is a sign of a bad extraction; discard rather
        // than hand something malformed to the database.
        if (sql.isEmpty() || sql.length() > 800 || sql.contains("`")) return null;

        JSONObject result = new JSONObject();
        result.put("sql", sql);
        result.put("recap", "Query recovered from the model's reasoning trace after it did not emit a final answer.");
        return result;
    }

    private String lastMatch(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    private String buildSchemaContext(Connection conn) {
        return org.tsicoop.nexus.framework.SchemaIntrospector.buildContext(conn);
    }

    @SuppressWarnings("unchecked")
    private JSONArray reRank(String question, JSONArray pool) throws Exception {
        if (VLLM_URL == null || VLLM_MODEL == null || pool.isEmpty()) return pool;

        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject(); sys.put("role", "system"); sys.put("content", RERANK_PROMPT); messages.add(sys);
        JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", "Original question: " + question + "\n\nCandidate rows:\n" + pool.toJSONString());
        messages.add(usr);

        JSONObject body = new JSONObject();
        body.put("model", VLLM_MODEL);
        body.put("messages", messages);
        body.put("max_tokens", 2048);
        body.put("temperature", 0.2);

        HttpClient http = new HttpClient();
        JSONObject llmResponse = http.sendPost(VLLM_URL + "/v1/chat/completions", body, "Authorization", "Bearer dummy");
        String content = extractContent(llmResponse);
        if (content == null) return new JSONArray();

        JSONObject parsed = extractJson(content);
        JSONArray matches = parsed != null && parsed.get("matches") instanceof JSONArray ? (JSONArray) parsed.get("matches") : new JSONArray();

        Map<String, String> justificationByHandle = new HashMap<>();
        for (Object m : matches) {
            JSONObject mo = (JSONObject) m;
            Object extId = mo.get("external_id");
            if (extId != null) justificationByHandle.put(String.valueOf(extId), String.valueOf(mo.get("justification")));
        }

        JSONArray result = new JSONArray();
        for (Object rowObj : pool) {
            JSONObject row = (JSONObject) rowObj;
            Object extId = row.get("external_id");
            if (extId == null) continue;
            String justification = justificationByHandle.get(String.valueOf(extId));
            if (justification == null) continue;
            JSONObject withJustification = new JSONObject();
            withJustification.putAll(row);
            withJustification.put("justification", justification);
            result.add(withJustification);
        }
        return result;
    }

    /* ── SQL safety ──────────────────────────────────────────────────────── */

    private void validateReadOnlySql(String sql) {
        String trimmed = sql.trim();
        if (!trimmed.matches("(?is)^(SELECT|WITH)\\b.*"))
            throw new SecurityException("Generated query must start with SELECT or WITH.");
        String body = trimmed.replaceAll(";\\s*$", "");
        if (body.contains(";"))
            throw new SecurityException("Multi-statement queries are not permitted.");
        if (body.contains("--") || body.contains("/*"))
            throw new SecurityException("SQL comments are not permitted.");
        for (String kw : BANNED_KEYWORDS) {
            if (Pattern.compile("(?i)\\b" + kw + "\\b").matcher(body).find())
                throw new SecurityException("Disallowed keyword in generated query: " + kw);
        }
    }

    private String enforceLimit(String sql, int cap) {
        String trimmed = sql.trim().replaceAll(";\\s*$", "");
        Matcher m = Pattern.compile("(?i)\\bLIMIT\\s+(\\d+)\\s*$").matcher(trimmed);
        if (m.find()) {
            int existing = Integer.parseInt(m.group(1));
            return existing > cap ? trimmed.substring(0, m.start()) + "LIMIT " + cap : trimmed;
        }
        return trimmed + " LIMIT " + cap;
    }

    /* ── Execution ───────────────────────────────────────────────────────── */

    @SuppressWarnings("unchecked")
    private JSONArray executeReadOnly(String sql, int rowCap) throws SQLException {
        JSONArray rows = new JSONArray();
        ReadOnlyPoolDB roPool = new ReadOnlyPoolDB();
        try (PreparedStatement ps = roPool.getConnection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = Math.min(meta.getColumnCount(), MAX_COLUMNS);
            while (rs.next() && rows.size() < rowCap) {
                JSONObject row = new JSONObject();
                for (int i = 1; i <= colCount; i++) {
                    Object value = rs.getObject(i);
                    // Generated SQL can select raw digital_twins columns directly (id, created_at,
                    // updated_at), not just current_state->>'field' text - org.json.simple only
                    // knows how to safely serialize String/Number/Boolean and writes anything else
                    // (java.util.UUID, java.sql.Timestamp, ...) unquoted via toString(), producing
                    // broken JSON on the wire. Force every non-primitive JDBC type to a String here.
                    if (value != null && !(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
                        value = value.toString();
                    }
                    if (value instanceof String && ((String) value).length() > MAX_CELL_CHARS) {
                        value = ((String) value).substring(0, MAX_CELL_CHARS) + "…";
                    }
                    row.put(meta.getColumnLabel(i), value);
                }
                rows.add(row);
            }
        } finally {
            roPool.cleanup(null, null, roPool.getConnection());
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private JSONArray columnsOf(JSONArray rows) {
        JSONArray columns = new JSONArray();
        if (!rows.isEmpty()) {
            JSONObject first = (JSONObject) rows.get(0);
            columns.addAll(first.keySet());
        }
        return columns;
    }

    /* ── Audit logging (reuses Governance.java's table) ─────────────────── */

    @SuppressWarnings("unchecked")
    private void logAnalyticsAudit(Connection conn, UUID actorId, UUID userId, String mode,
                                    String question, String sqlGenerated, boolean success, String reason) {
        try {
            JSONObject executed = new JSONObject();
            executed.put("success", success);
            executed.put("action_type", "NL_ANALYTICS_QUERY");
            executed.put("mode", mode);
            if (sqlGenerated != null) executed.put("sql_generated", sqlGenerated);
            if (reason != null) executed.put("reason", reason);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO action_audit_log (actor_id, user_id, intent_raw, action_executed, created_at) VALUES (?, ?, ?, ?::jsonb, NOW())")) {
                ps.setObject(1, actorId);
                ps.setObject(2, userId);
                ps.setString(3, question);
                ps.setString(4, executed.toJSONString());
                ps.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("[Analytics] audit log failed: " + e.getMessage());
        }
    }

    private UUID resolveActorId(Connection conn, Object rawTwinId) {
        if (rawTwinId == null) return null;
        String sid = String.valueOf(rawTwinId).trim();
        if (sid.isEmpty() || sid.equalsIgnoreCase("null")) return null;
        try {
            UUID candidate = UUID.fromString(sid);
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM digital_twins WHERE id = ?")) {
                ps.setObject(1, candidate);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? candidate : null;
                }
            }
        } catch (Exception ignored) { return null; }
    }

    private UUID resolveUserId(Connection conn, Object rawUserId) {
        if (rawUserId == null) return null;
        String sid = String.valueOf(rawUserId).trim();
        if (sid.isEmpty() || sid.equalsIgnoreCase("null")) return null;
        try {
            UUID candidate = UUID.fromString(sid);
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM nexus_users WHERE user_id = ?")) {
                ps.setObject(1, candidate);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? candidate : null;
                }
            }
        } catch (Exception ignored) { return null; }
    }

    /* ── Vocabulary (same pattern as Intent.java / Policy.java) ─────────── */

    private String loadVocabSection(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT domain_slang FROM root_organisation LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String raw = rs.getString("domain_slang");
                if (raw != null) {
                    Object parsed = new JSONParser().parse(raw);
                    if (parsed instanceof JSONObject) {
                        JSONObject vocab = (JSONObject) parsed;
                        if (vocab.isEmpty()) return "";
                        StringBuilder sb = new StringBuilder();
                        for (Object key : vocab.keySet()) sb.append("  ").append(key).append(" = ").append(vocab.get(key)).append("\n");
                        return sb.toString().trim();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[Analytics] loadVocabSection failed: " + e.getMessage());
        }
        return "";
    }

    /* ── LLM response parsing ────────────────────────────────────────────── */

    private String extractContent(JSONObject llmResponse) {
        try {
            JSONArray choices = (JSONArray) llmResponse.get("choices");
            if (choices != null && !choices.isEmpty()) {
                JSONObject msg = (JSONObject) ((JSONObject) choices.get(0)).get("message");
                if (msg != null) return (String) msg.get("content");
            }
        } catch (Exception ignore) {}
        return null;
    }

    private JSONObject extractJson(String content) {
        try {
            String stripped = stripMarkdownFences(content).trim();
            int start = stripped.indexOf('{');
            int end   = stripped.lastIndexOf('}');
            if (start < 0 || end < 0 || end < start) return null;
            Object parsed = new JSONParser().parse(stripped.substring(start, end + 1));
            return parsed instanceof JSONObject ? (JSONObject) parsed : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String stripMarkdownFences(String text) {
        Pattern p = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text.trim());
        if (m.find()) return m.group(1).trim();
        return text.trim();
    }

    @Override public void get(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void put(HttpServletRequest req, HttpServletResponse res) {}
    @Override public void delete(HttpServletRequest req, HttpServletResponse res) {}
    @Override public boolean validate(String m, HttpServletRequest req, HttpServletResponse res) { return true; }
}
