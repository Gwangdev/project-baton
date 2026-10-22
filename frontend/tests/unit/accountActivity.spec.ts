import { describe, expect, it } from 'vitest'
import type { BatonRepository } from '@/api/batonRepository'
import DashboardView from '@/views/DashboardView.vue'
import { demoRepository, mountAt, signedIn } from './support'

const KIM = 'A-10421'

/** 본인 계정에 일어난 일을 계정 영역에서 펼쳐 볼 수 있다(서버가 최근 5건을 준다). */
describe('내 계정 활동', () => {
  async function withActivity(activity: unknown): Promise<BatonRepository> {
    const repo = await signedIn(demoRepository(), KIM)
    const original = repo.getCurrentUser
    repo.getCurrentUser = async () => ({ ...(await original()), accountActivity: activity as never })
    return repo
  }

  it('서버가 준 활동을 종류·시각·주소와 함께 최신순으로 보여 준다', async () => {
    const { wrapper } = await mountAt(
      DashboardView,
      '/dashboard',
      await withActivity([
        { action: 'SIGN_IN', occurredAt: '2026-10-06T10:00:30.123+09:00', remoteAddress: '203.0.113.9' },
        { action: 'SET_CREDENTIAL', occurredAt: '2026-10-05T01:00:00Z', remoteAddress: '198.51.100.5' },
        { action: 'ISSUE_CREDENTIAL_TOKEN', occurredAt: '2026-10-05T00:00:00Z', remoteAddress: null },
        { action: 'RESET_SYSTEM_ADMIN_CREDENTIAL', occurredAt: '2026-10-04T00:00:00Z', remoteAddress: null },
      ]),
    )

    const items = wrapper.findAll('.activity__item')
    expect(items.map((i) => i.find('.activity__what').text())).toEqual(['로그인', '비밀번호 설정', '설정 코드 발급(관리자)', '운영자가 비밀번호 재설정'])
    expect(items[0].find('.activity__where').text()).toBe('203.0.113.9')
    // 시각은 다른 화면과 같은 방식으로 서버가 준 오프셋의 시각을 그대로 보여 준다.
    expect(items[0].find('.activity__when').text()).toBe('2026-10-06 10:00')
    expect(items[2].find('.activity__where').text()).toBe('주소 기록 없음')
    expect(items[3].find('.activity__where').text()).toBe('주소 기록 없음')
    expect(wrapper.find('.activity__hint').text()).toContain('모르는 주소')
  })

  it('활동이 없거나 서버가 생략하면 아무것도 그리지 않는다', async () => {
    for (const none of [[], undefined]) {
      const { wrapper } = await mountAt(DashboardView, '/dashboard', await withActivity(none))
      expect(wrapper.find('.activity').exists()).toBe(false)
    }
  })

  it('데모 저장소는 가상의 로그인 한 건을 준다', async () => {
    const repo = await signedIn(demoRepository(), KIM)
    const me = await repo.getCurrentUser()
    expect(me.accountActivity?.map((a) => a.action)).toEqual(['SIGN_IN'])
  })
})
