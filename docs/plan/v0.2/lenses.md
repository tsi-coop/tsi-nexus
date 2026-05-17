# Lenses — Data Apps Lite

**Version:** 0.2  
**Status:** Planning  
**Module path:** `web/lenses/`

---

## What is Lenses

TSI Nexus has a core engine (Context Graph, Guardrails, Service Registry)
and two front-ends: the Admin (configuration) and Liquid (the adaptive operator
UI). The gap is purpose-built, workflow-driven micro-apps — a Vendor Portal,
CRM, Asset Tracker — that non-technical staff can deploy without a developer.

**Lenses** closes that gap. A Lens is a focused application assembled from
primitives that already exist in TSI Nexus: Input Manifests (forms), Context
Cards (display templates), and Guardrails (rules). An admin configures a Lens
in the **Studio** by filling in a visual form. Field users open it in the
**Canvas** — a lightweight, mobile-ready HTML player that renders the correct
form and context for wherever an entity sits in its workflow.

### Naming Rationale

| Layer | Name | What it does |
|---|---|---|
| Foundation | **Nexus** | The digital brain — data, graph, intelligence |
| Interface | **Liquid** | The adaptive raw interface — flows to any task |
| Applications | **Lenses** | Focused apps — direct the intelligence at one workflow |

Within the Lenses module:

| Part | Name | Who uses it |
|---|---|---|
| Configuration | **Studio** | Non-technical admin / ops manager |
| Runtime | **Canvas** | Field officers, branch staff, any operator |

---

## Module Structure

```
web/lenses/
  studio/
    index.html     ← list and manage all Lenses
    edit.html      ← create / configure a specific Lens
  canvas/
    index.html     ← field user opens and operates a Lens
```

The admin sidebar (all 12 existing admin pages) gains a **Lenses** nav entry
under the Experience group.

---

## Studio — The App Builder Experience

A non-technical ops manager (Priya) wants to build a Vendor Onboarding app.
She logs into the Admin, clicks **Lenses**, and sees the Studio.

### Step 1 — Identity

She fills in three fields:

- **Lens Name:** Vendor Onboarding
- **Entity Type:** vendor  *(the digital_twins.type this app targets)*
- **Description:** Track vendor applications from submission to approval.

The system auto-generates a slug: `vendor-onboarding`.

### Step 2 — Workflow Stages

This is the heart of the Studio. Priya thinks in pipeline terms:

```
New Application → Documents Verified → Compliance Check → Approved / Rejected
```

For each stage she answers three questions using dropdowns and text inputs:

1. **What do I call this stage?** e.g. "Documents Verified"
2. **What should the officer fill in here?** → picks an Input Manifest
3. **What should they see about the entity?** → picks a Context Card

Then she defines **transition buttons** — what the user can do from this stage
and where it leads. Example: "Mark Verified → Compliance Check" and
"Return to Applicant → New Application."

No SQL, no JSON, no code.

### Step 3 — Publish

She clicks **Publish**. The Studio shows a shareable Canvas URL:

```
/lenses/canvas/index.html?lens=vendor-onboarding
```

She sends it to her field team. The app is live instantly.

---

## Canvas — The Field User Experience

Ravi is a field officer. He opens the Canvas link on his phone browser.

### Mode 1 — Entity List (landing screen)

When no entity is specified in the URL, the Canvas shows a **list of entities**
of the Lens's type (e.g. all vendors), with:

- Current stage shown as a colour badge on each row
- Stage filter tabs (e.g. "All | New Application | Compliance Check")
- Search by name or ID
- Tap any row to open the entity detail

This is the primary entry point for most real-world apps: "Which vendors are
waiting for me?"

URL: `canvas/index.html?lens=vendor-onboarding`

### Mode 2 — Entity Detail

Ravi taps a vendor. The Canvas switches to a two-panel detail view.

**Left panel:**
- Vendor name + entity ID chip
- Stage pipeline — all stages listed vertically, current one highlighted in
  indigo, completed ones marked with a check
- Action buttons for the current stage (the defined transitions)
- Recent activity feed (last 5 interaction stream entries)

**Right panel — two tabs:**
- **Overview:** The Context Card for this stage, rendered with live entity
  data (state fields, external service data, graph links)
- **Action:** The Input Manifest form for this stage (shown when an action
  button requiring input is tapped)

URL: `canvas/index.html?lens=vendor-onboarding&entity=VENDOR_001`

### The Action Flow

Ravi taps **"Mark Verified"**. The right panel switches to the form. He fills
it in and submits. Behind the scenes:

1. `POST /api/capture` validates fields and checks Guardrails
2. If a Guardrail blocks (e.g. "Tax ID not verified"), Ravi sees the plain
   English error message — no action taken
3. If clear: entity state is patched, interaction stream is appended, any
   configured PUSH services fire automatically
4. Stage pipeline updates — the vendor is now in Compliance Check

No Canvas-side guardrail logic. Everything routes through the existing
`Capture.java` engine.

---

## Architecture

### Database: `lens_manifest`

