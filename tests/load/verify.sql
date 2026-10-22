-- tests/load/baton.js의 실행 뒤 데이터 확인: :'started'(UTC, run.sh가 준다)에 시작한 실행에 대해 DB가 가진 것을
-- summary.json의 계수와 비교한다. 줄마다 불변식 하나다.
\set ON_ERROR_STOP on
\pset footer off

\echo 'writes recorded in the database since the run started'
select
  (select count(*) from personal_notes where created_at >= :'started' and code not like 'NOTE-L%') as notes_created,
  (select count(*) from work_records where confirmed_at >= :'started' and source_note_id is null) as reviews_confirmed,
  (select count(*) from work_records where trust_status = 'DISMISSED' and updated_at >= :'started') as reviews_dismissed,
  (select count(*) from work_records where source_note_id is not null and created_at >= :'started') as summaries_shared,
  (select count(*) from audit_events where action = 'UPDATE_USER' and occurred_at >= :'started') as users_updated;

\echo 'duplicates: a note body is unique per virtual-user iteration, a shared note yields one record'
select
  (select count(*) from (select author_id, body from personal_notes where created_at >= :'started' and code not like 'NOTE-L%'
                         group by 1, 2 having count(*) > 1) d) as duplicate_notes,
  (select count(*) from (select source_note_id from work_records where source_note_id is not null and created_at >= :'started'
                         group by 1 having count(*) > 1) d) as duplicate_shares,
  (select count(*) from idempotency_requests where state = 'IN_PROGRESS' and created_at >= :'started') as idempotency_left_in_progress;

\echo 'every confirmation queued exactly one index job (README §7)'
select count(*) filter (where jobs = 0) as confirmations_without_embed_job,
       count(*) filter (where jobs > 1) as confirmations_with_several_embed_jobs
from (select r.id, (select count(*) from jobs j where j.kind = 'EMBED' and j.target_id = r.id and j.created_at >= :'started') as jobs
      from work_records r
      where r.trust_status in ('CONFIRMED', 'CORRECTED') and (r.confirmed_at >= :'started' or r.created_at >= :'started')) t;

\echo 'jobs created during the run: outcome and queue time (created -> last update)'
select kind, status, coalesce(failure_code, '') as failure_code, count(*),
       round(percentile_cont(0.95) within group (order by extract(epoch from updated_at - created_at))::numeric, 2) as p95_seconds,
       round(max(extract(epoch from updated_at - created_at))::numeric, 2) as max_seconds
from jobs where created_at >= :'started'
group by 1, 2, 3 order by 1, 2, 3;

\echo 'database size'
select pg_size_pretty(pg_database_size('baton')) as database,
       pg_size_pretty(pg_total_relation_size('work_records')) as work_records_total,
       pg_size_pretty(pg_relation_size('work_records')) as work_records_heap,
       pg_size_pretty(pg_indexes_size('work_records')) as work_records_indexes,
       pg_size_pretty(pg_total_relation_size('record_sources')) as record_sources_total,
       pg_size_pretty(pg_total_relation_size('record_embeddings')) as record_embeddings_total,
       (select count(*) from record_embeddings) as embedding_rows;
