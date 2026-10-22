# 모델 실행용 작업 지시서

상태: W11까지 구현·검증됨(W11.1 수정 진행). 완료 증거가 있는 과거 카드와 앞으로 만들 대상을 구분한다. 카드에 적힌 예상 테스트·경로는 실제 실행 전에는 통과 또는 존재로 간주하지 않는다.

**카드를 만드는 기준(2026-10-10 사용자 결정):** 새 카드는 공개 표면(경로·스키마·migration)이나 계약이 바뀌는 작업에만 쓴다. 검증에서 나온 낮음 지적의 수정은 카드를 새로 만들지 않고 대장 한 행과, 그 지적이 나온 카드의 「보정」 절 몇 줄로 처리한다. 구현하지 않을 설계는 카드가 아니라 대장 항목에 결정을 적어 둔다.

## 실행자 공통 카드

작업 폴더는 이 저장소의 루트다. `pwd`, `git status --short`, `git log -1 --oneline`을 먼저 읽고 기존 변경을 보존한다. 기존 수업 폴더에서 작업하지 않는다. 이전 대화·다른 모델의 완료 보고를 증거로 사용하지 않는다.

각 카드는 작은 검토 단위이며 표에 있는 소항목을 **한 번에 하나씩** 구현한다. W04 전체를 한 번에 생성하지 않고 W04.1의 실패 테스트 → 구현 → 통과 → W04.2 순서로 간다. 모든 클래스가 빈 메서드인 대규모 골격을 먼저 만들지 않는다.

1. 하네스 문서(비공개), 개발 상태 기록(비공개), [README](README.md) §2·4·5, 현재 카드의 계약 절을 읽는다.
2. 현재 카드의 선행 증거가 없으면 해당 선행 작업부터 수행한다. 변경한 계약과 테스트 대상 경로를 적는다.
3. 카드에 지정한 실패 상황의 테스트를 먼저 작성한다. 실패 원인이 컴파일/환경이면 기대한 도메인 실패와 구분한다.
4. 해당 동작만 구현한다. HTTP 권한을 Controller annotation 하나로 끝내지 않고 서비스의 자원 소유 검사까지 적용한다.
5. 지정 테스트를 실제 실행하고 exit code와 실패 수를 기록한다. skip/disabled를 통과로 세지 않는다.
6. 처음 완성한 업무 operation과 카드 종료 시 게이트를 실행한다. 미구현 S3와 실제 오류 S4를 구분한다. SPEC의 미구현 경로를 지워 통과시키지 않는다.
7. 결과를 `docs/backend-design/evidence/Wxx.md`에 아래 형식으로 저장하고 STATE는 현재 카드·다음 카드만 100줄 이내로 유지한다. 이 파일들은 구현 시 생성한다.

```text
작업: Wxx.n
기준 커밋:
변경 파일:
계약: Cxx, operationId
테스트 명령 / exit code / 테스트 수 / skip 수:
실패 테스트가 잡은 구체적 반례:
현재 남은 계약 차이:
판정: 통과 / 실패 / 환경 차단
다음 작업:
```

할 수 없는 것: OAS에 없는 업무 endpoint 추가, 테스트 기대값을 구현 출력에 맞춰 변경, 임의 retry/timeouts 확대, 로그에 credential/원문 출력, mock 통과를 실제 모델 성능으로 보고, H2로 PostgreSQL 제약 검사 대체. 모델의 실제 이해·추론 성능은 제품명으로 판정하지 않는다. 계약 적용에 모호함이 남으면 모호한 문장·파일·선택지 두 개를 기록하고 독립적인 다른 소항목만 진행한다.

### 시작 프롬프트

아래 블록을 구현 모델에게 그대로 전달할 수 있다.

```text
Project_Baton 백엔드 작업 W00부터 수행하라. 작업 루트는
이 저장소의 루트다.
하네스 문서(비공개)와 개발 상태 기록(비공개)를 읽은 뒤 docs/backend-design/README.md,
contracts.md, work-orders.md에서 해당 카드가 요구하는 절을 읽어라.
현재 요청은 승인된 설계의 구현이며 카드 순서를 건너뛰지 마라.
한 번에 소항목 하나만 구현하고 명시된 반례 테스트를 실제 실행하라.
문서의 예상 명령/파일과 실제 생성·실행 결과를 구분하라.
52개 업무 operation 계약 정비가 완료되기 전에 서버 API를 추가하지 마라.
다른 카드의 기능을 선제 생성하지 말고 오류를 숨기는 fallback을 만들지 마라.
작업 종료 시 evidence/Wxx.md 형식으로 결과와 다음 소항목을 남겨라.
실자료 외부 전송·배포·push는 하지 마라. 커밋은 현재 사용자의 지시를 따르고,
커밋할 때 Co-authored-by 등 트레일러를 붙이지 마라.
```

## W00. 기준 계약 확정과 기계 검증

선행: 사용자가 설계에 기반한 구현 시작을 지시함. 읽기: contracts C00~C10, STATE/LOG, 현행 API 목록. 결과: **코드보다 먼저 고정된 계약**.

| 소작업 | 변경 파일 | 행동 | 완료 신호 |
|---|---|---|---|
| W00.1 | 개발 상태 기록(비공개) / 개발 기록(비공개) | 과거 발표 상태를 역사로 보존, 백엔드 단계와 설계 경로 추가; 선호 stack·private·규제 조건 유지 | STATE <=100줄, 최신 실제 API 수치, 승인과 제안 구분 |
| W00.2 | ProjectBaton-API.yml / SPEC.yaml | C01~C08/C10 반영, 6개 새 operation 추가, cookies/CSRF/If-Match/idempotency/오류/페이지/SSE 스키마 정의 | 업무 operation 집합 52개, paths38; SPEC prefix 포함 집합 일치 |
| W00.3 | ProjectBaton-DB.dbml | C02~C09의 필드·제약·8 tables·세션2 개정 | 업무/운영29개, 기존19개 이름 보존; schema history 제외 |
| W00.4 | tools/check_backend_contract.py / tools/test_backend_contract.py / requirements-dev.txt | YAML parser 고정 의존성 선언, 계약 검사기와 잘못된 fixture 작성 | 아래 7개 조건·반증 fixture 모두 검사 |

검사기 조건: (1) operationId 유일 (2) OAS local `$ref` 전부 해석 가능 (3) SPEC/OAS method+full path 집합 일치 (4) path parameters required/type 일치 (5) authenticated 쓰기 공통 오류·CSRF/버전 대상 정확 적용 (6) DTO에 정답/credential 필드 없음 (7) FR 직접 매핑22개와 신규6개 누락 없음. required 배열에 properties 없는 필드, page 상한 누락도 실패시킨다. 단순 operation 수만 비교하지 않는다.

검증 명령(구현 후): `.venv/bin/python -m unittest discover -s tools -p 'test_backend_contract.py'`, `.venv/bin/python tools/check_backend_contract.py`. 기존 `.venv`가 없으면 새 저장소에서 생성하고 requirements-dev.txt로 설치한다. 이전 프로젝트의 가상환경을 영구 경로로 사용하지 않는다. `.claude/`가 없어도 검증기를 실행할 수 있어야 한다.

## W01. 빌드·DB·테스트 기반

선행 W00. 읽기 README §3/8, C09. 결과는 최소 실행 앱과 실제 DB 테스트이며 업무 API는 아직 없음.

역사 상태: Maven 기준으로 완료했다. 아래 파일·명령과 [W01 증거](evidence/W01.md)는 당시 사실이므로 Gradle 전환 뒤에도 고쳐 쓰지 않는다. 대체 빌드 기준은 W05.G에서 새 증거로 남긴다.

파일: `backend/pom.xml`, `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/wrapper/`, `backend/DEPENDENCIES.md`, `backend/src/main/java/com/projectbaton/BatonApplication.java`, `backend/src/main/resources/application.yml`, `backend/src/main/resources/db/migration/V1__business_schema.sql`, `backend/src/test/java/com/projectbaton/SchemaIT.java`, `.gitignore`.

W01.1 Maven Wrapper+Java21+Boot4.1.1 구성. 공식 생성/배포물에서 wrapper를 가져오고 배포 checksum을 기록한다. dependencies는 MVC/JPA/validation/security/actuator/JDBC-session/Flyway/PostgreSQL, tests는 JUnit/MockMvc/Testcontainers. 사용 버전의 정확 artifact 이름을 공식 BOM/문서에서 확인하고 DEPENDENCIES에 기록한다. W01.2 V1 적용과 validate 기동. W01.3 JUnit `*Test`는 Surefire, `*IT`는 Failsafe verify에 연결. profile test는 외부 AI 호출 0건.

명령: `./backend/mvnw -f backend/pom.xml verify`. 기대: 빈 DB 기동·동일 DB 재기동 둘 다 성공, XOR/날짜/관계 project mismatch SQL이 실제 제약 위반, migration 재실행 시 데이터 보존. DB 연결 실패면 test skip하지 않고 환경 차단으로 보고. `backend/target` 등 새 생성물 gitignore 추가.

## W02. 보안·오류·멱등 요청 기반

선행 W01. 읽기 README §4, C01/C02/C09.

소작업 W02.1 `identity/SecurityConfig.java`, `AuthController.java`, `LocalAuthenticationService.java`, `CurrentActor.java`로 signIn/signOut/me/csrf를 구현. W02.2 `project/ProjectAccessPolicy.java`로 `requireMember(actorId,projectId)`와 `requireManager(actorId,projectId)` 구현. 사용자 이름/역할은 요청 body에서 신뢰하지 않는다. W02.3 `shared/ApiExceptionHandler.java`에 Problem 매핑. W02.4 `shared/IdempotencyService.java`, `audit/AuditService.java`, V2 운영 테이블로 command transaction을 구성한다. 테스트 `identity/AuthenticationIT`, `project/ProjectAccessPolicyIT`, `shared/IdempotencyIT`.

필수 테스트: wrong password401, 올바른 비밀번호+inactive403, anonymous401, CSRF없는 쓰기403, 로그아웃 후 보호 endpoint401, 다른 프로젝트404, admin의 타인메모 접근404, 같은 key 두 동시 생성 결과 업무행1개, 다른 body409, 권한 철회 뒤 replay 거부. 세션 JDBC 데이터가 DB 재시작 후 만료 규칙대로 작동하는지 확인한다.

W02 완료 시 첫 업무 operation 게이트 실행. 아직 backend가 52개를 모두 구현하지 않은 S3는 개발 진행 기록이며 전체 완료 판정이 아니다.

## W03. 관리자 관리와 일반 프로젝트 조회

선행 W02. 읽기 C02와 api-inventory admin/project/dashboard 행.

각 소항목마다 Controller/Service/Repository/DTO와 IT 한 묶음을 만든다: W03.1 `identity/Organization*` 4 operations, W03.2 `identity/User*`4, W03.3 `project/ProjectAdmin*`4, W03.4 `project/ProjectMember*`4, W03.5 `project/ProjectQuery*`와 `Dashboard*`3 operations. 프로젝트 일반 list/detail/dashboard와 admin 전역 목록의 범위를 구분한다.

테스트: 20/100 page 경계, 알 수 없는 필드400, 명시 null과 미전달 구분, stale If-Match412, missing428, empty list200, 참조 DELETE409와 references, 비참조 DELETE204 body없음, 자기/마지막 admin 강등409, membership 종료 후 다음 요청차단, 미래 joinedOn은 접근금지. admin 생성 사용자는 credential null로 로그인이 안 되는 상태임을 응답/UX에서 설명한다.

W03부터 W05.2까지의 역사적 전체 검증 명령은 `./backend/mvnw -f backend/pom.xml verify`이고 카드별 `-Dit.test=<ClassName>`은 개발 중에 사용했다. W05.G 완료 뒤 전체 검증은 `./backend/gradlew -p backend clean build`, 단일 통합 테스트는 `./backend/gradlew -p backend integrationTest --tests '<fully.qualified.ITClass>'`를 사용한다. 카드 종료 `build`는 선행 회귀를 포함한다.

## W04. 기록·메모·연결

선행 W03. 읽기 README §4/5.1, C03/C04.

| 소작업 | 파일 책임 | 테스트 이름과 반례 |
|---|---|---|
| W04.1 | `record/RecordQueryController`, `RecordQueryService`, `RecordRepository`, Record DTO: listRecords/getRecord | RecordPrivacyIT: 타인 개인 초안·원본·count·sourceNoteCode 누출0 |
| W04.2 | `record/RecordReviewService`/Controller: reviewRecord | RecordReviewIT: 동시에 CONFIRM/CORRECT 중 하나만 성공, 이력·version 일치 |
| W04.3 | `note/PersonalNote*`: 3 operations | PersonalNoteIT: 원본100자 중 선택10자만 shared record에 존재; 재시도 중복0 |
| W04.4 | `connection/SourceConnection*`: 기존7 operations | ConnectionIT: 공용 생성admin만, 소유자 위조불가, disconnect후 record보존 |
| W04.5 | `record/RecordRelationService`/Controller | RecordRelationIT: cycle·교차프로젝트·대칭중복 차단 |
| W04.6 | `connection/ManualDraftController`, `ManualDraftService` | ManualDraftIT: failed source 수동작성→AUTO_DRAFT→review, 즉시CONFIRMED 금지 |

record author unknown은 null user+이름으로 표현한다. 서비스에서 principal 이름을 외부 작성자로 대입하지 않는다. generated DTO 대신 엔티티 전체 반환을 발견하면 카드 실패다.

## W05. 영속 작업과 합성 수집

선행 W04. 읽기 README §6, C04/C09.

파일: `jobs/JobRepository.java`, `JobWorker.java`, `JobHandler.java`, `JobLeaseService.java`, `connection/SourceConnector.java`, `FixtureSourceConnector.java`, `IngestionService.java`, `SourceProcessingController.java`; `jobs/JobRecoveryIT.java`, `connection/IngestionIT.java`.

경계 타입: `SourceConnector.fetch(connectionId,checkpoint,batchSize)` → `SourceBatch(documents,nextCheckpoint)`. documents 각 항목은 externalId/version/contentHash/body/authorName/occurredOn. DB 소유자/프로젝트는 connection에서 결정한다. `JobHandler.execute(JobContext)` → `JobResult`; context는 jobId/targetId/generation/leaseToken/deadline만 포함한다. 원문 전달용 공통 Map을 만들지 않는다.

W05.1 job row+업무 row rollback 일치. W05.2 claim→lease→fenced completion. 두 소작업은 커밋 `e469932`, `2e4a7b9`까지 구현됐다. W05.3을 시작하기 전에 W05.G를 완료한다. 이후 W05.3 max3 attempts/deadline/포화, W05.4 fixture fetch와 source dedup/checkpoint, W05.5 retrySourceProcessing 순서로 진행한다. 테스트: 접수직후 프로세스 종료·lease 만료·오래된 worker 결과도착·같은 source 2회·같은 version 다른hash·정지된 connection·50k포화. 뒤늦은 결과가 새 generation을 덮으면 실패. 네트워크 재시도는 SDK와 중복하지 않는다.

### W05.G. Gradle 빌드 전환(W05.2 다음·W05.3 이전)

목표는 빌드 도구만 교체하고 W05.2까지의 애플리케이션 동작과 검증 범위를 보존하는 것이다. Java 업무 코드, SQL migration, 기존 테스트의 기대값을 Gradle에 맞추어 변경하지 않는다. 전환 기준선은 2026-09-21 Maven `verify`의 **77 tests, failures 0, errors 0, skipped 0**이다.

| 소작업 | 변경 파일 | 행동 | 완료 신호 |
|---|---|---|---|
| W05.G1 기준선 | `docs/backend-design/evidence/W05G.md` | 변경 전 `./backend/mvnw -f backend/pom.xml clean verify` 실행; 테스트 suite·case·skip 수와 실행 JAR 경로 기록 | Maven 77/0/0/0, 실패하면 전환 중단 |
| W05.G2 병행 구성 | `backend/settings.gradle.kts`, `backend/build.gradle.kts`, `backend/gradlew`, `backend/gradlew.bat`, `backend/gradle/wrapper/*` | Gradle 9.7.1 `-bin`, Kotlin DSL, Java toolchain 21, Boot plugin 4.1.1, Gradle native BOM으로 Boot 및 Spring AI 2.0.1 import; Maven 파일은 아직 유지 | Wrapper 버전·JAR·distribution checksum 검증, Gradle 의존성 resolve 성공 |
| W05.G3 테스트 수명주기 | `backend/build.gradle.kts` | 기본 `test`는 `**/*Test.class`, 별도 `integrationTest`는 같은 test source set의 `**/*IT.class`; 둘 다 JUnit Platform, integrationTest에 `spring.profiles.active=test`; `check`가 integrationTest 의존 | `clean build`가 16개 IT class·77 tests를 실제 실행, skip 0, `bootJar` 생성 |
| W05.G4 단일화 | `SPEC.yaml`, `.gitignore`, `backend/DEPENDENCIES.md`, 개발 상태 기록(비공개), 관련 설계 문서; 제거 `backend/pom.xml`, `backend/mvnw*`, `backend/.mvn/` | Gradle 결과가 기준선과 같을 때만 test_command와 문서를 전환하고 Maven 파일 제거; `backend/build/` ignore 추가, W01 증거는 보존 | Maven 참조는 역사 문서만 남고 활성 명령은 Gradle 하나 |

`build.gradle.kts`는 `java`와 `org.springframework.boot` plugin만 적용하고 별도 `io.spring.dependency-management` plugin 없이 Gradle native `platform`으로 두 BOM을 가져온다. 기존 Maven의 모든 direct dependency와 runtime/test scope를 일대일로 옮기며 `testRuntimeOnly("org.junit.platform:junit-platform-launcher")`를 선언한다. 버전은 BOM 관리 대상에 중복 기입하지 않는다. `integrationTest`는 `sourceSets.test`의 output/runtimeClasspath를 재사용하고 `shouldRunAfter(test)`로 순서만 정한다. 장기 dual-build는 금지한다.

검증 명령:

```text
./backend/gradlew -p backend --version
./backend/gradlew -p backend clean test integrationTest bootJar --rerun-tasks
./backend/gradlew -p backend clean build
./backend/gradlew -p backend integrationTest --tests 'com.projectbaton.jobs.JobRecoveryIT'
.venv/bin/python tools/check_backend_contract.py
python3 tools/gate.py .
```

검증자는 `backend/build/test-results/integrationTest/TEST-*.xml`에서 suite 16개, tests 77개, failures/errors/skipped 0을 기계 집계한다. `test`와 `integrationTest`가 같은 IT를 중복 실행하거나 어느 한쪽도 실행하지 않으면 실패다. `backend/build/libs/project-baton-backend-0.0.1-SNAPSHOT.jar`에 `BOOT-INF/classes/com/projectbaton/BatonApplication.class`와 세 migration이 있는지 검사한다. 새 임시 Gradle user home의 빈 캐시에서도 Wrapper checksum 검증과 `clean build`가 성공해야 한다. 기존 Maven `target` 결과를 Gradle 성공 증거로 사용하지 않는다.

전환 커밋은 (1) Gradle 병행 구성·동등성 증거, (2) 활성 명령 전환·Maven 제거의 두 논리 단위로 나눈다. 첫 커밋에서 Maven 기준선이 실패하거나 Gradle 테스트 수가 다르면 두 번째 커밋으로 진행하지 않고 `W05G.md`에 차이를 기록한다. W05.G 완료 전 W05.3 기능 개발과 다른 브랜치 통합을 중단한다.

## W06. 인수인계 snapshot·확인·준비도·완료

선행 W05. 읽기 README §5.2/5.3, C05/C06/C08.

파일: V3 migration, `handover/HandoverRequestService`, `HandoverGenerationHandler`, `HandoverQueryService`, `HandoverConflictService`, `HandoverAcknowledgementService`, `HandoverApprovalService`; `assessment/AssessmentService`, DTO/Controller/Repository는 해당 service와 같은 기능 패키지. 테스트 `HandoverSnapshotIT`, `HandoverTransitionIT`, `HandoverApprovalIT`, `AssessmentIT`.

W06.1 request/retry/regenerate와 snapshot. W06.2 deterministic package builder와 같은 generation publish. W06.3 conflict resolve·ack. W06.4 결정적 문항 생성·제출·정오. W06.5 approval+membership 변경+audit. W06.6 보완요청→재응시→READY→승인 회귀.

예제 fixture는 5개 이상의 확정 근거, private 초안1개, 다른 프로젝트 확정1개, 기간밖1개를 포함한다. `scopeFrom=2026-08-20, scopeTo=2026-09-14`, time=2026-09-14T00:00Z로 고정한다. 승인 테스트 effectiveOn=2026-09-14. 과거 generation 점수5/5 + 현재0/5는 거부. snapshot5개에 다른 프로젝트/기간밖이 섞이면 실패. 같은 날 동시에 승인 요청2개는 completion/audit/membership 전환1회. 아직 미래 effectiveOn이면409. 조회가 진행률을 올리면 실패. 원문 source update는 기존 snapshot hash를 바꾸지 않아야 한다.

## W07. 실제 구조화와 검색

선행 W06. 읽기 README §7/8, C04/C09.

파일: `record/RecordStructurer.java`, `SpringAiRecordStructurer.java`, `qa/EvidenceRetriever.java`, `PgVectorEvidenceRetriever.java`, `jobs/EmbeddingHandler.java`, `config/AiConfiguration.java`, V9 vector migration(V8은 W06.4 assessment가 사용), `backend/src/test/resources/ai-evaluation/` 합성 데이터 및 `docs/backend-design/evidence/model-profile.md`.

인터페이스: `RecordStructurer.extract(SourceDocument)` → `List<DraftRecord>`(recordType/title/summary/originalExcerpt/occurredOn/ownerUserId nullable/dueOn nullable). principal/project/source는 모델 출력에서 받지 않는다. `EvidenceRetriever.retrieve(EvidenceQuery)` → `List<EvidenceChunk>`; query는 서버가 만든 actor/project/generation/question/limit, chunk는 snapshotRecordId/chunkId/text/sourceLabel. 최소 테스트 수집기는 stub, 실제 구현은 Spring AI.

