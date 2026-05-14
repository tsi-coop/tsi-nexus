# TSI Nexus — Intelligence API: Client SDK Guide

Headless access to the institutional brain. Any application, script, or AI agent
can query TSI Nexus directly using API key authentication — no browser required.

---

## Authentication

All intelligence endpoints require two headers on every request:

| Header | Value |
|---|---|
| `X-API-Key` | The public key (`nxs_...`) issued when the app was registered |
| `X-API-Secret` | The secret issued at registration time (shown once, stored as SHA-256 hash) |

If either header is missing, malformed, or the key is revoked, the server returns
`401 Unauthorized`.

### Registering an App and Getting a Key

Use the Liquid admin UI or call the management endpoint directly.

**Request**
```http
POST /api/apikeys
Content-Type: application/json

{
  "action": "create",
  "app_name": "My Integration",
  "scopes": ["intent:read", "context:read", "governance:read", "capture:write"]
}
```

**Response** — the secret is returned **once and never stored in plaintext**; save it immediately.
```json
{
  "success": true,
  "api_key": "nxs_a3f9e1c2d4b07852a1f0e3c9d5b6f2a4",
  "api_secret": "9f3a1d8c2e5b047f3a2c1d9e6b4f0a7e3d2c1b9a8f7e6d5c4b3a291e0f8d7c6b"
}
```

### Scope Reference

Request only the scopes your app needs.

| Scope | Grants access to |
|---|---|
| `intent:read` | `POST /api/intent` |
| `context:read` | `POST /api/context`, `GET /api/entities`, `GET /api/graph` |
| `governance:read` | `POST /api/governance` |
| `capture:write` | `GET /api/capture`, `POST /api/capture` |

---

## Endpoints

### 1. Resolve Intent — `POST /api/intent`

**Scope:** `intent:read`

Translates natural language or a `/command @target` string into a structured
UI directive. Runs an LLM parse step when the input is free text; falls back to
fuzzy entity matching.

**Request**
```json
{
  "intent": "show me Ramesh Kumar's loan repayment history"
}
```

Or with an explicit command:
```json
{
  "intent": "/review @ramesh_mk_03"
}
```

**Response**
```json
{
  "success": true,
  "intent_captured": "show me Ramesh Kumar's loan repayment history",
  "llm_command": "/review @ramesh_mk_03",
  "components": [
    {
      "component_type": "universal_context_card",
      "props": "{\"target\":\"@ramesh_mk_03\"}"
    },
    {
      "component_type": "universal_action_confirm",
      "props": "{\"action_type\":\"REVIEW\",\"target_external_id\":\"@ramesh_mk_03\",\"intent_raw\":\"/review @ramesh_mk_03\"}"
    }
  ]
}
```

**Disambiguation** — when multiple entities match, the engine returns a disambiguation card instead of guessing:
```json
{
  "success": true,
  "components": [
    {
      "component_type": "nexus_disambiguation_card",
      "props": "{\"query\":\"Ramesh\",\"original_command\":\"review\",\"matches\":[...]}"
    }
  ]
}
```

---

### 2. Get Entity Context — `POST /api/context`

**Scope:** `context:read`

Assembles the complete institutional memory for one entity: current state,
graph relationships, and the 5 most recent interaction stream entries. Also
merges live data from any registered PULL services for that entity type.

**Request**
```json
{
  "external_id": "@ramesh_mk_03"
}
```

The `@` prefix is optional — both `"@ramesh_mk_03"` and `"ramesh_mk_03"` are accepted.

**Response**
```json
{
  "success": true,
  "context": {
    "twin_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "target": "@ramesh_mk_03",
    "type": "member",
    "state": "{\"name\":\"Ramesh Kumar\",\"kyc\":\"Verified\",\"loan_outstanding\":\"45000\"}",
    "last_updated": "2026-05-14 08:32:11.0",
    "graph_links": "[{\"type\":\"BELONGS_TO\",\"to\":\"coimbatore_01\",\"to_type\":\"branch\",\"to_name\":\"Coimbatore Branch\"}]",
    "recent_interactions": [
      { "content": "KYC verified by field officer on 12 May 2026", "timestamp": "2026-05-12 14:21:00.0" },
      { "content": "Loan repayment of ₹3,500 recorded", "timestamp": "2026-05-10 09:45:00.0" }
    ],
    "live_data": {
      "account_balance": 12450,
      "last_transaction": "2026-05-13"
    }
  }
}
```

**Entity not found**
```json
{
  "success": false,
  "reason": "Entity not found in Institutional Memory."
}
```

---

### 3. Check Governance — `POST /api/governance`

**Scope:** `governance:read`

Validates a proposed action against all matching guardrail policies in
`policy_manifest`. Returns immediately if any policy is violated. For
`ANALYTICS` actions, executes the registered query and returns data rows
instead of mutating state.

