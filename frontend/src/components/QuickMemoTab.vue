<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RECORD_TYPE_LABEL, seoulToday } from './labels'
import { errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import { RECORD_TYPES, type PersonalNote, type RecordType } from '@/api/types'

/**
 * S06 빠른 메모 탭.
 *
 * 메모 원본은 공개하지 않는다. 프로젝트가 받는 것은 작성자가 여기서 쓴 요약뿐이며, 작성자가 직접 확인한 것이므로
 * CONFIRMED 기록으로 만든다(D10·D12). 메모가 자동으로 공개되면 사람들이 메모를 쓰지 않게 되고, 통화 합의 같은
 * 맥락이 다시 시스템 밖으로 나간다.
 */
const props = defineProps<{ projectId: number }>()
const repository = useRepository()

const notes = ref<PersonalNote[]>([])
const selectedId = ref<number | null>(null)
const newBody = ref('')
const busy = ref(false)
const error = ref('')
const shared = ref('')

const shareType = ref<RecordType>('SCOPE_EXCEPTION')
const shareTitle = ref('')
const shareSummary = ref('')
const shareDate = ref(seoulToday())

async function load() {
  try {
    notes.value = (await repository.listPersonalNotes(props.projectId)).items
    selectedId.value = selectedId.value ?? notes.value[0]?.id ?? null
  } catch (e) {
    error.value = errorLine(e)
  }
}
onMounted(load)

const selected = computed(() => notes.value.find((n) => n.id === selectedId.value) ?? null)
const canShare = computed(() => !!selected.value && !!shareTitle.value.trim() && !!shareSummary.value.trim() && !!shareDate.value)

async function save() {
  if (!newBody.value.trim()) return
  busy.value = true
  error.value = ''
  try {
    const note = await repository.createPersonalNote(props.projectId, newBody.value)
    newBody.value = ''
    await load()
    selectedId.value = note.id
  } catch (e) {
    error.value = errorLine(e)
  } finally {
    busy.value = false
  }
}

async function share() {
  if (!selected.value) return
  busy.value = true
  error.value = ''
  shared.value = ''
  try {
    const record = await repository.sharePersonalNoteSummary(selected.value.id, {
      recordType: shareType.value, title: shareTitle.value, summary: shareSummary.value, occurredOn: shareDate.value,
    })
    shared.value = `${record.code}이(가) 확인됨 상태로 생성됐습니다. 원본 ${selected.value.code}은 계속 비공개입니다.`
    await load()
  } catch (e) {
    error.value = errorLine(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="memo">
    <div class="memo__list">
      <h3 class="head">내 메모</h3>
      <ul>
        <li v-for="n in notes" :key="n.id">
          <button class="item" :class="{ 'item--on': n.id === selectedId }" type="button" @click="selectedId = n.id">
            <span class="item__code">{{ n.code }}</span>
            <span class="item__badge">나만 보기</span>
            <span v-if="n.sharedRecordCodes?.length" class="item__shared">공유 {{ n.sharedRecordCodes.length }}건</span>
          </button>
        </li>
      </ul>
      <label class="field">
        <span>새 메모</span>
        <textarea v-model="newBody" rows="3" aria-label="새 메모" placeholder="통화·복도 대화처럼 시스템 밖에서 생긴 내용"></textarea>
      </label>
      <button class="btn" type="button" :disabled="busy || !newBody.trim()" @click="save">비공개 저장</button>
    </div>

    <div class="memo__detail">
      <template v-if="selected">
        <h3 class="head">원본 — 비공개</h3>
        <pre class="body">{{ selected.body }}</pre>
        <p class="warn">이 원문은 공유해도 프로젝트에 공개되지 않습니다. 아래에서 쓴 요약만 기록이 됩니다.</p>

        <h3 class="head">프로젝트에 공유할 요약</h3>
        <label class="field field--row"><span>유형</span>
          <select v-model="shareType" aria-label="공유 유형"><option v-for="t in RECORD_TYPES" :key="t" :value="t">{{ RECORD_TYPE_LABEL[t] }}</option></select>
        </label>
        <label class="field field--row"><span>제목</span><input v-model="shareTitle" type="text" aria-label="공유 제목" /></label>
        <label class="field field--row"><span>요약</span><textarea v-model="shareSummary" rows="3" aria-label="공유 요약"></textarea></label>
        <label class="field field--row"><span>발생일</span><input v-model="shareDate" type="date" aria-label="공유 발생일" /></label>
        <p v-if="shared" class="shared">{{ shared }}</p>
        <button class="btn btn--primary" type="button" :disabled="busy || !canShare" @click="share">
          선택 요약 공유
        </button>
        <p v-if="selected.sharedRecordCodes?.length" class="hint">
          이미 {{ selected.sharedRecordCodes.join(', ') }}로 공유했습니다. 다른 요약은 새 기록으로 추가됩니다.
        </p>
      </template>
      <p v-else class="hint">메모를 선택하십시오.</p>
      <p v-if="error" class="state state--failed">{{ error }}</p>
    </div>
  </section>
</template>

<style scoped>
.memo { display: grid; grid-template-columns: 260px 1fr; gap: 14px; align-items: start; }
.memo__list, .memo__detail { background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; }
.head { margin: 0 0 8px; font-size: 13px; color: var(--grey-dark); }
ul { list-style: none; margin: 0 0 12px; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.item { width: 100%; display: flex; align-items: center; gap: 6px; border: 1px solid var(--line); border-radius: 6px; background: var(--white); padding: 8px 10px; font-family: var(--font); font-size: 12px; cursor: pointer; text-align: left; }
.item--on { border-color: var(--navy); background: var(--paper); }
.item__code { font-weight: 600; }
.item__badge { color: var(--grey); }
.item__shared { margin-left: auto; color: var(--orange); }

.field { display: flex; flex-direction: column; gap: 4px; margin-bottom: 8px; font-size: 12px; color: var(--grey); }
.field--row { display: grid; grid-template-columns: 56px 1fr; align-items: center; gap: 8px; }
.field input, .field select, .field textarea { width: 100%; border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; font-family: var(--font); font-size: 12px; }
.field textarea { resize: vertical; }

.body { margin: 0 0 8px; padding: 10px; background: var(--paper); border-radius: 6px; font-family: var(--font); font-size: 12px; line-height: 1.6; white-space: pre-wrap; }
.warn { margin: 0 0 14px; font-size: 12px; color: var(--orange); }
.btn { height: 30px; padding: 0 12px; border: 1px solid var(--line); border-radius: 6px; background: var(--white); color: var(--navy); font-family: var(--font); font-size: 12px; cursor: pointer; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.btn--primary { background: var(--orange); border-color: var(--orange); color: var(--white); font-weight: 600; }
.shared { color: var(--grey-dark); font-size: 12px; }
.hint { color: var(--grey); font-size: 12px; }
</style>
