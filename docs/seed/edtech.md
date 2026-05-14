# Seed Guide - EdTech Platform

Professional learning platforms run cohort-based programs where outcomes - completion, assessment scores, credentials, job placement - are the institutional currency. Data connects learners to programs through enrollment and progress records, and it flows in two directions: instructors configure and deliver content, learners consume it and are assessed. Corporate clients sponsor cohorts and expect placement and completion reporting. The platform's value lies in turning learning activity into a verified, portable record of demonstrated capability.

---

## Step 1 - Institutional DNA

### Industry Context (required)

```
A digital-first professional upskilling platform offering cohort-based programs in
technology, business, and creative disciplines. Learners enroll in structured programs,
complete live sessions and asynchronous modules, and earn verified credentials on
assessment. Instructors design learning paths and deliver sessions. Corporate clients
sponsor employee cohorts and receive aggregated progress and placement reports.
Outcomes are tracked from enrollment through module completion, assessment, credential
issuance, and job placement or promotion.
```

### Data Flavor & Traits

```
Cohort sizes range from 20 to 200 learners. Progress is tracked as completion
percentage per module and overall program. Assessment scores are recorded per
attempt with pass thresholds and maximum retry limits. Engagement metrics include
session attendance rate, assignment submission rate, and forum participation count.
Corporate cohorts carry sponsor metadata: company name, budget code, HR contact,
and reimbursement eligibility flag. Credentials include a unique verification code,
issue date, and expiry if applicable.
```

---

## Step 2 - Entity Types & Counts

**AI Suggest description:**

```
Learners enroll in Programs through Cohorts. Instructors deliver Programs. Enrollments
track progress through modules and generate Assessment records. Credentials are issued
to Learners on program completion. Corporate Sponsors fund cohorts and receive reporting.
```

Suggested entity types and counts:

| Entity Type | Count | Notes |
|---|---|---|
| `learner` | 40 | Primary entity; holds enrollment and credential records |
| `instructor` | 6 | Program designers and live session facilitators |
| `program` | 6 | Structured course with modules and assessment criteria |
| `cohort` | 10 | Scheduled intake of learners for a program |
| `enrollment` | 40 | Learner-to-cohort link; tracks progress and status |
| `assessment` | 25 | Scored attempts linked to enrollments |

---

## Step 3 - Commands & Guardrails

**AI Suggest description:**

```
Enroll learners into cohorts, record assessment scores, issue credentials to completers,
extend enrollment deadlines for at-risk learners, suspend access for policy violations,
and record job placement outcomes for graduates.
```

Suggested commands:

| Command | Label | Guardrail intent |
|---|---|---|
| `/enroll` | Enroll Learner | Cohort must not exceed maximum capacity; learner must not already be enrolled |
| `/grade` | Record Assessment Score | Assessment must be linked to an active enrollment in the correct program |
| `/certify` | Issue Credential | Learner must have passed all required assessments with scores above the threshold |
| `/extend` | Extend Enrollment Deadline | Extension must not exceed the maximum deferral period set by the program |
| `/suspend` | Suspend Access | Suspension reason must be documented; learner must be notified |
| `/place` | Record Job Placement | Placement can only be recorded for learners who hold a valid credential |

---

## Step 4 - Simulation Parameters

| Setting | Recommended value | Why |
|---|---|---|
| Edge Cases | 12% | At-risk learners, failed assessments, deferred enrollments, expired credentials |
| Digital Twins | on | Learner, instructor, program, cohort, enrollment, assessment records |
| Relationships | on | Learners linked to cohorts, cohorts to programs, enrollments to assessments |
| Interactions | on | Session attendance logs, assignment submissions, assessment attempts, credential events |
| Liquid Templates | on | Learner profile cards with program progress, assessment history, credentials |
| Form Schemas | on | Enrollment, grading, credential issuance, extension request, placement forms |
| Policy Manifest | on | Capacity checks, assessment threshold enforcement, credential eligibility rules |
| Commands | on | Instructor and administrator workflow commands |

