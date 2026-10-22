import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRepository, provideRepository } from './api/repositoryProvider'
import { useSessionStore } from './stores/session'
import router from './router'
import App from './App.vue'
import './styles/base.css'

async function bootstrap(): Promise<void> {
  const app = createApp(App)
  const pinia = createPinia()
  app.use(pinia)
  const session = useSessionStore(pinia)
  // 어떤 401이든 화면의 세션을 끝낸다. 그러면 App.vue가 로그인 화면으로 돌려보낸다.
  provideRepository(await createRepository(() => session.expire()))
  app.use(router)
  app.mount('#app')
}

void bootstrap()
