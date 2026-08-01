# Conversational Intelligence: Data Apps Lite

**Version:** 0.2
**Status:** Planning
**Module path:** `src/org/tsicoop/nexus/api/Intent.java` (extended), `src/org/tsicoop/nexus/api/Analytics.java` (new)

---

## Context

The Liquid interface currently only understands two shapes of input: a `/command @handle`
mapped through `command_manifest`, or a single entity name resolved via fuzzy match. A user
testing an HR-staffing deployment hit this ceiling immediately with realistic questions like
*"Who are our top 5 Java candidates for the Enterprise X requisition who've passed their
Jombay assessment?"*, *"Why was Rahul's offer letter blocked yesterday?"*, *"Find candidates
similar to Anitha who stayed 2+ years at Infosys"*, and *"Status of open requisitions for the
Chennai branch this week?"*. All four fall through to the `nexus_semantic_results` dead-end
card ("no direct matches, try a name or @handle").

Tracing the code confirmed this is an architectural gap in `Intent.java`'s resolver, not a
prompt-tuning issue - and confirmed that most of what's needed already exists in the codebase
and just isn't wired up to live chat input:

- **Audit trail already exists.** `action_audit_log` (written by `Governance.java` on every
  guardrail pass/fail) plus a filterable `GET /api/audit` (`actor`, `from`, `to`) in
  `Audit.java` already answers "why was X blocked" - it's just unreachable from Liquid today.
- **NL→SQL generation already exists**, in `Policy.java#generateSql`, used today only to let an
  admin pre-author a `policy_manifest` row. The execution side (`Governance.java`) only runs
  SQL bound to exactly one or two pre-known `?` targets - there's no "no target, ad-hoc,
  ranked, LIMIT-N" live execution path.
- **pgvector is installed and completely unused** - `interaction_stream.embedding vector(1536)`
  exists in `db/init.sql` but nothing in `src/` ever reads or writes it, and there's no
  embedding-generation call anywhere (the LLM integration is chat-completions only).
- **Client-side rendering is already component-driven** (`web/liquid/liquid.html`) with a
  reusable `renderTable()` used today for governance ANALYTICS results, and a commentary pane
  (added this session) with an established `addCommentary()` pattern to extend.

This plan adds three new resolution paths - **audit-explain**, **live analytical query**, and
**similarity re-rank** - reusing that existing machinery, without adding sector-specific logic
to Java (routing and SQL are generated at request time from live schema introspection + the
user's own question, exactly like `Policy.java`'s existing flow).

**Decisions made with the user:**
- v0.2 ships **all three paths** (audit, analytics, similarity) in one pass - not phased.
- The new read-only DB role follows the existing `POSTGRES_PASSWD` convention: a dev-only
  default password in `docker-compose.yml`, with a comment that production must override
  `POSTGRES_RO_PASSWD` explicitly.

**Verified during design** (not just claimed): `Policy.java#buildSchemaContext` (lines
360–381) queries `array_agg(DISTINCT jsonb_object_keys(current_state))` per entity type but
the read loop only pulls `type` and `cnt` off the ResultSet - the JSONB field names it fetches
are silently discarded. This is a real, pre-existing bug, confirmed by direct read of the
file. It matters here because the new analytical path needs the model to see actual field
names (e.g. `current_state->>'branch'`) to write correct WHERE clauses, so fixing it is Phase 0.

---

## Phase 0: Prerequisite fix (small, isolated, do first)

Extract a corrected, shared schema-introspection helper so both the existing admin
`generate_sql` flow and the new live analytics path use the same (now-correct) live schema
context.

**New file:** `src/org/tsicoop/nexus/framework/SchemaIntrospector.java`

