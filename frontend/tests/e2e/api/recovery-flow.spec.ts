import { expect, test, type Page } from '@playwright/test'
import { currentUser, seoulToday, signIn, signOut } from './support.js'

/**
 * 복구 경로를 실제 Spring + PostgreSQL + stub AI로 밟는다.
 *
 * 백엔드 `e2e` 프로필의 두 번째 fixture(E2eRecoveryFixtureSeeder)에서 시작한다. 기록 REC-201~205는 공용 메일에서 온
 * 미확인 초안이라, 처음 요청한 패키지는 근거 부족으로 실패한다. 초안을 확인한 뒤 같은 범위로 재시도하면 같은 snapshot이라
 * 다시 실패하고, 최신 기록으로 재생성해야 열린다. 이어서 관리자의 보완 요청을 신규 담당자가 패키지 화면에서 읽고 재제출해
 * 열람 가능으로 되돌린다. 기존 담당자의 개인 폴더에는 처리에 실패한 파일이 하나 있어 직접 쓴 초안, 다시 처리, 연결
 * 해제·재연결을 확인한다. 시스템 관리자가 관리 쓰기 명령을 차례로 실행하고, 끝으로 PM이 확정 기록 사이의 참조 관계를
 * 등록한다. 모든 상태 변화는 화면의 실제 명령이고, 첫 fixture 흐름(handover-flow.spec.ts)과 데이터를 나누지 않는다.
 */

const MANAGER = 'A-20101'
const OUTGOING = 'A-20102'
const INCOMING = 'A-20103'
const ADMIN = 'A-10002'
const DRAFTS = ['REC-201', 'REC-202', 'REC-203', 'REC-204', 'REC-205']
const WINDOW = { from: '2026-09-21', to: '2026-09-27' }
const FAILED_FILE = '배송관제_인터페이스_정의서.docx'

test.describe.configure({ mode: 'serial' })

const ids = { project: 0, outgoing: 0, incoming: 0, handover: 0 }

async function handover(page: Page) {
  return (await page.request.get(`/api/v1/handovers/${ids.handover}`)).json() as Promise<{ status: string; generation: number; version: number }>
}

test('복구 fixture는 미확인 초안 다섯 건과 처리 실패 파일 하나에서 시작한다', async ({ page }) => {
  await signIn(page, OUTGOING)
  const me = await currentUser(page)
  ids.outgoing = me.user.id
  ids.project = me.memberships[0].projectId
  const records = await (await page.request.get(`/api/v1/projects/${ids.project}/records?limit=100`)).json()
  expect(records.items.map((r: { code: string; trustStatus: string }) => `${r.code}:${r.trustStatus}`).sort())
    .toEqual(DRAFTS.map((code) => `${code}:AUTO_DRAFT`))
  await signOut(page)

  await signIn(page, INCOMING)
  ids.incoming = (await currentUser(page)).user.id
})

test('확정 근거가 없는 기간의 패키지는 근거 부족으로 실패한다', async ({ page }) => {
  await signIn(page, MANAGER)
  await page.getByRole('button', { name: '담당자 교체 요청' }).click()
  await page.selectOption('select[aria-label="기존 담당자"]', String(ids.outgoing))
  await page.selectOption('select[aria-label="신규 담당자"]', String(ids.incoming))
  await page.fill('input[aria-label="교체 적용일"]', seoulToday())
  await page.fill('input[aria-label="기간 시작"]', WINDOW.from)
  await page.fill('input[aria-label="기간 종료"]', WINDOW.to)
  await page.getByRole('button', { name: '패키지 생성 요청' }).click()
  await expect(page).toHaveURL(/\/handovers\/\d+\/progress$/)
  ids.handover = Number(page.url().match(/handovers\/(\d+)/)![1])

  await expect(page.locator('[role="status"]')).toContainText('생성 실패', { timeout: 60_000 })
  await expect(page.locator('.state--failed')).toContainText('확정된 근거가 부족해')
})

