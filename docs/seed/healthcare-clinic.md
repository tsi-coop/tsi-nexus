# Seed Guide - Healthcare Clinic

Outpatient clinics manage high volumes of patient journeys that span months to years. Data is a mix of hard clinical facts - vitals, lab results, imaging - and subjective context from patient history and symptom narratives. Every institutional action, from a prescription to a referral, must be grounded in current clinical context and comply with medical guardrails around drug interactions, insurance authorisation, and care pathway rules.

---

## Step 1 - Institutional DNA

### Industry Context (required)

```
A multi-specialty outpatient clinic managing patient journeys across general medicine,
pediatrics, and chronic disease management. Clinical teams - physicians, nurses, and
administrative staff - coordinate consultations, diagnostic orders, prescriptions, and
specialist referrals. Patient records are longitudinal, tracking health from first visit
through ongoing care. Insurance pre-authorisation governs referrals and high-cost
procedures. Compliance with clinical documentation standards is mandatory for billing
and audit purposes.
```

### Data Flavor & Traits

```
Vitals captured at every encounter: blood pressure, temperature, weight, SpO2. Lab
results are linked to orders with reference ranges and out-of-range flags. Prescriptions
include drug name, dosage, frequency, duration, and substitution allowed flag. ICD-10
diagnosis codes used throughout. Clinical notes follow SOAP format. Insurance records
carry policy number, provider name, coverage limits, and pre-auth status. Patient
consent is recorded for procedures and data sharing.
```

---

## Step 2 - Entity Types & Counts

**AI Suggest description:**

```
Patients attend Encounters led by Providers. Encounters generate Orders for labs or
imaging, Diagnoses recorded against ICD codes, and clinical Notes. Patients are linked
to Insurance records. Orders produce Results that feed back into the encounter record.
```

Suggested entity types and counts:

| Entity Type | Count | Notes |
|---|---|---|
| `patient` | 30 | Primary entity; longitudinal record holder |
| `provider` | 8 | Physicians and nurses with specialty tags |
| `encounter` | 50 | Consultations; each patient has multiple |
| `order` | 40 | Lab, imaging, or procedure orders per encounter |
| `diagnosis` | 30 | ICD-10 coded diagnoses linked to encounters |
| `insurance` | 20 | Coverage records linked to patients |

---

## Step 3 - Commands & Guardrails

**AI Suggest description:**

```
Register patient encounters, issue prescriptions, place diagnostic orders, record
diagnoses, refer to specialists with insurance pre-authorisation, close encounters
with discharge summaries, and flag overdue follow-ups.
```

Suggested commands:

| Command | Label | Guardrail intent |
|---|---|---|
| `/admit` | Register Encounter | Patient must not have an open encounter already in progress |
| `/prescribe` | Issue Prescription | Drug must not conflict with active prescriptions on the patient's record |
| `/lab_order` | Place Lab Order | Patient must have an open encounter to attach the order |
| `/refer` | Specialist Referral | Insurance pre-authorisation must be on file for the referral category |
| `/discharge` | Close Encounter | All open orders must have results recorded before discharge |
| `/follow_up` | Schedule Follow-up | Follow-up interval must comply with the clinical pathway for the primary diagnosis |

---

## Step 4 - Simulation Parameters

| Setting | Recommended value | Why |
|---|---|---|
| Edge Cases | 10% | Pending results, expired insurance, overdue follow-ups - realistic but not dominant |
| Digital Twins | on | Patient, provider, encounter, order, diagnosis, insurance records |
| Relationships | on | Patients linked to encounters, encounters to orders and diagnoses |
| Interactions | on | Consultation notes, lab result entries, prescription logs |
| Liquid Templates | on | Patient profile cards with active diagnoses, open orders, insurance status |
| Form Schemas | on | Encounter registration, prescription entry, lab order, referral forms |
| Policy Manifest | on | Drug conflict checks, pre-auth requirements, discharge criteria |
| Commands | on | Clinical workflow commands for providers and administrators |

