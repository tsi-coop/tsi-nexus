# TSI Nexus as "Jarvis" — A Cross-Vertical Use-Case Playbook

Status: **thinking-through doc.** Generalizes the reasoning already applied and
decided for Agri Hero (see the Agri Hero backend architecture proposal) into a
repeatable pattern, then stress-tests that pattern against a very different
use case — a KNX smart-home assistant — to check where it holds and where it
doesn't.

## 1. The positioning

"Jarvis" is a good name for what Nexus already is, once you strip the
microfinance-specific language off it: a private, natural-language-operable
brain that (a) knows every entity and relationship in your world, (b) can be
told to do things but only within rules you've set, (c) remembers everything
that happened, and (d) can be wired to whatever external systems — APIs,
webhooks, sensors, buses — actually do the work in the physical/digital
world. That description doesn't mention microfinance, agriculture, or
buildings. It's the platform.

The Agri Hero decision already proved the important part: you don't extend
Nexus by writing sector code into it. You extend it by (1) adding DB rows —
entity types, forms, policies — and (2) writing a small, disposable **edge
service** that owns the messy channel-specific stuff (WhatsApp, Sarvam,
Meta media downloads) and talks to Nexus over its existing headless API. The
core stays the sovereign, boring, generic "brain." Every new "Jarvis for X"
is a test of whether that split still holds. This doc runs that test against
a smart home.

## 2. The reusable recipe

Any new use case decomposes onto the same six pillars. This table is the
checklist to run for *any* new vertical, not just the two below:

| Question to ask | Nexus mechanism |
|---|---|
| What are the "things" in this world? | `digital_twins` — one entity type per kind of thing |
| How do things relate to each other? | `twin_relationships` — graph edges, queried via `/api/graph` |
| How does Nexus learn a thing's current status from the outside world? | **PULL** (poll-on-read) or **INGEST** (external system pushes on change) — see §3 for how to choose |
| How does a user (or automation) submit a piece of information or request an action? | **Capture** (`interaction_schema` + `POST /api/capture`) — schema-driven form, state patch, stream entry |
| What must never be allowed, or must be reported on? | `policy_manifest` — `GUARDRAIL` (blocks) or `ANALYTICS` (reports) |
| How does Nexus tell the outside world to actually *do* something? | **PUSH** — `service_registry` row fired after a Capture commits |
| What's the audit trail? | `interaction_stream` — already generic, needs nothing new |
| What does the user type/say to trigger all this? | `command_manifest` + `Intent.java` NL resolution, or a channel-specific edge service calling the same API |

If a use case can't be described using only this table, that's the signal a
genuine platform gap exists (see §7) — not a reason to write vertical logic
into Java.

## 3. PULL vs. INGEST vs. PUSH — the choice that actually varies per vertical

This is the one place the "recipe" isn't mechanical — it depends on how the
external system communicates:

- **PULL** fits systems that are *queried on demand* and have no notion of
  pushing changes (a credit bureau API, a mandi price API). Nexus asks, the
  system answers, `Context.java` caches it into `live_data` for that page
  load.
- **INGEST** fits systems that *emit events when something changes*, whether
  on a timer or on a real-world trigger. Nexus doesn't ask; it's told, and
  `current_state` is patched in near-real-time.
- **PUSH** is the only outbound direction: Nexus telling the world to act,
  after a guardrail-checked Capture commits.

Agri Hero is PULL/INGEST-light and Capture/PUSH-heavy: prices and PO
generation are user-initiated actions, not continuous physical signals.
Smart home flips that ratio, which is the interesting part of §5.

## 4. Worked example A — Agri Hero (recap)

Already decided; included here only to show the pattern in its "home"
vertical before stretching it:

| Agri Hero concept | Nexus mechanism |
|---|---|
| Broker / supplier / buyer | `digital_twins`, one type per role |
| Trade relationships | `twin_relationships` |
| Price broadcast | Capture (`interaction_schema` + `state_patch`) |
| PO generation | Capture + `GUARDRAIL` (blocks if no accepted supplier response) |
| PDF generation | PUSH → existing Puppeteer microservice |
| Stakeholder reporting | `ANALYTICS` policy / report-style SQL |
| WhatsApp + Sarvam voice | Separate FastAPI edge service calling Nexus's headless API — **not** Java |

