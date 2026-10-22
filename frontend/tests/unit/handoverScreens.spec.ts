import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import type { BatonRepository } from '@/api/batonRepository'
import { createDemoRepository } from '@/demo/demoRepository'
import HandoverPackageView from '@/views/HandoverPackageView.vue'
import HandoverProgressView from '@/views/HandoverProgressView.vue'
import HandoverRequestView from '@/views/HandoverRequestView.vue'
import ManagerReviewView from '@/views/ManagerReviewView.vue'
import ReadinessCheckView from '@/views/ReadinessCheckView.vue'
import { ApiProblem } from '@/api/http'
import type { QaStreamHandlers, QaStreamResult } from '@/api/qaStream'
import { DEMO_PASSWORDS, button, mountAt } from './support'

const KIM = 'A-10421'
const PARK = 'A-10188'
const LEE = 'A-10733'
const ALL_TYPES = ['SCHEDULE', 'CHANGE_DECISION', 'COMMITMENT', 'BLOCKER', 'SCOPE_EXCEPTION', 'OPEN_ISSUE'] as const

async function as(repo: BatonRepository, employeeNo: string) {
  await repo.signOut()
  await repo.signIn(employeeNo, DEMO_PASSWORDS[employeeNo])
  return repo
}

/** 충돌이 아직 열린 게시된 패키지. `now`로 테스트가 생성을 붙잡아 둘 수 있다. */
async function published(options: { generationMs?: number; now?: () => number } = {}) {
  const repo = createDemoRepository({ delayMs: 0, generationMs: options.generationMs ?? 0, now: options.now })
  await as(repo, PARK)
  const accepted = await repo.requestHandover(14, {
    outgoingOwnerId: 1, incomingOwnerId: 3, effectiveOn: '2026-09-12', scopeFrom: '2026-06-14', scopeTo: '2026-09-11',
    scopeRecordTypes: [...ALL_TYPES], includeNoteDerivedRecords: true,
  })
  return { repo, id: accepted.id }
}

afterEach(() => vi.useRealTimers())

describe('S07 담당자 교체 요청', () => {
  it('관리자가 아닌 구성원도 현재 구성원 목록에서 고르고, 요청 권한은 서버가 거절한다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), KIM)
    const { wrapper } = await mountAt(HandoverRequestView, '/handovers/new', repo)
    expect(wrapper.find('input[aria-label="신규 담당자 사용자 ID"]').exists()).toBe(false)
    await wrapper.find('select[aria-label="기존 담당자"]').setValue(1)
    await wrapper.find('select[aria-label="신규 담당자"]').setValue(3)
    await button(wrapper, '패키지 생성 요청').trigger('click')
    await flushPromises()
    expect(wrapper.find('.state--denied').text()).toContain('프로젝트 관리자만')
  })

  it('구성원 목록을 기다리는 동안에는 실패 안내나 사용자 ID 입력을 보이지 않는다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), PARK)
    vi.spyOn(repo, 'listCurrentProjectMembers').mockReturnValue(new Promise(() => {}))
    const { wrapper } = await mountAt(HandoverRequestView, '/handovers/new', repo)
    expect(wrapper.text()).toContain('구성원 목록을 불러오는 중')
    expect(wrapper.find('input[aria-label="신규 담당자 사용자 ID"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('불러오지 못했습니다')
  })

  it('구성원 목록을 받지 못하면 사용자 ID 입력으로 바꾼다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), PARK)
    vi.spyOn(repo, 'listCurrentProjectMembers').mockRejectedValue(new Error('unavailable'))
    const { wrapper } = await mountAt(HandoverRequestView, '/handovers/new', repo)
    expect(wrapper.find('input[aria-label="신규 담당자 사용자 ID"]').exists()).toBe(true)
  })

  it('고른 범위를 제출하고 접수된 ID로 생성 상태 화면을 연다', async () => {
    const repo = await as(createDemoRepository({ delayMs: 0 }), PARK)
    const request = vi.spyOn(repo, 'requestHandover')
    const { wrapper, router } = await mountAt(HandoverRequestView, '/handovers/new', repo)
    await wrapper.find('select[aria-label="기존 담당자"]').setValue(1)
    await wrapper.find('select[aria-label="신규 담당자"]').setValue(3)
    await wrapper.find('input[aria-label="교체 적용일"]').setValue('2026-09-12')
    await button(wrapper, '미해결 결정').trigger('click')
    await button(wrapper, '패키지 생성 요청').trigger('click')
    await flushPromises()
    expect(request).toHaveBeenCalledWith(14, expect.objectContaining({
      outgoingOwnerId: 1, incomingOwnerId: 3, effectiveOn: '2026-09-12', scopeFrom: '2026-06-01',
      scopeRecordTypes: ['SCHEDULE', 'CHANGE_DECISION', 'COMMITMENT', 'BLOCKER', 'SCOPE_EXCEPTION'],
    }))
    expect(router.currentRoute.value.path).toBe('/handovers/1/progress')
  })
})

