-- ============================================================================
-- TSI NEXUS: THE UNIFIED SOVEREIGN CORE
-- Version: 2.1 (Decoupled & Generic)
-- ============================================================================

-- 0. INFRASTRUCTURE
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 1. UNIVERSAL TYPES
-- We use standard TEXT for types to allow "Vertical Collapse" without ENUM migrations
-- However, we keep a check constraint for core system roles.
CREATE TYPE policy_outcome AS ENUM ('allow', 'block', 'require_dual_auth', 'flag');

-- 2. THE ENTITY VAULT (Digital Twins)
CREATE TABLE digital_twins (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    type TEXT NOT NULL,                -- e.g., 'member', 'machine', 'officer'
    external_id TEXT UNIQUE NOT NULL,  -- The @handle used in Liquid
    current_state JSONB NOT NULL DEFAULT '{}', 
    version_count INTEGER DEFAULT 1,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. THE GRAPH PILLAR (Relationships)
-- This was missing from the previous version; essential for "Graph Walks"
CREATE TABLE twin_relationships (
    rel_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    from_twin_id UUID REFERENCES digital_twins(id) ON DELETE CASCADE,
    to_twin_id UUID REFERENCES digital_twins(id) ON DELETE CASCADE,
    relationship_type TEXT NOT NULL,   -- e.g., 'SPOUSE_OF', 'MANAGES', 'OPERATES'
    metadata JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 4. THE TEMPORAL ANCESTRY (History)
CREATE TABLE twin_state_history (
    history_id BIGSERIAL PRIMARY KEY,
    twin_id UUID REFERENCES digital_twins(id) ON DELETE CASCADE,
    snapshot JSONB NOT NULL,
    reason TEXT,               
    actor_id UUID,             
    recorded_at TIMESTAMPTZ DEFAULT NOW()
);

-- 5. SEMANTIC INTERACTION STREAM (Memory)
CREATE TABLE interaction_stream (
    id BIGSERIAL PRIMARY KEY,
    owner_id UUID REFERENCES digital_twins(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    embedding vector(1536),    
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 6. THE POLICY MANIFEST (Governance)
-- This is the "Business Compiler" table.
CREATE TABLE policy_manifest (
    policy_id TEXT PRIMARY KEY,
    action_type TEXT NOT NULL,         -- Matches the /command verb (e.g., DISBURSE)
    description TEXT,
    query_logic TEXT NOT NULL,         -- The SQL executed at runtime
    error_message TEXT NOT NULL,       -- Liquid feedback string (denial reason or analytics label)
    execution_mode TEXT NOT NULL DEFAULT 'GUARDRAIL'
        CHECK (execution_mode IN ('GUARDRAIL', 'ANALYTICS')),
                                       -- GUARDRAIL: blocks if COUNT > 0; ANALYTICS: returns rows as data
    is_active BOOLEAN DEFAULT TRUE,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 7. ACTION & AUDIT LOG
CREATE TABLE action_audit_log (
    audit_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    actor_id UUID REFERENCES digital_twins(id),
    intent_raw TEXT,           
    action_executed JSONB,     
    policy_id TEXT REFERENCES policy_manifest(policy_id),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 8. AUTOMATION: LINEAGE TRIGGER
CREATE OR REPLACE FUNCTION fn_nexus_track_lineage()
RETURNS TRIGGER AS $$
BEGIN
    IF (OLD.current_state IS DISTINCT FROM NEW.current_state) THEN
        INSERT INTO twin_state_history (twin_id, snapshot, reason)
        VALUES (OLD.id, OLD.current_state, 'State Mutation');
        NEW.version_count := OLD.version_count + 1;
        NEW.updated_at := NOW();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_nexus_lineage
BEFORE UPDATE ON digital_twins
FOR EACH ROW EXECUTE FUNCTION fn_nexus_track_lineage();

-- 9. FUZZY SEARCH INDEX
-- Enables word_similarity() for name-based entity lookup at lakh scale
CREATE INDEX IF NOT EXISTS idx_twins_name_trgm
    ON digital_twins USING gin((current_state->>'name') gin_trgm_ops);

-- 10. SYSTEM SEED (Essential only)
INSERT INTO digital_twins (id, type, external_id, current_state) VALUES
('00000000-0000-0000-0000-000000000000', 'system', 'system_actor', '{"role":"governance_core"}')
ON CONFLICT DO NOTHING;