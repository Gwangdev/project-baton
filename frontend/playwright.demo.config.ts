import { defineConfig } from '@playwright/test'

/**
 * 데모 모드 E2E (`npm run test:e2e:demo`). VITE_DATA_MODE=demo로 띄워 백엔드 없이 1단계 시나리오를 메모리에서
 * 돌린다. 기본 E2E가 메모리 데이터로 통과하는 일이 없도록 설정을 나눈다.
 */
export default defineConfig({
  testDir: './tests/e2e/demo',
  use: {
    baseURL: 'http://localhost:5173',
    viewport: { width: 1280, height: 800 },
  },
  webServer: {
    command: 'npx vite --port 5173 --strictPort',
    url: 'http://localhost:5173',
    reuseExistingServer: false,
    timeout: 60_000,
    env: { VITE_DATA_MODE: 'demo' },
  },
})