describe('S08 패키지 생성 상태', () => {
  it('스스로 진행시키지 않고 워커가 게시할 때까지 getHandover를 폴링한다', async () => {
    vi.useFakeTimers()
    let now = 0
    const { repo, id } = await published({ generationMs: 3000, now: () => now })
    const read = vi.spyOn(repo, 'getHandover')
    const { wrapper } = await mountAt(HandoverProgressView, `/handovers/${id}/progress`, repo)
    expect(wrapper.text()).toContain('현재 상태: 요청 접수')
    now = 1500
    await vi.advanceTimersByTimeAsync(1500)
    expect(wrapper.text()).toContain('현재 상태: 패키지 생성 중')
    now = 3000
    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.text()).toContain('현재 상태: 열람 가능')
    expect(read).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('1건 미해소')
    wrapper.unmount()
  })

  it('서버가 100건까지만 보내는 확인 대기 경고는 「이상」으로 적는다', async () => {
    const { repo, id } = await published()
    const original = repo.getHandover.bind(repo)
    vi.spyOn(repo, 'getHandover').mockImplementation(async (handoverId) => {
      const detail = await original(handoverId)
      const sample = (await repo.listRecords(14, { limit: 1 })).items[0]
      return { ...detail, unconfirmedRecords: Array.from({ length: 100 }, () => sample) }
    })
    const { wrapper } = await mountAt(HandoverProgressView, `/handovers/${id}/progress`, repo)
    expect(wrapper.find('.counts').text()).toContain('확인 대기100건 이상')
    wrapper.unmount()
  })

  it('항목 version과 고른 후보로 충돌을 해소한다', async () => {
    const { repo, id } = await published()
    const resolve = vi.spyOn(repo, 'resolveHandoverItem')
    const { wrapper } = await mountAt(HandoverProgressView, `/handovers/${id}/progress`, repo)
    await wrapper.find('input[aria-label="선택 사유"]').setValue('결재 문서가 최종')
    await wrapper.find('input[aria-label="REC-102 최종 근거로 선택"]').trigger('change')
    await flushPromises()
    const conflict = (await repo.listHandoverItems(id)).items.find((i) => i.conflictId)!
    expect(resolve).toHaveBeenCalledWith(id, conflict.id, 0, 102, '결재 문서가 최종')
    expect(wrapper.text()).toContain('최종 근거 REC-102 — 결재 문서가 최종')
  })

  it('대체 쌍에서는 대체된 옛 기록을 고를 수 없게 표시한다', async () => {
    const { repo, id } = await published()
    const { wrapper } = await mountAt(HandoverProgressView, `/handovers/${id}/progress`, repo)
    const replaced = wrapper.find('input[aria-label="REC-101 최종 근거로 선택"]')
    expect(replaced.attributes('disabled')).toBeDefined()
    expect(wrapper.find('.conflicts').text()).toContain('대체된 기록')
    expect(wrapper.find('input[aria-label="REC-102 최종 근거로 선택"]').attributes('disabled')).toBeUndefined()
  })

  it('서버처럼 충돌 항목 ID와 관계 ID가 달라도 대체 쌍을 후보 기록으로 찾아 옛 기록을 막는다', async () => {
    const { repo, id } = await published()
    // 서버의 conflictId는 충돌 행의 ID이고 conflicts[].id는 관계의 ID다. 두 값이 같다고 가정하면 짝을 못 찾는다.
    const items = repo.listHandoverItems.bind(repo)
    vi.spyOn(repo, 'listHandoverItems').mockImplementation(async (handoverId) => {
      const page = await items(handoverId)
      return { ...page, items: page.items.map((i) => (i.conflictId ? { ...i, conflictId: 987654 } : i)) }
    })
    const { wrapper } = await mountAt(HandoverProgressView, `/handovers/${id}/progress`, repo)
    expect(wrapper.find('input[aria-label="REC-101 최종 근거로 선택"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('잘못된 관계를 사유와 함께 철회하면 고를 수 없게 하고 패키지를 다시 만들라고 알린다', async () => {
    const { repo, id } = await published()
    const withdraw = vi.spyOn(repo, 'withdrawRecordRelation')
    const relation = (await repo.getHandover(id)).conflicts[0]
    const { wrapper } = await mountAt(HandoverProgressView, `/handovers/${id}/progress`, repo)
    await button(wrapper, '관계 철회').trigger('click')
    await wrapper.find('input[aria-label="철회 사유"]').setValue('개장일 변경은 합의되지 않았다')
    await button(wrapper, '철회 확정').trigger('click')
    await flushPromises()
    expect(withdraw).toHaveBeenCalledWith(relation.id, relation.version, '개장일 변경은 합의되지 않았다')
    expect(wrapper.find('.conflicts').text()).toContain('철회된 관계')
    expect(wrapper.find('.conflicts').text()).toContain('패키지를 다시 만들어야 합니다')
    expect(wrapper.find('input[aria-label="REC-102 최종 근거로 선택"]').attributes('disabled')).toBeDefined()
    expect((await repo.getHandover(id)).approvalBlockers).toContain('RELATIONS_WITHDRAWN')
    // 서버처럼 철회된 항목은 미해소로 세지 않고 재생성이 필요한 건으로 따로 센다.
    expect(wrapper.text()).toContain('0건 미해소')
    expect(wrapper.text()).toContain('1건 철회됨')
    wrapper.unmount()
  })

  it('철회가 실패한 뒤 다시 성공하면 앞선 실패 문구를 지운다', async () => {
    const { repo, id } = await published()
    const real = repo.withdrawRecordRelation.bind(repo)
    vi.spyOn(repo, 'withdrawRecordRelation').mockRejectedValueOnce(new Error('일시적 실패')).mockImplementation(real)
    const { wrapper } = await mountAt(HandoverProgressView, `/handovers/${id}/progress`, repo)
    await button(wrapper, '관계 철회').trigger('click')
    await wrapper.find('input[aria-label="철회 사유"]').setValue('개장일 변경은 합의되지 않았다')
    await button(wrapper, '철회 확정').trigger('click')
    await flushPromises()
    expect(wrapper.find('.conflicts').text()).toContain('일시적 실패')
    await button(wrapper, '철회 확정').trigger('click')
    await flushPromises()
    expect(wrapper.find('.conflicts').text()).toContain('철회된 관계')
    expect(wrapper.find('.conflicts').text()).not.toContain('일시적 실패')
    wrapper.unmount()
  })

  it('철회가 진행 중이면 같은 항목의 후보를 고르지 못하게 한다', async () => {
    const { repo, id } = await published()
    const real = repo.withdrawRecordRelation.bind(repo)
    let release!: () => void
    vi.spyOn(repo, 'withdrawRecordRelation').mockImplementation(async (...args) => {
      await new Promise<void>((done) => { release = done })
      return real(...args)
    })
    const { wrapper } = await mountAt(HandoverProgressView, `/handovers/${id}/progress`, repo)
    const candidate = () => wrapper.find('input[aria-label="REC-102 최종 근거로 선택"]')
    expect(candidate().attributes('disabled')).toBeUndefined()
    await button(wrapper, '관계 철회').trigger('click')
    await wrapper.find('input[aria-label="철회 사유"]').setValue('개장일 변경은 합의되지 않았다')
    await button(wrapper, '철회 확정').trigger('click')
    await flushPromises()
    expect(candidate().attributes('disabled')).toBeDefined()
    release()
    await flushPromises()
    wrapper.unmount()
  })

  it('선택 사유는 서버가 저장하는 300자까지만 입력받는다', async () => {
    const { repo, id } = await published()
    const { wrapper } = await mountAt(HandoverProgressView, `/handovers/${id}/progress`, repo)
    expect(wrapper.find('input[aria-label="선택 사유"]').attributes('maxlength')).toBe('300')
    wrapper.unmount()
  })
})

describe('S09 인수인계 패키지', () => {
  it('필수 항목을 version과 함께 명시 확인한다', async () => {
    const { repo, id } = await published()
    await as(repo, LEE)
    const ack = vi.spyOn(repo, 'acknowledgeHandoverItem')
    const { wrapper } = await mountAt(HandoverPackageView, `/handovers/${id}`, repo)
    expect(wrapper.text()).toContain('필수 항목 확인 0 /')
    await wrapper.find('button[aria-label="프로젝트 개요 이해했음"]').trigger('click')
    await flushPromises()
    expect(ack).toHaveBeenCalledWith(id, 1, 0)
    expect(wrapper.text()).toContain('필수 항목 확인 1 /')
  })

  it('delta는 초안으로만 보이고 저장된 답변으로 바뀐다', async () => {
    const { repo, id } = await published()
    await as(repo, LEE)
    let release!: (result: QaStreamResult) => void
    vi.spyOn(repo, 'askQuestion').mockImplementation(async (_h: number, _q: string, handlers: QaStreamHandlers) => {
      handlers.onMeta?.({ questionId: 1, generation: 1, traceId: 't' })
      handlers.onEvidence?.([])
      handlers.onDelta?.('임시 문장입니다.')
      return new Promise<QaStreamResult>((resolve) => { release = resolve })
    })
    const { wrapper } = await mountAt(HandoverPackageView, `/handovers/${id}`, repo)
    await wrapper.find('input[aria-label="질문"]').setValue('최종 오픈일은?')
    await wrapper.find('form.qa__form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.qa__draft').text()).toBe('임시 문장입니다.')

    vi.spyOn(repo, 'listQuestions').mockResolvedValueOnce({
      items: [
        { id: 1, role: 'USER', content: '최종 오픈일은?', createdAt: '2026-09-27T00:00:00Z' },
        { id: 2, role: 'ASSISTANT', content: '저장된 답변', createdAt: '2026-09-27T00:00:01Z', answerStatus: 'ANSWERED', citations: [] },
      ],
      page: { total: 2, limit: 100, offset: 0, nextCursor: null },
    })
    release({ kind: 'done', answer: { id: 2, role: 'ASSISTANT', content: '저장된 답변', createdAt: '2026-09-27T00:00:01Z' } })
    await flushPromises()
    expect(wrapper.find('.qa__draft').exists()).toBe(false)
    expect(wrapper.find('.qa__text').text()).toBe('저장된 답변')
  })

  it('스트림 오류면 초안을 버리고 재시도 여부를 알린다', async () => {
    const { repo, id } = await published()
    await as(repo, LEE)
    vi.spyOn(repo, 'askQuestion').mockImplementation(async (_h: number, _q: string, handlers: QaStreamHandlers) => {
      handlers.onDelta?.('버려질 문장')
      return { kind: 'error', code: 'MODEL_TIMEOUT', retryable: true, traceId: 'tr-7' }
    })
    const { wrapper } = await mountAt(HandoverPackageView, `/handovers/${id}`, repo)
    await wrapper.find('input[aria-label="질문"]').setValue('질문')
    await wrapper.find('form.qa__form').trigger('submit')
    await flushPromises()
    expect(wrapper.find('.qa__draft').exists()).toBe(false)
    expect(wrapper.find('.qa__pending').text()).toContain('AI 응답 시간이 초과되었습니다 — 잠시 후 다시 질문하십시오. (추적 ID tr-7)')
  })

  it('질문 이력의 일시적 실패는 알리되 질문 입력은 남긴다', async () => {
    const { repo, id } = await published()
    await as(repo, LEE)
    vi.spyOn(repo, 'listQuestions').mockRejectedValueOnce(new ApiProblem({ status: 503, code: 'HTTP_503', traceId: 't-9' }))
    const { wrapper } = await mountAt(HandoverPackageView, `/handovers/${id}`, repo)
    expect(wrapper.find('.qa .state--failed').text()).toContain('추적 ID t-9')
    expect(wrapper.find('input[aria-label="질문"]').exists()).toBe(true)
  })

  it('서버 판단대로 Q&A는 신규 담당자에게만 열린다', async () => {
    const { repo, id } = await published()
    const { wrapper } = await mountAt(HandoverPackageView, `/handovers/${id}`, repo)
    expect(wrapper.find('.qa .state--denied').text()).toContain('지정된 신규 담당자만')
  })
})

