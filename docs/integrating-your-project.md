# Integrating TSI Nexus Into Your Project

This guide is for teams who want to wire TSI Nexus into a real product rather
than explore it with synthetic demo data. If you've already played with the
`/seed` tool, the [mock integration server](../README.md#3-first-time-setup),
or the [example agents](../examples/agents/README.md), those were the demo
path - this guide covers the production path instead.

There are two integration surfaces - the Service Registry and the headless
Intelligence API - and you configure both via direct, script-driven API calls
("config-as-code"). Most real deployments use both surfaces together.

---

## 1. Connect your real backend systems (Service Registry)

Every external system TSI Nexus talks to - a credit bureau, an HR system, an
industrial gateway, whatever you run - is registered in the Admin UI as a
PULL, PUSH, or INGEST service, each with its own auth header/secret.

| Pattern | Direction | Use case |
|---|---|---|
| **PULL** | Nexus → your system | Enrich entity context at read time |
| **PUSH** | Nexus → your system | Notify your system after a form is submitted |
| **INGEST** | Your system → Nexus | Push state updates into Nexus from an external source |

See [`docs/integration-guide.md`](integration-guide.md) for the full contract,
field-by-field payloads, and registration reference.

> If you explored TSI Nexus via the `/seed` demo, its Service Registry rows
> point at `examples/integrations/MockServer.java` rather than a real system -
> register your real endpoints alongside or in place of those before go-live.

---

## 2. Call Nexus from your own app or agent (headless Intelligence API)

This is what the Python scripts in `examples/agents/` stand in for during a demo.
In production, your own backend, chat surface, or AI agent calls the same
endpoints those scripts call, using a real API key scoped to what it needs.

1. Create a scoped API key: Admin UI → API Keys, or `POST /api/apikeys`.
2. Call the Intelligence API with `X-API-Key` / `X-API-Secret` headers:
   - `POST /api/intent` - resolve natural language or `/command` strings
   - `POST /api/context` - fetch an entity's full state, graph links, and recent history
   - `POST /api/governance` - check (and optionally execute) an action against guardrails
   - `GET`/`POST /api/capture` - discover and submit structured interaction forms
   - `GET /api/entities`, `GET /api/graph` - discover what's deployed, for bootstrapping an integration

See [`docs/api-client-sdk.md`](api-client-sdk.md) for full request/response
examples, scopes, error envelopes, and curl snippets for every endpoint.

Separately, if your app creates or manages digital twins directly (rather than
only reading/writing their state through Intent/Context/Capture), see the
Twins & Relationships API in
[`docs/api-client-sdk.md`](api-client-sdk.md#twins--relationships-api):
`POST`/`GET`/`PATCH`/`DELETE /api/twins`, `POST /api/twins/{id}/restore`,
`POST /api/twins/{id}/state/clear`, `GET`/`POST`/`DELETE /api/relationships`.

> **Limitation:** a capture-flow guardrail (`POST /api/governance` /
> `POST /api/capture`) can only inspect the target twin's own state, not the
> data in the incoming form submission. Validation that depends on the
> submitted payload (e.g. "reject if the requested quantity exceeds what was
> on offer") has to be pre-checked in your own app before calling
> `/api/capture` - it can't be expressed as a Nexus guardrail.

---

## 2b. Configure via direct API calls ("config-as-code")

Define entity types, relationship types, capture schemas, guardrails,
commands, and templates yourself via direct admin-authenticated `POST`
calls. This is useful when you want config that's reviewable, re-runnable,
and versioned alongside your app code rather than generated once from a
prose description.

| Endpoint | Defines |
|---|---|
| `POST /api/graph` | Entity types (`define_type`) and relationship types (`define_rel`) |
| `POST /api/schemas` | Capture form schemas |
| `POST /api/policy` | Guardrails (policy manifest) |
| `POST /api/tuning` | Vocabulary terms, and commands (`add_command`) |
| `POST /api/templates` | Context card templates |

These use the admin JWT (`POST /api/auth` with `email`/`password`, returned
as `token`, sent as `Authorization: Bearer <token>`), not an `X-API-Key` app
key - the two auth systems are separate and not interchangeable. Run your
scripts in dependency order (entity/relationship types first, then schemas,
then policy/tuning/templates, which reference them), and write them as
idempotent upserts (`action:"upsert"`, or `ON CONFLICT` if you're inserting
directly) so re-runs are safe.

A command registered via `POST /api/tuning` (`add_command`) requires at least
one of `linked_form` or `linked_template` - it always drives either a capture
form or a context-card render. For a command that only reads and summarizes
existing state, with no form and no side effect, you may not be able to
register it as a `/command` at all: call `/api/context` directly instead and
handle the natural-language framing in your own app.

---

## Recommended sequence: a fresh installation

Don't build your real deployment on top of a database that was used for the
`/seed` demo. Start clean.

1. **Fresh install.** Stand up a new instance - `docker compose up -d` against
   a fresh database - and complete the setup wizard at `/setup` to create your
   admin account. See [Getting started](../README.md#getting-started) if you
   haven't done this yet.
2. **Define your entity types, schemas, guardrails, commands, and templates
   via [config-as-code](#2b-configure-via-direct-api-calls-config-as-code).**
   Write scripts against `/api/graph`, `/api/schemas`, `/api/policy`,
   `/api/tuning`, `/api/templates` as idempotent upserts. No synthetic digital
   twins, relationships, interaction history, or mock service registrations
   get created - load your real entities afterward via INGEST or a direct
   data load.
3. **Configure the Service Registry.** Register your real PULL / PUSH /
   INGEST endpoints in Admin UI → Service Registry, following
   [Section 1](#1-connect-your-real-backend-systems-service-registry) above.
4. **Issue a scoped API key** and point your real backend/agent at the
   headless API, following
   [Section 2](#2-call-nexus-from-your-own-app-or-agent-headless-intelligence-api) above.
5. **Review every guardrail** in Policy Manifest against your actual business
   rules before go-live.

If you'd already explored TSI Nexus via `/seed` on the instance you now want
to run in production, retire that demo data first: stop `MockServer.java`,
revoke any API keys created for testing, and either clear the seeded data
(`/seed` → **Clear Seeded Data**) or start over on a fresh database as above.

---

## Where to go next

| Need | Doc |
|---|---|
| External system contract (PULL/PUSH/INGEST) | [`docs/integration-guide.md`](integration-guide.md) |
| Headless API reference (auth, endpoints, scopes) | [`docs/api-client-sdk.md`](api-client-sdk.md) |
| Architecture, pillars, DB schema | [`docs/architecture.md`](architecture.md) |
