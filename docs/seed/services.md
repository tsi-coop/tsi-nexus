# Seed Guide - Professional Services Firm

Professional services firms - consulting, advisory, legal, accounting - are relationship businesses driven by project economics. Consultants are pulled from a shared resource pool and staffed to client engagements. Revenue flows through milestones. Utilisation is the core efficiency metric. Data connects clients to projects, projects to consultants, and consultants to time entries and deliverables. Guardrails govern staffing conflicts, billing accuracy, and milestone sign-off before invoicing.

---

## Step 1 - Institutional DNA

### Industry Context (required)

```
A boutique professional services firm delivering management consulting, compliance
advisory, and technology implementation engagements to mid-market clients. Projects
are fixed-price or time-and-materials. Consultants are drawn from a shared pool and
assigned to engagements based on skills and availability. Client relationships span
multiple engagements over years. Revenue is recognised at project milestones, which
trigger invoice generation. Consultant utilisation rate and on-time milestone delivery
are the primary operational metrics. Conflict-of-interest checks are performed before
staffing decisions.
```

### Data Flavor & Traits

```
Projects carry billing type (fixed-price or T&M), client reference code, estimated
hours, and contracted value. Consultant profiles list skill tags (strategy, finance,
IT, legal, operations) and grade (analyst, consultant, manager, director). Time entries
are logged in 15-minute increments against project codes. Milestones have due dates,
deliverable descriptions, and client sign-off status. Invoices reference milestone IDs
and include line items, tax, and payment terms. Client satisfaction scores are captured
at project closure on a 1-5 scale with qualitative comments.
```

---

## Step 2 - Entity Types & Counts

**AI Suggest description:**

```
Clients commission Projects which are broken into Milestones. Consultants are assigned
to Projects through Engagements and log time entries. Milestones trigger Invoices when
signed off by the client. Client satisfaction is recorded at project closure.
```

Suggested entity types and counts:

| Entity Type | Count | Notes |
|---|---|---|
| `client` | 12 | Organisations; relationship holder across projects |
| `project` | 20 | Scoped engagement with billing type and contracted value |
| `consultant` | 12 | Billable staff with skill tags and utilisation tracking |
| `engagement` | 20 | Consultant-to-project assignment with role and billing rate |
| `milestone` | 30 | Deliverable checkpoints that gate invoice generation |
| `invoice` | 20 | Billing documents linked to completed milestones |

---

## Step 3 - Commands & Guardrails

**AI Suggest description:**

```
Staff consultants to projects after conflict checks, log billable time against project
codes, approve project milestones after client sign-off, generate invoices on milestone
approval, close completed engagements, and record client satisfaction scores at project
closure.
```

Suggested commands:

| Command | Label | Guardrail intent |
|---|---|---|
| `/staff` | Assign Consultant | Consultant must not have a conflict-of-interest flag with the client |
| `/log_time` | Record Time Entry | Time entry must reference an active project code with budget remaining |
| `/approve_milestone` | Approve Milestone | Client sign-off document must be recorded before milestone is marked complete |
| `/raise_invoice` | Generate Invoice | Invoice can only be raised against a milestone in approved state |
| `/close_project` | Close Project | All milestones must be in approved state and all invoices in paid state |
| `/review` | Record Client Satisfaction | Satisfaction score can only be submitted after the project is closed |

---

## Step 4 - Simulation Parameters

| Setting | Recommended value | Why |
|---|---|---|
| Edge Cases | 10% | Stalled projects, overdue invoices, over-utilised consultants, at-risk client relationships |
| Digital Twins | on | Client, project, consultant, engagement, milestone, invoice records |
| Relationships | on | Clients linked to projects, projects to consultants via engagements, milestones to invoices |
| Interactions | on | Time entry logs, milestone approvals, invoice events, client satisfaction records |
| Liquid Templates | on | Project status cards, consultant utilisation views, client relationship summaries |
| Form Schemas | on | Staffing, time entry, milestone approval, invoice, closure, satisfaction forms |
| Policy Manifest | on | Conflict-of-interest checks, budget caps, milestone gating for invoices |
| Commands | on | Project manager and engagement lead workflow commands |

