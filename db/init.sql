-- ============================================================================
-- TSI NEXUS: THE UNIFIED SOVEREIGN CORE
-- Version: 2.0 (Collapsed Nexus + Liquid)
-- Target: PostgreSQL 16+ | Extensions: pgvector, Apache AGE
-- ============================================================================

-- 0. INFRASTRUCTURE SETUP
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;
-- Note: Ensure Apache AGE is installed at the OS level before enabling
-- CREATE EXTENSION IF NOT EXISTS age;

-- 1. CORE DOMAIN TYPES
CREATE TYPE entity_type AS ENUM ('machine', 'officer', 'merchant', 'system', 'process');
CREATE TYPE policy_outcome AS ENUM ('allow', 'block', 'require_dual_auth', 'flag');

-- 2. THE ENTITY VAULT (Digital Twins)
-- The "Source of Truth" for every participant in the institution.
CREATE TABLE digital_twins (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    type entity_type NOT NULL,
    external_id TEXT UNIQUE, -- Hardware ID, Employee Code, or KYC ID
    current_state JSONB NOT NULL DEFAULT '{}', -- Live attributes
    ui_preference JSONB DEFAULT '{}',          -- Liquid's adaptive layout state
    version_count INTEGER DEFAULT 1,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. THE TEMPORAL ANCESTRY (State History)
-- Ensures "Institutional Memory" by never deleting a state change.
CREATE TABLE twin_state_history (
    history_id BIGSERIAL PRIMARY KEY,
    twin_id UUID REFERENCES digital_twins(id) ON DELETE CASCADE,
    snapshot JSONB NOT NULL,
    reason TEXT,               -- Intent captured via Liquid
    actor_id UUID,             -- Who/What triggered the change
    recorded_at TIMESTAMPTZ DEFAULT NOW()
);

-- 4. SEMANTIC INTERACTION STREAM (Memory Pillar)
-- Stores vectorized intent from Command-K, Voice, and Logs.
CREATE TABLE interaction_stream (
    id BIGSERIAL PRIMARY KEY,
    owner_id UUID REFERENCES digital_twins(id),
    content TEXT NOT NULL,
    embedding vector(1536),    -- Optimized for Gemma 4 context
    context_tags JSONB,        -- Auto-tagged by Nexus (e.g. #high-risk)
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 5. THE POLICY GUARD (Governance Pillar)
-- Deterministic laws that the Nexus Reasoning Engine cannot bypass.
CREATE TABLE policy_guard_rules (
    rule_id TEXT PRIMARY KEY,
    description TEXT,
    condition_logic TEXT,      -- Logic for the python orchestrator
    outcome policy_outcome DEFAULT 'allow',
    is_active BOOLEAN DEFAULT TRUE,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 6. LIQUID ACTION & AUDIT LOG (The "Black Box")
-- Records the "Collapse" of Intent and Action for legal defense.
CREATE TABLE action_audit_log (
    audit_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    actor_id UUID REFERENCES digital_twins(id),
    intent_raw TEXT,           -- What the user typed in Liquid
    action_executed JSONB,     -- The resulting system command
    state_hash TEXT,           -- Fingerprint of Nexus state at execution
    policy_id TEXT REFERENCES policy_guard_rules(rule_id),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 7. SOVEREIGN PII VAULT (Side-car Security)
-- Separated from the reasoning engine for privacy compliance.
CREATE TABLE pii_vault (
    twin_id UUID PRIMARY KEY REFERENCES digital_twins(id),
    full_name_enc TEXT,        -- Encrypted PII
    contact_info_enc TEXT,
    national_id_masked TEXT,
    last_accessed TIMESTAMPTZ
);

-- 8. AUTOMATION: AUTOMATIC LINEAGE TRACKING
-- Every update to a Twin automatically spawns a History record.
CREATE OR REPLACE FUNCTION fn_nexus_track_lineage()
RETURNS TRIGGER AS $$
BEGIN
    IF (OLD.current_state IS DISTINCT FROM NEW.current_state) THEN
        INSERT INTO twin_state_history (twin_id, snapshot, reason)
        VALUES (OLD.id, OLD.current_state, 'Nexus State Synchronization');
        NEW.version_count := OLD.version_count + 1;
        NEW.updated_at := NOW();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_nexus_lineage
BEFORE UPDATE ON digital_twins
FOR EACH ROW EXECUTE FUNCTION fn_nexus_track_lineage();

-- 9. PERFORMANCE INDEXING
CREATE INDEX idx_interaction_search ON interaction_stream USING ivfflat (embedding vector_cosine_ops);
CREATE INDEX idx_twin_history_lookup ON twin_state_history(twin_id, recorded_at);
CREATE INDEX idx_audit_intent_search ON action_audit_log USING gin(action_executed);

-- 10. GRAPH INITIALIZATION (Optional Placeholder)
-- SELECT create_graph('tsi_nexus_graph');

-- 11. SEED DATA (Development)
INSERT INTO digital_twins (id, type, external_id, current_state) VALUES
    -- System actor used as the default actor_id from Liquid UI
    ('00000000-0000-0000-0000-000000000000', 'system',  'system_actor', '{"role":"system"}'),
    (uuid_generate_v4(),                     'officer', 'satish',       '{"status":"active","role":"admin"}'),
    (uuid_generate_v4(),                     'machine', 'machine01',    '{"status":"active","location":"floor-1"}')
ON CONFLICT (external_id) DO NOTHING;