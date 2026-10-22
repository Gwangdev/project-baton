import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { fallbackRoutes, routes } from './routes'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [...routes, ...fallbackRoutes],
})

/**
 * FR-01 — 세션 없이는 어떤 화면도 열지 않는다.
 *
 * 화면마다 검사하지 않고 여기서 한 번만 막는다. 화면에 흩어 두면 새 화면을 추가할 때 빠뜨린다. 세션 자체는 서버
 * 쿠키이고, 가드는 페이지를 열 때 한 번 `GET /me`를 묻는다. 서버에 닿지 못하면 호출마다 실패할 화면 대신 그
 * 사유를 담아 로그인 화면으로 보낸다.
 */
router.beforeEach(async (to) => {
  const session = useSessionStore()
  try {
    await session.restore()
  } catch {
    return to.name === 'login' ? true : { name: 'login', query: { unavailable: '1' } }
  }
  // 설정 코드 화면은 로그인 전에 쓰는 화면이라 세션이 없어도 연다.
  if (to.name === 'credentials') return true
  if (to.name === 'login') return session.isAuthenticated ? { name: 'dashboard' } : true
  if (!session.isAuthenticated) return { name: 'login', query: { redirect: to.fullPath } }
  return true
})

/** 로그인 뒤 이동할 곳은 이 앱 안의 경로만 받는다. 그 밖의 값은 open redirect가 된다. */
export function safeRedirect(value: unknown): string {
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') ? value : '/dashboard'
}

export default router
