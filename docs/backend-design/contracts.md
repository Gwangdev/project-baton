# 백엔드 계약 변경 명세

상태: 2026-09-14 설계 제안으로 작성했고, W00에서 루트 OAS/DBML/SPEC과 계약 검사에 반영했으며 구현은 52개 operation 모두 끝났다. 이후의 개정은 각 절에 날짜와 함께 적고, W11.1·W11.2 개정은 C11에 모았다. 기존 46개 API 전체 목록은 [api-inventory.md](api-inventory.md), 정책은 [README.md](README.md)에 있다.

## C00. 충돌 판정과 수치

| 기존 근거 | 발견 사항 | 구현 지시 |
|---|---|---|
| STATE/LOG | 39/41 operations 및 발표 종료 전 상태 | 현재 OAS 46개가 기준. 과거 기록 삭제 없이 역사로 이동 |
| DBML users | credential_hash varchar(64), SHA-256; 2단계 SSO 언급 | 로컬 인증은 varchar(255), adaptive hash, SSO는 nullable; SHA-256 구현 금지 |
| OAS signIn / security | bearerAuth인데 응답 CurrentUser에 token 없음 | C01 쿠키 세션으로 일관화 |
| OAS askQuestion / NFR | 201 JSON만 있고 스트리밍 없음 | C07에 200 SSE content 협상 추가 |
| handover_items | isRequired 있으나 확인 저장·API 없음 | C06 explicit acknowledgement |
| assessment_questions | 객관식 enum 있으나 choice 저장·응답 없음 | C08 choices/정답 서버 전용 |
| record_sources | 외부 문서 ID/version/본문 저장 없음 | C04 dedup·원문·처리상태 |
| DBML 19 tables | 임베딩·실행 복구·세션·감사 없음 | C09 운영 테이블 추가; 19개 고정하지 않음 |
| OAS DELETE project member | isActive 안내지만 실제 leftOn 모델 | leftOn으로 종료; 참조 검사에 프로젝트+사용자 의미상 이력 포함 |
| OAS DELETE organization | 소속 프로젝트 검사를 요구하지만 project.organization_id 없음 | 조직은 부서, 프로젝트는 부서 소속이 아님; 조직 삭제는 users 참조 검사로 설명 정정 |
| demo repository | 코드형 string ID, 부분문자열 채점, 호출마다 생성 진척 | 서버 int64 ID·DB 작업 상태·결정적 채점으로 교체 |
| 설계 해설/trace | DELETE 0개 문구와 화면 기반 과대 연결 | baseline은 역사 자료. 새 목록으로 개수·직접 FR 대응 검증 |

원본 기획의 서비스 가치·private·사람 확정 규칙은 유지한다. 충돌이 본 표에 없으면 구현자가 자의로 OAS/DB를 바꾸지 않고 파일·항목·선택지를 기록한다.

## C01. 공통 HTTP 계약

- OAS 3.0.3 유지, API prefix `/api/v1` 정확히 한 번. 기존 operationId 유지.
- `cookieAuth: {type: apiKey, in: cookie, name: BATON_SESSION}` 전역 적용. signIn 및 csrf는 `security: []`. JWT 토큰 발급·refresh API는 만들지 않는다.
- 추가 `GET /auth/csrf` operationId `getCsrfToken`, 200 `{token: string, headerName: "X-CSRF-TOKEN"}`, Cache-Control=no-store. Spring 표준 CSRF repository/handler 사용. CSRF 없는 상태변경은 403 Problem.
- signIn 200 CurrentUser 유지, Set-Cookie로 세션 발급. 로그인 오류 400/401/403/429 유지. 실패 횟수 제한은 계정+IP 조합 5회/5분, 사용자 존재 여부와 무관한 401 메시지; 활성 여부 403은 비밀번호 검증 후만.
- signOut 204: 유효 세션은 제거, 만료 세션도 CSRF 보호를 충족하면 204. 일반 bearer 401 설명 제거. 클라이언트는 토큰 오류 시 csrf 재취득 1회 후 로그아웃 재전송 가능.
- Content-Type=application/json, 오류 application/problem+json. 상태 400/401/403/404/409/412/413/415/422/429/503를 사용하는 operation에 명시. 422는 구조상 유효하지만 자료 부족 등 추가 제안이 필요할 때 사용하지 말고 본 설계에서는 업무 충돌 409로 통일(422 신규 사용 없음). 지원하지 않는 Accept는 406.
- 5xx(2026-09-24 개정): 모든 operation은 `500 InternalServerError`를 선언한다. 본문은 Problem이며 `code=INTERNAL_ERROR`, `traceId`, 고정 detail만 싣고 예외 메시지·스택·SQL·원문은 싣지 않는다. 서버 로그에는 예외 타입, 첫 애플리케이션 프레임, 같은 traceId만 남긴다. durable job을 적재하는 command(`requestHandover`, `retryHandoverGeneration`, `regenerateHandover`, `retrySourceProcessing`, 그리고 W07부터 EMBED job을 같은 트랜잭션에 적재하는 `reviewRecord`, `sharePersonalNoteSummary`)는 `503 ServiceUnavailable`을 선언하고, 큐 포화 시 `code=QUEUE_FULL`, `Retry-After: 30`을 반환하며 업무 행·job·idempotency key를 남기지 않는다(README §6). `askQuestion`의 503은 C07을 따른다. 404·405·406·413·415처럼 프레임워크가 거절한 요청은 원래 상태를 유지하고 Problem 본문만 받는다. 서블릿 필터 안의 실패, 보안 방화벽 거절(400), 컨테이너 오류는 MVC 예외 처리기에 닿지 않으므로 컨테이너 오류 dispatch(`/error`)에서 같은 Problem 형식과 code 규칙으로 렌더링한다. Spring Boot 기본 오류 컨트롤러는 응답 media type을 바꿀 수 없어 대체한다. 오류 dispatch만 인증 없이 통과하고, 클라이언트의 `/error` 직접 요청은 401 또는 404만 받는다.
- Problem 필드: 기존 type/title/status/detail/instance/errors 유지 + `code:string`, `traceId:string`. 안정적인 code 예: ACCESS_DENIED, INVALID_INPUT, STALE_VERSION, INVALID_STATE, REFERENCE_CONFLICT, QUEUE_FULL, INSUFFICIENT_EVIDENCE. 내부 stack/SQL/원문 금지.
- int64 ID는 양수이며 JS 안전 범위 `<=9007199254740991`을 OAS maximum과 DB CHECK에 명시한다. 표시용 code는 경로 ID가 아니다. code는 고정 prefix+DB sequence, max(id)+1 금지.
- 날짜 yyyy-MM-dd, timestamp UTC RFC3339; PATCH는 미전달=유지, 명시 null=nullable 필드 삭제. 일반 DTO nullable만으로 두 상태를 합치지 않고 필드 존재 여부를 보존한다. 비nullable null은 400.

### 페이지와 입력 상한

목록은 공통 `limit=20, 1..100`, `offset=0, 0..10000`를 받는다. 기존 items-only 목록(SourceConnectionList, PersonalNoteList, HandoverList, HandoverItemList)에 page를 추가한다. total은 권한 필터 후 개수. admin 목록도 상한을 적용한다. 기본 정렬은 `(created_at DESC, id DESC)`, timestamp 없는 항목은 `(display_order ASC,id ASC)`. 타임라인은 `(occurred_on DESC,id DESC)`로 고정하고 날짜·유형·신뢰 필터만 허용한다.

offset 상한 초과는 400; 전체 이력 접근을 차단하지 않도록 record와 qa 목록은 `cursor` 선택 입력과 `nextCursor`를 추가한다. cursor와 nonzero offset 동시 입력은 400. cursor는 마지막 sort key와 id의 base64url JSON이며 필터·권한을 대체하지 않는다. 임의 cursor라도 해당 사용자의 필터가 항상 적용된다. 날짜+id 복합 비교, 동일 날짜 중복/누락 테스트 필수. UI 무한 스크롤은 cursor를 사용한다. 대시보드 projects·connectionHealth 각각 최대 20, counts는 별도 정확 집계, 전체 조회는 각 목록 API를 이용한다.

문자열 maxLength는 DB varchar 길이와 일치시킨다. text 추가 상한: summary 4,000자, note body 10,000자, original excerpt 10,000자, question 2,000자, answer 4,000자, approval comment 500자. 자유 외부 본문은 100,000자 별도. max request body 1MiB(JSON). 미래 파일 수집 10MiB는 HTTP JSON 요청 크기와 구분한다. page·count는 0 이상. 빈 PATCH, 알 수 없는 변경 필드, enum 미지원 값은 400.

### 동시 수정과 중복 요청

organizations/users/projects/members/connections/records/handovers/items DTO에 `version:int64` 추가. 각 행 version bigint default 0 not null. GET 상세 ETag=`"<version>"`; 목록의 version으로도 If-Match를 만들 수 있다. PATCH, DELETE, record review, handover approval/retry/generations, item acknowledgement에 If-Match 필수, 미전달 428, stale 412. 해당 응답에 428/412/ETag를 OAS에 추가한다. record review 성공 DTO에도 새 version을 반환한다.

