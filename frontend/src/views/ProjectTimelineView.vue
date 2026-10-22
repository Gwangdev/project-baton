<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { RECORD_TYPE_LABEL, TRUST_STATUS_LABEL } from '@/components/labels'
import { recordSourceName } from '@/components/recordSource'
import { latestRequest } from '@/components/latestRequest'
import { parseRouteId } from '@/api/ids'
import { errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import { useSessionStore } from '@/stores/session'
import { RECORD_TYPES, type RecordTrustStatus, type RecordType, type WorkRecord } from '@/api/types'

/**
 * S03 프로젝트 타임라인.
 *
 * 기록은 서버가 고정한 순서(발생일, 그다음 ID, 최신순)로 보여준다. 필터는 계약의 쿼리 파라미터 그대로라서 서버가
 * 어디서나 쓰는 같은 권한 조건으로 거르고, 다음 페이지는 기록 cursor로 가져온다. 대체된 기록은 표시를 붙여 목록에
 * 남긴다. 「왜 바뀌었는가」가 바로 신규 담당자에게 필요한 것이라 옛 사실을 숨기면 그것을 잃는다.
 */
const session = useSessionStore()
const repository = useRepository()
const route = useRoute()
const router = useRouter()

const STATUSES: RecordTrustStatus[] = ['AUTO_DRAFT', 'CONFIRMED', 'CORRECTED', 'DISMISSED']

const records = ref<WorkRecord[]>([])
const total = ref(0)
const nextCursor = ref<string | null>(null)
const selectedTypes = ref<RecordType[]>([])
const selectedStatuses = ref<RecordTrustStatus[]>([])
const occurredFrom = ref('')
const occurredTo = ref('')
const error = ref('')

const projectId = computed(() => parseRouteId(route.params.projectId))
const nextLoad = latestRequest()

async function load(append = false) {
  const id = projectId.value
  if (id === null) {
    error.value = '프로젝트를 찾을 수 없습니다.'
    return
  }
  session.selectProject(id)
  error.value = ''
  const isLatest = nextLoad()
  try {
    const result = await repository.listRecords(id, {
      recordType: selectedTypes.value.length ? selectedTypes.value : undefined,
      trustStatus: selectedStatuses.value.length ? selectedStatuses.value : undefined,
      occurredFrom: occurredFrom.value || undefined,
      occurredTo: occurredTo.value || undefined,
      cursor: append ? nextCursor.value ?? undefined : undefined,
      limit: 50,
    })
    if (!isLatest()) return
    records.value = append ? [...records.value, ...result.items] : result.items
    total.value = result.page.total
    nextCursor.value = result.page.nextCursor ?? null
  } catch (e) {
    if (!isLatest()) return
    records.value = []
    error.value = errorLine(e)
  }
}
onMounted(() => load())
watch([selectedTypes, selectedStatuses, occurredFrom, occurredTo, projectId], () => load())

function toggle<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value]
}

const filtersActive = computed(
  () => selectedTypes.value.length > 0 || selectedStatuses.value.length > 0 || !!occurredFrom.value || !!occurredTo.value,
)

function clearFilters() {
  selectedTypes.value = []
  selectedStatuses.value = []
  occurredFrom.value = ''
  occurredTo.value = ''
}
</script>