```java
public static String buildContext(Connection conn) {
    StringBuilder sb = new StringBuilder();
    String sql = "SELECT type, COUNT(*) AS cnt, " +
                 "array_agg(DISTINCT keys.key) AS field_names " +
                 "FROM digital_twins, LATERAL jsonb_object_keys(current_state) AS keys(key) " +
                 "WHERE type != 'system' GROUP BY type ORDER BY type";
    try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
            sb.append("  entity type='").append(rs.getString("type"))
              .append("' count=").append(rs.getLong("cnt"))
              .append(" fields=").append(java.util.Arrays.toString(
                  (Object[]) rs.getArray("field_names").getArray()))
              .append("\n");
        }
    } catch (Exception ignore) {}
    try (PreparedStatement ps = conn.prepareStatement(
            "SELECT DISTINCT relationship_type FROM twin_relationships ORDER BY relationship_type");
         ResultSet rs = ps.executeQuery()) {
        while (rs.next()) sb.append("  relationship_type='").append(rs.getString("relationship_type")).append("'\n");
    } catch (Exception ignore) {}
    return sb.toString();
}
```

`Policy.java#buildSchemaContext` becomes a one-line delegate to
`SchemaIntrospector.buildContext(conn)`. Pure bug fix - the admin `generate_sql` flow now
actually sees field names as its own prompt already claims it does.

---

## 1. Intent classification

A **second, dedicated LLM call**, not an extension of `llmParseIntent`. `llmParseIntent`'s
contract (`Intent.java` lines 158–178) is a strict single-line `/command` or `/unknown` output,
exercised on every interaction today - folding four new routes into it risks regressing the
well-exercised command path. Instead:

- `llmParseIntent` stays untouched.
- New private method `classifyIntelligenceQuery(rawInput, entityList, todayIso)` is added to
  `Intent.java`, called **only** from the existing "no command, no handle" fallback branch
  (`resolveToAdaptiveUI`, current lines 267–293) - the same branch all four example questions
  already fall into today. Zero added latency/cost for existing command or name-lookup traffic.

New system prompt `INTELLIGENCE_ROUTE_PROMPT` (temperature 0.1, max_tokens 200, same
conventions as `Intent.java`/`Policy.java`):

```
You are the query router for TSI Nexus. Classify the user's question into exactly one route
and extract structured parameters. Today's date is {TODAY_ISO}.

Known entities:
{ENTITY_LIST}

Routes:
  AUDIT      - why/when/whether an action was blocked, approved, or executed (a specific past
               decision, optionally time-boxed). Extract actor_handle + date_from/date_to.
  ANALYTICS  - filter, rank, count, or aggregate entities by concrete criteria (skills, status,
               scores, relationships, time windows).
  SIMILARITY - find entities "similar to"/"like"/"resembling" a named entity, based on
               free-text/profile characteristics rather than exact filters.
  NONE       - anything else (greetings, unresolvable, out of scope).

Rules:
  1. Output ONLY a single JSON object, no markdown, no explanation.
  2. actor_handle must be an exact @handle from the entity list above, or null.
  3. date_from/date_to are YYYY-MM-DD or null. "yesterday" = TODAY-1. "this week" = Monday of
     the current week through TODAY.
  4. cleaned_question is the user's question verbatim, for ANALYTICS/SIMILARITY routes.

Output shape:
{"route":"AUDIT|ANALYTICS|SIMILARITY|NONE","actor_handle":null,"date_from":null,"date_to":null,"cleaned_question":null}
```

If parsing fails or `route == "NONE"`, fall through to the existing `nexus_semantic_results`
dead-end - no regression for genuinely unresolvable input.

---

## 2. Audit-explain path

No new backend query capability needed - `GET /api/audit` already supports
`?actor=&from=&to=`. `Analytics.java` is not involved here.

1. `Intent.java`: `route=AUDIT` emits one component:
   ```json
   {"component_type":"nexus_audit_narrative",
    "props":"{\"actor_handle\":\"@officer_rahul\",\"date_from\":\"2026-07-31\",\"date_to\":\"2026-07-31\",\"question\":\"...\"}"}
   ```
