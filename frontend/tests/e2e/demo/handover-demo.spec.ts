import { expect, test, type Page } from '@playwright/test'
import { DEMO_PASSWORDS } from '../../../src/demo/demoCredentials.ts'

/**
 * 데모 모드 E2E (VITE_DATA_MODE=demo).
 *
 * 메모리 데모 저장소를 상대로 1단계 장면 1~7을 브라우저에서 밟는다. 사람을 바꾸려면 실제 백엔드와 똑같이
 * 로그아웃하고 다시 로그인한다. Actor 전환은 없다. 상태가 메모리에만 있으므로 장면 사이에 새로 고치지 않고
 * 앱 안에서 이동한다.
 */

const ACCOUNTS: Record<string, { id: string; pw: string }> = Object.fromEntries(
  [['김현우', 'A-10421'], ['박지민', 'A-10188'], ['이서연', 'A-10733'], ['최도윤', 'A-10002']]
    .map(([name, id]) => [name, { id, pw: DEMO_PASSWORDS[id] }]),
)

async function fillSignIn(page: Page, name: keyof typeof ACCOUNTS) {
  await page.fill('input[aria-label="사내 계정"]', ACCOUNTS[name].id)
  await page.fill('input[aria-label="비밀번호"]', ACCOUNTS[name].pw)
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}

async function signIn(page: Page, name: keyof typeof ACCOUNTS) {
  await page.goto('/login')
  await fillSignIn(page, name)
}

/** 새로 고치지 않고 로그아웃·로그인해 메모리 데모 상태를 유지한다. */
async function changeUser(page: Page, name: keyof typeof ACCOUNTS) {
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/login/)
  await fillSignIn(page, name)
}

test('루트는 로그인에서 시작하고 모든 화면에 세션이 필요하다 (FR-01)', async ({ page }) => {
  await page.goto('/')
  await expect(page).toHaveURL(/\/login/)
  for (const path of ['/dashboard', '/records', '/admin', '/handovers/new']) {
    await page.goto(path)
    await expect(page, `${path} opened without a session`).toHaveURL(/\/login/)
  }
})

test('틀린 자격증명은 계정과 무관하게 같은 문구로 거절된다', async ({ page }) => {
  await page.goto('/login')
  await page.fill('input[aria-label="사내 계정"]', 'A-10421')
  await page.fill('input[aria-label="비밀번호"]', '틀린비밀번호')
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page.locator('.error')).toContainText('사내 계정 또는 비밀번호가 올바르지 않습니다')
  await expect(page).toHaveURL(/\/login/)
})

test('셸이 로그인 계정과 데모 안내를 보여준다', async ({ page }) => {
  await signIn(page, '김현우')
  await expect(page.locator('.pagehead')).toContainText('B리테일 옴니채널 고객지원 플랫폼 구축')
  await expect(page.locator('.account__name')).toContainText('김현우')
  await expect(page.locator('.footnote')).toContainText('가상 정보')
})

