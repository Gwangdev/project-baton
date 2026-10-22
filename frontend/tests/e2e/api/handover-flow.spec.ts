import { expect, test } from '@playwright/test'
import { currentUser, seoulToday, signIn, signOut } from './support.js'

/**
 * 실제 Spring + PostgreSQL + stub AI를 브라우저로 조작한다.
 *
 * 백엔드는 `e2e` 프로필이 넣은 합성 fixture에서 시작한다. 그 최신 확정 상태를 아래에 선언하고 가장 먼저 확인한다.
 * seed가 바뀌면 흐름 중간의 알 수 없는 실패가 아니라 여기서 바로 드러나게 하기 위해서다. 1단계 데모의 자동 진행은
 * 되풀이하지 않는다. 아래의 모든 상태 변화는 실제 명령이고, 패키지 생성은 화면이 폴링하는 동안 백엔드 워커가 끝낸다.
 */

const FIXTURE: Record<string, { recordType: string; trustStatus: string; dueOn?: string }> = {
  'REC-101': { recordType: 'SCHEDULE', trustStatus: 'CONFIRMED', dueOn: '2026-10-15' },
  'REC-102': { recordType: 'CHANGE_DECISION', trustStatus: 'CONFIRMED', dueOn: '2026-10-22' },
  'REC-103': { recordType: 'COMMITMENT', trustStatus: 'CORRECTED', dueOn: '2026-09-18' },
  'REC-104': { recordType: 'BLOCKER', trustStatus: 'CONFIRMED' },
  'REC-105': { recordType: 'SCOPE_EXCEPTION', trustStatus: 'CONFIRMED' },
  'REC-106': { recordType: 'OPEN_ISSUE', trustStatus: 'CONFIRMED', dueOn: '2026-09-16' },
  // 기존 담당자 개인 폴더의 미확정 초안 하나. 다른 사람에게는 보이지 않아야 한다.
  'REC-107': { recordType: 'OPEN_ISSUE', trustStatus: 'AUTO_DRAFT' },
}
const RELATIONS = [
  { from: 'REC-101', relationType: 'CONFLICTS_WITH', to: 'REC-102' },
  { from: 'REC-106', relationType: 'REFERENCES', to: 'REC-104' },
]
/** 백엔드의 결정적 기록 유형 문항 보기 이름 (DeterministicAssessmentFactory). */
const TYPE_CHOICE: Record<string, string> = {
  SCHEDULE: '일정', CHANGE_DECISION: '변경 결정', COMMITMENT: '고객 약속',
  BLOCKER: '장애요인', SCOPE_EXCEPTION: '범위 예외', OPEN_ISSUE: '미해결 결정',
}

const KIM = 'A-10421'
const PARK = 'A-10188'
const LEE = 'A-10733'
const CHOI = 'A-10002'

test.describe.configure({ mode: 'serial' })

const ids = { project: 0, kim: 0, lee: 0, handover: 0 }

test('로그인하지 않은 브라우저는 로그인 화면으로만 가고, 틀린 비밀번호는 그 자리에 머문다', async ({ page }) => {
  await page.goto('/records')
  await expect(page).toHaveURL(/\/login\?redirect=/)
  await page.fill('input[aria-label="사내 계정"]', CHOI)
  await page.fill('input[aria-label="비밀번호"]', 'not-the-password')
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page.locator('.error')).toContainText('사내 계정 또는 비밀번호가 올바르지 않습니다')
  await expect(page.locator('text=데모 계정 안내')).toHaveCount(0)
})

