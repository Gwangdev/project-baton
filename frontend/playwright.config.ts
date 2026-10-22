import { defineConfig } from '@playwright/test'

/**
 * 실제 백엔드 E2E (기본 `npm run test:e2e`).
 *
 * global setup이 일회용 pgvector 컨테이너와 `e2e` 프로필의 Spring 애플리케이션을 띄우고, 애플리케이션이 합성
 * fixture 프로젝트를 넣는다. 브라우저 경로에 테스트 더블은 없다. 앱은 운영용 `api` 빌드를 `vite preview`로
 * 띄우고 /api를 그 백엔드로 넘긴다. dev 서버는 필요할 때 의존성을 최적화하며 테스트 도중 페이지를 새로 고칠 수
 * 있기 때문이다. 자격증명은 환경변수로만 받는다. BATON_SEED_PASSWORD를 줄 수 있고, 없으면 setup이 이번 실행용
 * 무작위 값을 만들어 백엔드와 테스트에 함께 넘긴다.
 *
 * 인수인계 하나를 요청부터 승인까지 밟으므로 한 DB에서 순서대로 실행한다.
 */
const apiPort = process.env.BATON_E2E_API_PORT ?? '18080'
const webPort = process.env.BATON_E2E_WEB_PORT ?? '5174'

export default defineConfig({
  testDir: './tests/e2e/api',
  globalSetup: './tests/e2e/api/global-setup.ts',
  fullyParallel: false,
  workers: 1,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  use: {
    baseURL: `http://localhost:${webPort}`,
    viewport: { width: 1280, height: 800 },
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `npx vite build && npx vite preview --port ${webPort} --strictPort`,
    url: `http://localhost:${webPort}`,
    reuseExistingServer: false,
    timeout: 120_000,
    env: { VITE_DATA_MODE: 'api', BATON_API_PROXY_TARGET: `http://127.0.0.1:${apiPort}` },
  },
})
