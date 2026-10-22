-- 합성 부하 데이터 집합(README §8): 프로젝트 300개, 사용자 5,000명, 기록 900,000건(그 절반이 가장 큰 프로젝트 열 개에
-- 있다), 약 2 KiB의 요약, 기록 관계, 부하 시험이 쓰는 인수인계 기간의 임베딩 작업. 모든 값이 합성이다. 마이그레이션된
-- 빈 DB에 tests/load/seed.sh로만 돌린다.
--
-- 재현성: random()을 쓰지 않는다. 모든 선택이 hashtextextended(<salt>:<n>, seed)라서, 같은 PostgreSQL major 버전에서
-- 같은 seed는 실행 계획과 상관없이 같은 행을 만든다.
--
-- psql 변수: seed(bigint). 환경: BATON_SEED_CREDENTIAL_HASH, 합성 로그인 비밀번호의 bcrypt 해시. seed.sh가 만들어
-- 이름으로 넘겨서, 비밀번호도 해시도 이 파일이나 프로세스 목록에 닿지 않는다.

\set ON_ERROR_STOP on
\getenv credential_hash BATON_SEED_CREDENTIAL_HASH
\if :{?credential_hash}
\else
    do $$ begin raise exception 'seed.sql: BATON_SEED_CREDENTIAL_HASH is not set; run it through tests/load/seed.sh'; end $$;
\endif
set max_parallel_workers_per_gather = 0;
select set_config('baton.seed', :'seed', false);

select count(*) > 0 as has_users from users \gset
\if :has_users
    \echo 'seed.sql: users already exist; the load data set needs an empty migrated database.'
    \quit
\endif

create function pg_temp.h(n bigint, salt text) returns bigint language sql stable as $$
    select abs(hashtextextended(salt || ':' || n, current_setting('baton.seed')::bigint))
$$;

begin;

-- 조직과 사용자. 계정 1-3은 시스템 관리자이고 어떤 프로젝트에도 속하지 않는다.
insert into organizations (id, name)
select i, '합성 조직 ' || lpad(i::text, 2, '0') from generate_series(1, 30) i;

insert into users (id, organization_id, employee_no, name, title, email, system_role, credential_hash)
select i, 1 + (i - 1) % 30, 'LT' || lpad(i::text, 5, '0'), '합성사용자' || i,
       (array['사원', '대리', '과장', '차장', '부장', '수석'])[1 + pg_temp.h(i, 'title') % 6],
       'lt' || lpad(i::text, 5, '0') || '@load.example',
       case when i <= 3 then 'SYSTEM_ADMIN'::system_role else 'USER'::system_role end,
       :'credential_hash'
from generate_series(1, 5000) i;

-- 프로젝트 1-10이 기록의 절반을 가진 큰 프로젝트다.
insert into projects (id, code, name, delivering_company, client_company, started_on, ends_on, phase, status)
select i, 'LPJ-' || lpad(i::text, 3, '0'), '합성 프로젝트 ' || i, '합성 수행사', '합성 고객사 ' || (1 + i % 40),
       date '2026-05-01', date '2027-03-31', '구축',
       case when i % 10 = 0 then 'AT_RISK'::project_status else 'ON_TRACK'::project_status end
from generate_series(1, 300) i;

-- 관리자가 아닌 사용자는 모두 기본 프로젝트 하나에 속한다. 사용자 u는 프로젝트 1 + (u - 4) % 300에 들어간다.
-- 프로젝트마다 그런 사용자 가운데 처음 셋이 관리자, 기존 담당자, 신규 담당자다. 큰 프로젝트 열 개는 다른 프로젝트의
-- 구성원 40명도 받는다.
insert into project_members (project_id, user_id, project_role, joined_on)
select 1 + (u - 4) % 300, u,
       (array['PROJECT_MANAGER', 'OUTGOING_OWNER', 'INCOMING_OWNER'])[1 + (u - 4) / 300]::project_role,
       date '2026-05-01'
from generate_series(4, 5000) u where (u - 4) / 300 < 3
union all
select 1 + (u - 4) % 300, u, 'MEMBER', date '2026-05-01'
from generate_series(4, 5000) u where (u - 4) / 300 >= 3;

insert into project_members (project_id, user_id, project_role, joined_on)
select p, 4 + (p * 37 + j * 113) % 4997, 'MEMBER', date '2026-05-01'
from generate_series(1, 10) p, generate_series(1, 40) j
on conflict (project_id, user_id) do nothing;

-- 프로젝트마다 공유 연동 넷. checkpoint가 fixture 커넥터의 마지막 문서 뒤에 있어서, 예약 수집이 시험 내내 모든 연동에
-- 돌아도 새 후보를 더하지 않는다.
insert into source_connections (id, project_id, channel, display_name, external_ref, status, health, connector_kind,
                                checkpoint, last_status, last_collected_at)