W07.1 JSON schema 검증과 타입 실패. W07.2 모델 profile 선정: 사용자에게 연결된 local/provider 설정을 사용하되 embedding model ID·차원·버전을 실제 호출1회로 검증하여 profile에 고정한다. 모델 미설정이면 stub 작업까지 완료하고 실제모델 검증은 환경 차단으로 표시. W07.3 embedding jobs·버전 검증. W07.4 authorized retrieval+keyword fallback. 무조건 HNSW부터 생성하지 않는다.

테스트: invalid JSON → source FAILED, source본문 prompt injection → private/project scope 변경불가, vector down → keyword, stale embedding → 제외, 프로젝트 A/B 같은 키워드 → B근거0. 평균 구조화 처리시간·실패율을 측정한다. 모델이 제시한 unknown enum·unknown user는 임의로 맞춰 넣지 않는다.

## W08. Q&A와 streaming

선행 W07. 읽기 C07, README §7.

파일: `qa/QuestionController.java`, `QuestionService.java`, `GroundedAnswerGenerator.java`, `SpringAiAnswerGenerator.java`, `CitationValidator.java`, `QaHistoryRepository.java`; 테스트 `QaPrivacyIT`, `QaStreamingIT`, `QaRecoveryIT`.

W08.1 GET history 사용자격리. W08.2 POST JSON201와 서버 citation mapping. W08.3 SSE200 event 순서/terminal event/취소. W08.4 jobs와 미완성대화 복구. clock와 모델 fake는 테스트에 주입하며 실서비스 로직에 `if(question.includes(...))` 시나리오 분기를 넣지 않는다.

반례: 근거0→NO_EVIDENCE, 공급자실패→FAILED, 후보밖인용→게시거부, `meta`만 빨리 보낸 경우 TTFT 달성으로 세지 않음, disconnect후 worker 늦은출력→answer중복0, 타인history404, 권한철회 후 stream 다음chunk 전 차단. Spring Security thread context를 워커로 무심코 전달하지 않고 actor ID로 권한을 새로 검증한다.

## W09. 프론트엔드 HTTP 전환

선행 W08(일반업무 통합은 W06 후 부분 검증 가능). 읽기 frontend demo model/repository/store/router와 C01~C08.

파일: `frontend/src/api/http.ts`, `frontend/src/api/batonRepository.ts`, `frontend/src/stores/session.ts`, 기존 views의 명시적 호출부, 통합 E2E. mock 폴더는 `VITE_DATA_MODE=demo` 전용. production 기본은 `api`; API가 실패하면 demo로 자동 fallback하지 않는다. entity id는 안전정수 number, 화면 code는 string을 유지하는 mapper를 둔다. 화면마다 서버 규칙을 재구현하지 않는다.

W09.1 cookie/CSRF/Problem/If-Match/key 공통 client. W09.2 actor switch 제거한 실제 로그인과 권한. W09.3 목록page/cursor·record/note. W09.4 package polling·명시확인·준비도. W09.5 streaming fetch. E2E는 실제 Spring+PostgreSQL+stub AI를 사용하고 API 테스트 더블을 사용하지 않는다.

명령: `npm --prefix frontend ci`, `npm --prefix frontend test -- --run`, `npm --prefix frontend run build`, `npm --prefix frontend run test:e2e`(W09에서 실제 backend용 config 구분). fixture 프로젝트 REC-101~106의 최신 확정상태를 테스트 준비에서 명시한다. 과거 데모의 모든 자동진행을 실제 backend에 복제하지 않는다.

## W10. 성능·장애·복구 인수 시험

선행 W09. 읽기 README §8, 하네스 기준 문서(비공개) 하드닝 문서.

파일: `compose.yml`, `compose.dev.yml`, `backend/Dockerfile`, `infra/proxy/`, `backend/src/test/.../FailureIsolationIT.java`, `tests/load/baton.js`, `docs/backend-design/evidence/performance.md`, `docs/backend-design/evidence/recovery.md`. Dockerfile의 빌드 단계와 복사 경로는 Gradle Wrapper 및 `backend/build/libs`를 사용한다. 부하 도구는 k6의 버전/digest를 시험 결과에 기록한다.

W10.1 non-root/read-only/network/health/runtime limits. W10.2 합성 900k 데이터 생성 도구와 reproducible seed. W10.3 NFR 시나리오 일반30/60RPS·모델포화·벡터down. W10.4 DB백업→별도 빈volume 복원→업무조회·snapshot·jobs 결과 일치. 원본volume 삭제 금지. W10.5 측정 환경/latency/error/backlog/실패 case별 결과 기록.

고정 완료 조건: [README](README.md) §8의 모든 필수 수치와 데이터 불변식. 실제 모델이 없는 상태에서는 AI 지연 목표 미측정으로 남는다. 로컬 작은 데이터 테스트만으로 5,000명 지원을 주장하지 않는다.

### W10 보정(2026-10-13, 최종 코드 부하 재측정의 대장 157·158)
공개 표면 변경 없음. 처리는 개발 기록(비공개) 대장 157·158.
- **157:** `backend/Dockerfile`의 Gradle 캐시를 BuildKit cache mount에 둔다. 예전 의존성 층은 jar를 받지 않아 소스가 바뀔 때마다 Maven Central에서 다시 받았다.
- **160:** 부하 도구가 관측을 켜지 않은 실행에서 k6 직전에 멈췄다(빈 배열을 `set -u`에서 펼침, macOS bash 3.2). `tests/load/run-check.sh`가 두 실행 모드의 k6 인자를 확인한다.
- **171:** 이미지 빌드가 읽기 전용 Gradle 캐시(`BATON_GRADLE_RO_CACHE`, 보통 `~/.gradle/caches`)를 먼저 본다. Docker 빌드 캐시가 지워져도 Maven Central 없이 빌드된다. `tests/deploy/offline-build-check.sh`가 빈 캐시와 Maven Central 차단 상태에서 확인한다.
- **158:** 부하 도구가 askQuestion 409(패키지 재생성 중, OAS 선언)를 예상 응답으로 받는다.

## W10.6 성능 기준 충족

선행 W10(측정 완료, 60 RPS·soak 미달). 읽기: README §4.2·§6·§8, [contracts](contracts.md) C01·C09, `evidence/performance.md`의 「개선 설계를 위한 변경 전 측정」. 공개 API·OAS·SPEC.yaml 변경은 없다. DB 변경은 새 migration(V12)의 인덱스 추가뿐이다.

W10에서 DB CPU가 2코어 상한에 붙었다. 그 결과 Hikari pool 20이 고갈됐고, 5초 연결 대기 뒤 500이 났다. 이 카드는 원인을 순서대로 줄인다. 먼저 요청마다 드는 CPU를 줄이고(P1·P3·P5), 다음으로 AI 경로가 일반 API의 연결을 빼앗지 못하게 하며(P4), 마지막으로 soak 봉우리를 분해한다(P7).

**P1. 기록 가시성 predicate를 한 곳에 둔다.** `record/RecordVisibility`가 SQL 조각 하나를 만들고, 목록·count·단건·대시보드가 모두 그것을 쓴다(README §4.2).
- 조건은 두 부분이다. 활성 membership은 요청당 한 번만 평가한다(InitPlan). 가시성은 `wr.trust_status in ('CONFIRMED','CORRECTED') or wr.record_source_id in (행위자가 검토할 수 있는 이 프로젝트 연결의 source)`다. 두 번째 항은 비상관 부분질의라 요청당 한 번 해시로 계산된다. 연결 소유자 본인, 또는 공용 연결이면서 OUTGOING_OWNER·PROJECT_MANAGER인 경우가 여기에 든다. 부분질의에 `rs.project_id`·`sc.project_id` 조건을 넣는데, V1 복합 FK가 이미 보장하는 사실이라 결과 집합은 바뀌지 않는다.
- count는 표시용 조인을 하지 않는다. 목록은 표시용 조인(source·note·owner·confirmer)을 가시성 조건 밖에 둔다. `page.total`의 의미(권한 필터 뒤 정확한 개수, C01)는 그대로다.
- 변경 전 측정(4.55만 건 프로젝트, 부하 없음): count는 118~149ms에서 22~35ms로, shared buffers는 61,298에서 39,702로 줄었다. 목록 첫 페이지는 33~45ms에서 19~29ms로 줄었고 temp 파일을 쓰지 않게 됐다.
- 기각한 안:
  - 확정/초안 UNION ALL — count는 비슷하지만 목록이 196~318ms로 느려지고, 조건이 두 분기로 갈라진다.
  - 상관 EXISTS — 200ms대로 오히려 느리다.
  - 근사 total·total 생략·캐시 — C01 변경이 필요한데, 위 수치로 보면 필요하지 않다.
  - `work_records`에 연결·소유자 비정규화 — DBML 변경과 쓰기 경로가 늘어난다.
- 보류: 재시험이 여전히 미달일 때만 인덱스에 `record_source_id`를 INCLUDE해 count를 index-only로 만든다.

**P2. 대시보드 확인 대기 수 결함.** `pendingRecordCount`(요약과 프로젝트 카드)가 `author_id = 행위자`로 센다. 그런데 초안을 만드는 두 경로(`SourceStructuringHandler`, `ManualDraftService`)는 `author_id`를 채우지 않는다. 그래서 운영에서는 항상 0이다. P1 조각에 `AUTO_DRAFT`를 더해 센다. 즉 요약 값은 프로젝트별 `listRecords(trustStatus=AUTO_DRAFT)`의 `page.total` 합이다. `openRiskCount`(확정 OPEN_ISSUE만)는 확정 기록만 지식에 쓰는 신뢰 모델과 맞으므로 유지한다.

**P3. 인덱스(V12).**
- `work_records(project_id, occurred_on DESC, id DESC)`: DBML에는 선언돼 있고 migration에는 없다. 필터 없는 타임라인이 요청마다 4.5만 행을 정렬하는 문제를 없앤다.
- `jobs(kind, target_id, created_at DESC, id DESC)`: `releaseAbandonedSources`의 DISTINCT ON(현재 14.8만 행 seq scan, 8.7ms, 행 수에 비례)과 수집 스케줄러의 NOT EXISTS에 쓴다.
- 일반 `CREATE INDEX`로 만든다. Flyway가 트랜잭션 안에서 실행하기 때문이다. 90만 행 기준 쓰기 잠금이 수 초 걸리므로 배포 창에서 적용한다(V11은 1.2초였다).

**P4. AI 경로의 DB 격리(README §6 워커 pool 8 구현).**
- 채택: Spring `AbstractRoutingDataSource`로 API pool(20)과 AI pool(8)을 나눈다. 트랜잭션 관리자와 JdbcTemplate은 하나로 두므로 기존 `@Transactional` 코드는 바뀌지 않는다.
  - AI pool을 쓰는 맥락: JobSlots runner 스레드 전체, LeaseHeartbeat, QA 복구, 수집 스케줄러, Q&A 근거 검색 구간.
  - 그 밖(요청 처리, Q&A 접수·종결)은 API pool을 쓴다.
  - 연결은 트랜잭션을 시작할 때 정해진다. 따라서 맥락은 트랜잭션 밖에서 정하고, 트랜잭션 안에서 바꾸려 하면 실패시킨다.
- 기각: DataSource와 트랜잭션 관리자 두 벌에 qualifier를 붙이는 안. `JobLeaseService`·`JobRepository`처럼 양쪽 맥락에서 쓰는 코드를 둘로 나눠야 하고, 관리자를 잘못 고르면 조용히 섞인다. 워커 별도 프로세스도 기각한다. README §3의 전환 조건(측정된 큐 경합)을 채우지 않고, 배포 단위만 늘어난다.
- 한계와 보완:
  - pool 분리는 연결 고갈을 막을 뿐 DB CPU 포화는 막지 못한다. CPU는 P1·P3·P5로 줄인다.
  - AI pool 연결에는 `statement_timeout` 30초(= lease)를 둔다. AI 쿼리 하나가 lease보다 오래 연결을 쥐지 못하게 하기 위해서다.
  - API pool에는 두지 않는다. 느린 요청을 500으로 바꿀 뿐 부하는 줄이지 않고, 개선 뒤의 정상 쿼리는 ms 단위이기 때문이다.
  - AI의 DB 동시 사용은 이미 slot 10으로 묶여 있고, pool 8이 그 위에 상한을 하나 더 건다.
  - 연결 합계는 API 20 + AI 8 + Flyway 1로, `max_connections` 100 안에 든다.
- 함께 고치는 결함: `PgVectorEvidenceRetriever.retrieve()`가 `@Transactional(readOnly = true)` 안에서 임베딩 모델을 호출해, 모델 응답을 기다리는 동안 연결을 쥔다(C01·README §4.2 위반. stub은 즉시 답해 드러나지 않았다). 임베딩을 먼저 계산하고 DB 조회만 짧게 한다.

**P5. job 처리 비용과 보존.**
- 정리 작업의 위치: `claim()` 앞의 sweep 5개와 `releaseAbandonedSources`를 claim마다 돌리지 않는다. 새 `JobSweeper`가 dispatch tick마다(프로세스당 1초) 한 번 돌리고, 순서(sweep → release)는 지금과 같다. claim은 대상을 고르고 lease를 기록하는 일만 한다. 만료 lease를 회수하는 데는 최대 1 tick이 더 걸린다(README §6의 회수 규칙은 그대로다).
- 보존:
  - `jobs`는 성공 7일, 실패 30일이 지난 행을 지운다. 미종료 job은 지우지 않는다.
  - `idempotency_requests`는 `expires_at`이 지난 행을 지운다(C01 「보존 24시간」. 지금은 정리 코드가 없는 결함이다). 유효한 IN_PROGRESS 행은 지우지 않는다.
  - 두 가지 모두 5,000행 단위로 나눠 지운다.
- 스위치: `baton.retention.jobs.enabled`의 기본값은 false다. 운영 보존 정책이 아직 확인되지 않았으므로(개발 상태 기록(비공개) 미해결 항목), 승인 전에는 자동 삭제하지 않는다는 README §8을 따른다. compose.dev.yml과 부하 overlay에서만 true로 켠다. idempotency 정리는 계약에 정해진 값이므로 항상 켠다.
- 규모: 수집만으로 하루 약 34.6만 행이 쌓인다. 7일 보존이면 정상 상태가 약 240만 행이 되며, P3 인덱스가 조회 비용을 행 수와 무관하게 만든다.

**P6. job 실행기 종료(W10 리뷰 미결).** 종료할 때 dispatch를 멈추고, 실행 중인 job을 최대 25초 기다린 뒤 인터럽트한다. 이에 맞춰 compose app의 `stop_grace_period`를 30초로 둔다. 25초를 넘긴 job은 지금처럼 lease 만료 뒤 재시도된다.

**P7. soak 봉우리 분해.**
- 측정 방법: 시나리오는 그대로 두고 관측만 늘린다.
  - k6 요청별 결과를 CSV로 남긴다(`--out csv`).
  - `sample.sh` 간격을 5초로 줄인다.
  - `run.sh`는 마지막 INGEST 버스트에서 150초 뒤에 k6를 시작한다. 그러면 INGEST 버스트(앱 기동 기준 5분 주기)와 패키지 재생성(k6 시작 기준 5분 주기)이 서로 다른 창에 떨어진다.
  - 재시험 뒤 10초 창별 p95와 kind별 job 생성 시각을 겹쳐 본다.
- 조치 조건:
  - INGEST 버스트가 든 창에서만 일반 p95가 500ms를 넘으면, 스케줄러가 연결마다 `available_at`을 주기 안에 결정적 오프셋(연결 id 기반)으로 흩는다. 「5분마다 연결당 한 건」 규칙은 유지된다.
  - 패키지 재생성 창이 원인이면 설계로 돌아온다. 재생성은 제품 기능이라 임의로 늦추지 않는다.

**P8. 부하 도구.**
- 검토 초안 배분: `setup()`에서 10개 프로젝트의 AUTO_DRAFT를 검토자 권한으로 프로젝트당 1,000건씩 받는다. 반복마다 `exec.scenario.iterationInTest`번째 초안을 쓴다. 지금은 테스트 전체 VU 번호(`exec.vu.idInTest`)로 나눠 몫이 겹쳤고, 그래서 412가 16% 났다. 목표는 412 1% 미만이다.
- 측정 조건 유지: seed 20260928, 컨테이너 상한, 시나리오와 비율, VU 상한은 W10과 같다. 바꾸는 것은 관측(CSV, 5초 표본, 시작 위상)과 초안 배분뿐이다.
- 재시험 스택: 새 compose project와 새 volume에서 seed·prime부터 시작한다. W10 스택은 부하 뒤의 데이터라 비교 기준이 될 수 없다. 자원 생성과 부하 실행은 실행 시점에 사용자 승인을 받는다. 기존 Docker 자원은 그대로 둔다.
- 증거: `tests/load/explain.sql`이 이 카드의 EXPLAIN 전후를 재현한다.

**제외:**
- 재시도 backoff 1초·4초+jitter(README §6): 성능과 무관하고, 재시도 의미를 바꾸므로 자체 테스트가 필요하다. 미결을 유지하고 W11 판정 표에 올린다.
- ConflictResolution 잠금 순서: 프론트엔드 상호작용 결함이다. 부모가 재조회 완료를 알려 주는 약속을 설계해야 하므로 별도 카드로 한다.
- `failedJobCount`: 범위·기간·가시성을 먼저 정의해야 하고, 성능과 무관하다. 미결을 유지한다.
- 실모델 AI 지연: 모델이 없어 측정할 수 없다.

**수용 기준과 검증:**
1. 재시험(같은 seed·상한·시나리오) 전 구간에서 다음을 충족한다. 실패율 <1%(의도된 4xx 제외), 일반 p95 ≤500ms, 타임라인 p95 ≤1초, dropped 0, 권한 누출 0, 누락·중복 0(`verify.sql`). 412 비율은 따로 보고한다.
2. `RecordVisibilityIT`: 개인·공용 연결 초안, 메모 공유 기록, 네 신뢰 상태, PM·OUTGOING·INCOMING·MEMBER, 비구성원·퇴장 구성원·비활성 사용자/조직/프로젝트 조합에서 다음을 확인한다.
   - (a) `page.total` = cursor로 끝까지 받은 items 수
   - (b) 유형·상태·날짜 필터 조합마다 (a)가 성립
   - (c) 타인 개인 초안이 목록·count·단건·대시보드 어디에도 없음
   - (d) 비구성원 404
   - (e) 대시보드 `pendingRecordCount` = 프로젝트별 AUTO_DRAFT total 합

   (e)는 변경 전 코드에서 먼저 실패해야 한다.
3. `FailureIsolationIT`에 사례를 추가한다. AI pool 8개를 모두 점유한 상태에서 일반 API 30 RPS × 15초가 오류 0, p95 500ms여야 한다. 기존 사례는 스레드·slot 점유만 다루므로 이 사례가 필요하다.
4. 검색 트랜잭션 IT: 임베딩을 호출하는 순간 활성 트랜잭션도, 빌린 연결도 없어야 한다. 변경 전 코드에서 실패를 먼저 확인한다.
5. `JobRecoveryIT`의 기존 반례를 모두 유지하면서 sweep이 claim이 아닌 tick에서 도는지 확인하고, `RetentionIT`에서 다음을 확인한다: 기간이 지난 성공·실패 job만 삭제, 미종료 job 불변, 스위치가 꺼지면 0건, 만료된 idempotency COMPLETED 행 삭제, 유효한 IN_PROGRESS 행 유지. `JobSlotsTest`에서 종료 중에는 새 claim이 없고, 상한 안에서 기다린 뒤, 상한을 넘으면 인터럽트하는지 확인한다.
6. EXPLAIN 전후를 `performance.md`에 남긴다. 대상은 count, 목록(필터 없음·AUTO_DRAFT), 단건, 대시보드, `releaseAbandonedSources`, 수집 스케줄러다.
7. 전체 빌드, 프론트엔드 단위·실제 백엔드 E2E, 계약 검사기가 모두 통과해야 하고, gate BLOCK은 기존 수 이하여야 한다.

## W10.7 60 RPS 구간 안정화

선행 W10.6(커밋 bf1b84b..f6e09e5). 읽기: `evidence/W10.6.md`, `evidence/performance.md`의 「W10.6 재시험」「W10.6 soak 재시험」.

W10.6 뒤 두 번의 재시험에서 soak은 통과했지만, 60 RPS 일반 p95가 같은 코드에서 252ms와 600ms로 갈렸다. 평균 DB CPU는 54~77%로 여유가 있다. 문제는 짧은 급등(순간 200%)이 API pool 대기(최대 180건)로 번지는 것이다. 급등 표본에서 가장 오래 걸린 문장은 벡터 검색·타임라인 total·확인 대기 수였다. 그러나 벡터 검색은 평소 10ms, 초당 0.5건이고(읽기 전용 EXPLAIN, baton-w106c), 평소 0.06ms인 단건 조회도 급등 때는 수백 ms로 늘었다. 표본은 가장 오래 걸린 문장만 보여 줄 뿐, 어느 문장이 CPU를 쓰는지는 아직 측정되지 않았다. 그래서 이 카드는 측정 → 판정 → 조치 순서로 간다. 계약 변경이 필요한 조치는 판정 뒤 따로 승인받는다.

**R1. 문장별 비용 측정.**
- 부하 overlay에서만 `pg_stat_statements`를 켠다. db에 `shared_preload_libraries=pg_stat_statements`, `track_io_timing=on`을 주고, 확장은 업무 DB가 아닌 `postgres` DB에 만든다. Flyway가 소유한 스키마와 compose.yml은 바뀌지 않는다.
- run.sh가 구간 경계마다 문장별 스냅샷(queryid, calls, total_exec_time, rows, shared blocks)을 결과 폴더에 저장하고 초기화한다.
- 판정 근거는 60 RPS 구간의 누적 실행 시간 상위 문장과 그 점유율이다.
- 기각한 안:
  - pg_stat_activity 표본만 쓰기(현재 방식) — 빈도×비용을 볼 수 없다.
  - auto_explain — 느린 문장만 남고 부하를 더한다.
- 한계: 확장 자체의 부하(수 %)가 있다. 그래서 측정 모드에서만 켜고, 판정용 실행과 같은 조건에서 비교한다.

