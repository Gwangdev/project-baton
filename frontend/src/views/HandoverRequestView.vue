<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import { PROJECT_ROLE_LABEL, RECORD_TYPE_LABEL, seoulToday } from '@/components/labels'
import { ApiProblem } from '@/api/http'
import { errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import { useSessionStore } from '@/stores/session'
import { RECORD_TYPES, type ProjectMember, type RecordType } from '@/api/types'

/**
 * S07 담당자 교체 요청.
 *
 * 이 화면은 「누가 무엇을 넘겨받는가」를 정한다. 범위를 좁게 잡으면 맥락을 잃고 넓게 잡으면 확인되지 않은 것까지
 * 딸려 가므로, 기간·기록 유형·메모 공유분 포함 여부를 각각 명시적으로 고른다.
 *
 * 누가 요청할 수 있는지는 서버만 정한다(FR-10, 프로젝트 관리자). 입력 양식은 모두에게 보이고, 거절은 서버의 403으로
 * 알린다. 기존·신규 담당자는 그 프로젝트의 현재 구성원 목록에서 고른다. 예전에는 구성원 목록이 시스템 관리자 전용이라
 * 일반 관리자는 사용자 ID를 입력해야 했다. 목록을 받지 못할 때만 사용자 ID 입력으로 바꾼다. 신규 담당자는 관리자가
 * 미리 프로젝트에 배정해야 한다 (README §5.2).
 */
const session = useSessionStore()
const repository = useRepository()
const router = useRouter()

const outgoingId = ref<number | null>(null)
const incomingId = ref<number | null>(null)
const effectiveOn = ref(seoulToday())
const scopeFrom = ref('')
const scopeTo = ref(seoulToday())
const selectedTypes = ref<RecordType[]>([...RECORD_TYPES])
const includeNoteDerived = ref(true)
const members = ref<ProjectMember[] | null>(null)
// 목록을 기다리는 동안 실패 안내와 사용자 ID 입력을 보이지 않는다. 예전에는 불러오는 중에도 그 대비 화면이 먼저 보였다.
const membersLoaded = ref(false)
const busy = ref(false)
const error = ref('')
const denied = ref(false)
const existingHandoverId = ref<number | null>(null)

onMounted(async () => {
  const projectId = session.currentProjectId
  if (!projectId) {
    membersLoaded.value = true
    return
  }
  try {
    const project = await repository.getProject(projectId)
    scopeFrom.value = project.startedOn
  } catch (e) {
    error.value = errorLine(e)
  }
  try {
    members.value = (await repository.listCurrentProjectMembers(projectId)).items
  } catch {
    members.value = null
  } finally {
    membersLoaded.value = true
  }
})

const blockers = computed(() => {
  const list: string[] = []
  if (!outgoingId.value) list.push('기존 담당자를 지정해야 합니다')
  if (!incomingId.value) list.push('신규 담당자를 지정해야 합니다')
  else if (incomingId.value === outgoingId.value) list.push('기존 담당자와 신규 담당자가 같습니다')
  if (!scopeFrom.value || !scopeTo.value) list.push('포함 기간을 지정해야 합니다')
  else if (scopeFrom.value > scopeTo.value) list.push('포함 기간의 시작이 종료보다 늦습니다')
  if (selectedTypes.value.length === 0) list.push('포함할 기록 유형을 하나 이상 골라야 합니다')
  if (!effectiveOn.value) list.push('교체 적용일을 지정해야 합니다')
  return list
})

function toggleType(value: RecordType) {
  selectedTypes.value = selectedTypes.value.includes(value)
    ? selectedTypes.value.filter((t) => t !== value)
    : [...selectedTypes.value, value]
}

async function submit() {
  const projectId = session.currentProjectId
  if (blockers.value.length > 0 || !projectId) return
  busy.value = true
  error.value = ''
  denied.value = false
  existingHandoverId.value = null
  try {
    const accepted = await repository.requestHandover(projectId, {
      outgoingOwnerId: outgoingId.value!,
      incomingOwnerId: incomingId.value!,
      effectiveOn: effectiveOn.value,
      scopeFrom: scopeFrom.value,
      scopeTo: scopeTo.value,
      scopeRecordTypes: selectedTypes.value,
      includeNoteDerivedRecords: includeNoteDerived.value,
    })
    await router.push(`/handovers/${accepted.id}/progress`)
  } catch (e) {
    error.value = errorLine(e)
    if (e instanceof ApiProblem && e.status === 403) denied.value = true
    if (e instanceof ApiProblem && e.status === 409) {
      const open = (await repository.listHandovers(projectId).catch(() => null))?.items.find((h) => h.status !== 'COMPLETED')
      existingHandoverId.value = open?.id ?? null
    }
  } finally {
    busy.value = false
  }
}

const memberLabel = (m: ProjectMember) => `${m.userName} ${m.userTitle} · ${PROJECT_ROLE_LABEL[m.projectRole]} (ID ${m.userId})`
const nameOf = (id: number | null) => members.value?.find((m) => m.userId === id)?.userName ?? (id ? `ID ${id}` : '--')
</script>

<template>
  <AppShell>
    <template #title><h1 class="title">담당자 교체 요청</h1></template>
    <template #actions>
      <button class="btn btn--primary" type="button" :disabled="busy || blockers.length > 0" @click="submit">
        패키지 생성 요청
      </button>
    </template>

    <p v-if="!session.currentProjectId" class="state state--empty">배정된 프로젝트가 없습니다.</p>
    <p v-if="denied" class="state state--denied">
      담당자 교체는 해당 프로젝트의 프로젝트 관리자만 요청할 수 있습니다. 서버가 이 계정의 요청을 거부했습니다.
    </p>

    <form class="form" @submit.prevent="submit">
      <section class="group">
        <h2 class="group__head">담당자</h2>
        <p v-if="!membersLoaded" class="field field--wide hint">구성원 목록을 불러오는 중입니다.</p>
        <template v-else-if="members">
          <label class="field"><span>기존 담당자</span>
            <select v-model="outgoingId" aria-label="기존 담당자">
              <option :value="null">선택하십시오</option>
              <option v-for="m in members" :key="m.id" :value="m.userId">{{ memberLabel(m) }}</option>
            </select>
          </label>
          <label class="field"><span>신규 담당자</span>
            <select v-model="incomingId" aria-label="신규 담당자">
              <option :value="null">선택하십시오</option>
              <option v-for="m in members" :key="m.id" :value="m.userId">{{ memberLabel(m) }}</option>
            </select>
          </label>
        </template>
        <template v-else>
          <label class="field"><span>기존 담당자 사용자 ID</span>
            <input v-model.number="outgoingId" type="number" min="1" aria-label="기존 담당자 사용자 ID" />
          </label>
          <label class="field"><span>신규 담당자 사용자 ID</span>
            <input v-model.number="incomingId" type="number" min="1" aria-label="신규 담당자 사용자 ID" />
          </label>
          <p class="field field--wide hint">
            구성원 목록을 불러오지 못했습니다. 사용자 ID로 지정하십시오. 신규 담당자는 시스템 관리자가 먼저 프로젝트에 배정해야 합니다.
          </p>
        </template>
        <label class="field"><span>교체 적용일</span><input v-model="effectiveOn" type="date" aria-label="교체 적용일" /></label>
      </section>

      <section class="group">
        <h2 class="group__head">포함 범위</h2>
        <label class="field"><span>기간 시작</span><input v-model="scopeFrom" type="date" aria-label="기간 시작" /></label>
        <label class="field"><span>기간 종료</span><input v-model="scopeTo" type="date" aria-label="기간 종료" /></label>
        <div class="field field--wide">
          <span>기록 유형</span>
          <div class="chips">
            <button
              v-for="t in RECORD_TYPES"
              :key="t"
              class="chip"
              :class="{ 'chip--on': selectedTypes.includes(t) }"
              type="button"
              @click="toggleType(t)"
            >{{ RECORD_TYPE_LABEL[t] }}</button>
          </div>
        </div>
        <label class="field field--wide check">
          <input v-model="includeNoteDerived" type="checkbox" />
          <span class="check__text">
            개인 메모에서 공유된 확정 기록을 포함합니다.
            <em>원본 메모는 포함되지 않으며 공유 승인된 요약만 들어갑니다.</em>
          </span>
        </label>
      </section>

      <p v-if="error" class="state state--failed">
        {{ error }}
        <button v-if="existingHandoverId" class="link" type="button" @click="router.push(`/handovers/${existingHandoverId}/progress`)">
          진행 중인 인수인계 보기
        </button>
      </p>
    </form>

    <template #aside>
      <h2 class="aside__head">요청 요약</h2>
      <dl class="aside__list">
        <dt>기존 → 신규</dt><dd>{{ nameOf(outgoingId) }} → {{ nameOf(incomingId) }}</dd>
        <dt>적용일</dt><dd>{{ effectiveOn }}</dd>
        <dt>포함 기간</dt><dd>{{ scopeFrom }} ~ {{ scopeTo }}</dd>
        <dt>기록 유형</dt><dd>{{ selectedTypes.length }} / {{ RECORD_TYPES.length }}종</dd>
        <dt>메모 공유분</dt><dd>{{ includeNoteDerived ? '포함' : '제외' }}</dd>
      </dl>
      <div v-if="blockers.length" class="aside__blockers">
        <p class="aside__blockers-head">제출할 수 없는 이유</p>
        <ul><li v-for="b in blockers" :key="b">{{ b }}</li></ul>
      </div>
      <p v-else class="aside__ready">제출할 수 있습니다. 권한과 배정 여부는 서버가 확인합니다.</p>
    </template>
  </AppShell>
</template>

<style scoped>
.title { margin: 0; font-size: 22px; }

.form { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; align-items: start; }
.group { background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; }
.group__head { margin: 0 0 12px; font-size: 13px; color: var(--grey-dark); }
.field { display: grid; grid-template-columns: 84px 1fr; align-items: center; gap: 8px; margin-bottom: 10px; font-size: 12px; }
.field > span { color: var(--grey); }
.field--wide { align-items: start; }
.field input, .field select { width: 100%; border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; font-family: var(--font); font-size: 12px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { border: 1px solid var(--line); background: var(--white); color: var(--navy); border-radius: 999px; padding: 4px 10px; font-family: var(--font); font-size: 12px; cursor: pointer; }
.chip--on { background: var(--navy); color: var(--white); border-color: var(--navy); }
.check { grid-template-columns: 16px 1fr; }
.check input { width: 16px; }
.check__text { color: var(--navy); line-height: 1.5; }
.check__text em { display: block; color: var(--grey); font-style: normal; font-size: 11px; }

.btn { height: 32px; padding: 0 14px; border: 1px solid var(--line); border-radius: 6px; background: var(--white); color: var(--navy); font-family: var(--font); font-size: 13px; cursor: pointer; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.btn--primary { background: var(--orange); border-color: var(--orange); color: var(--white); font-weight: 600; }

.aside__head { margin: 0 0 10px; font-size: 13px; color: var(--grey-dark); }
.aside__list { display: grid; grid-template-columns: auto 1fr; gap: 6px 10px; margin: 0 0 14px; font-size: 12px; }
.aside__list dt { color: var(--grey); }
.aside__list dd { margin: 0; }
.aside__blockers { padding: 10px 12px; background: #fff3ec; border-radius: 6px; font-size: 12px; color: var(--grey-dark); }
.aside__blockers-head { margin: 0 0 6px; color: var(--orange); font-weight: 600; }
.aside__blockers ul { margin: 0; padding-left: 16px; }
.aside__ready { font-size: 12px; color: var(--grey-dark); }
.hint { margin: 0; font-size: 11px; color: var(--grey); }
.link { border: none; background: none; padding: 0 4px; color: var(--orange); font-family: var(--font); font-size: 12px; cursor: pointer; text-decoration: underline; }
</style>
