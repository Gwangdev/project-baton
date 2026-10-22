# OAS 컴포넌트 맵 — 작성 근거

> `ProjectBaton-API.yml`에서 생성 / 경로 42개 · 오퍼레이션 56개 · 스키마 86개

## 왜 Swagger 전체 화면을 쓰지 않는가

Swagger 화면을 그대로 붙이면 청중이 읽는 것은 「경로가 많다」 하나뿐이다.
발표에서 설명해야 하는 것은 **화면의 행동 하나가 어떤 method·path·schema로 내려가는가**이고,
그것이 데이터 모델과 어긋나지 않는다는 사실이다. 그래서 시연 서사에 실제로 등장하는
행동 10개만 뽑아 연결을 보인다. 전체 목록은 API 문서 파일로 대신한다.

## 화면 행동 ↔ 오퍼레이션 ↔ 스키마

| 화면 | 사용자 행동 | Method · Path | 요청 스키마 | 성공 | 응답 스키마 |
|---|---|---|---|---|---|
| S01 | 사내 계정으로 로그인 | `POST /auth/sign-in` | `SignInRequest` | `200` | `CurrentUser` |
| S05 | AI 초안을 수정 후 승인 | `POST /records/{recordId}/review` | `ReviewRecordRequest` | `200` | `RecordDetail` |
| S06 | 메모의 선택 요약만 공유 | `POST /notes/{noteId}/share` | `ShareNoteSummaryRequest` | `201` | `RecordDetail` |
| S06 | 승인 폴더 연결·일시 중지·해제 | `PATCH /connections/{connectionId}` | `UpdateSourceConnectionRequest` | `200` | `SourceConnection` |
| S07 | 담당자 교체 요청 | `POST /projects/{projectId}/handovers` | `CreateHandoverRequest` | `202` | `Handover` |
| S08 | 충돌의 최종 근거 선택 | `PATCH /handovers/{handoverId}/items/{itemId}` | `ResolveHandoverItemRequest` | `200` | `HandoverItem` |
| S09 | 근거 기반 질문 | `POST /handovers/{handoverId}/questions` | `AskQuestionRequest` | `201` | `QaAnswer` |
| S10 | 준비도 답안 제출 | `POST /handovers/{handoverId}/assessment/attempts` | `SubmitAttemptRequest` | `201` | `AssessmentAttempt` |
| S11 | 완료 승인 또는 보완 요청 | `POST /handovers/{handoverId}/approval` | `HandoverApprovalRequest` | `200` | `HandoverDetail` |
| S12 | 사용자 비활성화 | `PATCH /admin/users/{userId}` | `UserUpdateRequest` | `200` | `User` |

## 기능군

| 기능군 | 오퍼레이션 | 역할 |
|---|---:|---|
| `auth` | 4 | S01 사내 계정 로그인과 로그아웃 |
| `dashboard` | 2 | S02 프로젝트 대시보드의 요약 정보 |
| `projects` | 3 | S02·S03 프로젝트 목록과 상세 |
| `records` | 5 | S03·S04·S05 프로젝트 기록의 조회와 확인 |
| `notes` | 3 | S06 빠른 메모의 비공개 저장과 선택 요약 공유 |
| `connections` | 9 | S06·S12 기록 수집 연동의 연결과 상태 |
| `handovers` | 9 | S07·S08·S11 담당자 교체와 인수인계 패키지 |
| `qa` | 2 | S09 근거 기반 질의응답 |
| `assessments` | 2 | S10 업무 준비도 확인 |
| `admin` | 17 | S12 사용자·조직·프로젝트·역할 관리 |

## 명세 전체에 건 공통 규칙

| 규칙 | 값 | 이유 |
|---|---|---|
| 오류 응답 | `application/problem+json` · 400 · 401 · 403 · 404 · 406 · 409 · 412 · 413 · 415 · 428 · 429 · 500 · 503 | 화면이 오류를 같은 모양으로 읽어야 처리도 한 곳에 모인다 |
| 비동기 접수 | `202 Accepted` 4건 + `Location` 헤더 | 패키지 생성은 오래 걸린다. 접수와 완료를 나누지 않으면 화면이 멈춘다 |
| 스키마 재사용 | `$ref` 715회 · 스키마 86개 | 같은 개체를 두 번 정의하면 한쪽만 고쳐져 ERD와 어긋난다 |
| 권한 조건 | 경로별 `401`·`403` 명시 | FR-01·FR-22의 접근 통제를 명세에서 읽을 수 있어야 한다 |
| 삭제 | `DELETE` 5건은 오생성 취소용, 참조가 남아 있으면 `409`. 쓰인 대상은 `PATCH`의 `isActive` | 확인자를 지우면 기록의 근거가 끊긴다 |

## 데이터 모델과의 대응

| OAS 스키마 | DBML 테이블 |
|---|---|
| `WorkRecord` · `RecordDetail` | `work_records` · `record_revisions` · `record_relations` |
| `PersonalNote` | `personal_notes` |
| `SourceConnection` · `CollectedFile` | `source_connections` · `record_sources` |
| `Handover` · `HandoverDetail` · `HandoverItem` | `handovers` · `handover_scope_types` · `handover_items` |
| `QaAnswer` · `QaCitation` | `qa_messages` · `qa_citations` |
| `AssessmentDetail` · `AssessmentAttempt` · `AssessmentAnswer` | `assessments` · `assessment_questions` · `assessment_attempts` · `assessment_answers` |
| `Organization` · `User` · `Project` · `ProjectMember` | `organizations` · `users` · `projects` · `project_members` |
