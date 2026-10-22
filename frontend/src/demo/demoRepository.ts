import type { BatonRepository } from '@/api/batonRepository'
import { demoAdminRepository } from './repositories/admin'
import { demoAssessmentRepository } from './repositories/assessments'
import { demoConnectionRepository } from './repositories/connections'
import { demoHandoverRepository } from './repositories/handovers'
import { demoNoteRepository } from './repositories/notes'
import { demoProjectRepository } from './repositories/projects'
import { demoQuestionRepository } from './repositories/questions'
import { demoRecordRepository } from './repositories/records'
import { demoSessionRepository } from './repositories/session'
import { DemoState, type DemoOptions } from './state'

/**
 * 백엔드를 대신하는 메모리 구현. VITE_DATA_MODE=demo일 때만 쓴다.
 *
 * 서버와 같은 DTO 형태와 같은 Problem code로 답하므로 화면에 데모 분기가 필요 없다. 업무 규칙의 두 번째 구현이
 * 아니라 발표 소품이다. 권한과 상태 검사는 1단계 장면이 보여주는 만큼만 단순화했고, 생성은 시간이 지나면 끝나서
 * 진행 화면이 폴링할 대상이 있다. 기능마다 `repositories/*` 한 파일이 API 쪽 같은 이름의 계약을 구현하고, 모두
 * 하나의 메모리 상태(state.ts)를 함께 쓴다. `api` 모드에서는 아무것도 쓰지 않는다.
 */
export function createDemoRepository(options: DemoOptions = {}): BatonRepository {
  const state = new DemoState(options)
  return {
    mode: 'demo',
    ...demoSessionRepository(state),
    ...demoProjectRepository(state),
    ...demoRecordRepository(state),
    ...demoNoteRepository(state),
    ...demoConnectionRepository(state),
    ...demoHandoverRepository(state),
    ...demoQuestionRepository(state),
    ...demoAssessmentRepository(state),
    ...demoAdminRepository(state),
  }
}
