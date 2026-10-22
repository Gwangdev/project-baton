# 백업·복원 확인 (W10.4·W10.5)

판정: **통과.** 부하 시험을 마친 DB를 백업했다. 이를 별도의 빈 volume에 복원한 뒤 데이터 fingerprint 79줄과 API 응답 13개를 비교했고, 원본과 모두 같았다. 원본 volume은 삭제하거나 바꾸지 않았다.

측정 범위는 로컬 단일 호스트에서의 논리 백업(`pg_dump`)과 복원이다. README §8의 운영 제안에 대해서는 다음과 같다.
- RPO 24시간/RTO 4시간, 야간 암호화 백업, 다른 장애 영역 보관, 월간 복원 연습: 이번 측정은 **복원 절차가 동작한다는 증거**일 뿐이며, 운영 백업 체계가 갖춰졌다는 뜻은 아니다.
- 백업 파일 암호화와 원격 보관: 구현·측정하지 않았다.
- WAL 연속 보관/PITR: 범위 밖이다.

## 절차 (`tests/recovery/restore-check.sh`)

1. API와 proxy를 멈춘다. 원본 fingerprint와 dump를 같은 시점에 얻기 위한 유지보수 창 방식이다. 서비스 중단 없는 온라인 백업(`pg_dump --snapshot`)은 측정하지 않았다.
2. 원본 fingerprint(`tests/recovery/fingerprint.sql`)를 남기고 `pg_dump -Fc -Z 6`로 백업한다. 파일의 SHA-256을 기록한다.
3. API와 proxy를 다시 시작한다.
4. 새 빈 volume과 외부 경로 없는 internal 네트워크를 만든다. 같은 DB 이미지를 원본과 같은 격리 조건으로 띄운다(UID 999, 읽기 전용 루트, tmpfs).
5. `pg_restore --exit-on-error`로 복원하고 `analyze`한다.
6. 복원본 fingerprint를 남겨 원본과 `diff`한다.
7. 같은 앱 이미지를 복원 DB에 붙여 하나 더 띄운다. 원본 API와 복원 API에서 같은 사용자·같은 조회를 실행해(`tests/recovery/api-probe.sh`) 응답 본문의 SHA-256을 `diff`한다.

fingerprint 구성:
- 모든 public 테이블의 행 수
- 업무 데이터(기록·관계·메모), snapshot, 패키지 항목, 평가 제출, Q&A·인용, jobs, 멱등 요청, 감사, 임베딩을 정해진 순서로 이은 md5
- sequence 값, Flyway 이력(V1~V11 checksum)

## 결과 (2026-09-28)

| 항목 | 값 |
|---|---|
| 대상 | 부하 시험 직후 DB 3,161 MB(기록 904,181, snapshot 108,146, jobs 89,596, 임베딩 72,143, Q&A 인용 3,285, 평가 제출 10) |
| 백업 | 33초, dump 225 MB, SHA-256 `24e0adf53fbe24d9db0a…`(전체 값은 결과 디렉터리의 baton.dump.sha256) |
| API 중단 | 07:12:27Z ~ 07:13:01Z(백업 동안), 이후 재시작 |
| 복원 | 빈 volume에서 준비·복원·analyze까지 71초 |
| fingerprint | **동일** (79줄: 행 수, md5 14종, sequence, Flyway 11건) |
| API 비교 | **동일** (응답 13개가 모두 서로 다른 해시이며 원본과 일치: 프로젝트, 확정·초안 타임라인, 기록 상세 3건, 인수인계 목록·상세·항목·평가·질문 이력, 기간 필터 타임라인, 메모 목록) |
| 원본 volume | `baton-w10_db-data`(2026-09-28T00:31:20Z 생성) 그대로 |

첫 실행은 스크립트 결함 두 가지로 실패했다.
1. 복원 DB에 비밀번호를 환경변수 이름(`-e POSTGRES_PASSWORD`)으로 넘겼는데, 셸에는 `BATON_DB_PASSWORD`만 있어 빈 값이 전달됐다. 그래서 DB 초기화가 거부됐다.
2. 준비 대기 루프가 컨테이너 종료를 감지하지 못해 계속 기다렸다.

두 가지를 고쳤다(`POSTGRES_PASSWORD`를 `BATON_DB_PASSWORD`로 내보냄, 대기 중 컨테이너가 죽으면 로그를 출력하고 중단). 첫 실행이 만든 복원 자원은 비어 있는 것을 확인한 뒤 지우고 다시 실행했다.

## 확인한 격리 조건 (W10.1과 같은 기준)

- 복원 DB: 호스트 포트 없음, internal 네트워크(외부 경로 없음), 비루트, 읽기 전용 루트
- 복원 API: 원본과 같은 이미지·사용자·쿠키 설정. 복원 네트워크에만 연결

## 남은 것

- 복원 사본(`baton-w10-restore-app`, `baton-w10-restore-db`, 네트워크 `baton-w10_restore`, volume `baton-w10_restore-db-data`)은 확인용으로 멈춘 채 남겨 두었다. 지울지는 사용자가 정한다.
- 운영 백업 체계(암호화, 원격 보관, 주기 실행, 보존 기간)는 회사 보존 정책이 정해진 뒤의 일이다. 이 정책은 개발 상태 기록(비공개)의 미해결 라벨로 추적 중이다.
