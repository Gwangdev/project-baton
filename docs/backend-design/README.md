# Project Baton 백엔드 설계 · 구현 지시서

작성일: 2026-09-14. 기준 커밋: `bc39306`. 상태(2026-10-12 갱신): **W00–W15·D1 구현·격리 검증(2026-10-12).** 이 문서는 설계 근거로 유지한다. 현행 계약은 루트 OAS·DBML·SPEC이고, 설계 이후의 개정은 각 절에 날짜와 함께 적는다. 아래 「판정 근거」 표의 수치와 「현재 구현」은 작성 당시 값이다.

목적은 추론 부담이 큰 결정을 설계 단계에서 해결하고, 구현자는 작은 작업과 명시된 테스트를 수행하게 만드는 것이다. 문서만으로 어떤 모델에서도 무오류를 보장할 수는 없다. 계약 검사, 실제 PostgreSQL 테스트, 권한 반례와 작업별 중단 조건으로 오류를 발견하고 다음 단계 전파를 막는다.

## 읽는 순서

1. 이 파일: 범위·결정·품질·권한·업무 규칙.
2. [계약 변경 명세](contracts.md): 기존 명세의 결함, 변경할 필드·경로·DB 구조.
3. [작업 카드](work-orders.md): 의존 순서, 파일 책임, 테스트, 실행자 입력·출력.
4. [현행 API 목록](api-inventory.md): 현재 56개 operation의 원문 대응.
5. [검토 기록](review.md): 확인 근거와 검증 범위.


## 1. 확인된 기준과 제안의 구분

| 구분 | 내용 | 근거 |
|---|---|---|
| 기존 확정 | Single Tenant, Vue/Spring/Spring AI/PostgreSQL/pgvector, 프로젝트별 권한 | 개발 상태 기록(비공개), 개발 기록(비공개)의 2단계 범위 |
| 기존 확정 | FR-01~FR-22, 6종 기록·4종 신뢰 상태, 개인 메모 원본 비공개 | 1단계 발표 자료(비공개) §2 |
| 작성 당시 계약 | OAS 3.0.3, 32 paths, 46 operations, 77 schemas, DB 19 tables | 루트 OAS·DBML 직접 집계 |
| 현행 계약(2026-10-19, D6) | 42 paths, 56 operations, 86 schemas, DB 30 tables(Flyway V1–V22) | 루트 OAS·DBML, `DbmlConsistencyIT` |
| 역사 자료 | 로그의 39/41 operations, 설계 해설의 DELETE 0개 | 현재 계약과 다름. 현재 DELETE는 5개 |
| 작성 당시 구현 | Vue 메모리 저장소와 데모 시나리오, 서버 구현 없음 | 현행은 `backend/README.md`, `evidence/W11.md` |
| 이번 제안 | 모듈형 단일 앱, 쿠키 세션, 영속 작업 큐, 스냅샷, 동시성 제어, 아래 품질 목표 | 본 설계. 기존 승인 사실로 간주하지 않는다 |

개발 상태 기록(비공개)의 발표 제출 대기와 41 API 표기는 백엔드 착수 상태가 아니다. W00에서 원래 내용을 로그의 역사 구간으로 보존하고 현재 상태를 갱신한다. 현재 원본 OAS·DBML·SPEC은 본 문서 작성만으로 바뀌지 않는다. 실행 시작을 지시받으면 W00에서 제안 계약을 먼저 반영하고, 그 뒤에 코드를 작성한다. 계약 변경을 건너뛰어 새 엔드포인트를 구현하지 않는다.

## 2. 범위와 우선순위

**P0:** 로컬 합성 데이터로 인증, 프로젝트별 권한, 기록 확인, 비공개 메모 공유, 관리 CRUD, 내구성 있는 작업 처리, 인수인계 생성·검토·준비도·승인, 실제 HTTP 프론트엔드 연결.

**P1:** Spring AI 기반 실제 구조화·RAG, 같은 계약을 따르는 로컬 LLM 어댑터, 검색 폴백, 성능·장애·복구 검증. 가짜 응답은 P0 검증용 `stub` 프로필임을 명시하며 AI 구현 완료로 세지 않는다.

**P2:** 실기업 SSO·실제 클라우드 폴더 연동·고가용성 배포. 공급자 계정과 도입 정책이 아직 없으므로 인터페이스·수용 기준을 제공하고 운영 연결 완료를 주장하지 않는다. P0/P1 합성 데이터 개발은 이 조건에 묶이지 않는다.

금지: PC 감시, 통화 자동 녹음, AI 자동 확정, 개인 메모 원본의 RAG 투입, 다른 프로젝트 근거 사용, 무승인 실자료 외부 전송. 회사별 보존·SSO 조건 미정은 운영 출시 차단 사항이며 로컬 설계 작성의 차단 사유는 아니다.

## 3. 구조와 기술 선택