test('seed 프로젝트는 선언한 최신 확정 상태다', async ({ page }) => {
  await signIn(page, KIM)
  const kim = await currentUser(page)
  ids.kim = kim.user.id
  ids.project = kim.memberships[0].projectId

  const records = await (await page.request.get(`/api/v1/projects/${ids.project}/records?limit=100`)).json()
  const actual = Object.fromEntries(records.items.map((r: { code: string; recordType: string; trustStatus: string; dueOn?: string }) =>
    [r.code, { recordType: r.recordType, trustStatus: r.trustStatus, ...(r.dueOn ? { dueOn: r.dueOn } : {}) }]))
  expect(actual).toEqual(FIXTURE)

  for (const relation of RELATIONS) {
    const from = records.items.find((r: { code: string }) => r.code === relation.from)
    const detail = await (await page.request.get(`/api/v1/records/${from.id}`)).json()
    expect(detail.relations).toEqual(expect.arrayContaining([
      expect.objectContaining({ relationType: relation.relationType, relatedRecordCode: relation.to }),
    ]))
  }

  await signOut(page)
  await signIn(page, PARK)
  const managerView = await (await page.request.get(`/api/v1/projects/${ids.project}/records?limit=100`)).json()
  expect(managerView.items.map((r: { code: string }) => r.code)).not.toContain('REC-107')
})

test('기존 담당자가 비공개 초안을 검토하고 메모 요약을 공유한다', async ({ page }) => {
  await signIn(page, KIM)
  await page.click('a[href="/records"]')
  await expect(page.locator('tbody tr')).toHaveCount(1)
  await page.locator('tbody tr', { hasText: 'REC-107' }).getByRole('button', { name: '원문 대조' }).click()
  await expect(page.locator('.pane__excerpt')).toContainText('결제 예외 흐름')
  await page.getByRole('button', { name: '제외', exact: true }).click()
  await expect(page.locator('.badge')).toHaveText('제외됨')

  await page.getByRole('button', { name: '내 기록 수집으로' }).click()
  await expect(page.locator('.status__name')).toContainText('내 업무 폴더')
  await page.getByRole('button', { name: '빠른 메모' }).click()
  await page.fill('textarea[aria-label="새 메모"]', '9/26 고객 통화. 결제 예외 흐름은 2차 테스트에서 확인하기로 함. (개인 메모)')
  await page.getByRole('button', { name: '비공개 저장' }).click()
  await expect(page.locator('pre.body')).toContainText('9/26 고객 통화')
  await page.fill('input[aria-label="공유 제목"]', '결제 예외 흐름 2차 테스트로 이관')
  await page.fill('textarea[aria-label="공유 요약"]', '결제 예외 흐름 확인은 2차 통합 테스트에서 진행한다.')
  await page.getByRole('button', { name: '선택 요약 공유' }).click()
  await expect(page.locator('.shared')).toContainText('확인됨 상태로 생성됐습니다')
})

test('관리자가 교체를 요청하고, 워커를 기다린 뒤 충돌을 해소한다', async ({ page }) => {
  await signIn(page, LEE)
  ids.lee = (await currentUser(page)).user.id
  await signOut(page)

  await signIn(page, PARK)
  await page.getByRole('button', { name: '담당자 교체 요청' }).click()
  // 관리자가 아닌 PM도 구성원 목록에서 고른다(실제 백엔드의 구성원 목록 경로를 지난다).
  await page.selectOption('select[aria-label="기존 담당자"]', String(ids.kim))
  await page.selectOption('select[aria-label="신규 담당자"]', String(ids.lee))
  await page.fill('input[aria-label="교체 적용일"]', seoulToday())
  await page.getByRole('button', { name: '패키지 생성 요청' }).click()
  await expect(page).toHaveURL(/\/handovers\/\d+\/progress$/)
  ids.handover = Number(page.url().match(/handovers\/(\d+)/)![1])

  await expect(page.locator('[role="status"]')).toContainText('열람 가능', { timeout: 60_000 })
  await expect(page.locator('.counts')).toContainText('7건')
  await page.fill('input[aria-label="선택 사유"]', '변경 결재 문서가 최종 오픈일이다')
  await page.locator('input[aria-label="REC-102 최종 근거로 선택"]').check()
  await expect(page.locator('.conflict__resolved')).toContainText('최종 근거 REC-102')
})

