<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import { HANDOVER_STATUS_LABEL, HEALTH_LABEL, PROJECT_ROLE_LABEL, PROJECT_STATUS_LABEL, formatTimestamp } from '@/components/labels'
import { errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import { useSessionStore } from '@/stores/session'
import type { DashboardSummary, Handover } from '@/api/types'

/**
 * S02 프로젝트 대시보드.
 *
 * 모든 사용자가 여기서 시작한다(D5). 숫자는 서버가 권한으로 걸러 센 값(`GET /dashboard`)이라, 이 화면에 역할
 * 분기 없이도 사람마다 다른 숫자가 보인다. 다음 행동 안내는 프로젝트 역할로 갈 화면만 가리킨다. 그 행동이 허용되는지는
 * 여전히 해당 화면에서 서버가 판단한다.
 */
const session = useSessionStore()
const repository = useRepository()
const router = useRouter()

const summary = ref<DashboardSummary | null>(null)
const handover = ref<Handover | null>(null)
const error = ref('')

async function load() {
  error.value = ''
  try {
    const projectId = session.currentProjectId
    const [dashboard, handoverPage] = await Promise.all([
      repository.getDashboard(),
      projectId ? repository.listHandovers(projectId) : Promise.resolve(null),
    ])
    summary.value = dashboard
    const handovers = handoverPage?.items ?? []
    handover.value = handovers.find((h) => h.status !== 'COMPLETED') ?? handovers[0] ?? null
  } catch (e) {
    error.value = errorLine(e)
  }
}
onMounted(load)

const role = computed(() => session.currentMembership?.projectRole ?? null)
const openRiskCount = computed(() => summary.value?.projects.reduce((n, p) => n + p.openRiskCount, 0) ?? 0)
const connections = computed(() => summary.value?.connectionHealth ?? [])
const showsEmptyHandoverState = computed(() => role.value === 'INCOMING_OWNER' && handover.value === null)

const nextAction = computed(() => {
  const h = handover.value
  const pending = summary.value?.pendingRecordCount ?? 0
  if (role.value === 'OUTGOING_OWNER') {
    return pending > 0
      ? { label: `확인 대기 ${pending}건 처리`, to: '/records' }
      : { label: '내 기록 수집 확인', to: `/projects/${session.currentProjectId}/collection` }
  }
  if (role.value === 'PROJECT_MANAGER') {
    if (!h || h.status === 'COMPLETED') return { label: '담당자 교체 요청', to: '/handovers/new' }
    if (h.status === 'READY' || h.status === 'REVIEW_REQUESTED') return { label: '인수인계 검토', to: `/handovers/${h.id}/review` }
    return { label: '패키지 생성 상태 확인', to: `/handovers/${h.id}/progress` }
  }
  if (role.value === 'INCOMING_OWNER') return h ? { label: '인수인계 패키지 열기', to: `/handovers/${h.id}` } : null
  return session.isSystemAdmin ? { label: '시스템 관리로 이동', to: '/admin' } : null
})

function openProject(projectId: number) {
  session.selectProject(projectId)
  router.push(`/projects/${projectId}`)
}
</script>

<template>
  <AppShell>
    <template #title>
      <h1 class="title">프로젝트 대시보드</h1>
      <p class="sub">{{ session.user?.name }} {{ session.user?.title }}<template v-if="role"> · {{ PROJECT_ROLE_LABEL[role] }}</template></p>
    </template>
    <template #actions>
      <button v-if="nextAction" class="btn btn--primary" type="button" @click="router.push(nextAction.to)">
        {{ nextAction.label }}
      </button>
    </template>

    <p v-if="error" class="state state--failed">{{ error }}</p>

    <template v-if="summary">
      <section class="cards">
        <article class="card">
          <p class="card__label">내 프로젝트</p>
          <p class="card__value">{{ summary.projectCount }}</p>
          <p class="card__note">접근 권한이 있는 프로젝트</p>
        </article>
        <article class="card" :class="{ 'card--warn': summary.pendingRecordCount > 0 }">
          <p class="card__label">확인 대기 기록</p>
          <p class="card__value">{{ summary.pendingRecordCount }}</p>
          <p class="card__note">AI 초안 중 사람이 확인하지 않은 건</p>
        </article>
        <article class="card">
          <p class="card__label">진행 중 인수인계</p>
          <p class="card__value">{{ summary.activeHandoverCount }}</p>
          <p class="card__note">{{ handover ? `${handover.code} · ${HANDOVER_STATUS_LABEL[handover.status]}` : '없음' }}</p>
        </article>
        <article class="card" :class="{ 'card--warn': openRiskCount > 0 }">
          <p class="card__label">미해결 위험</p>
          <p class="card__value">{{ openRiskCount }}</p>
          <p class="card__note">장애요인과 미해결 결정</p>
        </article>
      </section>

      <!-- D5: 아직 배정이 없는 신규 담당자도 막다른 화면 대신 현재 상태를 본다. -->
      <p v-if="showsEmptyHandoverState" class="state state--empty">
        배정된 인수인계 없음 — 프로젝트 관리자가 담당자 교체를 요청하면 여기에 표시됩니다.
      </p>

      <section class="panel">
        <h2 class="panel__head">내 프로젝트</h2>
        <p v-if="summary.projects.length === 0" class="state state--empty">접근 권한이 있는 프로젝트가 없습니다.</p>
        <table v-else class="table">
          <thead>
            <tr><th>프로젝트</th><th>고객사</th><th>수행 기간</th><th>현재 단계</th><th>상태</th><th>내 역할</th></tr>
          </thead>
          <tbody>
            <tr v-for="p in summary.projects" :key="p.id" class="table__row" @click="openProject(p.id)">
              <td class="table__name">{{ p.name }}</td>
              <td>{{ p.clientCompany }}</td>
              <td>{{ p.startedOn }} ~ {{ p.endsOn }}</td>
              <td>{{ p.phase }}</td>
              <td class="table__status">{{ PROJECT_STATUS_LABEL[p.status] }}</td>
              <td>{{ PROJECT_ROLE_LABEL[p.myRole] }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <!-- FR-22: 서버는 참여 프로젝트의 연동만, 시스템 관리자에게는 전체를 돌려준다. -->
      <section class="panel">
        <h2 class="panel__head">
          기록 수집 상태
          <span class="panel__note">조회 가능한 연동 {{ connections.length }}건</span>
        </h2>
        <ul class="conns">
          <li v-for="c in connections" :key="c.id" class="conn">
            <span class="conn__name">{{ c.displayName }}</span>
            <span v-if="c.ownerUserId" class="conn__mine">개인 연결</span>
            <span class="conn__health" :class="`conn__health--${c.health.toLowerCase()}`">{{ HEALTH_LABEL[c.health] }}</span>
            <span class="conn__time">{{ c.lastCollectedAt ? formatTimestamp(c.lastCollectedAt) : '수집 전' }}</span>
          </li>
        </ul>
      </section>
    </template>
  </AppShell>
</template>

<style scoped>
.title { margin: 0; font-size: 22px; }
.sub { margin: 4px 0 0; font-size: 12px; color: var(--grey-dark); }
.btn { height: 32px; padding: 0 14px; border: 1px solid var(--line); border-radius: 6px; background: var(--white); color: var(--navy); font-family: var(--font); font-size: 13px; cursor: pointer; }
.btn--primary { background: var(--orange); border-color: var(--orange); color: var(--white); font-weight: 600; }

.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.card { background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; }
.card--warn { border-left: 3px solid var(--orange); }
.card__label { margin: 0; font-size: 12px; color: var(--grey-dark); }
.card__value { margin: 4px 0 2px; font-size: 28px; font-weight: 700; }
.card__note { margin: 0; font-size: 11px; color: var(--grey); }


.panel { background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; margin-bottom: 14px; }
.panel__head { margin: 0 0 10px; font-size: 13px; color: var(--grey-dark); display: flex; align-items: baseline; gap: 8px; }
.panel__note { margin-left: auto; font-size: 11px; color: var(--grey); }

.table { width: 100%; border-collapse: collapse; font-size: 12px; }
.table th { text-align: left; padding: 8px 10px; background: var(--paper); color: var(--grey-dark); border-bottom: 1px solid var(--line); }
.table td { padding: 10px; border-bottom: 1px solid var(--line); }
.table__row { cursor: pointer; }
.table__row:hover { background: var(--paper); }
.table__name { font-weight: 600; }
.table__status { color: var(--orange); }

.conns { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.conn { display: flex; align-items: center; gap: 8px; font-size: 12px; padding: 6px 0; border-bottom: 1px solid var(--line); }
.conn__name { min-width: 260px; }
.conn__mine { font-size: 11px; color: var(--orange); }
.conn__health { padding: 1px 8px; border-radius: 999px; font-size: 11px; }
.conn__health--normal { background: var(--paper); color: var(--grey-dark); }
.conn__health--delayed { background: #fff0e8; color: var(--orange); }
.conn__health--failed { background: var(--orange); color: var(--white); }
.conn__time { margin-left: auto; color: var(--grey); }
</style>