test('기존 담당자가 확인 대기함에서 초안 다섯 건을 한 번에 승인한다', async ({ page }) => {
  await signIn(page, OUTGOING)
  await page.click('a[href="/records"]')
  await expect(page.locator('tbody tr')).toHaveCount(DRAFTS.length)
  await page.locator('input[aria-label="전체 선택"]').check()
  await page.getByRole('button', { name: `선택 승인 (${DRAFTS.length})` }).click()
  await expect(page.locator('tbody tr')).toHaveCount(0)
  const records = await (await page.request.get(`/api/v1/projects/${ids.project}/records?limit=100`)).json()
  expect(records.items.every((r: { trustStatus: string }) => r.trustStatus === 'CONFIRMED')).toBe(true)
})

test('같은 범위 재시도는 같은 snapshot이라 다시 실패하고, 최신 기록으로 재생성해야 패키지가 열린다', async ({ page }) => {
  await signIn(page, MANAGER)
  await page.goto(`/handovers/${ids.handover}/progress`)
  const before = await handover(page)
  // 재시도는 일시적 실패를 위한 명령이다. 요청 때 만든 1세대 snapshot을 다시 쓰므로 그 뒤에 확인한 기록은 들어가지 않는다.
  await page.getByRole('button', { name: '같은 범위로 재시도' }).click()
  await expect.poll(async () => (await handover(page)).version, { timeout: 60_000 }).toBeGreaterThan(before.version)
  await expect.poll(async () => (await handover(page)).status, { timeout: 60_000 }).toBe('FAILED')
  expect((await handover(page)).generation).toBe(1)

  await page.reload()
  await page.getByRole('button', { name: '최신 기록으로 재생성' }).click()
  await expect(page.locator('.sub')).toContainText('2세대', { timeout: 60_000 })
  await expect(page.locator('[role="status"]')).toContainText('열람 가능', { timeout: 60_000 })
  expect(await handover(page)).toMatchObject({ status: 'READY', generation: 2 })

  await page.getByRole('button', { name: '최신 기록으로 재생성' }).click()
  await expect(page.locator('.sub')).toContainText('3세대', { timeout: 60_000 })
  await expect(page.locator('[role="status"]')).toContainText('열람 가능', { timeout: 60_000 })
  expect(await handover(page)).toMatchObject({ status: 'READY', generation: 3 })
})

test('관리자가 보완을 요청하면 신규 담당자의 재제출로 열람 가능 상태로 돌아간다', async ({ page }) => {
  await signIn(page, MANAGER)
  await page.goto(`/handovers/${ids.handover}/review`)
  await page.fill('textarea[aria-label="보완 요청 사유"]', '야간 배차 규칙의 결정 기한을 다시 확인해 주십시오.')
  await page.getByRole('button', { name: '보완 요청' }).click()
  await expect.poll(async () => (await handover(page)).status).toBe('REVIEW_REQUESTED')
  await signOut(page)

  await signIn(page, INCOMING)
  // 보완 요청 사유는 신규 담당자의 패키지 화면에 보인다. 예전에는 저장만 되고 API로 나가지 않았다.
  await page.goto(`/handovers/${ids.handover}`)
  await expect(page.locator('[role="note"]')).toContainText('야간 배차 규칙의 결정 기한을 다시 확인해 주십시오.')
  await page.goto(`/handovers/${ids.handover}/readiness`)
  const assessment = await (await page.request.get(`/api/v1/handovers/${ids.handover}/assessment`)).json()
  for (const q of assessment.questions as { questionNo: number; questionType: string; choices: { text: string }[] | null }[]) {
    if (q.questionType === 'SHORT_ANSWER') await page.fill(`input[aria-label="${q.questionNo}번 답안"]`, '2026-11-12')
    else await page.locator(`input[aria-label="${q.questionNo}번 ${q.choices![0].text}"]`).check()
  }
  await page.getByRole('button', { name: '제출', exact: true }).click()
  await expect(page.locator('.result__score')).toBeVisible()
  expect((await handover(page)).status).toBe('READY')
})

