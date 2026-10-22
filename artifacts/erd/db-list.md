# Appendix — 도메인별 데이터베이스 목록

> `ProjectBaton-DB.dbml`에서 생성 / 테이블 30개 · enum 16개

## 1. 조직과 권한

| 테이블 | 역할 | 주요 컬럼 |
|---|---|---|
| `organizations` | 조직. 사용자 소속과 관리 단위 | `id` · `is_active` |
| `users` | 사내 계정. 이름·직급·시스템 역할·활성 여부 | `id` · `organization_id` · `system_role` · `is_active` |
| `credential_tokens` | 관리자가 발급한 일회용 비밀번호 설정 코드. 해시만 저장 | `id` · `user_id` · `created_by` |
| `projects` | 프로젝트. 수행사·고객사·기간·수행 상태 | `id` · `code` · `status` · `is_active` |
| `project_members` | 프로젝트별 역할 배정. 접근 통제의 단위 | `id` · `project_id` · `user_id` · `project_role` |

## 2. 기록 수집

| 테이블 | 역할 | 주요 컬럼 |
|---|---|---|
| `source_connections` | 연동 설정. 채널·소유자·연결 상태·마지막 수집 결과 | `id` · `project_id` · `owner_user_id` · `channel` · `status` · `health` |
| `record_sources` | 수집된 개별 문서·메시지·파일의 원문 위치 | `id` · `source_connection_id` · `project_id` |

## 3. 프로젝트 기록

| 테이블 | 역할 | 주요 컬럼 |
|---|---|---|
| `personal_notes` | 빠른 메모 원본. 작성자 전용이며 공유해도 공개되지 않음 | `id` · `code` · `project_id` · `author_id` |
| `work_records` | 프로젝트 기록. 유형·요약·원문 발췌·신뢰 상태를 가진 중심 개체 | `id` · `code` · `project_id` · `record_type` · `author_id` · `owner_user_id` |
| `record_revisions` | 기록 수정 이력. 무엇을 왜 고쳤는지 | `id` · `work_record_id` · `changed_by` |
| `record_relations` | 기록 사이의 대체·상충·참조 관계 | `id` · `project_id` · `relation_type` · `withdrawn_by` |

## 4. 담당자 교체와 인수인계

| 테이블 | 역할 | 주요 컬럼 |
|---|---|---|
| `handovers` | 담당자 교체와 인수인계 진행 상태 | `id` · `code` · `project_id` · `requested_by` · `outgoing_owner_id` · `incoming_owner_id` |
| `handover_scope_types` | 인수인계에 포함할 기록 유형 다중 선택 | `handover_id` · `record_type` |
| `handover_items` | 패키지 항목. 근거 기록·충돌 해소·후속 작업 | `id` · `handover_id` · `generation_id` · `project_id` · `item_kind` · `work_record_id` |
| `handover_generations` | 패키지 생성 회차. 다시 생성해도 이전 회차가 남는다 | `id` · `handover_id` · `project_id` · `created_by` · `status` |
| `handover_snapshot_records` | 회차 시점에 고정한 근거 기록의 내용과 신뢰 상태 | `id` · `generation_id` · `project_id` · `work_record_id` · `trust_status` |
| `handover_item_conflicts` | 충돌 항목이 가리키는 관계와 양쪽 기록의 스냅숏 | `id` · `project_id` · `generation_id` · `handover_item_id` · `relation_id` · `from_snapshot_id` |
| `handover_item_acknowledgements` | 후임자가 항목을 읽고 확인한 기록 | `id` · `project_id` · `generation_id` · `item_id` · `user_id` |

## 5. 근거 기반 질의응답

| 테이블 | 역할 | 주요 컬럼 |
|---|---|---|
| `qa_messages` | 질의응답. 답변 상태와 근거 없을 때의 확인 대상 | `id` · `handover_id` · `session_user_id` · `answer_status` · `generation_id` · `reply_to_id` |
| `qa_citations` | 답변이 인용한 기록과 인용 시점의 신뢰 상태 | `id` · `qa_message_id` · `work_record_id` · `snapshot_record_id` · `is_primary` |
| `record_embeddings` | 확정 기록의 검색용 임베딩(256차원)과 모델 ID | `id` · `project_id` · `record_id` |

## 6. 업무 준비도 확인

| 테이블 | 역할 | 주요 컬럼 |
|---|---|---|
| `assessments` | 인수인계별 업무 준비도 점검 | `id` · `handover_id` · `generation_id` · `status` |
| `assessment_questions` | 점검 문항과 오답 시 확인할 근거 기록 | `id` · `assessment_id` · `question_type` · `generation_id` · `linked_record_id` |
| `assessment_attempts` | 응시 회차와 점수 | `id` · `assessment_id` · `generation_id` · `taken_by` · `score` · `total` |
| `assessment_answers` | 문항별 답안과 정오 판정 | `id` · `attempt_id` · `generation_id` · `question_id` · `is_correct` |

## 7. 운영 기반

| 테이블 | 역할 | 주요 컬럼 |
|---|---|---|
| `jobs` | 비동기 작업 큐. 패키지 생성·색인·재시도 | `id` · `status` |
| `idempotency_requests` | 같은 요청의 재전송을 한 번만 처리하기 위한 키와 결과 | `id` · `actor_id` |
| `audit_events` | 감사 기록. 추가만 허용하고 수정·삭제는 막는다 | `id` |
| `spring_session` | 로그인 세션(Spring Session JDBC) | `primary_id` |
| `spring_session_attributes` | 세션 속성 | `session_primary_id` |

## 상태 enum

| enum | 값 |
|---|---|
| `record_type` | `SCHEDULE` · `CHANGE_DECISION` · `COMMITMENT` · `BLOCKER` · `SCOPE_EXCEPTION` · `OPEN_ISSUE` |
| `record_trust_status` | `AUTO_DRAFT` · `CONFIRMED` · `CORRECTED` · `DISMISSED` |
| `record_relation_type` | `SUPERSEDES` · `REFERENCES` · `CONFLICTS_WITH` |
| `source_channel` | `EMAIL` · `APPROVAL` · `MESSENGER` · `MEETING_MINUTES` · `CLOUD_PROJECT_FOLDER` |
| `connection_status` | `ACTIVE` · `PAUSED` · `DISCONNECTED` |
| `handover_status` | `REQUESTED` · `GENERATING` · `READY` · `REVIEW_REQUESTED` · `COMPLETED` · `FAILED` |
| `handover_item_kind` | `OVERVIEW` · `KEY_DECISION` · `CHANGE` · `COMMITMENT` · `OPEN_RISK` · `SCHEDULE` · `FOLLOW_UP` |
