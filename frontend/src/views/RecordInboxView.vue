<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { RECORD_TYPE_LABEL } from '@/components/labels'
import { latestRequest } from '@/components/latestRequest'
import { recordSourceName } from '@/components/recordSource'
import { errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import { useSessionStore } from '@/stores/session'
import type { RecordTrustStatus, WorkRecord } from '@/api/types'

/**
 * S04 확인 대기 기록함.
 *
 * 여기서 하는 일은 빨리 훑고 넘기는 것이라, 한 기록을 한 줄에 두고 출처를 첫 열에 둔 표로 보여준다. 목록은 서버가
 * 권한으로 거른 페이지이며(다른 사람 개인 폴더의 초안은 아예 오지 않는다), 쌓인 건이 많아도 잘리지 않도록 기록
 * cursor로 이어 읽는다. 일괄 처리는 기록마다 version을 붙인 검토를 한 번씩 보내고, 거절은 서버가 준 그대로 알린다.
 */
const session = useSessionStore()
const repository = useRepository()
const router = useRouter()

const VIEWS = [
  { id: 'pending', label: '확인 대기', status: ['AUTO_DRAFT'] as RecordTrustStatus[] },
  { id: 'mine', label: '내 연결분', status: [] as RecordTrustStatus[] },
  { id: 'all', label: '전체', status: [] as RecordTrustStatus[] },
] as const
type ViewId = (typeof VIEWS)[number]['id']

const records = ref<WorkRecord[]>([])
const total = ref(0)
const nextCursor = ref<string | null>(null)
const myConnectionIds = ref<Set<number>>(new Set())
const activeView = ref<ViewId>('pending')
const selected = ref<number[]>([])
const busy = ref(false)
const error = ref('')
const result = ref('')
const nextLoad = latestRequest()

async function load(append = false) {
  const projectId = session.currentProjectId
  if (!projectId) return
  error.value = ''
  const isLatest = nextLoad()
  try {
    const view = VIEWS.find((v) => v.id === activeView.value)!
    const pageResult = await repository.listRecords(projectId, {
      trustStatus: view.status.length ? [...view.status] : undefined,
      cursor: append ? nextCursor.value ?? undefined : undefined,
      limit: 50,
    })
    if (!isLatest()) return
    records.value = append ? [...records.value, ...pageResult.items] : pageResult.items
    total.value = pageResult.page.total
    nextCursor.value = pageResult.page.nextCursor ?? null
    if (!append) {
      const connections = await repository.listSourceConnections(projectId)
      myConnectionIds.value = new Set(connections.items.filter((c) => c.ownerUserId === session.user?.id).map((c) => c.id))
    }
  } catch (e) {
    if (isLatest()) error.value = errorLine(e)
  }
}
onMounted(() => load())

function switchView(id: ViewId) {
  activeView.value = id
  selected.value = []
  void load()
}

const visible = computed(() =>
  activeView.value === 'mine'
    ? records.value.filter((r) => r.source && myConnectionIds.value.has(r.source.id))
    : records.value,
)
const allSelected = computed(
  () => visible.value.some((r) => r.trustStatus === 'AUTO_DRAFT')
    && visible.value.filter((r) => r.trustStatus === 'AUTO_DRAFT').every((r) => selected.value.includes(r.id)),
)

function toggleAll() {
  selected.value = allSelected.value ? [] : visible.value.filter((r) => r.trustStatus === 'AUTO_DRAFT').map((r) => r.id)
}

function toggleOne(id: number) {
  selected.value = selected.value.includes(id) ? selected.value.filter((c) => c !== id) : [...selected.value, id]
}

async function bulk(action: 'CONFIRM' | 'DISMISS') {
  busy.value = true
  result.value = ''
  const failures: string[] = []
  let done = 0
  for (const id of selected.value) {
    const target = records.value.find((r) => r.id === id)
    if (!target) continue
    try {
      await repository.reviewRecord(id, target.version, { action })
      done += 1
    } catch (e) {
      failures.push(`${target.code}: ${errorLine(e)}`)
    }
  }
  result.value = `${done}건 처리` + (failures.length ? ` · ${failures.length}건 실패 — ${failures.join(' / ')}` : '')
  selected.value = []
  // 새 목록(새 version)을 받은 뒤에 버튼을 푼다. AdminView와 같은 이유다.
  await load()
  busy.value = false
}
</script>

<template>
  <AppShell>
    <template #title>
      <h1 class="title">확인 대기 기록함</h1>
      <p class="sub">{{ VIEWS.find((v) => v.id === activeView)!.label }} <strong>{{ total }}건</strong></p>
    </template>
    <template #actions>
      <button class="btn" type="button" :disabled="selected.length === 0 || busy" @click="bulk('DISMISS')">
        선택 제외
      </button>
      <button class="btn btn--primary" type="button" :disabled="selected.length === 0 || busy" @click="bulk('CONFIRM')">
        선택 승인 ({{ selected.length }})
      </button>
    </template>

    <nav class="views" aria-label="저장된 뷰">
      <button
        v-for="v in VIEWS"
        :key="v.id"
        class="views__tab"
        :class="{ 'views__tab--on': activeView === v.id }"
        type="button"
        @click="switchView(v.id)"
      >{{ v.label }}</button>
    </nav>

    <p v-if="error" class="state state--failed">{{ error }}</p>
    <p v-if="result" class="state">{{ result }}</p>

    <p v-if="!session.currentProjectId" class="state state--empty">배정된 프로젝트가 없습니다.</p>
    <p v-else-if="visible.length === 0 && !error" class="state state--empty">
      확인할 기록이 없습니다. 새 기록이 수집되면 여기에 쌓입니다.
    </p>
    <template v-else>
      <table class="table">
        <thead>
          <tr>
            <th class="table__check"><input type="checkbox" :checked="allSelected" aria-label="전체 선택" @change="toggleAll" /></th>
            <th>출처</th>
            <th>유형</th>
            <th>AI 추출 요약</th>
            <th>발생일</th>
            <th>신뢰 상태</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in visible" :key="r.id">
            <td class="table__check">
              <input
                type="checkbox"
                :checked="selected.includes(r.id)"
                :disabled="r.trustStatus !== 'AUTO_DRAFT'"
                :aria-label="`${r.code} 선택`"
                @change="toggleOne(r.id)"
              />
            </td>
            <td class="table__source">{{ recordSourceName(r) }}</td>
            <td>{{ RECORD_TYPE_LABEL[r.recordType] }}</td>
            <td class="table__summary">
              <span class="table__code">{{ r.code }}</span>{{ r.summary }}
            </td>
            <td>{{ r.occurredOn }}</td>
            <td><StatusBadge :status="r.trustStatus" /></td>
            <td>
              <button class="text-button" type="button" @click="router.push(`/records/${r.id}`)">원문 대조</button>
            </td>
          </tr>
        </tbody>
      </table>
    </template>
    <!-- 표 밖에 둔다. 걸러 보는 뷰는 이 페이지가 비어도 다음 페이지에 해당 건이 있을 수 있다. -->
    <button v-if="nextCursor" class="btn more" type="button" @click="load(true)">더 보기</button>
  </AppShell>
</template>

<style scoped>
.title { margin: 0; font-size: 22px; }
.sub { margin: 4px 0 0; font-size: 12px; color: var(--grey-dark); }
.sub strong { color: var(--orange); }

.btn { height: 30px; padding: 0 12px; font-size: 12px; }

.views { display: flex; gap: 0; border-bottom: 1px solid var(--line); margin-bottom: 12px; }
.views__tab { border: none; background: none; padding: 8px 14px; font-family: var(--font); font-size: 13px; color: var(--grey-dark); cursor: pointer; border-bottom: 2px solid transparent; }
.views__tab--on { color: var(--navy); font-weight: 600; border-bottom-color: var(--orange); }

.table { width: 100%; border-collapse: collapse; background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; font-size: 12px; }
.table th { text-align: left; padding: 9px 10px; background: var(--paper); color: var(--grey-dark); font-weight: 600; border-bottom: 1px solid var(--line); white-space: nowrap; }
.table td { padding: 10px; border-bottom: 1px solid var(--line); vertical-align: middle; }
.table__check { width: 34px; }
.table__source { color: var(--grey-dark); white-space: nowrap; }
.table__summary { color: var(--navy); }
.table__code { display: inline-block; margin-right: 6px; color: var(--grey); }
/* 발표에서 처음 여는 기록. 발표자가 목록에서 찾느라 멈추지 않게 한다. */
.text-button { white-space: nowrap; }
.more { margin-top: 12px; }
</style>
