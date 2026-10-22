import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiProblem } from '@/api/http'
import { provideRepository } from '@/api/repositoryProvider'
import type { BatonRepository } from '@/api/batonRepository'
import router, { safeRedirect } from '@/router'
import { routes } from '@/router/routes'

/** FR-01 — 가드는 서버 세션을 한 번 묻고 모든 화면을 로그인 뒤에 둔다. */

const me = {
  user: { id: 1, version: 0, employeeNo: 'A', name: 'n', title: 't', email: 'e', systemRole: 'USER', isActive: true, organizationId: 1, loginEnabled: true },
  memberships: [],
}

function withSession(getCurrentUser: BatonRepository['getCurrentUser']) {
  setActivePinia(createPinia())
  provideRepository({ getCurrentUser } as BatonRepository)
}

beforeEach(async () => {
  // 라우터는 앱의 단일 인스턴스다. 모든 테스트를 로그아웃 상태의 로그인 화면에서 시작한다.
  withSession(vi.fn().mockRejectedValue(new ApiProblem({ status: 401, code: 'UNAUTHENTICATED' })))
  await router.replace('/login')
})

describe('경로 표', () => {
  it('S01~S12 열두 화면을 숫자 ID 파라미터로 유지한다', () => {
    // 설정 코드로 비밀번호를 정하는 /credentials는 로그인 화면(S01)의 일부다.
    expect(routes.map((r) => r.meta?.screen)).toEqual(['S01', 'S01', 'S02', 'S03', 'S06', 'S04', 'S05', 'S07', 'S08', 'S09', 'S10', 'S11', 'S12'])
    expect(routes.map((r) => r.path).filter((p) => p.includes(':'))).toEqual([
      '/projects/:projectId', '/projects/:projectId/collection', '/records/:recordId', '/handovers/:handoverId/progress',
      '/handovers/:handoverId', '/handovers/:handoverId/readiness', '/handovers/:handoverId/review',
    ])
  })
})

describe('이동 가드', () => {
  it('로그인하지 않은 방문자도 설정 코드 화면은 열 수 있다', async () => {
    withSession(vi.fn().mockRejectedValue(new ApiProblem({ status: 401, code: 'UNAUTHENTICATED' })))
    await router.push('/credentials')
    expect(router.currentRoute.value.name).toBe('credentials')
  })

  it('로그인하지 않은 방문자를 로그인으로 보내고 가려던 곳을 기억한다', async () => {
    withSession(vi.fn().mockRejectedValue(new ApiProblem({ status: 401, code: 'UNAUTHENTICATED' })))
    await router.push('/records/101')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/records/101')
  })

  it('복원된 세션은 통과시키고 로그인 화면으로는 보내지 않는다', async () => {
    withSession(vi.fn().mockResolvedValue(me))
    await router.push('/admin')
    expect(router.currentRoute.value.name).toBe('admin')
    await router.push('/login')
    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  it('서버에 닿지 못하면 사유와 함께 로그인 화면을 보여준다', async () => {
    withSession(vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await router.push('/dashboard')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.unavailable).toBe('1')
  })

  it('로그인 뒤에는 앱 안의 경로만 받는다', () => {
    expect(safeRedirect('/handovers/1')).toBe('/handovers/1')
    expect(safeRedirect('//evil.example')).toBe('/dashboard')
    expect(safeRedirect('https://evil.example')).toBe('/dashboard')
    expect(safeRedirect(undefined)).toBe('/dashboard')
  })
})
