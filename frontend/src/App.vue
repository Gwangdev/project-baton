<script setup lang="ts">
import { watch } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'

/**
 * 화면을 연 채로 세션이 끝나면(로그아웃, 또는 어떤 호출이든 401) 그 화면을 바로 떠난다.
 * 머물면 서버가 더는 인정하지 않는 세션의 데이터를 계속 보여주게 된다.
 */
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

watch(
  () => session.isAuthenticated,
  (signedIn) => {
    if (signedIn || !session.restored || route.name === 'login') return
    const query = session.endReason === 'expired' ? { redirect: route.fullPath, expired: '1' } : {}
    void router.replace({ name: 'login', query })
  },
)
</script>

<template>
  <!-- 목록 화면은 열릴 때 현재 프로젝트를 읽는다. 프로젝트가 바뀌면 화면을 다시 만들어 새 프로젝트로 읽게 한다. -->
  <RouterView :key="session.currentProjectId ?? 0" />
</template>
