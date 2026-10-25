<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import ConflictResolution from '@/components/ConflictResolution.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { HANDOVER_STATUS_LABEL } from '@/components/labels'
import { ApiProblem } from '@/api/http'
import { parseRouteId } from '@/api/ids'
import { describeBlocker, errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import type { AssessmentDetail, HandoverDetail, HandoverItem } from '@/api/types'

/**
 * S11 관리자 인수인계 검토·완료.
 *
 * 완료의 기준은 「문서를 넘겼는가」가 아니라 「이어받을 수 있는가」다. 조건은 현재 세대에 대한 서버의
 * `approvalBlockers`(승인 명령이 쓰는 것과 같은 검사)를 그대로 나열하고, 하나라도 남으면 승인을 막는다. 보완 요청에는
 * 사유가 필요하다. 서버가 패키지를 신규 담당자에게 되돌리고, 신규 담당자가 준비도를 다시 제출하면 READY로
 * 돌아온다. 결정은 프로젝트 관리자만 할 수 있고, 다른 사람은 서버의 거절을 받는다.
 */
const repository = useRepository()
const route = useRoute()
const router = useRouter()

const handover = ref<HandoverDetail | null>(null)
const items = ref<HandoverItem[]>([])
const assessment = ref<AssessmentDetail | null>(null)
const assessmentNote = ref('')
const comment = ref('')
const busy = ref(false)
const error = ref('')
const done = ref('')

const handoverId = computed(() => parseRouteId(route.params.handoverId))

async function load() {
  const id = handoverId.value
  if (id === null) {
    error.value = '인수인계를 찾을 수 없습니다.'
    return
  }
  try {
    handover.value = await repository.getHandover(id)
    const published = ['READY', 'REVIEW_REQUESTED', 'COMPLETED'].includes(handover.value.status)
    items.value = published ? (await repository.listHandoverItems(id)).items : []
    assessmentNote.value = ''
    assessment.value = published ? await repository.getAssessment(id).catch((e) => { assessmentNote.value = errorLine(e); return null }) : null
  } catch (e) {
    error.value = errorLine(e)
  }
}
onMounted(load)

const latest = computed(() => assessment.value?.attempts.at(-1) ?? null)
const conflictItems = computed(() => items.value.filter((i) => i.conflictId))
const followUps = computed(() => items.value.filter((i) => i.itemKind === 'FOLLOW_UP'))
const completed = computed(() => handover.value?.status === 'COMPLETED')
const blockers = computed(() => handover.value?.approvalBlockers ?? [])

async function decide(decision: 'APPROVE' | 'REQUEST_REVISION') {
  if (!handover.value) return
  busy.value = true
  error.value = ''
  done.value = ''
  try {
    handover.value = await repository.decideHandoverCompletion(
      handover.value.id, handover.value.version, decision, decision === 'REQUEST_REVISION' ? comment.value.trim() : undefined,
    )
    done.value = decision === 'APPROVE' ? '인수인계 완료를 승인했습니다.' : '보완을 요청했습니다. 신규 담당자가 준비도를 다시 제출하면 검토할 수 있습니다.'
    comment.value = ''
    await load()
  } catch (e) {
    error.value = errorLine(e)
    if (e instanceof ApiProblem && (e.status === 412 || e.status === 409)) await load()
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AppShell>
    <template #title>
      <h1 class="title">관리자 인수인계 검토</h1>
      <p v-if="handover" class="sub">
        {{ handover.code }} · {{ handover.outgoingOwnerName }} → {{ handover.incomingOwnerName }} · {{ HANDOVER_STATUS_LABEL[handover.status] }}
      </p>
    </template>
    <template #actions>
      <template v-if="handover && !completed">
        <button class="btn" type="button" :disabled="busy || !comment.trim() || handover.status !== 'READY'" @click="decide('REQUEST_REVISION')">보완 요청</button>
        <button class="btn btn--primary" type="button" :disabled="busy || blockers.length > 0" @click="decide('APPROVE')">
          인수인계 완료 승인
        </button>
      </template>
    </template>

    <p v-if="error" class="state state--failed">{{ error }}</p>
    <p v-if="done" class="state">{{ done }}</p>

    <template v-if="handover">
      <p v-if="completed" class="state state--failed completed">
        인수인계가 완료됐습니다. 미해결 위험은 {{ handover.incomingOwnerName }}의 후속 작업으로 넘어갔습니다.
      </p>

      <section v-if="!completed" class="panel">
        <h2 class="panel__head">
          완료 승인 조건
          <span class="panel__note">{{ blockers.length === 0 ? '모두 충족' : `${blockers.length}건 미충족` }}</span>
        </h2>
        <ul v-if="blockers.length" class="blockers">
          <li v-for="b in blockers" :key="b">{{ describeBlocker(b) }}</li>
        </ul>
        <p v-else class="hint">승인할 수 있습니다.</p>
        <label class="comment">
          <span>보완 요청 사유 (보완 요청 시 필수, 500자 이내)</span>
          <textarea v-model="comment" rows="2" maxlength="500" aria-label="보완 요청 사유"></textarea>
        </label>
      </section>

      <div class="grid">
        <section class="panel">
          <h2 class="panel__head">업무 준비도</h2>
          <p v-if="assessmentNote" class="state state--empty">{{ assessmentNote }}</p>
          <p v-else-if="!latest" class="state state--empty">아직 제출되지 않았습니다.</p>
          <template v-else>
            <p class="score">
              {{ latest.score }} / {{ latest.total }}
              <span class="score__attempt">{{ latest.attemptNo }}회차 · 통과 기준 {{ assessment?.passThreshold }}</span>
            </p>
            <ul class="answers">
              <li v-for="a in latest.answers" :key="a.questionId" :class="{ 'answers--wrong': !a.isCorrect }">
                <span class="answers__no">{{ a.questionNo }}</span>
                <span class="answers__mark">{{ a.isCorrect ? '확인함' : '다시 확인 필요' }}</span>
                <span v-if="!a.isCorrect && a.linkedRecordCode" class="answers__record">{{ a.linkedRecordCode }}</span>
              </li>
            </ul>
          </template>
        </section>

        <section class="panel">
          <h2 class="panel__head">미확인 기록 <span class="panel__note">{{ handover.unconfirmedRecords.length }}건</span></h2>
          <p class="hint">패키지 근거에 넣지 않은 범위 안의 초안입니다. 승인 조건에는 포함되지 않습니다.</p>
          <ul class="records">
            <li v-for="r in handover.unconfirmedRecords" :key="r.id">
              <span class="records__code">{{ r.code }}</span>
              <StatusBadge :status="r.trustStatus" />
              <span class="records__title">{{ r.title }}</span>
            </li>
          </ul>
        </section>
      </div>

      <section class="panel">
        <h2 class="panel__head">상충 기록 처리</h2>
        <ConflictResolution :handover-id="handover.id" :items="conflictItems" :relations="handover.conflicts" @resolved="load" />
      </section>

      <section class="panel">
        <h2 class="panel__head">후속 작업 <span class="panel__note">{{ followUps.length }}건</span></h2>
        <p class="hint">미해결 위험은 완료와 함께 신규 담당자의 업무로 넘어갑니다.</p>
        <ul class="follow">
          <li v-for="f in followUps" :key="f.id">
            <span class="follow__title">{{ f.title }}</span>
            <span class="follow__meta">{{ f.assigneeName ?? '--' }} · 기한 {{ f.dueOn ?? '--' }}</span>
          </li>
        </ul>
      </section>

      <button class="btn" type="button" @click="router.push(`/handovers/${handover.id}/progress`)">생성 상태·재생성</button>
    </template>
  </AppShell>
</template>

<style scoped>
.title { margin: 0; font-size: 22px; }
.sub { margin: 4px 0 0; font-size: 12px; color: var(--grey-dark); }
.completed { color: var(--navy); }

.panel { background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; margin-bottom: 14px; }
.panel__head { margin: 0 0 10px; font-size: 13px; color: var(--grey-dark); display: flex; align-items: baseline; gap: 8px; }
.panel__note { margin-left: auto; font-size: 11px; color: var(--orange); }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; align-items: start; }

.blockers { margin: 0; padding-left: 18px; font-size: 13px; color: var(--orange); }
.score { margin: 0 0 10px; font-size: 22px; font-weight: 700; }
.score__attempt { font-size: 11px; font-weight: 400; color: var(--grey); margin-left: 6px; }

.answers, .records, .conflicts, .follow { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; font-size: 12px; }
.answers li, .records li { display: flex; align-items: center; gap: 8px; }
.answers__no { width: 18px; height: 18px; border-radius: 50%; background: var(--paper); color: var(--grey-dark); display: grid; place-items: center; font-size: 11px; }
.answers__mark { color: var(--grey-dark); }
.answers--wrong .answers__mark { color: var(--orange); font-weight: 600; }
.records__code, .conflicts__title, .follow__title { font-weight: 600; }
.records__title { color: var(--grey-dark); }
.follow__meta { color: var(--grey); }

.hint { margin: 0; font-size: 12px; color: var(--grey); }
.comment { display: flex; flex-direction: column; gap: 4px; margin-top: 10px; font-size: 12px; color: var(--grey-dark); }
.comment textarea { border: 1px solid var(--line); border-radius: 6px; padding: 6px 8px; font-family: var(--font); font-size: 13px; }
.answers__record { font-size: 12px; color: var(--grey-dark); }
</style>
