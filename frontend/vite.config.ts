/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 브라우저는 한 출처와만 통신한다(README §3). 개발 중에는 Vite가 /api를 Spring 백엔드로 넘겨, 운영의 reverse
// proxy 뒤와 똑같이 세션 쿠키와 CSRF 토큰이 같은 출처에 머문다.
const apiTarget = process.env.BATON_API_PROXY_TARGET ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    proxy: { '/api': { target: apiTarget } },
  },
  test: {
    // 라우터와 컴포넌트 테스트가 document를 필요로 한다.
    environment: 'jsdom',
    include: ['tests/unit/**/*.spec.ts'],
  },
})
