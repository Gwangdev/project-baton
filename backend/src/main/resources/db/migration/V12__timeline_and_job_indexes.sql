-- Two orders that were computed per request on the 900,000-record load data (docs/backend-design/evidence/performance.md).
-- The unfiltered timeline sorted a project's whole record set to return one page; ProjectBaton-DB.dbml already declares
-- this index and V1 left it out. The abandoned-source sweep takes the latest STRUCTURE job per source and the ingestion
-- schedule looks up unfinished INGEST jobs per connection; without an index on kind and target both scanned every job,
-- a table that grows by about 345,000 rows a day from the ingestion schedule alone.
CREATE INDEX work_records_project_occurred_idx ON work_records (project_id, occurred_on DESC, id DESC);
CREATE INDEX jobs_kind_target_created_idx ON jobs (kind, target_id, created_at DESC, id DESC);
