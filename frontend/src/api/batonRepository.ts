import { HttpClient } from './http'
import { apiAdminRepository, type AdminRepository } from './repositories/admin'
import { apiAssessmentRepository, type AssessmentRepository } from './repositories/assessments'
import { apiConnectionRepository, type ConnectionRepository } from './repositories/connections'
import { apiHandoverRepository, type HandoverRepository } from './repositories/handovers'
import { apiNoteRepository, type NoteRepository } from './repositories/notes'
import { apiProjectRepository, type ProjectRepository } from './repositories/projects'
import { apiQuestionRepository, type QuestionRepository } from './repositories/questions'
import { apiRecordRepository, type RecordRepository } from './repositories/records'
import { apiSessionRepository, type SessionRepository } from './repositories/session'

/**
 * 화면이 쓰는 유일한 데이터 접근 계약.
 *
 * 기능마다 계약 하나(`src/api/repositories/*`)가 있고, 이 인터페이스는 그 합이다. 메서드 이름은 OpenAPI
 * operationId와 같아 화면의 호출을 계약까지 바로 추적할 수 있다. 화면은 권한이나 업무 상태를 스스로 판단하지
 * 않는다. 호출하고, 401/403/404를 포함해 서버가 답한 것을 보여준다. 버전이 있는 명령은 화면이 마지막으로 읽은
 * 행 `version`을 받아 `If-Match`로 보낸다.
 *
 * `api` 모드는 {@link createApiRepository}로 Spring 백엔드와 통신한다. `demo` 모드(VITE_DATA_MODE=demo 전용)는
 * 같은 기능 계약을 `src/demo/repositories/*`가 메모리 시나리오로 구현한다. API가 실패해도 demo로 넘어가지 않는다.
 */
export interface BatonRepository
  extends SessionRepository, ProjectRepository, RecordRepository, NoteRepository, ConnectionRepository,
    HandoverRepository, QuestionRepository, AssessmentRepository, AdminRepository {
  readonly mode: 'api' | 'demo'
}

export function createApiRepository(http: HttpClient): BatonRepository {
  return {
    mode: 'api',
    ...apiSessionRepository(http),
    ...apiProjectRepository(http),
    ...apiRecordRepository(http),
    ...apiNoteRepository(http),
    ...apiConnectionRepository(http),
    ...apiHandoverRepository(http),
    ...apiQuestionRepository(http),
    ...apiAssessmentRepository(http),
    ...apiAdminRepository(http),
  }
}
