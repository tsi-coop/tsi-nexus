-- ============================================================================
-- TSI NEXUS: THE UNIFIED SOVEREIGN CORE (V3 - INSTITUTIONAL UPGRADE)
-- ============================================================================

-- 0. INFRASTRUCTURE 
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 1. THE SOVEREIGN ROOT (Organisation)
-- Every Nexus instance is anchored by a Root Organisation.
CREATE TABLE root_organisation (
    org_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    legal_identifier TEXT UNIQUE,
    domain_slang JSONB DEFAULT '{"Group Leader": "@coordinator"}', -- For Alias Registry
    config JSONB DEFAULT '{
        "emergency_offline_mode": true,
        "global_temperature": 0.2
    }', -- Synced with Intelligence Tuning Safety
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2. THE ENTITY VAULT (Digital Twins)
-- Accommodates Members, Officers, and Branches
CREATE TABLE digital_twins (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    org_id UUID REFERENCES root_organisation(org_id),
    type TEXT NOT NULL,                -- 'member', 'officer', 'branch', 'skill'
    external_id TEXT UNIQUE NOT NULL,  -- The @handle (e.g., @officer_rahul)
    current_state JSONB NOT NULL DEFAULT '{}', 
    version_count INTEGER DEFAULT 1,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 3. THE GRAPH PILLAR (Relationships & Skills)
-- Maps Tribal Knowledge & Skills
CREATE TABLE twin_relationships (
    rel_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    from_twin_id UUID REFERENCES digital_twins(id) ON DELETE CASCADE,
    to_twin_id UUID REFERENCES digital_twins(id) ON DELETE CASCADE,
    relationship_type TEXT NOT NULL,   -- 'SPOUSE_OF', 'MANAGES', 'HAS_SKILL'
    metadata JSONB DEFAULT '{}',       -- Stores "How-to" tribal knowledge
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 4. EXTERNAL SERVICE REGISTRY
-- Manages API Gateways and Health Checks
CREATE TABLE service_registry (
    service_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    identifier TEXT UNIQUE NOT NULL,   -- 'AADHAAR_KYC', 'CIBIL_PROXY'
    api_base_url TEXT NOT NULL,
    auth_config JSONB,                 -- Keys/Secrets
    status TEXT DEFAULT 'Active',      -- 'Active', 'Degraded', 'Offline'
    last_health_check TIMESTAMPTZ,
    uptime_percentage NUMERIC DEFAULT 100.00
);

-- 6. APP ACCESS & RBAC (Sovereign Access Keys)
-- Securely authorizes external apps for DTwin/Stream access
CREATE TABLE app_access_registry (
    app_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    app_name TEXT NOT NULL,
    api_key TEXT UNIQUE NOT NULL,
    api_secret_hash TEXT NOT NULL,
    authorized_scopes TEXT[],         -- ['DTWIN_WRITE', 'STREAM_LOG']
    status TEXT DEFAULT 'Active',
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 7. INTERACTION STREAM (Institutional Memory)
-- The immutable timeline of Tribal Knowledge
CREATE TABLE interaction_stream (
    id BIGSERIAL PRIMARY KEY,
    owner_id UUID REFERENCES digital_twins(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    intent_mapped TEXT,                -- The interpreted command (e.g., /disburse)
    embedding vector(1536),    
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 7b. COMMAND MANIFEST (Intent → Action Routing)
-- Defines every slash-command the system understands; drives Intent resolution and Policy targeting
CREATE TABLE command_manifest (
    command_id    UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    command_verb  TEXT UNIQUE NOT NULL,  -- 'disburse', 'onboard', 'collect'
    label         TEXT NOT NULL,         -- Human-readable: 'Fund Release'
    action_type   TEXT NOT NULL,         -- Uppercase verb used in policy_manifest: 'DISBURSE'
    args_hint     TEXT DEFAULT '',       -- '@target [amount]'
    hint          TEXT DEFAULT '',       -- Short description for LLM prompt
    component_type TEXT DEFAULT 'action', -- UI component hint
    multi_target  BOOLEAN DEFAULT FALSE,
    has_value     BOOLEAN DEFAULT FALSE,
    is_active     BOOLEAN DEFAULT TRUE,
    created_at    TIMESTAMPTZ DEFAULT NOW()
);

-- 7c. INTERACTION SCHEMA (Form-driven Capture)
-- Each row is a schema-driven form; its action_type hooks into policy_manifest
CREATE TABLE interaction_schema (
    schema_id     TEXT PRIMARY KEY,      -- 'KYC_UPDATE', 'FIELD_VISIT'
    label         TEXT NOT NULL,         -- 'KYC Verification Update'
    applies_to    TEXT NOT NULL,         -- Entity type: 'member', 'officer'
    action_type   TEXT NOT NULL,         -- Uppercase: 'KYC_UPDATE'
    fields        JSONB NOT NULL DEFAULT '[]',
    state_patch   JSONB DEFAULT '{}',    -- Keys to merge into current_state on submit
    stream_tmpl   TEXT DEFAULT '',       -- Liquid template for interaction_stream entry
    is_active     BOOLEAN DEFAULT TRUE,
    created_at    TIMESTAMPTZ DEFAULT NOW()
);

-- 8. THE POLICY MANIFEST (Governance Core)
-- The "Business Compiler"
CREATE TABLE policy_manifest (
    policy_id TEXT PRIMARY KEY,
    action_type TEXT NOT NULL,         -- Matches command verbs
    description TEXT DEFAULT '',       -- Human-readable rule intent
    query_logic TEXT NOT NULL,         -- The SQL enforced at runtime
    error_message TEXT NOT NULL,       -- Liquid feedback on denial
    execution_mode TEXT NOT NULL DEFAULT 'GUARDRAIL',
    is_active BOOLEAN DEFAULT TRUE
);

-- 9. ACTION & AUDIT LOG (Time-Travel Auditing)
-- Final ledger of every human intent and result
CREATE TABLE action_audit_log (
    audit_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    actor_id UUID REFERENCES digital_twins(id),
    intent_raw TEXT,                   -- The human's actual words
    action_executed JSONB,             -- The resulting JSON command
    policy_id TEXT REFERENCES policy_manifest(policy_id),
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- 10. SEEDING SESSIONS (Adaptive Bootstrapping)
-- Tracks institutional DNA materialisation history
CREATE TABLE seeding_sessions (
    session_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    industry_context TEXT NOT NULL,
    data_flavor TEXT,
    entity_types JSONB,
    edge_cases_pct INTEGER DEFAULT 5,
    status TEXT DEFAULT 'running',
    twins_seeded INTEGER DEFAULT 0,
    relationships_seeded INTEGER DEFAULT 0,
    interactions_seeded INTEGER DEFAULT 0,
    templates_seeded INTEGER DEFAULT 0,
    forms_seeded INTEGER DEFAULT 0,
    policies_seeded INTEGER DEFAULT 0,
    commands_seeded INTEGER DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ DEFAULT now(),
    completed_at TIMESTAMPTZ
);

-- 10. SYSTEM TRIGGERS & SEEDS
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

-- 11. LIQUID TEMPLATE REGISTRY
CREATE TABLE liquid_templates (
    template_id   UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name          TEXT NOT NULL,
    entity_type   TEXT NOT NULL,
    html_content  TEXT NOT NULL DEFAULT '',
    condition_sql TEXT NOT NULL DEFAULT '',
    is_active     BOOLEAN DEFAULT TRUE,
    created_at    TIMESTAMPTZ DEFAULT NOW(),
    updated_at    TIMESTAMPTZ DEFAULT NOW()
);

-- 12. NEXUS USERS (Admin & Staff Accounts)
CREATE TABLE IF NOT EXISTS nexus_users (
    user_id       UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name          TEXT NOT NULL,
    email         TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'admin',  -- 'admin', 'staff'
    is_active     BOOLEAN DEFAULT TRUE,
    twin_id       UUID UNIQUE REFERENCES digital_twins(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ DEFAULT NOW()
);

-- SEED: Root Organisation & System Actor
INSERT INTO root_organisation (name, config) VALUES (
    'TSI Nexus Global',
    '{
        "emergency_offline_mode": true,
        "global_temperature": 0.2,
        "type_registry": {
            "officer": {
                "attributes": ["name", "email", "role", "branch"],
                "defined_at": "system",
                "system": true
            }
        },
        "relationship_registry": [
            {
                "from_type": "officer",
                "rel_type": "HAS_SYSTEM_ACCESS",
                "to_type": "system"
            }
        ]
    }'
) ON CONFLICT DO NOTHING;
INSERT INTO digital_twins (id, type, external_id, current_state)
VALUES ('00000000-0000-0000-0000-000000000000', 'system', 'governance_node', '{"role":"governance_core"}')
ON CONFLICT DO NOTHING;

