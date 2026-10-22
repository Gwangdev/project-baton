-- 성능 증거(docs/backend-design/evidence/performance.md) 뒤의 읽기 전용 실행 계획. 모든 문장이 EXPLAIN ANALYZE 아래의
-- SELECT이고, 앱에서 UPDATE인 작업 sweep 두 개는 같은 뜻의 SELECT로 재서, 이 파일을 돌려도 데이터는 바뀌지 않는다.
--
--  docker compose exec -T db psql -U baton -d baton -X -v actor=<user id> -v project=<project id> < tests/load/explain.sql
--
-- 기록 문장은 project/RecordVisibility와 record/RecordRepository가 만드는 글을, 작업 문장은
-- connection/SourceStructuringHandler와 connection/IngestionScheduler의 글을 그대로 따른다. 그쪽이 바뀌면 이 파일도
-- 함께 바꾼다. 첫 실행은 차가운 buffer 비용을 치르므로 두 번 돌려 두 번째 결과를 읽는다.
\set ON_ERROR_STOP on
\pset footer off
\set visible 'wr.project_id = scope.project_id and exists (select 1 from projects visible_project join project_members visible_member on visible_member.project_id = visible_project.id and visible_member.user_id = scope.actor_id join users visible_user on visible_user.id = visible_member.user_id join organizations visible_organization on visible_organization.id = visible_user.organization_id where visible_project.id = scope.project_id and visible_project.is_active and visible_user.is_active and visible_organization.is_active and visible_member.joined_on <= current_date and (visible_member.left_on is null or current_date < visible_member.left_on)) and (wr.trust_status in (''CONFIRMED'', ''CORRECTED'') or wr.record_source_id in (select reviewable_source.id from record_sources reviewable_source join source_connections reviewable_connection on reviewable_connection.id = reviewable_source.source_connection_id join project_members reviewer on reviewer.project_id = reviewable_connection.project_id and reviewer.user_id = scope.actor_id where reviewable_source.project_id = scope.project_id and reviewable_connection.project_id = scope.project_id and (reviewable_connection.owner_user_id = scope.actor_id or (reviewable_connection.owner_user_id is null and reviewer.project_role in (''OUTGOING_OWNER'', ''PROJECT_MANAGER'')))))'
\set display_joins 'left join record_sources rs on rs.id = wr.record_source_id left join source_connections sc on sc.id = rs.source_connection_id left join personal_notes pn on pn.id = wr.source_note_id left join users owner on owner.id = wr.owner_user_id left join users confirmer on confirmer.id = wr.confirmed_by'
\set columns 'wr.id, wr.code, wr.summary, wr.occurred_on, wr.trust_status, owner.name, pn.code, sc.owner_user_id, rs.display_name, confirmer.name'

\echo '== timeline total =='
explain (analyze, buffers, costs off, summary on)
select count(*) from work_records wr cross join (select :actor::bigint as actor_id, :project::bigint as project_id) scope
where :visible;

\echo '== timeline total, drafts only =='
explain (analyze, buffers, costs off, summary on)
select count(*) from work_records wr cross join (select :actor::bigint as actor_id, :project::bigint as project_id) scope
where :visible and wr.trust_status in ('AUTO_DRAFT'::record_trust_status);

\echo '== timeline first page, no filter =='
explain (analyze, buffers, costs off, summary on)
select :columns from work_records wr cross join (select :actor::bigint as actor_id, :project::bigint as project_id) scope
:display_joins
where :visible
order by wr.occurred_on desc, wr.id desc limit 20 offset 0;

\echo '== timeline first page, drafts only =='
explain (analyze, buffers, costs off, summary on)
select :columns from work_records wr cross join (select :actor::bigint as actor_id, :project::bigint as project_id) scope
:display_joins
where :visible and wr.trust_status in ('AUTO_DRAFT'::record_trust_status)
order by wr.occurred_on desc, wr.id desc limit 100 offset 0;

\echo '== record detail (the project''s newest record) =='
select id as record from work_records where project_id = :project order by occurred_on desc, id desc limit 1 \gset
explain (analyze, buffers, costs off, summary on)
select :columns from work_records wr
cross join (select :actor::bigint as actor_id, (select project_id from work_records where id = :record) as project_id) scope
:display_joins
where wr.id = :record and :visible;

\echo '== dashboard projects with their pending counts and, for the twenty cards, open-risk counts =='
explain (analyze, buffers, costs off, summary on)
select p.id, pm.project_role,
       (select count(*) from work_records wr cross join (select :actor::bigint as actor_id, p.id as project_id) scope
        where wr.trust_status = 'AUTO_DRAFT' and :visible) as pending_record_count,
       case when row_number() over (order by p.created_at desc, p.id desc) <= 20
            then (select count(*) from work_records wr where wr.project_id = p.id and wr.record_type = 'OPEN_ISSUE'
                    and wr.trust_status in ('CONFIRMED', 'CORRECTED'))
            else 0 end as open_risk_count
from projects p join project_members pm on pm.project_id = p.id join users u on u.id = pm.user_id
join organizations o on o.id = u.organization_id
where pm.user_id = :actor and p.is_active and u.is_active and o.is_active
  and pm.joined_on <= current_date and (pm.left_on is null or current_date < pm.left_on)
order by p.created_at desc, p.id desc;

\echo '== abandoned-source release (as SELECT) =='
explain (analyze, buffers, costs off, summary on)
select source.id from record_sources source,
     (select distinct on (target_id) target_id, status, failure_code from jobs where kind = 'STRUCTURE'
      order by target_id, created_at desc, id desc) latest
where latest.target_id = source.id and latest.status = 'FAILED' and source.processing_status in ('PENDING', 'PROCESSING')
  and not exists (select 1 from jobs pending where pending.kind = 'STRUCTURE' and pending.target_id = source.id
                  and pending.status in ('PENDING', 'RUNNING', 'RETRY_WAIT'));

\echo '== ingestion schedule: connections without an unfinished INGEST job =='
explain (analyze, buffers, costs off, summary on)
select connection.id from source_connections connection join projects project on project.id = connection.project_id
where connection.status = 'ACTIVE' and project.is_active
  and not exists (select 1 from jobs where kind = 'INGEST' and target_id = connection.id and status in ('PENDING', 'RUNNING', 'RETRY_WAIT'))
order by connection.id;
