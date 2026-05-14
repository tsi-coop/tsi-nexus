# TSI Nexus - Core Architecture

## Platform Philosophy

TSI Nexus is a vertical-agnostic institutional intelligence platform. It gives any small or medium enterprise a sovereign "company brain" - a single private foundation that stores every entity, rule, and interaction, and surfaces them through a natural language interface.

The platform has zero sector-specific logic in Java. Every domain concept - terminology, entity types, policies, commands, templates - lives in database rows configured by the deploying institution.

---

## Admin Navigation Hierarchy

The three core configuration surfaces and their roles:

| UI Label | Old Name | Role |
|---|---|---|
| **Context Cards** | Liquid Templates | Show information (HTML templates per entity type) |
| **Input Manifests** | Form Builder | Collect information (schema-driven forms) |
| **Guardrails** | Policy Manifest | Control information (SQL-enforced business rules) |

---

## Six Pillars

### 1. Context Graph
Every entity is a **Digital Twin** (`digital_twins` table: `external_id`, `type`, `current_state` JSONB). Entities connect via `twin_relationships`. The graph walk in `Context.java` assembles: state + graph links + recent interactions + live data from external services.

### 2. Intelligence Tuning
- Choose the LLM model
- Define institutional vocabulary (`root_organisation.domain_slang` JSONB) - maps abbreviations and local terms to definitions
- Vocabulary is injected into every LLM call: intent parsing (`Intent.java`), policy SQL generation (`Policy.java`), and AI generation (`Intelligence.java`)
- Define command manifests (`command_manifest` table): slash commands that map institutional verbs to forms, context cards, and guardrails

### 3. Liquid - Adaptive Interface
Two-panel UI:
- **Left panel**: intent (natural language + command shortcuts)
- **Right panel**: materialises Context Cards, Input Manifest forms, or action confirmations

`Intent.java` resolves plain English input to a structured command using `word_similarity()` (PostgreSQL fuzzy match, no LLM required for entity resolution) + LLM for intent classification.

Context Cards are HTML templates with Liquid variable substitution:
- `{{ actor.state.FIELD }}` - from `current_state` JSONB
- `{{ entity.live.FIELD }}` - from external PULL service data
- `{{ actor.external_id }}`, `{{ actor.type }}`

### 4. Guardrails
`policy_manifest` table: each row is a SQL policy keyed by `action_type` and `execution_mode`.

**GUARDRAIL mode** — a pre-action gate. `query_logic` must return `COUNT(*)`. `Capture.java` runs every GUARDRAIL policy for the `action_type` before committing anything; if COUNT > 0, the action is blocked and `error_message` is returned to the user. Zero hardcoded logic.

**ANALYTICS mode** — a query-as-command. `query_logic` returns meaningful rows with named columns (not a COUNT). `Capture.java` ignores ANALYTICS policies entirely; they only fire when `Governance.java` receives a POST for that `action_type`. Instead of blocking or mutating state, `Governance.java` runs the SQL, collects the result rows, and returns them as data. The `error_message` field is repurposed as a display label for the result set. This is how slash commands like `/report @entity` return a data table rather than triggering an action.

See **Command Patterns** below for how to wire these into the Liquid interface.

### 5. Interaction Stream
`interaction_stream` table: append-only log of every action, note, field visit, and automated event. Acts as institutional memory. Every `Capture.java` POST appends an entry rendered from the schema's `stream_tmpl`.

### 6. Service Registry
Three integration types (see detailed section below).

---

## Command Patterns

Every Liquid command is a row in `command_manifest`. The `component_type` column controls what the right panel renders when the command is invoked; the `action_type` column is the key that joins to `policy_manifest` and `interaction_schema`. Three patterns cover all institutional workflows:

---

### Pattern 1 — Form Capture

Opens a schema-driven form. On submit, `Capture.java` validates fields, checks GUARDRAIL policies, patches `current_state`, appends to `interaction_stream`, and fires PUSH services.

**When to use:** collecting structured data that changes entity state — KYC verification, loan application, survey.

**Wiring:**

