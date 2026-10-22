<script setup lang="ts">
import { computed, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { errorLine } from '@/api/problemText'
import { currentDataMode } from '@/api/repositoryProvider'
import { useSessionStore } from '@/stores/session'
import { ACCOUNT_ACTIVITY_LABEL, PROJECT_ROLE_LABEL, formatTimestamp } from './labels'

/**
 * 로그인 뒤 모든 화면의 공통 셸.
 *
 * 왼쪽 레일로 영역을 고르고, 상단바에는 로그인한 사람과 화면이 다루는 프로젝트를, 페이지 헤더에는 화면의 제목과
 * 동작을 둔다. 계정 표시는 `GET /me`에서 오며 Actor 전환은 없다. 권한은 서버 세션에서만 오므로 사용자를 바꾸려면
 * 로그아웃하고 다시 로그인한다.
 */
const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const isDemo = currentDataMode() === 'demo'

const projectId = computed(() => session.currentProjectId)
const rail = computed(() => [
  { to: '/dashboard', label: '대시보드', icon: '▦' },
  { to: '/records', label: '확인 대기', icon: '☰' },
  { to: projectId.value ? `/projects/${projectId.value}` : '/dashboard', label: '타임라인', icon: '◷' },
  { to: projectId.value ? `/projects/${projectId.value}/collection` : '/dashboard', label: '내 기록 수집', icon: '⊞' },
  { to: '/handovers/new', label: '담당자 교체', icon: '⇄' },
  { to: '/admin', label: '시스템 관리', icon: '⚙' },
])

const screenId = computed(() => (route.meta.screen as string) ?? '')
const roleLabel = computed(() => {
  const role = session.currentMembership?.projectRole
  return role ? PROJECT_ROLE_LABEL[role] : session.isSystemAdmin ? '시스템 관리자' : '배정된 프로젝트 없음'
})

/**
 * 프로젝트가 경로에 있는 화면은 같은 화면의 새 프로젝트 경로로 옮긴다(화면이 경로의 프로젝트를 선택한다).
 * 특정 기록·인수인계 화면은 이전 프로젝트의 것이므로 대시보드로 간다. 나머지 목록 화면은 App.vue가 다시 만든다.
 */
async function onProjectChange(event: Event) {
  const projectId = Number((event.target as HTMLSelectElement).value)
  if (route.params.projectId) {
    await router.push({ name: route.name!, params: { ...route.params, projectId: String(projectId) } })
    return
  }
  session.selectProject(projectId)
  if (route.params.recordId || route.params.handoverId) await router.push('/dashboard')
}

const signOutError = ref('')

async function onSignOut() {
  signOutError.value = ''
  try {
    await session.signOut()
    await router.push({ name: 'login' })
  } catch (e) {
    signOutError.value = `로그아웃하지 못했습니다. ${errorLine(e)}`
  }
}
</script>

<template>
  <div class="shell">
    <nav class="rail" aria-label="주요 영역">
      <RouterLink
        v-for="item in rail"
        :key="item.label"
        class="rail__item"
        :class="{ 'rail__item--active': route.path === item.to }"
        :to="item.to"
        :title="item.label"
      >
        <span aria-hidden="true">{{ item.icon }}</span>
        <span class="rail__label">{{ item.label }}</span>
      </RouterLink>
    </nav>

    <div class="main">
      <header class="topbar">
        <span class="topbar__brand">Project Baton</span>
        <select
          v-if="session.memberships.length > 1"
          class="topbar__project"
          :value="projectId ?? undefined"
          aria-label="작업 프로젝트"
          @change="onProjectChange"
        >
          <option v-for="m in session.memberships" :key="m.projectId" :value="m.projectId">{{ m.projectName }}</option>
        </select>
        <div class="account">
          <div class="account__current">
            <span class="account__name">{{ session.user?.name }} {{ session.user?.title }}</span>
            <span class="account__role">{{ roleLabel }}</span>
          </div>
          <details v-if="session.accountActivity.length" class="activity">
            <summary class="activity__title">내 계정 활동</summary>
            <div class="activity__panel">
              <ul class="activity__list" aria-label="내 계정 활동">
                <li v-for="(item, index) in session.accountActivity" :key="index" class="activity__item">
                  <span class="activity__what">{{ ACCOUNT_ACTIVITY_LABEL[item.action] }}</span>
                  <span class="activity__when">{{ formatTimestamp(item.occurredAt) }}</span>
                  <span class="activity__where">{{ item.remoteAddress ?? '주소 기록 없음' }}</span>
                </li>
              </ul>
              <p class="activity__hint">모르는 주소나 시각이 있으면 관리자에게 알려 주세요.</p>
            </div>
          </details>
          <button class="account__out" type="button" @click="onSignOut">로그아웃</button>
        </div>
      </header>
      <p v-if="signOutError" class="state state--failed signout-error" role="alert">{{ signOutError }}</p>

      <div class="pagehead">
        <div>
          <p class="pagehead__meta">
            <span class="pagehead__screen">{{ screenId }}</span>
            {{ session.currentMembership?.projectName ?? '' }}
          </p>
          <slot name="title" />
        </div>
        <div class="pagehead__actions"><slot name="actions" /></div>
      </div>

      <div class="body">
        <section class="body__main"><slot /></section>
        <aside v-if="$slots.aside" class="body__aside"><slot name="aside" /></aside>
      </div>

      <footer v-if="isDemo" class="footnote">본 화면의 데이터는 시연을 위한 가상 정보입니다.</footer>
    </div>
  </div>
</template>

<style scoped>
.shell { display: flex; min-height: 100vh; }

/* 아이콘 레일 — 폭을 좁게 유지해 본문 폭을 1280px 안에서 지킨다. */
.rail {
  width: 64px;
  flex: 0 0 64px;
  background: var(--navy);
  padding: 12px 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.rail__item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 10px 4px;
  color: var(--grey);
  text-decoration: none;
  font-size: 16px;
}
.rail__item--active { color: var(--orange); background: var(--navy-soft); }
.rail__label { font-size: 9px; letter-spacing: -0.2px; }

.main { flex: 1; min-width: 0; display: flex; flex-direction: column; }

.topbar {
  display: flex;
  align-items: center;
  gap: 16px;
  height: 52px;
  padding: 0 20px;
  background: var(--navy);
}
.topbar__brand { font-weight: 700; color: var(--white); font-size: 14px; }
.topbar__project {
  height: 28px;
  max-width: 320px;
  border: 1px solid var(--navy-soft);
  border-radius: 6px;
  background: var(--navy-soft);
  color: var(--white);
  font-family: var(--font);
  font-size: 12px;
}
.account { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.account__current { display: flex; flex-direction: column; align-items: flex-end; line-height: 1.25; }
.account__name { font-size: 13px; font-weight: 600; color: var(--white); }
.account__role { font-size: 11px; color: var(--grey); }
.activity { position: relative; font-size: 12px; color: var(--grey); }
.activity__title { cursor: pointer; list-style: none; }
.activity__panel {
  position: absolute; right: 0; top: 24px; z-index: 5; min-width: 320px; padding: 8px 12px;
  background: var(--white); color: var(--ink, #1b2430); border: 1px solid var(--line, #d6dbe3);
  border-radius: 6px; box-shadow: 0 6px 18px rgba(0, 0, 0, 0.15);
}
.activity__list { margin: 0; padding: 0; list-style: none; }
.activity__item { display: grid; grid-template-columns: 1.4fr 1fr 1fr; gap: 8px; padding: 4px 0; }
.activity__hint { margin: 6px 0 0; font-size: 11px; color: var(--grey); }
.account__out {
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--navy-soft);
  border-radius: 6px;
  background: transparent;
  color: var(--grey);
  font-family: var(--font);
  font-size: 12px;
  cursor: pointer;
}

.pagehead {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px 12px;
  background: var(--white);
  border-bottom: 1px solid var(--line);
}
.pagehead__meta { margin: 0 0 4px; font-size: 12px; color: var(--grey-dark); }
.pagehead__screen { color: var(--orange); font-weight: 700; margin-right: 6px; }
.pagehead__actions { display: flex; gap: 8px; }

.body { flex: 1; display: flex; gap: 0; align-items: stretch; }
.body__main { flex: 1; min-width: 0; padding: 20px; }
/* 우측 패널 — 출처와 신뢰 상태처럼 본문 판단에 필요한 근거를 붙여 둔다. */
.body__aside {
  width: 300px;
  flex: 0 0 300px;
  padding: 20px;
  background: var(--white);
  border-left: 1px solid var(--line);
}

.signout-error { margin: 8px 20px 0; }
.footnote { padding: 10px 20px; color: var(--grey); font-size: 11px; }
</style>