| 대안 | 장점 | 비용·위험 | 판단 |
|---|---|---|---|
| 모듈형 단일 앱 + PostgreSQL 작업 큐 | 업무 트랜잭션과 작업 접수를 한 DB에서 원자적으로 처리, 배포 단순 | 큐 폴링·lease 검증 필요 | 초기 채택 |
| API·워커 분리 + RabbitMQ | 워커 자원 격리·확장 용이 | outbox·브로커 운영·중복 배달 처리가 추가 | DB 큐 경합/AI 자원 경합이 측정되면 전환 |
| 마이크로서비스 | 조직별 독립 배포 | 분산 트랜잭션·관찰·운영 부담 | 현재 규모·개발 조건에서 보류 |

기술 기준: **Java 21, Spring Boot 4.1.1, Spring AI BOM 2.0.1, PostgreSQL 17 계열 + pgvector 0.8 계열**. W05.G에서 Maven 기준선과 동등한 16 IT suites/77 tests 및 실행 JAR을 검증한 뒤 **Gradle Wrapper 9.7.1 + Kotlin DSL**을 단일 빌드 체계로 확정했다. Spring Boot 4.1.1은 Gradle 8.14 이상과 9.x를 공식 지원한다. 실제 해석된 의존성과 Wrapper checksum은 `backend/DEPENDENCIES.md`에 고정한다. `latest`, SNAPSHOT, 모델이 추정한 메서드명은 금지한다.

Spring MVC + JPA + Bean Validation + Security + Spring Session JDBC + Flyway + Actuator/Micrometer를 사용한다. BOM 관리 대상은 별도 버전을 중복 지정하지 않는다. JDBC의 파라미터 바인딩은 큐 claim과 복합 검색에 사용한다. 엔티티는 응답으로 직렬화하지 않고 record DTO로 변환한다. Lombok·MapStruct·범용 BaseService·모든 서비스의 인터페이스/Impl 쌍은 추가하지 않는다. 외부 공급자 경계에만 인터페이스를 둔다.

```text
Vue → same-origin reverse proxy → Spring MVC API
                                  ├─ Security / project policy
                                  ├─ domain services → PostgreSQL
                                  └─ durable jobs → bounded workers
                                                        ├─ source adapter
                                                        └─ Spring AI → configured model
PostgreSQL: business data + jobs + sessions + vectors + audit
```

`backend/src/main/java/com/projectbaton/` 아래 `identity`, `project`, `record`, `note`, `connection`, `handover`, `assessment`, `qa`, `jobs`, `audit`, `config`, `shared`를 둔다. 각 기능 내부에 controller/service/repository/DTO를 둔다. `shared`에는 Clock, 공통 오류·페이지 타입만 둔다. 컨트롤러는 검증·인증 주체 전달·상태코드 매핑, 서비스는 권한·업무·트랜잭션, repository는 DB 조회만 책임진다. 순환 서비스 의존은 금지한다. 승인 서비스가 assessment 조회를 사용하되 assessment는 승인 서비스를 호출하지 않는다.

## 4. 인증·권한 규칙

### 4.1 인증

브라우저 단일 출처 서비스이므로 Spring Security + JDBC 세션을 제안한다. 현재 bearer JWT 표기는 토큰 발급/폐기 계약이 없는 상태여서 [계약 변경](contracts.md) C01로 교체한다. 로컬 로그인은 `DelegatingPasswordEncoder`의 adaptive encoder를 사용한다. 단순 SHA-256과 데모 비밀번호 비교는 금지한다. SSO는 P2의 OIDC 연결 시 동일 내부 user ID로 매핑한다.

세션 idle 30분, 로그인 시 ID 재발급, 로그아웃 시 세션 삭제. 쿠키 `BATON_SESSION`, HttpOnly, SameSite=Lax, Path=/, 운영 Secure=true. CSRF는 로그인·로그아웃 포함 상태변경 요청에 적용한다. `GET /api/v1/auth/csrf`에서 받은 토큰을 `X-CSRF-TOKEN`으로 보낸다. 세션 ID는 localStorage에 저장하지 않는다. 로그인 성공 후 CSRF 토큰을 다시 가져온다. 비활성 사용자·비활성 조직은 매 요청 차단한다. 가입 공개 API는 없다. 로컬 계정 seed 비밀번호는 환경변수 입력을 해시하여 저장하고 관리자 생성 계정의 로그인 활성화는 별도 초기 자격증명 준비 전까지 차단한다.

### 4.2 권한 행렬

프로젝트 활성 membership 판정: `joined_on <= 업무일 AND (left_on IS NULL OR 업무일 < left_on)`, 업무일은 Asia/Seoul, 저장 timestamp는 UTC. 시스템 역할과 프로젝트 역할은 별개다.