## 5. Worked example B — a KNX smart-home Jarvis

KNX is a decades-old field-bus standard for building automation: lights,
blinds/shutters, HVAC, door/window contacts, presence sensors, and switches
all sit on a shared bus and exchange "telegrams" addressed by **group
address (GA)** — e.g. `1/1/4` might mean "living room ceiling light." You
integrate with it through a KNX/IP interface plus a small daemon (`knxd`,
Calimero, or a vendor gateway) that exposes telegrams as an API/MQTT stream
rather than raw bus frames. That daemon is the natural home for the edge
service, exactly like the FastAPI layer in Agri Hero.

### 5.1 Entity model
Each physical device (a light, a blind actuator, a thermostat, a door
sensor) is a `digital_twins` row, typed by device class (`light`, `blind`,
`thermostat`, `contact_sensor`, `switch`). A device's `current_state` JSONB
carries both automation state (`on`, `brightness`, `position_pct`,
`setpoint_c`) and its KNX addressing (`cmd_ga`, `status_ga`) — addressing is
just config data on the twin, same as any other field.

### 5.2 Topology as relationships
Rooms and floors are twins too (`room`, `floor`), and `twin_relationships`
encodes `located_in` edges (light → room → floor). This is exactly the
broker↔supplier graph in Agri Hero, just a physical hierarchy instead of a
trade one. `/api/graph` answers "what's in the living room?" the same way
it answers "which suppliers does this broker deal with?"

