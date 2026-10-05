# Release Notes

## v0.2

- **Bring your own LLM.** One provider-neutral client for self-hosted open-weight models (e.g. Gemma via vLLM), Sarvam, OpenAI, Claude and Gemini, configured through `LLM_*` settings.
- **Digital Twin write API.** Create, update, soft-delete and restore twins and relationships over `/api/twins` and `/api/relationships`, with new `twins:read` / `twins:write` API key scopes.
- **Guardrails that use submitted data.** A guardrail's SQL can bind request fields (such as a quantity) through `param_keys`, for both `/api/capture` and `/api/governance`.
- **Security hardening.** Console pages now require a server-side login, and first-run setup requires `TSI_NEXUS_BOOTSTRAP_TOKEN`. See [`SECURITY.md`](SECURITY.md).

### Known limitation

Admin-side AI tasks (demo data seeding, guardrail SQL generation, context card templates and analytics) currently share the single configured LLM. Reasoning-style models can be slow or return incomplete output for these bulk generation tasks, so use a standard instruct model for seeding.

### Coming in v0.3

- **Dedicated admin model.** A separate, tuned model setting for seeding, SQL generation, templates and analytics.
- **Action decisioning.** An engine such as Laya for deciding on agent actions.
