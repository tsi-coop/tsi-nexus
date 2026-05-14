# Seed Guide - Microfinance Institution

Microfinance institutions replace traditional collateral with social collateral. Small borrower groups co-guarantee each other's loans, and field officers verify repayment reliability through high-frequency center meetings and on-site physical checks. The model is relational and geospatial by nature - individual creditworthiness is inseparable from group behavior and field-verified facts.

---

## Step 1 - Institutional DNA

### Industry Context (required)

```
A rural microfinance cooperative operating through the Joint Liability Group (JLG) model.
Field officers conduct weekly Center Meetings in villages across multiple districts, collecting
small repayments aligned with micro-entrepreneur cash flows. Borrowers begin with small starter
loans and qualify for progressive lending cycles by proving repayment reliability. Physical
collateral verification - livestock inspection, Patta Chitta land documentation - is a core
field activity. Social collateral from group peer-guarantees substitutes for formal credit scores.
Approximately 5,000 active members organised into groups of 5, each group attached to a
geographically clustered Center.
```

### Data Flavor & Traits

```
Loan sizes range from 10,000 to 50,000 in local currency. Officers use district codes and
local names. Field notes are empathetic - capturing life events (medical emergencies, crop
loss) that explain missed meetings. Repayment cycles are weekly or monthly. GPS coordinates
and photo timestamps are attached to asset verifications. Group attendance percentage and
consecutive on-time repayments are the primary risk signals, not credit bureau scores.
```

---

## Step 2 - Entity Types & Counts

**AI Suggest description:**

```
Members organised into JLGs, JLGs clustered into Centers supervised by Field Officers
attached to Branch Offices. Each member holds one or more Loan Accounts and may have
registered Collateral Assets (livestock, land).
```

Suggested entity types and counts:

| Entity Type | Count | Notes |
|---|---|---|
| `member` | 40 | Individual borrowers; primary entity |
| `group` | 8 | JLG of 5 members; unit of social collateral |
| `center` | 4 | Cluster of 2 groups; meeting venue |
| `officer` | 4 | Field officers who run center meetings |
| `loan_account` | 40 | One active loan per member on average |
| `collateral_asset` | 20 | Livestock or land assets linked to members |

---

## Step 3 - Commands & Guardrails

**AI Suggest description:**

```
Disburse loans to approved members, collect weekly or monthly repayments, verify physical
collateral at member location, onboard new members into a group, reschedule loans for
members with verified life events, and log center meeting attendance.
```

Suggested commands:

| Command | Label | Guardrail intent |
|---|---|---|
| `/disburse` | Disburse Loan | Group must have 100% attendance in last 4 meetings |
| `/collect` | Record Repayment | Loan account must be active and not fully settled |
| `/verify_asset` | Verify Collateral Asset | GPS coordinates must be within 50m of member's registered address |
| `/onboard` | Onboard Member | Group must be below maximum membership cap |
| `/reschedule` | Reschedule Loan | Requires supervisor override; life event must be documented |
| `/center_meeting` | Log Center Meeting | Meeting date must not duplicate an existing entry for the same center |

---

## Step 4 - Simulation Parameters

| Setting | Recommended value | Why |
|---|---|---|
| Edge Cases | 15% | Reflect realistic delinquency - overdue loans, missed meetings, flagged collateral |
| Digital Twins | on | Core data - member, group, center, officer, loan, asset records |
| Relationships | on | Critical - members linked to groups, groups to centers, officers to branches |
| Interactions | on | Center meeting logs, repayment receipts, asset verification notes |
| Liquid Templates | on | Member profile cards showing loan status, group membership, repayment history |
| Form Schemas | on | Repayment capture, asset verification, member onboarding forms |
| Policy Manifest | on | Guardrails for disbursement eligibility, rescheduling approval |
| Commands | on | Slash commands for field officer workflows |

