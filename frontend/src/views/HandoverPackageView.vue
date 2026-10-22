<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { HANDOVER_STATUS_LABEL, ITEM_KIND_LABEL, ITEM_KIND_ORDER, QA_STATUS_LABEL } from '@/components/labels'
import { recordSourceName } from '@/components/recordSource'
import { ApiProblem } from '@/api/http'
import { isGenerationPending } from '@/api/handoverPolling'
import { parseRouteId } from '@/api/ids'
import { QA_FAILURE_TEXT, errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import type { HandoverDetail, HandoverItem, HandoverItemKind, QaAnswer, QaCitation } from '@/api/types'

/**
 * S09 인수인계 패키지·근거 기반 Q&A.
 *
 * 신규 담당자는 항목을 읽고, 필수 항목은 「이해했음」으로 명시 확인하며(C06, 화면을 연 것만으로는 확인이 아니다),
 * 이번 세대 snapshot에서만 답하는 질문을 한다. 확인과 질문을 누가 할 수 있는지는 서버가 정하고, 거절은 받은
 * 그대로 보여준다.
 *
 * 답변은 C07 이벤트로 흘러온다. delta 문장은 별도 영역의 임시 초안이고, 대화에 남는 답변은 `done`으로 저장된
 * 것(이력을 다시 읽는다)이다. `error`가 오면 초안을 버린다. 서버는 답변 전체가 인용 검증을 통과한 뒤 delta를
 * 보내므로, 토큰 단위가 아니라 마지막에 한꺼번에 도착한다.
 */
const repository = useRepository()
const route = useRoute()
const router = useRouter()

interface Turn {
  question: QaAnswer
  answer: QaAnswer | null
}

interface PendingAnswer {
  question: string
  draft: string[]
  citations: QaCitation[]
  failure: string
}

const handover = ref<HandoverDetail | null>(null)
const items = ref<HandoverItem[]>([])
const activeKind = ref<HandoverItemKind>('OVERVIEW')
const activeItemId = ref<number | null>(null)
const error = ref('')
const ackError = ref('')
const acknowledging = ref<number | null>(null)

const history = ref<QaAnswer[]>([])
const historyCursor = ref<string | null>(null)
const qaDenied = ref('')
const qaError = ref('')
const question = ref('')
const pending = ref<PendingAnswer | null>(null)
const asking = ref(false)
let streamAbort: AbortController | null = null

const handoverId = computed(() => parseRouteId(route.params.handoverId))

async function loadPackage() {
  const id = handoverId.value
  if (id === null) {
    error.value = '인수인계를 찾을 수 없습니다.'
    return
  }
  try {
    handover.value = await repository.getHandover(id)
    items.value = isGenerationPending(handover.value.status) || handover.value.status === 'FAILED'
      ? []
      : (await repository.listHandoverItems(id)).items
  } catch (e) {
    error.value = errorLine(e)
  }
}

async function loadHistory(append = false) {
  const id = handoverId.value
  if (id === null) return
  try {
    const page = await repository.listQuestions(id, append ? historyCursor.value ?? undefined : undefined)
    history.value = append ? [...history.value, ...page.items] : page.items
    historyCursor.value = page.page.nextCursor ?? null
    qaDenied.value = ''
    qaError.value = ''
  } catch (e) {
    // 거절만 질문 영역을 닫는다. 일시적 실패로 닫으면 새로 고칠 때까지 질문할 수 없다.
    if (e instanceof ApiProblem && (e.status === 403 || e.status === 404)) {
      qaDenied.value = '질의응답은 이 인수인계에 지정된 신규 담당자만 사용할 수 있습니다.'
    } else {
      qaError.value = `질문 이력을 불러오지 못했습니다. ${errorLine(e)}`
    }
  }
}

onMounted(async () => {
  await loadPackage()
  if (handover.value) await loadHistory()
})
onBeforeUnmount(() => streamAbort?.abort())

const published = computed(() => items.value.length > 0)
const menu = computed(() =>
  ITEM_KIND_ORDER.map((kind) => ({ kind, label: ITEM_KIND_LABEL[kind], count: items.value.filter((i) => i.itemKind === kind).length }))
    .filter((m) => m.count > 0),
)
const visible = computed(() =>
  items.value.filter((i) => i.itemKind === activeKind.value).slice().sort((a, b) => a.displayOrder - b.displayOrder),
)
const activeItem = computed(() => visible.value.find((i) => i.id === activeItemId.value) ?? visible.value[0] ?? null)
const required = computed(() => items.value.filter((i) => i.isRequired))
const acknowledgedCount = computed(() => required.value.filter((i) => i.acknowledged).length)

const turns = computed<Turn[]>(() => {
  const list: Turn[] = []
  for (const message of history.value) {
    if (message.role === 'USER') list.push({ question: message, answer: null })
    else if (list.length && !list[list.length - 1].answer) list[list.length - 1].answer = message
  }
  return list
})

async function acknowledge(item: HandoverItem) {
  if (!handover.value) return
  acknowledging.value = item.id
  ackError.value = ''
  try {
    await repository.acknowledgeHandoverItem(handover.value.id, item.id, item.version)
  } catch (e) {
    ackError.value = errorLine(e)
  } finally {
    acknowledging.value = null
    items.value = (await repository.listHandoverItems(handover.value.id)).items
  }
}

async function ask() {
  const text = question.value.trim()
  if (!text || !handover.value) return
  asking.value = true
  streamAbort = new AbortController()
  pending.value = { question: text, draft: [], citations: [], failure: '' }
  try {
    const result = await repository.askQuestion(handover.value.id, text, {
      onEvidence: (citations) => { if (pending.value) pending.value.citations = citations },
      onDelta: (sentence) => { pending.value?.draft.push(sentence) },
    }, streamAbort.signal)
    if (result.kind === 'done') {
      pending.value = null
      question.value = ''
    } else {
      pending.value = {
        question: text, draft: [], citations: [],
        failure: `${QA_FAILURE_TEXT[result.code] ?? result.code}${result.retryable ? ' — 잠시 후 다시 질문하십시오.' : ''} (추적 ID ${result.traceId})`,
      }
    }
    await loadHistory()
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') return
    pending.value = { question: text, draft: [], citations: [], failure: errorLine(e) }
  } finally {
    asking.value = false
    streamAbort = null
  }
}
</script>

<template>
  <AppShell>
    <template #title>
      <h1 class="title">인수인계 패키지</h1>
      <p v-if="handover" class="sub">
        {{ handover.code }} · {{ handover.outgoingOwnerName }} → {{ handover.incomingOwnerName }} ·
        포함 {{ handover.includedCount }}건 · {{ handover.generation }}세대 · {{ HANDOVER_STATUS_LABEL[handover.status] }}
      </p>
    </template>
    <template #actions>
      <button v-if="handover && published" class="btn btn--primary" type="button" @click="router.push(`/handovers/${handover.id}/readiness`)">
        업무 준비도 확인
      </button>
    </template>

    <p v-if="error" class="state state--failed">{{ error }}</p>
    <p v-else-if="handover && !published" class="state state--empty">
      패키지가 아직 게시되지 않았습니다. 현재 상태는 {{ HANDOVER_STATUS_LABEL[handover.status] }}입니다.
    </p>

    <template v-if="handover && published">
      <p v-if="handover.status === 'REVIEW_REQUESTED' && handover.revisionComment" class="state state--denied" role="note">
        관리자가 보완을 요청했습니다: {{ handover.revisionComment }}
      </p>
      <p class="state" role="status">
        필수 항목 확인 {{ acknowledgedCount }} / {{ required.length }} — 필수 항목은 읽은 뒤 「이해했음」을 눌러야 완료 승인 조건이 충족됩니다.
      </p>
      <p v-if="ackError" class="state state--failed">{{ ackError }}</p>

      <div class="package">
        <nav class="menu" aria-label="패키지 항목">
          <button
            v-for="m in menu"
            :key="m.kind"
            class="menu__item"
            :class="{ 'menu__item--on': activeKind === m.kind }"
            type="button"
            @click="activeKind = m.kind; activeItemId = null"
          >
            <span>{{ m.label }}</span>
            <span class="menu__count">{{ m.count }}</span>
          </button>
        </nav>

        <section class="content">
          <article
            v-for="item in visible"
            :key="item.id"
            class="item"
            :class="{ 'item--on': activeItem?.id === item.id }"
            @click="activeItemId = item.id"
          >
            <p class="item__title">
              {{ item.title }}
              <span v-if="item.isRequired" class="item__required">필수 확인</span>
              <span v-if="item.acknowledged" class="item__acked">확인함</span>
            </p>
            <p class="item__body">{{ item.body }}</p>
            <p class="item__meta">
              <span v-if="item.evidence">근거 {{ item.evidence.code }}</span>
              <span v-if="item.resolvedByRecordCode">최종 근거 {{ item.resolvedByRecordCode }}</span>
              <span v-if="item.assigneeName">담당 {{ item.assigneeName }}</span>
              <span v-if="item.dueOn">기한 {{ item.dueOn }}</span>
            </p>
            <button
              v-if="item.isRequired && !item.acknowledged"
              class="btn btn--sm"
              type="button"
              :aria-label="`${item.title} 이해했음`"
              :disabled="acknowledging === item.id"
              @click.stop="acknowledge(item)"
            >이해했음</button>
          </article>
        </section>
      </div>

      <section class="qa">
        <h2 class="qa__head">프로젝트 질문</h2>
        <p v-if="qaDenied" class="state state--denied">{{ qaDenied }}</p>
        <template v-else>
          <p v-if="qaError" class="state state--failed">{{ qaError }}</p>
          <ol class="qa__log">
            <li v-for="turn in turns" :key="turn.question.id" class="qa__turn">
              <p class="qa__question">{{ turn.question.content }}</p>
              <div v-if="turn.answer" class="qa__answer" :class="`qa__answer--${(turn.answer.answerStatus ?? 'failed').toLowerCase()}`">
                <p class="qa__status">{{ QA_STATUS_LABEL[turn.answer.answerStatus ?? 'FAILED'] }}</p>
                <p v-if="turn.answer.content" class="qa__text">{{ turn.answer.content }}</p>
                <ul v-if="turn.answer.citations?.length" class="cites">
                  <li v-for="c in turn.answer.citations" :key="c.recordCode" class="cite" :class="{ 'cite--sub': !c.isPrimary }">
                    <span class="cite__code">{{ c.recordCode }}</span>
                    <StatusBadge :status="c.trustStatusAtAnswer" />
                    <span class="cite__source">{{ c.sourceName }}</span>
                    <span class="cite__date">{{ c.occurredOn }}</span>
                    <span v-if="!c.isPrimary" class="cite__tag">보조 표시</span>
                    <button v-if="c.recordId" class="link" type="button" @click="router.push(`/records/${c.recordId}`)">원문 열람</button>
                  </li>
                </ul>
                <dl v-if="turn.answer.answerStatus === 'NO_EVIDENCE'" class="suggest">
                  <dt>확인할 사람</dt><dd>{{ turn.answer.suggestedContacts ?? '--' }}</dd>
                  <dt>확인할 문서</dt><dd>{{ turn.answer.suggestedDocuments ?? '--' }}</dd>
                </dl>
              </div>
            </li>
          </ol>
          <button v-if="historyCursor" class="link" type="button" @click="loadHistory(true)">이후 대화 더 보기</button>

          <div v-if="pending" class="qa__pending" aria-live="polite">
            <p class="qa__question">{{ pending.question }}</p>
            <p v-if="pending.failure" class="state state--failed">{{ pending.failure }}</p>
            <template v-else>
              <p class="state state--loading">
                {{ pending.draft.length ? '검증된 문장을 받는 중입니다. 저장이 끝나면 확정됩니다.' : '확인된 기록에서 근거를 찾는 중입니다…' }}
              </p>
              <p v-if="pending.draft.length" class="qa__draft">{{ pending.draft.join(' ') }}</p>
              <p v-if="pending.citations.length" class="qa__candidates">
                근거 후보 {{ pending.citations.map((c) => c.recordCode).join(', ') }}
              </p>
            </template>
          </div>

          <form class="qa__form" @submit.prevent="ask">
            <input v-model="question" type="text" aria-label="질문" maxlength="2000" placeholder="예: 최종 오픈일은 언제이고 왜 변경됐나요?" :disabled="asking" />
            <button class="btn btn--primary" type="submit" :disabled="asking || !question.trim()">질문</button>
          </form>
          <p class="qa__note">근거가 없으면 답을 만들지 않고 확인할 대상을 알려줍니다.</p>
        </template>
      </section>
    </template>

    <template v-if="activeItem?.evidence" #aside>
      <h2 class="aside__head">근거</h2>
      <p class="aside__code">{{ activeItem.evidence.code }}</p>
      <p class="aside__title">{{ activeItem.evidence.title }}</p>
      <StatusBadge :status="activeItem.evidence.trustStatus" />
      <dl class="aside__list">
        <dt>출처</dt><dd>{{ recordSourceName(activeItem.evidence) }}</dd>
        <dt>작성자</dt><dd>{{ activeItem.evidence.authorName }}</dd>
        <dt>발생일</dt><dd>{{ activeItem.evidence.occurredOn }}</dd>
        <dt>담당자</dt><dd>{{ activeItem.evidence.ownerName ?? '--' }}</dd>
        <dt>기한</dt><dd>{{ activeItem.evidence.dueOn ?? '--' }}</dd>
      </dl>
      <button class="link" type="button" @click="router.push(`/records/${activeItem.evidence.id}`)">원문 화면으로</button>
    </template>
  </AppShell>
</template>

<style scoped>
.title { margin: 0; font-size: 22px; }
.sub { margin: 4px 0 0; font-size: 12px; color: var(--grey-dark); }

.package { display: grid; grid-template-columns: 180px 1fr; gap: 14px; align-items: start; margin-bottom: 16px; }
.menu { display: flex; flex-direction: column; gap: 2px; background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 8px; }
.menu__item { display: flex; align-items: center; justify-content: space-between; gap: 8px; border: none; background: none; padding: 8px 10px; border-radius: 6px; font-family: var(--font); font-size: 12px; color: var(--grey-dark); cursor: pointer; text-align: left; }
.menu__item--on { background: var(--navy); color: var(--white); font-weight: 600; }
.menu__count { font-size: 11px; opacity: 0.8; }

.content { display: flex; flex-direction: column; gap: 8px; }
.item { background: var(--white); border: 1px solid var(--line); border-left: 3px solid transparent; border-radius: var(--radius); padding: 12px 14px; cursor: pointer; }
.item--on { border-left-color: var(--orange); }
.item__title { margin: 0 0 4px; font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 8px; }
.item__required { font-size: 11px; color: var(--orange); font-weight: 500; }
.item__acked { font-size: 11px; color: var(--navy); font-weight: 500; }
.item__body { margin: 0 0 6px; font-size: 13px; color: var(--grey-dark); }
.item__meta { margin: 0; display: flex; gap: 12px; font-size: 11px; color: var(--grey); }

.qa { background: var(--white); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px 16px; }
.qa__head { margin: 0 0 10px; font-size: 13px; color: var(--grey-dark); }
.qa__log { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 12px; }
.qa__question { margin: 0 0 6px; font-size: 13px; font-weight: 600; }
.qa__answer { padding: 10px 12px; border-radius: 8px; background: var(--paper); }
.qa__answer--no_evidence { background: #fff3ec; }
.qa__answer--failed { background: #fff0e8; }
.qa__status { margin: 0 0 4px; font-size: 11px; font-weight: 700; color: var(--orange); }
.qa__answer--answered .qa__status { color: var(--navy); }
.qa__text { margin: 0; font-size: 13px; }

.cites { list-style: none; margin: 8px 0 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.cite { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.cite--sub { opacity: 0.75; }
.cite__code { font-weight: 600; }
.cite__source { color: var(--grey-dark); }
.cite__date { color: var(--grey); }
.cite__tag { font-size: 11px; color: var(--orange); }

.suggest { display: grid; grid-template-columns: auto 1fr; gap: 2px 10px; margin: 8px 0 0; font-size: 12px; }
.suggest dt { color: var(--grey); }
.suggest dd { margin: 0; }

.qa__form { display: flex; gap: 8px; margin-top: 12px; }
.qa__form input { flex: 1; border: 1px solid var(--line); border-radius: 6px; padding: 8px 10px; font-family: var(--font); font-size: 13px; }
.qa__pending { margin-top: 12px; padding: 10px 12px; border: 1px dashed var(--line); border-radius: 8px; }
.qa__draft { margin: 0; font-size: 13px; color: var(--grey-dark); font-style: italic; }
.qa__candidates { margin: 6px 0 0; font-size: 11px; color: var(--grey); }
.qa__note { margin: 6px 0 0; font-size: 11px; color: var(--grey); }

.aside__head { margin: 0 0 8px; font-size: 13px; color: var(--grey-dark); }
.aside__code { margin: 0; font-size: 11px; color: var(--grey); }
.aside__title { margin: 2px 0 8px; font-size: 14px; font-weight: 600; }
.aside__list { display: grid; grid-template-columns: auto 1fr; gap: 4px 10px; margin: 10px 0; font-size: 12px; }
.aside__list dt { color: var(--grey); }
.aside__list dd { margin: 0; }

.link { border: none; background: none; padding: 0; color: var(--orange); font-family: var(--font); font-size: 12px; cursor: pointer; text-decoration: underline; }
.btn { height: 32px; padding: 0 14px; border: 1px solid var(--line); border-radius: 6px; background: var(--white); color: var(--navy); font-family: var(--font); font-size: 13px; cursor: pointer; }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.btn--primary { background: var(--orange); border-color: var(--orange); color: var(--white); font-weight: 600; }
.btn--sm { height: 26px; padding: 0 10px; margin-top: 8px; font-size: 12px; }
</style>
