# Seed Guide - Manufacturing Firm

Small manufacturing firms operate as job shops - taking custom orders, sourcing materials against a Bill of Materials, running jobs on machines, and delivering quality-verified finished goods. The data is grounded in physical facts and timing: what is being made, on which machine, by whom, with what materials, and whether it passed inspection. Material traceability and on-time delivery are the metrics that determine operational health.

---

## Step 1 - Institutional DNA

### Industry Context (required)

```
A small custom-parts manufacturing firm producing precision components for industrial
clients. Operates a job-shop floor with CNC machines, fabrication stations, and quality
inspection bays. Work Orders are raised against client purchase orders, staffed by
certified machine operators, and executed against Bills of Materials. Raw material
consumption is traced by batch and supplier lot. Every finished batch passes a quality
inspection before dispatch. Turnaround speed and zero-defect delivery are the primary
competitive differentials.
```

### Data Flavor & Traits

```
Work orders carry client reference numbers, target quantities, material specs, and
deadline dates. Machine states cycle through: idle, running, maintenance, down. Raw
material stock is tracked by batch number and supplier lot. Operators hold certifications
for specific machine categories - CNC, welding, press. Quality inspections use pass,
fail, and conditional verdicts with defect codes and rework notes. Time logs capture
setup time, run time, and downtime per job in minutes. Skill notes from experienced
operators are attached to machine records as institutional knowledge.
```

---

## Step 2 - Entity Types & Counts

**AI Suggest description:**

```
Work Orders define what is to be made. Machines execute the work, assigned to Operators
who hold machine-specific certifications. Raw Materials are consumed from stock against
the Bill of Materials. Finished Goods are produced and linked to an Inspection record
before dispatch to the client.
```

Suggested entity types and counts:

| Entity Type | Count | Notes |
|---|---|---|
| `work_order` | 20 | Core operational entity; one per client job |
| `machine` | 10 | CNC, fabrication, or inspection equipment |
| `raw_material` | 15 | Stock items with batch and lot traceability |
| `operator` | 8 | Certified floor staff assigned to work orders |
| `inspection` | 20 | Quality checks on finished batches |
| `finished_good` | 15 | Completed parts awaiting dispatch |

---

## Step 3 - Commands & Guardrails

**AI Suggest description:**

```
Start work orders after verifying material stock, log material consumption against
the BOM, record quality inspections on finished batches, report machine downtime,
complete and dispatch finished orders, and trigger reorders when raw material stock
falls below threshold.
```

Suggested commands:

| Command | Label | Guardrail intent |
|---|---|---|
| `/start_job` | Start Work Order | Required raw materials must be in stock above minimum quantity |
| `/log_material` | Record Material Use | Consumed quantity must not exceed the BOM allocation for the work order |
| `/inspect` | Record Quality Inspection | Inspection must be linked to a work order in completed state |
| `/report_downtime` | Report Machine Downtime | Machine must be in running state to log a downtime event |
| `/complete_order` | Complete Work Order | All inspection records must show pass or conditional before dispatch |
| `/reorder` | Trigger Material Reorder | Stock level must be at or below the defined reorder threshold |

---

## Step 4 - Simulation Parameters

| Setting | Recommended value | Why |
|---|---|---|
| Edge Cases | 20% | Manufacturing produces natural exceptions - overdue jobs, machines down, failed inspections, low stock |
| Digital Twins | on | Work order, machine, material, operator, inspection, finished good records |
| Relationships | on | Work orders linked to machines, operators, materials, and inspections |
| Interactions | on | Job start/end logs, material consumption entries, inspection reports, downtime events |
| Liquid Templates | on | Work order progress cards, machine status views, material stock levels |
| Form Schemas | on | Job start, material use, inspection, downtime, and completion forms |
| Policy Manifest | on | Stock sufficiency checks, BOM compliance, inspection gating for dispatch |
| Commands | on | Floor-level commands for operators and supervisors |