test('기존 담당자가 처리에 실패한 폴더 파일로 초안을 직접 써서 확인 대기로 보낸다', async ({ page }) => {
  await signIn(page, OUTGOING)
  await page.goto(`/projects/${ids.project}/collection`)
  const row = page.locator('table.files tbody tr', { hasText: FAILED_FILE })
  await expect(row).toContainText('처리 실패')
  await row.getByRole('button', { name: `${FAILED_FILE} 직접 작성` }).click()
  await row.locator('input[aria-label="초안 제목"]').fill('위치 전송 주기 정의')
  await row.locator('textarea[aria-label="초안 요약"]').fill('관제 서버와 기사 앱은 30초마다 위치를 주고받는다.')
  await row.locator('textarea[aria-label="초안 원문 발췌"]').fill('관제 서버와 기사 앱 사이의 위치 전송 주기 정의.')
  await row.locator('input[aria-label="초안 발생일"]').fill('2026-09-26')
  const created = page.waitForResponse((r) => r.url().includes('/files/') && r.url().endsWith('/records') && r.request().method() === 'POST')
  await row.getByRole('button', { name: '초안 저장' }).click()
  expect((await created).status()).toBe(201)
  await expect(page).toHaveURL(/\/records\/\d+$/)
  await expect(page.locator('.badge')).toContainText('확인 대기')
  await expect(page.locator('input[aria-label="제목"]')).toHaveValue('위치 전송 주기 정의')
})

test('기존 담당자가 처리에 실패한 폴더 파일을 다시 처리하고, 폴더 연결을 해제했다가 다시 연결한다', async ({ page }) => {
  await signIn(page, OUTGOING)
  await page.goto(`/projects/${ids.project}/collection`)
  const row = page.locator('table.files tbody tr', { hasText: FAILED_FILE })
  await expect(row).toContainText('처리 실패')
  // 명령 응답을 기다린다. 화면이 파일 목록을 다 그리기 전의 클릭은 요청을 보내지 않을 수 있다.
  const attempt = page.waitForResponse((r) => r.url().includes('/processing-attempts') && r.request().method() === 'POST')
  await row.getByRole('button', { name: '다시 처리' }).click()
  expect((await attempt).status()).toBe(202)
  // 워커가 원본을 다시 구조화한다. stub은 기록된 출력이 없는 문서에서 초안을 만들지 않으므로 처리 완료, 기록 없음이다.
  await expect(async () => {
    await page.reload()
    await expect(page.locator('table.files tbody tr', { hasText: FAILED_FILE })).toContainText('처리 완료', { timeout: 1_000 })
  }).toPass({ timeout: 60_000 })

  await page.getByRole('button', { name: '연결 해제' }).click()
  await expect(page.locator('.hint').first()).toContainText('연결이 해제되어도 이미 수집된 파일과 기록은 남습니다')
  await page.getByRole('button', { name: '다시 연결' }).click()
  await expect(page.getByRole('button', { name: '연결 해제' })).toBeVisible()
})

