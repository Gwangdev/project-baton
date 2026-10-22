-- 백업과 복원 확인(restore-check.sh)용 데이터 fingerprint. 원본과 복원한 DB에서 출력이 같으면 복원이 같은 업무
-- 데이터, snapshot, 작업을 담은 것이다. 줄마다 행 수이거나 고정된 순서의 행 md5여서 두 파일을 diff로 비교할 수 있다.
-- 큰 글은 먼저 자기 md5로 줄인다.
\set ON_ERROR_STOP on
\pset footer off
\pset tuples_only on
\pset format unaligned

select 'rows ' || relname || ' ' || n from (
  select c.relname, (xpath('/row/n/text()', query_to_xml('select count(*) as n from public.' || quote_ident(c.relname), false, true, '')))[1]::text as n
  from pg_class c join pg_namespace s on s.oid = c.relnamespace
  where s.nspname = 'public' and c.relkind = 'r'
) t order by relname;

select 'md5 work_records ' || md5(string_agg(id || ':' || version || ':' || trust_status || ':' || coalesce(confirmed_by::text, '') || ':' || md5(summary), ',' order by id)) from work_records;
select 'md5 record_relations ' || md5(string_agg(id || ':' || from_record_id || ':' || to_record_id || ':' || relation_type, ',' order by id)) from record_relations;
select 'md5 personal_notes ' || md5(string_agg(id || ':' || author_id || ':' || md5(body), ',' order by id)) from personal_notes;
select 'md5 handovers ' || md5(string_agg(id || ':' || status || ':' || generation || ':' || version, ',' order by id)) from handovers;
select 'md5 handover_generations ' || md5(string_agg(id || ':' || handover_id || ':' || generation || ':' || status, ',' order by id)) from handover_generations;
select 'md5 handover_snapshot_records ' || md5(string_agg(generation_id || ':' || work_record_id || ':' || md5(content_json::text), ',' order by generation_id, work_record_id)) from handover_snapshot_records;
select 'md5 handover_items ' || md5(string_agg(id || ':' || generation_id || ':' || item_kind || ':' || md5(body), ',' order by id)) from handover_items;
select 'md5 assessment_attempts ' || md5(string_agg(id || ':' || assessment_id || ':' || score || ':' || total, ',' order by id)) from assessment_attempts;
select 'md5 qa_messages ' || md5(string_agg(id || ':' || role || ':' || coalesce(answer_status::text, '') || ':' || md5(content), ',' order by id)) from qa_messages;
select 'md5 qa_citations ' || md5(string_agg(id || ':' || qa_message_id || ':' || work_record_id, ',' order by id)) from qa_citations;
select 'md5 jobs ' || md5(string_agg(id || ':' || kind || ':' || status || ':' || attempts || ':' || coalesce(failure_code, ''), ',' order by id)) from jobs;
select 'md5 idempotency_requests ' || md5(string_agg(id || ':' || state || ':' || coalesce(http_status::text, ''), ',' order by id)) from idempotency_requests;
select 'md5 audit_events ' || md5(string_agg(id || ':' || action || ':' || result, ',' order by id)) from audit_events;
select 'md5 record_embeddings ' || md5(string_agg(record_id || ':' || record_version || ':' || chunk_no || ':' || md5(embedding::text), ',' order by record_id, record_version, chunk_no)) from record_embeddings;

select 'sequence ' || sequencename || ' ' || coalesce(last_value::text, 'unset') from pg_sequences where schemaname = 'public' order by sequencename;
select 'flyway ' || version || ' ' || coalesce(checksum::text, '') from flyway_schema_history where success order by installed_rank;
