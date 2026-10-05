# Release Notes

## v0.2

- **Bring your own LLM.** One provider-neutral client for self-hosted open-weight models (e.g. Gemma via vLLM), Sarvam, OpenAI, Claude and Gemini, configured through `LLM_*` settings.
- **Digital Twin write API.** Create, update, soft-delete and restore twins and relationships over `/api/twins` and `/api/relationships`, with new `twins:read` / `twins:write` API key scopes.
- **Guardrails that use submitted data.** A guardrail's SQL can bind request fields (such as a quantity) through `param_keys`, for both `/api/capture` and `/api/governance`.
- **Security hardening.** Console pages now require a server-side login, and first-run setup requires `TSI_NEXUS_BOOTSTRAP_TOKEN`. See [`SECURITY.md`](SECURITY.md).