**R2. 반복 측정과 실행 간 편차.**
- 기준 스냅샷: seed·prime 직후 한 번 덤프한다(W10.4 restore-check와 같은 방식). 매 실행 전에 새 volume으로 복원해 같은 데이터에서 시작한다(복원 약 71초).
- 판정 실행:
  - 단축 시나리오(warm-up 5분 → 30 RPS 5분 → 60 RPS 15분) 3회
  - 전체 95분 1회
- 호스트 조건: 실행 전후의 전체 컨테이너 docker stats와 호스트 load average를 environment.txt에 남긴다.
- 스크립트 구조: 관리 시나리오는 역할(시스템 관리자/인수인계 참여자)을 테스트 전체 VU 번호(`exec.vu.idInTest`)로 고른다. 두 재시험에서 사용자 수정이 597건과 129건으로 크게 달랐다. 같은 번호의 VU가 실행마다 다른 시나리오를 맡은 결과로 추정하지만, k6의 VU 배정 방식은 확인하지 않았다. 추정과 무관하게 이 구조는 역할 분포를 실행에 맡긴다. 그래서 역할을 시나리오의 반복 번호로 고르고, VU마다 역할별 세션을 둔다. 결과에는 시나리오·역할·연산별 요청 수 표를 남긴다.

**R3. 조치 후보** (R1 판정 뒤 적용한다. 각각 EXPLAIN 전후 비교와 R2 재측정을 붙인다).
- 타임라인 total: `record_source_id`를 인덱스에 INCLUDE해 count를 index-only로 만든다. W10.6에서 조건부 승인된 안이다. DBML 표기가 바뀌므로 적용할 때 승인받는다.
- 확인 대기 수(대시보드 요약·프로젝트 카드·프로젝트 상세): 프로젝트마다 가시성 부분질의를 다시 계산한다. 후보는 두 가지다.
  - 한 요청 안에서 요약과 카드가 같은 프로젝트의 값을 공유
  - AUTO_DRAFT 부분 인덱스
- R1 상위 문장이 위 후보와 다르면 이 카드로 돌아와 후보를 고친다.
- 기각한 안:
  - total이나 확인 대기 수를 캐시로 제공 — C01의 「권한 필터 뒤 정확한 개수」를 바꾼다.
  - 앱·DB 컨테이너 상한 변경 — W10과의 비교 조건이 바뀐다.
  - 임계값 조정

**R4. 부하 도구의 세션 만료.** 한 VU가 여러 PM 세션을 쥐면, 30분 idle로 만료된 세션의 첫 요청 401이 실패로 집계됐다(soak 재시험 90건). 마지막 사용 뒤 25분이 지난 세션은 요청 전에 다시 로그인한다. 서버 동작과 무관한 도구 잡음을 실패율에서 뺀다.

**제외:**
- 벡터 검색 HNSW: 평소 10ms, 초당 0.5건이라 CPU 주범이 아니다. README §7에 따라 실모델 도입 때 평가한다.
- 임계값·컨테이너 상한 변경.
- W10.6에서 제외한 항목(재시도 backoff, ConflictResolution 잠금, failedJobCount).

**수용 기준:**
1. R1: 60 RPS 구간 문장별 누적 실행 시간 상위 10개와 점유율 표(performance.md).
2. R2: 단축 시나리오 3회 모두 60 RPS 기준 충족(일반 p95 ≤500ms, 타임라인 ≤1초, 실패 <1%, dropped 0). 전체 95분 1회는 전 구간 충족. 미달이면 수치를 그대로 기록하고 설계로 돌아온다.
3. 시나리오·역할·연산별 요청 수가 실행 사이에 ±5% 안이다.
4. 조치마다 EXPLAIN 전후를 남기고, 전체 빌드·실제 백엔드 E2E·RecordVisibilityIT가 계속 통과한다.

**자원:** 측정 스택 하나(`baton-w107`)를 두고, 실행마다 DB volume을 기준 덤프에서 새로 만든다. 실행마다 volume을 지우고 다시 만들므로, 실행 전에 이 스택에 한정한 삭제 권한을 받는다. 기존 스택과 volume은 건드리지 않는다.

## W11. 최종 검증과 다음 단계 인계

선행 W10.7. 하네스 기준 문서(비공개), 하네스 기준 문서(비공개)로 독립 검증을 수행한다. 해당 검증자는 실제 코드/테스트/계약을 보고 builder 결론을 신뢰하지 않는다. 검증 환경에서 독립 에이전트가 없으면 독립 검증 미실행으로 적는다.

확인: 52개 업무 operation의 구현·권한·오류·DTO 테스트 연결, OAS/SPEC/DB migration 일치, FR22개 직접 대응, source/record/project 소유권, 모든 상태전이, replay·lease·snapshot·grading, 실제 NFR·복구. 미사용 API·누락 endpoint를 표로 판정한다. GUI에서 한번 눌러봤다는 기록은 인수 시험을 대체하지 않는다.

`python3 tools/gate.py .`와 계약 검사기, `./backend/gradlew -p backend clean build`, 프론트엔드 통합 suite 결과를 첨부한다. 기존 게이트는 JVM 의존성 취약점·DB 필드 일치·권한 설계를 충분히 검사하지 않으므로 별도 dependency scan 결과와 통합 테스트를 함께 기록한다. 하네스의 일반 gate BLOCK은 오류별 처리 상태를 남기고 통과로 위장하지 않는다.

최종 판정은 `P0 기능 완료`, `P1 실제 AI 검증 완료`, `운영 연결/배포 준비 완료`를 분리한다. P0만 완성한 경우 서비스 전체 완료라고 쓰지 않는다. 상태·실행법·복구법과 변경된 설명 자료는 실제 구현에 맞춰 갱신한다.

## W11.1 독립 검증 지적 수정

선행 W11(커밋 368597d). 읽기: `evidence/W11.md`, 개발 기록(비공개)의 「검증 결과」와 피드백 대장 1~29. 설계 승인: 2026-10-03 사용자 결정(W11 보고의 권고안 전부 채택). W11에서 확인된 결함을 고치고 계약을 구현에 맞춘다. 공개 경로는 늘리지 않는다(SPEC.yaml 불변). 바뀌는 것은 응답 코드 선언, 목록 파라미터, 응답 필드, migration V15다. 이 카드가 끝나면 `/verify`를 다시 돈다.

**결정과 수용 기준(지적 번호는 피드백 대장 번호):**

| 지적 | 결정 | 수용 기준(테스트를 먼저 실패시킨다) |
|---|---|---|
| 1·27 | BUILD_PACKAGE를 실행하기 시작하면 handover와 현재 generation을 REQUESTED → GENERATING으로 바꾼다(README §5.2 표). dispatch 주기의 housekeeping이 버려진 인수인계를 FAILED로 돌린다. 대상은 REQUESTED·GENERATING이면서 현재 generation의 마지막 BUILD_PACKAGE job이 FAILED이고 살아 있는 job이 없는 건이다. failure_reason은 job의 failure_code다. 원본의 버려진 작업 회수(releaseAbandonedSources)와 같은 형태다. | job 3회 소진과 기한 초과 각각에서 sweep 뒤 handover·generation이 FAILED이고 failureReason이 MAX_ATTEMPTS_EXCEEDED·DEADLINE_EXCEEDED다. 그 뒤 retry가 202다. 실행 중에는 GENERATING이 관측된다. |
| 2 | 관계는 CONFIRMED·CORRECTED 기록끼리만 등록한다. 이미 다른 기록이 대체한 기록을 다시 대체하면 409 INVALID_STATE다. 둘 다 프로젝트 행 잠금 아래에서 검사한다(README §5.1). | AUTO_DRAFT·DISMISSED 연결과 이중 대체가 409이고 행이 생기지 않는다. |
| 3 | 대시보드 connectionHealth는 연결 목록과 같은 조건(`owner_user_id is null or = 요청자`)을 쓴다. 시스템 관리자 전체 보기는 S12 기준대로 둔다. | 다른 구성원의 개인 폴더 연결이 대시보드에 없다. |
| 4 | scopeFrom ≤ scopeTo, scopeRecordTypes는 비어 있지 않고 RecordType 값만 허용한다. 위반은 400이다. 요청의 includeNoteDerivedRecords가 없으면 true다(OAS 기본값). | 각 위반이 500이 아니라 400이다. 생략 시 true로 저장된다. |
| 5 | C05 그대로 구현한다. 요청과 재생성에서 snapshot 대상이 5,000건을 넘으면 409 SCOPE_TOO_LARGE이고 아무것도 쓰지 않는다. | 5,001건이면 409와 code SCOPE_TOO_LARGE, handover 행 0건. 5,000건은 202다. |
| 6 | 접근 가능한 프로젝트에서 PM 역할이 없으면 403 ACCESS_DENIED다. 비구성원은 404다(README §4.2). request·retry·regenerate·resolve·createRecordRelation을 approval과 맞춘다. | 비PM 구성원 403, 비구성원 404. |
| 7 | OAS가 실제 응답을 선언한다. 인증 operation 전부에 403(ACCOUNT_INACTIVE), 질의 파라미터가 있는 GET에 400, signOut에 401, 본문이 있는 operation에 413을 선언한다. listOrganizations·listSourceConnections에 limit·offset을 선언한다. 통합 테스트가 낸 모든 응답을 기록하고, 빌드의 `check` 단계에서 OAS와 대조한다. 대조 대상은 (method, path, status)와 2xx JSON 본문의 필수 필드·미선언 필드다. | 대조 작업이 선언 밖 응답을 하나라도 만나면 빌드가 실패한다(먼저 현 상태에서 실패 확인). |
| 8 | /me의 user에 authProvider·organizationName을 넣고 미선언 createdAt을 뺀다. request·retry·regenerate의 202 본문은 Handover 스키마 전체다. | 7의 대조 작업과 개별 IT. |
| 9 | 요청 본문 상한은 1 MiB다(C01). 앱 필터가 Content-Length와 실제 읽은 바이트로 막고 413 PAYLOAD_TOO_LARGE Problem을 낸다. proxy도 같은 상한을 둔다. | 1 MiB+1 바이트 본문이 413이다. 정확히 1 MiB 이하는 통과한다. |
| 10 | listPersonalNotes·listHandovers·listHandoverItems·listProjectMembers가 Limit·Offset을 받는다. page는 실제 total·limit·offset이다. ProjectMemberList에 page를 더한다. | 상한 100 초과 400. total과 page를 끝까지 넘긴 items 수가 같다. |
| 11 | 관리 화면 각 탭에 이전·다음 페이지를 둔다. listLimit.ts 주석을 실제 동작에 맞춘다. | 단위 테스트에서 101번째 사용자에 닿는다. |
| 12 | 화면에 넣는다. PM은 기록 상세에서 관계를 등록한다. 개인 폴더의 처리 실패 파일에서 연결 소유자가 수동 초안을 작성한다. | 단위 테스트와 실제 백엔드 E2E 1건씩. |
| 13 | HandoverDetail에 revisionComment(nullable)를 노출한다. 후임자 화면은 보완 요청 상태에서 이를 보여 준다. excluded_count는 V15로 지우고 OAS·화면 타입에서도 뺀다. | 보완 요청 뒤 getHandover에 사유가 있다. 스키마에 excluded_count가 없다. |
| 14 | 이미 해결된 충돌 항목을 다시 해결하면 409 INVALID_STATE다. | 두 번째 해결이 409이고 첫 해결이 유지된다. |
| 15 | 428은 모두 code IF_MATCH_REQUIRED다. 참조 충돌 Problem과 프로젝트 not-found 예외는 shared의 것 하나만 쓴다. 다른 도메인이 빌려 쓰던 400 예외는 shared InvalidInputException으로 옮긴다. IllegalArgumentException 일괄 400 매핑을 없앤다(내부 오류가 400으로 가려지지 않게). | 기존 IT의 428 code 단언이 IF_MATCH_REQUIRED로 통일된다. |
| 16 | 계정이 없거나 자격증명이 없어도 같은 비용의 해시 비교를 한 번 한다. 표준 DaoAuthenticationProvider를 쓰지 않는 사유는 다음과 같다. 그 provider는 비밀번호 확인 전에 비활성 여부를 검사해, 틀린 비밀번호로도 계정 상태가 드러난다. 또 조직 활성 여부를 보지 않는다. | 계정 없음 경로에서 encoder가 한 번 호출된다(단위 테스트). |
| 17 | 질문 접수 트랜잭션은 요청자별 advisory lock을 잡고 나서 횟수를 센다. | 동시 접수 두 건 중 상한을 넘는 쪽이 429다. |
| 18 | Spring Boot BOM 위에 tomcat-embed-* 11.0.26, jackson-bom 3.1.7 제약을 둔다. npm 개발 의존성은 `npm audit fix`로 갱신한다. | OSV 재조회 0건, npm audit 0건. |
| 19 | QaTestSupport의 비밀번호는 실행마다 무작위 값을 쓴다. docs/backend-design/W06.4-start-prompt.md를 지운다. | gate X1·H1에서 해당 경로가 사라진다. |
| 20 | DBML을 migration 결과에 맞춘다. SchemaIT가 Flyway 적용 뒤 DB의 테이블·컬럼·인덱스 열 목록을 DBML과 대조한다. | 대조 IT가 개정 전 DBML에서 실패하고 개정 뒤 통과한다. |
| 24 | 시작할 때 활성 SYSTEM_ADMIN이 없고 BATON_BOOTSTRAP_ADMIN_* 환경변수가 모두 있으면 관리자 1명을 만든다(조직이 없으면 함께). 값은 로그에 남기지 않는다. compose.yml은 이 변수를 선택적으로 넘긴다. | 변수 없음은 아무 일도 하지 않는다. 변수가 있으면 1회 생성한다. 관리자가 있으면 다시 만들지 않는다. |
| 25 | 종료된 배정에 같은 사용자를 다시 추가하면 그 배정을 되살린다(역할·joinedOn 갱신, leftOn 해제, version+1, 201). 진행 중 배정의 중복은 409 그대로다. | 종료 → 재추가 201, 현재 구성원으로 판정된다. |
| 26 | HandoverItem의 evidence(snapshot 기준 WorkRecord), assigneeName, dueOn을 채운다. WorkRecord·RecordDetail의 supersededByCode는 확정된 대체 기록의 code다. | 항목·기록 IT. |
| 28 | retry·regenerate·resolve·acknowledge에 MockMvc IT를 둔다. If-Match 없음 428, 오래된 버전 412, 같은 key 재생, ETag, 확인 201/200을 본다. | 4개 operation이 HTTP 계층 IT를 가진다. |
| 29 | 한 줄 메서드 파일을 표준 형식으로 다시 쓴다(동작 변경 없음). | 전체 빌드 통과. |

**기록:** 21(계약 문서 머리말의 "구현 전" 갱신)과 22(피드백 대장에 과거 지적 소급 등록, 명세 개정 이력, 1단계 게이트 예외 사유 갱신)를 이 카드에서 처리한다. 23(NFR-04 유입 처리량)은 측정하지 않은 상태로 둔다(W11에서 명시함).

**범위 밖:** 기록된 미결(재시도 backoff, ConflictResolution 잠금 순서, failedJobCount, HNSW), 실모델(P1), 운영 연동.

**검증:** 전체 빌드, 프런트엔드 단위·실제 백엔드 E2E·데모 E2E·빌드, 계약 검사기, OSV·npm 재검사, `gate.py . --commit` BLOCK 수가 기준선(9) 이하. 체크포인트에서 `/code-review` 1회, API·권한·입력 경로가 바뀌므로 `/security-review` 1회를 돌린다. 그 뒤 `/verify`를 격리 컨텍스트에서 다시 돈다.

## W11.2 W11.1 재검증 지적 수정

선행 W11.1(커밋 6bfed3b..a347e6a)과 그 재검증(378b967). 읽기: 개발 기록(비공개)의 피드백 대장 56~69와 「검증 결과」 2026-10-04, `evidence/W11.1.md`. 설계 승인: 2026-10-04 사용자 결정 「권고대로 진행」. 공개 경로는 늘리지 않는다(SPEC.yaml 불변, 경로 38·operation 52). 바뀌는 것은 해소 사유 상한(300자), AssessmentStatus enum 선언, 오류 설명, 검증 도구다. migration은 없다. 이 카드가 끝나면 `/verify`를 격리 컨텍스트에서 다시 돈다.

**결정과 수용 기준(지적 번호는 피드백 대장 번호, 테스트를 먼저 쓰고 실패를 확인한다):**

| 지적 | 결정 | 수용 기준 |
|---|---|---|
| 56 | requestHandover·retry·regenerate의 IdempotencyService authorize 자리에 현재 권한 검사를 넣는다. 형제 명령(resolve·acknowledge·approval·relation)과 같은 방식이다. request는 프로젝트 PM, retry·regenerate는 그 handover의 프로젝트 PM이다. | PM이 key K로 요청 → PM 역할 해제 → 같은 본문·K로 재전송하면 403이다. 비구성원은 404다. 세 operation 각각에 MockMvc IT를 둔다. |
| 57 | 해소 사유 상한을 DB 컬럼에 맞춰 300자로 한다. 앱 검증(HandoverItemController, HandoverConflictService), OAS ResolveHandoverItemRequest.resolutionNote maxLength 300, 화면 ConflictResolution.vue maxlength 300을 함께 바꾼다. migration은 없다. | 301자는 400, 300자는 200이다. responseContract와 화면 단위 테스트가 통과한다. |
| 58 | (a) 본문 정수 ID를 읽는 모든 곳을 shared 헬퍼 하나(정수 노드이고 canConvertToLong이며 1 이상 2^53-1 이하)로 바꾸고 범위 밖은 400이다. UserManagementService의 positiveLong 방식이 기준이다. (b) listRecords cursor 해석에서 DateTimeParseException도 400으로 바꾼다. | `99999999999999999999` ID와 occurredOn이 "x"인 cursor가 각각 400이다(대표 IT). 헬퍼를 쓰는 자리를 grep으로 확인한다. |
| 59 | 자체 검사기를 유지한다. 외부 OAS 검증 라이브러리를 채택하지 않는 사유를 contracts.md C11과 클래스 Javadoc에 적는다(새 의존성 다운로드 회피, OAS 3.0 nullable·allOf 규칙을 이미 다룸). ① 관측 0건이면 실패. ② operation 중 관측되지 않은 것이 있으면 실패. ③ 500 관측은 위반이다. 의도된 장애 주입은 사유를 적은 허용 목록 파일(테스트 리소스)로만 예외로 둔다. ④ 검사하지 않는 범위(파라미터, 헤더, maxLength·format, MockMvc 밖 HTTP)를 Javadoc에 적는다. | ResponseContractCheckTest에 ①②③ 사례를 더하고 먼저 실패시킨다. 전체 빌드에서 위반 0. |
| 60 | DbmlConsistencyIT를 넓힌다. 인덱스의 unique 여부, 컬럼 NOT NULL, varchar 길이를 비교한다. FK·CHECK·enum 값은 범위 밖이라고 Javadoc에 적는다. 드러나는 DBML 차이는 migration 쪽에 맞춰 DBML을 고친다. | 개정 전 DBML에서 새 비교가 실패하거나, 차이가 없으면 의도적으로 틀린 사례로 실패를 확인한다. 개정 뒤 통과한다. |
| 61 | REQUESTED → GENERATING에서도 handover version을 1 올린다(다른 전이와 대칭). | HandoverRecoveryIT에서 begin 뒤 version이 1 오른다. 기존 IT의 ETag·If-Match 단언을 맞춘다. |
| 62 | problemText.ts에 MAX_ATTEMPTS_EXCEEDED·DEADLINE_EXCEEDED(생성 실패 사유), SCOPE_TOO_LARGE(범위를 좁히라는 안내), PAYLOAD_TOO_LARGE 문구를 넣는다. 죽은 PRECONDITION_REQUIRED 매핑을 지운다. OAS getHandover 설명에 GENERATION_ABANDONED를 더한다. | 화면 단위 테스트가 각 code의 안내 문구를 확인한다. |
| 63 | advisory lock의 상수 결합 SQL 2곳(AdminBootstrap, JobRepository)을 바인딩으로 바꾼다. | AdminBootstrapIT와 ErrorContractIT(큐 포화)가 통과한다. |
| 64 | 판정이 확정된 뒤(/verify 뒤) 루트 README·backend/README의 상태(Flyway 범위, 판정, 남은 결함)를 실제에 맞춘다. contracts.md 제목의 「구현 전」을 고친다. | 문서와 HEAD가 일치한다(검증자가 확인). |
| 65 | JobLeaseService.claim()(kind 없음)은 지우고 테스트 14곳을 claim(kind)로 바꾼다. JobSlots.availableSlots는 FailureIsolationIT 등이 포화를 관측하는 데 쓰므로 유지하고 그 사유를 Javadoc에 적는다. assessment IN_PROGRESS는 OAS AssessmentStatus enum(과 화면 타입)에서 빼고 DB enum 값은 남긴다. PostgreSQL enum 값 삭제는 타입 재생성이 필요하기 때문이다. 이 사유를 contracts.md C11에 적는다. | 컴파일·전체 빌드 통과. gate S1에서 claim이 사라진다. responseContract가 통과한다. |
| 66 | contracts.md C10 대응표에 getDashboard(FR-01·09·22, S02)를 더한다. | 문서 |
| 67 | 개발 기록(비공개) 게이트 예외 목록에 X2를 적는다. 하네스 도구 gate.py·test_gate.py의 subprocess shell=True이며 산출물 코드가 아니다. npm audit을 다시 실행하고 결과 요약(명령·exit·건수)을 evidence/W11.2.md에 남긴다. | 문서 |
| 68 | 접근 가능한 프로젝트에서 지정한 사용자(outgoing·incoming owner, ownerUserId)가 현재 구성원이 아니면 400 INVALID_INPUT이다. errors에 필드 이름을 담는다. 프로젝트 자체가 안 보이면 404 그대로다. OAS 설명을 갱신한다. | requestHandover와 createManualDraft에서 비구성원 사용자 ID는 400(필드 이름 포함), 비구성원 요청자는 404다. |
| 69 | 코드·설정·migration 주석의 작업 카드 번호를 원인 → 과정 → 결론의 문장으로 다시 쓴다. 근거가 계약 절이면 README §·C번호만 남긴다. migration 파일 이름은 바꾸지 않는다. | `grep -rnE "\bW[0-9]{2}(\.[0-9])?\b" backend/src infra compose*.yml tests frontend/src` 결과가 0이다. 테스트 이름·evidence·work-orders는 대상이 아니다. |
| 제안 | 재생성 경로의 SCOPE_TOO_LARGE 회귀 테스트와, int 범위를 넘는 사용자 ID의 질문 접수 테스트(지적 50 보강)를 더한다. | 해당 IT |

