# Laya as the Default Runtime Intelligence

**Version:** 0.2
**Status:** Planning
**Module path:** `src/org/tsicoop/nexus/framework/` (new `DecisionEngine`, `LayaEngine`), `src/org/tsicoop/nexus/api/Intent.java` and `Analytics.java` (modified)
**Upstream:** https://github.com/NandhaKishorM/laya

---

## Goal

Make everyday Nexus use run with **no GPU and no per-token cost**. [Laya](https://github.com/NandhaKishorM/laya) runs locally on CPU, so it becomes the default **runtime intelligence**. All generative work (setup and analytics) is grouped as **admin intelligence**, configured independently and backed by an LLM. **The admin LLM is mandatory**: every deployment configures one, hosted or self-hosted. Air-gapped sites self-host an open-weight model such as Gemma. Laya removes the LLM from the hot path; it does not remove the LLM requirement.

## Key finding: Laya is not a chat LLM

Per the Laya README (summarised, verify against the repo before Phase 1), Laya is a non-autoregressive **decision engine**:

- Three encoder checkpoints, about 320-420M parameters: `laya` (English, 512 ctx), `laya-multilingual` (100+ languages, 1,024 ctx, up to 8,192 windowed), `laya-typed-decisions` (fine-tuned on domain workflows, 1,024 ctx).
- One forward pass returns a typed decision: `choice` (multi-option with probabilities), `score` (ordinal), `noul` (yes/no with calibrated probability), or a structured decision via JSON schema.
- It **does not generate text**.
- Python 3.10+, PyTorch, Transformers. Optional FastAPI server `laya-serve` exposing `POST /v1/systemone` with a custom `state` / `questions` schema. **Not OpenAI-compatible.**
- Apache 2.0.
- Published latency (33-40ms single question, 72-158ms for 10 batched) was measured on a **T4 GPU**. CPU numbers are unmeasured and are the first thing Phase 0 must establish.

Consequence: Laya cannot replace `LLMClient`. It replaces the LLM only where the task is a classification. That is what motivates the split below.

## Admin intelligence vs runtime intelligence

Nexus makes two very different kinds of intelligence calls. They get separate configuration.

| | Admin intelligence | Runtime intelligence |
|---|---|---|
| **What** | Setup (seeding, Policy SQL, Context Cards, Input Manifests) and analytics (NL-to-SQL, similarity re-rank) | Intent routing, command classification, parameter binding, soft signals |
| **Who** | Admins and backend / staff users (analytics already requires `getAdminAuthToken`) | Every Liquid user, on every request |
| **Frequency** | Rare, deliberate | Every keystroke |
| **Latency tolerance** | A few seconds is fine | Very low |
| **Needs** | A generative model | A classifier |
| **Default** | **Required**: any LLM the admin picks (hosted, or self-hosted such as Gemma) | **Laya** |
| **Cost profile** | Occasional; negligible even on a hosted model, free if self-hosted | Must be free and fast |

The rule: **runtime never calls a generative model.** Every generative call is an admin-intelligence call, and the admin LLM is required.

### What "mandatory" means

- **Nexus does not refuse to start.** Without a working admin LLM it starts in a **setup-required** state. Runtime (Laya) keeps working; admin features (seeding, generation, analytics) return a clear "configure the admin LLM" error.
- **Startup check.** On boot, `LLMClient.ping()` (which already exists) validates the admin LLM: config present, key accepted, model reachable. The result is cached and exposed by `Tuning.java` as `admin_llm_status`: `ready`, `not_configured`, `unreachable` or `auth_failed`.
- **First-run experience.** Until `admin_llm_status` is `ready`, the admin UI shows a banner and routes first-time admins to `intelligence_tuning.html` to configure and test the model. Seeding is blocked until then.
- **Failure at runtime.** An invalid key or unreachable model degrades only admin features, with the same status and a retry. It never affects Laya-driven runtime.
- **Air-gapped.** Point `ADMIN_LLM_*` at a self-hosted OpenAI-compatible server (vLLM or llama.cpp serving Gemma). This is what `LLMClient` already supports and defaults to, so no new provider is needed.

### Call-site mapping

| Call site | Bucket | Engine |
|---|---|---|
| `Intent.classifyIntelligenceQuery` (AUDIT / ANALYTICS / SIMILARITY / NONE) | Runtime | Laya `choice` |
| `Intent.llmParseIntent` (pick a `/command`) | Runtime | Laya `choice` over `command_manifest` |
| `Intent` parameters (`actor_handle`, dates) | Runtime | `word_similarity()` + small generic date parser |
| `Analytics.generateSql` free-form NL-to-SQL | Admin | Admin LLM (unchanged logic) |
| `Analytics.reRank` (SIMILARITY) | Admin | Admin LLM (unchanged logic) |
| `Policy.generateSql` | Admin | Admin LLM |
| `Intelligence.java` (Context Cards, forms) | Admin | Admin LLM |
| `Seeding.java` | Admin | Admin LLM |

## Architecture

Add a second engine interface beside `LLMClient` instead of bolting Laya into it. `LLMClient.chat()` is text-in / text-out, which does not fit Laya's typed-decision model.

```
DecisionEngine (new interface)     decide(question, options[]) -> {choice, probs, confidence}
   ├─ LayaEngine                   HTTP to laya-serve (default runtime engine)
   └─ LLMDecisionEngine            wraps today's prompts via LLMClient (low-confidence fallback)

LLMClient (unchanged)              admin intelligence: SQL, HTML, seeding, analytics
```

**Routing rule:** runtime code calls `DecisionEngine` first. If Laya's confidence is below `LAYA_MIN_CONFIDENCE`, it falls back to the LLM when one is configured. If no LLM is configured, it takes the existing `/unknown` or NONE path.

Design principle preserved: no sector logic in Java. Options come from `command_manifest` rows, vocabulary from `root_organisation.domain_slang`.

## Configuration

Two independent blocks, shown as two status cards in `intelligence_tuning.html`.

```
# Admin intelligence (generative: setup + analytics)
ADMIN_LLM_PROVIDER / ADMIN_LLM_BASE_URL / ADMIN_LLM_MODEL / ADMIN_LLM_API_KEY
    ...plus the existing LLM_CHAT_PATH / LLM_AUTH_HEADER / LLM_API_VERSION overrides

# Runtime intelligence (default: Laya)
RUNTIME_ENGINE=laya|llm
LAYA_BASE_URL, LAYA_MIN_CONFIDENCE, LAYA_TIMEOUT_MS
```

- `LLM_*` and `VLLM_*` remain accepted as a fallback for `ADMIN_LLM_*`, so existing deployments keep working (same backward-compat approach `LLMClient` already uses for `VLLM_*`).
- `RUNTIME_ENGINE=llm` reproduces today's behaviour: runtime calls go through `LLMClient` with the current prompts.
- `Tuning.java` reports both sides: `admin_llm_*` and `runtime_engine_*` status, alongside the existing `llm_online` keys for compatibility.
- If a deployment later wants a different model for analytics than for setup (for example a stronger reasoning model), add an optional `ANALYTICS_LLM_*` override that defaults to the admin config. Not built in this version.

## Analytics under the admin config

`Analytics.java` keeps its current design: a free-form question goes to the LLM (`generateSql`, line 235), the result is validated (`validateReadOnlySql`), limited (`enforceLimit`), and run on the read-only connection (`ReadOnlyPoolDB`); SIMILARITY mode re-ranks with `reRank`. The only change is which config it reads (the admin LLM). Analytics users are admin-authenticated backend and staff users who ask deliberate questions and can wait a few seconds, so a generative model is the right tool and its cost is negligible.

While the admin LLM is not ready (setup-required state), analytics returns a clear "configure the admin LLM" message with the current `admin_llm_status`, rather than the current bare "no output" error.

## Phases

### Phase 0 - Feasibility spike (about 1 day, do first)

- `pip install laya`, run `laya-serve` on a CPU-only machine. Measure p50/p95 latency, RAM, cold start.
- Build an eval set of about 100 real utterances from the seeded domains (HR, edtech, lending) plus the examples in `conversational-intelligence.md`. Label each with its route and command. Check `ts-nexus-eval/` for reusable harness code.
- Compare zero-shot `laya`, `laya-multilingual` and `laya-typed-decisions` against the current Gemma setup on accuracy.
- Test `choice` accuracy at 10, 50 and 200 options, to size the command shortlist problem.
- **Go/no-go:** route accuracy within a few points of the LLM, and CPU p95 under about 300ms.

### Phase 1 - Engine abstraction and admin/runtime config (`framework/`)

- New `DecisionEngine.java`, `LayaEngine.java`, `LLMDecisionEngine.java`, using the same HTTP client and error conventions as `LLMClient`.
- Add `ADMIN_LLM_*` and `RUNTIME_ENGINE` / `LAYA_*` env handling, with `LLM_*` / `VLLM_*` as backward-compatible fallbacks for `ADMIN_LLM_*`.
- `LayaEngine.ping()`; `Tuning.java` reports admin and runtime status separately, including `admin_llm_status` (`ready` / `not_configured` / `unreachable` / `auth_failed`).
- Startup health check for the admin LLM, cached and refreshable from the admin UI. Nexus starts in a setup-required state instead of failing.
- A shared guard used by `Seeding`, `Intelligence`, `Policy` and `Analytics` that returns the standard "configure the admin LLM" error when status is not `ready`.

### Phase 2 - Move Intent onto the runtime engine (`Intent.java`)

- Replace `classifyIntelligenceQuery` with a `choice` call over the four routes.
- Replace `llmParseIntent` with a `choice` over `command_manifest` rows. Option text is built from `label`, `hint` and `command_verb`. `domain_slang` expansions are injected into the question text.
- Resolve `actor_handle` with `word_similarity()`; dates with a small generic parser, with no sector logic.
- Keep the LLM path as the low-confidence fallback. This also retires the 300 / 3072 `max_tokens` reasoning-headroom workarounds on the default path.

### Phase 3 - Analytics on the admin config

- `Analytics.java` reads the admin LLM config. Its generation and re-rank logic is unchanged.
- With no admin LLM configured, return a clear "needs a generative model configured" response instead of the bare "no output" error.

### Phase 4 - Utterance generation to train the runtime engine

The admin LLM already generates commands during seeding. Extend it to also generate a few example utterances per command.

- Use them as option text or few-shot examples for Laya. This directly addresses the zero-shot domain-jargon accuracy risk.
- Store them (`command_manifest` extra column or a small `intent_examples` table) so they can be edited.
- They double as fine-tuning data for `laya-typed-decisions` in Phase 6.

### Phase 5 - Packaging and first-run

- Add a `laya` service to `docker-compose.yml` (Python image, `laya-serve`, model-cache volume).
- Compose defaults: `RUNTIME_ENGINE=laya`, no GPU, and no API key needed for runtime. `ADMIN_LLM_*` is **required**; compose fails fast with a clear message if it is unset, unless the air-gapped profile (Phase 7) is enabled.
- First-run flow in the admin UI: banner while `admin_llm_status` is not `ready`, a guided configure-and-test step in `intelligence_tuning.html`, and seeding blocked until the model responds.
- Admin buttons (Seeding, Policy-generate, Card-generate, analytics) are disabled with an explanation while the status is not `ready`.
- Update `README.md`, `docs/architecture.md` and the LLM providers table.

### Phase 6 - Tuning and feedback loop

- Log low-confidence and user-corrected routings.
- Fine-tune `laya-typed-decisions` per deployment from those logs plus Phase 4 utterances (Laya ships fine-tuning notebooks).
- Add a Laya panel to `intelligence_tuning.html`: engine status, threshold, recent misroutes.

### Phase 7 - Air-gapped reference profile

Because the admin LLM is mandatory, air-gapped and no-external-service installs need a supported way to meet it.

- Document and test a self-hosted profile: an OpenAI-compatible server (vLLM on GPU, or llama.cpp with a quantised GGUF on CPU) serving Gemma or a similar open-weight model, wired through `ADMIN_LLM_PROVIDER=openai-compatible`.
- Optional compose profile (`--profile airgap`) that adds the model server and pre-wires `ADMIN_LLM_*`.
- Measure whether a CPU-only quantised model is good enough for seeding, Policy SQL and analytics SQL, and record the minimum model size and hardware. If not, the guidance is a GPU box for the admin LLM only, since runtime stays on CPU.
- **Trial and CI environments:** decide between (a) the small self-hosted model above, or (b) a stub OpenAI-compatible server returning canned responses, modelled on `mock/MockServer.java`. A stub is enough for tests and demos but not real seeding; (a) is the answer for real air-gapped use.

### Deferred

Templated analytics (pre-authored, parameterised reports matched by Laya, with SQL bound as parameters) is not needed while analytics runs on the admin LLM. Revisit only if analytics needs a fast cache, or admin-reviewed SQL for sensitive reports. It is no longer a way around the LLM requirement, since the admin LLM is mandatory.

## Risks and open questions

1. **Air-gapped quality.** A self-hosted CPU model may be too weak for reliable SQL generation. Phase 7 measures this; the fallback is a GPU host for the admin LLM only.
2. **Enforcement edge cases.** A model that is reachable but poor (bad SQL, malformed JSON) still shows `ready`. Consider a periodic self-test prompt in the health check, not just a ping.
3. **Python sidecar.** Nexus is a Java WAR; this adds a second runtime and about 1-2GB of image size.
4. **Option-count scaling.** `choice` over hundreds of commands may degrade. Entity resolution stays on `word_similarity()`; commands may need a two-stage shortlist (for example trigram pre-filter, then Laya).
5. **Zero-shot accuracy on domain jargon is unknown.** Phase 0 answers this; Phases 4 and 6 improve it.
6. **Unverified upstream details.** Confirm license, the `/v1/systemone` schema, and `laya-serve` stability directly in the repo before Phase 1.
7. **Naming migration.** Introducing `ADMIN_LLM_*` alongside `LLM_*` needs a clear deprecation note in the README so operators are not confused by two sets of variables.

## Files touched

| File | Change |
|---|---|
| `framework/DecisionEngine.java` | New interface |
| `framework/LayaEngine.java` | New: HTTP client for `laya-serve` |
| `framework/LLMDecisionEngine.java` | New: fallback wrapping `LLMClient` |
| `framework/LLMClient.java` | Read `ADMIN_LLM_*` first, `LLM_*` / `VLLM_*` as fallback |
| `api/Intent.java` | Route and command classification via `DecisionEngine` |
| `api/Analytics.java` | Read admin config; shared admin-LLM guard |
| `api/Seeding.java` | Example utterances (Phase 4) |
| `api/Tuning.java` | Separate admin / runtime status; `admin_llm_status` and startup check |
| `web/admin/` | `intelligence_tuning.html` admin / runtime cards; first-run banner and guided setup |
| `docker-compose.yml`, `Dockerfile` | `laya` service, new env defaults, `airgap` profile |
| `README.md`, `docs/architecture.md` | Docs |
