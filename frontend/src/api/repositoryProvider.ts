import { createApiRepository, type BatonRepository } from './batonRepository'
import { HttpClient } from './http'
import { resolveDataMode, type DataMode } from './dataMode'

/**
 * 실행 중인 앱의 저장소 인스턴스 하나를 보관한다.
 *
 * demo 분기는 `import.meta.env.VITE_DATA_MODE`를 문자열 상수와 비교한다. Vite가 빌드 시점에 값을 치환하므로
 * `api` 빌드에서는 조건이 거짓으로 접히고 demo 시나리오가 산출물에 아예 들어가지 않는다. 실행 중에 모드를
 * 바꾸는 경로는 없다. API가 실패하면 실패로 남는다.
 */

let current: BatonRepository | null = null

export function currentDataMode(): DataMode {
  return resolveDataMode(import.meta.env.VITE_DATA_MODE)
}

export async function createRepository(onUnauthenticated: () => void): Promise<BatonRepository> {
  currentDataMode()
  if (import.meta.env.VITE_DATA_MODE === 'demo') {
    const { createDemoRepository } = await import('@/demo/demoRepository')
    return createDemoRepository()
  }
  return createApiRepository(new HttpClient({ onUnauthenticated }))
}

export function provideRepository(repository: BatonRepository): void {
  current = repository
}

export function useRepository(): BatonRepository {
  if (!current) throw new Error('Repository is not initialised; main.ts must provide it before mounting')
  return current
}
