-- Policies can bind request params (form_data / governance params) into their SQL.
ALTER TABLE policy_manifest ADD COLUMN IF NOT EXISTS param_keys JSONB NOT NULL DEFAULT '[]'::jsonb;