업무 생성·command인 POST(signIn/signOut 제외)와 모든 PATCH·DELETE에 `Idempotency-Key` UUID 필수(2026-09-28 개정: OAS와 구현이 이미 PATCH·DELETE에도 요구하던 범위로 문구를 맞춤), 미전달/형식 오류 400. 저장 scope는 `(actor_id, method, canonical_path, key)`, JSON canonical body SHA-256을 비교. 최초 요청과 같은 body면 저장된 상태/응답을 재전송(201/202 포함), 다른 body면 409. 진행 중 재전송은 409 REQUEST_IN_PROGRESS + Retry-After=1. 성공 결과·idempotency row·업무 쓰기는 같은 트랜잭션. 보존 24시간, 세션 변경에도 actor가 같으면 재사용. 로그아웃·권한 철회 후 재요청에도 현재 권한 검사를 먼저 수행한다. replay는 그 다음, version 검사보다 먼저 해서 성공한 요청 재전송이 412가 되지 않게 한다.

여기서 비동기 command의 성공 결과는 **작업 접수 202**다. 접수 트랜잭션에서 업무 행·PENDING job·재전송할 202 결과를 함께 커밋하고 LLM 완료를 기다리지 않는다. 이후 워커 결과가 해당 202 응답을 덮어쓰지 않는다. Q&A만 다음 2단계 규칙을 적용한다.

1. 짧은 접수 트랜잭션: 현재 권한 확인 → key UNIQUE 확보 → USER 질문·QA job·IN_PROGRESS idempotency 행 커밋. 중복 key 경쟁은 기존 행을 조회해 body 비교 후 replay/409로 처리한다.
2. 트랜잭션 밖 검색·모델 호출·문장 검증·전송. 외부 호출 동안 DB lock/connection을 점유하지 않는다.
3. 짧은 종결 트랜잭션: lease token과 현재 권한 재검증 → ASSISTANT 결과·인용·job 종결·COMPLETED idempotency 응답 함께 커밋. 이후 done 전송. DB 커밋 실패 시 done을 성공으로 보내지 않는다. 이미 보낸 delta는 done 전까지 임시 표시다.
4. 중단·timeout은 복구 작업이 FAILED 답변과 재전송할 실패 결과를 같은 트랜잭션으로 종결한다. 권한 철회라면 이후 replay는 거부한다. 접수 후 30초 deadline과 만료 lease가 모두 지난 미완료 건을 복구하며, 살아 있는 lease를 강탈하지 않는다. 종결 전 프로세스 중단을 테스트한다.

idempotency에 `state`(IN_PROGRESS/COMPLETED), `created_at`, `target_id`(Q&A USER 메시지 ID; 그 외 nullable)를 저장한다. SSE도 최종 결과는 canonical JSON으로 저장하고 replay 시 done 이벤트로 변환한다. 유효한 IN_PROGRESS 행은 24시간 정리 대상에서 제외한다. 인증 실패·입력 오류처럼 업무 접수 전 실패는 key를 소비하지 않는다.

## C02. 관리자·사용자

users credential_hash는 nullable varchar(255), `auth_provider varchar(16) NOT NULL`(LOCAL|OIDC), `external_subject varchar(255) NULL`, `external_issuer varchar(500) NULL` 추가. OIDC이면 issuer+subject 필수, 로컬 비밀번호와 동시 사용 금지. LOCAL 신규 계정의 null credential은 초대/초기화 전 로그인 불가 상태. dev seed만 환경변수로 credential 설정, 범용 비밀번호 변경 API 임의 추가 금지. OIDC는 P2이며 미연결 상태에서 사용 가능하다고 보고하지 않는다.

SYSADMIN은 global 관리 권한만 가지며 자동으로 업무 내용 접근권을 얻지 않는다. 마지막 활성 SYSADMIN을 비활성화·삭제·강등하거나 그 소속 organization을 비활성화하면 409. 조직 비활성화는 그 소속 사용자의 다음 요청부터 차단. 프로젝트 비활성화는 진행 중 handover가 있으면 409, 그렇지 않으면 목록·접근에서 제외하되 이력 보존.

hard DELETE는 기존 5개만 유지한다. FK restrict에 더해 project member는 `(project_id,user_id)`의 기록 생성/확인/인수인계 요청·수신·승인/평가 이력 존재 시 409. 선행 count만 믿지 않고 DB FK 예외도 409로 매핑한다. 감사 로그만 있는 오생성 자원은 삭제 가능(감사의 target ID는 FK가 아님); 이미 업무에 쓰인 대상은 삭제하지 않는다. `references`는 숫자와 일반 자원명만, private 본문·ID 목록은 반환하지 않는다.

## C03. 기록과 관계

work_records 추가 `version`, `source_version varchar(128)`, `search_text text`(공유 요약으로만 구성). record_revisions 추가 `before_data jsonb`, `after_data jsonb`; 기존 summary 필드 유지. 확정 기록은 본 설계에서 직접 PATCH하지 않는다. 새 source version은 새 AUTO_DRAFT를 생성하고 PM이 확정 후 대체 관계를 등록한다.

추가 `POST /projects/{projectId}/record-relations`, operationId `createRecordRelation`: PM 전용, body `{fromRecordId:int64,toRecordId:int64,relationType:RecordRelationType,note:string(max300)}`, 필수 앞 3개. 201 RecordRelation + id/version, 400/401/403/404/409. same-project·자기참조 금지·CONFLICTS_WITH 정렬·SUPERSEDES cycle 검사. 관계 삭제/수정은 미제공; 오류 관계 교정 UX는 후속 개정 대상이므로 fixture 외 실제 운영 연결 전 검토한다.

DB CHECK: source XOR; CONFIRMED/CORRECTED iff confirmed_by와 confirmed_at 둘 다 not null; draft/dismissed는 둘 다 null. due_on은 occurred_on보다 빠를 수도 있는 과거 약속이므로 임의 CHECK 추가 금지. project.started_on<=ends_on, scope_from<=scope_to, outgoing!=incoming, relation.from!=to, conflict.from<to는 CHECK.

서로 다른 프로젝트 관계는 CHECK의 서브쿼리로 구현하지 않는다. record_relations에 project_id 추가, work_records의 UNIQUE(project_id,id)와 두 composite FK로 보장한다. handover_items와 snapshot에도 project_id를 추가하여 handover와 record의 composite FK 일치를 보장한다. source/note 프로젝트 일치도 동일 composite FK를 사용하며 필요한 UNIQUE 쌍을 migration에 명시한다.

## C04. 수집과 수동 복구

record_sources 추가: `project_id bigint NOT NULL`, `external_document_id varchar(300) NOT NULL`, `external_version varchar(128) NOT NULL`, `content_hash char(64) NOT NULL`, `source_body text NOT NULL`, `processing_status varchar(20) NOT NULL`(PENDING/PROCESSING/SUCCEEDED/FAILED), `failure_code varchar(60) NULL`, `source_author_name varchar(120) NULL`. UNIQUE(connection_id에 해당하는 기존 source_connection_id, external_document_id, external_version). source_body는 소유자 또는 허용된 공용 검토자에게만 접근 가능한 내부 원문이며 로그·RAG index에 넣지 않는다.

외부 작성자가 users에 없을 수 있으므로 work_records.author_id를 nullable로 변경하고 `author_name varchar(120) NOT NULL` 추가한다. original author와 확인자는 다르다. 이름 미확인 시 `알 수 없는 작성자`, principal을 외부 작성자로 위조하지 않는다. 메모 공유는 author_id=작성자이고 표시 이름도 그 사용자의 당시 이름.

source_connections 추가 `connector_kind varchar(32)`(FIXTURE|외부 공급자 코드), `checkpoint text NULL`, `version`. 로컬은 FIXTURE만 사용. 개인 연결 owner_user_id는 요청 DTO에서 받지 않고 서버가 지정. fixture 입력은 프로젝트에 명시적으로 둔 합성 파일로만 제한하고 개인 PC 디렉터리 감시 기능으로 확장하지 않는다. 외부 공급자 OAuth secret은 별도 secret reference만 저장한다.

추가 `POST /connections/{connectionId}/files/{sourceId}/records`, operationId `createManualDraft`: 해당 source 원문 접근·검토 권한, body `{recordType,title,summary,originalExcerpt,occurredOn,ownerUserId?,dueOn?}`, 필수 앞 5개, 문자열 상한 C01. 201 WorkRecord(AUTO_DRAFT). AI 실패 시 수동 복구, 즉시 확정하지 않음. 모든 원문은 source의 권한 경계를 유지한다.

추가 `POST /connections/{connectionId}/files/{sourceId}/processing-attempts`, operationId `retrySourceProcessing`: FAILED source만, 해당 검토 권한, body 없음, 202 `{sourceId,status:"PENDING"}`, Location은 기존 files 목록 경로. CollectedFile에 processingStatus/failureCode 추가해 상태를 관측한다. 다른 connection의 source는 404. 중복 실행 방지로 같은 source version의 생성 record에 UNIQUE(record_source_id, source_version, extraction_index)를 추가(extraction_index integer not null default 0; manual은 source 내 sequence로 새 번호).

## C05. 생성·snapshot

handovers 추가 `generation integer NOT NULL DEFAULT 1`, `version`, `revision_comment varchar(500) NULL`. project_id와 id UNIQUE. scope 변경은 기존 행 PATCH가 아니라 재생성 command로 제한한다.

추가 `POST /handovers/{handoverId}/generations`, operationId `regenerateHandover`: PM, If-Match 필수, READY/REVIEW_REQUESTED/FAILED만. body `{scopeFrom,scopeTo,scopeRecordTypes,includeNoteDerivedRecords}` 전부 필수. 기존 관계자·effectiveOn은 유지. 202 Handover, Location 기존 상세. 이전 generation은 보관, active generation만 증가. 기존 retry는 FAILED의 같은 snapshot/generation만 재시도한다. 재생성은 최신 확정 records로 새 snapshot을 만든다.