**설계 검토에서 정한 세부(표를 넓히지 않는다):**
- 58의 헬퍼 대상은 본문 정수 ID를 읽는 9곳이다: PersonalNoteService, RecordRelationService, RecordReviewService, ManualDraftService, HandoverRequests, ProjectMemberService, AssessmentController(questionId), DraftRecordParser(모델 출력의 ownerUserId), UserManagementService. 각 도메인의 400 예외는 그대로 두고 검사 조건만 헬퍼로 모은다. 헬퍼는 소수(`1.5`)와 문자열도 거른다. cursor의 id도 같은 헬퍼를 쓴다(질문 목록 cursor 포함).
- 56의 검사 자리는 형제 중 relation·note·connection이 쓰는 방식, 곧 Request의 authorize 칸이다. resolve·acknowledge·approval은 execute 앞에서 같은 검사를 호출하므로 동작이 같고 바꾸지 않는다.
- 59의 허용 목록은 지금 관측되는 의도된 500 두 건(approval의 감사 로그 장애 주입, listHandovers의 테이블 이름 변경)만 담는다.
- 68의 `errors`는 기존 Problem 구조의 FieldError(field, message)를 쓴다. 새 응답 필드는 없다.

**오류 응답·권한 점검(design-review-checklist):** 같은 성격의 실패는 같은 코드로 나간다(범위 밖 정수·cursor·구성원 아닌 지정 사용자 모두 400 INVALID_INPUT, 해소 사유 상한 초과도 400). 권한이 바뀐 PM의 재전송은 403, 비구성원은 404로 README §4.2와 같다. 새 공개 표면은 없다.

**기록:** 64는 /verify 뒤, 66·67은 문서 작업이다.

**범위 밖:** 기록된 미결(대장 23, 41~45, 재시도 backoff, ConflictResolution 잠금 순서, failedJobCount, HNSW, 실모델 P1, 운영 규정 라벨, 일반 계정 자격증명 발급). 부하 재측정은 하지 않는다.

**검증:** 전체 빌드(responseContract 포함), 프런트엔드 단위·실제 백엔드 E2E·데모 E2E·빌드, 계약 검사기와 그 단위 테스트, OSV·npm 재검사, `gate.py . --commit` BLOCK 수가 기준선(8) 이하. 체크포인트에서 `/code-review high`를 커밋 범위 378b967..에 1회, `/security-review`를 시도한다. 그 뒤 `/verify`를 격리 컨텍스트에서 돈다.

## W11.2.1 W11.2 검증의 설계 지적 77·84

선행 W11.2(커밋 a78544d). 설계 승인: 2026-10-05 사용자 지시 「77·84는 진행해」. 공개 경로는 늘리지 않는다(SPEC.yaml 불변). migration 없음. 지적 70은 같은 지시로 예외 확정이다(migration 주석은 고치지 않는다).

| 지적 | 결정 | 수용 기준(테스트를 먼저 쓰고 실패를 확인한다) |
|---|---|---|
| 77 | 요청자가 이미 볼 수 있는 프로젝트에서 요청이 지정한 사용자가 현재 구성원이 아니면 어느 operation이든 400 INVALID_INPUT이고 errors에 필드 이름을 담는다. W11.2의 requestHandover·createManualDraft에 더해 sharePersonalNoteSummary(ownerUserId)와 reviewRecord(CORRECT의 ownerUserId)에 적용한다. requestHandover의 outgoing와 incoming이 같으면 409 INVALID_STATE가 아니라 400(필드 incomingOwnerId)이다. 요청 본문 자체의 모순이고 상태 충돌이 아니기 때문이다(C01의 「같은 성격의 실패는 같은 코드」). 문구와 생성은 InvalidInputException의 정적 팩토리 하나로 모은다. | 노트 공유와 기록 정정에서 비구성원 ownerUserId는 400과 필드 이름, 요청자가 비구성원이면 404 그대로. 같은 owner 요청은 400. OAS 설명 갱신. |
| 84 | AssessmentService의 자체 isManager를 지우고 ProjectAccessPolicy.isManager를 쓴다. 앞선 requireMember가 같은 활성 조건을 이미 요구하므로 동작은 같다. | 동작 보존 리팩터링이라 새 실패 시험은 없다. 관리자 조회 200·일반 구성원 403을 고정하는 시험을 더하고(구현 전부터 통과), 전체 빌드가 통과한다. |

**범위 밖:** 지적 70(예외), 기록된 미결 전부.

## W12. 관리자가 만든 계정의 초기 자격증명 발급

선행 W11.2.1. 설계 승인: 2026-10-05 사용자 결정(코드 유효 24시간, 비밀번호 최소 12자, 본인 비밀번호 변경 API 제외, 멱등 예외 모두 승인). 읽기: README §4.1(「관리자 생성 계정의 로그인 활성화는 별도 초기 자격증명 준비 전까지 차단한다」), contracts.md C01·C02, 개발 기록(비공개) 피드백 대장의 기록된 미결(관리자가 만든 일반 계정의 초기 자격증명 발급).

### 문제
`POST /admin/users`로 만든 계정은 `credential_hash`가 null이라 로그인할 수 없다(`LocalAuthenticationService`는 해시가 없으면 로그인을 거절하고, 화면은 「자격증명 준비 전」으로 보여 준다). 비밀번호를 정하는 경로가 환경변수 부트스트랩(최초 관리자 1명)뿐이라, 관리자가 만든 일반 사용자는 영원히 로그인할 수 없다. FR-18(사용자 관리)과 FR-01(로그인)이 그 지점에서 이어지지 않는다. 같은 경로가 비밀번호 분실 때의 재설정도 해결해야 한다.

### 검토한 방식
| 방식 | 장점 | 단점 | 판정 |
|---|---|---|---|
| A. 관리자가 임시 비밀번호를 정해 넘긴다 | 가장 단순 | 관리자가 사용자의 비밀번호를 알게 된다(그 계정으로 한 행위를 부인할 수 있다). 임시 값이 채팅·메일에 남는다. 최초 로그인 때 변경 강제 장치가 따로 필요하다 | 기각 |
| B. 일회용 설정 코드(채택) | 관리자는 코드만 알고 비밀번호는 본인만 안다(다만 코드를 가진 사람은 누구나 그 계정의 비밀번호를 정할 수 있어 발급한 관리자도 포함된다 — 잔여 위험, W13.1·C11 「W13.1 개정」·A-11). 코드는 짧게 살고 한 번 쓰면 끝난다. 재설정에도 같은 경로를 쓴다 | 새 표 하나와 공개 경로 2개가 늘어난다. 코드 전달(메일·메신저)은 범위 밖이라 관리자가 직접 전한다 | **채택** |
| C. OIDC(SSO) | 비밀번호를 우리가 갖지 않는다 | 고객사 IdP가 있어야 한다. P2 | 후속 |
| D. Spring Security의 `OneTimeTokenService`(`JdbcOneTimeTokenService`) | 표준 모듈이다. 일회용 토큰의 발급·소비를 이미 한다 | 일회용 토큰으로 곧 로그인하는 흐름(`OneTimeTokenAuthenticationFilter`)을 위한 것이다. 이 카드는 「코드로 비밀번호를 정한다」이고 자동 로그인하지 않으며, 사용자별 유효 코드 1건·재발급 시 이전 코드 무효·해시만 저장·24시간, 소비와 같은 트랜잭션에서의 비밀번호 갱신과 세션 종료, 로그인과 같은 실패 제한기, 계정 열거 방지(없는 사번도 같은 응답과 같은 비용)가 필요하다. 표준 서비스가 이 요구를 얼마나 채우는지는 실행으로 확인하지 못했다 | 미채택 — 요구가 코드를 소비하는 쪽(비밀번호 설정)에 몰려 있어 직접 구현했다. 사유 기록은 검증(지적 103)이 요구했다. 표준 서비스가 요구를 채운다고 확인되면 교체를 다시 검토한다 |

### 결정과 수용 기준
| # | 결정 | 수용 기준(테스트를 먼저 쓰고 실패를 확인한다) |
|---|---|---|
| 1 | **발급:** `POST /api/v1/admin/users/{userId}/credential-tokens`(SYSTEM_ADMIN). 코드는 256비트 난수(base64url)이고 **응답에 한 번만 평문으로 나간다.** DB에는 SHA-256 해시만 둔다(난수 자체가 고엔트로피이므로 비밀번호용 느린 해시가 필요 없다). 사용자별 유효한 코드는 하나라서 다시 발급하면 이전 코드는 무효다. 유효 시간은 기본 24시간(`baton.auth.credential-token-ttl`) | 비관리자 403·비구성원 해당 없음. 대상이 없으면 404 USER_NOT_FOUND. 비활성 사용자나 LOCAL이 아닌 계정은 409 INVALID_STATE. 재발급하면 이전 코드로 설정이 실패한다. DB에 평문이 없다 |
| 2 | **설정:** `POST /api/v1/auth/credentials`(인증 불필요, CSRF는 로그인과 같이 적용). 본문은 employeeNo·token·newPassword. 성공하면 204이고 **자동 로그인하지 않는다.** 코드는 사용되고, 그 사용자의 기존 세션은 모두 폐기한다(재설정 때 분실한 비밀번호의 세션을 끊는다) | 올바른 코드로 비밀번호가 정해지고 그 비밀번호로 로그인된다. 같은 코드를 다시 쓰면 실패한다. 만료·잘못된 코드·다른 사용자의 코드·없는 사번은 **모두 같은 응답**(400 `INVALID_CREDENTIAL_TOKEN`)이고 계정 존재 여부를 드러내지 않는다. 기존 세션이 끊긴다 |
| 3 | **남용 방지:** 설정 경로는 로그인과 같은 실패 제한기(5회/5분, 사번+주소)를 쓰고 한도를 넘으면 429 `AUTH_RATE_LIMITED`다. 없는 사번도 같은 비용의 해시 비교를 한 번 한다(로그인의 계정 열거 방지와 같다) | 6번째 실패가 429. 없는 사번과 틀린 코드의 처리 시간 차이를 단위 시험으로 고정(해시 비교 횟수) |
| 4 | **비밀번호 규칙:** 길이 12~128자, 사번과 같으면 거부, 구성 규칙(대소문자·기호 강제)은 두지 않는다(NIST SP 800-63B의 방향). 회사 정책이 정해지면 `baton.auth.password-min-length` 한 곳만 바꾼다. 해시는 기존 `DelegatingPasswordEncoder` | 11자와 129자와 사번은 400 INVALID_INPUT(필드 newPassword). 12자는 통과 |
| 5 | **멱등 예외:** 발급 경로는 `Idempotency-Key`를 받지 않는다. 멱등 재전송은 응답 본문을 `idempotency_requests`에 저장하는데, 여기에는 평문 코드가 들어가기 때문이다. 발급은 호출마다 새 코드를 내고 이전 것을 무효화하는 것이 자연스러운 의미이기도 하다. C01의 「모든 명령은 멱등」에 대한 예외로 C11에 사유를 적는다. 설정 경로도 멱등 키가 없다(코드 한 번 사용이 곧 멱등 장치다) | OAS에 키 파라미터가 없다. 계약 검사기 통과 |
| 6 | **감사:** `ISSUE_CREDENTIAL_TOKEN`(대상 사용자), `SET_CREDENTIAL`(W13.1에서 두 행에 요청 주소를 더했다). 코드와 비밀번호는 감사·로그·응답 어디에도 남기지 않는다 | 감사 행 존재, 평문 부재(DB 전체 검색 시험) |
| 7 | **DB:** migration V16 `credential_tokens(id, user_id FK, token_hash char(64) unique, expires_at, used_at, created_by, created_at)`, 사용자별 미사용 코드는 부분 unique 인덱스로 하나. 만료·사용된 행은 기존 보존 정리(`RetentionSchedule`)가 지운다. DBML과 `DbmlConsistencyIT`를 맞춘다 | DbmlConsistencyIT, 정리 시험 |
| 8 | **화면:** 관리 화면 사용자 표에 LOCAL 계정마다 「설정 코드 발급」 버튼. 눌러서 나온 코드와 만료 시각을 **한 번만** 보여 주고(다시 볼 수 없다는 안내, 복사 버튼) 닫으면 화면에서 지운다. 로그인 화면에 「설정 코드로 비밀번호 정하기」 경로(사번·코드·새 비밀번호 두 번). demo 모드는 같은 흐름을 메모리로 흉내 낸다 | 화면 단위 시험, 실제 백엔드 E2E 1건(관리자가 계정을 만들고 코드를 발급하면 그 사용자가 설정한 뒤 로그인한다) |

### 공개 표면과 계약
- 경로 2개(`/admin/users/{userId}/credential-tokens` POST, `/auth/credentials` POST), operation 52 → **54**, paths 38 → **40**. `SPEC.yaml`·OAS·C10 대응표(FR-01·FR-18)·명세 개정 이력에 같은 날 반영한다. 사용자가 이미 승인한 요구사항(FR-18의 사용자 관리, README §4.1이 미뤄 둔 자격증명 준비)을 채우는 **설계 누락 보완**이라 개정 이력 사유는 이 둘이다.
- `User.loginEnabled`의 의미는 그대로다(해시가 있는가). 발급 대기 상태는 새 필드 없이 화면이 방금 받은 응답으로만 안다.

### 설계 검토(design-review-checklist)
- **형제 대칭:** 사용자 관리 하위 연산이다. 같은 축의 조직·프로젝트에는 자격증명 개념이 없다. 비대칭의 사유는 사용자에게만 로그인 주체성이 있기 때문이다.
- **오류 응답:** 같은 성격의 실패는 같은 코드다. 설정 경로의 모든 코드·사용자 실패는 400 하나라서 응답으로 구분되지 않는다. 한도 초과는 로그인과 같은 429다.
- **권한 표면:** 발급은 SYSTEM_ADMIN만. 설정 경로는 코드를 가진 사람만 쓸 수 있고, 코드는 발급받은 그 사용자에게만 유효하다(코드 해시 조회 뒤 사용자 ID 일치를 확인).
- **미사용 코드:** 새로 정의하는 모든 것이 호출된다.
- **배포 경계:** 설정 경로는 인증 전 공개 경로이므로 proxy의 본문 상한·제한이 그대로 적용된다.

### 범위 밖
이메일·메신저 전달, 「비밀번호를 잊었어요」 셀프서비스(관리자 재발급으로 갈음), 본인의 현재 비밀번호를 아는 상태의 변경(`POST /auth/password`; 재설정 경로로 갈음하고 필요해지면 후속), MFA, 계정 잠금, OIDC.

### 승인된 값
코드 유효 24시간, 비밀번호 길이 12자, 「본인 비밀번호 변경 API를 이번에 두지 않는다」는 결정, 멱등 예외. 규칙(길이·유효 시간)은 설정값이라 나중에 바꿔도 구조는 그대로다.

**검증:** 전체 빌드, 계약 검사기, 프런트엔드 단위·실제 백엔드 E2E·데모 E2E·빌드, OSV·npm 재조회, gate BLOCK 수가 기준선(8) 이하. 체크포인트에서 `/code-review`와 인증 경로가 바뀌므로 `/security-review`(origin이 없으면 수동 점검)를 돈다.

## W12.1 업무일 단일 시간대

선행 W12. 설계 승인: 2026-10-05 사용자 결정(「업무 시간대는 단일 시간대로 결정지어. 해외출장근무를 나가면 어차피 네트워크망 자체가 달라지기 때문에 별도로 고민해야 하는 문제」). 지적: 기록된 미결 대장 44(업무일 판정이 DB 세션 시간대에 의존). 공개 표면은 바뀌지 않는다(SPEC.yaml 불변).

| 결정 | 수용 기준 |
|---|---|
| 업무일은 단일 시간대 Asia/Seoul이다. Java의 「오늘」은 `shared/BusinessCalendar` 하나에서 얻고(`LocalDate.now()` 호출을 지운다), 모든 DB 연결은 풀마다 초기 SQL `SET TIME ZONE 'Asia/Seoul'`로 시작한다. 시간대는 설정값으로 열지 않고 상수 하나다(사용자·프로젝트별 시간대는 이번 범위 밖) | JVM 기본 시간대가 UTC여도 세 풀(API·worker·control)의 `show timezone`이 Asia/Seoul이다(BusinessCalendarTest, DatabaseTimeZoneIT). JVM이 다른 시간대여도 BusinessCalendar.today()가 서울 날짜다 |

**근거:** PostgreSQL 드라이버는 세션 시간대를 서버가 아니라 JVM 기본 시간대로 연다. compose가 `TZ: Asia/Seoul`을 주어 지금은 맞지만, TZ 없이 기동한 서비스(컨테이너 기본은 UTC)는 `current_date`가 서울보다 아홉 시간 늦어 오늘 배정된 구성원이 09:00까지 구성원이 아니다.

## W12 완료 기록(2026-10-05)

구현: V16 `credential_tokens`, `CredentialService`·`CredentialController`, OAS 두 경로·스키마 둘, DBML·SPEC·C10/C11, 관리 화면의 발급 버튼과 일회 표시, `/credentials` 화면, demo 저장소, 계약 검사기(`AUTH_EXEMPT_WRITES`, 40/54/85). 시험: 백엔드 `CredentialIT` 9건·`CredentialServiceTest` 2건·`RetentionIT` 1건, 프런트엔드 단위 11건(http·mapping·router·관리 화면·설정 화면·demo), 실제 백엔드 E2E 1건. 결과는 `evidence/W12.md`.


## W13. 실제 AI 모델 연결과 평가

선행 W12.1. 설계 승인: 2026-10-05 사용자 결정(「W13 카드 승인」, API 키는 나중에 사용자가 넣는다, RPO 24시간·RTO 4시간 그대로 적용). 읽기: `evidence/model-profile.md`(stub 프로필과 실모델 도입 절차 6단계), README §6·§8(AI 처리량·지연 목표), `docs/backend-design/assumptions.md` A-04·A-09. 사용자 결정(2026-10-05): 모델은 갈아 끼울 수 있게 두고 OpenAI(SKALA 제공 키)·Google(무료 키)·로컬 Ollama 세 가지를 필요에 따라 바꿔 쓴다. 평가는 합성 데이터와 비용 상한 안에서만 한다.

### 방식
**Spring AI를 그대로 쓴다. LangChain은 쓰지 않는다.** 이유: 구조화·Q&A·임베딩 어댑터가 이미 `ChatModel`·`EmbeddingModel` 인터페이스에만 의존하고(`SpringAiRecordStructurer`, `SpringAiAnswerGenerator`, `SpringAiEmbeddingClient`), OpenAI·Google GenAI(Gemini)·Ollama 모두 Spring AI의 공식 starter가 있어 프로필 설정으로 고른다. LangChain(Python)을 쓰려면 FastAPI 서비스 하나가 늘어 서비스 간 인증·장애 처리·배포가 두 벌이 되고, 설계 확정 사항(「FastAPI는 초기 아키텍처에 넣지 않는다」)과 어긋난다. LangChain 자체를 써 보는 것이 목적이면 기본 경로 밖의 실험으로 분리한다.

### 결정과 수용 기준
| # | 결정 | 수용 기준(테스트를 먼저 쓰고 실패를 확인한다) |
|---|---|---|
| 1 | 세 공급자 starter를 의존성에 더한다(`backend/DEPENDENCIES.md`에 좌표·버전·사유). 새 의존성은 내려받기 전에 사용자 승인을 받는다. 공급자는 `BATON_AI_PROVIDER=openai\|google-genai\|ollama`(채팅과 임베딩을 따로 고르려면 `BATON_AI_CHAT_PROVIDER`·`BATON_AI_EMBEDDING_PROVIDER`)와 모델 ID 환경변수로 고른다. `stub`은 기본으로 남는다 | 프로필마다 가짜 `ChatModel`·`EmbeddingModel` bean으로 기동되고, 필요한 값이 없으면 이름을 담아 기동이 멈춘다(기존 규칙). 공급자를 바꿔도 어댑터 코드는 같다 |
| 2 | **키는 환경변수로만 받는다.** 사용자가 `~/baton-secrets/ai.env`(권한 600)에 `OPENAI_API_KEY`·`GOOGLE_API_KEY`를 직접 넣고, compose와 로컬 실행이 그 파일을 읽는다. 값은 저장소·대화·로그·감사에 남기지 않으며 설정 로그에는 변수 이름과 설정 여부만 낸다 | gate X1 0건. 부팅 로그에 키 값이 없다(시험으로 로그 전체를 검사) |
| 3 | **임베딩 차원은 256으로 유지한다.** 공급자마다 차원 축소를 요청한다(OpenAI `dimensions`, Google 출력 차원, embeddinggemma는 절단 후 다시 정규화). 실제 호출 1번으로 차원과 노름을 확인해 고정한다. 임베딩 행에는 모델 ID가 이미 있어 모델이 다른 벡터는 섞어 검색하지 않는다. **임베딩 모델을 바꾸면 기록을 다시 임베딩하는 일괄 작업이 한 번 필요하다**(이번 범위에 그 작업의 실행 경로를 넣는다) | 256보다 긴 벡터는 앞 256개로 자르고 다시 정규화하며 더 짧은 벡터는 받을 때 거절한다(기동 때는 모델을 호출하지 않으므로 기동이 멈추지 않는다 — 구현에서 바뀐 점이다). 모델을 바꾼 뒤 이전 모델의 벡터가 검색에 나오지 않는다 |
| 4 | **Ollama(qwen3:4b)의 「생각」 출력을 끈다.** `<think>` 블록이 섞이면 엄격한 JSON 파서가 거절한다. 끄는 옵션이 효과가 있는지는 실제 호출로 확인한다. 파서는 느슨하게 하지 않는다(model-profile 절차 5) | 실모델 호출 평가에서 형식 실패율을 측정해 기록. 코드 펜스·생각 블록이 섞인 출력은 거절된다(기존 시험) |
| 5 | **평가 세트와 측정.** 합성 문서 약 50건과 질문 약 30건(근거 있음·없음·충돌·프롬프트 주입 시도)을 만든다. 공급자별로 구조화 성공률·평균/최대 처리 시간·검증기 거절 사유 분포, Q&A 인용 검증 통과율·무근거 응답 정확도·첫 답변/전체 지연, 임베딩 검색 recall을 잰다. 결과는 `evidence/model-profile.md`에 공급자별 표로 남긴다 | 평가 도구가 같은 입력으로 세 공급자를 돌려 같은 지표를 낸다. 비용 상한 초과 전에 멈춘다 |
| 6 | **비용 상한.** 한 번의 평가에서 쓸 토큰을 설정값으로 건다(채팅 100만 토큰, 임베딩 100만 토큰이 기본). 호출 전에 누적치를 확인하고 넘을 것 같으면 중단하며, 실제 사용 토큰을 응답 메타데이터로 기록한다. 외부 단가는 확인해서 적는다(제가 알 수 없는 값은 비워 두고 표시한다) | 상한을 낮게 준 시험에서 평가가 도중에 멈추고 이유를 남긴다 |
| 7 | **외부 전송은 합성 데이터에 한정한다.** OpenAI·Google로 보내는 모든 입력은 평가 세트에서만 나오고, 실행 전에 보낼 입력의 출처가 합성임을 검사한다. 사용자가 이 전송을 승인한다(2026-10-05 「합성데이터 + 비용상한으로 가자」) | 합성 표식이 없는 입력은 외부 프로필에서 거절된다 |
| 8 | **부하 시험은 실제 모델로 돌리지 않는다.** 무료 키의 호출 한도와 비용 때문이다. AI 처리량은 소규모 실측의 평균 처리 시간으로 README §8의 계산식에 넣어 외삽하고 그 사실을 적는다 | 문서 |