| 행위 | 허용 주체 | 반드시 확인할 조건 |
|---|---|---|
| 프로젝트 일반 조회 | 활성 구성원 | 프로젝트·사용자·조직 활성 |
| 확정 기록 조회 | 활성 구성원 | 해당 프로젝트; 개인 원본 필드는 별도 마스킹 |
| 개인 연결 원문·미확정/제외 후보 | 연결 소유자 | 활성 구성원; PM·시스템 관리자 우회 불가 |
| 공용 연결 초안 검토 | OUTGOING_OWNER 또는 PROJECT_MANAGER | 활성 구성원; INCOMING_OWNER/MEMBER는 확정 자료만 |
| 개인 연결 초안 검토 | 연결 소유자 | 역할과 무관하게 본인 연결, 활성 구성원 |
| 개인 메모 생성·목록·공유 | 메모 작성자 | 활성 구성원; 공유해도 원본 비공개 |
| 개인 연결 생성·수정 | 연결 소유자 | CLOUD_PROJECT_FOLDER만; owner는 principal에서 채움 |
| 공용 연결 생성·설정/전체 상태 | SYSTEM_ADMIN | 원문 접근권은 생기지 않음 |
| 교체 요청·충돌 해결·보완·승인 | PROJECT_MANAGER | 같은 프로젝트의 현행 구성원 |
| 패키지 조회 | 현행 PM·지정 전임자·지정 후임자 | 프로젝트 접근권 + handover 참여, 일반 MEMBER 거부 |
| Q&A 생성·목록 | 지정 후임자 | 대화는 `(handover_id, session_user_id)`로 격리 |
| 준비도 제출·필수항목 확인 | 지정 후임자 | 현재 패키지 generation |
| 준비도 결과 조회 | 후임자·현행 PM | PM에게도 정답 키 미노출 |
| 조직·사용자·프로젝트·membership 관리 | SYSTEM_ADMIN | 자기 자신/마지막 관리자 비활성화·강등 금지 |

권한 없는 프로젝트 ID, 타인 메모 ID는 일관되게 404로 은닉한다. 이미 접근 가능한 자원에서 역할이 부족하면 403. 미인증은 401. 목록 필터, 합계, 대시보드, 검색, 인용 모두 같은 predicate를 적용한다. 목록을 먼저 가져온 다음 Java에서 권한 없는 행을 제거하는 방식은 금지한다.

개인 폴더 기록이 확정돼도 원본 저장소 권한은 승계되지 않는다. 비소유자에게는 확정 요약만 보이며 `originalExcerpt`, 원본 URL, 파일명은 비공개로 처리한다. 개인 메모의 `sourceNoteCode`도 작성자 외에는 생략한다. 확정된 요약·유형·담당자·기한은 공유 대상이다. 수집 단계에서 만든 원문 발췌를 실수로 함께 공개하지 않는다.

권한 검사와 쓰기의 TOCTOU 방지: 업무 변경 트랜잭션은 관련 project 행을 읽기 잠금하고 membership·user를 검증한다. membership 변경과 승인·프로젝트 비활성화는 project 쓰기 잠금을 획득한다. 잠금 순서는 `project → users(id 순) → memberships(id 순) → handover → record → item → assessment`로 고정한다. user 비활성화는 해당 user 쓰기 잠금으로 경쟁 요청을 직렬화한다. 긴 LLM 호출 동안 DB 트랜잭션을 유지하지 않는다.

## 5. 닫힌 업무 규칙

### 5.1 기록·관계·메모

`AUTO_DRAFT → CONFIRMED | CORRECTED | DISMISSED`만 review API에서 허용한다. 확정/제외 상태 재검토는 409. CONFIRM은 필드 수정 없이 확정, CORRECT는 변경 필드 하나 이상과 changeReason 필요, DISMISS는 확정자 필드 null 유지. 수정 이력은 요약만이 아니라 변경 전후의 제목·유형·요약·담당자·기한을 보존한다. 주석의 과거 D번호를 구현 규칙으로 사용하지 않는다.

메모 공유는 작성자가 보낸 요약으로 새 record를 생성하며 `original_excerpt=공유 요약`, 상태 CONFIRMED, 확인자=principal, `source_note_id`만 채운다. body를 자동 복사·AI 전달하지 않는다. 새 요약은 별도 key로 여러 번 공유할 수 있고 같은 요청의 재전송은 동일 결과로 처리한다.

SUPERSEDES는 새 기록 → 이전 기록, REFERENCES는 방향성, CONFLICTS_WITH는 낮은 ID → 높은 ID 한 행. 서로 다른 프로젝트·자기 자신·대체 순환·하나의 이전 기록을 두 개의 현행 기록이 대체하는 모호성·같은 두 기록 사이의 대체와 상충 공존은 409(대체는 결론이 났다는 뜻이고 상충은 아직 결론이 없다는 뜻이라 함께 성립하지 않는다. 참조는 함께 걸 수 있다). 관계 등록은 사람이 확정한 기록끼리 PM만 가능하다. AI는 관계 후보를 제안할 뿐 등록하지 않는다. 프로젝트 행 쓰기 잠금으로 관계 변경을 직렬화한다.

### 5.2 패키지와 완료