handover_generations: PK id bigint, handover_id FK, generation int, project_id, created_by, status, created_at, published_at nullable, UNIQUE(handover_id,generation), prompt_version nullable. handover_snapshot_records: PK id bigint, generation_id FK, project_id, work_record_id FK, record_version bigint, content_json jsonb, trust_status, captured_at, UNIQUE(generation_id,work_record_id). snapshot에는 공개 요약과 안전한 출처 식별만, private 전문은 제외한다.

패키지 생성 전 snapshot을 접수 트랜잭션에서 확정한다. 동일 generation retry는 기존 snapshot을 사용한다. 포함 최대 5,000 records, 초과 시 409 SCOPE_TOO_LARGE로 scope 축소를 안내한다. payload 생성은 chunk 처리하되 최종 publish만 짧은 트랜잭션에서 수행한다. 일부 items만 화면에 노출하지 않는다. handover.items·assessment 모두 같은 generation_id FK. assessments의 기존 UNIQUE(handover_id)를 UNIQUE(handover_id,generation)으로 변경한다.

FOLLOW_UP은 이번 snapshot의 BLOCKER/OPEN_ISSUE만 만든다. OPEN_RISK와 FOLLOW_UP이 같은 근거를 가질 수 있으나 UNIQUE(generation_id,item_kind,work_record_id)로 같은 종류 중복은 차단. FOLLOW_UP assignee=incoming user, due=record.due_on 없으면 handover.effective_on. 미래 일정이 없는 위험에도 표시 가능한 책임·재검토일을 부여한다. includedCount는 snapshot record 수이며 items 수가 아니다.

handover_item_conflicts: PK id, handover_item_id FK, relation_id FK, from_snapshot_id FK, to_snapshot_id FK, UNIQUE(handover_item_id,relation_id). 하나의 HandoverItem이 하나의 충돌만 나타내도록 CONFLICTS_WITH 관계는 정규화된 from record당 하나로 제한한다. snapshot 안의 두 후보 중 하나만 resolvedByRecordId로 선택 가능. 프론트엔드 ITEM-CF 문자열 prefix로 충돌을 판단하지 않는다. HandoverItem에 `conflictId` nullable, `candidateRecordIds:int64[]` 추가. 구세대 항목, 다른 handover 항목은 404/409.

## C06. 필수항목 확인

추가 `POST /handovers/{handoverId}/items/{itemId}/acknowledgements`, operationId `acknowledgeHandoverItem`. 지정 후임자, 현재 generation READY 또는 REVIEW_REQUESTED, If-Match=item.version, body 없음. 201 `{itemId,userId,generation,acknowledgedAt}`. 같은 후임자의 같은 generation 항목 재확인은 200 동일 결과. 400/401/403/404/409/412/428.

handover_item_acknowledgements: PK id, generation_id FK, item_id FK, user_id FK, acknowledged_at; UNIQUE(generation_id,item_id,user_id). HandoverItem 응답에 generation/version/acknowledged(boolean, 요청자 기준) 추가. PM은 approvalBlockers로 미확인 필수 수를 본다. GET을 읽은 것으로 간주해 DB 상태를 변경하지 않는다.

## C07. Q&A SSE

기존 POST /handovers/{handoverId}/questions와 operationId 유지. `Accept:application/json`은 기존 201 QaAnswer. `Accept:text/event-stream`은 200 streaming. JSON 기본 응답은 그대로 지원하여 UI 전환을 단계화한다. 브라우저는 POST body·CSRF를 지원하는 fetch readable stream을 사용한다.

이벤트 payload는 JSON:

| event | 필드 | 의미 |
|---|---|---|
| meta | questionId:int64, generation:int, traceId:string | 접수 식별; 첫 답변 시간으로 세지 않음 |
| evidence | citations:QaCitation[] | 서버 권한 검증 후보 |
| delta | text:string | 검증된 문장; max 4,000자 전체 합 |
| done | answer:QaAnswer | DB 저장 완료된 결과 |
| error | code:string, retryable:boolean, traceId:string | stream 개시 후 실패; HTTP status 변경 시도 금지 |

순서 meta → evidence → delta 0..N → done 또는 error 하나. NO_EVIDENCE는 delta 없이 done. 최초 accept 전에 401/403/404/429/503 등 Problem으로 반환. client disconnect는 취소 신호로 전달하고 미완성 답변은 FAILED, failure_code=CLIENT_DISCONNECTED. 재접속 SSE resume은 미지원; 새 질문 key로 다시 요청한다. 같은 Idempotency-Key는 저장된 최종 answer를 단일 done으로 replay하며 이미 진행 중이면 409.

qa_messages에 generation_id FK, reply_to_id self FK nullable, failure_code varchar(60) nullable, model_id varchar(120) nullable, prompt_version varchar(60) nullable 추가. USER/ASSISTANT 각 행 구분, assistant.reply_to_id UNIQUE. 처리 상태는 jobs에 두고 공개 QaAnswerStatus는 기존 3종을 유지한다. DB 재시작/worker 중단 후 미완성 USER 질문은 복구 스캐너가 FAILED assistant로 종결한다. qa_citations에 snapshot_record_id FK 추가; 당시 제목·요약은 snapshot에서 조회한다.

## C08. 평가

assessment_questions에 `choices jsonb NULL`, `accepted_answers jsonb NULL`, `correct_choice_id varchar(32) NULL`, `generation_id FK` 추가. MULTIPLE_CHOICE는 choices 배열 2..5개와 correct ID 하나 필수, SHORT_ANSWER는 accepted_answers 비어 있지 않은 배열 필수. choices는 `{id:string(max32),text:string(max300)}`. question_type과 저장 필드의 상호배제 CHECK를 둔다. 기존 expected_answer는 마이그레이션 시 정답 데이터로 변환 후 신규 쓰기에서 사용하지 않고 제거한다.

공개 AssessmentQuestion에는 choices만 추가. 답안 answerText는 객관식에서는 choice ID, 단답형은 사용자 문자열. expectedAnswer/acceptedAnswers/correctChoiceId는 응답 금지. AssessmentDetail에 generation 추가. 구세대 제출은 409. fixture 예제: `2026-10-22`, `10월 22일`, `10/22`는 acceptedAnswers에 명시하면 허용, `10월`은 오답.

실모델 생성 문항은 답을 추측하는 자유 생성으로 만들지 않는다. 초기 P1은 확인된 일정·기한·유형·담당자·요약에 대한 검수된 템플릿을 사용한다. 구조화된 필드가 부족한 문항은 생성 실패로 처리한다. 자유형 AI 문항/LLM 채점은 P2. 따라서 PM 문항 승인 API를 추가하지 않는다.

## C09. 운영 데이터와 migration

기존 19 업무 tables 유지·개정 + 신규 업무운영 tables 8개 + Spring Session 표준 tables 2개 = **29개 예상**(Flyway 이력 테이블 제외). 실제 migration과 DBML의 이름 집합을 검사하며 단순 개수만 맞추지 않는다.

| 신규 table | 필수 저장 계약 |
|---|---|
| jobs | id uuid PK, kind varchar(32), target_id bigint, generation int, dedup_key varchar(255) UNIQUE, status varchar(20), attempts int default0, available_at/deadline_at timestamptz, lease_token uuid nullable, lease_until timestamptz nullable, failure_code varchar(60) nullable, created_at/updated_at; CHECK attempts>=0 |
| idempotency_requests | id bigint PK, actor_id FK, method varchar(8), path varchar(300), key uuid, request_hash char(64), state varchar(20), target_id bigint nullable, http_status int nullable, response_json jsonb nullable, created_at/expires_at timestamptz; UNIQUE(actor_id,method,path,key); COMPLETED는 http_status 필수 |
| audit_events | id bigint PK, actor_id bigint nullable, action varchar(80), target_type varchar(80), target_id varchar(80), result varchar(32), trace_id varchar(64), occurred_at; actor/target는 삭제와 독립 보존, 원문 없음 |
| handover_generations | C05의 세대 정보 |
| handover_snapshot_records | C05의 허용된 근거 snapshot |
| handover_item_conflicts | C05의 충돌 후보 집합 |
| handover_item_acknowledgements | C06의 확인 이력 |
| record_embeddings | id uuid PK, project_id FK, record_id FK, record_version bigint, chunk_no int, chunk_text text, embedding_model varchar(120), embedding vector(dimension), created_at; UNIQUE(record_id,record_version,chunk_no,embedding_model) |

jobs.kind는 INGEST, STRUCTURE, BUILD_PACKAGE, EMBED, QA. 외부 처리 job의 target이 다른 프로젝트에 바뀌지 않도록 실행 시 업무 소유관계를 다시 확인한다. jobs에는 원문을 저장하지 않는다. session 표준 DDL은 사용 버전의 Spring Session PostgreSQL script를 Flyway에 포함하고 자동 schema init은 끈다.

