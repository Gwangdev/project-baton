-- Retention (config/RetentionSchedule) deletes expired rows 5,000 at a time and repeats until a batch comes back short.
-- Each batch looked its rows up again: finished jobs through the (status, available_at) index, which still reads every
-- SUCCEEDED row to test updated_at, and idempotency results with a sequential scan. With job retention on, the
-- ingestion schedule alone keeps about 2.4 million succeeded jobs inside the seven-day window, and each batch runs under
-- the worker pool's 30-second statement timeout. Both lookups now read only expired rows.
-- The indexes are partial, so unfinished jobs and in-progress requests, which retention never deletes, add nothing to
-- them.
CREATE INDEX jobs_finished_updated_idx ON jobs (status, updated_at) WHERE status IN ('SUCCEEDED', 'FAILED');
CREATE INDEX idempotency_requests_completed_expires_idx ON idempotency_requests (expires_at) WHERE state = 'COMPLETED';