요청 당시 기간(양 끝 포함), 유형, 공유 메모 포함 여부, 확정 기록 ID·버전을 저장한다. 조회 시마다 실시간 데이터로 기존 패키지 본문을 재작성하지 않는다. 자료가 추가돼도 기존 snapshot에 조용히 편입하지 않는다. 재생성 API는 generation을 증가시키고, 이전 generation의 확인·준비도 결과를 승인 근거에서 제외한다.

| 현재 상태 | 입력·조건 | 다음 상태 |
|---|---|---|
| 없음 | PM 요청, 전임·후임 다름, 후임 활성 구성원, 유효 scope | REQUESTED |
| REQUESTED | 워커가 해당 generation job 획득 | GENERATING |
| GENERATING | snapshot·항목·문항 원자적 게시 | READY |
| GENERATING | 제한 시간/재시도 소진/자료 부족 | FAILED |
| FAILED | PM retry | REQUESTED, 같은 generation 재시도 |
| READY | PM 보완 요청, comment 필수 | REVIEW_REQUESTED |
| REVIEW_REQUESTED | 후임자가 현재 generation 준비도 재제출 | READY |
| READY 또는 REVIEW_REQUESTED 또는 FAILED | PM 재생성 요청 | REQUESTED, generation+1 |
| READY | PM 승인, 모든 blocker 없음 | COMPLETED |
| COMPLETED | 변경 요청 | 409, 이력 고정 |

동일 프로젝트의 진행 중 handover는 한 건만 허용한다(FAILED 포함). 실패는 retry/재생성으로 복구한다. P0 취소는 미제공임을 UI에 명시한다. 신규 담당자는 요청 전에 관리자 membership API로 배정한다. 교체 요청이 임의의 사용자에게 프로젝트 권한을 새로 부여하지 않는다.

기간 내 AUTO_DRAFT는 **근거에 넣지 않으며** 접근 가능한 미확정 건수를 경고한다. 다른 사람의 개인 초안 내용/건수는 경고에도 넣지 않는다. 전임자 부재 시 모든 초안을 확인해야 완료되는 규칙은 채택하지 않는다. 필수 항목은 이번 snapshot에 포함된 변경 결정·고객 약속·미해결 위험·후속 작업과 개요로 한정한다.

승인 조건: 현재 generation READY, 후임자 최신 응시가 passThreshold 충족, 해당 generation 필수항목 전부 명시적 확인, SUPERSEDES/CONFLICTS_WITH의 패키지 내 검토 항목 해결, 모든 FOLLOW_UP 담당자가 후임자이며 필수 기한이 정의됨, 권한·활성 상태 유효, effectiveOn <= 업무일. 과거 세대 점수와 프로젝트 전체 미확정 건수를 사용하지 않는다.

승인은 한 트랜잭션으로 handover COMPLETED·감사 이벤트·전임 membership 종료·후임 역할 전환을 기록한다. 전임 `left_on=승인 업무일`, 후임 `project_role=OUTGOING_OWNER`(이후 현 담당자). 기존 enum 이름은 유지하되 UI는 완료 후 현 담당자로 표시한다. 후임이 현재 진행 건 이외의 인수인계 이력을 읽는 것은 접근 가능한 프로젝트와 자신의 handover 참여 조건으로 허용한다. 미래 교체일의 조기 승인은 409이며 자동 예약 전환은 이번 범위에서 제외한다.

### 5.3 이해도 평가

데모의 `expected.includes(given)`은 구현하지 않는다. 평가 문항 5개, passThreshold=5를 초기값으로 한다. 근거가 5문항을 만들 수 없으면 패키지 생성은 FAILED/INSUFFICIENT_EVIDENCE로 표시하고 재료 확보 후 재생성한다. 이는 소량 자료의 조기 인수인계를 제한하는 선택이므로 개선 후보는 마지막 절에 둔다.

MULTIPLE_CHOICE는 공개 choice ID 목록과 서버 전용 correctChoiceId를 저장한다. SHORT_ANSWER는 서버 전용 acceptedAnswers 목록과 NFKC·trim·연속 공백 1개·Locale.ROOT 소문자 정규화 후 완전 일치한다. 날짜 등 동치 표기는 문항 생성 시 acceptedAnswers에 명시한다. 점수는 LLM으로 판정하지 않는다. P0는 합성 fixture의 검수된 문항만 사용한다. P1은 확인된 구조화 필드에 검수된 템플릿을 적용하고 정답·근거를 결정적으로 검증한다(C08). 자유형 AI 문항·LLM 채점·PM 문항 승인 기능은 이번 범위에 포함하지 않는다.

모든 문항을 정확히 한 번 제출해야 한다. 누락·중복·다른 평가 문항·빈 답은 400. `(assessment_id, attempt_no)`는 assessment 잠금 하에서 발급한다. 정답과 acceptedAnswers는 API·브라우저 번들·로그에서 제외한다. 최신 응시만 승인 판정에 사용하고 과거 이력은 유지한다.

