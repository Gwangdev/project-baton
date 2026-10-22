import type { CurrentUser, ProjectMember, ProjectRole } from '@/api/types'
import { DEMO_PROJECT_ID } from './scenario'
import { clone, fail, type DemoState } from './state'

/**
 * 데모 저장소의 인증·권한 확인. 서버의 ProjectAccessPolicy·SystemAdminAccessPolicy 역할을 발표 장면에 필요한
 * 만큼만 한다. 없는 세션은 401, 구성원이 아닌 프로젝트는 404, 역할이 모자라면 403으로 서버와 같게 답한다.
 */

export function requireUser(state: DemoState): number {
  if (state.signedIn === null) fail(401, 'UNAUTHENTICATED')
  return state.signedIn!
}

export function isAdmin(state: DemoState, userId: number): boolean {
  return state.users.find((u) => u.id === userId)?.systemRole === 'SYSTEM_ADMIN'
}

export function requireAdmin(state: DemoState): number {
  const userId = requireUser(state)
  if (!isAdmin(state, userId)) fail(403, 'ACCESS_DENIED')
  return userId
}

/**
 * 현재 구성원: 배정이 끝나지 않았고 사용자가 활성이다. 권한 판단과 구성원 목록이 같은 조건을 써서, 서버처럼 「누가 행동할
 * 수 있는가」와 「누가 목록에 보이는가」가 어긋나지 않는다(데모는 배정 시작일과 조직 활성은 따로 보지 않는다).
 */
export function currentMembers(state: DemoState): ProjectMember[] {
  return state.members.filter((m) => !m.leftOn && state.users.find((u) => u.id === m.userId)?.isActive !== false)
}

export function roleOf(state: DemoState, userId: number, projectId: number): ProjectRole | null {
  if (projectId !== DEMO_PROJECT_ID) return null
  return currentMembers(state).find((m) => m.userId === userId)?.projectRole ?? null
}

export function requireMember(state: DemoState, projectId: number): number {
  const userId = requireUser(state)
  if (!roleOf(state, userId, projectId)) fail(404, 'PROJECT_NOT_FOUND')
  return userId
}

export function userName(state: DemoState, userId: number): string {
  return state.users.find((u) => u.id === userId)?.name ?? '알 수 없음'
}

export function currentUser(state: DemoState, userId: number): CurrentUser {
  return {
    user: clone(state.users.find((u) => u.id === userId)!),
    memberships: state.projects.flatMap((p) => {
      const role = roleOf(state, userId, p.id)
      return role ? [{ projectId: p.id, projectCode: p.code, projectName: p.name, projectRole: role }] : []
    }),
    // 데모는 가상 데이터이므로 가상의 로그인 한 건만 보여 준다(주소는 문서용 예약 주소).
    accountActivity: [{ action: 'SIGN_IN', occurredAt: new Date().toISOString(), remoteAddress: '198.51.100.24' }],
  }
}