2. `web/liquid/liquid.html`, new `renderAuditNarrative(props)`:
   - `fetch('/api/audit?actor=...&from=...&to=...&limit=20', {headers: AUTH()})`.
   - Deterministic JS templating over returned rows (no further LLM call - traceable straight
     to source data): prioritizes denied entries, renders
     `"<name>'s <policy/action> was blocked on <date> at <time> - <reason>."`; falls back to a
     neutral framing if nothing was denied in range; shows an explicit empty state if zero rows
     (never a silent dead end).
   - `addCommentary('Audit Trail', ...)` explaining these are guardrail-check log entries,
     matching the existing commentary-pane pattern.

**Zero changes to `Audit.java`.**

---

## 3. Live analytical-query path

### 3a. Sandboxed read-only DB role

`db/init.sql` - appended, idempotent (fresh installs):
```sql
-- READ-ONLY ROLE for LLM-generated ad-hoc analytical queries (Conversational Intelligence v0.2)
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'nexus_readonly') THEN
    CREATE ROLE nexus_readonly LOGIN PASSWORD 'changeme_ro';
  END IF;
END $$;
GRANT CONNECT ON DATABASE tsi_nexus TO nexus_readonly;
GRANT USAGE ON SCHEMA public TO nexus_readonly;
GRANT SELECT ON digital_twins, twin_relationships, interaction_stream, twin_state_history TO nexus_readonly;
ALTER ROLE nexus_readonly SET statement_timeout = '3000ms';
```
Explicit table allowlist - deliberately excludes `nexus_users` (password hashes),
`app_access_registry` (API secrets), `service_registry` (auth configs), and
`action_audit_log`.

`Analytics.java` self-heals on already-running deployments: on first request, using the
existing privileged `PoolDB` connection, it re-runs the same idempotent `CREATE ROLE IF NOT
EXISTS` + `GRANT`s, then sets the real password from `POSTGRES_RO_PASSWD`.

**New file:** `src/org/tsicoop/nexus/framework/ReadOnlyPoolDB.java` - a second small HikariCP
pool (max 3 connections, same structure as `PoolDB.java`) authenticating as `nexus_readonly`.
Kept separate from the main pool so ad-hoc LLM SQL is isolated in `pg_stat_activity` and can't
starve the application pool.

`SystemConfig.java`: +2 properties (`framework.db.ro.user`, `framework.db.ro.password`) set
from `System.getenv("POSTGRES_RO_USER")`/`POSTGRES_RO_PASSWD`, same pattern as the four
existing `framework.db.*` properties.

`docker-compose.yml`: +2 env vars on the `server` service, dev default + comment that
production must override `POSTGRES_RO_PASSWD` (per the decision above).

### 3b. SQL safety allowlist

Applied after generation, before execution:
```java
private void validateReadOnlySql(String sql) {
    String trimmed = sql.trim();
    if (!trimmed.matches("(?is)^(SELECT|WITH)\\b.*"))
        throw new SecurityException("Generated query must start with SELECT or WITH.");
    String body = trimmed.replaceAll(";\\s*$", "");
    if (body.contains(";"))
        throw new SecurityException("Multi-statement queries are not permitted.");
    if (body.contains("--") || body.contains("/*"))
        throw new SecurityException("SQL comments are not permitted.");
    String[] banned = {"INSERT","UPDATE","DELETE","DROP","ALTER","TRUNCATE","GRANT","REVOKE",
        "CREATE","EXECUTE","CALL","COPY","VACUUM","REINDEX","SET","RESET","LISTEN","NOTIFY",
        "DO","MERGE","LOCK","BEGIN","COMMIT","ROLLBACK","pg_sleep","pg_read_file","dblink"};
    for (String kw : banned) {
        if (Pattern.compile("(?i)\\b" + kw + "\\b").matcher(body).find())
            throw new SecurityException("Disallowed keyword in generated query: " + kw);
    }
}
```
On rejection: 400 response, and the rejection itself is logged to `action_audit_log` so an
injection attempt is visible in the existing admin Audit UI, not silently swallowed.