## 6. 영속 비동기 처리

`@Async`만으로 접수 완료를 반환하지 않는다. 요청 트랜잭션에서 업무 행과 jobs 행을 함께 저장한 뒤 202를 반환한다. 별도 워커는 짧은 트랜잭션으로 `FOR UPDATE SKIP LOCKED` claim 후 lease token을 저장하고 커밋한다. 외부 호출을 마친 후 token·generation이 여전히 일치할 때만 결과를 게시한다. 중복 실행 가능성은 인정하되 업무 결과는 unique key와 fencing으로 한 번만 게시한다.

상태: PENDING → RUNNING → SUCCEEDED 또는 RETRY_WAIT → RUNNING 또는 FAILED. claim은 실행 슬롯을 확보한 다음 수행한다. lease 30초, heartbeat 10초, 전체 deadline은 종류별 별도. 재시작 시 만료 lease를 회수한다. 자동 재시도는 최대 3회 **총 시도**(최초 포함), backoff 1초·4초 + 최대 20% jitter, 429의 Retry-After는 남은 deadline 안에서 존중한다. 400/401/403/스키마 오류는 자동 재시도하지 않는다. 사용자 명시적 retry는 별도 attempt batch를 기록한다.

SDK 재시도는 1회 시도로 고정하고 작업 계층만 재시도한다. 이는 업무 DB와 결과 원자성 때문에 필요한 영속 orchestration이며, HTTP 재시도 알고리즘을 여러 층에서 재구현하지 않는다는 하네스 규칙을 따른다. 워커가 재시작하면 API와 조회는 계속 동작하고 Q&A 진행 실패는 FAILED로 정리한다.

AI 총 동시 호출은 배포 전체 10개: 대화 3, 구조화/패키지 6, 임베딩 1. 초기 API/워커 실행 인스턴스는 1개로 고정한다. 인스턴스 수를 늘릴 때 DB 기반 lease 슬롯 또는 분리 큐 없이 프로세스별 semaphore만 복제하면 목표 10을 위반한다. DB pool 20, worker DB pool 8, control DB pool 4를 초기값으로 하고 DB max_connections에 마이그레이션·세션·관측 여유를 남긴다. 이 값은 성능 보장 수치가 아니다.

DB pool 분리(2026-09-28 개정, W10.6): 두 pool은 Spring `AbstractRoutingDataSource` 하나 뒤에 두고 트랜잭션 관리자는 하나로 유지한다. worker pool(8)은 AI·작업 경로가 쓴다: job 실행 slot의 스레드, Q&A 근거 검색 구간. control pool(4, 2026-09-30 개정, W10.7 체크포인트 리뷰)은 job을 살려 두고 정리하는 예약 작업이 쓴다: lease heartbeat, 만료 회수·정리(sweep), Q&A 복구, 보존 삭제, 수집 스케줄러. heartbeat가 worker pool을 job 실행 slot 8개와 나눠 쓰면, 작업이 연결을 모두 쥐었을 때 연결 대기 시간을 넘겨 lease가 만료되고 실행 중인 job이 다른 slot에 다시 넘어갔다. control pool 크기는 예약 스레드 수와 같아 예약 작업이 연결을 기다리지 않는다. 그 밖의 요청 처리와 Q&A 접수·종결 트랜잭션은 API pool(20)을 쓴다. 연결은 트랜잭션을 시작할 때 정해지므로 경로는 트랜잭션 밖에서 정한다. W10 부하 시험에서 AI 경로가 같은 pool을 쓰다가 DB 포화 때 연결을 5~9초 쥐었기 때문에 나눈다. worker·control pool 연결의 `statement_timeout`은 lease와 같은 30초다. 한 쿼리가 lease보다 오래 연결을 쥐지 못하게 하기 위해서다. API pool에는 두지 않는다. pool 분리는 연결 고갈을 막을 뿐 DB CPU 포화는 막지 못하므로, CPU는 쿼리 비용으로 다룬다. 만료 lease 회수·소진 job 실패 처리·버려진 source 정리는 claim마다가 아니라 dispatch 주기(기본 1초)마다 프로세스에서 한 번 실행한다. 회수는 최대 한 주기 늦어질 수 있다.