```sql
CREATE TABLE lens_manifest (
    id           SERIAL PRIMARY KEY,
    lens_id      VARCHAR(64) UNIQUE NOT NULL,
    name         VARCHAR(255) NOT NULL,
    description  TEXT,
    entity_type  VARCHAR(100) NOT NULL,
    stages       JSONB NOT NULL DEFAULT '[]',
    nav_commands JSONB NOT NULL DEFAULT '[]',
    status       VARCHAR(20) NOT NULL DEFAULT 'draft',
    created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP NOT NULL DEFAULT NOW()
);
```

Each element of `stages`:

```json
{
  "stage_id":    "compliance",
  "label":       "Compliance Check",
  "state_field": "status",
  "state_match": "COMPLIANCE",
  "schema_id":   "vendor_compliance_form",
  "template_id": "<uuid>",
  "transitions": [
    { "to_stage": "approved", "label": "Approve",  "action_type": "approve_vendor" },
    { "to_stage": "rejected", "label": "Reject",   "action_type": "reject_vendor"  }
  ]
}
```

Stage resolution: the Canvas calls the resolve endpoint, which walks the
stages array and matches `entity.current_state[state_field] === state_match`.
Falls back to `stages[0]` if no match (new / uninitialised entity).

### Backend: `Lenses.java` + `/api/lenses`

| Method | Params | Behaviour |
|---|---|---|
| GET | _(none)_ | List all Lenses (summary) |
| GET | `?id={lens_id}` | Full manifest (for studio editor) |
| GET | `?resolve=true&lens={lens_id}&entity={external_id}` | Runtime resolve: manifest + current stage + form schema |
| POST | `action: upsert` | Create / update a Lens manifest |
| POST | `action: publish` | Set status to live |
| POST | `action: delete` | Hard delete |

Registered in `_processor.tsi`:
```
/api/lenses=org.tsicoop.nexus.api.Lenses
```

The resolve endpoint returns enough for the Canvas to render without
additional round-trips for the stage config. Context card HTML and the
interaction stream are fetched separately via existing `/api/context` and
`/api/stream` endpoints.

### Canvas API calls (runtime)

| Call | Endpoint | Purpose |
|---|---|---|
| Entity list | `GET /api/entities?type={entity_type}` | List view |
| Resolve stage | `GET /api/lenses?resolve=true&...` | Current stage + form |
| Context card | `GET /api/context?id={external_id}` | Rendered HTML |
| Activity feed | `GET /api/stream?owner={external_id}&limit=5` | Recent entries |
| Submit action | `POST /api/capture` | Form submission + guardrails |

### Files Created / Modified

| File | Status |
|---|---|
| `src/.../api/Lenses.java` | New |
| `web/lenses/studio/index.html` | New |
| `web/lenses/studio/edit.html` | New |
| `web/lenses/canvas/index.html` | New |
| `db/lenses.sql` | New (migration doc) |
| `web/WEB-INF/_processor.tsi` | +1 line |
| 12 × `web/admin/*.html` | +1 nav entry each |

---

## Open Design Decisions

These questions will be revisited before implementation of the relevant parts.

### 1. Entity Creation from Canvas
Can a field user register a brand-new entity (e.g. a new vendor) from within
the Canvas? `Capture.java` currently requires the entity to pre-exist.

- **Option A (Lite scope):** No. Entities are created via Admin or seeding.
  Canvas is management-only.
- **Option B:** Add a "New [entity]" button on the list screen that calls a
  create endpoint, then opens the entity detail at stage 0.

### 2. Role Scoping
Should a Lens be visible to any authenticated user, or should the Studio allow
assigning specific roles/users as the audience for a given Lens?

- **Option A (Lite scope):** Any authenticated user can open any Lens Canvas.
- **Option B:** Studio lets the admin assign a role (e.g. `field_officer`) to
  a Lens. Canvas checks the user's role on load.

### 3. Parallel Stages
Should a workflow support parallel stages — two reviews that must both
complete before advancing?

- **Option A (Lite scope):** Linear + branching only. Covers the vast majority
  of small-scale apps.
- **Option B:** Parallel stage support — significantly more complex.

### 4. Stage Filter on Entity List
On the Canvas list screen, should stage filter tabs be shown only for stages
defined in the Lens, or should there be a global "All" view across all stages?

---

## Verification Checklist

1. DB migration runs; `lens_manifest` table exists with correct columns.
2. `GET /api/lenses` returns `[]` on fresh install.
3. Studio: create a Lens with 2 stages linked to existing schema + template.
   Confirm DB row written correctly.
4. `GET /api/lenses?resolve=true&lens={id}&entity={id}` returns
   `current_stage` with matched stage and `form_schema`.
5. Canvas list view: entities of the correct type appear with stage badges.
6. Canvas detail view: context card renders, stage pipeline is accurate.
7. Canvas action: submit form, confirm `interaction_stream` entry added and
   stage pipeline refreshes.
8. Publish: status chip turns emerald, Canvas URL appears in Studio.
9. Mobile: Canvas panels stack vertically on narrow viewports.
