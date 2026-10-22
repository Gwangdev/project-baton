import { describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { ApiProblem } from '@/api/http'
import LoginView from '@/views/LoginView.vue'
import ProjectTimelineView from '@/views/ProjectTimelineView.vue'
import RecordCollectionView from '@/views/RecordCollectionView.vue'
import RecordInboxView from '@/views/RecordInboxView.vue'
import RecordReviewView from '@/views/RecordReviewView.vue'
import { DEMO_PASSWORDS, button, demoRepository, mountAt, signedIn } from './support'

const KIM = 'A-10421'
const LEE = 'A-10733'
const PARK = 'A-10188'

describe('S01 로그인', () => {
  it('서버의 거절을 보여주고 비밀번호를 지운다', async () => {
    const repo = demoRepository()
    const { wrapper } = await mountAt(LoginView, '/login', repo)
    await wrapper.find('input[aria-label="사내 계정"]').setValue(KIM)
    await wrapper.find('input[aria-label="비밀번호"]').setValue('wrong')
    await wrapper.find('form').trigger('submit')
    // 데모는 WebCrypto 해시를 비교하는데, 이 값은 마이크로태스크 밖에서 끝난다.
    await vi.waitFor(() => expect(wrapper.find('.error').text()).toContain('사내 계정 또는 비밀번호가 올바르지 않습니다'))
    expect((wrapper.find('input[aria-label="비밀번호"]').element as HTMLInputElement).value).toBe('')
  })

  it('공개 데모 빌드는 합성 데이터·매일 초기화·개인정보 입력 금지·접속 주소 기록을 알리고, 기본 빌드는 알리지 않는다', async () => {
    const plain = await mountAt(LoginView, '/login', demoRepository())
    expect(plain.wrapper.find('.public-demo').exists()).toBe(false)
    vi.stubEnv('VITE_PUBLIC_DEMO', 'true')
    try {
      const { wrapper } = await mountAt(LoginView, '/login', demoRepository())
      const notice = wrapper.find('.public-demo').text()
      expect(notice).toContain('합성 데이터')
      expect(notice).toContain('04:00')
      expect(notice).toContain('실제 개인정보')
      expect(notice).toContain('접속 주소')
    } finally {
      vi.unstubAllEnvs()
    }
  })

  it('api 빌드에는 데모 계정이 없다', async () => {
    const { wrapper } = await mountAt(LoginView, '/login', demoRepository())
    expect(wrapper.text()).not.toContain('데모 계정 안내')
    expect(wrapper.text()).not.toContain(DEMO_PASSWORDS[KIM])
  })

  it('로그인하고 앱 안의 redirect만 따른다', async () => {
    const repo = demoRepository()
    const { wrapper, router } = await mountAt(LoginView, '/login?redirect=/records', repo)
    await wrapper.find('input[aria-label="사내 계정"]').setValue(KIM)
    await wrapper.find('input[aria-label="비밀번호"]').setValue(DEMO_PASSWORDS[KIM])
    await wrapper.find('form').trigger('submit')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/records'))
  })
})

describe('S04 확인 대기 기록함', () => {
  it('초안 필터를 서버에 맡기고 cursor로 이어 읽는다', async () => {
    const repo = await signedIn(demoRepository(), KIM)
    const list = vi.spyOn(repo, 'listRecords')
    const { wrapper } = await mountAt(RecordInboxView, '/records', repo)
    expect(list).toHaveBeenLastCalledWith(14, { trustStatus: ['AUTO_DRAFT'], cursor: undefined, limit: 50 })
    expect(wrapper.findAll('tbody tr').map((r) => r.find('.table__code').text())).toEqual(['REC-106', 'REC-103'])

    list.mockResolvedValueOnce({ items: [], page: { total: 2, limit: 50, offset: 0, nextCursor: 'NEXT' } })
    await button(wrapper, '전체').trigger('click')
    await flushPromises()
    list.mockClear()
    await button(wrapper, '더 보기').trigger('click')
    await flushPromises()
    expect(list).toHaveBeenCalledWith(14, { trustStatus: undefined, cursor: 'NEXT', limit: 50 })
  })

  it('선택한 기록을 각자의 version으로 검토하고 거절을 기록별로 알린다', async () => {
    const repo = await signedIn(demoRepository(), KIM)
    const review = vi.spyOn(repo, 'reviewRecord')
    review.mockRejectedValueOnce(new ApiProblem({ status: 412, code: 'STALE_VERSION', traceId: 't' }))
    const { wrapper } = await mountAt(RecordInboxView, '/records', repo)
    await wrapper.find('input[aria-label="전체 선택"]').trigger('change')
    await button(wrapper, '선택 승인 (2)').trigger('click')
    await flushPromises()
    expect(review.mock.calls.map(([id, version, input]) => [id, version, input])).toEqual([
      [106, 0, { action: 'CONFIRM' }],
      [103, 0, { action: 'CONFIRM' }],
    ])
    expect(wrapper.text()).toContain('1건 처리 · 1건 실패 — REC-106: 다른 사용자가 먼저 변경')
  })
})

describe('S05 기록 상세·확인', () => {
  it('읽은 version으로 승인한다', async () => {
    const repo = await signedIn(demoRepository(), KIM)
    const review = vi.spyOn(repo, 'reviewRecord')
    const { wrapper } = await mountAt(RecordReviewView, '/records/103', repo)
    expect(wrapper.findAll('.pane__head').map((p) => p.text())).toEqual(['원문', 'AI 추출 결과'])
    await button(wrapper, '승인').trigger('click')
    await flushPromises()
    expect(review).toHaveBeenCalledWith(103, 0, { action: 'CONFIRM' })
    expect(wrapper.find('.badge').text()).toBe('확인됨')
  })

  it('수정 후 승인은 바뀐 필드와 사유만 보낸다', async () => {
    const repo = await signedIn(demoRepository(), KIM)
    const review = vi.spyOn(repo, 'reviewRecord')
    const { wrapper } = await mountAt(RecordReviewView, '/records/103', repo)
    await wrapper.find('textarea[aria-label="요약"]').setValue('고친 요약')
    expect(button(wrapper, '승인').attributes('disabled')).toBeDefined()
    expect(button(wrapper, '수정 후 승인').attributes('disabled')).toBeDefined()
    await wrapper.find('input[aria-label="수정 사유"]').setValue('회의록과 대조')
    await button(wrapper, '수정 후 승인').trigger('click')
    await flushPromises()
    expect(review).toHaveBeenCalledWith(103, 0, { action: 'CORRECT', summary: '고친 요약', changeReason: '회의록과 대조' })
    expect(wrapper.find('.revisions').text()).toContain('회의록과 대조')
  })

  it('다른 사람이 먼저 검토했으면 덮어쓰지 않고 다시 불러온다', async () => {
    const repo = await signedIn(demoRepository(), KIM)
    const { wrapper } = await mountAt(RecordReviewView, '/records/103', repo)
    const fresh = await repo.getRecord(103)
    await repo.reviewRecord(103, fresh.version, { action: 'DISMISS' })
    await button(wrapper, '승인').trigger('click')
    await flushPromises()
    expect(wrapper.find('.state--failed').text()).toContain('다른 사용자가 먼저 변경')
    expect(wrapper.find('.badge').text()).toBe('제외됨')
  })

  it('공개하지 않는 ID에는 서버 답을 보여주고, 경로의 화면 code는 거부한다', async () => {
    const repo = await signedIn(demoRepository(), LEE)
    const { wrapper } = await mountAt(RecordReviewView, '/records/999', repo)
    expect(wrapper.text()).toContain('찾을 수 없거나 접근 권한이 없습니다')
    const byCode = await mountAt(RecordReviewView, '/records/REC-103', repo)
    expect(byCode.wrapper.text()).toContain('기록을 찾을 수 없습니다')
  })
})

describe('S05 관계 등록', () => {
  it('PM은 확정 기록에서 다른 확정 기록과의 관계를 등록한다', async () => {
    const repo = await signedIn(demoRepository(), PARK)
    const relate = vi.spyOn(repo, 'createRecordRelation')
    const { wrapper } = await mountAt(RecordReviewView, '/records/102', repo)
    const targets = wrapper.findAll('select[aria-label="관계 대상 기록"] option').map((o) => o.text())
    expect(targets.some((t) => t.startsWith('REC-104'))).toBe(true)
    expect(targets.some((t) => t.startsWith('REC-103'))).toBe(false)

    await wrapper.find('select[aria-label="관계 종류"]').setValue('REFERENCES')
    await wrapper.find('select[aria-label="관계 대상 기록"]').setValue(104)
    await wrapper.find('form.relate').trigger('submit')
    await flushPromises()

    expect(relate).toHaveBeenCalledWith(14, { fromRecordId: 102, toRecordId: 104, relationType: 'REFERENCES' })
    expect(wrapper.find('.revisions').text()).toContain('REC-104')
  })

  it('다른 기록으로 옮기면 관계 후보를 다시 읽어 지금 기록을 뺀다', async () => {
    const repo = await signedIn(demoRepository(), PARK)
    const { wrapper, router } = await mountAt(RecordReviewView, '/records/102', repo)
    await router.push('/records/104')
    await flushPromises()
    const targets = wrapper.findAll('select[aria-label="관계 대상 기록"] option').map((o) => o.text())
    expect(targets.some((t) => t.startsWith('REC-104'))).toBe(false)
    expect(targets.some((t) => t.startsWith('REC-102'))).toBe(true)
  })

  it('PM이 아니면 관계 등록도 철회도 보여주지 않는다', async () => {
    const { wrapper } = await mountAt(RecordReviewView, '/records/102', await signedIn(demoRepository(), KIM))
    expect(wrapper.find('form.relate').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('관계 철회')
  })

  it('PM은 관계를 등록한 기록 상세에서 사유와 함께 철회한다', async () => {
    const repo = await signedIn(demoRepository(), PARK)
    const withdraw = vi.spyOn(repo, 'withdrawRecordRelation')
    const { wrapper } = await mountAt(RecordReviewView, '/records/102', repo)
    expect(wrapper.find('.revisions').text()).toContain('REC-101')
    await button(wrapper, '관계 철회').trigger('click')
    await wrapper.find('input[aria-label="철회 사유"]').setValue('대체가 아니라 서로 어긋난다')
    await button(wrapper, '철회 확정').trigger('click')
    await flushPromises()
    expect(withdraw).toHaveBeenCalledWith(1, 0, '대체가 아니라 서로 어긋난다')
    // 102의 관계는 그 대체 하나였으므로, 다시 읽은 상세에는 관련 기록 절이 없다.
    expect(wrapper.text()).not.toContain('관련 기록')
  })

  it('같은 쌍에 다른 종류의 관계가 있어 거절되면 먼저 철회하라고 안내한다', async () => {
    const repo = await signedIn(demoRepository(), PARK)
    const { wrapper } = await mountAt(RecordReviewView, '/records/102', repo)
    await wrapper.find('select[aria-label="관계 종류"]').setValue('CONFLICTS_WITH')
    await wrapper.find('select[aria-label="관계 대상 기록"]').setValue(101)
    await wrapper.find('form.relate').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('「관련 기록」에서 철회한 뒤 다시 등록하십시오')
  })
})

describe('S03 타임라인', () => {
  it('화면에서 거르지 않고 필터를 서버에 넘긴다', async () => {
    const repo = await signedIn(demoRepository(), KIM)
    const list = vi.spyOn(repo, 'listRecords')
    const { wrapper } = await mountAt(ProjectTimelineView, '/projects/14', repo)
    await button(wrapper, '장애요인').trigger('click')
    await wrapper.find('input[aria-label="시작일"]').setValue('2026-09-01')
    await flushPromises()
    expect(list).toHaveBeenLastCalledWith(14, expect.objectContaining({ recordType: ['BLOCKER'], occurredFrom: '2026-09-01' }))
    expect(wrapper.findAll('.card__code').map((c) => c.text())).toEqual(['REC-104'])
  })

  it('늦게 도착한 이전 필터의 응답으로 최신 결과를 덮지 않는다', async () => {
    const repo = await signedIn(demoRepository(), KIM)
    const { wrapper } = await mountAt(ProjectTimelineView, '/projects/14', repo)
    const real = repo.listRecords.bind(repo)
    let releaseSlow!: () => void
    vi.spyOn(repo, 'listRecords')
      .mockImplementationOnce(async (id, query) => {
        const result = await real(id, query)
        await new Promise<void>((resolve) => { releaseSlow = resolve })
        return result
      })
      .mockImplementation(real)
    await button(wrapper, '장애요인').trigger('click')
    await flushPromises()
    await button(wrapper, '장애요인').trigger('click')
    await button(wrapper, '일정').trigger('click')
    await flushPromises()
    releaseSlow()
    await flushPromises()
    expect(wrapper.findAll('.card__code').map((c) => c.text())).toEqual(['REC-101'])
  })

  it('대체된 기록은 숨기지 않고 표시한다', async () => {
    const repo = await signedIn(demoRepository(), KIM)
    const { wrapper } = await mountAt(ProjectTimelineView, '/projects/14', repo)
    const card = wrapper.findAll('.card').find((c) => c.text().includes('REC-101'))!
    expect(card.text()).toContain('REC-102가 이 기록을 대체함')
  })
})

describe('S06 내 기록 수집', () => {
  it('소유자에게 폴더 파일을 보여주고 실패한 파일을 다시 처리하게 한다', async () => {
    const repo = await signedIn(demoRepository(), KIM)
    const retry = vi.spyOn(repo, 'retrySourceProcessing')
    const { wrapper } = await mountAt(RecordCollectionView, '/projects/14/collection', repo)
    expect(wrapper.text()).toContain('수집된 파일 3건')
    await button(wrapper, '다시 처리').trigger('click')
    await flushPromises()
    expect(retry).toHaveBeenCalledWith(15, 203)
  })

  it('처리에 실패한 파일은 소유자가 직접 초안을 써서 확인 대기로 보낸다', async () => {
    const repo = await signedIn(demoRepository(), KIM)
    const write = vi.spyOn(repo, 'createManualDraft')
    const { wrapper, router } = await mountAt(RecordCollectionView, '/projects/14/collection', repo)
    await wrapper.find('button[aria-label="고객보고_9월2주.pptx 직접 작성"]').trigger('click')
    await wrapper.find('select[aria-label="초안 유형"]').setValue('COMMITMENT')
    await wrapper.find('input[aria-label="초안 제목"]').setValue('주간 보고 일정 약속')
    await wrapper.find('textarea[aria-label="초안 요약"]').setValue('매주 금요일 고객 보고를 제출한다.')
    await wrapper.find('textarea[aria-label="초안 원문 발췌"]').setValue('보고 슬라이드 2쪽: 매주 금요일 제출')
    await wrapper.find('input[aria-label="초안 발생일"]').setValue('2026-09-10')
    await wrapper.find('form.draft').trigger('submit')
    await flushPromises()

    expect(write).toHaveBeenCalledWith(15, 203, {
      recordType: 'COMMITMENT', title: '주간 보고 일정 약속', summary: '매주 금요일 고객 보고를 제출한다.',
      originalExcerpt: '보고 슬라이드 2쪽: 매주 금요일 제출', occurredOn: '2026-09-10',
    })
    const created = await write.mock.results[0].value
    expect(created.trustStatus).toBe('AUTO_DRAFT')
    expect(router.currentRoute.value.fullPath).toBe(`/records/${created.id}`)
  })

  it('연결이 없는 사용자에게 폴더 연결을 제공한다', async () => {
    const repo = await signedIn(demoRepository(), LEE)
    const create = vi.spyOn(repo, 'createSourceConnection')
    const { wrapper } = await mountAt(RecordCollectionView, '/projects/14/collection', repo)
    await wrapper.find('input[aria-label="연결 이름"]').setValue('내 폴더')
    await wrapper.find('input[aria-label="폴더 경로"]').setValue('drive://approved/lee')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(create).toHaveBeenCalledWith(14, { channel: 'CLOUD_PROJECT_FOLDER', displayName: '내 폴더', externalRef: 'drive://approved/lee' })
    expect(wrapper.text()).toContain('수집된 파일 0건')
  })

  it('비공개 메모는 작성한 요약만 공유한다', async () => {
    const repo = await signedIn(demoRepository(), KIM)
    const share = vi.spyOn(repo, 'sharePersonalNoteSummary')
    const { wrapper } = await mountAt(RecordCollectionView, '/projects/14/collection', repo)
    await button(wrapper, '빠른 메모').trigger('click')
    await flushPromises()
    await wrapper.find('input[aria-label="공유 제목"]').setValue('대량 상담이력 다운로드 1차 오픈 제외')
    await wrapper.find('textarea[aria-label="공유 요약"]').setValue('2차 개선으로 이관한다.')
    await wrapper.find('input[aria-label="공유 발생일"]').setValue('2026-09-10')
    await button(wrapper, '선택 요약 공유').trigger('click')
    await flushPromises()
    expect(share).toHaveBeenCalledWith(1, {
      recordType: 'SCOPE_EXCEPTION', title: '대량 상담이력 다운로드 1차 오픈 제외', summary: '2차 개선으로 이관한다.', occurredOn: '2026-09-10',
    })
    expect(wrapper.find('.shared').text()).toContain('REC-105이(가) 확인됨 상태로 생성됐습니다')
  })
})