큐 최대 미종료 50,000건; 포화 시 사용자 요청은 503 + Retry-After=30, 수집기는 체크포인트를 전진시키지 않는다. INGEST 적재(2026-09-24 개정): 스케줄러가 기본 5분마다 활성 프로젝트의 ACTIVE 연결 중 미종료 INGEST job이 없는 연결에 한 건씩 적재하고, 연결 행 잠금으로 중복 적재를 막는다. 큐가 포화되면 그 주기의 적재를 멈춘다. 워커는 INGEST를 claim해 기본 100건 배치를 수집하고, 같은 트랜잭션에서 lease를 확인하며 job을 완료한다. lease를 잃었으면 수집 결과를 롤백한다. 포화로 STRUCTURE 적재가 실패하면 롤백 후 job을 RUNNING으로 두어 lease 회수로 재시도한다. 연결 비활성(`CONNECTION_INACTIVE`)·부재(`CONNECTION_NOT_FOUND`)는 job만 실패시키고, 미지원 커넥터(`UNSUPPORTED_CONNECTOR`)·같은 버전 내용 변경(`SOURCE_VERSION_CONFLICT`)은 job과 연결의 last_status를 FAILED로 기록한다. 연결의 last_message에는 코드만 남긴다. 큐 시간 p95·최고 age·실패율을 기록한다. 수집 작업의 중복 key는 `(connection_id, external_document_id, external_version)`이고 동일 버전 내용이 다르면 오류로 격리한다. 원문 정규화 문자열은 최대 100,000자, 파일 10MiB, 로컬 테스트는 합성 텍스트 fixture만 사용한다. 임의 externalRef URL을 서버가 fetch하지 않는다.

## 7. RAG와 모델 품질

검색 입력은 서버가 계산한 현재 user·project·handover generation으로 고정한다. 클라이언트가 project 필터를 생략하거나 프롬프트로 우회할 수 없다. snapshot 확정 요약만 임베딩하며 개인 메모 원문·개인 파일 전문은 제외한다. 신규/변경 record는 index job을 같은 트랜잭션으로 적재한다. 임베딩 상태와 record version을 비교하고 stale index는 사용하지 않는다.

기본 chunk 800자, overlap 100자, 후보 최대 40개, 최종 근거 최대 8개. 임베딩 모델 ID·차원·digest는 W07에서 고정하며 다른 차원을 같은 컬럼에 혼합하지 않는다. 초기값의 검색 품질은 평가로 판단한다. pgvector의 ANN 검색은 필터 조건에서 recall이 낮아질 수 있으므로 프로젝트 B-tree 필터와 정확 검색을 먼저 측정하고 필요 시 HNSW/iterative scan을 평가한다. 한국어 키워드 폴백은 정규화된 용어의 안전한 substring 검색부터 시작하고 데이터 규모 시험에서 실패하면 pg_trgm 인덱스를 도입한다. 기본 영어 stemming이 한국어 검색을 해결한다고 가정하지 않는다.

검색 근거 0건은 NO_EVIDENCE, 모델 타임아웃은 FAILED. 한쪽을 다른 쪽으로 위장하지 않는다. 인용은 모델이 쓴 URL을 신뢰하지 않고 서버의 후보 ID로 재구성한다. 인용 ID가 allowlist 밖이면 해당 답변을 게시하지 않는다. 미해결 충돌 질문에는 결론을 확정하지 않고 두 근거와 확인 대상을 제시한다. 검증된 문장만 스트리밍하고 검색 안내 이벤트를 실제 첫 답변 토큰으로 세지 않는다. 구조화 JSON schema 위반을 임의 coercion하거나 확정값으로 승격하지 않는다.

`stub`는 오프라인 계약 테스트, `local`은 선택한 로컬 모델, `provider`는 승인된 모델 연결 프로필이다. W07 구현: `stub` 구조화는 문서 본문 SHA-256으로 기록된 출력(`ai-stub/structuring.json`)만 재생하고 그 밖의 문서에는 기록을 만들지 않는다. 스스로 분류하지 않으므로 모델 판단으로 오인될 출력이 없다. `stub` 임베딩은 정규화 단어·문자 bigram의 부호 해시 256차원이다. `local`/`provider`는 Spring AI `ChatModel`·`EmbeddingModel` bean과 `baton.ai.embedding-model-id`가 없거나 임베딩 차원이 256이 아니면 기동을 멈춘다. 구조화 출력은 코드 펜스·설명 문장·중복 키·후행 토큰·알 수 없는 필드/enum·프로젝트 밖 담당자·원문에 없는 발췌를 모두 실패(`MODEL_OUTPUT_INVALID`)로 처리하고, 공급자 오류는 최대 3회 시도 후 `MODEL_UNAVAILABLE`로 원본을 FAILED 처리해 수동 재시도로 넘긴다. 벡터 후보는 코사인 거리 `baton.retrieval.max-cosine-distance`(기본 0.8, stub 기준값) 이하만 쓰며, 실모델 도입 시 평가로 다시 정한다. W08 구현: QA job은 요청 스레드가 lease(30초)를 쥔 RUNNING으로 만들고 deadline을 lease 만료와 같게 두어, 살아 있는 요청의 job을 lease 회수가 넘겨주지 않는다. 모델 호출은 대화용 스레드 3개에서 `baton.qa.model-timeout`(기본 20초) 안에 끝나야 하고, 넘으면 `MODEL_TIMEOUT`이다. delta는 생성이 끝나고 인용 검증을 통과한 답변을 문장 단위로 보낸 것이다. 따라서 첫 delta 시각은 생성 완료 시각이며 토큰 단위 스트리밍이 아니다. 스트림 중 실패는 error 이벤트로 끝나고 FAILED 답변이 저장된다. 같은 key의 재전송은 저장된 답변을 done 하나로 돌려준다. 모델이 빈 답변과 빈 인용을 반환하면 NO_EVIDENCE로 처리하고, 본문이 있는데 인용이 없거나 후보 밖을 인용하면 `CITATION_INVALID`로 게시하지 않는다. 미해결 충돌 기록이 근거에 있으면 상대 기록을 근거에 추가하고 UNRESOLVED CONFLICT로 표시한다. 인용의 출처명은 개인 폴더 파일명이면 폴더 소유자에게만 보이고(그 밖에는 `비공개 출처`), 메모 공유 기록은 `공유 메모`로 표시한다. 제안 한도(사용자당 분당 6회·동시 1회)는 429 `QA_RATE_LIMITED`로 적용했다. `local`/`provider`는 `baton.ai.chat-model-id`도 요구해 답변마다 모델 ID와 prompt version을 남긴다. 모델 이름만 같다고 성능·정확도가 같다고 보지 않고 각 프로필을 별도 측정한다. 외부 LLM 비용·하드웨어·사용 모델이 없으므로 3초/15초 달성을 지금 보장하지 않는다. 사용자당 Q&A 분당 6회·동시 1회, 질문 2,000자, 답변 4,000자, context 12,000자 상한을 제안한다.

