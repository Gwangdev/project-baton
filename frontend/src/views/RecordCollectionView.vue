<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import ProjectFolderTab from '@/components/ProjectFolderTab.vue'
import QuickMemoTab from '@/components/QuickMemoTab.vue'
import { parseRouteId } from '@/api/ids'
import { useSessionStore } from '@/stores/session'

/**
 * S06 내 기록 수집.
 *
 * 두 탭은 「내가 만든 기록이 어떻게 프로젝트에 들어가는가」라는 같은 질문의 두 경로다.
 * 폴더는 정기 수집으로, 메모는 명시적으로 공유한 요약으로 들어간다.
 */
const TABS = [
  { id: 'folder', label: '프로젝트 폴더' },
  { id: 'memo', label: '빠른 메모' },
] as const

const session = useSessionStore()
const route = useRoute()
const active = ref<'folder' | 'memo'>('folder')
const projectId = computed(() => parseRouteId(route.params.projectId))
watch(projectId, (id) => { if (id !== null) session.selectProject(id) }, { immediate: true })
</script>

<template>
  <AppShell>
    <template #title><h1 class="title">내 기록 수집</h1></template>
    <nav class="tabs" aria-label="수집 방식">
      <button
        v-for="t in TABS"
        :key="t.id"
        class="tabs__tab"
        :class="{ 'tabs__tab--on': active === t.id }"
        type="button"
        @click="active = t.id"
      >{{ t.label }}</button>
    </nav>
    <p v-if="projectId === null" class="state state--empty">프로젝트를 찾을 수 없습니다.</p>
    <template v-else>
      <ProjectFolderTab v-if="active === 'folder'" :project-id="projectId" />
      <QuickMemoTab v-else :project-id="projectId" />
    </template>
  </AppShell>
</template>

<style scoped>
.title { margin: 0; font-size: 22px; }
.tabs { display: flex; border-bottom: 1px solid var(--line); margin-bottom: 14px; }
.tabs__tab { border: none; background: none; padding: 8px 14px; font-family: var(--font); font-size: 13px; color: var(--grey-dark); cursor: pointer; border-bottom: 2px solid transparent; }
.tabs__tab--on { color: var(--navy); font-weight: 600; border-bottom-color: var(--orange); }
</style>