<template>
  <AppShell>
    <template #title><h1 class="title">프로젝트 타임라인</h1></template>
    <template #actions>
      <span class="count">{{ records.length }}건 표시 / 조건에 맞는 {{ total }}건</span>
    </template>

    <div class="filters">
      <div class="filters__row">
        <span class="filters__label">기록 유형</span>
        <button
          v-for="t in RECORD_TYPES"
          :key="t"
          class="chip"
          :class="{ 'chip--on': selectedTypes.includes(t) }"
          type="button"
          @click="selectedTypes = toggle(selectedTypes, t)"
        >{{ RECORD_TYPE_LABEL[t] }}</button>
      </div>
      <div class="filters__row">
        <span class="filters__label">신뢰 상태</span>
        <button
          v-for="st in STATUSES"
          :key="st"
          class="chip"
          :class="{ 'chip--on': selectedStatuses.includes(st) }"
          type="button"
          @click="selectedStatuses = toggle(selectedStatuses, st)"
        >{{ TRUST_STATUS_LABEL[st] }}</button>
      </div>
      <div class="filters__row">
        <span class="filters__label">발생 기간</span>
        <input v-model="occurredFrom" class="date" type="date" aria-label="시작일" />
        <span class="tilde">~</span>
        <input v-model="occurredTo" class="date" type="date" aria-label="종료일" />
        <button v-if="filtersActive" class="chip chip--clear" type="button" @click="clearFilters">필터 해제</button>
      </div>
    </div>

    <p v-if="error" class="state state--failed">{{ error }}</p>
    <p v-else-if="records.length === 0" class="state state--empty">조건에 맞는 기록이 없습니다. 필터를 넓혀 보십시오.</p>

    <ol v-else class="cards">
      <li v-for="r in records" :key="r.id" class="card" :class="{ 'card--superseded': r.supersededByCode }" @click="router.push(`/records/${r.id}`)">
        <div class="card__head">
          <span class="card__type">{{ RECORD_TYPE_LABEL[r.recordType] }}</span>
          <span class="card__code">{{ r.code }}</span>
          <StatusBadge :status="r.trustStatus" />
          <span class="card__date">{{ r.occurredOn }}</span>
        </div>
        <p class="card__title">{{ r.title }}</p>
        <p class="card__summary">{{ r.summary }}</p>
        <p v-if="r.supersededByCode" class="card__superseded">
          {{ r.supersededByCode }}가 이 기록을 대체함 — 현재 사실로 사용하지 않음
        </p>
        <dl class="card__meta">
          <dt>출처</dt><dd>{{ recordSourceName(r) }}</dd>
          <dt>작성자</dt><dd>{{ r.authorName }}</dd>
          <dt>담당자</dt><dd>{{ r.ownerName ?? '--' }}</dd>
          <dt>기한</dt><dd>{{ r.dueOn ?? '--' }}</dd>
        </dl>
      </li>
    </ol>
    <button v-if="nextCursor" class="chip more" type="button" @click="load(true)">더 보기</button>
  </AppShell>
</template>

<style scoped>
.title { margin: 0; font-size: 22px; }
.count { font-size: 12px; color: var(--grey-dark); }

.filters { background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 12px 14px; margin-bottom: 16px; }
.filters__row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 4px 0; }
.filters__label { width: 68px; font-size: 12px; font-weight: 600; color: var(--grey-dark); }
.chip { border: 1px solid var(--line); background: var(--white); color: var(--navy); border-radius: 999px; padding: 4px 10px; font-family: var(--font); font-size: 12px; cursor: pointer; }
.chip--on { background: var(--navy); color: var(--white); border-color: var(--navy); }
.chip--clear { margin-left: 8px; color: var(--orange); border-color: var(--orange); }
.date { height: 26px; border: 1px solid var(--line); border-radius: 6px; padding: 0 6px; font-family: var(--font); font-size: 12px; }
.tilde { color: var(--grey); }

.cards { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.card { background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; }
.card--superseded { border-left: 3px solid var(--orange); }
.card__head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.card__type { font-size: 11px; font-weight: 700; color: var(--orange); }
.card__code { font-size: 11px; color: var(--grey); }
.card__date { margin-left: auto; font-size: 12px; color: var(--grey-dark); }
.card__title { margin: 0 0 4px; font-size: 15px; font-weight: 600; }
.card__summary { margin: 0 0 8px; font-size: 13px; color: var(--grey-dark); }
.card__superseded { margin: 0 0 8px; font-size: 12px; color: var(--orange); }
.card__meta { display: grid; grid-template-columns: auto 1fr auto 1fr; gap: 2px 10px; margin: 0; font-size: 12px; }
.card__meta dt { color: var(--grey); }
.card__meta dd { margin: 0; color: var(--navy); }
.card { cursor: pointer; }
.more { margin-top: 12px; }
</style>
