-- ============================================================================
-- TSI NEXUS: UNIFIED OPERATION, ANALYTICS & COMPARISON SEED
-- ============================================================================

-- 1. HIERARCHY: BRANCHES & OFFICERS
INSERT INTO digital_twins (type, external_id, current_state) VALUES
('branch', 'branch_east', '{"name": "East Coast", "region": "Coastal"}'),
('branch', 'branch_west', '{"name": "West Valley", "region": "Inland"}'),
('officer', 'rahul_01', '{"name": "Rahul Gupta",  "level": "Senior"}'),
('officer', 'sara_02',  '{"name": "Sara Iyer",    "level": "Junior"}');

-- 2. HIERARCHY: GROUPS
INSERT INTO digital_twins (type, external_id, current_state) VALUES
('group', 'unity_group', '{"name": "Unity JLG"}'),
('group', 'alpha_group', '{"name": "Alpha JLG"}');

-- 3. SOVEREIGN TWINS (MEMBERS)
INSERT INTO digital_twins (type, external_id, current_state) VALUES
('member', 'satish_01', '{"name": "Satish Kumar",  "status": "Active", "kyc": "Verified", "disbursed": 5000, "repaid": 4800}'),
('member', 'anita_02',  '{"name": "Anita Desai",   "status": "NPA",    "kyc": "Verified", "disbursed": 3000, "repaid": 500}'),
('member', 'rohan_03',  '{"name": "Rohan Mehta",   "status": "Active", "kyc": "Pending",  "disbursed": 4500, "repaid": 4400}'),
('member', 'meena_04',  '{"name": "Meena Sharma",  "status": "Active", "kyc": "Verified", "disbursed": 6000, "repaid": 5950}');

