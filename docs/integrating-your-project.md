# Integrating TSI Nexus Into Your Project

This guide is for teams who want to wire TSI Nexus into a real product rather
than explore it with synthetic demo data. If you've already played with the
`/seed` tool, the [mock integration server](../README.md#3-first-time-setup),
or the [example agents](../examples/agents/README.md), those were the demo
path - this guide covers the production path instead.

There are two integration surfaces. Most real deployments use both together.

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

---

## Recommended sequence: a fresh installation

Don't build your real deployment on top of a database that was used for the
`/seed` demo. Start clean:

1. **Fresh install.** Stand up a new instance - `docker compose up -d` against
   a fresh database - and complete the setup wizard at `/setup` to create your
   admin account. See [Getting started](../README.md#getting-started) if you
   haven't done this yet.
2. **Onboard your project.** Open `/onboard` and describe your organisation.
   This generates entity types, context card templates, input manifests,
   commands, and guardrails from that description - no synthetic digital
   twins, relationships, interaction history, or mock service registrations
   are created. Load your real entities afterward via INGEST or a direct
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