### 공개 표면과 계약
없음. 경로·operation·스키마·migration이 바뀌지 않는다(SPEC.yaml 불변). 설정값과 평가 도구(테스트 코드와 문서)만 늘어난다. 임베딩 모델 교체용 재임베딩 실행 경로는 내부 작업(job)이다.

### 설계 검토
- **오류 응답:** 공급자 오류는 기존대로 `MODEL_UNAVAILABLE`(재시도 가능)으로 모이고 키 값·공급자 응답 원문은 오류에 싣지 않는다.
- **권한 표면:** 변경 없음. 프롬프트 주입 방어(경계 태그, 인용 서버 재구성)는 그대로이고 실모델에서도 평가한다.
- **미사용 코드:** 쓰지 않는 프로필을 만들지 않는다. 세 프로필 모두 평가로 호출된다.

### 먼저 확인할 것(승인 뒤 첫 작업)
1. 키 파일 존재와 이름(값은 읽지 않는다). 2. Ollama 모델 실행과 생각 출력 끄기. 3. 세 공급자의 256차원 임베딩 호출 1번씩. 4. 외부 단가·한도 확인.

### 승인된 값
Spring AI starter 세 개의 다운로드, 비용 상한(채팅·임베딩 각 100만 토큰), 평가 규모(문서 약 50건·질문 약 30건), 임베딩 256차원 축소 방식.

**검증:** 전체 빌드, gate, 평가 결과(공급자별 표), 키 유출 검사. 외부 호출 평가는 사용자 승인 뒤에만 돌린다.

### W13 완료 기록(2026-10-05)
결정 1~7은 구현했고 8(부하 시험은 실제 모델로 돌리지 않는다)은 문서로 지켰다. 구현: Spring AI starter 네 개(Google 임베딩은 별도 starter), `application.yml`의 공급자 선택, `AiSettings`(기동 전 설정 검사, 외부 공급자는 `BATON_AI_EXTERNAL_TRANSMISSION=approved` 명시 필요), 임베딩의 256차원 맞춤, `EmbeddingBackfill`(모델을 바꾼 뒤 부족한 색인 작업), 평가 도구(`aiEvaluation`, 합성 집합, 토큰 상한, 합성 표식 검사). 설계에 없던 발견 세 가지를 같은 카드에서 고쳤다: Google 임베딩은 별도 starter와 자리표시자 키가 필요하고, 임베딩 어댑터가 기동 때 공급자를 호출하던 것을 없앴으며, Ollama는 JSON 모드와 구조화 프롬프트의 한 줄(ownerUserId는 숫자)이 있어야 소형 모델이 쓸 수 있다. Jackson 2 계열을 2.21.7로 올렸다(SDK가 끌어오는 권고 7건). 결과와 한계는 `evidence/W13.md`와 `evidence/model-profile.md`. OpenAI·Google 평가는 키를 넣은 뒤 같은 도구로 돌린다.

### W13 보정(2026-10-13, 대장 156)
사용자 선택(「1, 2 권고대로 가자」). 공개 경로·operation·스키마는 그대로이고 migration은 없다. 처리·시험은 개발 기록(비공개) 대장 156과 `evidence/W13.md` 「보정」에 있다.
- **161(156의 후속):** stub 프로필도 공급자를 지정하면 Spring AI가 그 모델을 만들므로, 그 공급자의 키는 `AiSettings`가 변수 이름으로 확인한다(외부 전송 승인·모델 ID는 보지 않는다).
- **156:** 완료 기록의 「Google 임베딩은 자리표시자 키가 필요」를 바꿨다. Compose는 설정하지 않은 변수를 빈 문자열로 넘겨 자리표시자가 적용되지 않았고, 키 없는 Compose 스택은 기동하지 못했다. Google 임베딩 연결 자동 구성을 제외하고, `spring.ai.model.embedding.text=google-genai`일 때만 `config/GoogleEmbeddingConnectionConfiguration`이 불러온다. 자리표시자는 없다. 배포 전 확인으로 `tests/deploy/compose-start-check.sh`를 더했다.

## W13.1 W11.2.1~W13 검증의 지적 87~104

선행 W13(커밋 ce49a34). 설계 승인: 2026-10-05 사용자 지시 「87번은 권고대로 진행해, 나머지 지적도 /build로 고쳐」. 공개 경로·operation은 늘리지 않는다(SPEC.yaml 불변). migration V17 하나(`audit_events.remote_address`)가 늘고 OAS는 응답 하나(400)만 더한다. 지적 89~104 중 사용자 판단이 따로 필요하다고 표시된 96·102·103은 지시를 「고쳐」로 읽어 아래처럼 정했고, 뒤집어야 하면 해당 행만 되돌린다.

| 지적 | 결정 | 수용 기준(시험을 먼저 쓰고 실패를 확인한다) |
|---|---|---|
| 87 | 권고안: **감사에 요청 주소를 남기고 잔여 위험을 문서로 밝힌다.** `audit_events.remote_address varchar(64)`(null 허용, V17)를 더하고 `ISSUE_CREDENTIAL_TOKEN`과 `SET_CREDENTIAL`이 요청 주소를 기록한다. 발급 주소와 설정 주소가 같으면 검토자가 의심할 수 있다. 관리자가 코드를 직접 써서 남의 비밀번호를 정할 수 있다는 사실은 막지 않고(방식 B의 본질) C11·A-11·W12 카드에 잔여 위험으로 적는다. 사용자에게 알리는 경로(메일·메신저)는 범위 밖이다. Javadoc의 「관리자는 비밀번호를 알지 못한다」를 사실대로 고친다. 주소는 개인정보라 A-02·A-03에 적는다 | CredentialIT: 발급·설정 감사 행에 요청 주소가 있고(테스트 요청의 주소), 코드·비밀번호는 여전히 어디에도 없다. DbmlConsistencyIT가 DBML과 V17을 대조한다 |
| 88 | `setCredential`도 사용자 행을 먼저 잠근 뒤 코드 행을 잠근다(`issue`와 같은 순서). 사번이 없어도 같은 질의를 한 번씩 돌려 질의 수를 같게 한다(실존 계정만 행 잠금이 생긴다) | 한 연결이 사용자 행을 잠근 채 있을 때 `setCredential`이 코드 행을 잡기 전에 기다린다(순서 증명, 기다림은 `pg_stat_activity`로 확인). 「두 호출을 동시에 돌려 교착이 없음」을 직접 보이는 시험은 두지 않았다: 교착은 두 잠금이 엇갈릴 때만 나서 결정적으로 재현할 수 없고, 순서가 같으면 구조상 일어날 수 없다 |
| 89 | 실패 제한기의 키 갱신을 `compute`로 키 단위 원자화해 빈 deque를 지우는 경쟁을 없애고, 키가 임계를 넘어도 전체 순회를 `SWEEP_INTERVAL`(30초)에 한 번으로 제한한다. 사번을 바꿔 가며 보내면 제한이 걸리지 않는 한계는 로그인과 같은 구조이므로 그대로 두고 C11에 적는다 | 임계 위에서 간격 안의 연속 실패는 순회하지 않고(만료 키가 남음) 간격 뒤에는 정리한다. 여러 스레드가 같은 키에 동시에 실패를 쌓아도 모두 집계된다 |
| 90 | 주석의 작업 카드 번호 6곳을 문장으로 쓴다 | 위 grep이 backend/src·backend/build.gradle.kts·tools·frontend/src·tests·infra에서 0건 |
| 91 | 시간대를 SQL 문자열 결합으로 만들지 않는다. 권고는 드라이버 `options`(`-c TimeZone=Asia/Seoul`)였으나 실제로는 통하지 않았다: 드라이버가 JVM 시간대를 `TimeZone` 시작 파라미터로 따로 보내고 그것이 `options`를 이긴다(DatabaseTimeZoneIT가 UTC로 실패, 기록 있음). 연결 초기 SQL을 조립하지 않는 상수로 만들고, 상수가 `BusinessCalendar.ZONE`과 같음을 단위 시험으로 고정한다 | DatabaseTimeZoneIT가 JVM을 UTC로 둔 상태에서 통과, DatabasePoolsTest(상수와 ZONE 일치) |
| 92 | `HandoverReadinessCheck.businessDay()`는 세션 시간대(풀이 고정)의 `current_date`를 쓴다. 같은 트랜잭션 시계라는 성질은 그대로다 | HandoverApprovalIT 통과 |
| 93 | README·backend/README·docs README·api-inventory·work-orders W13 카드 문구를 HEAD에 맞춘다 | 문서 — 다음 검증이 대조 |
| 94 | 게이트 신규 항목 판정을 LOG에 적었다(이미 처리) | — |
| 95 | `EmbeddingBackfill`의 시험만 쓰는 4인자 공개 생성자를 지우고(시험은 창 크기를 함께 준다), `trackedKeys()`는 시험 접점임을 Javadoc에 적는다 | 컴파일과 전체 빌드 |
| 96 | 평가 도구는 승인 변수를 스스로 주입하지 않고 환경에서 읽는다. 외부 공급자를 고르고 `BATON_AI_EXTERNAL_TRANSMISSION=approved`가 없으면 앱이 기동하지 않는다. 집합 해시 검사와 호출당 출력 상한은 두지 않는다: 합성 표식은 실수를 막는 장치이지 변조를 막는 장치가 아니고, 출력 상한은 공급자마다 옵션이 달라 평가가 쓰는 JSON 모드·온도 옵션을 덮어쓸 위험이 있다. 대신 한 호출의 초과는 그 호출 하나로 한정되고 다음 호출은 거절된다는 것을 model-profile에 적는다(한 호출의 출력은 모델의 최대 출력까지라 한도가 없는 하나다) | 평가 도구가 승인 변수를 주입하지 않으므로 환경변수 없이 외부 공급자를 고르면 앱이 기동하지 않는다(AiSettingsTest·AiProviderIT의 승인 시험이 이미 덮는다. 도구 쪽 새 시험은 없다) |
| 97 | AiProviderIT는 시험이 기대하는 변수를 모두 명시해 프로세스 환경과 무관하게 하고, 같은 이름을 두 번 주지 않는다 | 환경에 `BATON_AI_PROVIDER=openai`·`OPENAI_API_KEY`·`BATON_AI_EXTERNAL_TRANSMISSION`을 둔 채 AiProviderIT가 통과 |
| 98 | 변경 전에 열린 세션이 재설정으로 끊기지 않는 것은 배포 전환기 한계다. evidence의 「30분」을 고치고 절차(`spring_session` 비우기)를 C11에 적는다. 옛 색인 이름을 따로 찾는 코드는 두지 않는다(운영 배포 전이라 전환할 세션이 없다) | 문서 |
| 99 | 한계를 적는다: 외부 모델의 앞 256차원 절단 적합성은 설계 가정 A-13으로, 운영 경로의 검색 설정(`max-cosine-distance`·키워드 결합·HNSW)은 평가 범위 밖, embeddinggemma 태그의 다이제스트를 기록, Ollama 평가 보고서를 evidence에 커밋 | 문서 |
| 100 | OAS `issueCredentialToken`에 400을 선언한다 | CredentialIT에서 정수가 아닌 userId를 부르면 400이고 responseContract가 위반 0 |
| 101 | 비밀 검사기를 피하려고 리터럴을 쪼갠 두 곳을 속성 Map으로 바꾼다 | 기존 시험과 gate X1 |
| 102 | 원격 Ollama를 외부 전송으로 본다: 기본 주소(루프백)·`host.docker.internal`·한 단어 호스트 이름(compose 서비스)·사설망 주소·`.internal`·`.local`·`.localhost`가 아닌 호스트는 `BATON_AI_EXTERNAL_TRANSMISSION=approved`가 있어야 기동한다. 이름 해석은 하지 않는다(기동 때 네트워크를 쓰지 않는다) | AiSettingsTest: 안쪽 주소는 승인 없이 통과, 바깥 호스트는 승인 없이 거절되고 변수 이름을 알린다 |
| 103 | W12 카드의 「검토한 방식」에 Spring Security의 `OneTimeTokenService`를 더하고 쓰지 않은 사유를 적는다 | 문서 |
| 104 | 시험이 JVM 기본 시간대의 `LocalDate.now()` 대신 `BusinessCalendar.today()`를 쓴다. DatabaseTimeZoneIT의 JVM 기본 시간대 변경은 필요한 시험 수단이므로 두되 복원을 보장한다 | `-Duser.timezone=Pacific/Auckland`로 integrationTest가 통과 |

**범위 밖:** 기록된 미결(대장 23, 41~43, 45), 부하 재측정, OpenAI·Google 평가, 코드 전달 경로, 관리자 계정 대상 발급 제한·2인 승인.

**검증:** 전체 빌드(responseContract 포함), 환경 오염(위 97)·다른 시간대(위 104) 실행, 프런트엔드는 바뀌지 않으므로 단위·E2E 재실행으로 확인, 계약 검사기, gate BLOCK 수가 기준선(7) 이하, OSV는 의존성 무변경이라 재조회하지 않는다. 체크포인트에서 `/code-review`와 인증 경로가 바뀌므로 수동 보안 점검. 그 뒤 고친 범위만 격리 검증한다.

### W13.1 완료 기록(2026-10-05)
지적 87~104와 구현 중 찾은 105를 처리했다. 87은 권고안(감사에 요청 주소, 잔여 위험 문서화)만 구현했고 사용자 알림 경로와 관리자 대상 발급 제한은 사용자 결정으로 남긴다. 91은 권고한 드라이버 `options`가 통하지 않아(드라이버의 `TimeZone` 시작 파라미터가 이긴다) 상수 SQL과 일치 시험으로 바꿨다. 96·98·99는 일부만 고쳤고 고치지 않은 이유를 대장에 적었다. 104는 TZ=Pacific/Auckland 실행이 사실로 확정했고 그 실행이 시험 한 건(ProjectMemberIT)과 별개의 경쟁(105, 스케줄러 잠금 뒤 검사)을 더 찾았다. 결과는 `evidence/W13.1.md`.

## W14 설정 코드 잔여 위험 보강 (대장 87·106)

선행 W13.1(커밋 558d576). 설계 승인: 2026-10-06 사용자 선택(87의 네 항목 모두: 로그인 성공 감사 + 주소, 감사 추적 ID를 요청과 연결, 관리자 계정 대상 발급 제한, 사용자 알림 경로). 공개 경로·operation은 늘리지 않는다(SPEC.yaml 불변). migration V18 하나와 OAS `CurrentUser`의 선택 필드 하나가 늘고, 계약 검사기의 수치(40 paths, 54 operations, 85 schemas)는 그대로다.

### 문제
설정 코드를 가진 사람은 누구나 그 계정의 비밀번호를 정할 수 있어 발급한 관리자도 포함된다(W13.1이 감사에 요청 주소를 남기게 했다). 남은 틈: 로그인 성공이 감사되지 않아 탈취 뒤 그 계정의 행위에는 주소가 없고, 감사 행의 추적 ID가 요청과 이어지지 않으며, 관리자가 다른 관리자에게도 발급할 수 있고, 계정 주인이 자기 계정에 일어난 일을 볼 방법이 없다.

### 결정과 수용 기준
| # | 결정 | 수용 기준(테스트를 먼저 쓰고 실패를 확인한다) |
|---|---|---|
| 1 | **로그인 성공 감사.** 로그인에 성공하면 `SIGN_IN` 감사 행(actor·target = 그 사용자, 요청 주소)을 남긴다. **실패는 남기지 않는다:** 인증 전 경로가 감사 표를 끝없이 채울 수 있어서다(실패는 제한기가 센다). 로그아웃은 남기지 않는다 | 로그인 성공 뒤 `SIGN_IN` 행이 주소와 함께 한 건 있고, 틀린 비밀번호는 행을 만들지 않는다 |
| 2 | **감사 추적 ID를 요청과 연결.** 요청마다 추적 ID(UUID)를 하나 만들어(`RequestTraceFilter`, 클라이언트가 보낸 값은 쓰지 않는다) 요청 속성과 로그 컨텍스트(MDC `traceId`)에 둔다. 오류 응답 본문의 `traceId`와 감사 행의 `trace_id`가 같은 값을 쓴다. 응답 헤더로는 내보내지 않는다(OAS에 없는 응답 헤더를 늘리지 않는다) | 필터가 체인 안에서 값을 두고 끝나면 지운다(단위). 오류 응답의 `traceId`가 그 요청의 값이다(단위). 요청 밖(시동 때 부트스트랩)의 감사는 무작위 값 그대로 |
| 3 | **관리자 계정 대상 발급 제한.** 대상이 `SYSTEM_ADMIN`이면(자기 자신 포함) 설정 코드를 발급하지 않고 409 `INVALID_STATE`다(비활성·비LOCAL 대상과 같은 코드). 이유: 관리자가 다른 관리자의 비밀번호를 정할 수 있는 경로를 줄인다. **재검증(대장 129)이 보인 틈:** 발급 뒤 대상이 관리자가 되면 코드가 통하고, 다른 관리자를 강등한 뒤 발급·설정하고 승격하는 경로도 있다. 앞의 것은 `setCredential`이 관리자 대상의 코드를 틀린 코드와 같은 400으로 거절해 닫는다. 뒤의 것은 관리자 권한 안의 일이라 막지 않고 감사로 추적하며 문서에 한계로 적는다(강등 직후 발급 보류·2인 승인은 사용자가 두지 않기로 했다) | 관리자 대상 발급이 409이고 코드 행이 생기지 않는다. 일반 사용자 대상은 그대로 |
| 4 | **관리자 계정 복구는 운영자 몫이다.** 3의 부작용으로 유일한 관리자가 비밀번호를 잃으면 API 복구 경로가 없다. 부트스트랩 변수 다섯 개에 `BATON_BOOTSTRAP_ADMIN_RESET=true`를 더하면, 사번이 기존 활성 LOCAL 시스템 관리자와 같을 때 비밀번호를 변수의 값으로 바꾸고 그 사용자의 세션을 모두 끊고 `RESET_SYSTEM_ADMIN_CREDENTIAL` 감사(actor 없음)를 남긴다. 일치하는 관리자가 없으면 이유를 담아 기동을 멈춘다. 플래그가 켜진 채 기동할 때마다 경고를 남긴다(운영자가 끄는 것을 잊지 않게). 이 경로는 배포 변수를 바꿀 수 있는 사람만 쓴다 | 플래그 없이는 비밀번호가 안 바뀐다(기존 동작). 플래그가 있으면 새 비밀번호로 로그인되고 옛 비밀번호는 거절되며 옛 세션이 끊기고 감사 행이 있다. 일치하는 관리자가 없으면 기동 중단 |
| 5 | **사용자에게 보이는 계정 활동.** `GET /me`와 로그인 응답(`CurrentUser`)에 선택 필드 `accountActivity`(최근 5건, 최신순)를 더한다: `SIGN_IN`(그 사용자의 로그인), `ISSUE_CREDENTIAL_TOKEN`·`SET_CREDENTIAL`(그 사용자가 대상인 것). 항목은 `action`·`occurredAt`·`remoteAddress`(없으면 null)다. 화면은 상단 계정 영역에 「내 계정 활동」을 펼쳐 보여 준다. **한계:** 메일·메신저 같은 바깥 채널이 없어 로그인할 수 있는 사람에게만 보인다. 비밀번호를 빼앗긴 사람은 새 코드로 비밀번호를 다시 정한 뒤 그 목록에서 모르는 주소를 알아본다. 조회가 `/me`마다 돌므로 부분 인덱스 두 개(V18)를 둔다 | 로그인 뒤 `/me`에 `SIGN_IN`이 있고, 코드 발급·설정 뒤 대상 사용자의 `/me`에 두 행이 주소와 함께 있고 남의 행은 없고, 6건 이상이면 5건이다. DbmlConsistencyIT가 인덱스를 대조한다. 화면 단위 시험, demo 모드 |

### 공개 표면과 계약
- OAS `CurrentUser`에 선택 필드 `accountActivity`(인라인 객체 배열, `required`에 넣지 않는다). 새 경로·operation·최상위 스키마는 없다(85 그대로).
- migration V18: `audit_events`의 부분 인덱스 둘(`SIGN_IN`을 actor로, 설정 코드 두 이벤트를 target으로 찾는다). DBML에 같은 인덱스.
- `compose.yml`·`backend/README.md`에 복구 변수와 절차.