-- 4. THE GRAPH PILLAR (Relationship Mapping)
INSERT INTO twin_relationships (from_twin_id, to_twin_id, relationship_type) VALUES
-- Officer to Branch
((SELECT id FROM digital_twins WHERE external_id='rahul_01'), (SELECT id FROM digital_twins WHERE external_id='branch_east'), 'WORKS_AT'),
((SELECT id FROM digital_twins WHERE external_id='sara_02'),  (SELECT id FROM digital_twins WHERE external_id='branch_west'), 'WORKS_AT'),
-- Member to Group
((SELECT id FROM digital_twins WHERE external_id='satish_01'), (SELECT id FROM digital_twins WHERE external_id='unity_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='anita_02'),  (SELECT id FROM digital_twins WHERE external_id='unity_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='rohan_03'),  (SELECT id FROM digital_twins WHERE external_id='alpha_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='meena_04'),  (SELECT id FROM digital_twins WHERE external_id='alpha_group'), 'MEMBER_OF'),
-- Member to Officer (Portfolio)
((SELECT id FROM digital_twins WHERE external_id='satish_01'), (SELECT id FROM digital_twins WHERE external_id='rahul_01'), 'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='anita_02'),  (SELECT id FROM digital_twins WHERE external_id='rahul_01'), 'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='rohan_03'),  (SELECT id FROM digital_twins WHERE external_id='sara_02'),  'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='meena_04'),  (SELECT id FROM digital_twins WHERE external_id='sara_02'),  'MANAGED_BY');

-- 5. THE POLICY MANIFEST (Governance & Analytics)

-- ACTION: DISBURSE (KYC Guard)
INSERT INTO policy_manifest (policy_id, action_type, description, query_logic, error_message, execution_mode)
VALUES ('POL-KYC', 'DISBURSE', 'Identity Verification',
        'SELECT COUNT(*) FROM digital_twins WHERE external_id = ? AND current_state->>''kyc'' != ''Verified''',
        'Action Blocked: KYC documentation is missing or pending.',
        'GUARDRAIL');

-- ACTION: DISBURSE (Group NPA Guard)
INSERT INTO policy_manifest (policy_id, action_type, description, query_logic, error_message, execution_mode)
VALUES ('POL-JLG', 'DISBURSE', 'Group Liability Check',
        'SELECT COUNT(*) FROM digital_twins t
         JOIN twin_relationships r1 ON t.id = r1.from_twin_id
         JOIN twin_relationships r2 ON r1.to_twin_id = r2.to_twin_id
         JOIN digital_twins m ON r2.from_twin_id = m.id
         WHERE t.external_id = ? AND m.current_state->>''status'' = ''NPA''',
        'Action Blocked: A member of your Joint Liability Group is in default.',
        'GUARDRAIL');

-- ACTION: ANALYZE (Single Entity Performance)
-- Uses two-hop traversal so branches (branch→officer→member) are resolved correctly.
INSERT INTO policy_manifest (policy_id, action_type, description, query_logic, error_message, execution_mode)
VALUES ('ANALYZE-E', 'ANALYZE', 'Portfolio Quality Drill-down',
        'WITH target AS (
             SELECT id, external_id FROM digital_twins WHERE external_id = ?
         ),
         hop1 AS (
             SELECT DISTINCT CASE WHEN r.from_twin_id = t.id THEN r.to_twin_id ELSE r.from_twin_id END AS node_id
             FROM target t
             JOIN twin_relationships r ON r.from_twin_id = t.id OR r.to_twin_id = t.id
         ),
         hop2 AS (
             SELECT DISTINCT CASE WHEN r.from_twin_id = h.node_id THEN r.to_twin_id ELSE r.from_twin_id END AS node_id
             FROM hop1 h
             JOIN twin_relationships r ON r.from_twin_id = h.node_id OR r.to_twin_id = h.node_id
             WHERE CASE WHEN r.from_twin_id = h.node_id THEN r.to_twin_id ELSE r.from_twin_id END
                   NOT IN (SELECT id FROM target)
         ),
         all_reach AS (SELECT node_id FROM hop1 UNION SELECT node_id FROM hop2)
         SELECT t.external_id,
                SUM((m.current_state->>''repaid'')::int) * 100 / NULLIF(SUM((m.current_state->>''disbursed'')::int), 0) AS recovery_rate,
                COUNT(m.id) FILTER (WHERE m.current_state->>''status'' = ''NPA'') AS npas
         FROM target t, all_reach ar
         JOIN digital_twins m ON m.id = ar.node_id AND m.type = ''member''
         GROUP BY t.external_id',
        'Analysis Complete: Visualizing entity performance.',
        'ANALYTICS');

-- 6. INTERACTION STREAM (Event History per Twin)
INSERT INTO interaction_stream (owner_id, content, created_at) VALUES
-- satish_01: healthy borrower
((SELECT id FROM digital_twins WHERE external_id='satish_01'), 'Loan application submitted for ₹5,000 working capital.', NOW() - INTERVAL '90 days'),
((SELECT id FROM digital_twins WHERE external_id='satish_01'), 'KYC documents verified. Aadhaar and PAN confirmed.', NOW() - INTERVAL '88 days'),
((SELECT id FROM digital_twins WHERE external_id='satish_01'), 'Loan disbursed: ₹5,000 to primary account.', NOW() - INTERVAL '85 days'),
((SELECT id FROM digital_twins WHERE external_id='satish_01'), 'Repayment received: ₹1,600. Ahead of schedule.', NOW() - INTERVAL '55 days'),
((SELECT id FROM digital_twins WHERE external_id='satish_01'), 'Field visit by Rahul. Business in good standing.', NOW() - INTERVAL '40 days'),
((SELECT id FROM digital_twins WHERE external_id='satish_01'), 'Repayment received: ₹1,600. Balance: ₹1,800.', NOW() - INTERVAL '25 days'),
((SELECT id FROM digital_twins WHERE external_id='satish_01'), 'Pre-closure request submitted. Final payment pending.', NOW() - INTERVAL '5 days'),

-- anita_02: NPA case
((SELECT id FROM digital_twins WHERE external_id='anita_02'), 'Loan application submitted for ₹3,000 inventory purchase.', NOW() - INTERVAL '120 days'),
((SELECT id FROM digital_twins WHERE external_id='anita_02'), 'KYC verified. Group co-guarantee accepted.', NOW() - INTERVAL '118 days'),
((SELECT id FROM digital_twins WHERE external_id='anita_02'), 'Loan disbursed: ₹3,000 to primary account.', NOW() - INTERVAL '115 days'),
((SELECT id FROM digital_twins WHERE external_id='anita_02'), 'Repayment received: ₹500. Partial — cited business disruption.', NOW() - INTERVAL '85 days'),
((SELECT id FROM digital_twins WHERE external_id='anita_02'), 'Missed repayment. Follow-up call attempted, no response.', NOW() - INTERVAL '55 days'),
((SELECT id FROM digital_twins WHERE external_id='anita_02'), 'Account flagged NPA. Recovery proceedings initiated.', NOW() - INTERVAL '30 days'),
((SELECT id FROM digital_twins WHERE external_id='anita_02'), 'Field visit by Rahul. Borrower contacted, committed to restructuring.', NOW() - INTERVAL '10 days'),

-- rohan_03: active but KYC pending
((SELECT id FROM digital_twins WHERE external_id='rohan_03'), 'Loan application submitted for ₹4,500 equipment upgrade.', NOW() - INTERVAL '75 days'),
((SELECT id FROM digital_twins WHERE external_id='rohan_03'), 'KYC partially submitted. Aadhaar verified, PAN pending.', NOW() - INTERVAL '73 days'),
((SELECT id FROM digital_twins WHERE external_id='rohan_03'), 'Loan disbursed conditionally pending full KYC completion.', NOW() - INTERVAL '70 days'),
((SELECT id FROM digital_twins WHERE external_id='rohan_03'), 'Repayment received: ₹2,200. Consistent payer.', NOW() - INTERVAL '40 days'),
((SELECT id FROM digital_twins WHERE external_id='rohan_03'), 'Reminder sent: PAN submission overdue.', NOW() - INTERVAL '20 days'),
((SELECT id FROM digital_twins WHERE external_id='rohan_03'), 'Repayment received: ₹2,200. KYC still outstanding.', NOW() - INTERVAL '10 days'),

-- meena_04: top performer
((SELECT id FROM digital_twins WHERE external_id='meena_04'), 'Loan application submitted for ₹6,000 seasonal stock.', NOW() - INTERVAL '60 days'),
((SELECT id FROM digital_twins WHERE external_id='meena_04'), 'KYC fully verified. Documents complete.', NOW() - INTERVAL '59 days'),
((SELECT id FROM digital_twins WHERE external_id='meena_04'), 'Loan disbursed: ₹6,000 to primary account.', NOW() - INTERVAL '55 days'),
((SELECT id FROM digital_twins WHERE external_id='meena_04'), 'Repayment received: ₹3,000. 50% cleared within 30 days.', NOW() - INTERVAL '25 days'),
((SELECT id FROM digital_twins WHERE external_id='meena_04'), 'Repayment received: ₹2,950. Loan near closure. Eligible for top-up.', NOW() - INTERVAL '8 days'),

-- rahul_01: officer activity log
((SELECT id FROM digital_twins WHERE external_id='rahul_01'), 'Monthly portfolio review completed. 1 NPA flagged (anita_02).', NOW() - INTERVAL '30 days'),
((SELECT id FROM digital_twins WHERE external_id='rahul_01'), 'Field visits completed: satish_01 and anita_02. Reports filed.', NOW() - INTERVAL '10 days'),

-- sara_02: officer activity log
((SELECT id FROM digital_twins WHERE external_id='sara_02'), 'Quarterly review: both borrowers current. No exceptions.', NOW() - INTERVAL '15 days'),
((SELECT id FROM digital_twins WHERE external_id='sara_02'), 'meena_04 flagged for top-up loan eligibility.', NOW() - INTERVAL '8 days');

-- ACTION: COMPARE (Benchmarking two Entities)
-- Same two-hop traversal as ANALYZE, carrying root_id so members are aggregated per target.
INSERT INTO policy_manifest (policy_id, action_type, description, query_logic, error_message, execution_mode)
VALUES ('COMPARE-E', 'COMPARE', 'Side-by-side Institutional Benchmarking',
        'WITH target AS (
             SELECT id, external_id FROM digital_twins WHERE external_id IN (?, ?)
         ),
         hop1 AS (
             SELECT DISTINCT t.id AS root_id,
                 CASE WHEN r.from_twin_id = t.id THEN r.to_twin_id ELSE r.from_twin_id END AS node_id
             FROM target t
             JOIN twin_relationships r ON r.from_twin_id = t.id OR r.to_twin_id = t.id
         ),
         hop2 AS (
             SELECT DISTINCT h.root_id,
                 CASE WHEN r.from_twin_id = h.node_id THEN r.to_twin_id ELSE r.from_twin_id END AS node_id
             FROM hop1 h
             JOIN twin_relationships r ON r.from_twin_id = h.node_id OR r.to_twin_id = h.node_id
             WHERE CASE WHEN r.from_twin_id = h.node_id THEN r.to_twin_id ELSE r.from_twin_id END
                   NOT IN (SELECT id FROM target)
         ),
         all_reach AS (SELECT root_id, node_id FROM hop1 UNION SELECT root_id, node_id FROM hop2)
         SELECT t.external_id,
                SUM((m.current_state->>''disbursed'')::int) AS exposure,
                SUM((m.current_state->>''repaid'')::int) * 100 / NULLIF(SUM((m.current_state->>''disbursed'')::int), 0) AS rate
         FROM target t
         JOIN all_reach ar ON t.id = ar.root_id
         JOIN digital_twins m ON m.id = ar.node_id AND m.type = ''member''
         GROUP BY t.external_id',
        'Comparison Complete: Benchmarking institutional nodes.',
        'ANALYTICS');

-- ============================================================================
-- SCALE EXPANSION: Multi-Branch Enterprise Seed
-- Designed to exercise fuzzy search disambiguation (3× Meena, 2× Satish,
-- 2× Anita, unique Ravi / Leela for auto-resolve testing).
-- ============================================================================

-- NEW BRANCHES
INSERT INTO digital_twins (type, external_id, current_state) VALUES
('branch', 'branch_north', '{"name": "Northern Plains", "region": "North"}'),
('branch', 'branch_south', '{"name": "Southern Delta",  "region": "South"}');

-- NEW OFFICERS
INSERT INTO digital_twins (type, external_id, current_state) VALUES
('officer', 'priya_03', '{"name": "Priya Nair",   "level": "Senior"}'),
('officer', 'arun_04',  '{"name": "Arun Sharma",  "level": "Junior"}');

-- NEW GROUPS
INSERT INTO digital_twins (type, external_id, current_state) VALUES
('group', 'sunrise_group', '{"name": "Sunrise JLG"}'),
('group', 'gamma_group',   '{"name": "Gamma JLG"}'),
('group', 'beta_group',    '{"name": "Beta JLG"}'),
('group', 'delta_group',   '{"name": "Delta JLG"}');

-- NEW MEMBERS
-- North portfolio (priya_03): sunrise_group + gamma_group
INSERT INTO digital_twins (type, external_id, current_state) VALUES
('member', 'meena_05',   '{"name": "Meena Verma",     "status": "Active",  "kyc": "Verified", "disbursed": 5500, "repaid": 3000}'),
('member', 'ravi_06',    '{"name": "Ravi Kumar",      "status": "Active",  "kyc": "Verified", "disbursed": 4000, "repaid": 3800}'),
('member', 'lakshmi_07', '{"name": "Lakshmi Devi",    "status": "Active",  "kyc": "Verified", "disbursed": 5000, "repaid": 4900}'),
('member', 'satish_08',  '{"name": "Satish Naidu",    "status": "NPA",     "kyc": "Verified", "disbursed": 3500, "repaid": 900}'),
('member', 'suresh_09',  '{"name": "Suresh Babu",     "status": "Active",  "kyc": "Pending",  "disbursed": 3000, "repaid": 2100}'),
('member', 'kavya_10',   '{"name": "Kavya Shankar",   "status": "Active",  "kyc": "Verified", "disbursed": 6000, "repaid": 5800}');

-- South portfolio (arun_04): beta_group + delta_group
INSERT INTO digital_twins (type, external_id, current_state) VALUES
('member', 'anita_11',   '{"name": "Anita Kumari",    "status": "Active",  "kyc": "Pending",  "disbursed": 2000, "repaid": 1800}'),
('member', 'gopal_12',   '{"name": "Gopal Das",       "status": "Active",  "kyc": "Verified", "disbursed": 4500, "repaid": 4000}'),
('member', 'leela_13',   '{"name": "Leela Bai",       "status": "Active",  "kyc": "Verified", "disbursed": 2500, "repaid": 2400}'),
('member', 'meena_14',   '{"name": "Meena Krishnan",  "status": "Active",  "kyc": "Verified", "disbursed": 4500, "repaid": 4400}'),
('member', 'deepa_15',   '{"name": "Deepa Nair",      "status": "NPA",     "kyc": "Verified", "disbursed": 4000, "repaid": 700}'),
('member', 'priya_16',   '{"name": "Priya Menon",     "status": "Active",  "kyc": "Verified", "disbursed": 3500, "repaid": 3200}');

-- NEW RELATIONSHIPS
INSERT INTO twin_relationships (from_twin_id, to_twin_id, relationship_type) VALUES
-- Officers to Branches
((SELECT id FROM digital_twins WHERE external_id='priya_03'), (SELECT id FROM digital_twins WHERE external_id='branch_north'), 'WORKS_AT'),
((SELECT id FROM digital_twins WHERE external_id='arun_04'),  (SELECT id FROM digital_twins WHERE external_id='branch_south'), 'WORKS_AT'),
-- North: sunrise_group members
((SELECT id FROM digital_twins WHERE external_id='meena_05'),   (SELECT id FROM digital_twins WHERE external_id='sunrise_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='ravi_06'),    (SELECT id FROM digital_twins WHERE external_id='sunrise_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='lakshmi_07'), (SELECT id FROM digital_twins WHERE external_id='sunrise_group'), 'MEMBER_OF'),
-- North: gamma_group members
((SELECT id FROM digital_twins WHERE external_id='satish_08'),  (SELECT id FROM digital_twins WHERE external_id='gamma_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='suresh_09'),  (SELECT id FROM digital_twins WHERE external_id='gamma_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='kavya_10'),   (SELECT id FROM digital_twins WHERE external_id='gamma_group'), 'MEMBER_OF'),
-- South: beta_group members
((SELECT id FROM digital_twins WHERE external_id='anita_11'),   (SELECT id FROM digital_twins WHERE external_id='beta_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='gopal_12'),   (SELECT id FROM digital_twins WHERE external_id='beta_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='leela_13'),   (SELECT id FROM digital_twins WHERE external_id='beta_group'), 'MEMBER_OF'),
-- South: delta_group members
((SELECT id FROM digital_twins WHERE external_id='meena_14'),   (SELECT id FROM digital_twins WHERE external_id='delta_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='deepa_15'),   (SELECT id FROM digital_twins WHERE external_id='delta_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='priya_16'),   (SELECT id FROM digital_twins WHERE external_id='delta_group'), 'MEMBER_OF'),
-- North members → priya_03
((SELECT id FROM digital_twins WHERE external_id='meena_05'),   (SELECT id FROM digital_twins WHERE external_id='priya_03'), 'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='ravi_06'),    (SELECT id FROM digital_twins WHERE external_id='priya_03'), 'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='lakshmi_07'), (SELECT id FROM digital_twins WHERE external_id='priya_03'), 'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='satish_08'),  (SELECT id FROM digital_twins WHERE external_id='priya_03'), 'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='suresh_09'),  (SELECT id FROM digital_twins WHERE external_id='priya_03'), 'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='kavya_10'),   (SELECT id FROM digital_twins WHERE external_id='priya_03'), 'MANAGED_BY'),
-- South members → arun_04
((SELECT id FROM digital_twins WHERE external_id='anita_11'),   (SELECT id FROM digital_twins WHERE external_id='arun_04'), 'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='gopal_12'),   (SELECT id FROM digital_twins WHERE external_id='arun_04'), 'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='leela_13'),   (SELECT id FROM digital_twins WHERE external_id='arun_04'), 'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='meena_14'),   (SELECT id FROM digital_twins WHERE external_id='arun_04'), 'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='deepa_15'),   (SELECT id FROM digital_twins WHERE external_id='arun_04'), 'MANAGED_BY'),
((SELECT id FROM digital_twins WHERE external_id='priya_16'),   (SELECT id FROM digital_twins WHERE external_id='arun_04'), 'MANAGED_BY');

-- INTERACTION STREAMS: New Members
INSERT INTO interaction_stream (owner_id, content, created_at) VALUES
-- meena_05 (Meena Verma — North, mid-cycle)
((SELECT id FROM digital_twins WHERE external_id='meena_05'), 'Loan disbursed: ₹5,500 for seasonal produce stocking.', NOW() - INTERVAL '45 days'),
((SELECT id FROM digital_twins WHERE external_id='meena_05'), 'Repayment received: ₹3,000. Consistent payer.', NOW() - INTERVAL '15 days'),
((SELECT id FROM digital_twins WHERE external_id='meena_05'), 'Field visit by Priya. Business expanding to adjacent market.', NOW() - INTERVAL '5 days'),
-- ravi_06 (unique name — tests auto-resolve)
((SELECT id FROM digital_twins WHERE external_id='ravi_06'), 'KYC verified. Aadhaar and PAN submitted.', NOW() - INTERVAL '60 days'),
((SELECT id FROM digital_twins WHERE external_id='ravi_06'), 'Loan disbursed: ₹4,000 for transport equipment.', NOW() - INTERVAL '55 days'),
((SELECT id FROM digital_twins WHERE external_id='ravi_06'), 'Repayment received: ₹3,800. Near closure, eligible for top-up.', NOW() - INTERVAL '10 days'),
-- satish_08 (Satish Naidu — NPA, 2nd Satish)
((SELECT id FROM digital_twins WHERE external_id='satish_08'), 'Loan disbursed: ₹3,500 for inventory purchase.', NOW() - INTERVAL '90 days'),
((SELECT id FROM digital_twins WHERE external_id='satish_08'), 'Partial repayment: ₹900. Cited crop failure.', NOW() - INTERVAL '60 days'),
((SELECT id FROM digital_twins WHERE external_id='satish_08'), 'Missed repayment. Account flagged NPA.', NOW() - INTERVAL '30 days'),
((SELECT id FROM digital_twins WHERE external_id='satish_08'), 'Field visit by Priya. Borrower requests restructuring.', NOW() - INTERVAL '7 days'),
-- anita_11 (Anita Kumari — KYC pending, 2nd Anita)
((SELECT id FROM digital_twins WHERE external_id='anita_11'), 'Loan conditionally disbursed pending PAN submission.', NOW() - INTERVAL '30 days'),
((SELECT id FROM digital_twins WHERE external_id='anita_11'), 'Repayment received: ₹1,800. KYC still outstanding.', NOW() - INTERVAL '5 days'),
-- leela_13 (unique name — tests auto-resolve)
((SELECT id FROM digital_twins WHERE external_id='leela_13'), 'KYC fully verified. Loan disbursed: ₹2,500.', NOW() - INTERVAL '40 days'),
((SELECT id FROM digital_twins WHERE external_id='leela_13'), 'Repayment received: ₹2,400. Ahead of schedule.', NOW() - INTERVAL '8 days'),
-- meena_14 (Meena Krishnan — 3rd Meena, South)
((SELECT id FROM digital_twins WHERE external_id='meena_14'), 'Loan application submitted. KYC verified.', NOW() - INTERVAL '50 days'),
((SELECT id FROM digital_twins WHERE external_id='meena_14'), 'Loan disbursed: ₹4,500. Trade finance.', NOW() - INTERVAL '45 days'),
((SELECT id FROM digital_twins WHERE external_id='meena_14'), 'Repayment received: ₹4,400. Excellent track record.', NOW() - INTERVAL '10 days'),
-- deepa_15 (NPA — South)
((SELECT id FROM digital_twins WHERE external_id='deepa_15'), 'Loan disbursed: ₹4,000.', NOW() - INTERVAL '100 days'),
((SELECT id FROM digital_twins WHERE external_id='deepa_15'), 'Partial payment: ₹700. Business disruption cited.', NOW() - INTERVAL '70 days'),
((SELECT id FROM digital_twins WHERE external_id='deepa_15'), 'NPA declared. Recovery team engaged.', NOW() - INTERVAL '35 days'),
-- Officer streams
((SELECT id FROM digital_twins WHERE external_id='priya_03'), 'North portfolio review: 1 NPA (satish_08). Recovery plan initiated.', NOW() - INTERVAL '20 days'),
((SELECT id FROM digital_twins WHERE external_id='priya_03'), 'Field visits completed: meena_05, ravi_06. Both current.', NOW() - INTERVAL '5 days'),
((SELECT id FROM digital_twins WHERE external_id='arun_04'),  'South portfolio: 1 NPA (deepa_15). Flagged for review.', NOW() - INTERVAL '15 days'),
((SELECT id FROM digital_twins WHERE external_id='arun_04'),  'meena_14 flagged as top performer. Eligible for enhanced credit limit.', NOW() - INTERVAL '10 days');

-- ============================================================================
-- COMMAND MANIFEST: Available slash commands in this deployment.
-- Drives the LLM system prompt, UI routing, and sidebar display dynamically.
-- ============================================================================

INSERT INTO command_manifest (command_verb, label, args_hint, hint, component_type, action_type, multi_target, has_value) VALUES
  ('analyze',  'Analyze',  '@entity',         'Pull up the full profile and performance details for any entity.',     'universal_action_confirm', 'ANALYZE',  FALSE, FALSE),
  ('compare',  'Compare',  '@entity @entity', 'Put two entities side by side. AI explains the differences.',          'universal_action_confirm', 'COMPARE',  TRUE,  FALSE),
  ('disburse', 'Disburse', '@entity amount',  'Initiate a governed transaction. Compliance rules run automatically.', 'universal_action_confirm', 'DISBURSE', FALSE, TRUE),
  ('capture',  'Capture',  '@entity',         'Record an interaction — a form, survey, or field visit.',              'interaction_capture_form',  'CAPTURE',  FALSE, FALSE);

-- ============================================================================
-- INTERACTION SCHEMAS: Generic Capture Registry
-- Each row defines a self-contained interaction form.
-- Adding a new interaction type (survey, doc-upload, field visit …) is a
-- plain INSERT here — no Java changes required.
-- ============================================================================

INSERT INTO interaction_schema (schema_id, label, applies_to, action_type, fields, state_patch, stream_tmpl)
VALUES (
  'KYC_MEMBER_CAPTURE',
  'KYC Document Verification',
  'member',
  'CAPTURE_KYC',
  '[
    {"key":"photo_id_type","label":"Photo ID Type","type":"select","options":["Aadhaar","PAN","Voter ID","Passport","Driving License"],"required":true,"hint":"Primary government-issued ID submitted by the member"},
    {"key":"id_number_last4","label":"ID Last 4 Digits","type":"text","required":true,"pattern":"^[0-9]{4}$","hint":"Last 4 digits of the ID number only","state_key":"kyc_id_last4"},
    {"key":"verified_by","label":"Verified By (Officer)","type":"text","required":true,"hint":"Full name of the field officer who verified the document"},
    {"key":"verification_date","label":"Verification Date","type":"date","required":true,"state_key":"kyc_date"},
    {"key":"notes","label":"Observations","type":"textarea","required":false,"hint":"Optional — document condition, discrepancies, or field remarks","state_transform":"omit"}
  ]',
  '{"kyc":"Verified"}',
  'KYC verified. {photo_id_type} (last 4: {id_number_last4}) confirmed by {verified_by} on {verification_date}.'
);

-- Governance: prevent re-capture when KYC is already Verified
-- Set is_active = FALSE to allow re-verification in deployments that need it.
INSERT INTO policy_manifest (policy_id, action_type, description, query_logic, error_message, execution_mode)
VALUES (
  'POL-KYC-DUP',
  'CAPTURE_KYC',
  'Idempotency guard — block if member is already KYC verified',
  'SELECT COUNT(*) FROM digital_twins WHERE external_id = ? AND current_state->>''kyc'' = ''Verified''',
  'Action Blocked: KYC is already verified for this member.',
  'GUARDRAIL'
);

INSERT INTO interaction_schema (schema_id, label, applies_to, action_type, fields, state_patch, stream_tmpl)
VALUES (
  'HOUSEHOLD_SURVEY_CAPTURE',
  'Household Survey',
  'member',
  'CAPTURE_HOUSEHOLD',
  '[
    {"key":"family_size","label":"Number of Family Members","type":"number","required":true,"pattern":"^[1-9][0-9]?$","hint":"Total people living in the household"},
    {"key":"income_source","label":"Primary Income Source","type":"select","options":["Agriculture","Trade / Business","Wage Labour","Salaried Employment","Other"],"required":true},
    {"key":"income_range","label":"Monthly Household Income","type":"select","options":["Below ₹5,000","₹5,000 – ₹10,000","₹10,000 – ₹20,000","Above ₹20,000"],"required":true},
    {"key":"housing_type","label":"Housing Type","type":"select","options":["Owned","Rented","Shared / Joint family"],"required":true},
    {"key":"has_bank_account","label":"Bank Account","type":"select","options":["Yes","No"],"required":true},
    {"key":"notes","label":"Field Officer Notes","type":"textarea","required":false,"state_transform":"omit"}
  ]',
  '{"household_survey":"Completed"}',
  'Household survey completed. Family of {family_size}. Primary income: {income_source}. Monthly income: {income_range}. Housing: {housing_type}.'
);