| Table | Key columns | Example value |
|---|---|---|
| `command_manifest` | `command_verb`, `component_type`, `linked_form` | `kyc`, `interaction_capture_form`, `<schema_id>` |
| `interaction_schema` | `schema_id`, `action_type`, `fields`, `state_patch`, `stream_tmpl` | `KYC_MEMBER_CAPTURE`, fields array, `{"kyc":"Verified"}`, `"KYC completed for {name}"` |
| `policy_manifest` | `action_type`, `execution_mode`, `query_logic`, `error_message` | `KYC_MEMBER_CAPTURE`, `GUARDRAIL`, `SELECT COUNT(*) FROM digital_twins WHERE external_id=? AND current_state->>'kyc'='Verified'`, `"Member already verified"` |

`linked_form` in `command_manifest` must match the `schema_id` in `interaction_schema`. The policy `action_type` must match the schema `action_type`. The GUARDRAIL policy is optional — omit it if the form should always be submittable.

---

### Pattern 2 — Action Confirmation (GUARDRAIL)

Shows a confirmation card with the target entity and action details. On confirm, `Governance.java` runs GUARDRAIL policies → executes a state mutation → logs to the audit stream. No form fields — the command itself carries the parameters.

**When to use:** single-step state changes that need a policy gate — disbursing a loan, approving an application, closing an account.

**Wiring:**

| Table | Key columns | Example value |
|---|---|---|
| `command_manifest` | `command_verb`, `component_type`, `action_type` | `disburse`, `action` (default), `DISBURSE` |
| `policy_manifest` | `action_type`, `execution_mode`, `query_logic`, `error_message` | `DISBURSE`, `GUARDRAIL`, `SELECT COUNT(*) FROM digital_twins WHERE external_id=? AND current_state->>'kyc'!='Verified'`, `"KYC must be completed before disbursement"` |

`interaction_schema` is not needed for this pattern. Multiple GUARDRAIL policies can share the same `action_type` — all are checked and any violation blocks the action.

---

### Pattern 3 — Analytics Query

Shows a confirmation card, but instead of mutating state the command runs a SQL query and returns the results as a data table. No state change, no form, no PUSH.

**When to use:** on-demand reporting within the Liquid interface — loan history, repayment schedule, overdue summary.

**Wiring:**

| Table | Key columns | Example value |
|---|---|---|
| `command_manifest` | `command_verb`, `component_type`, `action_type` | `report`, `action` (default), `LOAN_REPORT` |
| `policy_manifest` | `action_type`, `execution_mode`, `query_logic`, `error_message` | `LOAN_REPORT`, `ANALYTICS`, `SELECT disbursed_on, amount, status FROM loans WHERE member_id=?`, `"Loan History"` |

`execution_mode = 'ANALYTICS'` is the only switch. `Capture.java` skips ANALYTICS rows entirely; `Governance.java` detects the mode and returns rows instead of executing a guard. The `error_message` field becomes the table label in the UI. `query_logic` may return any columns — use named columns for readable output.

---

### Summary

| Pattern | `component_type` | `execution_mode` | State change | Form |
|---|---|---|---|---|
| Form Capture | `interaction_capture_form` | `GUARDRAIL` (optional) | Yes — via `state_patch` | Yes |
| Action Confirmation | `action` (default) | `GUARDRAIL` | Yes — via Governance | No |
| Analytics Query | `action` (default) | `ANALYTICS` | No | No |

---

## Service Registry Architecture

### PULL - Enrich context at read time
**Flow**: `Context.java:callPullServices()` → on every entity context load, queries `service_registry WHERE service_type='PULL' AND entity_type=? AND status='Active'` → GETs `api_base_url/{externalId}` → merges JSON response into `live_data` → accessible in Context Card templates as `{{ entity.live.FIELD }}`

**Config**: `auth_config` JSONB `{"header": "X-API-Key", "secret": "..."}` - header/secret sent with every GET request. 2-second timeout.

### PUSH - Notify external systems after action
**Flow**: `Capture.java:callPushServices()` → background thread after DB commit → queries `service_registry WHERE service_type='PUSH' AND trigger_action=? AND status='Active'` → POSTs payload to `api_base_url`

**Payload**: `{action_type, external_id, form_data: {...}, timestamp}`

### INGEST - Receive data from external systems
**Flow**: External system POSTs to `POST /api/ingest` with `{identifier, external_id, data}` → `Ingest.java` validates auth header against `service_registry` auth_config → captures before-state → patches `digital_twins.current_state` → appends to `interaction_stream` using `stream_tmpl` → returns before/after diff

**History**: `GET /api/ingest?identifier=<SOURCE>&limit=N` returns recent ingest events from `interaction_stream` for that source. Omit `identifier` to return all ingest events across all registered INGEST sources.