Migration 순서: V1 기존 업무19 + C02/C03/C04 기반 변경; V2 jobs/idempotency/audit/session; V3 record relation/version 변경; V4 source_occurred_on 보존; V5 handover generation/snapshot; V6 handover package items; V7 handover conflict/acknowledgement; V8 assessment generation; V9 vector extension/embedding. W06.3에서 C05/C06 테이블이, W06.4에서 assessment generation이 선행되어야 하므로 vector migration은 V9로 순연한다. V9는 실모델을 고정하지 못해 `embedding vector(256)`(stub 모델 크기)으로 만든다. 다른 크기의 모델을 쓰려면 열을 다시 만드는 migration이 필요하며 한 열에 크기를 섞지 않는다. W01에 Flyway baseline 테스트, 이후 카드가 필요한 버전을 추가한다. 적용된 파일 수정 금지, 수정은 새 migration. `ddl-auto=validate`, Flyway만 schema 소유자. 자동 create/update와 `IF NOT EXISTS`로 schema mismatch를 숨기지 않는다. 테스트는 실제 PostgreSQL/pgvector Testcontainers, H2 대체 금지.

인덱스 최소: work_records(project_id,trust_status,occurred_on DESC,id DESC), personal_notes(project_id,author_id,id), project_members(user_id,project_id), qa_messages(handover_id,session_user_id,id), jobs(status,available_at), generations(handover_id,generation), snapshot(generation_id,work_record_id). W10.6(2026-09-28 개정, V12) 추가: work_records(project_id,occurred_on DESC,id DESC)와 jobs(kind,target_id,created_at DESC,id DESC). W10.7(2026-09-29·30 개정, V13·V14): work_records 상태 인덱스에 INCLUDE(record_source_id)를 더해 타임라인 total·확인 대기 수를 index-only로 센다. 보존 삭제용 부분 인덱스 jobs(status,updated_at) WHERE status IN (SUCCEEDED,FAILED)와 idempotency_requests(expires_at) WHERE state=COMPLETED를 둔다. 근거는 evidence/performance.md의 EXPLAIN이다. FK 신규 컬럼의 인덱스 필요성을 EXPLAIN으로 확인한다. 모든 source body·response_json 등 민감 저장은 DB 디스크/백업 암호화 및 접근 정책 적용 대상이다.

## C10. API 변경 개수와 추적

기존 46개는 유지, C01/C03/C04(2개)/C05/C06의 6개 추가 = **52개 업무 operations** 예상. paths는 추가 경로 6개로 **38개** 예상. Actuator 관리 경로는 업무 API 수에서 제외하되 SPEC.exclude에 제공 주체·내부 포트를 명시한다. code_roots는 backend/src/main/java와 frontend/src다. W05.G에서 Maven과 Gradle의 동등성 검증 뒤 Maven 파일을 제거하고 test_command를 `./backend/gradlew -p backend clean build`로 전환했다.

| 요구사항 | 직접 구현 operation/내부 작업 | 필수 반례 |
|---|---|---|
| FR-01 | signIn/signOut/getCsrfToken/getCurrentUser/listProjects | 다른 프로젝트·만료 세션 |
| FR-01·09·22 | getDashboard(S02) | 다른 구성원의 개인 폴더 연결·남의 비공개 초안 개수 |
| FR-01·18 | issueCredentialToken, setCredential | 관리자 아닌 발급·남의 코드·만료·재사용·평문 저장 |
| FR-02/03 | listRecords/getRecord/getProject/createRecordRelation | private 원본·교차 프로젝트 관계 |
| FR-04/05 | INGEST/STRUCTURE, createManualDraft/retrySourceProcessing | 재시작·중복 문서·AI JSON 오류 |
| FR-06/09 | reviewRecord/listRecords | 이중 확정·타인 개인 초안 |
| FR-07/08 | listPersonalNotes/createPersonalNote/sharePersonalNoteSummary | 타인 메모·중복 공유·원본 노출 |
| FR-10/11 | requestHandover/listHandovers/getHandover/regenerateHandover, listCurrentProjectMembers(D1, 담당자 선택) | 기간 밖·이전 세대·후임 미배정, 비구성원의 구성원 목록 조회 |
| FR-12 | resolveHandoverItem/createRecordRelation | 후보 외 record·잘못된 item |
| FR-13 | decideHandoverCompletion/acknowledgeHandoverItem | 미확인 필수·미달·구세대 점수 |
| FR-14 | listHandoverItems/getHandover/retryHandoverGeneration | 부분 게시·원본 변경의 침투 |
| FR-15/16 | askQuestion/listQuestions | 허위 인용·다른 사용자 대화·무근거 |
| FR-17 | getAssessment/submitAssessmentAttempt | 부분 답 문자열·정답 키 노출 |
| FR-18 | 16개 admin operations | 마지막 관리자·참조 삭제·역할 우회 |
| FR-19/21/22 | 7개 connection operations + INGEST | 잘못된 owner·상태만으로 원문 공개 |
| FR-20 | 모델/수집 장애 격리 | 모델 down 중 일반 조회·쓰기 |

C01~C10 중 문서에만 있고 OAS/DBML에 없는 필드는 구현자가 먼저 코드에 추가하지 않는다. W00의 계약 개정 결과에 차이를 남겨 검토하고 각 후속 카드가 사용하는 계약을 고정한다.

## C11. W11.1 개정 (2026-10-03, 사용자 승인)

W11 독립 검증의 지적을 고치면서 바꾼 계약이다. 공개 경로·operation 수는 그대로다(38/52).

- **선언 보강(C01 이행):** 인증된 요청이면 모든 operation이 `403 ACCOUNT_INACTIVE`를 낼 수 있으므로 403을 선언한다. 질의 파라미터가 있는 GET에 400, 본문이 있는 operation에 413(`PAYLOAD_TOO_LARGE`, 1 MiB)과 415, signOut에 401을 선언한다. 428의 code는 `IF_MATCH_REQUIRED` 하나다.
- **목록 페이지(C01 이행):** listOrganizations, listSourceConnections, listPersonalNotes, listHandovers, listHandoverItems, listProjectMembers가 `limit`·`offset`을 받는다. ProjectMemberList에 page를 더했다.
- **역할 부족은 403(README §4.2):** 접근 가능한 프로젝트에서 PM 역할이 없는 구성원이 PM 명령을 부르면 403이다. 비구성원은 404 그대로다.
- **C05 이행:** snapshot 대상이 5,000건을 넘으면 409 `SCOPE_TOO_LARGE`. scope 오류(기간 역전, 빈 유형, 알 수 없는 유형)는 400. 요청의 includeNoteDerivedRecords 생략은 true.
- **인수인계 상태(README §5.2 이행):** 워커가 패키지 job을 가져가면 GENERATING이다. job이 재시도를 소진하거나 2시간 기한을 넘기면 dispatch 주기의 정리가 handover를 FAILED로 돌리고 failureReason에 `MAX_ATTEMPTS_EXCEEDED`·`DEADLINE_EXCEEDED`를 남긴다.
- **응답 필드:** HandoverDetail에 revisionComment를 더하고 excludedCount를 뺐다(migration V15가 `handovers.excluded_count`를 지운다. 쓰는 코드가 없어 늘 0이었다). 202 본문은 Handover 전체다. User(`/me`)에 authProvider·organizationName을 채우고 미선언 createdAt을 뺐다. HandoverItem의 evidence·assigneeName·dueOn과 WorkRecord의 supersededByCode를 채운다. createManualDraft의 201은 RecordDetail이다. 연결 상세의 lastStatus·lastMessage는 nullable이다.
- **ETag(C01 이행):** getHandover 200이 version을 ETag로 보낸다.
- **관계 규칙(README §5.1 이행):** 확정 기록끼리만 등록하고, 이미 다른 기록이 대체한 기록을 다시 대체하면 409다.
- **구성원 재배정:** 종료된 배정의 사용자를 다시 추가하면 같은 배정 행을 되살린다(201, 같은 id). 진행 중 배정의 중복은 409다.
- **최초 관리자:** 빈 DB에서 `BATON_BOOTSTRAP_ADMIN_*` 다섯 값이 모두 있으면 SYSTEM_ADMIN 1명을 만든다. 일부만 있으면 기동을 멈춘다.
- **검증 수단:** 통합 테스트의 모든 MockMvc 응답을 OAS와 대조하는 `responseContract` 작업을 `check`에 넣었다. `DbmlConsistencyIT`가 DBML과 migration 결과(테이블·컬럼·인덱스 열)를 대조한다.

### W11.2 개정 (2026-10-04 설계 승인, 같은 날 사용자 결정 「권고대로 진행」)

W11.1 재검증의 지적을 고치면서 바꾼 계약이다. 공개 경로·operation 수는 그대로다(38/52). migration은 없다.

