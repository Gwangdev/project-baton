-- The timeline total and the pending counts on the dashboard and the project detail decide, for every record of a
-- project, whether it is confirmed or a draft the actor may review, and the second test needs record_source_id. The
-- status index held every other column those counts read, so each count still visited the table: about 33,000 buffers
-- for one total on a large project of the 900,000-record load data, and at 60 RPS those reads outside shared_buffers
-- set the response time (docs/backend-design/evidence/performance.md). With record_source_id included, the counts
-- are index-only scans (1,199 buffers for the same total, measured on a trial copy of the index).
--
-- Flyway runs before the application accepts requests, so the table lock taken while the index is built (seconds on
-- the load data) blocks nothing. The new index is built first so the table never lacks one.
CREATE INDEX work_records_project_status_occurred_include_idx
    ON work_records (project_id, trust_status, occurred_on DESC, id DESC) INCLUDE (record_source_id);
DROP INDEX work_records_project_status_occurred_idx;
ALTER INDEX work_records_project_status_occurred_include_idx RENAME TO work_records_project_status_occurred_idx;