### 5.3 State sync is INGEST-first, not PULL-first
This is the real difference from Agri Hero. KNX devices don't wait to be
asked — the bus broadcasts a telegram the instant a physical switch is
pressed or a sensor fires. The KNX bridge service should push every state
change to `POST /api/ingest` as it happens, patching `current_state` in
near-real-time and appending a stream entry ("Living room light turned on
via wall switch"). PULL still has a role — a one-time reconciliation read
when a twin is first created, or to recover from a missed telegram — but it
is not the primary sync path here the way it is for Agri Hero's price
lookups. Recognizing "this vertical is push-heavy" is exactly the kind of
per-vertical judgment call §3 flags.

### 5.4 Commands go out through Capture + PUSH
"Turn the living room lights to 30%" is a Capture: an `interaction_schema`
(`type: light`, fields: `brightness`) submitted via `/api/capture`,
guardrail-checked, state-patched, and streamed — then a PUSH service
(`trigger_action: SET_BRIGHTNESS`) fires a webhook to the KNX bridge, which
translates it into a telegram on `cmd_ga`. Nexus never speaks KNX; the
bridge does, exactly the way the Puppeteer service is the only thing that
knows about PDFs in Agri Hero.

### 5.5 Guardrails carry the safety logic
Physical-world guardrails matter more here than in most verticals — this is
where "Jarvis" earns trust:
- Block `SET_TEMPERATURE` below a floor (`GUARDRAIL`, e.g. 16°C)
- Block `OPEN_GARAGE_DOOR` while `away_mode = true` unless voice-print/PIN
  confirmed
- Block `UNLOCK_DOOR` after a configurable hour without a second factor
- `ANALYTICS` policies compute energy usage trends by room/device for a
  dashboard — same mechanism as Agri Hero's price-trend reporting

All of this is SQL rows, zero Java, exactly the constraint TSI Nexus already
holds itself to.

### 5.6 Scenes — the one genuine gap
"Good night" (dim living room to 0, lock the door, set the thermostat to
18°C, arm the sensors) touches multiple twins in one user intent. Capture as
it stands is one schema → one state patch → one twin. Two ways to handle
this, in order of how much core change they need:

1. **Edge-side fan-out (no core change, ship this first).** The voice/chat
   edge service resolves "good night" to N separate `POST /api/capture`
   calls, one per twin, sequentially or in parallel — the same trust
   boundary Agri Hero already uses when its FastAPI service fans a
   broadcast out to multiple suppliers via a `/api/graph` query.
2. **A generic "composite capture" primitive (a real platform feature,
   later).** Model a scene as its own twin type whose `current_state` is an
   ordered list of `{target_external_id, action_type, patch}` steps, with
   one `RUN_SCENE` action type that applies them atomically inside a single
   transaction. This isn't smart-home-specific — Agri Hero would want the
   same thing for "accept offer → generate PO → notify supplier" as one
   atomic unit instead of three round trips. Worth designing as a
   cross-vertical primitive if a second use case asks for it, not as a KNX
   special case.

Start with (1). It costs nothing and proves out whether atomic multi-twin
actions are actually needed before building (2).

### 5.7 Voice front-end reuses the Agri Hero shape exactly
A wake-word device, phone app, or Sarvam-based regional-language pipeline
does STT + intent extraction and calls Nexus's headless API
(`X-API-Key`/`X-API-Secret`) — `GET /api/context` to know what's in the
room, `POST /api/capture` to act, `GET /api/graph` to resolve "the
bedroom lights" to a twin list. It is registered as an app via the existing
`POST /api/apikeys` flow with scoped permissions, same as any Agri Hero
integrator. Nexus's Java core needs nothing new to support this channel —
which is the whole point of the split decided in Agri Hero.

### 5.8 Summary table

| Smart-home concept | Nexus mechanism |
|---|---|
| Light / blind / thermostat / sensor | `digital_twins`, one type per device class |
| Room / floor hierarchy | `twin_relationships` |
| KNX telegram → Nexus state | **INGEST** (primary), PULL (reconciliation only) |
| Voice/app command → device | Capture (`interaction_schema`) |
| Nexus → KNX telegram | **PUSH** → KNX bridge daemon |
| Safety limits, access control | `policy_manifest` GUARDRAIL |
| Energy dashboards | `policy_manifest` ANALYTICS |
| Activity/security timeline | `interaction_stream` (as-is) |
| "Good night" / multi-device scenes | Edge-side fan-out now; composite-capture primitive if this recurs |
| Voice pipeline (STT + NLU) | Separate edge service, headless API, `POST /api/apikeys` — KNX/protocol code never enters Nexus core |

## 6. Cross-vertical comparison — the proof the recipe is generic

| Pillar | Microfinance (shipped) | Agri Hero (decided) | Smart home (this doc) |
|---|---|---|---|
| Twins | Member, loan | Broker, supplier, buyer | Light, blind, thermostat, room |
| Relationships | Member↔group | Broker↔supplier | Device↔room↔floor |
| Primary inbound sync | INGEST (credit bureau) | Capture (user-submitted) | INGEST (bus telegrams) |
| Primary outbound action | Capture (KYC, disbursement) | Capture + PUSH (PO/PDF) | Capture + PUSH (KNX telegram) |
| Guardrail example | Block disbursement if KYC unverified | Block PO if no accepted response | Block unlock after hours w/o 2FA |
| Edge channel | None yet (in-app) | FastAPI + WhatsApp + Sarvam | Voice device/app + KNX bridge |

Three verticals, zero shared Java beyond the platform itself. That's the
argument for "Jarvis" as the positioning — the same six mechanisms keep
absorbing genuinely different domains without the core growing.

## 7. Platform gaps this exercise actually surfaced

Worth tracking as real (generic) feature candidates, not vertical hacks:

1. **Composite/atomic multi-twin actions** (§5.6) — came up for smart-home
   scenes; would also simplify Agri Hero's accept→PO→notify sequence.
   Defer until a second use case asks for it.
2. **Time/schedule-triggered evaluation** — smart-home guardrails like "if
   no motion for 30 minutes, turn off lights" aren't triggered by an
   inbound Capture or INGEST event, they're triggered by *the absence* of
   one over time. Today's `ANALYTICS` mode runs after the fact on read;
   there's no cron-style "evaluate this policy every N minutes and act"
   primitive. Also generic — any vertical with SLA/timeout rules
   (microfinance overdue-payment nudges, Agri Hero stale-broadcast expiry)
   would use the same thing.

Neither blocks shipping a KNX pilot with today's Nexus — §5.6 has a
zero-core-change workaround, and simple timers can live in the edge bridge
for now — but both are worth designing properly the next time two verticals
independently ask for them.

## 8. Packaging channel integrations — connector repos, not core code

A natural next question: should Nexus ship ready-made integrations for
WhatsApp, Sarvam, etc., so every new deployment doesn't rebuild an edge
service from scratch?

**Recommendation: keep the integration code out of the Nexus core WAR, but
maintain official connector templates as separate, versioned repos.** Each
connector is a small standalone edge service (the same shape as Agri Hero's
FastAPI service) that talks to Nexus only through the existing headless API
(`/api/context`, `/api/capture`, `/api/ingest`, scoped via
`POST /api/apikeys`). This gives adopters "out of the box" convenience
without folding channel code into the core.

**Why not bundle it into core instead:** doing so reintroduces exactly the
risk the Agri Hero split was designed to avoid — an internet-facing webhook
and third-party SDKs (WhatsApp Cloud API, Sarvam) living in the same process
as the institutional brain; core release cycles coupled to unrelated
vendors' API changes; and an implicit bias toward whichever two channels got
bundled over whatever the next vertical actually needs (Telegram, Alexa,
SMS, a KNX bridge). The connector-repo approach gets nearly all the
convenience — a customer can clone and configure a template instead of
writing one from scratch — while keeping blast radius and genericity intact.

**How to apply:** when Agri Hero's FastAPI service (or a future Sarvam/voice
bridge for the smart-home use case) is built, structure it so it could be
lifted out as `nexus-connectors/whatsapp-sarvam` or similar — schema fetched
from Nexus at startup/cache time (not hardcoded), auth via a scoped API key,
no Nexus-specific business logic baked in beyond what the deploying
institution configured via DB rows. Treat the first one or two connectors
built this way as the template for all future ones, rather than one-off
throwaway code.