test('시스템 관리자가 조직·사용자·프로젝트·배정·연동을 만들고 바꾸고 지운다', async ({ page }) => {
  const org = 'E2E 임시 조직'
  const person = 'E2E 임시사용자'
  const project = 'E2E 임시 프로젝트'
  await signIn(page, ADMIN)
  await page.goto('/admin')
  const tab = (name: string) => page.locator('nav[aria-label="관리 대상"]').getByRole('button', { name, exact: true }).click()

  await tab('조직')
  await page.fill('input[aria-label="새 조직 이름"]', org)
  await page.locator('form.create').getByRole('button', { name: '등록' }).click()
  await page.getByRole('button', { name: `${org} 비활성화` }).click()
  await page.getByRole('button', { name: `${org} 활성화` }).click()

  await tab('사용자')
  await page.selectOption('select[aria-label="새 사용자 조직"]', { label: org })
  await page.fill('input[aria-label="새 사용자 사내 계정"]', 'E-99001')
  await page.fill('input[aria-label="새 사용자 이름"]', person)
  await page.fill('input[aria-label="새 사용자 직함"]', '매니저')
  await page.fill('input[aria-label="새 사용자 이메일"]', 'e-99001@example.test')
  await page.locator('form.create').getByRole('button', { name: '등록' }).click()
  await expect(page.getByRole('button', { name: `${person} 삭제` })).toBeVisible()

  await tab('프로젝트')
  await page.fill('input[aria-label="새 프로젝트 코드"]', 'E2E-TMP-01')
  await page.fill('input[aria-label="새 프로젝트 이름"]', project)
  await page.fill('input[aria-label="수행사"]', 'A테크서비스')
  await page.fill('input[aria-label="고객사"]', 'E2E 고객사')
  await page.fill('input[aria-label="시작일"]', '2026-10-01')
  await page.fill('input[aria-label="종료일"]', '2026-12-31')
  await page.fill('input[aria-label="단계"]', '착수')
  await page.locator('form.create').getByRole('button', { name: '등록' }).click()
  await expect(page.getByRole('button', { name: `${project} 삭제` })).toBeVisible()

  await tab('구성원 배정')
  await page.selectOption('select[aria-label="배정 프로젝트"]', { label: project })
  await page.selectOption('select[aria-label="배정할 사용자"]', { label: `${person} 매니저 (E-99001)` })
  await page.fill('input[aria-label="배정일"]', seoulToday())
  await page.getByRole('button', { name: '배정', exact: true }).click()
  await page.getByRole('button', { name: `${person} 배정 종료` }).click()
  await page.getByRole('button', { name: `${person} 배정 삭제` }).click()
  await expect(page.getByRole('button', { name: `${person} 배정 삭제` })).toHaveCount(0)

  // 배정 이력이 지워졌으니 참조가 없는 사용자·프로젝트·조직은 삭제할 수 있다 (잘못 만든 항목의 삭제).
  await tab('사용자')
  await page.getByRole('button', { name: `${person} 삭제` }).click()
  await expect(page.getByRole('button', { name: `${person} 삭제` })).toHaveCount(0)
  await tab('프로젝트')
  await page.getByRole('button', { name: `${project} 삭제` }).click()
  await expect(page.getByRole('button', { name: `${project} 삭제` })).toHaveCount(0)
  await tab('조직')
  await page.getByRole('button', { name: `${org} 삭제` }).click()
  await expect(page.getByRole('button', { name: `${org} 삭제` })).toHaveCount(0)

  // 연동 탭은 관리자가 지금 고른 프로젝트(첫 fixture 프로젝트)의 연동을 보여준다.
  await tab('연동 설정')
  const mail = page.locator('tbody tr', { hasText: '사내 메일 — 고객지원사업부' })
  await mail.getByRole('button', { name: '일시 중지' }).click()
  await expect(mail).toContainText('일시 중지')
  await mail.getByRole('button', { name: '재개' }).click()
  await expect(mail.getByRole('button', { name: '일시 중지' })).toBeVisible()
})