select (p - 1) * 4 + c, p,
       (array['EMAIL', 'APPROVAL', 'MESSENGER', 'MEETING_MINUTES'])[c]::source_channel,
       (array['프로젝트 메일', '전자결재', '사내 메신저', '회의록'])[c] || ' ' || p,
       'fixture://load/' || p || '/' || c, 'ACTIVE', 'NORMAL', 'FIXTURE', '2', 'SUCCEEDED', timestamptz '2026-09-27 18:00+09'
from generate_series(1, 300) p, generate_series(1, 4) c;

-- 프로젝트별 기록 양과 거기서 나오는 id 범위.
create temp table plan as
with sizes as (
    select p, case when p <= 10 then 45000 else 1551 + case when p <= 220 then 1 else 0 end end as records
    from generate_series(1, 300) p
)
select p, records, (records + 2) / 3 as sources,
       coalesce(sum(records) over (order by p rows between unbounded preceding and 1 preceding), 0) as record_offset,
       coalesce(sum((records + 2) / 3) over (order by p rows between unbounded preceding and 1 preceding), 0) as source_offset,
       (5000 - 4 - (p - 1)) / 300 + 1 as base_members
from sizes;

-- 요약과 원문 본문에 쓰는 문장 조각. 조각 하나가 약 120바이트라서 15-19개(평균 17개)면 약 2 KiB의 요약이 된다.
create temp table fragments as
select i,
       (array['고객사 요청 사항', '인터페이스 명세', '데이터 이관 계획', '성능 시험 결과', '보안 점검 항목', '사용자 교육 일정',
              '운영 전환 절차', '장애 대응 방안', '계약 변경 범위', '통합 시험 시나리오', '배포 승인 절차', '권한 설계안',
              '외부 연계 방식', '품질 관리 기준', '비용 정산 기준', '검수 조건'])[1 + pg_temp.h(i, 'subject') % 16]
       || (array['에 대해 담당자 간 합의가 이루어졌고 후속 조치 담당이 정해졌다. ',
                 '의 일정이 조정되어 다음 주간 회의에서 다시 확인하기로 하였다. ',
                 '과 관련된 쟁점이 회의에서 다시 제기되어 추가 자료를 요청하였다. ',
                 '을 고객사와 재검토한 뒤 변경 요청서로 정리하기로 하였다. ',
                 '의 영향 범위를 분석한 결과 일부 화면과 배치 작업이 포함되었다. ',
                 '에 대한 승인 절차가 진행 중이며 결재 완료 후 적용할 예정이다. ',
                 '을 기준으로 잔여 작업을 다시 나누고 주간 보고에 반영하였다. ',
                 '의 미해결 항목은 위험 목록에 올려 다음 점검 때 확인하기로 하였다. '])[1 + pg_temp.h(i, 'predicate') % 8]
       || '참조 ' || lpad((pg_temp.h(i, 'ref') % 10000)::text, 4, '0') || '. ' as text
from generate_series(0, 999) i;

create temp table fragment_array as select array_agg(text order by i) as a from fragments;

insert into record_sources (id, source_connection_id, display_name, external_ref, collected_at, project_id,
                            external_document_id, external_version, content_hash, source_body, processing_status,
                            source_author_name, source_occurred_on)
select source_id, (plan.p - 1) * 4 + 1 + s % 4, '합성 문서 ' || source_id, 'fixture://load/doc/' || source_id,
       (occurred + time '18:00') at time zone 'Asia/Seoul', plan.p, 'load-doc-' || source_id, 'v1',
       encode(sha256(convert_to(body, 'UTF8')), 'hex'), body, 'SUCCEEDED', '합성작성자' || (1 + source_id % 97), occurred
from plan, generate_series(0, plan.sources - 1) s,
     lateral (select plan.source_offset + s + 1 as source_id,
                     date '2026-06-30' + (s * 90 / plan.sources)::int as occurred) ids,
     lateral (select string_agg(fa.a[1 + pg_temp.h(ids.source_id * 8 + f, 'body') % 1000], '' order by f) as body
              from fragment_array fa, generate_series(1, 4) f) texts;

-- 기록: 원문마다 셋. 75%는 CONFIRMED, 20%는 검토를 기다리는 AUTO_DRAFT, 5%는 DISMISSED. 담당자는 프로젝트 기본 구성원
-- 가운데 한 명이고 확인자는 그 관리자라서, 모든 값이 앱 자체의 확인을 통과한다.
insert into work_records (id, code, project_id, record_source_id, record_type, title, summary, original_excerpt,
                          occurred_on, author_name, owner_user_id, due_on, trust_status, confirmed_by, confirmed_at,
                          created_at, updated_at, source_version, extraction_index, search_text)
select record_id, 'REC-' || record_id, plan.p, plan.source_offset + k / 3 + 1, kind::record_type,
       '[' || label || '] 합성 기록 ' || record_id, summary, left(summary, 160), occurred,
       '합성작성자' || (1 + (plan.source_offset + k / 3 + 1) % 97),
       4 + (plan.p - 1) + 300 * (pg_temp.h(record_id, 'owner') % plan.base_members),
       case when kind in ('SCHEDULE', 'COMMITMENT') then occurred + (14 + pg_temp.h(record_id, 'due') % 47)::int end,
       status::record_trust_status,
       case when status = 'CONFIRMED' then 4 + (plan.p - 1) end,
       case when status = 'CONFIRMED' then (occurred + 1 + time '10:00') at time zone 'Asia/Seoul' end,
       (occurred + time '18:30') at time zone 'Asia/Seoul', (occurred + time '18:30') at time zone 'Asia/Seoul',
       'v1', k % 3, summary