describe('S10 업무 준비도', () => {
  it('모든 답을 한 번에 제출하고 서버 채점을 보여준다', async () => {
    const { repo, id } = await published()
    await as(repo, LEE)
    const submit = vi.spyOn(repo, 'submitAssessmentAttempt')
    const { wrapper } = await mountAt(ReadinessCheckView, `/handovers/${id}/readiness`, repo)
    const inputs = wrapper.findAll('input.question__input')
    expect(inputs).toHaveLength(5)
    await inputs[0].setValue('10월 22일')
    expect(button(wrapper, '제출').attributes('disabled')).toBeDefined()
    for (const input of inputs.slice(1)) await input.setValue('모름')
    await button(wrapper, '제출').trigger('click')
    await flushPromises()
    expect(submit.mock.calls[0][1]).toEqual([
      { questionId: 1, answerText: '10월 22일' }, { questionId: 2, answerText: '모름' }, { questionId: 3, answerText: '모름' },
      { questionId: 4, answerText: '모름' }, { questionId: 5, answerText: '모름' },
    ])
    expect(wrapper.find('.result__score').text()).toBe('1 / 5')
    expect(wrapper.findAll('.question__record').map((r) => r.text())).toContain('근거 기록 REC-104')
  })

  it('객관식은 보기 ID를 보낸다', async () => {
    const { repo, id } = await published()
    await as(repo, LEE)
    vi.spyOn(repo, 'getAssessment').mockResolvedValue({
      generation: 1, status: 'NOT_STARTED', passThreshold: 1, attempts: [],
      questions: [{ id: 9, questionNo: 1, questionType: 'MULTIPLE_CHOICE', prompt: '어떤 유형의 기록인가?', linkedRecordCode: 'REC-102',
        choices: [{ id: 'TYPE_schedule', text: '일정' }, { id: 'TYPE_change_decision', text: '변경 결정' }] }],
    })
    const submit = vi.spyOn(repo, 'submitAssessmentAttempt').mockResolvedValue({ attemptNo: 1, score: 1, total: 1, answers: [] })
    const { wrapper } = await mountAt(ReadinessCheckView, `/handovers/${id}/readiness`, repo)
    await wrapper.find('input[aria-label="1번 변경 결정"]').setValue(true)
    await button(wrapper, '제출').trigger('click')
    await flushPromises()
    expect(submit).toHaveBeenCalledWith(id, [{ questionId: 9, answerText: 'TYPE_change_decision' }])
  })
})