**Config**: `service_registry` rows with `service_type='INGEST'`:
- `identifier` - source name, e.g. `MOCK_CREDIT_BUREAU_INGEST`
- `auth_config` JSONB `{"header": "X-Ingest-Key", "secret": "..."}` - validated on every POST
- `stream_tmpl` - token template for the stream entry, e.g. `"Credit Bureau update for {external_id}: credit_score={credit_score}"`. Tokens: `{external_id}` and any `{field_name}` from the data payload. Falls back to ad-hoc `key=value` format if empty.

**POST response shape**:
```json
{
  "success": true,
  "external_id": "SEED_member_001",
  "fields_updated": 3,
  "changed_keys": ["credit_score", "bureau_status", "last_check_date"],
  "before": { "credit_score": 650, "bureau_status": "Clear" },
  "after":  { "credit_score": 720, "bureau_status": "Flagged", "last_check_date": "2026-05-14" }
}
```

---

## Seeder Architecture

`Seeding.java` takes an `industry_context` string and generates a fully configured institutional instance. Every step calls the LLM with the same domain context, ensuring semantic alignment across all generated artefacts.

### Pipeline (8 steps)

```
[1/8] Twins            - Digital twins with realistic current_state JSONB
[2/8] Relationships    - Graph edges between twins
[3/8] Interactions     - Interaction stream history entries
[4/8] Mock services    - External data field specs; registers PULL + INGEST rows in service_registry
[5/8] Context Cards    - HTML templates; receives live field names from step 4, emits {{ entity.live.* }}
[6/8] Input Manifests  - interaction_schema forms; prefill metadata from step 4 field names
[7/8] Commands         - command_manifest + paired guardrail policies
[8/8] Policies         - Additional policy_manifest rows
```

### Alignment guarantee
Mock services (step 4) run before templates (step 5) and forms (step 6). The live field names from step 4 are passed as context into the prompts for steps 5 and 6. This guarantees `{{ entity.live.credit_score }}` in a template matches `credit_score` in `mock-data.json` - no post-hoc coordination.

### Mock service output
Step 4 produces:
1. **PULL rows** in `service_registry` (`api_base_url = http://host.docker.internal:9090/{entity_type}`, auth: `X-Mock-Key / nexus-demo`)
2. **INGEST rows** in `service_registry` (identifier = `MOCK_{SERVICE_NAME}_INGEST`, auth: `X-Ingest-Key / ingest-demo`, `stream_tmpl` auto-generated from field names)
3. `mock-data.json` stored in `seeding_sessions.mock_data` JSONB column, downloadable from the seeding UI

### mock-data.json extended structure
```json
{
  "port": 9090,
  "auth_header": "X-Mock-Key",
  "auth_secret": "nexus-demo",
  "nexus_ingest_url": "http://host.docker.internal:8080/api/ingest",
  "services": {
    "member": {
      "service_name": "Credit Bureau",
      "ingest_identifier": "MOCK_CREDIT_BUREAU_INGEST",
      "ingest_auth_header": "X-Ingest-Key",
      "ingest_auth_secret": "ingest-demo",
      "ingest_interval_seconds": 30,
      "external_ids": ["SEED_member_001", "SEED_member_002", ...],
      "fields": { "credit_score": {"type":"int","min":300,"max":850}, ... }
    }
  }
}
```

### MockServer (external, lives in repo)
`mock/MockServer.java` - standalone single-file Java app (no WAR dependency):
- **PULL mode** (always on): Serves `GET /{entityType}/{externalId}` with stable pseudo-random values (`new Random(externalId.hashCode())`)
- **INGEST push mode** (when `nexus_ingest_url` present): Scheduled thread per entity type, every `ingest_interval_seconds` picks a random `external_id`, generates synthetic field values, POSTs to Nexus `/api/ingest`. Uses varied RNG (`externalId.hashCode() ^ currentTimeMillis`) so each push produces different values.
- Auth header validation on PULL requests
- Compile and run: `javac mock/MockServer.java && java -cp mock MockServer`
- Startup log shows both PULL routes and INGEST push schedules

### Admin demo flow after seeding
1. Run seeding with domain context
2. Download mock config from seeding page
3. Place in `mock/`, run `java mock/MockServer.java`
4. PULL services already registered, Liquid shows `{{ entity.live.* }}` immediately
5. INGEST push thread starts automatically, every 30s terminal shows `[INGEST] ... HTTP 200`
6. `GET /api/ingest` - ingest history stream; entity context cards show real-time external updates