test('관리자가 발급한 설정 코드로 새 사용자가 비밀번호를 정하고 로그인한다', async ({ page }) => {
  const person = 'E2E 코드사용자'
  const employeeNo = 'E-99002'
  // 비밀번호는 실행마다 새로 만든다. 저장소에는 어떤 비밀번호도 적지 않는다.
  const chosen = `e2e-${crypto.randomUUID()}`
  await signIn(page, ADMIN)
  await page.goto('/admin')
  await page.locator('nav[aria-label="관리 대상"]').getByRole('button', { name: '사용자', exact: true }).click()
  await page.selectOption('select[aria-label="새 사용자 조직"]', { index: 1 })
  await page.fill('input[aria-label="새 사용자 사내 계정"]', employeeNo)
  await page.fill('input[aria-label="새 사용자 이름"]', person)
  await page.fill('input[aria-label="새 사용자 직함"]', '매니저')
  await page.fill('input[aria-label="새 사용자 이메일"]', 'e-99002@example.test')
  await page.locator('form.create').getByRole('button', { name: '등록' }).click()
  await expect(page.getByRole('button', { name: `${person} 삭제` })).toBeVisible()
  await expect(page.locator('tbody tr', { hasText: person })).toContainText('자격증명 준비 전')

  await page.getByRole('button', { name: `${person} 설정 코드 발급` }).click()
  const code = (await page.locator('[data-testid="credential-code"]').innerText()).trim()
  expect(code).toHaveLength(43)
  await page.getByRole('button', { name: '닫기' }).click()
  await expect(page.locator('[data-testid="credential-code"]')).toHaveCount(0)
  await signOut(page)

  // 코드를 모르는 사람은 정할 수 없고, 아는 사람은 한 번만 정할 수 있다.
  await page.goto('/credentials')
  await page.fill('input[aria-label="사내 계정"]', employeeNo)
  await page.fill('input[aria-label="설정 코드"]', 'x'.repeat(43))
  await page.fill('input[aria-label="새 비밀번호"]', chosen)
  await page.fill('input[aria-label="새 비밀번호 확인"]', chosen)
  await page.getByRole('button', { name: '비밀번호 정하기' }).click()
  await expect(page.getByRole('alert')).toContainText('설정 코드')
  await page.fill('input[aria-label="설정 코드"]', code)
  await page.fill('input[aria-label="새 비밀번호"]', chosen)
  await page.fill('input[aria-label="새 비밀번호 확인"]', chosen)
  await page.getByRole('button', { name: '비밀번호 정하기' }).click()
  await expect(page.getByRole('status')).toContainText('비밀번호를 정했습니다')

  await page.goto('/login')
  await page.fill('input[aria-label="사내 계정"]', employeeNo)
  await page.fill('input[aria-label="비밀번호"]', chosen)
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
  // 본인 계정에 일어난 일(발급, 설정, 방금 한 로그인)을 본인이 볼 수 있다.
  await page.locator('summary', { hasText: '내 계정 활동' }).click()
  const activity = page.getByRole('list', { name: '내 계정 활동' })
  await expect(activity).toContainText('로그인')
  await expect(activity).toContainText('비밀번호 설정')
  await expect(activity).toContainText('설정 코드 발급(관리자)')
  await signOut(page)

  // 한 번이라도 로그인한 계정은 감사 행의 행위자라 지우지 않는다. 지우면 그 감사 행이 가리킬 사람이 사라진다.
  // 관리자는 삭제 대신 비활성화로 안내받는다.
  await signIn(page, ADMIN)
  await page.goto('/admin')
  await page.locator('nav[aria-label="관리 대상"]').getByRole('button', { name: '사용자', exact: true }).click()
  await page.getByRole('button', { name: `${person} 삭제` }).click()
  await expect(page.locator('.state--failed')).toContainText('audit_events')
  await expect(page.locator('.state--failed')).toContainText('비활성화')
  await expect(page.getByRole('button', { name: `${person} 삭제` })).toHaveCount(1)
})

test('PM이 확정된 두 기록의 참조 관계를 등록하면 기록 상세에 나타난다', async ({ page }) => {
  await signIn(page, MANAGER)
  const records = await (await page.request.get(`/api/v1/projects/${ids.project}/records?limit=100`)).json()
  const id = (code: string) => (records.items as { id: number; code: string }[]).find((r) => r.code === code)!.id
  await page.goto(`/records/${id('REC-203')}`)
  await page.selectOption('select[aria-label="관계 종류"]', 'REFERENCES')
  await page.selectOption('select[aria-label="관계 대상 기록"]', String(id('REC-201')))
  const created = page.waitForResponse((r) => r.url().endsWith('/record-relations') && r.request().method() === 'POST')
  await page.locator('form.relate').getByRole('button', { name: '등록' }).click()
  expect((await created).status()).toBe(201)
  await expect(page.locator('.revisions').first()).toContainText('REC-201')
})