describe('S11 관리자 검토', () => {
  it('서버의 차단 사유를 나열하고 모두 사라질 때까지 승인을 막는다', async () => {
    const { repo, id } = await published()
    const { wrapper } = await mountAt(ManagerReviewView, `/handovers/${id}/review`, repo)
    const blockers = wrapper.findAll('.blockers li').map((b) => b.text())
    expect(blockers).toEqual([
      '신규 담당자가 확인하지 않은 필수 항목이 있습니다',
      '최종 근거를 고르지 않은 상충 기록이 있습니다',
      '업무 준비도 점검이 제출되지 않았습니다',
    ])
    expect(button(wrapper, '인수인계 완료 승인').attributes('disabled')).toBeDefined()
  })

  it('읽은 version과 사유로 보완을 요청한다', async () => {
    const { repo, id } = await published()
    const decide = vi.spyOn(repo, 'decideHandoverCompletion')
    const { wrapper } = await mountAt(ManagerReviewView, `/handovers/${id}/review`, repo)
    expect(button(wrapper, '보완 요청').attributes('disabled')).toBeDefined()
    await wrapper.find('textarea[aria-label="보완 요청 사유"]').setValue('CRM 계정 대응 계획 보완')
    await button(wrapper, '보완 요청').trigger('click')
    await flushPromises()
    expect(decide).toHaveBeenCalledWith(id, 1, 'REQUEST_REVISION', 'CRM 계정 대응 계획 보완')
    expect(wrapper.find('.sub').text()).toContain('보완 요청됨')
  })
})