## 9. Intelligence layer model choice — when self-hosting Gemma isn't viable

A concrete instance of §2's "Intelligence Tuning" pillar (`root_organisation`
LLM setting) coming up for a specific prospect: no budget/infra to self-host
Gemma for this deployment. Two things this forced apart, worth keeping
separate going forward:

- **Voice/text (ASR/TTS, channel-specific speech handling) is never part of
  the intelligence layer.** It's edge-service territory, same as Sarvam in
  Agri Hero (§4, §5.7) — Intent.java/Policy.java/Intelligence.java only ever
  see text, regardless of which channel or vendor produced it. The dilemma
  of "should voice be part of the intelligence layer" resolves to no: it
  would reintroduce channel logic into the core, which §8 already argues
  against.
- **Which text LLM powers Intent/Policy/Intelligence is just the existing
  per-institution config knob**, not an architecture decision. Swapping
  models is a `root_organisation` setting change, not a code change.

**Decision for this prospect:** default to **Gemini (Flash tier)** as the
hosted alternative to self-hosted Gemma — cheapest hosted option for a
cost-constrained pilot, and same model lineage as Gemma so existing
`domain_slang`-injected prompts need minimal rework if the deployment later
moves to self-hosted Gemma. Fall back to **OpenAI (GPT-4o-mini/4.1-mini)**
specifically if Gemini's structured-output reliability (JSON/function-calling)
proves insufficient for Policy.java's SQL generation or Intent.java's
classification — that's the one place model choice actually affects
correctness, not just cost.

## 10. Playbook: onboarding the next "Jarvis for X"

1. Run the entity types, relationships, forms, and policies through the
   §2 checklist before writing anything.
2. Use the seeder (`Seeding.java`, 8-step pipeline) against an
   `industry_context` description to stand up a first-draft configuration —
   twins, relationships, Context Cards, Input Manifests, commands,
   policies — for demoing before any real integration exists.
3. Decide PULL vs. INGEST vs. PUSH per external system using §3 — don't
   default to PULL out of habit.
4. Write one small edge service per channel (chat/voice/protocol bridge).
   It owns all channel-specific complexity and talks to Nexus only through
   the existing headless API (`/api/context`, `/api/capture`,
   `/api/governance`, `/api/graph`, `/api/ingest`), registered via
   `POST /api/apikeys` with the minimal scopes it needs.