### 설계 검토(design-review-checklist)
- **형제 대칭:** 감사는 이미 관리 명령들이 남긴다. 로그인 감사는 인증 축의 빈 곳이다. 로그아웃·실패를 안 남기는 이유는 위에 적었다.
- **오류 응답:** 관리자 대상 발급은 비활성·비LOCAL 대상과 같은 409 `INVALID_STATE`다. 조회 항목에 오류 경로는 없다.
- **권한 표면:** `accountActivity`는 본인의 것만 읽는다(actor·target이 본인). 관리자가 남의 활동을 읽는 경로는 만들지 않는다. 복구 경로는 HTTP 표면이 아니라 시동 변수라 새 공개 표면이 아니다.
- **미사용 코드:** 새로 정의하는 모든 것이 호출된다.

### 범위 밖
메일·메신저로의 알림, 2인 승인, 로그인 실패·잠금의 감사, 감사를 읽는 관리자 API, 비밀번호 변경 API(본인), 감사 보존 배치의 변경.

### 승인된 값
87의 네 항목 모두 구현. 최근 5건, 부분 인덱스. 실패 로그인은 감사하지 않는다.

**검증:** 전체 빌드(responseContract 포함), TZ=Pacific/Auckland 통합 시험, 프런트엔드 단위·실제 백엔드 E2E·데모 E2E·빌드, 계약 검사기, gate BLOCK 수가 기준선(7) 이하, 의존성 무변경이라 OSV는 재조회하지 않는다. 체크포인트에서 `/code-review`와 인증 경로가 바뀌므로 수동 보안 점검. 그 뒤 격리 검증.

### W14 완료 기록(2026-10-06)
네 결정(로그인 성공 감사, 추적 ID 연결, 관리자 계정 대상 발급 제한과 운영자 복구, 계정 활동 보기)을 모두 구현했다. 구현을 먼저 하고 시험을 뒤에 써서 구현 전 실패를 본 시험은 없다(evidence/W14.md에 그대로 적었다). 체크포인트 리뷰에서 관리자 행의 발급 버튼과 데모 저장소가 서버 규칙과 어긋난 것, 복구 플래그만 있으면 조용히 무시되는 것을 찾아 고쳤다. 결과는 `evidence/W14.md`.

## W15 로그인 감사 행의 요청 주소 보존 한도 (대장 139, 143)

선행 W14(커밋 aea1852..8457c18, 격리 검증 전). 설계 승인: 2026-10-07 사용자 선택(139: 「주소만 비우기, 2년」·스위치 기본 꺼짐·143 포함, 144는 142와 묶어 감사 전체 결정에서 다룬다). 공개 경로·operation·스키마는 늘리지 않는다(SPEC.yaml 불변, 40 paths·54 operations·85 schemas 그대로). migration V19 하나가 늘고 OAS는 설명 문장만 바뀐다.

### 문제
W14 이후 모든 로그인이 `SIGN_IN` 감사 행(요청 주소 포함)으로 남고 감사 행을 정리하는 곳이 없다. 요청 주소는 개인정보(A-02)인데 로그인 행에는 프로젝트가 없어 A-03의 「프로젝트 종료 뒤 3년」을 적용할 기준일이 없고, 지금은 사실상 영구 보관이다. 용량은 문제가 아니다(하루 로그인 1·3·6회 [가정]이면 3년 0.23·0.68·1.35 GB, 행당 약 200 B [가정]). 사용자 판단(2026-10-07): 프로젝트 재개 등으로 감사 이력이 필요할 수 있고 백업(A-07)이 없어 행 삭제는 하지 않는다. 행은 남기고 개인정보인 주소만 비운다. 한편 `RetentionSchedule.run()`은 단계를 한 덩어리로 실행해 앞 단계의 예외가 뒤 단계를 막는다(대장 143).

### 결정과 수용 기준
| # | 결정 | 수용 기준(테스트를 먼저 쓰고 실패를 확인한다) |
|---|---|---|
| 1 | **주소만 비운다.** `action = 'SIGN_IN'`이고 `occurred_at`이 `baton.retention.audit.sign-in-address-days`(기본 730일)보다 오래된 행의 `remote_address`를 null로 바꾼다. 행(actor·target·시각·추적 ID)은 지우지 않는다. 5,000행씩 끊어 짧게 줄 때까지 되풀이한다(`jobs`와 같다). 값은 바인딩으로 넘긴다(`make_interval(days => ?)`) | 730일을 넘긴 `SIGN_IN` 행만 주소가 null이 된다. 730일 안의 `SIGN_IN` 행, 오래된 다른 action 행(설정 코드 이벤트의 주소 포함), 이미 주소가 없는 행은 그대로다. 행 수와 `occurred_at`은 변하지 않는다. 5,000행을 넘는 대상도 한 번의 실행에서 모두 처리한다. 두 번째 실행은 0행을 바꾼다 |
| 2 | **스위치 `baton.retention.audit.enabled`, 기본 꺼짐**(`BATON_RETENTION_AUDIT_ENABLED`). README §8이 운영 보존 정책 확정 전 자동 삭제를 막는 것과 같은 이유로 `jobs`의 관례를 따른다. 합성 환경(`compose.dev.yml`, 부하 overlay)에서만 켠다. 운영에서 켜는 것은 고객 보존 정책 확정 항목에 넣는다 | 스위치가 꺼져 있으면 오래된 행도 그대로다. `compose.yml`은 켜지 않는다 |
| 3 | **V19: 부분 인덱스 `audit_events_sign_in_address_idx (occurred_at) WHERE action = 'SIGN_IN' AND remote_address IS NOT NULL`.** 정리 조회가 `occurred_at` 순서로 대상만 읽게 한다. 주소를 비운 행은 이 인덱스에서 빠져 크기가 한도 안의 로그인 수로 묶인다. V18은 다시 고치지 않는다(C11이 밝힌 대로 V18 직접 수정은 어디에도 적용되지 않았을 때만 쓸 수 있는 예외다). V18의 `(actor_id, occurred_at DESC)` 인덱스는 그대로이므로 `/me`의 조회와 「최근 5건」은 바뀌지 않는다 | 정리 대상 조회의 실행 계획이 V19 인덱스를 쓴다(EXPLAIN, `AccountActivityIT`의 방식). `DbmlConsistencyIT`가 인덱스를 대조한다 |
| 4 | **`RetentionSchedule`의 단계 격리(143).** 단계(멱등 결과, 설정 코드, job, 감사 주소) 하나가 예외를 내도 이름을 담아 오류 로그를 남기고 다음 단계를 실행한다. 감사 단계는 끝에 둔다 | 앞 단계가 예외를 내도 뒤 단계가 실행된다(단위: 협력 객체가 던지게 해 호출을 센다). 오류 로그에 단계 이름이 있다. 구현을 되돌려 실패하는 것을 변이로 확인한다 |
| 5 | **계정 활동의 표시.** `accountActivity`는 그대로 최근 5건이다. 정리된 로그인은 `remoteAddress`가 null로 나온다(null은 이미 허용된 값이다). 행은 지우지 않으므로 「최근 5건」은 정리로 줄지 않는다 | 주소가 비워진 로그인이 `/me`에 시각·action과 함께 `remoteAddress: null`로 나오고, 5건 한도와 순서가 그대로다 |

### 공개 표면과 계약
- OAS `CurrentUser.accountActivity.remoteAddress`의 설명에 「스위치를 켠 운영에서는 730일이 지난 로그인의 주소가 비워진다」를 더한다(필드·타입·`required` 불변). 새 경로·operation·최상위 스키마는 없다.
- migration V19, DBML에 인덱스 하나와 `remote_address` 설명 갱신. 새 설정 키 둘(`baton.retention.audit.enabled`, `baton.retention.audit.sign-in-address-days`)은 `application.yml`에 주석과 함께 둔다. `compose.dev.yml`·`tests/load/compose.load.yml`에 `BATON_RETENTION_AUDIT_ENABLED: "true"`.
- 문서: A-03(로그인 행의 주소는 730일 뒤 비운다. 행과 다른 감사 행은 지우지 않고 감사 전체의 보존은 대장 142), A-11(남은 한계에 「2년 넘은 로그인은 주소가 없다」), C11 「W15 개정」, `RetentionSchedule` Javadoc, `README.md`·`backend/README.md`·`docs/backend-design/README.md`의 `Flyway V1–V18` 표기를 V1–V19로 고친다.
- 코드: `AuditService`에 주소를 비우는 메서드 하나(소유 서비스에 두는 `CredentialService.deleteExpired`·`JobRepository.deleteFinished`의 관례), `RetentionSchedule`에 단계 격리와 새 단계. 새로 정의하는 모든 것이 호출된다.

### 설계 검토(design-review-checklist)
- **형제 대칭:** 보존 단계 넷 중 멱등 결과와 설정 코드는 항상, job과 감사 주소는 스위치다. 비대칭의 사유는 앞 둘이 계약(C01)·만료로 정해진 값이고 뒤 둘은 운영 보존 정책이 미확정이어서다(README §8). 스위치 둘의 이름과 기본값은 같은 관례다.
- **오류 응답:** HTTP 표면이 없다. 정리 실패는 오류 로그이고 다음 주기에 다시 시도한다.
- **권한 표면:** 새 읽기 경로도 관리자가 남의 활동을 읽는 경로도 없다. 정리는 시동 설정으로만 켠다.
- **검사와 실행 사이:** 갱신은 멱등(같은 값 null)이고 로그인 삽입과 행을 공유하지 않는다. 실행 주체가 둘 이상이어도 결과는 같고 교착 가능성은 단일 호스트 전제에서 넘긴다. `jobs`의 삭제와 같은 판정이다.
- **쓰기 비용:** 주소를 비우면 부분 인덱스 술어의 열이 바뀌어 HOT 갱신이 안 된다. 하루 수천 행이라 넘긴다. 로그인 삽입은 V19 항목 하나가 늘어난다.
- **SQL 조립:** 값은 모두 바인딩이다. 식별자는 상수다.
- **미사용 코드:** 없다.

### 범위 밖
행 삭제, 보관 DB·스냅샷·내보내기(A-07의 위치·키 결정과 함께 142에서 설계), 감사 전체의 보존 기준과 프로젝트 참조(142), 감사 행이 있는 사용자의 삭제 거절(144, 142와 묶는다), 설정 코드 이벤트 주소의 정리, 감사를 읽는 관리자 API, 부하 재측정(삭제 없이 하루 수천 행의 갱신이라 필요하지 않다고 판단하며, 인덱스 사용은 EXPLAIN 시험으로 확인한다).

### 승인된 값
`SIGN_IN` 행만, 주소만 비움, 730일(아래 「W15 보정」에서 24개월로 바뀌었다), 스위치 기본 꺼짐, 5,000행 배치, V19, 단계 격리 포함. 행 삭제 없음.

**검증:** 전체 빌드(responseContract 포함), TZ=Pacific/Auckland 통합 시험, 계약 검사기(설명 문장 변경, 수치 불변), compose config, gate BLOCK 수가 기준선(7) 이하, 의존성 무변경이라 OSV는 재조회하지 않는다. 프런트엔드는 바뀌지 않아 단위·E2E는 돌리지 않는다. 체크포인트에서 `/code-review` 한 번. 그 뒤 격리 검증.

### W15 완료 기록(2026-10-07)
다섯 결정(로그인 주소 비우기, 스위치 기본 꺼짐, V19, 단계 격리, 계정 활동 표시)을 모두 구현했다. 시험을 먼저 쓰고 골격만 둔 채 실패를 확인한 뒤 구현했다. 체크포인트 리뷰의 4건 중 시험 주석의 작업 번호 표식 제거와 기본 꺼짐을 확인하는 시험 추가를 고쳤고, 나머지 둘(기본값 중복, 실패한 단계의 지표 없음)은 안 고쳤다. 결과는 `evidence/W15.md`.

### W15 보정(2026-10-10, 격리 검증의 지적 145~149)
사용자 선택(「권고대로 가자」). 공개 경로·operation·스키마는 그대로이고 migration은 없다. 처리·커밋·시험은 개발 기록(비공개) 대장 145~149와 `evidence/W15.md` 「보정」에 있다.
- **145:** 부트스트랩 비밀번호 검사는 계정을 실제로 만들 때(관리자 존재 확인과 같은 트랜잭션 안)와 재설정할 때만 한다. 관리자가 이미 있으면 변수의 값은 보지 않는다. 규칙은 `identity/PasswordRule` 한 곳이다.
- **146:** 인덱스 시험이 운영 질의 상수(`LocalAuthenticationService.ACCOUNT_ACTIVITY`)의 계획을 보고 `Index Cond` 줄까지 단언한다. 순차 스캔을 끈 계획은 조건에 못 쓰는 부분 인덱스도 통째로 읽어 이름만으로는 변이를 못 잡는다. V19 시험도 같이 고쳤다.
- **147:** 낡은 서술 정정(`backend/README.md`, `accountActivity` Javadoc).
- **148:** 게이트 S1이 메서드 참조(`Type::name`)를 호출로 센다.
- **149:** A-03의 근거 서술을 가정으로 낮추고 기간을 달력 개월로 바꿨다(`baton.retention.audit.sign-in-address-months`, 기본 24). 고시 원문 확인은 배포 단계로 미뤘다.

## D1 배포 전 정리 (대장 41·42·43·142·144)

배포 단계의 카드는 D로 시작한다. 설계 승인: 2026-10-10 사용자 선택(「41 A, 42 A, 43 A로 가자」, 142-A·144-A는 같은 날 결정). 43이 경로 하나를 더하므로 카드를 쓴다(카드 기준: 공개 표면 변경). 96은 남은 두 항목을 사유와 함께 안 고치기로 한 항목이라 대상이 아니고, 149의 고시 원문 확인은 사용자 확인을 기다린다.

### 결정과 수용 기준
| # | 결정 | 수용 기준(테스트를 먼저 쓰고 실패를 확인한다) |
|---|---|---|
| 1 | **41: 대체(SUPERSEDES) 관계도 패키지의 검토 항목이다.** README §5.2의 승인 조건(「SUPERSEDES/CONFLICTS_WITH의 패키지 내 검토 항목 해결」)과 데모 장면에 서버를 맞춘다. 패키지 게시 때 두 기록이 모두 snapshot에 있는 SUPERSEDES 관계를 CONFLICTS_WITH와 같이 충돌 항목으로 만든다(항목은 새 기록의 항목). PM이 해소해야 승인할 수 있고, 해소 규칙(두 후보 중 하나, 한 번만)은 그대로다. **대체된 옛 기록은 평가 문항의 근거로 쓰지 않는다:** 옛 오픈일 같은 낡은 사실을 묻는 문항이 생기지 않게 한다 | SUPERSEDES 쌍이 있는 패키지는 충돌 항목 하나를 갖고, 해소 전 승인은 blocker `CONFLICTS_UNRESOLVED`, 해소 뒤 사라진다. snapshot에 한쪽만 있으면 항목이 없다. 평가 문항에 대체된 기록을 가리키는 문항이 없다 |
| 2 | **42: 승인에 보낸 의견은 거절한다.** `decision=APPROVE`에 `comment`가 있으면 400(`INVALID_INPUT`)이다. 보완 요청의 의견(필수)은 그대로다. OAS `HandoverApprovalRequest.comment` 설명에 「REQUEST_REVISION일 때만 보낸다」. 화면은 이미 승인 때 의견을 보내지 않는다. 데모 저장소도 같은 규칙 | 승인+의견은 400이고 상태가 바뀌지 않는다. 승인(의견 없음)·보완 요청은 그대로 |
| 3 | **43: 구성원이 읽는 구성원 목록.** `GET /api/v1/projects/{projectId}/members`(operationId `listCurrentProjectMembers`): 그 프로젝트의 현재 구성원(`ProjectAccessPolicy`의 현재 구성원 조건과 같다)만 돌려주고, 읽는 사람도 그 프로젝트의 현재 구성원이어야 한다(아니면 404, 다른 프로젝트 조회와 같다). 응답은 기존 `ProjectMemberList`(이메일 없음)를 재사용하고 `Limit`·`Offset`을 받는다. 인수인계 요청 화면은 역할과 관계없이 이 목록을 쓴다(사용자 ID 입력은 목록을 못 받을 때의 대비로만 남는다). 기록 확인 화면의 담당자 선택은 범위 밖 | 구성원은 현재 구성원만 받고(종료된 배정·비활성 사용자는 없음), 비구성원은 404, 페이지 상한이 지켜진다. OAS·SPEC·계약 검사기 수치가 41 paths·55 operations(스키마 85 그대로). 화면 단위 시험(일반 PM에게 드롭다운), 데모 저장소, 실제 백엔드 E2E의 교체 요청 |
| 4 | **144: 감사 행이 있는 사용자는 삭제하지 않는다.** `UserManagementService.references()`에 `audit_events`(그 사용자가 `actor_id`)를 더해 409와 기존 비활성화 안내를 낸다. 대상으로만 나오는 행은 막지 않는다. 관리 화면의 거절 표시와 데모 저장소가 같은 규칙을 갖는다 | 로그인했거나 관리 행위를 한 사용자의 삭제는 409이고 `references`에 `audit_events`가 있다. 한 번도 쓰이지 않은 사용자는 204 |
| 5 | **142: 감사 행은 시스템이 지우지 않는다.** A-03의 감사 문장을 「감사 이벤트는 시스템이 삭제하지 않는다(로그인 행의 요청 주소만 24개월 뒤 비운다). 보존 기간과 그 뒤의 삭제·내보내기는 고객 보존 정책과 백업(A-07) 확정 뒤 운영자 도구로 정한다」로 고친다 | 문서 |

### 공개 표면과 계약
경로 하나(`GET /projects/{projectId}/members`), OAS 설명 두 곳(승인 의견, 삭제 거절의 `references` 예), SPEC.yaml 한 줄, 계약 검사기 수치, C11 「D1」 절, README §5.2의 승인 조건은 이미 맞다. migration 없음.

### 설계 검토(design-review-checklist)
- **형제 대칭:** 구성원 목록이 관리자용(`/admin/projects/{id}/members`, 종료된 배정 포함)과 구성원용(현재 구성원만)으로 둘이 된다. 읽는 사람과 목적이 달라 의도된 비대칭이다. 응답 스키마는 같다.
- **권한 표면:** 구성원용 목록은 같은 프로젝트의 현재 구성원만 읽는다. 식별자를 바꿔 다른 프로젝트를 읽으면 404. 목록 상한은 서버가 강제한다(기존 `PageRequest`).
- **오류 응답:** 승인 의견 거절은 다른 입력 오류와 같은 400, 삭제 거절은 기존과 같은 409.
- **미사용 코드:** 화면의 관리자 목록 호출은 관리 화면에서 계속 쓰인다.

### 범위 밖
기록 확인 화면의 담당자 선택, 승인 의견의 저장, 감사 내보내기(A-07과 함께), 149의 출처.

**검증:** 전체 빌드, TZ=Pacific/Auckland 통합 시험, 프런트엔드 단위·빌드·E2E·데모, 계약 검사기, gate BLOCK 수가 기준선 이하. 체크포인트 `/code-review` 한 번, 새 경로가 있으므로 수동 보안 점검.

### D1 완료 기록(2026-10-10)
다섯 결정(대체 관계의 검토 항목, 승인 의견 거절, 구성원 목록, 감사 행위자 삭제 거절, 감사 보존 문구)을 모두 구현했다. 시험을 먼저 쓰고 실패를 확인했다. 체크포인트 리뷰 6건 중 3건을 고쳤고 2건은 사유를 적고 안 고쳤다. 결과는 `evidence/D1.md`.

### D1 보정(2026-10-12, 격리 검증의 지적 150~155)
사용자 결정(「153은 권고대로 하고 보정 진행해」). 새 카드 없이 이 절로 처리한다. 처리·커밋·시험은 개발 기록(비공개) 대장 150~155와 `evidence/D1.md` 「보정」에 있다.
- **150:** 교체 요청 화면에 불러오는 중 상태를 두고, 실제 백엔드 E2E가 드롭다운에서 고른다(카드 수용 기준 「실제 백엔드 E2E의 교체 요청」이 이제 충족된다).
- **151:** 새 경로에 403 선언.
- **152:** 데모가 로그인한 사용자의 삭제를 서버처럼 거절한다.
- **153:** 대체 쌍은 대체한(새) 기록으로만 해소한다(옛 기록이면 409). 충돌 화면은 대체된 기록을 고를 수 없게 표시한다.
- **154·155:** 명세 개정 이력과 C10, 낡은 서술·시험 이름 정정.

## D2 야간 암호화 백업 (A-07)

설계 승인: 2026-10-12 사용자 선택(「권고대로 가자」: 위치 B 사내 NAS 폴더와 S3 호환 대상은 승인 변수가 있어야 쓰는 확장, 키 B-age 공개키, 보존 일간 30 + 월간 12). 공개 API는 늘지 않는다. Compose 서비스 하나와 설정이 늘어 운영 계약(README §8, A-06·A-07)이 바뀌므로 카드를 쓴다.

### 문제
복원 절차는 검증됐지만(evidence/recovery.md: 3.1 GB DB를 33초에 덤프, 71초에 복원, 원본과 같음) 주기 실행, 암호화, 다른 장애 영역 보관, 보존 기간, 복원 연습이 없다. 단일 호스트 Compose라 같은 호스트의 백업은 호스트와 함께 잃는다. 백업에는 개인정보(이름·사번·이메일, 로그인 주소)와 영구 보관하기로 한 감사 행이 들어 있고, 실자료의 외부 전송은 금지 행위다.

