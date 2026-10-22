-- 접속기록 점검용 조회 (개인정보의 안전성 확보조치 기준 제8조 ②). 읽기만 한다. psql 변수 from·to에 점검 기간을 준다.
-- 시간대를 함께 써야 서울 기준 경계가 된다(쓰지 않으면 DB 세션 시간대로 읽는다):
--   psql -v from='2026-10-01 00:00+09' -v to='2026-11-01 00:00+09' -f access-log-queries.sql

\echo '1. 기간 안의 로그인 (성공만 기록된다)'
select a.occurred_at, u.employee_no, u.name, a.remote_address
from audit_events a left join users u on u.id = a.actor_id
where a.action = 'SIGN_IN' and a.occurred_at >= :'from' and a.occurred_at < :'to'
order by a.occurred_at;

\echo '2. 업무 시간(서울 08:00~20:00, 평일) 밖의 로그인'
select a.occurred_at at time zone 'Asia/Seoul' as seoul_time, u.employee_no, u.name, a.remote_address
from audit_events a left join users u on u.id = a.actor_id
where a.action = 'SIGN_IN' and a.occurred_at >= :'from' and a.occurred_at < :'to'
  and (extract(isodow from a.occurred_at at time zone 'Asia/Seoul') > 5
       or extract(hour from a.occurred_at at time zone 'Asia/Seoul') not between 8 and 19)
order by a.occurred_at;

\echo '3. 한 계정이 여러 주소에서 로그인한 경우'
select u.employee_no, u.name, count(distinct a.remote_address) as addresses, min(a.occurred_at), max(a.occurred_at)
from audit_events a join users u on u.id = a.actor_id
where a.action = 'SIGN_IN' and a.remote_address is not null and a.occurred_at >= :'from' and a.occurred_at < :'to'
group by u.employee_no, u.name having count(distinct a.remote_address) > 1
order by addresses desc;

\echo '4. 계정·권한을 바꾼 관리 행위'
select a.occurred_at, u.employee_no as actor, a.action, a.target_type, a.target_id, a.result
from audit_events a left join users u on u.id = a.actor_id
where a.occurred_at >= :'from' and a.occurred_at < :'to'
  and (a.action like '%USER%' or a.action like '%CREDENTIAL%' or a.action like '%PROJECT_MEMBER%'
       or a.action in ('BOOTSTRAP_SYSTEM_ADMIN', 'RESET_SYSTEM_ADMIN_CREDENTIAL'))
order by a.occurred_at;

\echo '5. 행위 종류별 건수'
select a.action, a.result, count(*)
from audit_events a
where a.occurred_at >= :'from' and a.occurred_at < :'to'
group by a.action, a.result order by count(*) desc;
