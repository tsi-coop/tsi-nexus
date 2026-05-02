-- ============================================================================
-- TSI NEXUS: MULTI-SCENARIO MICROFINANCE SEED
-- ============================================================================

-- 1. SEED GROUPS
INSERT INTO digital_twins (type, external_id, current_state) VALUES
('group', 'unity_group', '{"name": "Unity Group", "status": "Active"}'),
('group', 'alpha_group', '{"name": "Alpha Group", "status": "Active"}');

-- 2. SEED MEMBERS (Varying States)
INSERT INTO digital_twins (type, external_id, current_state) VALUES
-- Member A: Clean state, but in a "dirty" group (Anita defaults)
('member', 'satish_01', '{"status": "Active", "kyc": "Verified", "group": "unity_group"}'),
('member', 'anita_02',  '{"status": "NPA", "kyc": "Verified", "group": "unity_group"}'),

-- Member B: In a clean group, but has a personal KYC block
('member', 'rohan_03',  '{"status": "Active", "kyc": "Pending", "group": "alpha_group"}'),

-- Member C: Fully compliant (The "Golden Path")
('member', 'meena_04',  '{"status": "Active", "kyc": "Verified", "group": "alpha_group"}');

-- 3. SEED THE GRAPH (Relationships)
-- This allows the Policy SQL to walk the social connections.
INSERT INTO twin_relationships (from_twin_id, to_twin_id, relationship_type) VALUES
-- Unity Group Members
((SELECT id FROM digital_twins WHERE external_id='satish_01'), (SELECT id FROM digital_twins WHERE external_id='unity_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='anita_02'),  (SELECT id FROM digital_twins WHERE external_id='unity_group'), 'MEMBER_OF'),
-- Alpha Group Members
((SELECT id FROM digital_twins WHERE external_id='rohan_03'),  (SELECT id FROM digital_twins WHERE external_id='alpha_group'), 'MEMBER_OF'),
((SELECT id FROM digital_twins WHERE external_id='meena_04'),  (SELECT id FROM digital_twins WHERE external_id='alpha_group'), 'MEMBER_OF');

-- 4. THE POLICY MANIFEST (Multi-Layer Governance)
-- Note: Policies are applied to ALL 'DISBURSE' actions.

-- Layer 1: The Group Default Guard
INSERT INTO policy_manifest (policy_id, action_type, description, query_logic, error_message)
VALUES (
    'POL-MF-GROUP',
    'DISBURSE',
    'Check Group Contagion',
    'SELECT COUNT(*) FROM digital_twins t 
     JOIN twin_relationships r1 ON t.id = r1.from_twin_id 
     JOIN twin_relationships r2 ON r1.to_twin_id = r2.to_twin_id 
     JOIN digital_twins group_mates ON r2.from_twin_id = group_mates.id 
     WHERE t.external_id = ? AND group_mates.current_state->>''status'' = ''NPA''',
    'Action Blocked: A member of your Joint Liability Group is in default (NPA).'
);

-- Layer 2: The KYC Guard
INSERT INTO policy_manifest (policy_id, action_type, description, query_logic, error_message)
VALUES (
    'POL-MF-KYC',
    'DISBURSE',
    'Check KYC Status',
    'SELECT COUNT(*) FROM digital_twins WHERE external_id = ? AND current_state->>''kyc'' != ''Verified''',
    'Action Blocked: KYC documentation is missing or pending.'
);