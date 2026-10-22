import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { provideRepository } from '@/api/repositoryProvider'
import type { BatonRepository } from '@/api/batonRepository'
import { DEMO_PASSWORDS } from '@/demo/demoCredentials'
import { createDemoRepository } from '@/demo/demoRepository'
import { fallbackRoutes, routes } from '@/router/routes'
import { useSessionStore } from '@/stores/session'

/**
 * 화면 테스트 도구. 화면은 제공된 저장소로만 데이터에 닿는다. 그래서 테스트는 메모리 데모 구현으로 동작을 보거나,
 * 그 메서드를 감시해 화면이 보내는 호출(version, cursor, 키)을 그대로 확인한다.
 */

export { DEMO_PASSWORDS }

export function demoRepository(): BatonRepository {
  return createDemoRepository({ delayMs: 0, generationMs: 0 })
}

/** 데모 저장소를 `employeeNo`로 로그인시켜 돌려준다. 세션 store는 건드리지 않는다. */
export async function signedIn(repository: BatonRepository, employeeNo: string): Promise<BatonRepository> {
  await repository.signIn(employeeNo, DEMO_PASSWORDS[employeeNo])
  return repository
}

export async function mountAt(component: unknown, path: string, repository: BatonRepository) {
  const pinia = createPinia()
  setActivePinia(pinia)
  provideRepository(repository)
  await useSessionStore().restore().catch(() => undefined)
  const router = createRouter({ history: createMemoryHistory(), routes: [...routes, ...fallbackRoutes] })
  await router.push(path)
  await router.isReady()
  const wrapper = mount(component as never, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}

export const button = (wrapper: ReturnType<typeof mount>, text: string) => {
  const found = wrapper.findAll('button').find((b) => b.text() === text)
  if (!found) throw new Error(`button "${text}" not found in: ${wrapper.findAll('button').map((b) => b.text()).join(' | ')}`)
  return found
}
