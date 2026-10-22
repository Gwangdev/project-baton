-- The note list (listPersonalNotes) joins work_records on source_note_id to show which records a note was shared
-- into. ProjectBaton-DB.dbml declares this index, but V1 left it out, so with 900,000 records every note list scanned
-- the whole table (about 1 s per request; EXPLAIN in docs/backend-design/evidence/performance.md). It is partial
-- because only records shared from a note carry the column.
CREATE INDEX work_records_source_note_id_idx ON work_records (source_note_id) WHERE source_note_id IS NOT NULL;