## 8. 성능·장애·운영 수용 기준

| 요구사항 | 기존 목표 | 본 설계의 측정·택틱 |
|---|---|---|
| NFR-02/03 | 5,000 users / 1,500 DAU / 300 concurrent / 30·60 RPS | 300 virtual users는 상태 유지 상한; 60 RPS는 open arrival로 별도 구동 |
| NFR-04 | 10k/day, peak 20k/day | 8시간 집중 유입=0.694건/s, burst 5건/s 10분 추가 |
| NFR-05 | 일반 p95 500ms, 검색 1s | 대표 조회·쓰기 각각 임계값, DB/HTTP pool 분리, 목록 상한 |
| NFR-06 | 첫 답변 p95 3s, 전체 15s; timeout 30s | proxy 도착부터 첫 검증된 답변 문장/마지막 문장; 대기 포함 |
| NFR-07 | 구조화 p95 30s/60s, 패키지 p95 120s/300s | 접수부터 완료까지 대기 포함; 실행시간은 별도 지표 |
| NFR-08 | AI·벡터 장애 시 일반 업무 유지 | 모델 30초 지연·접속실패 중 일반 API 30 RPS/p95 500ms 검증 |
| NFR-09/10 | 프로젝트 격리·합성 데이터 | 교차 프로젝트·권한 철회·private 유출 테스트 필수 |

처리량 계산: 구조화에 6 slot을 배정하고 평균 30초가 걸린다면 0.2건/s, 8시간 5,760건으로 피크 2만 건에 못 미친다. 20,000건/8시간을 처리하려면 6 slot의 평균 처리시간은 8.64초 이하, 70% utilization을 목표로 하면 약 6.05초 이하여야 한다. p95 30초를 평균으로 오해하지 말고 실제 평균과 token 처리량을 측정한다. 초과 시 중복 수집 제거·배치 구조화·모델 선택 최적화를 먼저 평가하고, 동시성 확대는 비용/일반 API 영향 시험 후 별도 결정한다.

부하 데이터 제안: 300 projects, 5,000 users, 900,000 records(30일×피크 평균이 아닌 일 10k×90일의 시험 데이터), 평균 요약 2KiB, 상위 10개 프로젝트에 50% 편중. 인용·관계·평가도 생성한다. 모든 record를 같은 프로젝트에 넣지 않는다. 원문 용량·벡터·인덱스·WAL은 별도 측정한다. 2KiB×10k×365는 요약만 약 7.5GB/년(십진)이며 전체 DB 크기가 아니다.

부하 시나리오: warm-up 5분 → 30 RPS 15분 → 60 RPS 15분 → 30 RPS 60분 soak. 혼합 비율 조회 70%, 기록 검토·메모 20%, 관리·handover 10%; AI는 별도 producer로 10 slot 포화를 재현한다. 실패율 <1%(의도된 4xx 제외), 데이터 누락/중복 0, 권한 누출 0, dropped iterations 0. 하드웨어 CPU/RAM·DB·모델·데이터 seed를 결과에 첨부한다. 기준 미달이면 측정 결과를 내고 임계값을 조용히 완화하지 않는다.