### 결정과 수용 기준
| # | 결정 | 수용 기준(시험을 먼저 쓰고 실패를 확인한다) |
|---|---|---|
| 1 | **백업 서비스.** Compose에 `backup` 서비스(DB와 같은 pgvector 이미지에 `age`를 더한 `infra/backup`, 고정 비루트 사용자, 읽기 전용 루트, `data` 내부 네트워크만)를 둔다. 매일 `BATON_BACKUP_AT`(기본 02:00, Asia/Seoul)에 `pg_dump -Fc`를 실행한다. 서비스를 멈추지 않는다(`pg_dump`는 한 시점의 일관된 사본을 만든다). 대상은 `BATON_BACKUP_DIR`(호스트 경로, 운영에서는 NAS 마운트)이고 설정하지 않으면 서비스가 기동을 멈춘다 | 한 번 실행(`backup once`)으로 대상 폴더에 백업 파일과 SHA-256 파일이 생긴다. 대상이 없거나 쓸 수 없으면 이유를 남기고 실패한다 |
| 2 | **공개키 암호화.** 덤프는 디스크에 평문으로 쓰지 않고 곧바로 `age -R <공개키 파일>`로 암호화해 쓴다(`pg_dump … \| age … > 파일.partial`, 둘 다 성공하면 이름을 바꾼다). 호스트와 컨테이너에는 **공개키만** 둔다(`BATON_BACKUP_RECIPIENTS`, 읽기 전용 바인드). 개인키는 운영자가 호스트 밖에 둘 이상 보관한다(보관 규칙은 README의 운영 절차) | 백업 파일이 age 형식(헤더 `age-encryption.org/v1`)이고 평문 SQL·테이블 이름을 담지 않는다. 시험용 임시 키로 풀면 `pg_restore`가 읽는다. 공개키 파일이 없거나 덤프가 실패하면 `.partial`만 지우고 실패한다(빈 백업을 남기지 않는다) |
| 3 | **보존.** 일간 30개 + 월간 12개(각 달의 첫 백업)를 남기고 그 밖의 백업만 지운다. 보존은 백업 파일 이름의 시각으로 정한다(파일 시각이 아니다). 최대 약 13개월이라 백업 속 로그인 주소가 DB의 24개월 기준보다 먼저 사라진다 | 날짜가 다른 가짜 백업 파일 집합으로 남는 것과 지워지는 것이 정확하다(단위 시험, 경계: 31번째 일간, 13번째 월간, 같은 달 둘째 백업). 백업 형식이 아닌 파일은 지우지 않는다 |
| 4 | **외부 대상은 승인 변수.** 이번 구현은 폴더 대상만이다. S3 호환 업로드는 확장으로 남기되, 넣을 때는 사내 주소가 아닌 대상이 `BATON_BACKUP_EXTERNAL_TRANSMISSION=approved` 없이 동작하지 않게 한다(AI 외부 전송과 같은 규칙). 이번에는 그 규칙을 문서와 설정 검사에만 둔다 | `BATON_BACKUP_DIR`이 아닌 원격 대상 설정(`BATON_BACKUP_S3_*`)이 있으면 「아직 지원하지 않음」으로 기동을 멈춘다 |
| 5 | **월간 복원 연습.** `tests/recovery/restore-drill.sh`: 개인키 파일과 백업 파일을 받아, 내부 네트워크의 일회용 DB에 스트림으로 풀어 복원하고 행 수·Flyway 이력·업무 데이터 fingerprint를 남긴다. 개인키 보관자가 손으로 돌린다. 기존 `restore-check.sh`는 그대로 둔다 | 시험용 임시 키로 만든 백업을 연습 스크립트로 복원하면 원본 fingerprint와 같다. 끝나면 일회용 컨테이너·네트워크·volume이 남지 않는다 |

### 공개 표면과 계약
공개 API·스키마·migration은 없다. `compose.yml`의 `backup` 서비스와 변수 셋(`BATON_BACKUP_DIR`, `BATON_BACKUP_RECIPIENTS`, `BATON_BACKUP_AT`), `infra/backup/`, README §8과 루트 README의 운영 절차(키 만들기·보관·교체, 복원 연습), A-06·A-07 갱신.

### 설계 검토(design-review-checklist)
- **관례 대비(§6):** 암호화를 직접 구현하지 않는다(`age`). 스케줄은 서비스 안의 단순 대기 반복이다(외부 스케줄러 의존을 늘리지 않기 위해서이고 사유를 문서에 적는다). 덤프는 PostgreSQL 표준 도구다.
- **권한·비밀:** 비밀번호는 기존 변수(`BATON_DB_PASSWORD`)를 환경으로 받는다. 개인키는 저장소·호스트·컨테이너 어디에도 두지 않는다. 게이트 X1이 공개키 외의 키 리터럴을 막는지 확인한다.
- **인프라 하드닝:** 다른 서비스와 같은 기준(비루트, 읽기 전용 루트, 쓰기 경로 명시, 포트 없음, 내부 네트워크, 자원 상한, 로그 상한).
- **실패:** 백업 실패는 로그에 남고 다음 날 다시 시도한다. 지표·알림 연동은 범위 밖이다(운영 모니터링 A-05와 함께).

### 범위 밖
S3 호환 업로드의 실제 구현, WAL 연속 보관과 시점 복구(A-06의 상향 검토), 백업 실패 알림, 고객 보존 정책 확정.

**검증:** 백업·보존·복원 연습 시험, 전체 빌드(백엔드 무변경 확인), compose config, gate BLOCK 수가 기준선 이하(인프라 하드닝 축 포함). 체크포인트 `/code-review` 한 번.

### D2 완료 기록(2026-10-12)
다섯 결정(백업 서비스, 공개키 암호화, 보존, 원격 대상 거절, 월간 복원 연습)을 모두 구현했다. 구현하며 정한 것: 백업 서비스는 Compose 프로필 `backup`으로 켠다(두 호스트 경로가 없는 개발·부하·E2E 스택을 깨지 않기 위해서이고, 운영 배포 절차에서 켜는 것이 필수다). 복원 연습의 비교는 스키마를 모르는 fingerprint(`fingerprint-generic.sql`)로 한다. 시험을 먼저 쓰고 실패를 확인했고, 변이 둘(실패 때 쓰던 파일을 지우지 않음, 암호화를 뺌)에서 시험이 실패하는 것을 확인했다. 결과는 `evidence/D2.md`.

## D3 관측 구성 (A-05, A-14)

설계 승인: 2026-10-13 사용자 선택(「권고대로 가자」: 범위 A 지표, 서버 쪽 p95 분포, overlay 배치와 A-14, 화면 셋과 경보 규칙·알림 전송 없음, k6 실시간 전송, 이미지 넷 내려받기, 비밀번호는 `~/baton-secrets/observe.env`). 공개 API는 늘지 않는다. 배포 구성 파일과 앱 설정, 운영 계약(C11)·가정(A-05·A-14)이 바뀌는 새 기능이라 카드를 쓴다.

### 문제
앱은 내부 관리 포트에 지표를 내지만 모으는 쪽·화면·경보가 없다. 부하 재측정의 정체(DB CPU는 낮은데 연결 대기가 쌓임)를 5초 표본 스크립트와 사후 집계로만 볼 수 있었다. 요청 시간 지표에 분포가 없어 서버 쪽 p95(README §8의 목표)를 계산할 수 없고, DB 내부 지표와 컨테이너 CPU도 없다.

### 결정과 수용 기준
| # | 결정 | 수용 기준(시험을 먼저 쓰고 실패를 확인한다) |
|---|---|---|
| 1 | **지표만 다룬다.** Prometheus(수집·저장·경보 규칙), Grafana(화면), postgres_exporter(DB), cAdvisor(컨테이너 CPU·메모리). 로그 수집(Loki)과 추적(OpenTelemetry)은 후속 후보로 남긴다 | Prometheus의 수집 대상(app, postgres_exporter, cAdvisor, Prometheus 자신)이 모두 up이다 |
| 2 | **서버 쪽 p95.** `http.server.requests`에 분포 버킷을 켠다(기대 범위 5ms~10초로 버킷 수를 묶는다). 앱 설정이라 모든 환경에 적용된다 | 관리 포트의 지표에 `http_server_requests_seconds_bucket`이 있다(통합 시험). Prometheus에서 `histogram_quantile(0.95, …)`가 값을 돌려준다 |
| 3 | **overlay 배치.** `compose.observe.yml`, 기본 스택에는 넣지 않고 기본 스택·부하 overlay와 겹쳐 쓴다. 운영은 회사 관측 체계에 붙인다(A-14). Grafana는 `127.0.0.1`에만 열고 관리자 비밀번호는 필수 변수다(익명 끔). Prometheus는 열지 않는다. DB 지표는 `pg_monitor` 권한만 가진 `baton_monitor` 계정으로 읽는다(overlay의 일회성 준비 단계가 만든다). 관측 서비스는 내부 `observe` 네트워크로 앱 관리 포트에 닿는다. 비루트·읽기 전용 루트·자원 상한·digest 고정(cAdvisor는 호스트 상태를 읽어야 해 root이고 로컬·부하 시험 전용이라고 적는다) | 관리자 비밀번호가 없으면 overlay가 기동을 멈춘다. Grafana가 익명 요청에 화면을 주지 않는다. `baton_monitor`는 업무 테이블을 읽지 못한다 |
| 4 | **화면과 경보.** 화면 셋을 파일로 두고 Grafana가 기동할 때 싣는다: 서비스(RED·SLO), 자원(USE: CPU·pool·AI slot·DB), 부하 시험(k6). 경보 규칙: p95 SLO 소진 속도, 5xx 비율, API pool 대기 1분 지속, readiness 실패, job 적체. 알림 전송(Alertmanager·Slack)은 외부 전송이라 두지 않고 화면에만 뜬다 | `promtool check config`·`check rules`가 통과한다. Grafana가 화면 셋을 싣는다 |
| 5 | **부하 시험 실시간.** 부하 도구에 Prometheus 전송(k6 `experimental-prometheus-rw`)을 켜는 변수를 둔다. 관측을 켠 측정은 조건이 다르다는 것을 결과에 남긴다 | 관측을 켠 단축 1회에서 k6 p95가 화면에 실시간으로 그려진다 |
| 6 | **자원.** 상한 합계 약 1.6GiB(Prometheus 1GiB, Grafana 256MiB, exporter 64MiB, cAdvisor 256MiB). Prometheus 보존 7일·2GB | `docker inspect`로 상한 적용 확인 |

### 공개 표면과 계약
공개 API·스키마·migration은 없다. `compose.observe.yml`, `infra/observe/`, 부하 도구의 전송 변수, `application.yml`의 분포 설정, 변수 셋(`BATON_GRAFANA_ADMIN_PASSWORD`, `BATON_MONITOR_DB_PASSWORD`, `BATON_GRAFANA_PORT`), README §8·C11 「D3」, A-05·A-14.

### 설계 검토(design-review-checklist)
- **관례 대비(§6):** 표준 도구(Prometheus·Grafana·exporter)를 쓰고 수집기를 직접 만들지 않는다. 화면·규칙은 저장소의 파일이다(손으로 만든 화면은 남지 않는다).
- **권한·비밀:** 비밀번호는 저장소 밖 env 파일. 지표 태그에 사용자·프로젝트 ID·본문을 넣지 않는 §8 규칙을 그대로 지킨다(분포 버킷은 기존 태그 uri·method·status만 쓴다).
- **인프라 하드닝:** 다른 서비스와 같은 기준. 예외(cAdvisor)는 사유와 함께 적는다.
- **실패:** 관측 스택이 죽어도 업무 스택은 영향받지 않는다(앱은 관측 쪽을 부르지 않는다).

### 범위 밖
로그 수집·추적, 알림 전송, 호스트(Mac) 자체 지표, 운영 관측 체계 연결.

**검증:** `tests/observe/observe-check.sh`(일회용 스택 + overlay: 수집 대상 up, p95 질의, 규칙 검사, 화면 실림, 익명 거절, 정리), 통합 시험, 관측을 켠 단축 부하 1회, compose config, gate BLOCK 수가 기준선 이하.

### D3 완료 기록(2026-10-13)
결정 1~6을 구현했다. 바뀐 것 하나: cAdvisor를 뺐다. Docker Desktop에서 읽기 전용 호스트 마운트로는 Docker를 찾지 못해 컨테이너별 지표가 나오지 않았고(VM 전체 하나), Docker 소켓을 주면 Docker API 전체 권한이라 승인 범위 밖이다. 설계 때 정한 대로 빼고 「컨테이너 CPU 없음」으로 적었다. 앱 CPU는 앱의 프로세스 지표로, DB 컨테이너 CPU는 부하 시험 표본으로 본다. 구현하며 정한 것: 감시 계정의 비밀번호는 psql 안에서 환경변수로 읽는다(명령줄에 남지 않는다). k6는 native histogram으로 보내 모든 요청을 합친 p95를 한 값으로 계산하고, 그때 URL 태그는 뺀다(레코드 ID마다 시리즈가 생긴다; `counts.py`는 scenario·name만 읽는다). 부하 스크립트(`run.sh`, `baseline.sh`)는 `COMPOSE_FILE`이 있으면 그 파일 목록을 쓴다(관측 overlay가 앱을 `observe` 네트워크에 붙인 채 복원하려고). 경보 규칙의 「readiness 실패」는 앱 관리 포트 무응답과 DB 접속 불가 둘로, 「job 적체」는 생성 slot(6개) 10분 포화로 잰다(앱이 readiness 상태·job 적체를 지표로 내지 않고, slot이 하나뿐인 pool은 적체가 빠지는 동안 늘 바쁘다). 시험을 먼저 쓰고 실패를 확인했고, 변이 둘(Grafana 익명 접근, 감시 계정 전체 읽기)에서 해당 확인이 실패한다. 결과는 `evidence/D3.md`. 체크포인트 `/code-review` 10건(대장 160~169)을 고쳤다: Prometheus는 내부 네트워크에만 두고 관측 실행의 k6가 별도 overlay로 붙는다, 확인 스크립트가 보안 주장을 검사한다, 화면 생성 스크립트를 저장소에 둔다.

## D4 기록 관계 철회 (대장 173, 172)

설계 승인: 2026-10-14 사용자 선택(「173 -> A로 가」: 관계 철회 경로). 공개 operation과 migration이 늘어 카드를 쓴다. 같은 화면의 172(충돌 항목과 관계를 서로 다른 ID로 짝지음)를 함께 고친다. 실제 서버에서 철회 흐름을 시험하려면 그 짝짓기가 맞아야 해서다.

### 문제
대체 쌍은 새 기록으로만 해소한다(153). 그런데 기록 관계는 생성만 있고 고칠 수 없으며, 역방향 대체는 순환으로, 이미 대체된 기록의 두 번째 대체는 거절된다. 확정된 기록도 바꿀 수 없다. 그래서 대체 관계를 잘못 등록하면 PM은 틀렸다고 보는 새 기록을 최종 근거로 고르거나 승인하지 못한다. C11 D1과 코드 주석의 「대체에 동의하지 않는 PM은 관계를 고친다」는 존재하지 않는 경로였다.

### 결정과 수용 기준
| # | 결정 | 수용 기준(시험을 먼저 쓰고 실패를 확인한다) |
|---|---|---|
| 1 | **철회 API.** `POST /api/v1/record-relations/{relationId}/withdrawal`(`withdrawRecordRelation`), 본문 `{reason}`(필수, 공백 제외 1~300자), `If-Match`(관계 version), `Idempotency-Key`, CSRF. 응답 200 `RecordRelation`. 그 관계의 프로젝트 PM만(비구성원 404, PM이 아닌 구성원 403). 감사 `WITHDRAW_RECORD_RELATION` | 철회하면 200과 철회 정보가 담긴 관계, 감사 한 행. 같은 키 재전송은 같은 응답이고 감사가 늘지 않는다. 이미 철회된 관계 409 `INVALID_STATE`, version 불일치 412, If-Match 없음 428, 사유 없음·301자 400, 구성원 403, 비구성원 404 |
| 2 | **행을 지우지 않는다.** V20이 `withdrawn_at`·`withdrawn_by`·`withdrawal_reason`을 더한다(셋 다 있거나 셋 다 없다). 같은 쌍의 중복 금지와 「출처 기록당 충돌 하나」는 철회되지 않은 관계에만 적용한다 | 철회 뒤에도 행이 남고 세 열이 채워진다. 철회한 쌍을 다시 등록할 수 있다 |
| 3 | **철회된 관계는 없는 것으로 본다.** 새 관계의 검사(순환, 이미 대체됨), 기록 상세의 관계 목록과 「대체한 기록」, 패키지 생성(대체된 기록 제외, 충돌 항목) | 잘못된 대체를 철회하면 역방향 대체를 등록할 수 있다. 철회된 관계는 기록 상세에 나오지 않는다. 재생성한 패키지에는 그 쌍의 충돌 항목이 없고, 대체됐던 기록이 평가 근거로 돌아온다 |
| 4 | **진행 중 세대.** 게시된 세대는 고정이라 그 세대의 충돌 항목은 남는다. 철회된 관계의 항목은 해소할 수 없고(409), 준비도는 그 항목에 `CONFLICTS_UNRESOLVED` 대신 `RELATIONS_WITHDRAWN`(패키지를 다시 만들어야 함)을 낸다. 인수인계 상세의 `conflicts`는 철회 상태를 보인다(`RecordRelation.withdrawnAt`·`withdrawalReason`) | 철회 뒤 해소 시도 409, 차단 사유 `RELATIONS_WITHDRAWN`, 재생성 뒤 두 사유 모두 사라짐 |
| 5 | **화면.** 충돌 화면에서 쌍마다 「관계 철회」(사유 입력, PM만). 철회된 쌍은 철회 사유와 「패키지를 다시 만들어야 합니다」를 보이고 고를 수 없다. 데모도 같은 규칙. 172: 항목과 관계를 후보 기록 ID 쌍으로 짝짓는다(`conflictId`는 서버에서 충돌 행 ID라 관계 ID와 다르다) | 프런트엔드 단위: 서버와 같은 모양(충돌 행 ID ≠ 관계 ID)에서 대체된 기록이 비활성화되고 철회가 동작한다. 실제 백엔드 E2E: 대체 쌍 → 철회 → 재생성 → 충돌 없음 |

### 공개 표면과 계약
경로 41 → 42, operation 55 → 56, 최상위 스키마 85 → 86(`WithdrawRecordRelationRequest`). `RecordRelation`에 `withdrawnAt`·`withdrawalReason`(nullable). migration V20. 승인 차단 사유 `RELATIONS_WITHDRAWN`(문자열 목록, 스키마 불변). C11 D1의 「관계를 고친다」를 「관계를 철회하고 패키지를 다시 만든다」로 바꾸고 C11 「D4」를 쓴다. SPEC.yaml·api-inventory·DBML·README의 개수를 고친다.

### 설계 검토(design-review-checklist)
- **대칭:** 관계는 생성과 철회만 있다. 수정은 두지 않는다(철회하고 다시 등록한다). 생성과 같은 권한(PM)·같은 멱등·같은 409 코드다.
- **오류 코드:** 이미 철회됨과 철회된 관계의 해소는 다른 상태 충돌과 같은 409 `INVALID_STATE`다.
- **이력:** 행과 감사를 남긴다. 완료된 인수인계의 snapshot은 그대로다.
- **죽은 표면:** 없다. 새 operation은 화면이 부르고 IT가 시험한다.

### 범위 밖
철회의 취소(다시 등록으로 대신한다), 다른 사람이 등록한 관계의 철회 제한(PM이면 누구나), 관계 수정.

**검증:** RecordRelationIT·HandoverPackageIT의 새 시험, responseContract(56/56), 계약 검사기, 프런트엔드 단위·실제 백엔드 E2E·데모 E2E, gate BLOCK 기준선 이하.

### D4 완료 기록(2026-10-14)
결정 1~5를 구현했다. 구현하며 정한 것: Q&A가 미해소 충돌로 넘기는 쌍에서도 철회된 관계를 뺀다(결정 3의 「없는 것으로 본다」에 포함). 데모의 충돌 항목 ID를 관계 ID와 다른 번호로 매겨, 화면이 두 값을 섞으면 단위 시험이 잡는다. 실제 백엔드 E2E에서 `HandoverJson`이 충돌 필드를 손으로 나열하며 철회 정보를 빠뜨린 것을 찾아 고쳤다. 시험을 먼저 썼고, 변이 둘(패키지 생성의 철회 필터 제거, 짝짓기를 관계 ID 비교로 되돌림)의 출력은 `~/baton-results/d4-evidence/`에 남겼다. 결과는 `evidence/D4.md`.

### D4 보정(2026-10-16, 체크포인트 리뷰의 대장 183)
사용자 선택(「2안으로 가」). 관계 등록이 같은 두 기록 사이에 철회되지 않은 대체와 상충을 함께 걸지 못하게 한다(409 `INVALID_STATE`). 공개 경로·operation·스키마는 그대로이고 migration은 없다. 처리·시험은 개발 기록(비공개) 대장 183과 `evidence/D4.md` 「보정」에 있다.

### D4 보정(2026-10-19, 격리 검증의 대장 191~196)
사용자 선택(「권고대로 가자」). 공개 경로·operation·스키마는 그대로이고 migration은 없다. 데모를 서버 규칙에 맞추고(191), 철회 필터 세 곳에 시험을 더하고(192), 기록 상세에도 관계 철회를 두고(193, 충돌 화면과 같은 부품), 미해소 건수에서 철회된 항목을 빼고(194), E2E가 익명 volume을 남기지 않게 하고(195), 게이트 오탐 둘을 기록한다(196). 처리·시험은 개발 기록(비공개) 대장 191~196과 `evidence/D4.md` 「보정 2」에 있다.

## D5 감사 행의 위·변조 방지 (대장 198, 고시 제8조 ③)

설계 승인: 2026-10-19 사용자 선택(「권고대로 가자」: 트리거 방식, 계정 분리는 AWS 배포 설계 때; 「다음단계 하자」로 카드 승인). migration이 늘어 카드를 쓴다.

### 문제
개인정보의 안전성 확보조치 기준 제8조 ③은 접속기록이 위·변조, 도난·분실되지 않게 안전하게 보관하는 조치를 요구한다. 백업은 공개키로 암호화하지만(D2), `audit_events`는 앱이 쓰는 DB 계정이 수정·삭제할 수 있다. 정상 동작이 감사 행을 바꾸는 곳은 둘뿐이다: 행 추가(`AuditService.record`)와 24개월 지난 로그인 행의 요청 주소 비우기(`AuditService.CLEAR_SIGN_IN_ADDRESSES`). 감사 행을 가리키는 외래키는 없어 다른 테이블의 삭제가 감사 행을 바꾸지 않는다.