test('장면 1~7 — 기존 담당자에서 관리자 완료 승인까지', async ({ page }) => {
  // 장면 1 — 기존 담당자가 확인 대기 기록을 처리한다.
  await signIn(page, '김현우')
  await page.getByRole('button', { name: /확인 대기 2건 처리/ }).click()
  await expect(page.locator('tbody tr')).toHaveCount(2)
  await page.locator('tbody tr', { hasText: 'REC-103' }).getByRole('button', { name: '원문 대조' }).click()
  await page.fill('input[aria-label="기한"]', '2026-09-18')
  await page.fill('input[aria-label="수정 사유"]', '회의록에 빠져 있던 기한을 확인해 채움')
  await page.getByRole('button', { name: '수정 후 승인' }).click()
  await expect(page.locator('.badge')).toHaveText('수정 확정')

  // 장면 2 — 개인 메모의 선택 요약만 공유한다.
  await page.getByRole('button', { name: '내 기록 수집으로' }).click()
  await page.getByRole('button', { name: '빠른 메모' }).click()
  await expect(page.locator('.warn')).toContainText('공유해도 프로젝트에 공개되지 않습니다')
  await page.fill('input[aria-label="공유 제목"]', '대량 상담이력 다운로드 1차 오픈 제외')
  await page.fill('textarea[aria-label="공유 요약"]', '대량 상담이력 다운로드 기능은 1차 오픈 범위에서 제외하고 2차 개선으로 이관한다.')
  await page.fill('input[aria-label="공유 발생일"]', '2026-09-10')
  await page.getByRole('button', { name: '선택 요약 공유' }).click()
  await expect(page.locator('.shared')).toContainText('REC-105')
  await expect(page.locator('.shared')).toContainText('NOTE-001은 계속 비공개')

  await page.click('a[href="/records"]')
  await page.locator('input[aria-label="전체 선택"]').check()
  await page.getByRole('button', { name: /선택 승인/ }).click()
  await expect(page.locator('.state')).toContainText('1건 처리')

  // 장면 3 — 관리자가 교체를 요청하고, 패키지가 생성되면 충돌을 해소한다.
  await changeUser(page, '박지민')
  await page.getByRole('button', { name: '담당자 교체 요청' }).click()
  await page.selectOption('select[aria-label="기존 담당자"]', '1')
  await page.selectOption('select[aria-label="신규 담당자"]', '3')
  await page.fill('input[aria-label="교체 적용일"]', '2026-09-12')
  await page.fill('input[aria-label="기간 시작"]', '2026-06-14')
  await page.fill('input[aria-label="기간 종료"]', '2026-09-11')
  await page.getByRole('button', { name: '패키지 생성 요청' }).click()
  await expect(page).toHaveURL(/\/handovers\/1\/progress$/)
  await expect(page.locator('[role="status"]')).toContainText('열람 가능', { timeout: 15_000 })
  await page.locator('input[aria-label="REC-102 최종 근거로 선택"]').check()
  await expect(page.locator('.conflict__resolved')).toContainText('REC-102')

  // 장면 4~5 — 신규 담당자가 읽고, 필수 항목을 확인하고, 근거를 두고 질문한다.
  await changeUser(page, '이서연')
  await page.getByRole('button', { name: '인수인계 패키지 열기' }).click()
  await expect(page.locator('.menu__item').first()).toContainText('프로젝트 개요')
  const menu = page.locator('.menu__item')
  for (let i = 0; i < await menu.count(); i += 1) {
    await menu.nth(i).click()
    const pending = page.getByRole('button', { name: /이해했음$/ })
    for (let left = await pending.count(); left > 0; left -= 1) {
      await pending.first().click()
      await expect(pending).toHaveCount(left - 1)
    }
  }
  await page.fill('input[aria-label="질문"]', '최종 오픈일은 언제이고 왜 변경됐나요?')
  await page.getByRole('button', { name: '질문', exact: true }).click()
  await expect(page.locator('.qa__status').first()).toHaveText('근거 있음')
  await expect(page.locator('.cite')).toHaveCount(2)
  await expect(page.locator('.cite').nth(1)).toContainText('보조 표시')
  await page.fill('input[aria-label="질문"]', '2차 개선의 정확한 배포일은 언제인가요?')
  await page.getByRole('button', { name: '질문', exact: true }).click()
  await expect(page.locator('.qa__status').last()).toHaveText('확인 가능한 기록 없음')
  await expect(page.locator('.suggest')).toContainText('박지민 PL')

  // 장면 6 — 준비도 점검. 1차 4/5, 근거 기록을 다시 읽은 뒤 5/5.
  await page.getByRole('button', { name: '업무 준비도 확인' }).click()
  const answers = ['10월 22일', '9월 18일 통합 시연 버전 제공', 'CRM 테스트 계정 발급 지연', '대량 상담이력 다운로드', '모르겠음']
  for (const [i, value] of answers.entries()) await page.fill(`input[aria-label="${i + 1}번 답안"]`, value)
  await page.getByRole('button', { name: '제출', exact: true }).click()
  await expect(page.locator('.result__score')).toHaveText('4 / 5')
  await expect(page.locator('.question--wrong')).toContainText('근거 기록 REC-106')
  answers[4] = '대체 테스트 데이터 사용 여부'
  for (const [i, value] of answers.entries()) await page.fill(`input[aria-label="${i + 1}번 답안"]`, value)
  await page.getByRole('button', { name: '다시 제출' }).click()
  await expect(page.locator('.result__score')).toHaveText('5 / 5')

  // 장면 7 — 관리자가 완료를 승인한다.
  await changeUser(page, '박지민')
  await page.getByRole('button', { name: '인수인계 검토' }).click()
  await expect(page.locator('.score')).toContainText('5 / 5')
  await expect(page.locator('.blockers')).toHaveCount(0)
  await page.getByRole('button', { name: '인수인계 완료 승인' }).click()
  await expect(page.locator('.completed')).toContainText('인수인계가 완료됐습니다')
  await expect(page.locator('.follow')).toContainText('이서연')
})

test('거절은 숨긴 화면이 아니라 데이터 출처에서 온다', async ({ page }) => {
  await signIn(page, '김현우')
  await page.click('a[href="/handovers/new"]')
  await page.selectOption('select[aria-label="기존 담당자"]', '1')
  await page.selectOption('select[aria-label="신규 담당자"]', '3')
  await page.getByRole('button', { name: '패키지 생성 요청' }).click()
  await expect(page.locator('.state--denied')).toContainText('프로젝트 관리자만')
})