- **멱등 재전송도 권한을 다시 본다(C01):** requestHandover·retryHandoverGeneration·regenerateHandover가 저장된 응답을 돌려주기 전에 현재 PM 권한을 확인한다. PM 역할을 잃었으면 403, 프로젝트 구성원이 아니면 404다(다른 PM 명령은 이미 그랬다).
- **해소 사유는 300자:** `resolveHandoverItem`의 resolutionNote는 `maxLength: 300`이다. DB 열 `handover_items.resolution_note`가 varchar(300)이라서 500자까지 받던 앱 검증을 맞췄다. 301자 이상은 400이다.
- **본문 정수 ID와 cursor는 400(C01):** ID는 1 이상 2^53-1 이하의 정수 하나이고, 범위 밖·소수·문자열은 400 INVALID_INPUT이다. listRecords의 cursor가 날짜를 해석하지 못하거나 id가 정수가 아니어도 400이다.
- **지정한 사용자가 구성원이 아니면 400:** requestHandover의 outgoingOwnerId·incomingOwnerId, createManualDraft·sharePersonalNoteSummary·reviewRecord(CORRECT)의 ownerUserId가 프로젝트의 현재 구성원이 아니면 400 INVALID_INPUT이고 `errors[].field`에 필드 이름이 담긴다. 요청자 자신이 구성원이 아니거나 원문이 보이지 않으면 404 그대로다.
- **인수인계 version:** REQUESTED → GENERATING 전이도 version을 1 올린다. 다른 모든 전이와 같다.
- **AssessmentStatus:** OAS enum은 `NOT_STARTED`·`SUBMITTED`다. DB enum `assessment_status`의 `IN_PROGRESS` 값은 남긴다. 어떤 코드도 쓰지 않지만 PostgreSQL은 enum 값을 지우려면 타입을 다시 만들어야 하고(열을 옮기는 migration), 값 하나를 위해 그 위험을 지지 않기로 했다. 다음 enum 개정 때 함께 지운다.
- **죽은 표면:** 종류를 받지 않는 `JobLeaseService.claim()`을 지웠다(테스트만 불렀다). `JobSlots.availableSlots()`는 운영 게이지 `baton.job.slots.available`(README §8의 포화 신호)의 원천이고 포화를 관측하는 테스트(FailureIsolationIT·IngestionWorkerIT·JobSlotsTest)도 쓰므로 남기고 Javadoc에 사유를 적었다.
- **검증 수단(지적 59·60):**
  - `responseContract`는 자체 검사기를 유지한다. 외부 OpenAPI 검증 라이브러리는 새 의존성의 내려받기와 검토가 필요하고, 이 계약이 쓰는 규칙(OAS 3.0의 nullable·allOf, 필수·미선언 필드, enum, 타입)은 검사기가 이미 다룬다. 이제 다음도 실패로 본다: 관측 0건, 호출되지 않은 operation, 500 응답(의도한 장애 주입은 사유를 적은 `test/resources/contract/intended-server-errors.txt`에 있는 것만 예외), 더는 생기지 않는 허용 항목. 검사하지 않는 범위(파라미터·헤더·maxLength·format, MockMvc 밖 HTTP)는 클래스 Javadoc에 적었다.
  - `DbmlConsistencyIT`는 열 이름·인덱스 키 열에 더해 NOT NULL, char·varchar 길이, 인덱스의 unique 여부를 대조한다. 형 이름, 기본값, FK, CHECK, enum 값은 범위 밖이라고 Javadoc에 적었다. 대조에서 드러난 차이(handover_generations의 prompt_version 길이와 status 타입)는 migration에 맞춰 DBML을 고쳤다.

### W11.2.1 개정 (2026-10-05, 사용자 지시)

W11.2 검증의 지적 77·84를 고치면서 바꾼 계약이다. 경로·operation 수·migration은 그대로다.

- **지정 사용자 오류의 범위:** 위 「지정한 사용자가 구성원이 아니면 400」은 requestHandover·createManualDraft에서 sharePersonalNoteSummary와 reviewRecord(CORRECT)로 넓어졌다. 요청이 사용자를 지정하는 모든 operation이 같은 규칙이다. requestHandover의 두 담당자가 같은 사람이면 409 INVALID_STATE였던 것을 400 INVALID_INPUT(incomingOwnerId)으로 바꿨다. 요청 본문 자체의 모순이지 저장된 상태와의 충돌이 아니고, 이전 응답은 같은 요청의 다른 필드 오류(400)와 코드가 갈렸기 때문이다.
- **migration 주석(지적 70):** V10·V13·V14·V15의 주석에서 작업 카드 번호를 뺐다(SQL은 그대로다). 주석만 고쳐도 Flyway 체크섬이 바뀌므로, 이미 적용한 데이터베이스는 `flyway_schema_history`의 체크섬을 새 값으로 바꿔야 기동 검증을 통과한다. 보존 중인 compose volume 7개는 2026-10-05에 그렇게 맞췄고, load baseline 덤프는 복원할 때마다 `tests/load/flyway-checksums.sql`(baseline.sh restore가 실행)로 맞춘다. 새로 만드는 데이터베이스에는 필요 없다.

### W12 개정 (2026-10-05, 사용자 승인)

관리자가 만든 로컬 계정이 로그인할 수 없던 공백(README §4.1이 「별도 초기 자격증명 준비」로 미룬 것)을 메운다. 경로는 38 → **40**, operation은 52 → **54**, 스키마는 83 → **85**, 업무·운영 테이블은 29 → **30**이다(migration V16).

- **일회용 설정 코드:** `POST /admin/users/{userId}/credential-tokens`(시스템 관리자)가 활성 LOCAL 계정에 256비트 코드를 발급한다. 코드는 **응답에만 평문으로 나가고** DB에는 SHA-256 해시만 남는다(고엔트로피 난수라 느린 해시가 필요 없다). 사용자당 쓰지 않은 코드는 하나라서 다시 발급하면 이전 코드는 무효다. 유효 시간은 기본 24시간(`baton.auth.credential-token-ttl`).
- **설정:** `POST /auth/credentials`(인증 불필요, CSRF 적용)가 사번·코드·새 비밀번호를 받아 비밀번호를 정한다. 성공은 204이고 자동 로그인하지 않는다. 그 사용자의 기존 세션은 모두 종료한다. 없는 사번·틀린·만료·사용된·남의 코드는 **모두 같은 400 `INVALID_CREDENTIAL_TOKEN`**이고(계정 열거 방지), 같은 비용의 해시를 한 번 계산한다. 로그인과 같은 실패 제한(5회/5분)이 적용된다.
- **비밀번호 규칙:** 12~128자, 사번과 다름(NIST SP 800-63B의 방향: 길이 하한만, 구성 규칙 없음). 어기면 400 INVALID_INPUT(필드 newPassword)이고 코드는 쓰이지 않는다. 최소 길이는 `baton.auth.password-min-length`다. 값은 회사 정책이 정해지기 전의 설계 가정이다.
- **멱등 예외(C01):** 두 명령은 Idempotency-Key를 받지 않는다. 발급은 멱등 재전송이 응답을 저장하는데 그 응답이 평문 코드라서이고, 설정은 코드를 한 번 쓰면 끝나는 것이 멱등 장치라서이다. 계약 검사기는 `AUTH_EXEMPT_WRITES`에 이 둘을 사유와 함께 넣었다.
- **세션 종료:** `CurrentActor`가 `Principal`(이름 = 사번)이 되어 Spring Session이 사용자별로 세션을 찾는다. 재설정은 그 세션을 지운다.
- **보존 정리:** 사용되었거나 만료 하루가 지난 코드는 `RetentionSchedule`이 지운다(보존 정책과 무관한 운영 비밀이라 항상 켠다).
- **범위 밖:** 코드 전달(메일·메신저), 「비밀번호를 잊었어요」 셀프서비스(관리자 재발급으로 갈음), 본인의 현재 비밀번호를 아는 상태의 변경, MFA, 계정 잠금, OIDC.

### W12.1 개정 (2026-10-05, 사용자 결정)

- **업무일은 단일 시간대 Asia/Seoul:** 해외 출장 근무는 네트워크가 달라지는 별개의 문제로 보고 시간대를 사용자·프로젝트별로 나누지 않는다. `BusinessCalendar`(Java의 「오늘」)와 모든 DB 연결의 초기 `SET TIME ZONE 'Asia/Seoul'`이 같은 날을 가리키게 했다. 이전에는 PostgreSQL 드라이버가 JVM 기본 시간대로 세션을 열어서, TZ 없이 기동한 서비스(컨테이너 기본은 UTC)는 `current_date`가 서울보다 아홉 시간 늦었다(compose는 TZ를 맞춰 두었을 뿐이다). 저장 timestamp는 UTC 그대로다.


### W13.1 개정 (2026-10-05, 사용자 지시)

W11.2.1~W13 검증의 지적 87~104를 고치면서 바뀐 계약이다. 경로·operation·스키마 수는 그대로이고 migration V17 하나(`audit_events.remote_address`)와 OAS 응답 하나가 늘었다.