**Guardrail check request** (single target)
```json
{
  "action_type": "DISBURSE_LOAN",
  "intent_raw": "/disburse @ramesh_mk_03 50000",
  "params": {
    "target_external_id": "@ramesh_mk_03",
    "new_data": { "loan_outstanding": "50000" }
  }
}
```

**Guardrail check request** (comparison / multi-target)
```json
{
  "action_type": "TRANSFER",
  "intent_raw": "/transfer @ramesh_mk_03 @priya_cb_07",
  "params": {
    "target_1": "@ramesh_mk_03",
    "target_2": "@priya_cb_07"
  }
}
```

**Response — passed**
```json
{
  "success": true,
  "message": "Action Authorized & Finalized."
}
```

**Response — blocked**
```json
{
  "success": false,
  "reason": "Member has an outstanding overdue amount exceeding ₹10,000. Disbursement blocked."
}
```

**Analytics action response** (when `execution_mode = 'ANALYTICS'` in `policy_manifest`)
```json
{
  "success": true,
  "reason": "Repayment trend for last 6 months",
  "data": [
    { "month": "Nov 2025", "amount_paid": 3500 },
    { "month": "Dec 2025", "amount_paid": 3500 },
    { "month": "Jan 2026", "amount_paid": 0 }
  ]
}
```

> **Note:** For `GUARDRAIL` actions with a `new_data` payload, this endpoint also
> mutates `digital_twins.current_state` and appends to `action_audit_log` in a
> single transaction, then fires any registered PUSH services asynchronously.
> Omit `new_data` if you only want the policy check without mutation.

---

### 4. Interaction Capture — `GET /api/capture` and `POST /api/capture`

**Scope:** `capture:write`

#### GET — fetch applicable schemas for an entity

Returns all active `interaction_schema` rows that apply to the entity's type.
Use this to discover what interactions can be recorded before submitting one.

**Request**
```
GET /api/capture?external_id=@ramesh_mk_03
```

Optionally filter to a specific schema:
```
GET /api/capture?external_id=@ramesh_mk_03&schema_id=kyc_verification
```

**Response**
```json
{
  "success": true,
  "external_id": "@ramesh_mk_03",
  "entity_type": "member",
  "entity_name": "Ramesh Kumar",
  "schemas": [
    {
      "schema_id": "kyc_verification",
      "label": "KYC Verification",
      "applies_to": "member",
      "action_type": "KYC_VERIFY",
      "fields": [
        {
          "key": "id_type",
          "label": "ID Type",
          "type": "select",
          "options": ["Aadhaar", "PAN", "Voter ID"],
          "required": true
        },
        {
          "key": "id_number",
          "label": "ID Number",
          "type": "text",
          "pattern": "^[A-Z0-9]{8,16}$",
          "hint": "8–16 alphanumeric characters",
          "required": true,
          "state_transform": "last4"
        }
      ],
      "stream_tmpl": "KYC verified using {id_type} ending {id_number}"
    }
  ]
}
```

#### POST — submit a capture form

**Request**
```json
{
  "schema_id": "kyc_verification",
  "external_id": "@ramesh_mk_03",
  "form_data": {
    "id_type": "Aadhaar",
    "id_number": "987654321012"
  }
}
```

**Response — success**
```json
{
  "success": true,
  "message": "Captured and recorded.",
  "stream_entry": "KYC verified using Aadhaar ending 1012"
}
```

**Response — validation or policy failure**
```json
{
  "success": false,
  "reason": "Required field missing: ID Number"
}
```

> The POST performs four things in one transaction: field validation, guardrail
> policy check, `current_state` patch, and `interaction_stream` append. If any
> step fails, the transaction is rolled back and no state is changed.

---

### 5. List Entity Types — `GET /api/entities`

**Scope:** `context:read`

Returns all entity types deployed in this Nexus instance, with counts and a
sample of 8 entities per type. Also returns the full active command manifest.
Useful for bootstrapping an integration or MCP tool that needs to know what
entities exist.

**Request**
```
GET /api/entities
```

No body, no query parameters.

**Response**
```json
{
  "success": true,
  "entity_types": [
    {
      "type": "member",
      "count": 312,
      "sample": [
        { "handle": "@arjun_cb_01", "name": "Arjun Subramaniam" },
        { "handle": "@priya_cb_07", "name": "Priya Chandran" }
      ]
    },
    {
      "type": "branch",
      "count": 8,
      "sample": [
        { "handle": "@coimbatore_01", "name": "Coimbatore Branch" }
      ]
    }
  ],
  "commands": [
    {
      "command_verb": "review",
      "label": "Review Entity",
      "args_hint": "@target",
      "hint": "Pull full context and history for an entity",
      "component_type": "universal_action_confirm",
      "action_type": "REVIEW",
      "entity_type": "member",
      "multi_target": false,
      "has_value": false
    }
  ]
}
```

