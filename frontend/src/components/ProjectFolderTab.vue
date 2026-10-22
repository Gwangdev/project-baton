<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CONNECTION_STATUS_LABEL, HEALTH_LABEL, RECORD_TYPE_LABEL, formatTimestamp, seoulToday } from './labels'
import { errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import { useSessionStore } from '@/stores/session'
import { RECORD_TYPES, type CollectedFile, type RecordType, type SourceConnectionDetail } from '@/api/types'

/**
 * S06 프로젝트 폴더 탭.
 *
 * 회사가 승인한 클라우드 폴더만 연결한다. 개인 PC 폴더나 화면·앱 사용은 보지 않는다(D7). 수집은 서버 일정에 따라
 * 돌기 때문에 「지금 수집」 버튼 대신 마지막 결과를 보여준다. 폴더의 원본과 미확정 초안은 연결한 사람에게만
 * 보이며(D9), 서버가 이를 강제하므로 여기서는 이 사용자 본인의 폴더만 돌아온다. 구조화에 실패한 파일은 다시
 * 처리하도록 보낼 수 있다 (retrySourceProcessing). 다시 처리해도 안 되면 연결한 사람이 직접 초안을 쓴다
 * (createManualDraft, FR-05). 이 경로가 화면에 없어 AI가 끝내 실패한 파일은 기록이 될 수 없었다. 직접 쓴 초안도
 * 확인 대기로 들어가 같은 검토를 거친다.
 */
const props = defineProps<{ projectId: number }>()
const session = useSessionStore()
const repository = useRepository()
const router = useRouter()

const connection = ref<SourceConnectionDetail | null>(null)
const files = ref<CollectedFile[]>([])
const busy = ref(false)
const error = ref('')
const folderName = ref('')
const folderPath = ref('')

async function load() {
  error.value = ''
  try {
    const list = await repository.listSourceConnections(props.projectId)
    const mine = list.items.find((c) => c.channel === 'CLOUD_PROJECT_FOLDER' && c.ownerUserId === session.user?.id)
    connection.value = mine ? await repository.getSourceConnection(mine.id) : null
    files.value = mine ? (await repository.listCollectedFiles(mine.id)).items : []
  } catch (e) {
    error.value = errorLine(e)
  }
}
onMounted(load)

const connected = computed(() => connection.value?.status === 'ACTIVE')

async function run(fn: () => Promise<unknown>) {
  busy.value = true
  error.value = ''
  try {
    await fn()
    await load()
  } catch (e) {
    error.value = errorLine(e)
  } finally {
    busy.value = false
  }
}

const connect = () => run(() => repository.createSourceConnection(props.projectId, {
  channel: 'CLOUD_PROJECT_FOLDER', displayName: folderName.value.trim(), externalRef: folderPath.value.trim(),
}))
const disconnect = () => run(() =>
  repository.updateSourceConnection(connection.value!.id, connection.value!.version, { status: 'DISCONNECTED' }))
const reconnect = () => run(() => repository.reconnectSourceConnection(connection.value!.id))
const retry = (file: CollectedFile) => run(() => repository.retrySourceProcessing(connection.value!.id, file.id))

const drafting = ref<number | null>(null)
const draft = ref({ recordType: 'OPEN_ISSUE' as RecordType, title: '', summary: '', originalExcerpt: '', occurredOn: seoulToday() })
const canDraft = computed(() => !!draft.value.title.trim() && !!draft.value.summary.trim() && !!draft.value.originalExcerpt.trim())

function startDraft(file: CollectedFile) {
  drafting.value = file.id
  draft.value = { recordType: 'OPEN_ISSUE', title: '', summary: '', originalExcerpt: '', occurredOn: seoulToday() }
}

async function writeDraft(file: CollectedFile) {
  busy.value = true
  error.value = ''
  try {
    const d = draft.value
    const created = await repository.createManualDraft(connection.value!.id, file.id, {
      recordType: d.recordType, title: d.title.trim(), summary: d.summary.trim(), originalExcerpt: d.originalExcerpt.trim(), occurredOn: d.occurredOn,
    })
    drafting.value = null
    await router.push(`/records/${created.id}`)
  } catch (e) {
    error.value = errorLine(e)
  } finally {
    busy.value = false
  }
}

const PROCESSING_LABEL: Record<CollectedFile['processingStatus'], string> = {
  PENDING: '처리 대기', PROCESSING: '처리 중', SUCCEEDED: '처리 완료', FAILED: '처리 실패',
}
</script>

<template>
  <section class="folder">
    <p class="note">
      회사가 승인한 클라우드 저장소의 업무 폴더만 연결합니다. 개인 PC의 폴더나 화면·앱 사용은 수집하지 않습니다.
      연결한 폴더의 원본 파일과 미확정 후보는 연결한 본인에게만 보입니다. 수집은 서버가 정해진 주기로 실행합니다.
    </p>

    <div v-if="connection" class="status">
      <div class="status__main">
        <p class="status__name">{{ connection.displayName }}</p>
        <p class="status__path">{{ connection.externalRef }}</p>
      </div>
      <dl class="status__meta">
        <dt>연결 상태</dt><dd :class="{ 'is-off': !connected }">{{ CONNECTION_STATUS_LABEL[connection.status] }}</dd>
        <dt>수집 상태</dt><dd>{{ connection.lastStatus ? HEALTH_LABEL[connection.health] : '수집 전' }}</dd>
        <dt>마지막 수집</dt><dd>{{ formatTimestamp(connection.lastCollectedAt) }}</dd>
        <dt>마지막 결과</dt><dd>{{ connection.lastStatus ? `${connection.lastCollectedCount}건 수집 / ${connection.lastFailedCount}건 실패` : '--' }}</dd>
        <dt>확인 대기</dt><dd>{{ connection.draftCandidateCount }}건</dd>
      </dl>
      <div class="status__actions">
        <button v-if="connected" class="btn" type="button" :disabled="busy" @click="disconnect">연결 해제</button>
        <button v-else class="btn btn--primary" type="button" :disabled="busy" @click="reconnect">다시 연결</button>
      </div>
    </div>
    <form v-else class="connect" @submit.prevent="connect">
      <label class="field"><span>연결 이름</span><input v-model="folderName" type="text" aria-label="연결 이름" /></label>
      <label class="field"><span>폴더 경로</span><input v-model="folderPath" type="text" aria-label="폴더 경로" /></label>
      <button class="btn btn--primary" type="submit" :disabled="busy || !folderName.trim() || !folderPath.trim()">폴더 연결</button>
    </form>

    <p v-if="error" class="state state--failed">{{ error }}</p>

    <template v-if="connection">
      <h3 class="files__head">수집된 파일 {{ files.length }}건</h3>
      <p v-if="!connected" class="hint">연결이 해제되어도 이미 수집된 파일과 기록은 남습니다.</p>
      <table class="files">
        <thead>
          <tr><th>파일명</th><th>수집 시각</th><th>처리</th><th>생성된 기록</th></tr>
        </thead>
        <tbody>
          <tr v-for="f in files" :key="f.id">
            <td>{{ f.displayName }}</td>
            <td>{{ formatTimestamp(f.collectedAt) }}</td>
            <td>
              {{ PROCESSING_LABEL[f.processingStatus] }}
              <button v-if="f.processingStatus === 'FAILED'" class="btn btn--sm" type="button" :disabled="busy" @click="retry(f)">다시 처리</button>
              <button v-if="f.processingStatus === 'FAILED'" class="btn btn--sm" type="button" :disabled="busy"
                      :aria-label="`${f.displayName} 직접 작성`" @click="startDraft(f)">직접 작성</button>
              <form v-if="drafting === f.id" class="draft" @submit.prevent="writeDraft(f)">
                <select v-model="draft.recordType" aria-label="초안 유형">
                  <option v-for="t in RECORD_TYPES" :key="t" :value="t">{{ RECORD_TYPE_LABEL[t] }}</option>
                </select>
                <input v-model="draft.title" type="text" maxlength="200" placeholder="제목" aria-label="초안 제목" />
                <textarea v-model="draft.summary" rows="2" maxlength="4000" placeholder="요약" aria-label="초안 요약"></textarea>
                <textarea v-model="draft.originalExcerpt" rows="2" maxlength="10000" placeholder="근거가 된 원문 발췌" aria-label="초안 원문 발췌"></textarea>
                <input v-model="draft.occurredOn" type="date" aria-label="초안 발생일" />
                <div class="draft__actions">
                  <button class="btn btn--sm" type="button" @click="drafting = null">취소</button>
                  <button class="btn btn--sm btn--primary" type="submit" :disabled="busy || !canDraft">초안 저장</button>
                </div>
              </form>
            </td>
            <td>
              <span v-if="f.derivedRecordCodes?.length">{{ f.derivedRecordCodes.join(', ') }}</span>
              <span v-else class="hint">아직 없음</span>
            </td>
          </tr>
        </tbody>
      </table>
    </template>
  </section>
</template>

<style scoped>
.note { margin: 0 0 12px; padding: 10px 12px; background: #fff3ec; border-radius: 6px; font-size: 12px; color: var(--grey-dark); }
.status { display: grid; grid-template-columns: 1fr auto auto; gap: 16px; align-items: center; background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; }
.status__name { margin: 0; font-size: 14px; font-weight: 600; }
.status__path { margin: 2px 0 0; font-size: 12px; color: var(--grey); }
.status__meta { display: grid; grid-template-columns: auto auto; gap: 2px 10px; margin: 0; font-size: 12px; }
.status__meta dt { color: var(--grey); }
.status__meta dd { margin: 0; }
.status__meta .is-off { color: var(--orange); }
.status__actions { display: flex; gap: 8px; }

.connect { display: flex; align-items: flex-end; gap: 10px; background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; }
.field { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--grey); }
.field input { width: 260px; border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; font-family: var(--font); font-size: 12px; }

.btn { height: 30px; padding: 0 12px; border: 1px solid var(--line); border-radius: 6px; background: var(--white); color: var(--navy); font-family: var(--font); font-size: 12px; cursor: pointer; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.btn--primary { background: var(--orange); border-color: var(--orange); color: var(--white); font-weight: 600; }

.files__head { margin: 18px 0 8px; font-size: 13px; color: var(--grey-dark); }
.files { width: 100%; border-collapse: collapse; background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; font-size: 12px; }
.files th { text-align: left; padding: 8px 10px; background: var(--paper); color: var(--grey-dark); border-bottom: 1px solid var(--line); }
.files td { padding: 9px 10px; border-bottom: 1px solid var(--line); }
.hint { color: var(--grey); font-size: 12px; }
.draft { display: grid; gap: 6px; margin-top: 8px; max-width: 420px; }
.draft input, .draft select, .draft textarea { border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; font-family: var(--font); font-size: 12px; }
.draft__actions { display: flex; justify-content: flex-end; gap: 6px; }
.btn--sm { height: 24px; padding: 0 8px; margin-left: 6px; font-size: 11px; }
</style>