### 3c. LIMIT + column/row caps

`MAX_ANALYTICS_ROWS = 50`, `MAX_SIMILARITY_POOL = 200`. A regex rewrites/injects a trailing
`LIMIT`; as defense-in-depth the Java result-set loop also stops at the cap regardless
(`while (rs.next() && rows.size() < cap)`), and caps columns at 12 and any cell string at 500
chars before rendering.

### 3d. Timeout enforcement

`statement_timeout = 3000ms` set both at the role level (bootstrap) and explicitly on the
connection right before executing generated SQL (belt-and-suspenders). SQLState `57014`
(`query_canceled`) is caught and mapped to a friendly timeout error; the attempt is still
audit-logged.

### 3e. New endpoint

**`src/org/tsicoop/nexus/api/Analytics.java`**, registered in `web/WEB-INF/_processor.tsi`:
```
/api/analytics=org.tsicoop.nexus.api.Analytics
```

`POST /api/analytics` - request: `{"question": "...", "mode": "ANALYTICS"}` (`mode` is
`ANALYTICS` or `SIMILARITY`, passed through from `Intent.java`'s classification). **The
endpoint independently re-validates and sandboxes regardless of caller** - the read-only role,
SQL allowlist, LIMIT, and timeout are the actual trust boundary, not the classification step,
so direct POSTs to `/api/analytics` are equally safe.

Response (success):
```json
{
  "success": true, "mode": "ANALYTICS",
  "recap": "Filtered candidates with Java skill, linked to Enterprise X, passed Jombay; ranked, top 5.",
  "sql_generated": "SELECT ... LIMIT 5",
  "columns": ["name", "external_id", "assessment_score"],
  "rows": [ { "name": "...", "external_id": "...", "assessment_score": 91 } ],
  "row_count": 5, "truncated": false
}
```
Response (rejected): `{"success": false, "error": "Disallowed keyword in generated query: DROP"}`

**Audit logging** (reuses `action_audit_log`, no new table/UI): every `Analytics.java` call -
success or rejected - writes a row via the existing privileged `PoolDB` connection:
`action_type=NL_ANALYTICS_QUERY`, `mode`, `sql_generated`, `row_count`, `success`. This gives
admins full visibility into every ad-hoc query, including rejected/injection attempts, via the
existing `/api/audit` page - no new admin UI needed.

### 3f. New client component: `nexus_analytical_results`

`web/liquid/liquid.html`: `renderComponents` gains dispatch for `nexus_analytical_results` and
`nexus_audit_narrative`. New async `renderAnalyticalResults(props)`:
- Loading state, then `POST /api/analytics`.
- Renders `recap` as a one-line header, `rows` via the **existing** `renderTable(data)` (no
  changes needed there - already handles single-row key/value and multi-row layouts).
- Collapsible `<details>` "View generated SQL" block for transparency.
- `addCommentary('Analytical Query', ...)` explaining the query was auto-generated, sandboxed
  to read-only access, capped at N rows, time-limited.
- On `success:false`: inline error card + `addCommentary('Blocked', ...)`, matching the
  existing guardrail-denial pattern in `renderActionCard`.

---

## 4. Similarity path

Ships in this v0.2 pass, reusing the same sandbox - **LLM re-rank, not pgvector embeddings**.
`interaction_stream.embedding` exists but is entirely unused, no embedding-generation call
exists anywhere, and it's unverified whether the configured `VLLM_URL` endpoint even exposes an
embeddings API. Re-rank reuses 100% existing infrastructure and ships now; true embeddings are
a later, separate infra project (see Open Design Decisions).

Pipeline, same `Analytics.java` endpoint, `mode=SIMILARITY`:
1. Generate a broad candidate-pool SQL (no semantic filtering, `LIMIT 200`), executed through
   the identical `validateReadOnlySql` / `enforceLimit` / `ReadOnlyPoolDB` path as ANALYTICS.
2. One LLM re-rank call: ranks/filters the pool JSON against the original question, returns
   `[{"external_id":"...","justification":"..."}]` for the top ~5–10. Same chat-completions
   call shape already used in `Seeding.java`/`Intent.java`/`Policy.java` - no new infra.
3. Response adds a `justification` column, rendered through the same
   `nexus_analytical_results` component.

---

## 5. Files Created / Modified

| File | Status | Purpose |
|---|---|---|
| `src/org/tsicoop/nexus/framework/SchemaIntrospector.java` | New | Shared, corrected schema-context builder |
| `src/org/tsicoop/nexus/framework/ReadOnlyPoolDB.java` | New | Sandboxed HikariCP pool, `nexus_readonly` role |
| `src/org/tsicoop/nexus/api/Analytics.java` | New | NL→SQL generation, safety validation, execution, similarity re-rank; `POST /api/analytics` |
| `src/org/tsicoop/nexus/api/Intent.java` | Modified | `classifyIntelligenceQuery()`, `INTELLIGENCE_ROUTE_PROMPT`, new components in the fallback branch |
| `src/org/tsicoop/nexus/api/Policy.java` | Modified | `buildSchemaContext` delegates to `SchemaIntrospector` (bug fix) |
| `src/org/tsicoop/nexus/framework/SystemConfig.java` | Modified | +2 properties: `framework.db.ro.user`, `framework.db.ro.password` |
| `web/liquid/liquid.html` | Modified | `renderAnalyticalResults()`, `renderAuditNarrative()`, new dispatch cases |
| `web/WEB-INF/_processor.tsi` | Modified | +1 line: `/api/analytics=org.tsicoop.nexus.api.Analytics` |
| `db/init.sql` | Modified | Idempotent `nexus_readonly` role + scoped `GRANT SELECT` |
| `docker-compose.yml` | Modified | +2 env vars: `POSTGRES_RO_USER`, `POSTGRES_RO_PASSWD` (dev default) |
| `src/org/tsicoop/nexus/api/Commentary.java` | New | Card-level narrative insight; `POST /api/commentary` |
| `src/org/tsicoop/nexus/api/Intelligence.java` | Modified | Remove hardcoded sector-specific prompt; generalize `generateNarrative` for arbitrary card payloads |

---

## 6. Liquid UI Enhancements

Two gaps identified beyond just rendering the two new result components (`nexus_analytical_results`,
`nexus_audit_narrative`, both already covered in §2/§3f/§4 above):

### 6a. Discoverability

Nothing today signals that free-form analytical questions are possible. `loadSidebar()`
(`web/liquid/liquid.html`) only ever builds "Try asking" chips from live entity names and
`command_manifest` verbs - a user has no way to learn they can ask "top 5 candidates for X" at
all.

- Add one static hint chip/line to the "Try asking" section, e.g. *"Ask anything - try 'top 5
  ...' or 'why was ... blocked yesterday'"*. Kept static/generic rather than dynamically
  LLM-generated per deployment - avoids a wrong-sounding synthesized example and an extra call
  at sidebar-load time.
- Update `#cmd` placeholder text and the idle-state hint (`#stage.is-idle`) to reflect the
  broader capability, not just "member name."

### 6b. Commentary pane: LLM-narrated insight (replaces deterministic templates)

**Problem confirmed:** the commentary pane (added earlier this session) is pure JS templating -
`commentaryForContextCard()` and siblings describe UI structure ("The state panel lists N
fields... Connections lists N entities... click a chip to jump there") rather than the meaning
of the data. It can't do better than that; it has no reasoning over what the numbers mean.

**Reuse, don't reinvent:** `Intelligence.java#generateNarrative(actionType, metrics,
memberContexts)` is a fully-implemented, unused narrative generator - its system prompt is
already structured as *"one paragraph identifying the root cause, one sentence with a concrete
recommendation, cite specific entity IDs as evidence"*. `grep` confirms it is never called from
anywhere (`Templates.java`/`FormSchema.java` only use sibling methods `generateTemplate`/
`generateSchema`). It has one bug that must be fixed before reuse: its `SYSTEM_PROMPT`
hardcodes *"an institutional intelligence engine for TSI Nexus, a microfinance cooperative
platform"* - a direct violation of the zero-hardcoded-sector-logic principle.

**Changes:**

1. **`Intelligence.java`** - remove the hardcoded "microfinance cooperative platform" line;
   generalize the prompt to plain institutional language. Add a new general-purpose entry
   point (or broaden `generateNarrative`'s signature) that accepts a `cardType` string
   (`context|action|capture|disambiguation|analytics|audit`) plus an arbitrary `JSONObject
   payload` - instead of the COMPARE-specific `metrics`/`memberContexts` shape - so it can
   narrate any of the six rendered card types. Optionally loads `domain_slang` vocabulary
   (same `loadVocabSection` pattern already used in `Intent.java`/`Policy.java`) for
   institutional-term consistency.

2. **New endpoint:** `src/org/tsicoop/nexus/api/Commentary.java` → `POST /api/commentary`,
   registered in `_processor.tsi` as `/api/commentary=org.tsicoop.nexus.api.Commentary`.
   Request: `{"card_type": "context", "payload": {...}}` (payload is whatever data the card
   already has - state/live/links/stream for context cards, rows/recap for analytics results,
   audit rows for the audit narrative, etc. - no new data fetching, just reusing what the
   client already has in hand). Response: `{"success": true, "narrative": "..."}`.

3. **`web/liquid/liquid.html`** - every existing `addCommentary(title, deterministicHtml)`
   call-site is replaced with a new async `addNarrativeCommentary(cardType, payload)`:
   - Immediately shows a "Thinking…" placeholder in the commentary pane (same pulse-animation
     language as `stageLoading()`).
   - `POST /api/commentary`, then swaps the placeholder for the returned prose.
   - **Fired after** `appendToStage(card)`, decoupled from the main render - a slow or
     unavailable VLLM endpoint never blocks the card itself from appearing, matching how PULL
     live-data enrichment already behaves as a secondary, non-blocking step.
   - On failure/timeout: falls back to a short static line ("Commentary unavailable right
     now") rather than a stuck spinner.
   - The dead "What this means" block in `finalizeAction`'s success rendering (`result.narrative`
     - confirmed nothing on the backend ever populates this field today) is removed entirely;
     the commentary pane now owns that job for every card type in one consistent place instead
     of two different narrative UI locations.

**Cost/latency note:** this adds one more LLM call per rendered card, on top of the
intent-classification and NL→SQL calls already added by §1-§4 for analytical/audit/similarity
queries. Decoupling the narrative fetch from the primary render (point 3 above) means this
latency never blocks the actual answer from appearing - only the "why does this matter"
follow-up trails slightly behind it.

---

## Open Design Decisions

Resolved with the user: v0.2 ships all three paths together, and the DB role password follows
the existing dev-default/prod-override convention. Remaining open items:

### 1. Admin-visible review surface
- **Option A (lite, as designed):** Reuse `action_audit_log` + existing `/api/audit` page.
- **Option B:** Dedicated "Analytics Query Log" admin page with a promote-to-`policy_manifest`
  workflow, letting an admin turn a validated generated query into a reusable ANALYTICS row.

### 2. Row/time limits
- **Option A (lite, as designed):** Fixed constants (50 rows / 200 pool / 3s timeout).
- **Option B:** Configurable per-deployment via `root_organisation.config` JSONB (same pattern
  already used for `global_temperature`, `emergency_offline_mode`).

### 3. True embeddings roadmap
- **Option A (lite, as designed):** Defer to a later version. Document
  `interaction_stream.embedding` as reserved-but-unused; similarity stays LLM-re-rank-only.
- **Option B:** Begin planning an embedding write path now - first requires confirming whether
  the configured vLLM endpoint exposes `/v1/embeddings` (unverified), plus a backfill job and a
  two-stage ANN-then-rerank pipeline.

### 4. Read-only role table-grant scope
- **Option A (lite, as designed):** `digital_twins`, `twin_relationships`,
  `interaction_stream`, `twin_state_history`.
- **Option B:** Also grant non-secret columns of `command_manifest`/`policy_manifest` for
  meta-queries ("what policies apply to X action") - needs column-level grants or a view.

### 5. Cumulative LLM calls per user turn
A single analytical question can now trigger: intent classification → NL→SQL generation →
(similarity only) re-rank → card-level narrative. Each is a separate sequential or parallel
LLM round-trip against `VLLM_URL`.
- **Option A (lite, as designed):** Accept the latency stack for now; the narrative call is
  decoupled/async so it never blocks the primary answer from rendering.
- **Option B:** Cache narrative responses per session, keyed by `external_id` + a hash of the
  rendered state, so re-viewing the same entity within a session skips a redundant call.

---

## Verification Checklist

1. Fresh `docker-compose up`: `nexus_readonly` role exists; `psql -U nexus_readonly -c "SELECT 1"` succeeds; `psql -U nexus_readonly -c "SELECT * FROM nexus_users"` fails with permission denied.
2. Simulate an already-running deployment (drop the role manually): first `POST /api/analytics` call self-heals via the bootstrap check, no manual migration needed.
3. Seed an HR-staffing deployment with candidates, a requisition, assessment state/relationships, and a GUARDRAIL policy that blocks an offer.
4. Ask query #1 verbatim: confirm ANALYTICS routing, generated SQL executes, correct top-5 ranked results render with recap + "View generated SQL" panel.
5. Trigger a real denial dated yesterday, then ask query #2: confirm AUDIT routing, correct actor + date-range extraction, narrative quotes the actual denial reason from `action_audit_log`.
6. Ask query #3: confirm SIMILARITY routing, candidate pool capped at 200, re-rank returns justified top matches with a `justification` column.
7. Ask query #4: confirm ANALYTICS routing, correct aggregate counts by status/branch within the current week.
8. POST directly to `/api/analytics` with a question engineered to induce injected SQL (e.g. "also drop the table"): confirm rejection, 400 response, a rejected `NL_ANALYTICS_QUERY` audit row is written, and `digital_twins` row count is unchanged.
9. Craft a deliberately expensive query against a large seeded dataset: confirm the ~3s `statement_timeout` fires and returns a friendly timeout error rather than hanging.
10. Confirm existing command flows (e.g. `/disburse @member_x 500`) and single-name lookups are unchanged - regression check on `resolveToAdaptiveUI`'s first two branches.
11. Confirm a nonsense query still routes to `NONE` and shows the existing `nexus_semantic_results` fallback (no regression).
12. Confirm `action_audit_log` gains a tagged row for every analytics call (success and rejected), visible with generated SQL in the existing `/api/audit` admin page.
13. Load test: 10 concurrent analytical queries; confirm `ReadOnlyPoolDB`'s small pool (max 3) doesn't starve the main `PoolDB` pool.
14. Ask any of the four example questions: confirm the commentary pane shows a "Thinking…" state, then a prose narrative that references actual data from the card (not generic UI description), and that the card itself rendered before the narrative arrived.
15. Stop/misconfigure `VLLM_URL` temporarily: confirm cards still render normally and the commentary pane falls back to "Commentary unavailable right now" instead of hanging.
16. Confirm the "Try asking" sidebar section shows the new discoverability hint alongside the existing entity/command chips.