test('신규 담당자가 명시 확인하고, 스트림으로 질문하고, 준비도를 통과한다', async ({ page }) => {
  await signIn(page, LEE)
  await page.getByRole('button', { name: '인수인계 패키지 열기' }).click()
  await expect(page).toHaveURL(new RegExp(`/handovers/${ids.handover}$`))

  const menu = page.locator('.menu__item')
  await expect(menu.first()).toContainText('프로젝트 개요')
  for (let i = 0; i < await menu.count(); i += 1) {
    await menu.nth(i).click()
    const pending = page.getByRole('button', { name: /이해했음$/ })
    for (let left = await pending.count(); left > 0; left -= 1) {
      await pending.first().click()
      await expect(pending).toHaveCount(left - 1)
    }
  }
  await expect(page.locator('.state[role="status"]')).toContainText(/필수 항목 확인 (\d+) \/ \1/)

  const streamed = page.waitForResponse((r) => r.url().endsWith(`/handovers/${ids.handover}/questions`) && r.request().method() === 'POST')
  await page.fill('input[aria-label="질문"]', 'CRM 테스트 계정 발급은 어떻게 되고 있나요?')
  await page.getByRole('button', { name: '질문', exact: true }).click()
  expect((await streamed).headers()['content-type']).toContain('text/event-stream')
  await expect(page.locator('.qa__text').last()).toContainText('REC-104')
  await expect(page.locator('.cite').first()).toContainText('REC-104')
  await expect(page.locator('.qa__pending')).toHaveCount(0)

  await page.getByRole('button', { name: '업무 준비도 확인' }).click()
  const assessment = await (await page.request.get(`/api/v1/handovers/${ids.handover}/assessment`)).json()
  for (const q of assessment.questions as { questionNo: number; questionType: string; linkedRecordCode: string }[]) {
    const fact = FIXTURE[q.linkedRecordCode]
    if (q.questionType === 'SHORT_ANSWER') {
      await page.fill(`input[aria-label="${q.questionNo}번 답안"]`, fact.dueOn!)
    } else {
      await page.locator(`input[aria-label="${q.questionNo}번 ${TYPE_CHOICE[fact.recordType]}"]`).check()
    }
  }
  await page.getByRole('button', { name: '제출', exact: true }).click()
  await expect(page.locator('.result__score')).toHaveText('5 / 5')
})

test('관리자가 승인하면 기존 담당자는 프로젝트 접근을 잃는다', async ({ page }) => {
  await signIn(page, PARK)
  await page.goto(`/handovers/${ids.handover}/review`)
  await expect(page.locator('.panel__note').first()).toHaveText('모두 충족')
  await page.getByRole('button', { name: '인수인계 완료 승인' }).click()
  await expect(page.locator('.completed')).toContainText('인수인계가 완료됐습니다')
  await signOut(page)

  await signIn(page, KIM)
  await page.goto(`/projects/${ids.project}`)
  await expect(page.locator('.state--failed')).toContainText('찾을 수 없거나 접근 권한이 없습니다')
})

test('권한은 서버가 정한다 — 일반 구성원은 패키지를 거절당하고, 관리는 시스템 관리자만 한다', async ({ page }) => {
  await signIn(page, CHOI)
  await page.goto(`/handovers/${ids.handover}`)
  await expect(page.locator('.state--failed')).toContainText('이 작업을 수행할 권한이 없습니다')
  await page.goto('/admin')
  await expect(page.locator('.sub')).toContainText('사용자·조직·프로젝트·연동을 관리합니다')
  await expect(page.locator('tbody tr', { hasText: 'A-10733' })).toHaveCount(1)
  await signOut(page)

  await signIn(page, LEE)
  await page.goto('/admin')
  await expect(page.locator('.sub')).toContainText('연동 상태만 조회합니다')
})