### 결정과 수용 기준
| # | 결정 | 수용 기준(시험을 먼저 쓰고 실패를 확인한다) |
|---|---|---|
| 1 | **V21 트리거.** `audit_events`의 행 단위 BEFORE UPDATE OR DELETE 트리거가 삭제를 거부하고, 수정은 「`action = 'SIGN_IN'`이고 `remote_address`만 값에서 null로 바뀌는 것」 하나만 통과시킨다. 문장 단위 BEFORE TRUNCATE 트리거가 비우기를 거부한다 | 삭제, 다른 칸의 수정, 로그인 아닌 행의 주소 수정, 주소를 다른 값으로 바꾸기, TRUNCATE가 모두 DB 오류로 거절된다. 주소를 null로 비우는 갱신은 통과한다 |
| 2 | **정상 기능은 그대로.** 감사 추가, 로그인 주소 비우기 배치, 사용자 삭제(감사 행이 있으면 409, 대장 144), 계정 활동 조회 | 기존 IT 전부 통과(보존 기간 IT 포함) |
| 3 | **시험의 정리 방식.** 시험 22곳의 `delete from audit_events`와 보존 기간 시험의 날짜 조작은 트리거를 우회하는 시험 전용 도우미 하나로 모은다(같은 연결에서 `session_replication_role = replica`, Testcontainers 계정은 슈퍼유저). 운영 코드에는 우회 경로를 두지 않는다 | 도우미 밖에서 감사 행을 지우는 시험 코드가 없다(grep) |
| 4 | **한계를 적는다.** 트리거는 앱 버그·SQL 주입처럼 평범한 SQL로 감사 행을 바꾸는 것을 막는다. 테이블 소유자나 슈퍼유저는 트리거를 끌 수 있다. compose의 `baton` 계정은 그 DB의 슈퍼유저다. 계정 분리(소유자·migration 계정과 앱 계정, 앱 계정은 감사 테이블에 추가·조회와 `remote_address` 열 수정만)는 AWS 배포 설계(대장 170)에서 정한다. 위·변조 탐지(해시 연결)는 범위 밖 | assumptions.md A-03·A-11과 contracts.md C11 「D5」에 한계와 넘긴 곳이 있다 |

### 공개 표면과 계약
경로·operation·스키마 그대로(42/56/86). migration V21. DBML에 트리거 주석. 감사 행을 바꾸는 운영 경로가 없으므로 오류 응답 계약은 바뀌지 않는다(만약 생기면 500).

### 설계 검토(design-review-checklist)
- **대칭:** 감사는 추가만 있고, 유일한 수정(주소 비우기)이 트리거의 유일한 예외다.
- **죽은 표면:** 없다. 새 공개 표면이 없다.
- **오류 코드:** 운영 경로가 트리거에 걸리지 않으므로 새 Problem code가 없다.
- **이력:** 보존 정책(A-03)은 그대로다. 행을 지우지 않는다는 기존 결정을 DB가 강제하게 된다.

### 범위 밖
계정 분리(170과 함께), 위·변조 탐지(해시 연결), 운영자용 접속기록 조회 화면(대장 199), 다른 테이블의 보호.

**검증:** 새 AuditTamperIT(가칭), 전체 IT·responseContract, 계약 검사기, gate BLOCK 기준선 이하.

### D5 완료 기록(2026-10-19)
결정 1~4를 구현했다. V21이 `audit_events`에 행 단위(삭제·수정)와 문장 단위(TRUNCATE) 트리거를 단다. 통과하는 수정은 로그인 행의 주소를 null로 비우는 것 하나다. 시험 22개 파일의 감사 정리와 날짜 조작은 `AuditRows`(시험 코드)로 모았다. 한계는 assumptions.md A-03과 contracts.md C11 「D5」에 적었다(A-11은 자격증명 가정이라 A-03에만 적었다). 시험을 먼저 썼고 migration 전에 막아야 할 세 시험이 실패했으며, 「로그인 행만」 조건을 지운 변이도 실패했다(출력 `~/baton-results/d5-evidence/`). 결과는 `evidence/D5.md`.

## D6 충돌마다 검토 항목 하나 (대장 202)

설계 승인: 2026-10-19 사용자 선택(「권고대로 가자」: 선택지 A; 「다음단계 하자」로 카드 승인). migration이 늘어 카드를 쓴다.

### 문제
패키지 생성은 유효한 대체·상충 관계마다 충돌 행을 출처 기록의 첫 항목(FOLLOW_UP 제외)에 단다. 한 기록이 두 관계의 출처면(새 일정 하나가 옛 일정 둘을 대체, 또는 대체와 상충을 함께 가짐) 그 항목에 충돌 행이 둘 생기고, `HandoverConflictService.resolve`는 항목의 충돌이 정확히 하나가 아니면 409라 `CONFLICTS_UNRESOLVED`가 풀리지 않아 승인할 수 없다. 같은 기록의 항목을 하나 더 만들 수 없는 것은 V6의 고유 제약 `handover_items_generation_kind_record_key`(세대·종류·기록당 항목 하나) 때문이다. 데모는 기록마다 첫 관계 하나만 항목으로 만들어 서버와도 다르다.

### 결정과 수용 기준
| # | 결정 | 수용 기준(시험을 먼저 쓰고 실패를 확인한다) |
|---|---|---|
| 1 | **V22.** `handover_items`에 `conflict_slot smallint NOT NULL DEFAULT 0`을 더하고, 고유 제약을 `(generation_id, item_kind, work_record_id, conflict_slot)`으로 바꾼다. 기존 행은 0이다 | 같은 세대·종류·기록·slot의 항목 둘은 여전히 거절된다(SchemaIT·DbmlConsistencyIT) |
| 2 | **충돌마다 항목 하나.** 출처 기록별로 관계를 ID 순으로 놓고, 첫 관계는 그 기록의 항목(slot 0)에, 이후 관계는 같은 기록·종류·제목·본문·필수 여부를 복사한 새 항목(slot 1, 2, …, 목록 끝의 표시 순서)에 단다 | 한 기록이 옛 기록 둘을 대체하면 충돌 항목이 둘이고, 각 항목을 새 기록으로 해소하면 `CONFLICTS_UNRESOLVED`가 풀린다. 대체와 상충을 함께 가진 기록도 항목이 둘이다. 관계가 하나뿐인 기록의 패키지는 지금과 같다 |
| 3 | **나머지는 그대로.** 항목 API(`HandoverItem`)에 필드를 더하지 않는다(slot은 내부). 해소·확인·준비도·Q&A·평가는 항목과 충돌 행을 지금처럼 읽는다. 추가 항목도 원 항목처럼 필수면 후임자가 확인해야 한다 | 기존 IT 전부, responseContract 위반 0 |
| 4 | **데모도 같은 규칙.** 데모 패키지가 관계마다 항목을 만든다 | 데모 단위 시험(옛 기록 둘을 대체하는 기록 → 충돌 항목 둘, 둘 다 해소 뒤 차단 풀림) |

### 공개 표면과 계약
경로·operation·스키마 그대로(42/56/86). migration V22. DBML `handover_items`에 `conflict_slot`과 바뀐 고유 제약. contracts.md C11에 「D6」을 쓰고 D4의 「항목은 관계의 출처 기록의 것」을 「충돌마다 항목 하나, 출처 기록의 첫 관계는 그 기록의 항목」으로 고친다. 계약 검사기의 테이블 수는 그대로다(30).

### 설계 검토(design-review-checklist)
- **대칭:** 해소는 항목 하나에 충돌 하나라는 기존 규칙을 그대로 두고, 생성이 그 규칙을 지키게 한다.
- **죽은 표면:** 없다. 새 공개 표면이 없다.
- **오류 코드:** 바뀌지 않는다. 「충돌이 하나가 아님」 409는 생성이 그런 항목을 만들지 않으므로 방어로만 남는다.
- **이력:** 게시된 세대는 바뀌지 않는다. 이미 충돌 행이 둘인 항목이 있는 세대는 패키지를 다시 만들면 고쳐진다.

### 범위 밖
추가 항목을 원 항목 바로 뒤에 두는 정렬(표시 순서를 다시 매겨야 해 목록 끝에 둔다), 항목 제목에 상대 기록 표시.

**검증:** HandoverPackageIT·데모 단위의 새 시험, 전체 IT·responseContract, 계약 검사기, 프런트엔드 단위·E2E, gate BLOCK 기준선 이하.

### D6 완료 기록(2026-10-19)
결정 1~4를 구현했다. V22가 `conflict_slot`과 바뀐 고유 제약을 더하고, 패키지 생성이 관계마다 항목 하나에 충돌을 단다(출처가 같은 두 번째 관계부터는 그 기록 항목의 사본, 목록 끝). 데모도 같다. 시험을 먼저 썼고 서버·데모의 새 시험이 구현 전에 실패했다. 결과는 `evidence/D6.md`.

## D7 AWS 공개 데모 배포 (대장 170)

설계 승인: 2026-10-19 사용자 선택(「다음단계 하자」로 카드 승인). 사용자 선택: 포트폴리오 공개 데모(합성 데이터, 실제 백엔드), 금지 행위 예외(사용자 AWS 계정의 합성 데이터 데모 배포), EC2 한 대 + Compose(서울), CI가 이미지를 ECR로, DB 계정 분리, AI는 stub, 원격 백업 대상은 구현하지 않고 한계로 둠, GitHub 공개 저장소, 데모 계정 공개와 매일 초기화, 최악 비용을 구조로 상한, 대역폭 상한 2 Mbps와 순간 허용량, 월 전송량 할당(넘으면 프록시 중지), loan과 따로 쓰는 `t4g.small`, 도메인 `baton.gwang.dev`(Cloudflare DNS, loan.gwang.dev와 같은 방식). 공개 경로·스키마는 바뀌지 않지만 배포 구성·DB 계정·운영 절차가 바뀌어 카드를 쓴다.

### 문제
지금 구성은 이 Mac의 Compose와 운영자가 넣는 인증서를 전제한다. 공개 데모로 옮기려면 (1) 누구나 여는 주소와 인증서, (2) 크레딧(약 95달러, 다 쓰면 카드 청구)을 넘지 않는 최악 비용의 상한, (3) 공개 계정이 망가뜨린 데이터의 복구, (4) 앱 계정이 DB 슈퍼유저라 감사 트리거(D5)를 끌 수 있는 문제, (5) 이미지를 이 Mac이 아니라 CI가 만드는 경로가 필요하다.

### 결정과 수용 기준
| # | 결정 | 수용 기준 |
|---|---|---|
| 1 | **주소와 인증서.** `baton.gwang.dev`의 A 레코드(Cloudflare, DNS only)를 EC2의 고정 IP에 둔다. 배포용 Caddyfile은 도메인 이름으로 Let's Encrypt 인증서를 자동 발급·갱신한다(80은 HTTPS로 돌림). 로컬 Caddyfile(운영자 인증서)은 그대로 둔다 | 배포 뒤 `https://baton.gwang.dev`가 유효한 인증서로 열리고 Secure 쿠키로 로그인된다. HTTP는 HTTPS로 간다 |
| 2 | **요청당 과금 없음.** ALB·API Gateway·Lambda·NAT·WAF·CloudWatch Logs·자동 확장·정기 스냅샷을 쓰지 않는다. 로그는 호스트 안에서 크기 제한(Docker 로그 회전)으로 둔다 | 배포 정의에 위 자원이 없다(검토 항목). 로그 디렉터리 크기가 상한 안이다 |
| 3 | **CPU 크레딧 standard.** 인스턴스는 standard 크레딧 모드로 띄운다 | 인스턴스 속성에서 확인 |
| 4 | **나가는 전송의 상한.** (가) 호스트 커널(`tc`)이 나가는 트래픽을 평균 2 Mbps로 묶고 순간 허용량을 둔다. (나) 호스트가 이번 달 나간 바이트를 세다가 할당(90 GB)을 넘으면 프록시를 멈추고 다음 달 1일에 다시 켠다. 할당은 AWS의 월 무료 전송량보다 작아야 하며, 무료량은 배포 전에 AWS 요금 페이지에서 확인해 evidence에 적는다. 둘 다 앱·프록시 밖이라 그것들이 뚫려도 우회할 수 없다 | 부팅 뒤에도 설정이 남는다. 대용량을 받아 평균 속도가 상한 근처임을 확인한다. 할당을 작게 잡은 시험에서 넘는 순간 프록시가 멈춘다 |
| 5 | **보내는 양 줄이기.** 프록시가 응답을 압축(zstd, gzip)하고, 해시가 붙은 정적 파일(`/assets/*`)에 오래 캐시하라는 지정을 붙인다 | 응답 헤더 확인. 운영용 화면 빌드의 전송량이 압축 전보다 줄어든다 |
| 6 | **IP당 요청 한도.** 프록시에서 한 IP의 요청 수를 제한한다. 구현은 Caddy 기본 모듈에 없어 (가) Caddy에 rate-limit 모듈을 넣어 빌드하거나 (나) 앱에 IP당 한도 필터를 두는 것 중 설계 검토에서 정한다(직접 구현이면 사유를 적는다) | 한도를 넘는 요청이 429를 받는다. 정상 시연(인수인계 한 바퀴)은 한도에 걸리지 않는다 |
| 7 | **예산 알림과 자동 중지.** AWS Budgets에 크레딧을 뺀 실제 사용액 기준 알림을 걸고, 임계치에서 EC2를 멈추는 동작을 연결한다(지원 범위는 설정 때 확인). 사용자가 콘솔에서 설정하고 절차를 문서로 남긴다 | 알림 메일 수신 확인, 동작 설정 화면 기록 |
| 8 | **최악 비용 계산.** 전송 할당이 무료량 안이므로 최악 월 비용 = 인스턴스 + 디스크 + 공인 IP(고정비)다. loan 저장소 `docs/배포계획.md`·`docs/배포절차.md`의 추정(2026-09)은 `t4g.small` 약 $12 + gp3 20 GiB 약 $2 + 탄력적 IP 약 $3.6, 합계 월 약 $18이다. loan과 합치면 월 약 $36이다. 배포 전에 콘솔에서 현재 단가와 남은 크레딧을 확인하고, 크레딧으로 버티는 기간을 면접 일정과 맞춘다 | 계산표와 콘솔에서 확인한 단가·남은 크레딧이 evidence에 있다 |
| 9 | **인스턴스.** loan과 따로 `t4g.small`(ARM, 2 GiB, 스왑 2 GiB, gp3 20 GiB)을 쓴다. 같이 쓰면 메모리가 모자라 4 GiB로 키워야 하고(비용이 두 대와 비슷), loan 저장소의 Caddy를 고쳐야 하며 장애가 함께 번진다. 앱·DB·프록시를 띄워 인수인계 한 바퀴를 돈 뒤 메모리를 재 2 GiB로 충분한지 확인한다. 관측 스택(D3)은 배포하지 않는다(로컬 전용) | 측정값이 evidence에 있다. 모자라면 사유와 함께 크기를 다시 정한다 |
| 10 | **이미지는 CI가.** GitHub Actions가 시험을 돌리고 API·프록시·백업 이미지를 ARM(arm64)으로 만들어 ECR에 올린다. GitHub의 ARM 실행 환경을 쓸 수 있으면 그것으로, 아니면 에뮬레이션으로 빌드한다(workflow 작성 때 확인). AWS 접근은 OIDC로 받아 오래 사는 키를 두지 않는다. ECR은 최근 몇 개만 남긴다. EC2는 인스턴스 역할로 ECR에서 받는다 | CI 실행 기록, 저장소에 AWS 키 없음(gate X1), ECR 수명 주기 규칙 |
| 11 | **DB 계정 분리.** 소유자(migration) 계정, 앱 계정(테이블 읽기·쓰기, 감사 테이블은 추가·조회와 `remote_address` 수정만), 읽기 전용 점검 계정을 만든다. 앱 계정은 슈퍼유저도 소유자도 아니라 트리거를 끌 수 없다 | 앱 계정으로 `ALTER TABLE audit_events DISABLE TRIGGER`가 거절된다. 전체 IT가 분리된 계정 구성에서 통과한다. 접속기록 SQL이 점검 계정으로 돈다 |
| 12 | **공개 데모 계정과 매일 초기화.** 합성 시나리오(실제 백엔드 E2E의 seed)로 만든 기준 덤프를 두고, 매일 04:00 KST에 DB를 그 덤프로 되돌린다. 계정은 기존 담당자·후임자·PM·시스템 관리자 넷이고 README에 적는다(비밀번호는 데모 전용 값, 코드에 두지 않는다) | 초기화 뒤 네 계정으로 로그인되고 방문자가 바꾼 데이터가 사라진다. 초기화 중에는 점검 화면(503)이 뜬다 |
| 13 | **로그인 화면 안내.** 배포 빌드의 로그인 화면이 「합성 데이터 공개 데모, 매일 04:00 초기화, 실제 개인정보 입력 금지」를 보이고 README의 계정 안내로 이어진다 | 화면 단위 시험 |
| 14 | **접근 경로.** 보안 그룹은 80·443만 연다. 셸 접속은 SSH 포트 대신 SSM Session Manager로 한다 | 보안 그룹 규칙 기록 |
| 15 | **공개 저장소 전 검사.** push 전에 publishing-rules의 민감정보 스캔을 작업 트리와 **git 이력 전체**에 돌리고, 결과를 사용자에게 보고한다. 생성과 push는 사용자가 한다 | 스캔 결과 보고 |

### 한계(이번에 구현하지 않음)
- **백업:** 공개 데모는 백업하지 않는다(2026-10-21 사용자 결정, 대장 215). 매일 초기화가 데모 데이터를 기준 덤프로 되돌리므로 백업은 방문자의 변경만 남긴다. 야간 백업(D2)과 원격 대상은 실제 도입 배포에서 그 고객사의 저장소와 키로 켠다. D2 완료 기록의 「운영 배포 절차에서 켜는 것이 필수」는 그 실제 도입 배포를 말한다.
- 단일 호스트라 호스트 장애를 견디지 못한다(A-05). 공개 계정은 초기화 사이(최대 하루) 방문자가 바꾼 상태를 볼 수 있다.

### 공개 표면과 계약
경로·operation·스키마 그대로(42/56/86). migration은 계정 분리를 위한 권한 부여가 필요하면 하나 늘 수 있다(설계 검토에서 정한다). 배포 정의(Caddyfile.prod, 배포 compose overlay, 호스트 설정 스크립트, CI workflow, 초기화 스크립트)가 저장소에 생긴다. assumptions.md A-05·A-07, contracts.md C11 「D7」, README 배포 절.

### 설계 검토 판정(제안)
- **결정 6 IP당 한도:** 비용은 결정 4가 묶으므로 이 한도의 목적은 비용이 아니라 한 방문자가 다른 방문자를 밀어내지 못하게 하는 것이다. Caddy 기본 빌드와 Spring 모두 요청 한도를 제공하지 않는다. 제3자 Caddy 모듈이나 앱 필터를 새로 들이는 대신, 결정 4와 같은 층의 호스트 방화벽(nftables)으로 IP당 새 연결 속도와 동시 연결 수를 묶는다. 새 의존성이 없다. 한계: 요청이 아니라 연결 단위라, 연결 하나로 요청을 많이 보내는 것은 앱의 기존 한도(로그인·Q&A)와 결정 4가 맡는다.
- **결정 11 계정 생성:** 역할 생성은 DB 컨테이너의 초기화 스크립트(`docker-entrypoint-initdb.d`, 첫 기동에만)가, 권한 부여는 Flyway의 `afterMigrate` 콜백 SQL(프레임워크 제공)이 한다. 콜백은 역할이 있을 때만 부여하므로 역할이 없는 Testcontainers 시험에서는 아무것도 하지 않는다. migration에 역할 이름을 박지 않는 이유: 모든 환경에 같은 역할이 있어야 migration이 돈다.
- **결정 12 기준 덤프:** E2E seeder는 bootJar에 들지 않는 별도 소스셋이라, 로컬 스크립트가 seeder로 합성 시나리오를 만든 뒤 덤프하고 그 파일을 호스트에 한 번 올린다. 저장소에는 덤프를 두지 않는다(데모 비밀번호의 해시가 들어 있다. 비밀번호 자체는 공개값이다). 초기화 스크립트는 앱을 멈추고 DB를 지운 뒤 덤프로 되돌리고 앱을 다시 켠다(V21 트리거는 DB째 지우므로 걸리지 않는다).
- **인스턴스 공유:** 결정 9에서 따로 쓰기로 정했다.

### 범위 밖
원격 백업, 관측 스택 배포, 다중 호스트·관리형 DB, 실제 AI 공급자 연결(평가는 별도 승인).

### D7 완료 기록 — 저장소 안 단계(2026-10-19)
결정 1·2·4·5·6·9·10·11·12·13을 저장소 안에서 구현하고 확인했다. 결정 3·7·8·14와 1의 Let's Encrypt 발급, 9의 실제 호스트 메모리는 AWS 단계(`docs/operations/deploy-aws.md`)에서, 15는 저장소 공개 전에 한다. 구현하며 정한 것: migration은 앱이 아니라 같은 이미지의 일회용 `migrate` 컨테이너가 소유자로 한다(앱의 라우팅 DataSource에서 Flyway 연결을 파생할 수 없었고, 앱이 소유자 비밀번호를 들고 있으면 계정 분리의 뜻이 줄어든다). 기준 덤프는 CI가 이미지에 담아 ECR로 보낸다(SSH를 열지 않으므로). 연결 한도는 forward 훅에 건다(Docker의 DNAT). 수용 기준 11의 「전체 IT가 분리된 계정 구성에서 통과」는 `DatabaseRolesIT`(모든 테이블의 권한을 규칙과 대조)와 배포 구성 로컬 검사(실제 앱이 baton_app으로 로그인·초기화 흐름 수행)로 대신했다. 60여 개 IT를 계정 둘로 다시 짜는 것보다 권한 자체를 대조하는 편이 새 테이블까지 잡는다. 결과는 `evidence/D7.md`.

