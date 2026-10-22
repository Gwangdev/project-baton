-- A record relation could only be created: the reverse supersession is a cycle, a superseded record cannot be superseded
-- again and a confirmed record cannot change, so a supersession registered by mistake held for good and a handover could
-- be approved only on the record the PM believed wrong. A PM now withdraws a relation with a reason. The row stays, so
-- the history and the audit keep what was claimed and by whom; every check reads only the relations not withdrawn.
ALTER TABLE record_relations
    ADD COLUMN withdrawn_at timestamptz,
    ADD COLUMN withdrawn_by bigint REFERENCES users(id),
    ADD COLUMN withdrawal_reason varchar(300),
    ADD CONSTRAINT record_relations_withdrawal_complete CHECK (
        (withdrawn_at IS NULL) = (withdrawn_by IS NULL) AND (withdrawn_at IS NULL) = (withdrawal_reason IS NULL));

-- The same pair may be registered again once the earlier relation is withdrawn, so both uniqueness rules cover only the
-- relations still in force.
ALTER TABLE record_relations DROP CONSTRAINT record_relations_record_relation_key;
CREATE UNIQUE INDEX record_relations_record_relation_key
    ON record_relations(from_record_id, to_record_id, relation_type) WHERE withdrawn_at IS NULL;
DROP INDEX record_relations_one_conflict_per_source_key;
CREATE UNIQUE INDEX record_relations_one_conflict_per_source_key
    ON record_relations(project_id, from_record_id) WHERE relation_type = 'CONFLICTS_WITH' AND withdrawn_at IS NULL;