test('PM이 잘못 등록한 대체 관계를 충돌 화면에서 철회하고, 다시 만든 패키지에는 그 쌍이 없다', async ({ page }) => {
  // 대체된 기록은 평가 근거에서 빠지므로, 근거 다섯 건을 지키려고 직접 쓴 초안을 먼저 확정한다.
  await signIn(page, OUTGOING)
  const drafts = await (await page.request.get(`/api/v1/projects/${ids.project}/records?trustStatus=AUTO_DRAFT&limit=100`)).json()
  const manual = (drafts.items as { id: number; title: string }[]).find((r) => r.title === '위치 전송 주기 정의')!
  await page.goto(`/records/${manual.id}`)
  await page.getByRole('button', { name: '승인', exact: true }).click()
  await expect(page.locator('.badge')).not.toContainText('확인 대기')
  await signOut(page)

  await signIn(page, MANAGER)
  const records = await (await page.request.get(`/api/v1/projects/${ids.project}/records?limit=100`)).json()
  const id = (code: string) => (records.items as { id: number; code: string }[]).find((r) => r.code === code)!.id
  await page.goto(`/records/${id('REC-204')}`)
  await page.selectOption('select[aria-label="관계 종류"]', 'SUPERSEDES')
  await page.selectOption('select[aria-label="관계 대상 기록"]', String(id('REC-202')))
  const created = page.waitForResponse((r) => r.url().endsWith('/record-relations') && r.request().method() === 'POST')
  await page.locator('form.relate').getByRole('button', { name: '등록' }).click()
  expect((await created).status()).toBe(201)

  await page.goto(`/handovers/${ids.handover}/progress`)
  const before = (await handover(page)).generation
  await page.getByRole('button', { name: '최신 기록으로 재생성' }).click()
  await expect(page.locator('.sub')).toContainText(`${before + 1}세대`, { timeout: 60_000 })
  await expect(page.locator('[role="status"]')).toContainText('열람 가능', { timeout: 60_000 })
  // 실제 서버의 충돌 항목 ID는 관계 ID와 다르다. 화면은 후보 기록 쌍으로 짝을 찾아 대체된 옛 기록을 막는다.
  await expect(page.locator('input[aria-label="REC-202 최종 근거로 선택"]')).toBeDisabled()
  await expect(page.locator('input[aria-label="REC-204 최종 근거로 선택"]')).toBeEnabled()

  await page.getByRole('button', { name: '관계 철회' }).click()
  await page.fill('input[aria-label="철회 사유"]', '위치 전송 주기 정의는 기존 기록을 대체하지 않는다.')
  const withdrawn = page.waitForResponse((r) => r.url().endsWith('/withdrawal') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '철회 확정' }).click()
  expect((await withdrawn).status()).toBe(200)
  await expect(page.locator('.conflicts')).toContainText('철회된 관계')
  const blocked = await (await page.request.get(`/api/v1/handovers/${ids.handover}`)).json()
  expect(blocked.approvalBlockers).toContain('RELATIONS_WITHDRAWN')
  expect(blocked.approvalBlockers).not.toContain('CONFLICTS_UNRESOLVED')

  await page.getByRole('button', { name: '최신 기록으로 재생성' }).click()
  await expect(page.locator('.sub')).toContainText(`${before + 2}세대`, { timeout: 60_000 })
  await expect(page.locator('[role="status"]')).toContainText('열람 가능', { timeout: 60_000 })
  const rebuilt = await (await page.request.get(`/api/v1/handovers/${ids.handover}`)).json()
  expect(rebuilt.conflicts).toEqual([])
  expect(rebuilt.approvalBlockers).not.toContain('RELATIONS_WITHDRAWN')
})
