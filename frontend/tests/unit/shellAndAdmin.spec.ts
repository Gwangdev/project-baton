import { describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import type { BatonRepository } from '@/api/batonRepository'
import type { User } from '@/api/types'
import { createDemoRepository } from '@/demo/demoRepository'
import App from '@/App.vue'
import AdminView from '@/views/AdminView.vue'
import DashboardView from '@/views/DashboardView.vue'
import { useSessionStore } from '@/stores/session'
import { DEMO_PASSWORDS, button, mountAt } from './support'

const KIM = 'A-10421'
const PARK = 'A-10188'
const LEE = 'A-10733'
const CHOI = 'A-10002'

async function as(employeeNo: string) {
  const repo = createDemoRepository({ delayMs: 0, generationMs: 0 })
  await repo.signIn(employeeNo, DEMO_PASSWORDS[employeeNo])
  return repo
}

describe('셸', () => {
  it('Actor 전환 대신 서버가 준 로그인 계정을 보여준다', async () => {
    const { wrapper } = await mountAt(DashboardView, '/dashboard', await as(PARK))
    expect(wrapper.find('.account__name').text()).toBe('박지민 PL')
    expect(wrapper.find('.account__role').text()).toBe('프로젝트 관리자')
    expect(wrapper.find('select[aria-label="Actor 전환"]').exists()).toBe(false)
  })

  it('서버로 로그아웃하고 화면의 세션을 끝낸다', async () => {
    const repo = await as(KIM)
    const signOut = vi.spyOn(repo, 'signOut')
    const { wrapper, router } = await mountAt(DashboardView, '/dashboard', repo)
    await button(wrapper, '로그아웃').trigger('click')
    await flushPromises()
    expect(signOut).toHaveBeenCalledOnce()
    expect(useSessionStore().isAuthenticated).toBe(false)
    // 로그인 화면 컴포넌트는 지연 로딩되므로 청크를 불러온 뒤에 이동이 끝난다.
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
  })
})

describe('프로젝트 전환', () => {
  const twoProjects = (repo: BatonRepository) => {
    const original = repo.getCurrentUser
    vi.spyOn(repo, 'getCurrentUser').mockImplementation(async () => {
      const me = await original()
      return { ...me, memberships: [...me.memberships, { projectId: 77, projectCode: 'P-77', projectName: '다른 프로젝트', projectRole: 'MEMBER' }] }
    })
  }

  it('목록 화면은 새 프로젝트로 다시 읽는다', async () => {
    const repo = await as(KIM)
    twoProjects(repo)
    const list = vi.spyOn(repo, 'listRecords').mockResolvedValue({ items: [], page: { total: 0, limit: 50, offset: 0, nextCursor: null } })
    vi.spyOn(repo, 'listSourceConnections').mockResolvedValue({ items: [], page: { total: 0, limit: 0, offset: 0, nextCursor: null } })
    const { wrapper } = await mountAt(App, '/records', repo)
    await wrapper.find('select[aria-label="작업 프로젝트"]').setValue(77)
    await flushPromises()
    expect(list).toHaveBeenLastCalledWith(77, expect.objectContaining({ trustStatus: ['AUTO_DRAFT'] }))
  })

  it('프로젝트가 경로에 있는 화면은 새 프로젝트의 경로로 옮긴다', async () => {
    const repo = await as(KIM)
    twoProjects(repo)
    vi.spyOn(repo, 'listRecords').mockResolvedValue({ items: [], page: { total: 0, limit: 50, offset: 0, nextCursor: null } })
    const { wrapper, router } = await mountAt(App, '/projects/14', repo)
    await wrapper.find('select[aria-label="작업 프로젝트"]').setValue(77)
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/projects/77'))
    expect(useSessionStore().currentProjectId).toBe(77)
  })
})

describe('S02 대시보드', () => {
  it('서버가 센 숫자를 보여주고 역할마다 다음 행동을 안내한다', async () => {
    const kim = await mountAt(DashboardView, '/dashboard', await as(KIM))
    expect(kim.wrapper.findAll('.card__value').map((v) => v.text())).toEqual(['1', '2', '0', '2'])
    expect(kim.wrapper.find('.btn--primary').text()).toBe('확인 대기 2건 처리')

    const park = await mountAt(DashboardView, '/dashboard', await as(PARK))
    expect(park.wrapper.find('.btn--primary').text()).toBe('담당자 교체 요청')

    const lee = await mountAt(DashboardView, '/dashboard', await as(LEE))
    expect(lee.wrapper.text()).toContain('배정된 인수인계 없음')
  })
})

describe('S12 시스템 관리', () => {
  it('서버가 조직 목록을 거절하면 관리 탭을 닫아 둔다', async () => {
    const { wrapper } = await mountAt(AdminView, '/admin', await as(KIM))
    expect(wrapper.find('.sub').text()).toContain('연동 상태만 조회')
    expect(button(wrapper, '조직').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('tbody tr').length).toBeGreaterThan(0)
  })

  it('version으로 삭제하고 참조 충돌은 비활성화 안내로 바꾼다', async () => {
    const repo = await as(CHOI)
    const del = vi.spyOn(repo, 'deleteOrganization')
    const { wrapper } = await mountAt(AdminView, '/admin', repo)
    await button(wrapper, '조직').trigger('click')
    await wrapper.find('button[aria-label="A테크서비스 고객지원사업부 삭제"]').trigger('click')
    await flushPromises()
    expect(del).toHaveBeenCalledWith(1, 0)
    expect(wrapper.find('.state--failed').text()).toContain('참조하고 있어 삭제할 수 없습니다. 비활성화하십시오.')

    await wrapper.find('button[aria-label="A테크서비스 고객지원사어부 삭제"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('고객지원사어부')
  })

  it('명령 뒤 목록을 다시 불러오는 동안에는 옛 version으로 누를 수 없게 잠가 둔다', async () => {
    const repo = await as(CHOI)
    const { wrapper } = await mountAt(AdminView, '/admin', repo)
    await button(wrapper, '조직').trigger('click')
    const original = repo.listOrganizations.bind(repo)
    let release!: () => void
    vi.spyOn(repo, 'listOrganizations').mockImplementationOnce(async () => {
      await new Promise<void>((resolve) => { release = resolve })
      return original()
    })

    await wrapper.find('button[aria-label="A테크서비스 고객지원사어부 비활성화"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('button[aria-label="A테크서비스 고객지원사어부 삭제"]').attributes('disabled')).toBeDefined()

    release()
    await flushPromises()
    expect(wrapper.find('button[aria-label="A테크서비스 고객지원사어부 삭제"]').attributes('disabled')).toBeUndefined()
  })

  it('설정 코드를 발급해 한 번만 보여 주고 닫으면 화면에서 지운다', async () => {
    const repo = await as(CHOI)
    const issue = vi.spyOn(repo, 'issueCredentialToken')
    const { wrapper } = await mountAt(AdminView, '/admin', repo)

    await wrapper.find('button[aria-label="김현우 설정 코드 발급"]').trigger('click')
    await flushPromises()

    expect(issue).toHaveBeenCalledOnce()
    const code = wrapper.find('[data-testid="credential-code"]').text()
    expect(code.length).toBeGreaterThanOrEqual(20)
    expect(wrapper.text()).toContain('다시 볼 수 없습니다')
    await button(wrapper, '닫기').trigger('click')
    expect(wrapper.find('[data-testid="credential-code"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(code)
  })

  it('시스템 관리자 계정에는 설정 코드 발급 버튼이 없고 데모 저장소도 거절한다', async () => {
    const repo = await as(CHOI)
    const { wrapper } = await mountAt(AdminView, '/admin', repo)
    const users = (await repo.listUsers()).items
    const admin = users.find((u) => u.systemRole === 'SYSTEM_ADMIN' && u.authProvider === 'LOCAL')!
    const ordinary = users.find((u) => u.systemRole === 'USER' && u.isActive && u.authProvider === 'LOCAL')!

    expect(wrapper.find(`button[aria-label="${ordinary.name} 설정 코드 발급"]`).exists()).toBe(true)
    expect(wrapper.find(`button[aria-label="${admin.name} 설정 코드 발급"]`).exists()).toBe(false)
    await expect(repo.issueCredentialToken(admin.id)).rejects.toMatchObject({ status: 409 })
  })

  it('사용자 표는 페이지로 넘기고 배정 선택 상자에는 101번째 뒤의 사용자도 있다', async () => {
    const repo = await as(CHOI)
    const everyone: User[] = Array.from({ length: 130 }, (_, i) => ({
      id: 1000 + i, employeeNo: `E-${i + 1}`, name: `사용자${i + 1}`, title: '사원', email: `u${i}@example.test`,
      systemRole: 'USER', isActive: true, organizationId: 1, authProvider: 'LOCAL', loginEnabled: true, version: 0,
    }))
    const listUsers = vi.spyOn(repo, 'listUsers').mockImplementation(async (range) => {
      const window = range ?? { limit: everyone.length, offset: 0 }
      return { items: everyone.slice(window.offset, window.offset + window.limit), page: { total: everyone.length, limit: window.limit, offset: window.offset, nextCursor: null } }
    })
    const { wrapper } = await mountAt(AdminView, '/admin', repo)

    expect(wrapper.findAll('tbody tr').length).toBe(20)
    await wrapper.find('button[aria-label="사용자 다음 페이지"]').trigger('click')
    await flushPromises()
    expect(listUsers).toHaveBeenCalledWith({ limit: 20, offset: 20 })
    expect(wrapper.text()).toContain('21–40 / 130')
    await button(wrapper, '구성원 배정').trigger('click')
    const options = wrapper.findAll('select[aria-label="배정할 사용자"] option').map((o) => o.text())
    expect(options).toContain('사용자130 사원 (E-130)')
  })

  it('마지막 페이지의 마지막 행을 지우면 남은 마지막 페이지로 물러난다', async () => {
    const repo = await as(CHOI)
    let everyone: User[] = Array.from({ length: 21 }, (_, i) => ({
      id: 2000 + i, employeeNo: `P-${i + 1}`, name: `페이지${i + 1}`, title: '사원', email: `p${i}@example.test`,
      systemRole: 'USER', isActive: true, organizationId: 1, authProvider: 'LOCAL', loginEnabled: true, version: 0,
    }))
    vi.spyOn(repo, 'listUsers').mockImplementation(async (range) => {
      const window = range ?? { limit: everyone.length, offset: 0 }
      return { items: everyone.slice(window.offset, window.offset + window.limit), page: { total: everyone.length, limit: window.limit, offset: window.offset, nextCursor: null } }
    })
    vi.spyOn(repo, 'deleteUser').mockImplementation(async (userId) => { everyone = everyone.filter((u) => u.id !== userId) })
    const { wrapper } = await mountAt(AdminView, '/admin', repo)
    await wrapper.find('button[aria-label="사용자 다음 페이지"]').trigger('click')
    await flushPromises()
    await wrapper.find('button[aria-label="페이지21 삭제"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('1–20 / 20')
    expect(wrapper.findAll('tbody tr').length).toBe(20)
  })

  it('역할과 배정일로 구성원을 배정한다', async () => {
    const repo = await as(CHOI)
    const add = vi.spyOn(repo, 'addProjectMember')
    const { wrapper } = await mountAt(AdminView, '/admin', repo)
    await button(wrapper, '구성원 배정').trigger('click')
    await wrapper.find('select[aria-label="배정할 사용자"]').setValue(5)
    await wrapper.find('select[aria-label="배정 역할"]').setValue('MEMBER')
    await wrapper.find('input[aria-label="배정일"]').setValue('2026-09-27')
    await wrapper.find('form.create').trigger('submit')
    await flushPromises()
    expect(add).toHaveBeenCalledWith(14, { userId: 5, projectRole: 'MEMBER', joinedOn: '2026-09-27' })
  })
})