from plan, generate_series(0, plan.records - 1) k,
     lateral (select plan.record_offset + k + 1 as record_id,
                     date '2026-06-30' + ((k / 3) * 90 / plan.sources)::int as occurred) ids,
     lateral (select (array['SCHEDULE', 'CHANGE_DECISION', 'COMMITMENT', 'BLOCKER', 'SCOPE_EXCEPTION', 'OPEN_ISSUE'])[1 + i] as kind,
                     (array['일정', '변경 결정', '약속', '차단 이슈', '범위 예외', '미결 이슈'])[1 + i] as label
              from (select pg_temp.h(ids.record_id, 'type') % 6 as i) t) kinds,
     lateral (select case when r < 75 then 'CONFIRMED' when r < 95 then 'AUTO_DRAFT' else 'DISMISSED' end as status
              from (select pg_temp.h(ids.record_id, 'status') % 100 as r) t) statuses,
     lateral (select string_agg(fa.a[1 + pg_temp.h(ids.record_id * 16 + f, 'summary') % 1000], '' order by f) as summary
              from fragment_array fa, generate_series(1, 15 + (pg_temp.h(ids.record_id, 'length') % 5)::int) f) texts;

-- 같은 프로젝트의 확정 기록 사이의 관계: 100건마다 참조, 500건마다 충돌.
insert into record_relations (project_id, from_record_id, to_record_id, relation_type, note)
select a.project_id, a.id, b.id, 'REFERENCES', '합성 참조 관계'
from work_records a join work_records b on b.id = a.id - 1 and b.project_id = a.project_id
where a.id % 100 = 0 and a.trust_status = 'CONFIRMED' and b.trust_status = 'CONFIRMED';

insert into record_relations (project_id, from_record_id, to_record_id, relation_type, note)
select a.project_id, a.id, b.id, 'CONFLICTS_WITH', '합성 충돌 관계'
from work_records a join work_records b on b.id = a.id + 2 and b.project_id = a.project_id
where a.id % 500 = 0 and a.trust_status = 'CONFIRMED' and b.trust_status = 'CONFIRMED';

-- 메모 목록이 비지 않도록 프로젝트 구성원마다 비공개 메모 하나.
insert into personal_notes (code, project_id, author_id, body, created_at)
select 'NOTE-L' || m.id, m.project_id, m.user_id, '합성 메모: ' || fa.a[1 + pg_temp.h(m.id, 'note') % 1000],
       timestamptz '2026-09-20 09:00+09'
from project_members m, fragment_array fa;

-- 부하 시험이 요청하는 인수인계 기간(큰 프로젝트 열 개의 최근 7일)의 확정 기록에 대한 색인 작업. API로 기록을
-- 확정하면 앱이 직접 넣는 작업이다.
insert into jobs (id, kind, target_id, dedup_key, status, available_at, deadline_at)
select gen_random_uuid(), 'EMBED', id, 'embed:' || id || ':0', 'PENDING', now(), now() + interval '6 hours'
from work_records
where project_id <= 10 and trust_status = 'CONFIRMED' and occurred_on >= date '2026-09-21';

select setval('organizations_id_seq', (select max(id) from organizations));
select setval('users_id_seq', (select max(id) from users));
select setval('projects_id_seq', (select max(id) from projects));
select setval('source_connections_id_seq', (select max(id) from source_connections));
select setval('record_sources_id_seq', (select max(id) from record_sources));
select setval('work_records_id_seq', (select max(id) from work_records));

commit;

analyze;

\echo 'seed.sql: row counts'
select 'organizations' as table_name, count(*) from organizations
union all select 'users', count(*) from users
union all select 'projects', count(*) from projects
union all select 'project_members', count(*) from project_members
union all select 'source_connections', count(*) from source_connections
union all select 'record_sources', count(*) from record_sources
union all select 'work_records', count(*) from work_records
union all select 'record_relations', count(*) from record_relations
union all select 'personal_notes', count(*) from personal_notes
union all select 'embed_jobs', count(*) from jobs where kind = 'EMBED';

\echo 'seed.sql: distribution'
select round(avg(octet_length(summary))) as avg_summary_bytes,
       round(100.0 * count(*) filter (where project_id <= 10) / count(*), 1) as top10_percent,
       count(*) filter (where trust_status = 'CONFIRMED') as confirmed,
       count(*) filter (where trust_status = 'AUTO_DRAFT') as auto_draft,
       count(*) filter (where trust_status = 'DISMISSED') as dismissed
from work_records;

\echo 'seed.sql: fingerprint (identical for the same seed)'
select md5(string_agg(id || ':' || project_id || ':' || record_type || ':' || trust_status || ':' || md5(summary), ',' order by id))
from work_records where id % 1000 = 0;
