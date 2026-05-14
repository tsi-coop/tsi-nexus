# TSI Nexus - Third-Party Integration Guide

TSI Nexus integrates with external systems through three patterns, each registered via the Admin UI → Service Registry. No provisioning APIs are required, the admin configures the integration, and your system implements the contract below.

---

## Auth model

Every registered service has an `auth_config` that specifies a header name and secret:

```json
{ "header": "X-API-Key", "secret": "your-secret" }
```

The header name and secret value are chosen by the admin at registration time. All three patterns use this same model.

> **Security note**: `auth_config` secrets are stored as plain text in the database. For production deployments, restrict database access to the application server only, no direct external access, no shared credentials. Rotate secrets by updating the service registry entry and redeploying the external system simultaneously.

---

## PULL - Enrich entity context at read time

**Direction**: Nexus → your system  
**When**: every time a user opens an entity in the Liquid interface

Implement a `GET` endpoint. Nexus will call:

```
GET https://your-api.example.com/{entityType}/{externalId}
X-API-Key: your-secret
```

Return a flat JSON object with whatever fields you want to expose:

```json
{
  "credit_score": 720,
  "bureau_status": "Clear",
  "last_check_date": "2026-05-10"
}
```

These fields become available in Context Card templates as `{{ entity.live.credit_score }}` etc.

**Register in Admin UI:**

| Field | Value |
|---|---|
| Service Type | `PULL` |
| Entity Type | the entity type this enriches, e.g. `member` |
| Base URL | `https://your-api.example.com/member` - Nexus appends `/{externalId}` |
| Auth Config | `{"header":"X-API-Key","secret":"your-secret"}` |

**Notes:**
- 2-second connect + read timeout, respond quickly or Nexus skips live data for that load
- Multiple PULL services can be registered for the same entity type; responses are merged
- HTTP 4xx/5xx are silently skipped

---

## PUSH - Receive action notifications

**Direction**: Nexus → your system  
**When**: after a form submission is committed, in a background thread

Implement a `POST` endpoint. Nexus will send:

```
POST https://your-api.example.com/nexus-events
Content-Type: application/json
X-Webhook-Secret: your-secret

{
  "action_type": "LOAN_DISBURSEMENT",
  "external_id": "member_042",
  "timestamp": "2026-05-14T08:31:00Z"
}
```

**Register in Admin UI:**

| Field | Value |
|---|---|
| Service Type | `PUSH` |
| Trigger Action | the action type that fires this, e.g. `LOAN_DISBURSEMENT` |
| Base URL | `https://your-api.example.com/nexus-events` |
| Auth Config | `{"header":"X-Webhook-Secret","secret":"your-secret"}` |

**Notes:**
- Fired asynchronously, your endpoint does not block the user
- No retries on failure, make your endpoint idempotent
- Respond with any 2xx to acknowledge

---

## INGEST - Push state updates into Nexus

**Direction**: your system → Nexus  
**When**: whenever your system has fresh data to write into an entity's state

```
POST https://nexus.yourorg.com/api/ingest
Content-Type: application/json
X-Ingest-Key: your-secret

{
  "identifier": "CREDIT_BUREAU",
  "external_id": "member_042",
  "data": {
    "credit_score": 715,
    "bureau_status": "Clear",
    "last_check_date": "2026-05-14"
  }
}
```

**Fields:**

| Field | Required | Description |
|---|---|---|
| `identifier` | yes | Matches the `Identifier` of your registered INGEST service (uppercase) |
| `external_id` | yes | The entity to update, must exist in Nexus |
| `data` | yes | Flat key/value pairs merged into the entity's state (existing keys updated, new keys added, missing keys untouched) |

**Success response:**

```json
{
  "success": true,
  "external_id": "member_042",
  "fields_updated": 3,
  "changed_keys": ["credit_score", "bureau_status", "last_check_date"],
  "before": { "credit_score": 650, "bureau_status": "Flagged" },
  "after":  { "credit_score": 715, "bureau_status": "Clear", "last_check_date": "2026-05-14" },
  "stream_entry": "Credit Bureau update for member_042: credit_score=715, bureau_status=Clear"
}
```

**Error responses:**

| HTTP | Reason |
|---|---|
| 400 | Missing `identifier`, `external_id`, or `data` |
| 403 | Unknown/inactive service identifier, or wrong auth header value |
| 404 | `external_id` not found in Nexus |
| 500 | Internal error |

**Register in Admin UI:**

| Field | Value |
|---|---|
| Service Type | `INGEST` |
| Identifier | your source name, e.g. `CREDIT_BUREAU` - must match `identifier` in your POST body |
| Auth Config | `{"header":"X-Ingest-Key","secret":"your-secret"}` |
| Stream Template | *(optional)* token template for the activity log, e.g. `Credit Bureau update for {external_id}: credit_score={credit_score}`, tokens use `{field_name}` matching keys in `data` |

### Viewing ingest history

```
GET https://nexus.yourorg.com/api/ingest?identifier=CREDIT_BUREAU&limit=20
```

```json
{
  "success": true,
  "count": 2,
  "events": [
    {
      "external_id": "member_042",
      "entity_type": "member",
      "content": "Credit Bureau update for member_042: credit_score=715, bureau_status=Clear",
      "timestamp": "14 May 2026 08:31:00"
    }
  ]
}
```

Omit `identifier` to return recent events across all registered INGEST sources.

---

## Mock integration for development

The repository includes `mock/MockServer.java` - a standalone server that simulates all three patterns for local development and demos:

- **PULL**: serves deterministic synthetic data for every registered entity type
- **INGEST**: periodically pushes synthetic state updates into a running Nexus instance

Download `mock-data.json` from the Seeding page after seeding your instance, place it in `mock/`, and run:

```bash
javac mock/MockServer.java
java -cp mock MockServer
```

The server logs each INGEST push and its HTTP response code so you can verify the end-to-end flow without a real external system.