5. Add guardrails for anything that must never happen, as SQL rows, before
   go-live — not as an afterthought.
6. If the use case needs atomic multi-entity actions or time-triggered
   automation, start with the edge-side workaround (§7) and only propose a
   core change if a second vertical needs the same thing.

## 11. Takeaway

Agri Hero already answered the hard question — Nexus stays generic, edge
services absorb the mess, and the six pillars are enough to model an
entirely different industry. Running that same test against a smart home,
a domain with no trading, no brokers, and a push-heavy physical bus instead
of a request/response API, comes out the same way: no new Java, one small
bridge service, a handful of DB rows. That's a reasonable bar for "Jarvis"
as a platform positioning — the pitch isn't "an app for agriculture" or "an
app for buildings," it's "bring your entities, your rules, and one small
adapter, and Nexus is your institution's (or your home's) brain."

## 12. Blog draft — advisory framing, product reveal at the end

*Draft, simple-words pass, no em dashes. Reframed from a product
announcement into an advisory piece: state the shift, lay out the
requirements a system needs to meet, use the worked examples (§4-§6) as
illustrations, and only name TSI Nexus and the Apache 2 license in the
closing lines.*

---

### Your Business Needs Its Own Jarvis

Satish Ayyaswami
[date]

AI is changing how people use software. Typing into forms and clicking
through menus is giving way to just asking, in plain language, and getting
things done. That shift is already here, and it's moving fast.

This changes what we should expect from the systems we run. An
organisation can't just bolt a chat window onto its existing software and
call it done. If people are going to ask for things in plain language, the
system underneath has to actually understand the business, enforce its
rules, and connect to everything that already runs it. Most systems today
aren't built for that.

So what does a system actually need, to hold up under this shift?

**1. A connected view of the business, not scattered files.** Every
person, asset, branch, and relationship between them needs to live in one
place, not spread across a CRM, a spreadsheet, and someone's memory.
Without this, plain language questions have nothing solid to stand on.

**2. Intelligence that can be tuned, not a generic model bolted on.**
Every organisation has its own terms, job titles, and shorthand. A system
needs to learn that vocabulary and let the organisation pick the model
that fits its needs and budget, rather than forcing one model and one
vocabulary on everyone.

**3. An interface that adapts to intent.** Plain language in, the right
answer, form, or confirmation out, in the right place, at the right
moment. Not a fixed menu tree.

**4. Rules that are checked before anything happens, not after.** A
request phrased in plain language still has to be turned into a specific,
approved action, and that action has to be checked against the
organisation's own rules before it runs, not audited after the fact.

**5. A memory that keeps everything.** Every action, big or small, needs
to be captured as it happens. Otherwise the knowledge that made the system
useful walks out the door with whoever holds it in their head.

**6. Reasoning beyond simple lookups.** Not every question is "show me X."
People will ask why something happened, or ask for a comparison, and the
system needs to reason its way to an answer, not just retrieve a record.

**7. Secure, two-way connections to what already exists.** Credit
bureaus, legacy CRMs, WhatsApp, a smart home bus, whatever an organisation
already runs. A new system has to plug into that, pulling data in and
pushing actions out, without asking anyone to rip out what already works.

We're already testing these seven requirements against a few very
different settings.

**A microfinance officer.** She types "disburse the loan for member
4521" instead of clicking through six screens. The system checks whether
that member has cleared KYC before letting the disbursement go through. No
loan moves without passing the institution's own rules.

**An agricultural broker.** He sends a voice note in Hindi over WhatsApp,
asking for today's price. The system understands it, looks up the price,
and replies on the same thread, in the same language. Before a purchase
order is generated, it checks that a valid supplier response actually
exists.

**A homeowner.** She says "good night" once. The lights dim, the door
locks, the thermostat drops, the sensors arm themselves. Try opening the
garage at 2am while away mode is set, and the system blocks it, because
that's the rule she set, not an assumption baked into a gadget.

Different industries, same seven requirements underneath.

We've attempted to build exactly this. It's called TSI Nexus, and we've
open sourced it under the Apache 2 license.

---
