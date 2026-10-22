# 현행 API 전체 목록

루트 `ProjectBaton-API.yml`을 W00에서 승인 계약에 맞춰 동기화했다. 기준 설계 커밋은 `96985b9`이며, 기존 46개와 신규 6개, W12의 2개, D1의 1개, D4의 1개를 합친 56개 operation을 열거한다. 모든 경로 앞에 `/api/v1`이 붙는다. `operationId`는 계약 테스트의 식별자다. 컨트롤러 메서드명은 operationId와 다를 수 있다(예: `list`, `create`). 응답 필드·nullable·필수값은 OAS `$ref`를 따라 읽는다.

| Method | Path | operationId | 모듈 | 요청 schema | 현재 HTTP 응답 |
|---|---|---|---|---|---|
| `POST` | `/auth/sign-in` | `signIn` | `auth` | `SignInRequest` | `200,400,401,403,413,415,429,500` |
| `POST` | `/auth/sign-out` | `signOut` | `auth` | `없음` | `204,401,403,500` |
| `POST` | `/auth/credentials` | `setCredential` | `auth` | `SetCredentialRequest` | `204,400,403,413,415,429,500` |
| `GET` | `/auth/csrf` | `getCsrfToken` | `auth` | `없음` | `200,403,500` |
| `GET` | `/me` | `getCurrentUser` | `dashboard` | `없음` | `200,401,403,500` |
| `GET` | `/dashboard` | `getDashboard` | `dashboard` | `없음` | `200,401,403,500` |
| `GET` | `/projects` | `listProjects` | `projects` | `없음` | `200,400,401,403,500` |
| `GET` | `/projects/{projectId}` | `getProject` | `projects` | `없음` | `200,401,403,404,500` |
| `GET` | `/projects/{projectId}/members` | `listCurrentProjectMembers` | `projects` | `없음` | `200,400,401,403,404,500` |
| `GET` | `/projects/{projectId}/records` | `listRecords` | `records` | `없음` | `200,400,401,403,404,500` |
| `GET` | `/records/{recordId}` | `getRecord` | `records` | `없음` | `200,401,403,404,500` |
| `POST` | `/records/{recordId}/review` | `reviewRecord` | `records` | `ReviewRecordRequest` | `200,400,401,403,404,409,412,413,415,428,500,503` |
| `POST` | `/projects/{projectId}/record-relations` | `createRecordRelation` | `records` | `CreateRecordRelationRequest` | `201,400,401,403,404,409,413,415,500` |
| `POST` | `/record-relations/{relationId}/withdrawal` | `withdrawRecordRelation` | `records` | `WithdrawRecordRelationRequest` | `200,400,401,403,404,409,412,413,415,428,500` |
| `GET` | `/projects/{projectId}/notes` | `listPersonalNotes` | `notes` | `없음` | `200,400,401,403,404,500` |
| `POST` | `/projects/{projectId}/notes` | `createPersonalNote` | `notes` | `CreatePersonalNoteRequest` | `201,400,401,403,404,409,413,415,500` |
| `POST` | `/notes/{noteId}/share` | `sharePersonalNoteSummary` | `notes` | `ShareNoteSummaryRequest` | `201,400,401,403,404,409,413,415,500,503` |
| `GET` | `/projects/{projectId}/connections` | `listSourceConnections` | `connections` | `없음` | `200,400,401,403,404,500` |
| `POST` | `/projects/{projectId}/connections` | `createSourceConnection` | `connections` | `CreateSourceConnectionRequest` | `201,400,401,403,404,409,413,415,500` |
| `GET` | `/connections/{connectionId}` | `getSourceConnection` | `connections` | `없음` | `200,401,403,404,500` |
| `PATCH` | `/connections/{connectionId}` | `updateSourceConnection` | `connections` | `UpdateSourceConnectionRequest` | `200,400,401,403,404,409,412,413,415,428,500` |
| `DELETE` | `/connections/{connectionId}` | `deleteSourceConnection` | `connections` | `없음` | `204,400,401,403,404,409,412,428,500` |
| `POST` | `/connections/{connectionId}/reconnect` | `reconnectSourceConnection` | `connections` | `없음` | `200,400,401,403,404,409,500` |
| `GET` | `/connections/{connectionId}/files` | `listCollectedFiles` | `connections` | `없음` | `200,400,401,403,404,500` |
| `POST` | `/connections/{connectionId}/files/{sourceId}/records` | `createManualDraft` | `connections` | `CreateManualDraftRequest` | `201,400,401,403,404,409,413,415,500` |
| `POST` | `/connections/{connectionId}/files/{sourceId}/processing-attempts` | `retrySourceProcessing` | `connections` | `없음` | `202,400,401,403,404,409,500,503` |
| `GET` | `/projects/{projectId}/handovers` | `listHandovers` | `handovers` | `없음` | `200,400,401,403,404,500` |
| `POST` | `/projects/{projectId}/handovers` | `requestHandover` | `handovers` | `CreateHandoverRequest` | `202,400,401,403,404,409,413,415,500,503` |
| `GET` | `/handovers/{handoverId}` | `getHandover` | `handovers` | `없음` | `200,401,403,404,500` |
| `POST` | `/handovers/{handoverId}/retry` | `retryHandoverGeneration` | `handovers` | `없음` | `202,400,401,403,404,409,412,428,500,503` |
| `POST` | `/handovers/{handoverId}/generations` | `regenerateHandover` | `handovers` | `RegenerateHandoverRequest` | `202,400,401,403,404,409,412,413,415,428,500,503` |
| `GET` | `/handovers/{handoverId}/items` | `listHandoverItems` | `handovers` | `없음` | `200,400,401,403,404,500` |
| `PATCH` | `/handovers/{handoverId}/items/{itemId}` | `resolveHandoverItem` | `handovers` | `ResolveHandoverItemRequest` | `200,400,401,403,404,409,412,413,415,428,500` |
| `POST` | `/handovers/{handoverId}/items/{itemId}/acknowledgements` | `acknowledgeHandoverItem` | `handovers` | `없음` | `200,201,400,401,403,404,409,412,428,500` |
| `POST` | `/handovers/{handoverId}/approval` | `decideHandoverCompletion` | `handovers` | `HandoverApprovalRequest` | `200,400,401,403,404,409,412,413,415,428,500` |
| `GET` | `/handovers/{handoverId}/questions` | `listQuestions` | `qa` | `없음` | `200,400,401,403,404,500` |
| `POST` | `/handovers/{handoverId}/questions` | `askQuestion` | `qa` | `AskQuestionRequest` | `200,201,400,401,403,404,406,409,413,415,429,500,503` |
| `GET` | `/handovers/{handoverId}/assessment` | `getAssessment` | `assessments` | `없음` | `200,401,403,404,409,500` |
| `POST` | `/handovers/{handoverId}/assessment/attempts` | `submitAssessmentAttempt` | `assessments` | `SubmitAttemptRequest` | `201,400,401,403,404,409,413,415,500` |
| `GET` | `/admin/organizations` | `listOrganizations` | `admin` | `없음` | `200,400,401,403,500` |
| `POST` | `/admin/organizations` | `createOrganization` | `admin` | `OrganizationUpsertRequest` | `201,400,401,403,409,413,415,500` |
| `PATCH` | `/admin/organizations/{organizationId}` | `updateOrganization` | `admin` | `OrganizationUpdateRequest` | `200,400,401,403,404,409,412,413,415,428,500` |
| `DELETE` | `/admin/organizations/{organizationId}` | `deleteOrganization` | `admin` | `없음` | `204,400,401,403,404,409,412,428,500` |
| `GET` | `/admin/users` | `listUsers` | `admin` | `없음` | `200,400,401,403,500` |
| `POST` | `/admin/users` | `createUser` | `admin` | `UserCreateRequest` | `201,400,401,403,409,413,415,500` |
| `PATCH` | `/admin/users/{userId}` | `updateUser` | `admin` | `UserUpdateRequest` | `200,400,401,403,404,409,412,413,415,428,500` |
| `DELETE` | `/admin/users/{userId}` | `deleteUser` | `admin` | `없음` | `204,400,401,403,404,409,412,428,500` |
| `POST` | `/admin/users/{userId}/credential-tokens` | `issueCredentialToken` | `admin` | `없음` | `201,400,401,403,404,409,500` |
| `GET` | `/admin/projects` | `listAllProjects` | `admin` | `없음` | `200,400,401,403,500` |
| `POST` | `/admin/projects` | `createProject` | `admin` | `ProjectCreateRequest` | `201,400,401,403,409,413,415,500` |
| `PATCH` | `/admin/projects/{projectId}` | `updateProject` | `admin` | `ProjectUpdateRequest` | `200,400,401,403,404,409,412,413,415,428,500` |
| `DELETE` | `/admin/projects/{projectId}` | `deleteProject` | `admin` | `없음` | `204,400,401,403,404,409,412,428,500` |
| `GET` | `/admin/projects/{projectId}/members` | `listProjectMembers` | `admin` | `없음` | `200,400,401,403,404,500` |
| `POST` | `/admin/projects/{projectId}/members` | `addProjectMember` | `admin` | `ProjectMemberUpsertRequest` | `201,400,401,403,404,409,413,415,500` |
| `PATCH` | `/admin/projects/{projectId}/members/{memberId}` | `updateProjectMember` | `admin` | `ProjectMemberUpdateRequest` | `200,400,401,403,404,409,412,413,415,428,500` |
| `DELETE` | `/admin/projects/{projectId}/members/{memberId}` | `deleteProjectMember` | `admin` | `없음` | `204,400,401,403,404,409,412,428,500` |

현재 분포: GET 20 / POST 21 / PATCH 6 / DELETE 5. 기존 46개 중 삭제한 operation은 없으며, 인증·개인 원본·버전·SSE 변경은 [contracts.md](contracts.md)를 반영했다.