**운영 개선 제안(기존 확정 아님):** 일반 업무 월 가용률 99.5%(30일 오류 예산 216분), RPO 24시간/RTO 4시간을 초기 내부 검증 목표로 둔다. 야간 암호화 백업을 다른 장애 영역에 저장하고 매월 복원 연습한다. (2026-10-12 D2에서 구현: 폴더 대상·age 공개키·일간 30 + 월간 12·복원 연습 스크립트, 계약은 contracts.md C11 「D2」) 단일 호스트 Compose에는 호스트 장애 SPOF가 있어 99.5%를 보장하지 못한다. RPO 15분 필요 시 WAL 연속 보관/PITR로 상향한다. 자동 업무 데이터 삭제는 비활성화하고 보존 정책 승인 전 시행하지 않는다. 로그 보존 30일, 작업 성공 메타 7일/실패 30일은 합성 개발환경 제안값이며 운영 개인정보 보존 결정을 대체하지 않는다. 구현(2026-09-28 개정, W10.6): 작업 메타 정리는 `baton.retention.jobs.enabled` 스위치로 켜며, 기본값은 꺼짐이다. 합성 환경(compose.dev.yml, 부하 시험 overlay)에서만 켠다. 미종료 job은 지우지 않는다. 멱등 요청 행의 24시간 보존은 계약 값(C01)이므로 스위치 없이 항상 정리하고, 유효한 IN_PROGRESS 행은 남긴다.

컨테이너: 외부 포트는 proxy 하나, DB는 internal 네트워크, 관리자 도구는 별도 profile. 앱 non-root·read_only + /tmp tmpfs, DB 데이터는 명시 volume. DB 이미지가 요구하는 UID/쓰기 디렉터리를 실제 실행으로 검증한다. 로그 json-file max-size 10m/max-file 3. CPU/memory limit은 compose config와 실제 inspect 양쪽으로 검증한다.

Liveness는 프로세스만, readiness는 DB 질의까지 검사한다. LLM 장애는 readiness 실패 조건에서 제외하여 일반 서비스가 내려가지 않게 한다. `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`는 내부 관리 포트만 공개한다. 지표 태그에 user ID·project ID·질문·원문을 넣지 않는다. 감사에는 actor ID·행위·target ID·result·trace ID만 남기고 비밀번호·본문·토큰은 남기지 않는다. (2026-10-13 D3에서 구현: 요청 시간 분포 버킷과 목표값 SLO 버킷, 별도 관측 overlay `compose.observe.yml`의 Prometheus·Grafana·경보 규칙, 계약은 contracts.md C11 「D3」. 운영은 회사 관측 체계에 붙인다, A-14)

## 9. 더 나은 서비스를 위한 후속 제안

| 제안 | 기대 가치 | 비용·부작용 | 순위 |
|---|---|---|---|
| 근거 누락과 충돌을 보여주는 coverage 지표 | 패키지의 한계를 후임자가 파악 | 단순 건수를 정확성 점수로 오해할 수 있음 | P1, 항목별 누락만 표시 |
| 5개 미만 기록의 축소 평가 | 초기 소규모 프로젝트도 이용 가능 | 준비도 기준 변경, 형식적 통과 위험 | P2, PM 지정 필수항목으로 별도 설계 |
| 문서 변경 시 기존 인수인계에 새 자료 알림 | 오래된 패키지로 일하는 위험 감소 | 재생성/재확인 부담 | P1, stale 배지만 먼저 |
| 대기열 age와 추정 완료시간 | 사용자 반복 클릭 감소 | 추정 오차 | P1, backlog 상태 먼저 |
| Redis·별도 메시지 브로커·ANN | 실제 병목 해소 | 캐시 권한 무효화·운영 부담 | 지표 기반 채택 |

## 10. 외부 기술 근거

아래 링크는 2026-09-14 조회했고 Gradle 항목은 2026-09-21 재확인했다. 제품 요구사항이 아니라 구현 기법의 근거다.

- [Spring Boot 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html): Boot 4.1.1의 Java 및 Gradle 8.14+/9.x 지원 범위.
- [Spring Boot Gradle Plugin](https://docs.spring.io/spring-boot/gradle-plugin/introduction.html): 실행 JAR과 configuration cache 지원.
- [Spring Boot Gradle 의존성 관리](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html): Gradle native BOM 또는 dependency-management plugin 선택 근거. W05.G는 native BOM을 사용한다.
- [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html): 고정 버전 실행, `-bin` 배포본, distribution SHA-256과 Wrapper JAR 검증.
- Spring AI Getting Started: 2.0.x와 Boot 4.0/4.1 호환, BOM 사용.
- Spring Security Password Storage: adaptive PasswordEncoder와 저장 형식.
- [PostgreSQL SELECT](https://www.postgresql.org/docs/15/sql-select.html): SKIP LOCKED는 큐 소비자 경쟁 회피에 사용 가능; 일반 정합 조회 대용이 아님.
- [pgvector 원문](https://github.com/pgvector/pgvector): 필터와 approximate index의 동작 및 iterative scans.

실행 마지막 확인: 이 작업의 근거는 승인된 계약인가, private 데이터가 응답·프롬프트에 섞이지 않는가, 테스트가 실제 실행됐는가. 실패 시 구현 완료를 선언하지 말고 [작업 카드](work-orders.md)의 중단 보고 형식으로 남긴다.