- **설정 코드의 잔여 위험과 감사(지적 87):** 코드는 가진 사람이 쓸 수 있으므로 코드를 발급한 관리자는 그 계정의 비밀번호를 정하고 그 계정으로 로그인할 수 있다. 일회용 코드 방식(방식 B)의 본질이라 막지 않는다. 대신 `ISSUE_CREDENTIAL_TOKEN`과 `SET_CREDENTIAL` 감사 행에 요청 주소(`audit_events.remote_address`, null 허용, 두 이벤트만 채운다)를 남겨 발급 주소와 설정 주소를 사후에 비교할 수 있게 했다. 설정 행의 actor는 계정 소유자다. 사용자에게 알리는 경로(메일·메신저), 관리자 계정 대상 발급 제한, 2인 승인, 로그인 성공의 감사와 요청에 이어지는 추적 ID는 범위 밖이고 설계 가정 A-11에 잔여 위험과 이 비교의 한계로 적었다. 요청 주소는 개인정보라 A-02·A-03에 적었다.
- **잠금 순서(지적 88):** `setCredential`도 `issueCredentialToken`처럼 사용자 행을 먼저 잠근 뒤 코드 행을 잠근다. 서로 반대 순서로 잠가 교착(PostgreSQL 40P01)으로 한쪽이 500이 되던 것을 없앴다.
- **실패 제한기(지적 89):** 키 갱신과 정리를 키 단위 원자 연산으로 바꿔 빈 항목이 정리에 지워지는 경쟁을 없앴고, 키가 임계(1만)를 넘어도 전체 순회는 30초에 한 번이다. 사번을 바꿔 가며 보내는 시도는 (사번, 주소) 키라서 제한에 걸리지 않는 한계가 로그인과 같고 그대로 둔다(A-11).
- **issueCredentialToken의 400(지적 100):** 정수가 아닌 `userId`는 400이고 OAS에 선언했다. 형제 `updateUser`·`deleteUser`와 같다.
- **세션 전환(지적 98):** 재설정이 끊는 세션은 `CurrentActor`가 `Principal`이 된 뒤에 만들어진 것이다. 그 전에 열린 세션은 색인 이름이 달라 재설정이 찾지 못하고, 계속 쓰이는 동안 살아 있다(30분은 무활동 시간이다). 운영 배포 전이라 전환할 세션이 없으며, 이미 사용자가 있는 환경에 이 변경을 올릴 때는 `spring_session`을 비운 뒤 올린다.
- **원격 Ollama(지적 102):** 배포 네트워크 밖 주소의 Ollama는 OpenAI·Google과 같은 외부 전송이다. 기본 주소·`host.docker.internal`·한 단어 이름(Compose 서비스)·사설망 IPv4/IPv6·`.internal`·`.local`·`.localhost`가 아니면 `BATON_AI_EXTERNAL_TRANSMISSION=approved` 없이 기동하지 않는다. 이름은 해석하지 않는다(기동 때 네트워크를 쓰지 않는다). 알아볼 수 없는 주소는 바깥으로 본다.
- **평가 도구(지적 96):** `aiEvaluation`은 승인 변수를 스스로 주입하지 않고 환경에서 읽는다. 호출당 출력 상한은 두지 않는다: 한 호출의 초과는 하나로 한정되고 다음 호출이 거절된다.
- **업무 시간대(지적 91):** 시간대를 드라이버 `options`로 주는 방법은 통하지 않았다. 드라이버가 JVM 시간대를 `TimeZone` 시작 파라미터로 따로 보내고 그것이 `options`를 이긴다(시험이 UTC로 실패). 풀의 연결 초기 SQL은 그대로 두되 조립하지 않는 상수로 만들고, 상수와 `BusinessCalendar.ZONE`이 같음을 시험으로 고정했다. `HandoverReadinessCheck`의 시간대 리터럴 SQL은 `current_date`로 바꿔 업무일의 원천을 풀 하나로 모았다.

### W14 개정 (2026-10-06, 사용자 선택)

W13.1이 남긴 설정 코드의 잔여 위험(대장 87·106)을 네 갈래로 줄인다. 경로·operation(54)·최상위 스키마(85) 수는 그대로이고 migration V18 하나와 OAS `CurrentUser`의 선택 필드 하나가 늘었다.

- **로그인 성공 감사:** 로그인에 성공하면 `SIGN_IN` 감사 행(actor·target은 그 사용자, 요청 주소)을 남긴다. 실패와 로그아웃은 남기지 않는다. 인증 전 경로가 감사 표를 끝없이 채울 수 있고, 실패는 이미 제한기가 센다.
- **요청 추적 ID:** `RequestTraceFilter`가 `/api/*` 요청마다 UUID를 만들어 요청 속성과 로그 컨텍스트(MDC `traceId`)에 둔다(클라이언트가 보낸 값은 쓰지 않는다). 오류 응답의 `traceId`와 감사 행의 `trace_id`가 그 값이다. 응답 헤더로는 내보내지 않는다(OAS에 없는 헤더를 늘리지 않는다). 요청 밖의 감사(시동 때 부트스트랩)는 무작위 값 그대로다.
- **관리자 계정 대상 발급 제한:** `issueCredentialToken`의 대상이 `SYSTEM_ADMIN`이면(자기 자신 포함) 409 `INVALID_STATE`다. 비활성·비LOCAL 대상과 같은 코드다. 발급 뒤 대상이 관리자가 된 경우에도 `setCredential`이 그 코드를 틀린 코드와 같은 400 `INVALID_CREDENTIAL_TOKEN`으로 거절한다(코드는 쓰이지 않은 채 남는다). **막지 못하는 것:** 관리자가 다른 관리자를 강등하고 발급·설정한 뒤 다시 승격하는 경로는 관리자 권한 안의 일이라 코드로 막지 않는다(강등 직후 발급 보류·2인 승인은 사용자가 두지 않기로 했다). 추적은 감사(`UPDATE_USER`, `ISSUE_CREDENTIAL_TOKEN`, `SET_CREDENTIAL`, `SIGN_IN`)로만 된다.
- **관리자 복구는 운영자 몫:** 부트스트랩 변수 다섯 개에 `BATON_BOOTSTRAP_ADMIN_RESET=true`를 더하면, 사번이 기존 활성 LOCAL 시스템 관리자와 같을 때 비밀번호를 변수의 값으로 바꾸고 그 사용자의 세션을 모두 끊고 `RESET_SYSTEM_ADMIN_CREDENTIAL`(actor 없음)을 감사한다. 일치하는 관리자가 없으면 이유를 담아 기동을 멈춘다. 켜진 채 기동할 때마다 경고한다. 변수를 바꿀 수 있는 사람만 쓰는 경로라 HTTP 표면이 아니다.
- **`accountActivity`:** `GET /me`와 로그인 응답의 `CurrentUser`에 선택 필드. 본인의 `SIGN_IN`과 본인이 대상인 `ISSUE_CREDENTIAL_TOKEN`·`SET_CREDENTIAL`·`RESET_SYSTEM_ADMIN_CREDENTIAL`(운영자 재설정, 요청이 아니라 주소 없음) 중 최근 5건(최신순), 항목은 `action`·`occurredAt`·`remoteAddress`(없으면 null)다. 남의 활동은 담기지 않는다. V18의 부분 인덱스 두 개가 이 조회를 짧은 인덱스 범위 읽기로 만든다. 화면은 상단 계정 영역의 「내 계정 활동」에 펼쳐 보인다.
- **한계(A-11):** 바깥 채널(메일·메신저)이 없어 로그인할 수 있는 사람에게만 보인다. 감사를 읽는 관리자 API, 로그인 실패 감사, 2인 승인은 범위 밖이다.

### W14.1 보정 (2026-10-06, 재검증 지적 131·132·134·136·138)

- **로그 줄의 추적 ID(131):** `logging.pattern.correlation`을 `[%X{traceId:-}] `로 두어 요청이 도는 동안 쓰인 로그 줄에 그 요청의 추적 ID가 붙는다. 오류 응답의 `traceId`·감사 행의 `trace_id`와 같은 값이다(시험이 500 응답을 낸 요청의 로그 줄에서 확인). 요청 밖(시동 때)의 로그 줄은 비어 있다.
- **Q&A의 추적 ID(132):** 스트림의 첫 `meta` 이벤트와 실패 응답이 담는 `traceId`가 그 요청의 추적 ID다(이전에는 별도 무작위 값). 요청 스레드에서 정해지므로 비동기로 이어지는 일도 같은 값을 쓴다.
- **운영자 복구의 비밀번호(136):** 최초 관리자와 복구의 `BATON_BOOTSTRAP_ADMIN_PASSWORD`는 `baton.auth.password-min-length`(12)~128자이고 사번과 다르다. 어기면 값을 담지 않은 메시지로 기동이 멈춘다. 대장 125의 「안 고침」을 뒤집었다.
- **화면의 시각(138):** 「내 계정 활동」의 시각은 다른 화면과 같이 서버가 준 오프셋의 시각(Asia/Seoul)을 그대로 보인다.
- **운영자 재설정도 계정 활동에 보인다(대장 135):** `RESET_SYSTEM_ADMIN_CREDENTIAL`이 `accountActivity`의 `action`에 들어갔다(OAS enum, 조회, 화면 라벨 「운영자가 비밀번호 재설정」). 이 감사 행은 actor가 없고 요청 주소가 없다. 사용자 결정으로 V18의 부분 인덱스 술어를 마이그레이션 파일에서 직접 고쳤다: V18은 커밋만 됐고 보존 DB 7개와 load baseline 덤프를 포함해 어디에도 적용된 적이 없어(체크섬을 걸 곳이 없다) 이어 붙이는 V19로 인덱스를 다시 만드는 것보다 이력이 단순하다. **이미 V18이 적용된 데이터베이스가 생기면 이 방식은 쓸 수 없다.** 반복된 재설정(플래그를 켜 둔 채 기동)은 같은 행이 되풀이돼 보인다.

### W15 개정 (2026-10-07, 사용자 선택: 대장 139·143)

경로·operation(54)·최상위 스키마(85)는 그대로이고 migration V19 하나가 늘었다. OAS는 `accountActivity.remoteAddress`의 설명 문장만 바뀌었다.

