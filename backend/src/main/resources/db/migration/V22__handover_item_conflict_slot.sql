-- A record can be the source of two conflict relations (one new schedule replacing two old ones, or replacing one and
-- conflicting with another). All conflict rows went on the record's one item and settling accepts only an item with
-- exactly one conflict, so such a package could never be approved; a second item for the record was impossible, because a
-- generation held one item per kind and record. Each conflict now gets its own item: slot 0 is the record's item and the
-- package builder adds a copy at slot 1, 2, ... for each further pair.
ALTER TABLE handover_items ADD COLUMN conflict_slot smallint NOT NULL DEFAULT 0;
ALTER TABLE handover_items DROP CONSTRAINT handover_items_generation_kind_record_key;
ALTER TABLE handover_items ADD CONSTRAINT handover_items_generation_kind_record_key
    UNIQUE (generation_id, item_kind, work_record_id, conflict_slot);
