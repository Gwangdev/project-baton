import { expect, type Page } from '@playwright/test'

/** 실제 백엔드 E2E의 공통 동작. 비밀번호는 global setup이 내보낸 환경변수에서만 읽는다. */

export const seedPassword = () => {
  const value = process.env.BATON_SEED_PASSWORD
  if (!value) throw new Error('BATON_SEED_PASSWORD is not set; global setup exports it')
  return value
}

export const seoulToday = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date())

export async function signIn(page: Page, employeeNo: string) {
  await page.goto('/login')
  await page.fill('input[aria-label="사내 계정"]', employeeNo)
  await page.fill('input[aria-label="비밀번호"]', seedPassword())
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page).toHaveURL(/\/dashboard$/)
}

/** 서버가 세션을 끝낼 때까지 기다린다. 먼저 다른 곳으로 이동하면 로그아웃 요청이 취소된다. */
export async function signOut(page: Page) {
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/login/)
}

export async function currentUser(page: Page) {
  const response = await page.request.get('/api/v1/me')
  expect(response.ok()).toBe(true)
  return response.json() as Promise<{ user: { id: number }; memberships: { projectId: number }[] }>
}
