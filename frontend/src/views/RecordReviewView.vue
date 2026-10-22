<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import RelationWithdrawal from '@/components/RelationWithdrawal.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { RECORD_TYPE_LABEL } from '@/components/labels'
import { recordSourceName } from '@/components/recordSource'
import { ApiProblem } from '@/api/http'
import { parseRouteId } from '@/api/ids'
import { errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import { useSessionStore } from '@/stores/session'
import { RECORD_TYPES, type RecordDetail, type RecordRelationType, type RecordType, type ReviewRecordInput, type WorkRecord } from '@/api/types'

/**
 * S05 기록 상세·AI 초안 확인.
 *
 * 원문과 추출 결과를 나란히 두어 스크롤 없이 대조하게 한다. 자동 확정은 없다. 사용자가 승인·수정 후 승인·제외 중
 * 하나를 고르면 서버가 이 화면이 읽은 version(If-Match)을 기준으로 적용한다. 412는 다른 사람이 먼저 검토했다는
 * 뜻이므로 덮어쓰지 않고 다시 불러온다. 원문이 비어 있으면 서버가 보여주지 않은 것이다. 개인 폴더 원문은 소유자에게만 있다.
 *
 * 담당자는 보여주되 고칠 수 없다. 계약에 관리자가 아닌 사용자가 쓸 구성원 목록이 없어 고를 사용자 ID 목록을
 * 제공할 수 없기 때문이다.
 *
 * PM은 확정된 기록에서 다른 확정 기록과의 관계(대체·참조·상충)를 등록한다(README §5.1, FR-12). 이 화면이 없어
 * 인수인계 패키지의 충돌 항목이 seed 데이터에서만 생겼다. 고를 수 있는 기록은 확정 기록뿐이며 최근 순으로 100건씩
 * 더 불러온다. 역할은 표시에만 쓰고 권한은 서버가 판정한다.
 */
const repository = useRepository()
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const record = ref<RecordDetail | null>(null)
const missing = ref('')
const error = ref('')
const done = ref('')
const busy = ref(false)

const draftTitle = ref('')
const draftSummary = ref('')
const draftType = ref<RecordType>('OPEN_ISSUE')
const draftDue = ref<string | null>(null)
const changeReason = ref('')

function adopt(detail: RecordDetail) {
  record.value = detail
  draftTitle.value = detail.title
  draftSummary.value = detail.summary
  draftType.value = detail.recordType
  draftDue.value = detail.dueOn ?? null
  changeReason.value = ''
}

async function load() {
  error.value = ''
  done.value = ''
  missing.value = ''
  const id = parseRouteId(route.params.recordId)
  if (id === null) {
    record.value = null
    missing.value = '기록을 찾을 수 없습니다.'
    return
  }
  try {
    adopt(await repository.getRecord(id))
  } catch (e) {
    record.value = null
    missing.value = errorLine(e)
  }
}
onMounted(load)
watch(() => route.params.recordId, load)

const editable = computed(() => record.value?.trustStatus === 'AUTO_DRAFT')
const changes = computed<Partial<ReviewRecordInput>>(() => {
  const r = record.value
  if (!r) return {}
  const diff: Partial<ReviewRecordInput> = {}
  if (draftTitle.value !== r.title) diff.title = draftTitle.value
  if (draftSummary.value !== r.summary) diff.summary = draftSummary.value
  if (draftType.value !== r.recordType) diff.recordType = draftType.value
  if ((draftDue.value || null) !== (r.dueOn ?? null)) diff.dueOn = draftDue.value || null
  return diff
})
const changed = computed(() => Object.keys(changes.value).length > 0)

async function review(action: 'CONFIRM' | 'CORRECT' | 'DISMISS') {
  if (!record.value) return
  error.value = ''
  busy.value = true
  try {
    const input: ReviewRecordInput = action === 'CORRECT'
      ? { action, ...changes.value, changeReason: changeReason.value }
      : { action }
    adopt(await repository.reviewRecord(record.value.id, record.value.version, input))
    done.value = action === 'DISMISS' ? '이 기록을 인수인계에서 제외했습니다.' : '확인이 끝났습니다. 다음 기록으로 넘어가십시오.'
  } catch (e) {
    error.value = errorLine(e)
    if (e instanceof ApiProblem && e.status === 412) {
      const current = await repository.getRecord(record.value.id).catch(() => null)
      if (current) adopt(current)
    }
  } finally {
    busy.value = false
  }
}

const RELATION_LABEL = { SUPERSEDES: '대체', REFERENCES: '참조', CONFLICTS_WITH: '상충' } as const
const RELATION_CHOICES: { type: RecordRelationType; label: string }[] = [
  { type: 'SUPERSEDES', label: '이 기록이 대체하는 이전 기록' },
  { type: 'REFERENCES', label: '이 기록이 참조하는 기록' },
  { type: 'CONFLICTS_WITH', label: '이 기록과 상충하는 기록' },
]

const relationType = ref<RecordRelationType>('SUPERSEDES')
const relationTarget = ref<number | null>(null)
const relationNote = ref('')
const candidates = ref<WorkRecord[]>([])
const candidateCursor = ref<string | null>(null)
const relationError = ref('')

const canRelate = computed(() => {
  const r = record.value
  if (!r || (r.trustStatus !== 'CONFIRMED' && r.trustStatus !== 'CORRECTED')) return false
  return session.memberships.some((m) => m.projectId === r.projectId && m.projectRole === 'PROJECT_MANAGER')
})

async function loadCandidates(more = false) {
  const r = record.value
  if (!r) return
  const page = await repository.listRecords(r.projectId, {
    trustStatus: ['CONFIRMED', 'CORRECTED'], limit: 100, cursor: more ? candidateCursor.value ?? undefined : undefined,
  })
  const fresh = page.items.filter((c) => c.id !== r.id)
  candidates.value = more ? [...candidates.value, ...fresh] : fresh
  candidateCursor.value = page.page.nextCursor ?? null
}
// 다른 기록으로 옮겨 가도 화면은 그대로이므로, 기록이 바뀔 때마다 후보를 다시 읽어 지금 기록을 후보에서 뺀다.
watch([canRelate, () => record.value?.id], ([allowed]) => {
  relationTarget.value = null
  if (allowed) void loadCandidates().catch((e) => { relationError.value = errorLine(e) })
}, { immediate: true })

async function relate() {
  const r = record.value
  if (!r || relationTarget.value === null) return
  relationError.value = ''
  busy.value = true
  try {
    await repository.createRecordRelation(r.projectId, {
      fromRecordId: r.id, toRecordId: relationTarget.value, relationType: relationType.value,
      ...(relationNote.value.trim() ? { note: relationNote.value.trim() } : {}),
    })
    relationTarget.value = null
    relationNote.value = ''
    adopt(await repository.getRecord(r.id))
  } catch (e) {
    // 서버는 순환, 같은 쌍의 중복, 한 쌍에 대체와 상충을 함께 거는 것을 모두 409로 거절한다. 잘못 건 관계를 바꾸려면
    // 먼저 철회해야 하므로 그 길을 함께 알린다.
    const hint = e instanceof ApiProblem && e.status === 409 && relationType.value !== 'REFERENCES'
      ? ' 같은 기록과 이미 대체·상충 관계가 있거나 순환·중복 관계입니다. 잘못 건 관계는 「관련 기록」에서 철회한 뒤 다시 등록하십시오.'
      : ''
    relationError.value = errorLine(e) + hint
  } finally {
    busy.value = false
  }
}

async function reload() {
  const r = record.value
  if (!r) return
  try {
    adopt(await repository.getRecord(r.id))
  } catch (e) {
    relationError.value = errorLine(e)
  }
}
</script>

<template>
  <AppShell>
    <template #title>
      <h1 class="title">기록 상세 · AI 초안 확인</h1>
      <p v-if="record" class="sub">{{ record.code }} · {{ record.occurredOn }}</p>
    </template>
    <template #actions>
      <StatusBadge v-if="record" :status="record.trustStatus" />
    </template>

    <p v-if="!record" class="state state--empty">{{ missing || '불러오는 중입니다…' }}</p>

    <template v-else>
      <p v-if="record.supersededByCode" class="superseded">
        {{ record.supersededByCode }}가 이 기록을 대체했습니다 — 현재 사실로 사용하지 않습니다.
      </p>

      <div class="compare">
        <section class="pane">
          <h2 class="pane__head">원문</h2>
          <dl class="pane__meta">
            <dt>출처</dt><dd>{{ recordSourceName(record) }}</dd>
            <dt>작성자</dt><dd>{{ record.authorName }}</dd>
            <dt>발생일</dt><dd>{{ record.occurredOn }}</dd>
          </dl>
          <pre v-if="record.originalExcerpt" class="pane__excerpt">{{ record.originalExcerpt }}</pre>
          <p v-else class="hint">원문은 연결 소유자에게만 공개됩니다. 확정된 요약만 공유됩니다.</p>
        </section>

        <section class="pane">
          <h2 class="pane__head">AI 추출 결과</h2>
          <label class="field">
            <span>기록 유형</span>
            <select v-model="draftType" :disabled="!editable" aria-label="기록 유형">
              <option v-for="t in RECORD_TYPES" :key="t" :value="t">{{ RECORD_TYPE_LABEL[t] }}</option>
            </select>
          </label>
          <label class="field">
            <span>제목</span>
            <input v-model="draftTitle" type="text" :disabled="!editable" aria-label="제목" />
          </label>
          <label class="field">
            <span>요약</span>
            <textarea v-model="draftSummary" rows="3" :disabled="!editable" aria-label="요약"></textarea>
          </label>
          <label class="field">
            <span>담당자</span>
            <input :value="record.ownerName ?? '--'" type="text" disabled aria-label="담당자" />
          </label>
          <label class="field">
            <span>기한</span>
            <input v-model="draftDue" type="date" :disabled="!editable" aria-label="기한" />
          </label>
          <label v-if="editable && changed" class="field">
            <span>수정 사유</span>
            <input v-model="changeReason" type="text" placeholder="무엇을 왜 고쳤는지" aria-label="수정 사유" />
          </label>
        </section>
      </div>

      <section v-if="record.relations.length" class="revisions">
        <h2 class="revisions__head">관련 기록</h2>
        <ul>
          <li v-for="rel in record.relations" :key="rel.id">
            <strong>{{ RELATION_LABEL[rel.relationType] }}</strong>
            <button class="link" type="button" @click="router.push(`/records/${rel.relatedRecordId}`)">{{ rel.relatedRecordCode }}</button>
            {{ rel.relatedRecordTitle }}
            <p v-if="rel.note" class="revisions__diff">{{ rel.note }}</p>
            <RelationWithdrawal v-if="canRelate" :relation="rel" :disabled="busy" @pending="busy = $event" @done="reload" />
          </li>
        </ul>
      </section>

      <section v-if="canRelate" class="revisions">
        <h2 class="revisions__head">관계 등록</h2>
        <form class="relate" @submit.prevent="relate">
          <select v-model="relationType" aria-label="관계 종류">
            <option v-for="c in RELATION_CHOICES" :key="c.type" :value="c.type">{{ c.label }}</option>
          </select>
          <select v-model="relationTarget" aria-label="관계 대상 기록">
            <option :value="null">기록 선택</option>
            <option v-for="c in candidates" :key="c.id" :value="c.id">{{ c.code }} · {{ c.title }}</option>
          </select>
          <input v-model="relationNote" type="text" maxlength="300" placeholder="메모(선택)" aria-label="관계 메모" />
          <button class="btn" type="submit" :disabled="busy || relationTarget === null">등록</button>
          <button v-if="candidateCursor" class="link" type="button" @click="loadCandidates(true)">기록 더 불러오기</button>
        </form>
        <p v-if="relationError" class="state state--failed">{{ relationError }}</p>
      </section>

      <section v-if="record.revisions.length" class="revisions">
        <h2 class="revisions__head">수정 이력</h2>
        <ol>
          <li v-for="rev in record.revisions" :key="rev.revisionNo">
            <strong>{{ rev.changedByName }}</strong> — {{ rev.changeReason }}
            <p class="revisions__diff">{{ rev.beforeSummary }} → {{ rev.afterSummary }}</p>
          </li>
        </ol>
      </section>

      <p v-if="error" class="state state--failed">{{ error }}</p>
      <p v-if="done" class="done">{{ done }}</p>

      <div class="actions">
        <template v-if="editable">
          <button class="btn" type="button" :disabled="busy" @click="review('DISMISS')">제외</button>
          <button class="btn" type="button" :disabled="busy || changed" @click="review('CONFIRM')">승인</button>
          <button
            class="btn btn--primary"
            type="button"
            :disabled="busy || !changed || !changeReason.trim()"
            @click="review('CORRECT')"
          >수정 후 승인</button>
          <p v-if="changed && !changeReason.trim()" class="hint">내용을 고쳤으므로 수정 사유가 필요합니다.</p>
        </template>
        <template v-else>
          <button class="btn" type="button" @click="router.push('/records')">확인 대기 기록함으로</button>
          <button class="btn btn--primary" type="button" @click="router.push(`/projects/${record.projectId}/collection`)">
            내 기록 수집으로
          </button>
        </template>
      </div>
    </template>
  </AppShell>
</template>

<style scoped>
.title { margin: 0; font-size: 22px; }
.sub { margin: 4px 0 0; font-size: 12px; color: var(--grey-dark); }
.superseded { margin: 0 0 12px; padding: 8px 12px; border-left: 3px solid var(--orange); background: #fff3ec; font-size: 12px; color: var(--grey-dark); }

.compare { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; align-items: start; }
.pane { background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; }
.pane__head { margin: 0 0 10px; font-size: 13px; color: var(--grey-dark); }
.pane__meta { display: grid; grid-template-columns: auto 1fr; gap: 2px 10px; margin: 0 0 10px; font-size: 12px; }
.pane__meta dt { color: var(--grey); }
.pane__meta dd { margin: 0; }
.pane__excerpt { margin: 0; padding: 10px; background: var(--paper); border-radius: 6px; font-family: var(--font); font-size: 12px; line-height: 1.6; white-space: pre-wrap; }

.field { display: grid; grid-template-columns: 68px 1fr; align-items: center; gap: 8px; margin-bottom: 8px; font-size: 12px; }
.field span { color: var(--grey); }
.field input, .field select, .field textarea {
  width: 100%; border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px;
  font-family: var(--font); font-size: 12px; color: var(--navy); background: var(--white);
}
.field textarea { resize: vertical; }
.field :disabled { background: var(--paper); color: var(--grey-dark); }

.revisions { margin-top: 14px; background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 12px 16px; font-size: 12px; }
.revisions__head { margin: 0 0 8px; font-size: 13px; color: var(--grey-dark); }
.revisions ol { margin: 0; padding-left: 18px; }
.revisions__diff { margin: 2px 0 0; color: var(--grey-dark); }

.done { margin-top: 12px; color: var(--grey-dark); font-size: 12px; }
.actions { display: flex; align-items: center; gap: 8px; margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--line); }
.btn { height: 32px; padding: 0 14px; border: 1px solid var(--line); border-radius: 6px; background: var(--white); color: var(--navy); font-family: var(--font); font-size: 13px; cursor: pointer; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.btn--primary { background: var(--orange); border-color: var(--orange); color: var(--white); font-weight: 600; }
.hint { margin: 0 0 0 4px; font-size: 11px; color: var(--grey); }
.relate { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.relate select, .relate input { border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; font-family: var(--font); font-size: 12px; }
.relate input { flex: 1; min-width: 160px; }
.link { border: none; background: none; padding: 0 4px; color: var(--orange); font-family: var(--font); font-size: 12px; cursor: pointer; text-decoration: underline; }
</style>