---

### 6. Graph — `GET /api/graph`

**Scope:** `context:read`

Returns the organisation's entity type registry (blueprints) and relationship
registry as defined in `root_organisation.config`. Useful for understanding the
schema of the digital twin graph before traversing it.

**Request**
```
GET /api/graph
```

**Response**
```json
{
  "success": true,
  "blueprints": [
    {
      "type": "member",
      "count": 312,
      "attributes": ["name", "phone", "kyc", "loan_outstanding"],
      "instances": [
        { "handle": "@arjun_cb_01", "name": "Arjun Subramaniam", "state": "{...}" }
      ]
    }
  ],
  "relationships": [
    {
      "type": "BELONGS_TO",
      "from_type": "member",
      "to_type": "branch",
      "count": 289,
      "sample": [
        { "from": "@arjun_cb_01", "to": "@coimbatore_01" }
      ]
    }
  ]
}
```

---

## Error Responses

All endpoints return a consistent error envelope.

| HTTP Status | Meaning |
|---|---|
| `401 Unauthorized` | Missing headers, bad secret, revoked key, or missing required scope |
| `400 Bad Request` | Malformed JSON body or missing required fields |
| `404 Not Found` | The referenced entity or schema does not exist |
| `500 Internal Server Error` | Unexpected server-side failure |

```json
{
  "success": false,
  "error": "Context Retrieval Failed",
  "message": "Entity not found in Institutional Memory.",
  "path": "/api/context"
}
```

---

## Code Examples

```bash
API_KEY="nxs_a3f9e1c2d4b07852a1f0e3c9d5b6f2a4"
API_SECRET="9f3a1d8c2e5b047f3a2c1d9e6b4f0a7e3d2c1b9a8f7e6d5c4b3a291e0f8d7c6b"
BASE="https://nexus.tsi.org"

# Resolve intent
curl -s -X POST "$BASE/api/intent" \
  -H "X-API-Key: $API_KEY" \
  -H "X-API-Secret: $API_SECRET" \
  -H "Content-Type: application/json" \
  -d '{"intent": "show risk profile for Ramesh Kumar"}' | jq .

# Get entity context
curl -s -X POST "$BASE/api/context" \
  -H "X-API-Key: $API_KEY" \
  -H "X-API-Secret: $API_SECRET" \
  -H "Content-Type: application/json" \
  -d '{"external_id": "@ramesh_mk_03"}' | jq .

# Check a governance action (no new_data = policy check only, no mutation)
curl -s -X POST "$BASE/api/governance" \
  -H "X-API-Key: $API_KEY" \
  -H "X-API-Secret: $API_SECRET" \
  -H "Content-Type: application/json" \
  -d '{"action_type":"DISBURSE_LOAN","intent_raw":"/disburse @ramesh_mk_03 50000","params":{"target_external_id":"@ramesh_mk_03"}}' | jq .

# Fetch capture schemas for an entity
curl -s "$BASE/api/capture?external_id=@ramesh_mk_03" \
  -H "X-API-Key: $API_KEY" \
  -H "X-API-Secret: $API_SECRET" | jq .

# Submit a capture interaction
curl -s -X POST "$BASE/api/capture" \
  -H "X-API-Key: $API_KEY" \
  -H "X-API-Secret: $API_SECRET" \
  -H "Content-Type: application/json" \
  -d '{"schema_id":"kyc_verification","external_id":"@ramesh_mk_03","form_data":{"id_type":"Aadhaar","id_number":"987654321012"}}' | jq .

# List entity types
curl -s "$BASE/api/entities" \
  -H "X-API-Key: $API_KEY" \
  -H "X-API-Secret: $API_SECRET" | jq .

# Get graph schema
curl -s "$BASE/api/graph" \
  -H "X-API-Key: $API_KEY" \
  -H "X-API-Secret: $API_SECRET" | jq .
```

---

## Key Management

The management endpoint `/api/apikeys` is browser/admin-only (no API key auth).
Use it to administer registered apps.

| Action | Payload |
|---|---|
| List all apps | `GET /api/apikeys` |
| Create | `POST` `{ "action":"create", "app_name":"...", "scopes":[] }` |
| Rotate secret | `POST` `{ "action":"rotate", "app_id":"<uuid>" }` |
| Revoke | `POST` `{ "action":"revoke", "app_id":"<uuid>" }` |
| Re-activate | `POST` `{ "action":"activate", "app_id":"<uuid>" }` |
| Delete | `POST` `{ "action":"delete", "app_id":"<uuid>" }` |

Rotating a secret invalidates the previous one immediately. The new secret is
returned once and never stored in plaintext — same as creation.