- **로그인 행의 주소만 비운다:** `SIGN_IN`이고 24개월이 지난 감사 행의 `remote_address`를 null로 바꾼다(`baton.retention.audit.enabled`가 켜진 경우, 기본 꺼짐. 기간은 `baton.retention.audit.sign-in-address-months`(기본 24), 1 이상). 행(actor·target·시각·추적 ID)은 지우지 않는다. 요청 주소는 개인정보(A-02)인데 프로젝트가 없는 로그인 행에는 A-03의 「프로젝트 종료 뒤 3년」을 적용할 기준일이 없었다. 삭제하지 않는 이유는 프로젝트 재개 등에 감사 이력이 필요할 수 있고 백업(A-07)이 없어 지운 행을 복구할 수 없기 때문이다. 설정 코드 이벤트와 다른 감사 행은 건드리지 않는다.
- **용량은 근거가 아니다:** 하루 로그인 1·3·6회 [가정]이면 3년 0.23·0.68·1.35 GB(행당 약 200 B [가정], 실측하지 않았다). 정리는 개인정보 한도를 위한 것이다.
- **V19:** 부분 인덱스 `(occurred_at) WHERE action = 'SIGN_IN' AND remote_address IS NOT NULL`. 주소를 비운 행은 이 인덱스에서 빠진다. V18의 두 인덱스는 그대로라 「내 계정 활동」의 최근 5건은 정리로 줄지 않는다(비워진 주소는 null로 나온다). V18은 다시 고치지 않았다(W14.1의 직접 수정은 어디에도 적용되지 않았을 때만 쓸 수 있는 예외다).
- **단계 격리(대장 143):** `RetentionSchedule`의 단계(멱등 결과, 설정 코드, job 메타, 로그인 주소) 하나가 예외를 내도 단계 이름을 담아 오류 로그를 남기고 다음 단계를 실행한다. 한 단계가 계속 실패해도 다른 표의 정리가 멈추지 않는다. 한 주기에 한 단계가 실패하면 다음 주기에 다시 시도한다.
- **남은 것:** 감사 전체의 보존 기준·프로젝트 참조·내보내기와, 감사 행이 있는 사용자의 삭제를 막는 일(대장 142·144)은 사용자 결정으로 묶어 따로 설계한다. 이 변경 뒤에도 감사 행은 하나도 지워지지 않는다.

### W15 보정 (2026-10-10, 재검증 지적 146·147·148·149)

- **보존 기간은 달력 개월이다(149):** 설정 키가 `baton.retention.audit.sign-in-address-days`(730)에서 `baton.retention.audit.sign-in-address-months`(기본 24)로 바뀌고 SQL이 `make_interval(months => ?)`를 쓴다. 일수로는 윤년을 낀 2년(731일)에 하루 모자랄 수 있었다. 24개월이 접속기록 보관 기준을 충족한다는 말은 가정이다(고시 원문은 확인하지 못했다. A-03).
- **인덱스 시험이 운영 질의를 읽는다(146):** `LocalAuthenticationService.ACCOUNT_ACTIVITY`를 공개 상수로 올리고 시험이 그 문장의 실행 계획을 본다. 질의를 인덱스를 못 쓰게 바꾸면 시험이 실패한다.
- **게이트(148):** `tools/gate.py`의 S1(죽은 표면)이 메서드 참조 `Type::name`을 호출로 센다. 이 파일은 상위 하네스의 복사본이라 원본과 달라진다. 이 수정으로 Java 대상 13건 중 실제로 호출되는 5건(`deleteFinished`, `availableSlots`, `enqueueDueConnections`, `enqueueMissing`, `mapView`)이 빠져 8건이 됐다.

### W15 보정: 부트스트랩 비밀번호 검사 시점 (2026-10-10, 대장 145)

- **비밀번호 검사는 계정을 만들거나 재설정할 때만 한다:** 대장 136의 「최초 관리자와 복구의 `BATON_BOOTSTRAP_ADMIN_PASSWORD`는 12~128자이고 사번과 다르다. 어기면 기동이 멈춘다」에 「활성 관리자가 이미 있으면 검사하지 않고 값은 무시된다」를 더한다. 변수가 일부만 설정된 경우와 재설정 플래그만 있는 경우의 기동 중단은 그대로다.
- **규칙은 한 곳이다:** `identity/PasswordRule`(최대 128자, 최소 길이, 사번과 달라야 함)을 `CredentialService`와 `AdminBootstrap`이 함께 쓴다. 최소 길이 설정(`baton.auth.password-min-length`)은 각 클래스가 받는다.

### D1 배포 전 정리 (2026-10-10, 사용자 선택: 대장 41·42·43·142·144)

경로 하나가 늘어 41 paths·55 operations이고 최상위 스키마(85)는 그대로다. migration은 없다.

- **대체 관계도 검토 항목이다(41):** 패키지 게시 때 두 기록이 모두 snapshot에 있는 SUPERSEDES 관계를 CONFLICTS_WITH처럼 충돌 항목으로 만든다(새 기록의 항목). README §5.2의 승인 조건과 데모 장면에 서버가 맞춰졌다. 해소되지 않은 대체 쌍은 Q&A에서도 다른 충돌처럼 「미해소 충돌」로 넘겨져 답변이 PM의 확인을 요구한다. 해소는 한 번만이고, **대체 쌍은 대체한(새) 기록으로만 해소한다**(D1 보정, 대장 153: 옛 기록을 고르면 409. 평가 문항이 옛 기록을 빼고 만들어지므로 옛 기록을 고르면 기각된 사실을 묻게 된다. 대체에 동의하지 않는 PM은 관계를 철회하고 패키지를 다시 만든다 — D4). 충돌 쌍(CONFLICTS_WITH)은 두 후보 중 하나다. 대체된 옛 기록은 평가 문항의 근거로 쓰지 않는다. 그래서 대체된 기록을 빼고 문항을 만들 수 있는 기록이 다섯 건이 안 되면 패키지는 `INSUFFICIENT_EVIDENCE`로 실패한다.
- **승인에 의견을 붙이지 않는다(42):** `decision=APPROVE`에 `comment`가 있으면 400 `INVALID_INPUT`이다. 의견은 보완 요청의 사유(필수)다. 이전에는 길이만 검사하고 버렸다.
- **구성원이 읽는 구성원 목록(43):** `GET /projects/{projectId}/members`(`listCurrentProjectMembers`)는 그 프로젝트의 현재 구성원(배정 시작·미종료, 사용자·조직 활성)을 같은 프로젝트의 현재 구성원에게만 돌려준다(아니면 404). 응답은 `ProjectMemberList`이고 `Limit`·`Offset`을 받는다. 시스템 관리자용 목록은 종료된 배정까지 담는다. 인수인계 요청 화면은 역할과 관계없이 이 목록을 쓴다.
- **감사 행과 그 행위자는 남는다(142·144):** 시스템은 감사 행을 지우지 않는다(A-03). 사용자 삭제의 참조 확인에 `audit_events`(그 사용자가 행위자)를 더해, 로그인했거나 관리 행위를 한 사용자의 삭제는 409(`references`에 `audit_events`)와 비활성화 안내다. 대상으로만 나오는 행은 막지 않는다.

### D2 야간 암호화 백업 (2026-10-12, 사용자 선택: A-07)

공개 API·스키마·migration은 그대로다. 운영 구성에 `backup` 서비스(Compose 프로필 `backup`)가 늘었다.

- **대상과 암호화:** 매일 `BATON_BACKUP_AT`(기본 02:00, Asia/Seoul)에 `pg_dump --format=custom`을 `age --recipients-file`로 곧바로 암호화해 `BATON_BACKUP_DIR`에 `baton-YYYYMMDD-HHMMSS.dump.age`와 `.sha256`으로 쓴다. 평문은 디스크에 닿지 않는다. 덤프나 암호화가 실패하면 쓰던 파일을 지우고 실패한다. 호스트와 이미지에는 공개키 파일(`BATON_BACKUP_RECIPIENTS`)만 있다.
- **보존:** 백업 파일 이름의 시각으로 일간 30개(각 날의 마지막 백업) + 월간 12개(각 달의 첫 백업)를 남긴다(`BATON_BACKUP_KEEP_DAILY`, `BATON_BACKUP_KEEP_MONTHLY`). 백업 형식이 아닌 파일은 지우지 않는다.
- **거절:** 공개키가 없거나, 폴더에 쓸 수 없거나, 폴더에 표식 파일 `.baton-backup-target`이 없거나(NAS가 마운트되지 않은 빈 마운트 지점에 쓰지 않는다), 보존값이 1 이상의 정수가 아니거나(월간은 0 이상, 빈 값은 기본값), 다른 실행이 잠금(`.baton-backup.lock`)을 쥐고 있거나, DB 비밀번호가 없거나, 원격 대상 설정(`BATON_BACKUP_S3_ENDPOINT`·`_BUCKET`)이 있으면 이유를 남기고 실패한다. 원격 대상은 아직 없다. 백업 파일은 640(소유자 쓰기, 그룹 읽기)이다.
- **프로필:** 백업 서비스는 `backup` 프로필일 때만 뜬다. 두 호스트 경로를 설정하지 않은 개발·부하·E2E 스택이 그대로 동작하게 하기 위해서다. 운영 배포 절차에서 프로필을 켜는 것이 필수다.
- **복원 연습:** `tests/recovery/restore-drill.sh`가 개인키로 백업을 스트림으로 풀어 일회용 DB에 복원하고, 스키마를 모르는 fingerprint(`tests/recovery/fingerprint-generic.sql`: 테이블별 행 수와 행 md5의 md5)를 남긴다.

### D3 관측 구성 (2026-10-13, 사용자 선택: A-05·A-14)

공개 API·스키마·migration은 그대로다. 앱 설정 하나와 별도 overlay가 늘었다.

