import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ApiProblem } from '@/api/http'
import { provideRepository } from '@/api/repositoryProvider'
import type { BatonRepository } from '@/api/batonRepository'
import type { CurrentUser } from '@/api/types'
import { useSessionStore } from '@/stores/session'

const me: CurrentUser = {
  user: {
    id: 2, version: 0, employeeNo: 'A-10188', name: '박지민', title: 'PL', email: 'x@example.test',
    systemRole: 'USER', isActive: true, organizationId: 1, loginEnabled: true,
  },
  memberships: [
    { projectId: 7, projectCode: 'P-7', projectName: 'seven', projectRole: 'PROJECT_MANAGER' },
    { projectId: 9, projectCode: 'P-9', projectName: 'nine', projectRole: 'MEMBER' },
  ],
}

function repository(overrides: Partial<BatonRepository>): BatonRepository {
  return overrides as BatonRepository
}

beforeEach(() => setActivePinia(createPinia()))

describe('세션 store', () => {
  it('복원 중 401은 오류 없이 로그아웃 상태로 본다', async () => {
    provideRepository(repository({ getCurrentUser: vi.fn().mockRejectedValue(new ApiProblem({ status: 401, code: 'UNAUTHENTICATED' })) }))
    const session = useSessionStore()
    await session.restore()
    expect(session.isAuthenticated).toBe(false)
    expect(session.restored).toBe(true)
  })

  it('동시에 요청해도 쿠키 세션은 한 번만 복원한다', async () => {
    const getCurrentUser = vi.fn().mockResolvedValue(me)
    provideRepository(repository({ getCurrentUser }))
    const session = useSessionStore()
    await Promise.all([session.restore(), session.restore()])
    await session.restore()
    expect(getCurrentUser).toHaveBeenCalledOnce()
    expect(session.user?.name).toBe('박지민')
  })

  it('복원 중 서버 실패는 로그아웃인 척하지 않고 드러낸다', async () => {
    provideRepository(repository({ getCurrentUser: vi.fn().mockRejectedValue(new ApiProblem({ status: 500, code: 'INTERNAL_ERROR' })) }))
    await expect(useSessionStore().restore()).rejects.toMatchObject({ status: 500 })
  })

  it('공백을 뺀 계정으로 로그인하고 첫 배정 프로젝트를 현재 프로젝트로 고른다', async () => {
    const signIn = vi.fn().mockResolvedValue(me)
    provideRepository(repository({ signIn }))
    const session = useSessionStore()
    await session.signIn('  A-10188 ', 'pw')
    expect(signIn).toHaveBeenCalledWith('A-10188', 'pw')
    expect(session.currentProjectId).toBe(7)
    expect(session.currentMembership?.projectRole).toBe('PROJECT_MANAGER')
  })

  it('배정되지 않은 프로젝트 선택은 무시한다', async () => {
    provideRepository(repository({ signIn: vi.fn().mockResolvedValue(me) }))
    const session = useSessionStore()
    await session.signIn('A-10188', 'pw')
    session.selectProject(9)
    expect(session.currentProjectId).toBe(9)
    session.selectProject(404)
    expect(session.currentProjectId).toBe(7)
  })

  it('서버가 세션을 끝내지 못하면 로그인 상태를 유지한다', async () => {
    provideRepository(repository({
      signIn: vi.fn().mockResolvedValue(me),
      signOut: vi.fn().mockRejectedValue(new TypeError('offline')),
    }))
    const session = useSessionStore()
    await session.signIn('A-10188', 'pw')
    await expect(session.signOut()).rejects.toBeInstanceOf(TypeError)
    expect(session.isAuthenticated).toBe(true)
  })

  it('만료된 세션의 로그아웃이 401이면 이미 끝난 세션으로 보고 오류 없이 끝낸다', async () => {
    provideRepository(repository({
      signIn: vi.fn().mockResolvedValue(me),
      signOut: vi.fn().mockRejectedValue(new ApiProblem({ status: 401, code: 'UNAUTHENTICATED' })),
    }))
    const session = useSessionStore()
    await session.signIn('A-10188', 'pw')
    await expect(session.signOut()).resolves.toBeUndefined()
    expect(session.isAuthenticated).toBe(false)
    expect(session.endReason).toBe('signed-out')
  })

  it('401이 아닌 복원 실패 뒤에는 다음 이동에서 다시 복원한다', async () => {
    const getCurrentUser = vi.fn().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(me)
    provideRepository(repository({ getCurrentUser }))
    const session = useSessionStore()
    await expect(session.restore()).rejects.toBeInstanceOf(TypeError)
    expect(session.restored).toBe(false)
    await session.restore()
    expect(getCurrentUser).toHaveBeenCalledTimes(2)
    expect(session.isAuthenticated).toBe(true)
  })

  it('HTTP 클라이언트가 401에서 부르는 만료를 처리한다', async () => {
    provideRepository(repository({ signIn: vi.fn().mockResolvedValue(me) }))
    const session = useSessionStore()
    await session.signIn('A-10188', 'pw')
    session.expire()
    expect(session.isAuthenticated).toBe(false)
  })
})
