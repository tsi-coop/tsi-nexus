-- Soft delete for digital twins (existing databases; init.sql already includes this).
ALTER TABLE digital_twins ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'active';
ALTER TABLE digital_twins ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;
