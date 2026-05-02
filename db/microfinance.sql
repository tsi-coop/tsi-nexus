-- ============================================================================
-- TSI NEXUS: UNIFIED OPERATION, ANALYTICS & COMPARISON SEED
-- ============================================================================

-- 1. HIERARCHY: BRANCHES & OFFICERS
INSERT INTO digital_twins (type, external_id, current_state) VALUES
('branch', 'branch_east', '{"name": "East Coast", "region": "Coastal"}'),
('branch', 'branch_west', '{"name": "West Valley", "region": "Inland"}'),
('officer', 'rahul_01', '{"name": "Rahul", "level": "Senior"}'),
('officer', 'sara_02',  '{"name": "Sara", "level": "Junior"}');

-- 2. HIERARCHY: GROUPS
INSERT INTO digital_twins (type, external_id, current_state) VALUES
('group', 'unity_group', '{"name": "Unity JLG"}'),
('group', 'alpha_group', '{"name": "Alpha JLG"}');

-- 3. SOVEREIGN TWINS (MEMBERS)
INSERT INTO digital_twins (type, external_id, current_state) VALUES
('member', 'satish_01', '{"status": "Active", "kyc": "Verified", "disbursed": 5000, "repaid": 4800}'),
('member', 'anita_02',  '{"status": "NPA", "kyc": "Verified", "disbursed": 3000, "repaid": 500}'),
('member', 'rohan_03',  '{"status": "Active", "kyc": "Pending", "disbursed": 4500, "repaid": 4400}'),
('member', 'meena_04',  '{"status": "Active", "kyc": "Verified", "disbursed": 6000, "repaid": 5950}');

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