- **서버 쪽 p95:** 요청 시간(`http.server.requests`)에 분포 버킷을 둔다. 범위는 5ms~10초(시리즈당 버킷 53개)이고, 목표값과 같은 SLO 버킷(500ms, 1초)이 있어 목표 안의 비율을 보간 없이 센다. 태그는 기존 uri·method·status·outcome뿐이다(§8의 식별자 금지 그대로).
- **overlay:** `compose.observe.yml`은 기본 스택에 들지 않는다. 앱을 내부 `observe` 네트워크에 붙이고 Prometheus(7일·2GB), Grafana(`127.0.0.1`, 관리자 비밀번호 필수, 익명·가입·외부 호출 끔), postgres_exporter(`pg_monitor` 권한만 가진 `baton_monitor`, 일회성 `monitor-init`이 만든다)를 띄운다. 컨테이너 CPU 지표는 없다(cAdvisor는 Docker Desktop에서 Docker를 찾지 못했고, Docker 소켓은 Docker API 전체 권한이라 쓰지 않는다). 앱 CPU는 앱의 프로세스 지표로, DB 컨테이너 CPU는 부하 시험의 표본(`tests/load/sample.sh`)으로 본다. 필요한 변수는 `BATON_GRAFANA_ADMIN_PASSWORD`, `BATON_MONITOR_DB_PASSWORD`(저장소 밖 `~/baton-secrets/observe.env`)이고 `BATON_GRAFANA_PORT`(기본 3000)를 바꿀 수 있다.
- **화면과 경보:** 화면 셋(서비스·자원·부하 시험, 생성 스크립트 `infra/observe/grafana/build-dashboards.py`)과 경보 규칙(일반 API·타임라인 p95 목표 이탈, 5xx 1% 초과, API pool 대기 1분 지속, 생성 slot(구조화·패키지 6개) 10분 포화(slot이 하나뿐인 임베딩·수집 pool은 적체가 빠지는 동안 늘 바빠 경보에서 뺀다), 앱 관리 포트 무응답, DB 접속 불가)은 저장소의 파일이다. 경보는 Prometheus·Grafana 화면에만 뜨고 어디로도 보내지 않는다.
- **부하 시험:** `BATON_LOAD_PROMETHEUS=1`이면 `tests/load/run.sh`가 k6 지표를 Prometheus로 보낸다(native histogram, URL 태그 제외). 이때 `COMPOSE_FILE`에 `compose.observe.yml`과 `tests/load/compose.load-observe.yml`(k6만 관측 네트워크에 붙인다; Prometheus는 내부 네트워크에만 있고 원격 쓰기 수신에 인증이 없어서다)을 넣고, 관측 스택이 측정 중 CPU를 쓴다는 사실을 결과의 `environment.txt`에 남긴다.

### D4 기록 관계 철회 (2026-10-14, 사용자 선택: 대장 173 A, 172)

경로·operation이 하나씩 늘어 42 paths·56 operations, 최상위 스키마 86(`WithdrawRecordRelationRequest`). migration V20.

- **철회:** `POST /record-relations/{relationId}/withdrawal`(`withdrawRecordRelation`)은 그 관계 프로젝트의 PM만 부른다(비구성원 404, PM이 아닌 구성원 403). 본문 `{reason}`(공백 제외 1~300자), `If-Match`(관계 version, 없으면 428, 다르면 412), `Idempotency-Key`. 200 `RecordRelation`(version+1, `withdrawnAt`, `withdrawalReason`). 이미 철회된 관계는 409 `INVALID_STATE`. 감사 `WITHDRAW_RECORD_RELATION`.
- **행은 남는다:** V20의 `withdrawn_at`·`withdrawn_by`·`withdrawal_reason`(셋 다 있거나 없다). 같은 쌍의 중복 금지와 출처 기록당 충돌 하나는 철회되지 않은 관계에만 적용해, 철회한 쌍은 다시 등록할 수 있다.
- **철회된 관계는 없는 것으로 본다:** 새 관계의 순환·두 번째 대체 검사, 기록 상세의 `relations`와 `supersededByCode`, 패키지 생성(대체된 기록 제외와 충돌 항목), Q&A가 넘기는 미해소 충돌.
- **게시된 세대:** 그 세대의 충돌 항목은 남는다. 철회된 관계의 항목은 해소하면 409이고, 승인 차단 사유는 그 항목에 `CONFLICTS_UNRESOLVED` 대신 `RELATIONS_WITHDRAWN`이다. 인수인계 상세의 `conflicts`는 그 관계를 철회 정보와 함께 보인다. 패키지를 다시 만들면 그 쌍 없이 만들어진다.
- **한 쌍에 대체와 상충 중 하나(대장 183, D4 보정):** 같은 두 기록 사이에 철회되지 않은 대체가 있으면 상충을, 상충이 있으면 대체를 등록할 수 없다(409 `INVALID_STATE`, 방향 무관). 둘이 함께 있으면 한 패키지에 후보가 같은 충돌 항목이 둘 생겨 서로 반대 기록으로 해소될 수 있었고, 아래 짝짓기가 다른 관계를 찾았다. 참조는 충돌 항목을 만들지 않아 함께 걸 수 있다. 철회한 대체 대신 상충을 등록하는 것은 허용된다.
- **화면의 짝짓기(172):** 충돌 항목과 관계는 후보 기록 두 개가 같은 쌍인지로 찾는다(위 규칙으로 한 쌍의 충돌 관계는 하나다). 충돌마다 항목이 하나다(D6). `HandoverItem.conflictId`는 충돌 행의 ID이고 `conflicts[].id`는 관계의 ID라 서로 다른 번호다.

### D5 감사 행의 위·변조 방지 (2026-10-19, 대장 198)

경로·operation·스키마 그대로(42/56/86). migration V21.

- **추가만 된다:** `audit_events`의 행 단위 트리거가 삭제와 수정을 거부하고(SQLSTATE 42501), 문장 단위 트리거가 TRUNCATE를 거부한다. 통과하는 수정은 「`SIGN_IN` 행의 `remote_address`만 null로 바뀌는 것」 하나다(보존 기간 배치 `AuditService.CLEAR_SIGN_IN_ADDRESSES`). 운영 코드는 감사 행을 다른 방식으로 바꾸지 않으므로 오류 응답 계약은 그대로다.
- **시험:** 통합 시험의 감사 정리와 날짜 조작은 `AuditRows`(시험 코드) 하나를 거친다. 같은 연결에서 `session_replication_role = replica`로 트리거를 건너뛴다. 운영 코드에는 이 길이 없다.
- **한계:** 테이블 소유자·슈퍼유저는 트리거를 끌 수 있다. compose의 앱 계정은 그 DB의 슈퍼유저다. 계정 분리는 배포 설계(대장 170), 위·변조 탐지(해시 연결)는 범위 밖이다.

### D6 충돌마다 검토 항목 하나 (2026-10-19, 대장 202)

경로·operation·스키마 그대로(42/56/86). migration V22(`handover_items.conflict_slot`, 고유 제약 `(generation_id, item_kind, work_record_id, conflict_slot)`).

- **항목:** 유효한 대체·상충 관계마다 검토 항목이 하나다. 항목은 관계의 출처 기록(대체는 새 기록, 상충은 정렬한 낮은 기록)의 것이고, 출처가 같은 관계가 여럿이면 관계 ID 순으로 첫 관계는 그 기록의 항목(slot 0)에, 이후 관계는 같은 종류·제목·본문·필수 여부를 복사한 항목(slot 1, 2, …, 목록 끝)에 붙는다. 이전에는 모든 충돌이 한 항목에 붙어, 충돌이 정확히 하나인 항목만 받는 해소가 늘 409였다.
- **그대로인 것:** `HandoverItem`(slot은 내부 값), 해소·확인·준비도·Q&A·평가. 추가 항목이 필수면 후임자가 확인한다. 「충돌이 하나가 아님」 409는 방어로만 남는다.

### D7 AWS 공개 데모 배포 (2026-10-19, 대장 170)

경로·operation·스키마 그대로(42/56/86). migration은 늘지 않았다(권한은 Flyway 콜백 `afterMigrate.sql`).

- **구성:** `compose.yml` + `compose.deploy.yml`을 EC2 한 대(`t4g.small`, 서울)에 띄운다. 공개 포트는 프록시의 80·443뿐이고, 인증서는 Caddy가 `baton.gwang.dev`로 Let's Encrypt에서 받는다. 이미지는 CI가 arm64로 빌드해 ECR에 올린다(OIDC, 저장된 키 없음).
- **DB 계정:** migration은 일회용 `migrate` 컨테이너(`config/DatabaseMigration`)가 소유자로, 앱은 `baton_app`으로, 접속기록 점검은 `baton_inspect`로 한다. `baton_app`은 소유자가 아니라 감사 트리거(V21)를 끌 수 없고, 감사 테이블에는 추가·조회와 `remote_address` 수정만 할 수 있다. 로컬 구성과 시험은 계정 하나 그대로다(역할이 없으면 콜백은 아무것도 하지 않는다).
- **비용 상한:** 요청당 과금 서비스를 쓰지 않는다. 호스트 커널이 나가는 전송을 평균 2 Mbit/s로 묶고, 월 90 GB를 넘으면 프록시를 멈춘다. 프록시는 텍스트를 압축하고 해시 자산을 immutable로 내보낸다. 호스트 방화벽이 IP당 새 연결 속도와 동시 연결 수를 묶는다.
- **공개 데모:** 시연 계정 넷은 README에 있다. 매일 04:00(KST)에 DB를 기준 덤프로 되돌리고, 그동안 프록시가 503 안내를 낸다. 로그인 화면이 합성 데이터·매일 초기화·개인정보 입력 금지를 알린다(`VITE_PUBLIC_DEMO`).
- **한계:** 원격 백업 대상이 없다(A-07). 단일 호스트이고 전송 할당을 넘으면 그 달 데모가 멈춘다(A-05).

