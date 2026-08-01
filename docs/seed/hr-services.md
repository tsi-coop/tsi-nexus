# Seed Guide - HR Services & Staffing Enterprise

HR services, staffing, and recruitment enterprises are high-velocity, relationship-driven organizations powered by talent supply chains, compliance frameworks, and client SLAs. Talent is sourced, assessed, and placed into permanent roles, contract positions, or executive leadership. Revenue flows through placement fees, hourly/monthly billing margins, and RPO milestone retainers. Operational efficiency is measured by time-to-fill, candidate conversion rates, statutory compliance accuracy, and recruiter productivity. Data connects clients to job requisitions, requisitions to candidates, candidates to placements, and placements to statutory compliance checks and invoices. Guardrails govern statutory verification (PF/ESI/KYC), background checks, SLA compliance, and margin caps before candidate onboarding.

---

## Step 1 - Institutional DNA

### Industry Context (required)

```
An enterprise HR services and staffing organization delivering Permanent Staffing,
Flexible/Contract Staffing, Executive Search, RPO, and HR Tech solutions (in-house talent
assessment platform, upskilling). Clients range from mid-market firms to large enterprises across
group companies and sectors. Requisitions are fee-per-placement, contract margin-based, or
RPO retainer-backed. Recruiters source from a centralized talent pool, run assessments,
verify statutory compliance, and manage the pipeline. Revenue is recognized on candidate
onboarding, contract monthly billing, or RPO milestone achievements. Time-to-fill,
placement margin %, statutory compliance rate, and candidate retention are primary
operational metrics. Background verification (BGV) and labor compliance checks are
mandated before onboarding execution.
```

### Data Flavor & Traits

```
Job Requisitions carry engagement type (permanent, contract, executive_search, rpo), client
reference code, target SLA, bill rate, and pay range. Candidate profiles list skill matrix,
experience level, assessment scores (from the internal talent assessment platform), and statutory IDs (PAN, Aadhaar, UAN,
PF status). Placements track start/end dates, agreed margins, client SLA deadlines, and
candidate status. Compliance records log background verification (BGV), state labor laws,
and document approval states. Invoices reference candidate placements or RPO milestone IDs,
including service fees, statutory contributions, and GST. Candidate and client feedback
scores are captured post-placement on a 1-5 scale with qualitative field notes.
```

---

## Step 2 - Entity Types & Counts

**AI Suggest description:**

```
Clients issue Job Requisitions across Permanent, Flexible, Executive Search, and RPO lines.
Candidates apply or are sourced into Requisitions, undergo Assessment & Skill Mapping via HR
Tech platforms, and are routed into Placements. Placements are governed by Compliance Checks
and trigger Invoices based on placement completion or monthly contract billing.
```

Suggested entity types and counts:

| Entity Type | Count | Notes |
|---|---|---|
| `client_account` | 15 | Enterprise client organizations across sectors and group companies |
| `job_requisition` | 30 | Open requisitions categorized by service line (perm, flex, exec, RPO) |
| `candidate` | 50 | Talent pool profiles with assessment tags and statutory details |
| `placement` | 35 | Active candidate-to-client placements (permanent hire or contract deployment) |
| `compliance_check` | 35 | Statutory checks (PF/ESI/KYC) and background verification (BGV) records |
| `invoice` | 25 | Billing documents linked to placement milestones or monthly contract timesheets |

---

## Step 3 - Commands & Guardrails

**AI Suggest description:**

```
Match candidates to open job requisitions, run skill and assessment evaluations via
the HR Tech assessment platform, execute statutory and background checks, issue placement offers, raise
client invoices upon candidate onboarding or milestone sign-off, and capture candidate and
client feedback post-placement.
```

Suggested commands:

| Command | Label | Guardrail intent |
|---|---|---|
| `/match_candidate` | Match Candidate | Candidate skill tags and experience level must match the job requisition parameters |
| `/run_assessment` | Trigger HR Tech Assessment | Candidate must complete the HR Tech assessment platform evaluation before shortlist flag is set |
| `/verify_compliance` | Verify BGV & Statutory Docs | Candidate must pass background checks and statutory ID verification (PF/ESI/KYC) |
| `/generate_offer` | Issue Placement Offer | Candidate must have approved compliance checks and fall within requisition pay margins |
| `/raise_invoice` | Generate Invoice | Invoice can only be raised against a placement with confirmed onboarding or approved timesheet |
| `/close_requisition` | Close Job Requisition | Requisition target count must be fulfilled and all placed candidates onboarded |

---

## Step 4 - Simulation Parameters

| Setting | Recommended value | Why |
|---|---|---|
| Edge Cases | 10% | Candidate dropouts, failed BGV checks, overdue client SLA deadlines, unapproved statutory docs |
| Digital Twins | on | Client, job requisition, candidate, placement, compliance check, invoice records |
| Relationships | on | Clients linked to requisitions, requisitions to candidates via placements, placements to compliance and invoices |
| Interactions | on | Candidate interview logs, assessment scores, BGV verification events, invoice generation logs |
| Liquid Templates | on | Candidate profile cards, requisition pipeline views, compliance status summaries, placement margin cards |
| Form Schemas | on | Requisition creation, candidate matching, assessment execution, compliance verification, invoicing forms |
| Policy Manifest | on | Statutory labor checks, margin cap validation, BGV gating for placement offers |
| Commands | on | Recruiter, account manager, and compliance officer workflow commands |