---

## Input Manifests - External Integration

### Pre-fill from live data (planned)
Field schema JSONB extended with optional `prefill` key:
```json
{ "key": "declared_income", "label": "Declared Income", "type": "number",
  "prefill": "live.monthly_income" }
```
Resolved client-side in `liquid.html` - no backend change. Context already contains `entity.live` from the PULL service.

### PUSH with full payload (planned)
`Capture.java:callPushServices()` sends complete `form_data` in POST body so external systems can act on the submission without a callback.

---

## LLM Integration Points

| File | What LLM does | Vocabulary injected |
|---|---|---|
| `Intent.java` | Classifies plain English input into a structured command | Yes - `domain_slang` prepended to system prompt |
| `Policy.java` | Generates SQL `query_logic` from natural language policy description | Yes - appended to user message |
| `Intelligence.java` | Generates Context Card HTML or Input Manifest field schema | Yes - as CONTEXT line in system prompt |
| `Seeding.java` | Generates all 8 seeding artefacts | Domain context drives all prompts |

Vocabulary source: `root_organisation.domain_slang` JSONB - key/value pairs mapping local terms to definitions.

---

## Key Design Principles

1. **No hardcoded sector logic in Java** - entity types, field names, policy SQL, templates, commands all come from DB rows
2. **Seeder-driven alignment** - anything generated together (templates + mock fields) must share the same LLM pass to guarantee name alignment
3. **Mock infrastructure is external** - demo/dev tooling lives outside the WAR; the production system only knows about registered service URLs
4. **Safe HTML attribute injection** - dynamic term values in onclick handlers use `data-term="${esc(term)}"` + `this.dataset.term` pattern, never `JSON.stringify` in attribute strings
5. **Vocabulary is always conditional** - LLM prompts include the vocab section only when non-empty, so unconfigured deployments get no prompt bloat
6. **COALESCE for JSONB merge** - always `COALESCE(column, '{}') || jsonb_build_object(...)` to handle NULL columns safely

---

## Key Files

| File | Role |
|---|---|
| `src/.../api/Context.java` | Assembles full entity context: state + graph + stream + live |
| `src/.../api/Capture.java` | Schema-driven form submission engine: validates, checks guardrails, patches state, appends stream, fires PUSH |
| `src/.../api/Intent.java` | Natural language → structured command resolution |
| `src/.../api/Policy.java` | Natural language → SQL guardrail generation |
| `src/.../api/Seeding.java` | 8-step LLM-driven institutional data generator |
| `src/.../api/ServiceRegistry.java` | CRUD + health-check for service_registry |
| `src/.../api/Ingest.java` | INGEST endpoint: GET history + POST with before/after diff and stream_tmpl |
| `src/.../framework/InterceptingFilter.java` | Front controller: routes `/api/*` to Action classes via `_processor.tsi` |
| `web/WEB-INF/_processor.tsi` | URL → class mapping registry |
| `web/liquid/liquid.html` | Liquid two-panel adaptive interface |
| `web/admin/intelligence_tuning.html` | Vocabulary, model config, command builder |
| `web/admin/policy_manifest.html` | Guardrails admin with right-panel explanation |
| `mock/MockServer.java` | External standalone mock server: PULL (serves GET) + INGEST push (scheduled POST to Nexus) |

---

## DB Tables (core)

| Table | Purpose |
|---|---|
| `digital_twins` | Entity state store: external_id, type, current_state JSONB |
| `twin_relationships` | Graph edges: from_twin_id, to_twin_id, relationship_type, metadata |
| `interaction_stream` | Append-only activity log: owner_id, content, intent_mapped |
| `liquid_templates` | Context Card HTML: entity_type, html_content, condition_sql |
| `interaction_schema` | Input Manifest definitions: schema_id, fields JSONB, action_type, state_patch, stream_tmpl |
| `command_manifest` | Slash commands: verb, action_type, component_type, linked_form |
| `policy_manifest` | Guardrails: action_type, query_logic (SQL), error_message, execution_mode |
| `service_registry` | External services: identifier, api_base_url, auth_config, service_type (PULL/PUSH/INGEST), entity_type, trigger_action, stream_tmpl |
| `root_organisation` | Config: domain_slang JSONB, llm settings |
| `seeding_sessions` | Seeding history + mock_data JSONB (downloadable mock config) |
