<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import ConflictResolution from '@/components/ConflictResolution.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { HANDOVER_STATUS_LABEL } from '@/components/labels'
import { relationOfItem } from '@/api/conflicts'
import { isGenerationPending, pollHandover } from '@/api/handoverPolling'
import { parseRouteId } from '@/api/ids'
import { GENERATION_FAILURE_TEXT, errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import type { HandoverDetail, HandoverItem } from '@/api/types'

/**
 * S08 패키지 생성 상태.
 *
 * 생성은 비동기다. 요청은 202로 접수됐고 워커가 나중에 패키지를 게시한다. 이 화면은 워커가 끝날 때까지 인수인계를
 * 다시 읽기만 하고(스스로 상태를 진행시키지 않는다), 끝나면 결정된 내용을 보여준다. 패키지가 게시되면 이번 세대의
 * 상충 기록이 관리자의 선택을 기다린다(FR-12). 생성이 실패하면 같은 snapshot으로 재시도하거나 최신 확정 기록으로
 * 재생성할 수 있다.
 */
const repository = useRepository()
const route = useRoute()
const router = useRouter()

const STEPS = [
  { status: 'REQUESTED', label: '요청 접수' },
  { status: 'GENERATING', label: '패키지 생성 중' },
  { status: 'READY', label: '열람 가능' },
] as const

const handover = ref<HandoverDetail | null>(null)
const items = ref<HandoverItem[]>([])
const busy = ref(false)
const error = ref('')
let polling: AbortController | null = null

const handoverId = computed(() => parseRouteId(route.params.handoverId))

async function loadItems() {
  if (!handover.value || isGenerationPending(handover.value.status) || handover.value.status === 'FAILED') {
    items.value = []
    return
  }
  items.value = (await repository.listHandoverItems(handover.value.id)).items
}

async function watchGeneration() {
  const id = handoverId.value
  if (id === null) {
    error.value = '인수인계를 찾을 수 없습니다.'
    return
  }
  polling?.abort()
  polling = new AbortController()
  error.value = ''
  try {
    handover.value = await pollHandover(() => repository.getHandover(id), {
      signal: polling.signal,
      onUpdate: (h) => { handover.value = h },
    })
    await loadItems()
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') return
    error.value = errorLine(e)
  }
}

async function refresh() {
  if (!handover.value) return
  try {
    handover.value = await repository.getHandover(handover.value.id)
    await loadItems()
  } catch (e) {
    error.value = errorLine(e)
  }
}

onMounted(watchGeneration)
onBeforeUnmount(() => polling?.abort())

const conflictItems = computed(() => items.value.filter((i) => i.conflictId))
// 서버의 준비도와 같이, 관계가 철회된 항목은 미해소(CONFLICTS_UNRESOLVED)가 아니라 재생성이 필요한 건(RELATIONS_WITHDRAWN)이다.
const isWithdrawn = (i: HandoverItem) => Boolean(relationOfItem(handover.value?.conflicts, i)?.withdrawnAt)
const unresolvedCount = computed(() => conflictItems.value.filter((i) => !i.resolvedByRecordCode && !isWithdrawn(i)).length)
const withdrawnCount = computed(() => conflictItems.value.filter(isWithdrawn).length)
const stepIndex = computed(() => STEPS.findIndex((s) => s.status === handover.value?.status))
const isPublished = computed(() => !!handover.value && ['READY', 'REVIEW_REQUESTED', 'COMPLETED'].includes(handover.value.status))
const canRegenerate = computed(() => !!handover.value && ['READY', 'REVIEW_REQUESTED', 'FAILED'].includes(handover.value.status))
const failure = computed(() => {
  const reason = handover.value?.failureReason
  return reason ? GENERATION_FAILURE_TEXT[reason] ?? reason : ''
})

async function command(fn: (h: HandoverDetail) => Promise<unknown>) {
  if (!handover.value) return
  busy.value = true
  error.value = ''
  try {
    await fn(handover.value)
    await watchGeneration()
  } catch (e) {
    error.value = errorLine(e)
    await refresh()
  } finally {
    busy.value = false
  }
}

const retry = () => command((h) => repository.retryHandoverGeneration(h.id, h.version))
const regenerate = () => command((h) => repository.regenerateHandover(h.id, h.version, {
  scopeFrom: h.scopeFrom, scopeTo: h.scopeTo, scopeRecordTypes: h.scopeRecordTypes,
  includeNoteDerivedRecords: h.includeNoteDerivedRecords,
}))
</script>

<template>
  <AppShell>
    <template #title>
      <h1 class="title">패키지 생성 상태</h1>
      <p v-if="handover" class="sub">
        {{ handover.code }} · {{ handover.outgoingOwnerName }} → {{ handover.incomingOwnerName }} · {{ handover.generation }}세대
      </p>
    </template>
    <template #actions>
      <button v-if="canRegenerate" class="btn" type="button" :disabled="busy" @click="regenerate">최신 기록으로 재생성</button>
      <template v-if="isPublished">
        <button class="btn" type="button" @click="router.push(`/handovers/${handover!.id}/review`)">관리자 검토</button>
        <button class="btn btn--primary" type="button" @click="router.push(`/handovers/${handover!.id}`)">패키지 열기</button>
      </template>
    </template>

    <p v-if="error" class="state state--failed">{{ error }}</p>
    <p v-if="!handover && !error" class="state state--loading">인수인계 상태를 불러오는 중입니다…</p>

    <template v-if="handover">
      <ol class="steps">
        <li
          v-for="(step, i) in STEPS"
          :key="step.status"
          class="steps__item"
          :class="{ 'steps__item--done': isPublished || i < stepIndex, 'steps__item--on': i === stepIndex }"
        >
          <span class="steps__no">{{ i + 1 }}</span>
          <span class="steps__label">{{ step.label }}</span>
        </li>
        <li class="steps__meter">
          <span class="steps__bar" :style="{ width: `${handover.progressPercent ?? 0}%` }"></span>
          <span class="steps__pct">{{ handover.progressPercent ?? 0 }}%</span>
        </li>
      </ol>
      <p class="state" role="status">현재 상태: {{ HANDOVER_STATUS_LABEL[handover.status] }}</p>

      <p v-if="handover.status === 'FAILED'" class="state state--failed">
        생성 실패 — {{ failure || '원인 미상' }}
        <button class="link" type="button" :disabled="busy" @click="retry">같은 범위로 재시도</button>
      </p>

      <div class="grid">
        <section class="panel">
          <h2 class="panel__head">
            내용이 상충하는 기록
            <span class="panel__note">{{ unresolvedCount }}건 미해소<template v-if="withdrawnCount"> · {{ withdrawnCount }}건 철회됨</template></span>
          </h2>
          <p v-if="!isPublished" class="hint">패키지가 게시되면 이번 세대의 상충 기록이 여기에 표시됩니다.</p>
          <ConflictResolution v-else :handover-id="handover.id" :items="conflictItems" :relations="handover.conflicts" @resolved="refresh" />
        </section>

        <section class="panel">
          <h2 class="panel__head">
            아직 확인되지 않은 기록
            <span class="panel__note">{{ handover.unconfirmedRecords.length }}건</span>
          </h2>
          <p class="hint">
            확인되지 않은 기록은 패키지에 포함하지 않습니다. 필요하면 기존 담당자가 먼저 확인한 뒤 재생성하십시오.
          </p>
          <ul class="unconf">
            <li v-for="r in handover.unconfirmedRecords" :key="r.id">
              <span class="unconf__code">{{ r.code }}</span>
              <StatusBadge :status="r.trustStatus" />
              <span class="unconf__summary">{{ r.title }}</span>
            </li>
          </ul>
        </section>
      </div>

      <section class="panel">
        <h2 class="panel__head">포함 결과</h2>
        <dl class="counts">
          <dt>포함</dt><dd>{{ handover.includedCount }}건</dd>
          <!-- 서버는 경고 목록을 100건까지만 보낸다. 그 이상은 정확한 수를 모르므로 「이상」으로 적는다. -->
          <dt>확인 대기</dt><dd>{{ handover.unconfirmedRecords.length }}건{{ handover.unconfirmedRecords.length >= 100 ? ' 이상' : '' }}</dd>
          <dt>포함 유형</dt><dd>{{ handover.scopeRecordTypes.length }}종</dd>
          <dt>메모 공유분</dt><dd>{{ handover.includeNoteDerivedRecords ? '포함' : '제외' }}</dd>
          <dt>포함 기간</dt><dd>{{ handover.scopeFrom }} ~ {{ handover.scopeTo }}</dd>
        </dl>
        <p v-if="!isPublished" class="hint">생성이 끝나면 확정됩니다.</p>
      </section>
    </template>
  </AppShell>
</template>

<style scoped>
.title { margin: 0; font-size: 22px; }
.sub { margin: 4px 0 0; font-size: 12px; color: var(--grey-dark); }

.steps { list-style: none; display: flex; align-items: center; gap: 10px; margin: 0 0 16px; padding: 12px 16px; background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); }
.steps__item { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--grey); }
.steps__no { width: 20px; height: 20px; border-radius: 50%; background: var(--line); color: var(--white); display: grid; place-items: center; font-size: 11px; }
.steps__item--done .steps__no { background: var(--navy); }
.steps__item--done { color: var(--grey-dark); }
.steps__item--on .steps__no { background: var(--orange); }
.steps__item--on { color: var(--navy); font-weight: 600; }
.steps__meter { flex: 1; display: flex; align-items: center; gap: 8px; }
.steps__bar { height: 6px; background: var(--orange); border-radius: 999px; min-width: 2px; transition: width 0.3s; }
.steps__pct { font-size: 11px; color: var(--grey); }

.grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 14px; align-items: start; margin-bottom: 14px; }
.panel { background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; margin-bottom: 14px; }
.panel__head { margin: 0 0 10px; font-size: 13px; color: var(--grey-dark); display: flex; align-items: baseline; gap: 8px; }
.panel__note { margin-left: auto; font-size: 11px; color: var(--orange); }


.unconf { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; font-size: 12px; }
.unconf li { display: flex; align-items: center; gap: 6px; }
.unconf__code { font-weight: 600; }
.unconf__summary { color: var(--grey-dark); }

.counts { display: grid; grid-template-columns: repeat(4, auto 1fr); gap: 4px 10px; margin: 0; font-size: 12px; }
.counts dt { color: var(--grey); }
.counts dd { margin: 0; font-weight: 600; }

.hint { color: var(--grey); font-size: 12px; margin: 0; }
.hint--block { margin-top: 8px; }
.link { border: none; background: none; color: var(--orange); font-family: var(--font); font-size: 12px; cursor: pointer; text-decoration: underline; }
.btn { height: 32px; padding: 0 14px; border: 1px solid var(--line); border-radius: 6px; background: var(--white); color: var(--navy); font-family: var(--font); font-size: 13px; cursor: pointer; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.btn--primary { background: var(--orange); border-color: var(--orange); color: var(--white); font-weight: 600; }
</style>
