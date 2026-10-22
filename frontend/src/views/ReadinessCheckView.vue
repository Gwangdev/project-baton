<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import { formatTimestamp } from '@/components/labels'
import { parseRouteId } from '@/api/ids'
import { errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import type { AssessmentDetail } from '@/api/types'

/**
 * S10 업무 준비도 확인.
 *
 * 인사평가가 아니다. 목적은 신규 담당자가 무엇을 놓쳤는지 아는 것이다. 그래서 제출 뒤에도 문항을 남기고, 오답마다
 * 다시 읽을 기록을 알려준다. 채점은 서버에서만 한다(C08). 객관식 답은 보기 ID, 단답형은 정규화 후 비교하며 정답
 * 키는 브라우저에 오지 않는다. 모든 문항에 정확히 한 번 답해야 하므로 전부 채웠을 때만 제출된다.
 */
const repository = useRepository()
const route = useRoute()
const router = useRouter()

const assessment = ref<AssessmentDetail | null>(null)
const input = ref<Record<number, string>>({})
const busy = ref(false)
const error = ref('')

const handoverId = computed(() => parseRouteId(route.params.handoverId))

async function load() {
  const id = handoverId.value
  if (id === null) {
    error.value = '인수인계를 찾을 수 없습니다.'
    return
  }
  try {
    assessment.value = await repository.getAssessment(id)
  } catch (e) {
    error.value = errorLine(e)
  }
}
onMounted(load)

const questions = computed(() => assessment.value?.questions ?? [])
const latest = computed(() => assessment.value?.attempts.at(-1) ?? null)
const answeredCount = computed(() => questions.value.filter((q) => (input.value[q.id] ?? '').trim()).length)
const progress = computed(() => (questions.value.length === 0 ? 0 : Math.round((answeredCount.value / questions.value.length) * 100)))
const passed = computed(() => !!latest.value && !!assessment.value && latest.value.score >= assessment.value.passThreshold)
const resultOf = (questionId: number) => latest.value?.answers.find((a) => a.questionId === questionId) ?? null

async function submit() {
  const id = handoverId.value
  if (id === null) return
  busy.value = true
  error.value = ''
  try {
    await repository.submitAssessmentAttempt(id, questions.value.map((q) => ({ questionId: q.id, answerText: input.value[q.id].trim() })))
    input.value = {}
    await load()
  } catch (e) {
    error.value = errorLine(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <AppShell>
    <template #title>
      <h1 class="title">업무 준비도 확인</h1>
      <p class="sub">문서를 받았는지가 아니라, 업무를 이어받을 준비가 됐는지 확인합니다.</p>
    </template>
    <template #actions>
      <button v-if="handoverId" class="btn" type="button" @click="router.push(`/handovers/${handoverId}`)">패키지로 돌아가기</button>
    </template>

    <p v-if="error" class="state state--failed">{{ error }}</p>

    <template v-if="assessment">
      <section v-if="latest" class="result" :class="{ 'result--pass': passed }">
        <p class="result__score">{{ latest.score }} / {{ latest.total }}</p>
        <p class="result__note">
          <template v-if="passed">통과 기준({{ assessment.passThreshold }}문항)을 충족했습니다. 필수 항목 확인까지 끝나면 관리자가 완료를 승인할 수 있습니다.</template>
          <template v-else>틀린 항목의 근거 기록을 다시 확인한 뒤 재응시하십시오.</template>
        </p>
        <p class="result__attempt">{{ latest.attemptNo }}회차 · {{ formatTimestamp(latest.submittedAt) }}</p>
      </section>
      <div class="meter">
        <span class="meter__bar" :style="{ width: `${progress}%` }"></span>
        <span class="meter__text">{{ answeredCount }} / {{ questions.length }} 작성</span>
      </div>

      <ol class="questions">
        <li
          v-for="q in questions"
          :key="q.id"
          class="question"
          :class="{
            'question--wrong': resultOf(q.id)?.isCorrect === false,
            'question--right': resultOf(q.id)?.isCorrect === true,
          }"
        >
          <p class="question__prompt">
            <span class="question__no">{{ q.questionNo }}</span>{{ q.prompt }}
          </p>
          <div v-if="q.questionType === 'MULTIPLE_CHOICE'" class="question__choices" role="radiogroup" :aria-label="`${q.questionNo}번 보기`">
            <label v-for="c in q.choices ?? []" :key="c.id" class="question__choice">
              <input v-model="input[q.id]" type="radio" :name="`q-${q.id}`" :value="c.id" :disabled="busy" :aria-label="`${q.questionNo}번 ${c.text}`" />
              {{ c.text }}
            </label>
          </div>
          <input
            v-else
            v-model="input[q.id]"
            class="question__input"
            type="text"
            :aria-label="`${q.questionNo}번 답안`"
            :disabled="busy"
            placeholder="답을 입력하십시오"
          />
          <div v-if="resultOf(q.id)" class="question__result">
            <span v-if="resultOf(q.id)!.isCorrect" class="question__mark question__mark--ok">확인함</span>
            <template v-else>
              <span class="question__mark">다시 확인 필요</span>
              <span class="question__record">근거 기록 {{ resultOf(q.id)!.linkedRecordCode ?? q.linkedRecordCode }}</span>
            </template>
          </div>
        </li>
      </ol>

      <div class="actions">
        <button class="btn btn--primary" type="button" :disabled="busy || answeredCount < questions.length" @click="submit">
          {{ latest ? '다시 제출' : '제출' }}
        </button>
        <p class="hint">모든 문항에 답해야 제출할 수 있습니다. 점수는 평가가 아니라 놓친 맥락을 찾기 위한 것입니다.</p>
      </div>
    </template>
  </AppShell>
</template>

<style scoped>
.title { margin: 0; font-size: 22px; }
.sub { margin: 4px 0 0; font-size: 12px; color: var(--grey-dark); }

.result { display: grid; grid-template-columns: auto 1fr auto auto; align-items: center; gap: 16px; background: var(--white); border: 1px solid var(--line); border-left: 3px solid var(--orange); border-radius: var(--radius); padding: 14px 16px; margin-bottom: 14px; }
.result--pass { border-left-color: var(--navy); }
.result__score { margin: 0; font-size: 26px; font-weight: 700; }
.result__note { margin: 0; font-size: 13px; color: var(--grey-dark); }
.result__attempt { margin: 0; font-size: 11px; color: var(--grey); }

.meter { display: flex; align-items: center; gap: 10px; background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 12px 16px; margin-bottom: 14px; }
.meter__bar { height: 6px; background: var(--orange); border-radius: 999px; min-width: 2px; transition: width 0.2s; }
.meter__text { font-size: 12px; color: var(--grey-dark); white-space: nowrap; }

.questions { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.question { background: var(--white); border: 1px solid var(--line); border-left: 3px solid transparent; border-radius: var(--radius); padding: 12px 16px; }
.question--wrong { border-left-color: var(--orange); }
.question--right { border-left-color: var(--navy); }
.question__prompt { margin: 0 0 8px; font-size: 14px; display: flex; align-items: baseline; gap: 8px; }
.question__no { width: 18px; height: 18px; border-radius: 50%; background: var(--paper); color: var(--grey-dark); display: inline-grid; place-items: center; font-size: 11px; flex: none; }
.question__input { width: 100%; border: 1px solid var(--line); border-radius: 6px; padding: 7px 10px; font-family: var(--font); font-size: 13px; }
.question__result { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.question__mark { font-size: 12px; font-weight: 600; color: var(--orange); }
.question__mark--ok { color: var(--grey-dark); font-weight: 500; }

.actions { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
.hint { margin: 0; font-size: 11px; color: var(--grey); }
.btn { height: 32px; padding: 0 14px; border: 1px solid var(--line); border-radius: 6px; background: var(--white); color: var(--navy); font-family: var(--font); font-size: 13px; cursor: pointer; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.btn--primary { background: var(--orange); border-color: var(--orange); color: var(--white); font-weight: 600; }
.question__choices { display: flex; flex-wrap: wrap; gap: 8px 16px; margin-top: 6px; font-size: 13px; }
.question__choice { display: flex; align-items: center; gap: 6px; cursor: pointer; }
.question__record { font-size: 12px; color: var(--grey-dark); }
</style>
