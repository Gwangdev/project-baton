import { describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createDemoRepository } from '@/demo/demoRepository'
import CredentialSetupView from '@/views/CredentialSetupView.vue'
import { DEMO_PASSWORDS, button, mountAt } from './support'

/** 관리자에게서 받은 설정 코드로 비밀번호를 정하는 화면(S01의 한 경로). */

/** 비밀번호는 실행마다 새로 만든다. 저장소에는 어떤 비밀번호도 적지 않는다. */
const chosen = () => `chosen-${crypto.randomUUID()}`

async function issued() {
  const repo = createDemoRepository({ delayMs: 0, generationMs: 0 })
  await repo.signIn('A-10002', DEMO_PASSWORDS['A-10002'])
  const created = await repo.createUser({ organizationId: 1, employeeNo: 'N-2', name: '신규', title: '사원', email: 'n2@example.test' })
  const { token } = await repo.issueCredentialToken(created.id)
  await repo.signOut()
  return { repo, token }
}

async function fill(wrapper: Awaited<ReturnType<typeof mountAt>>['wrapper'], values: { employeeNo: string; token: string; password: string; confirm: string }) {
  await wrapper.find('input[aria-label="사내 계정"]').setValue(values.employeeNo)
  await wrapper.find('input[aria-label="설정 코드"]').setValue(values.token)
  await wrapper.find('input[aria-label="새 비밀번호"]').setValue(values.password)
  await wrapper.find('input[aria-label="새 비밀번호 확인"]').setValue(values.confirm)
}

describe('S01 설정 코드로 비밀번호 정하기', () => {
  it('코드와 새 비밀번호를 보내고 성공하면 로그인으로 안내한다', async () => {
    const { repo, token } = await issued()
    const set = vi.spyOn(repo, 'setCredential')
    const { wrapper } = await mountAt(CredentialSetupView, '/credentials', repo)

    const password = chosen()
    await fill(wrapper, { employeeNo: 'N-2', token, password, confirm: password })
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(set).toHaveBeenCalledWith({ employeeNo: 'N-2', token, newPassword: password })
    expect(wrapper.text()).toContain('비밀번호를 정했습니다')
    expect(wrapper.find('a[href="/login"]').exists()).toBe(true)
  })

  it('두 비밀번호가 다르면 보내지 않는다', async () => {
    const { repo, token } = await issued()
    const set = vi.spyOn(repo, 'setCredential')
    const { wrapper } = await mountAt(CredentialSetupView, '/credentials', repo)

    await fill(wrapper, { employeeNo: 'N-2', token, password: chosen(), confirm: chosen() })

    expect(button(wrapper, '비밀번호 정하기').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('서로 다릅니다')
    expect(set).not.toHaveBeenCalled()
  })

  it('틀린 코드는 계정 유무를 드러내지 않는 안내로 보여 주고 입력한 비밀번호를 지운다', async () => {
    const { repo } = await issued()
    const { wrapper } = await mountAt(CredentialSetupView, '/credentials', repo)

    const password = chosen()
    await fill(wrapper, { employeeNo: 'N-2', token: crypto.randomUUID(), password, confirm: password })
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('설정 코드')
    expect((wrapper.find('input[aria-label="새 비밀번호"]').element as HTMLInputElement).value).toBe('')
  })
})
