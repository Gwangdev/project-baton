-- 스키마를 몰라도 되는 public 스키마의 모든 테이블 fingerprint: 테이블마다 행 수와, 고정된 순서로 각 행의 md5를 다시
-- md5한 값. DB와 그 복원한 백업을 비교하는 데 쓴다(tests/recovery/restore-drill.sh). 행 md5를 써서 테이블 크기와
-- 상관없이 집계가 작다.
select format(
  'select %L || ''|'' || count(*) || ''|'' || coalesce(md5(string_agg(md5(t::text), '''' order by md5(t::text))), ''-'') from public.%I t',
  tablename, tablename)
from pg_tables where schemaname = 'public' order by tablename
\gexec
