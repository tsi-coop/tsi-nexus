-- Enforce one edge per (from, to, type) (existing databases; init.sql already includes this).
-- Remove duplicates first, keeping one edge (lowest rel_id) per group.
DELETE FROM twin_relationships r
USING twin_relationships k
WHERE r.from_twin_id = k.from_twin_id
  AND r.to_twin_id = k.to_twin_id
  AND r.relationship_type = k.relationship_type
  AND r.rel_id > k.rel_id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_twin_rel
    ON twin_relationships (from_twin_id, to_twin_id, relationship_type);
