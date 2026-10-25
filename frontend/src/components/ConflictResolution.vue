<script setup lang="ts">
import { ref, watch } from 'vue'
import RelationWithdrawal from './RelationWithdrawal.vue'
import StatusBadge from './StatusBadge.vue'
import { relationOfItem } from '@/api/conflicts'
import { errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import type { HandoverItem, RecordDetail, RecordRelation } from '@/api/types'

/**
 * 현재 패키지 세대의 상충 기록 (FR-12).
 *
 * 시스템은 유효한 기록을 스스로 고르지 않는다. 관리자가 항목의 후보 기록 중 하나를 사유와 함께 고르고, 서버는
 * 그 선택이 이번 세대의 후보인지와 항목 version이 화면에서 본 것과 같은지(If-Match)를 확인한다. 후보는 ID로 읽어
 * 요약을 나란히 비교할 수 있게 한다. 대체(SUPERSEDES) 쌍은 서버가 대체한 기록으로만 해소를 받으므로, 대체된 옛 기록은
 * 고를 수 없게 표시한다. 예전에는 두 후보를 똑같이 보여 주어 옛 기록을 고르면 이유 없는 거절만 보였다.
 * 관계가 잘못 등록됐으면 PM이 사유와 함께 철회한다. 게시된 세대는 바뀌지 않으므로 철회된 쌍은 고를 수 없고, 패키지를
 * 다시 만들면 그 쌍 없이 만들어진다.
 */
const props = defineProps<{ handoverId: number; items: HandoverItem[]; relations?: RecordRelation[] }>()

/** 이 항목의 충돌이 대체 쌍이면 대체된(옛) 기록의 ID. */
function replacedIn(item: HandoverItem): number | null {
  const relation = relationOfItem(props.relations, item)
  return relation?.relationType === 'SUPERSEDES' ? relation.toRecordId : null
}

function withdrawnIn(item: HandoverItem): RecordRelation | null {
  const relation = relationOfItem(props.relations, item)
  return relation?.withdrawnAt ? relation : null
}
const emit = defineEmits<{ resolved: [] }>()
const repository = useRepository()

const candidates = ref<Record<number, RecordDetail>>({})
const notes = ref<Record<number, string>>({})
const busy = ref(false)
const error = ref('')

watch(
  () => props.items.flatMap((i) => i.candidateRecordIds ?? []),
  async (ids) => {
    const missing = [...new Set(ids)].filter((id) => !candidates.value[id])
    // 후보끼리는 서로 기다릴 이유가 없으므로 한꺼번에 읽는다. 볼 수 없는 후보도 ID로는 고를 수 있다.
    // 허용 여부는 서버가 판단한다.
    const results = await Promise.allSettled(missing.map((id) => repository.getRecord(id)))
    const loaded = { ...candidates.value }
    results.forEach((r, i) => { if (r.status === 'fulfilled') loaded[missing[i]] = r.value })
    candidates.value = loaded
  },
  { immediate: true },
)

async function resolve(item: HandoverItem, recordId: number) {
  busy.value = true
  error.value = ''
  try {
    const note = notes.value[item.id]?.trim() || '관리자가 최종 근거로 선택'
    await repository.resolveHandoverItem(props.handoverId, item.id, item.version, recordId, note)
    emit('resolved')
  } catch (e) {
    error.value = errorLine(e)
    emit('resolved')
  } finally {
    busy.value = false
  }
}

</script>

<template>
  <div class="conflicts">
    <p v-if="items.length === 0" class="hint">상충하는 기록이 없습니다.</p>
    <article v-for="item in items" :key="item.id" class="conflict">
      <p class="conflict__title">{{ item.title }}</p>
      <div class="conflict__options">
        <label v-for="id in item.candidateRecordIds ?? []" :key="id" class="option">
          <input
            type="radio"
            :name="`conflict-${item.id}`"
            :value="id"
            :aria-label="`${candidates[id]?.code ?? id} 최종 근거로 선택`"
            :checked="item.resolvedByRecordCode === candidates[id]?.code"
            :disabled="busy || !!item.resolvedByRecordCode || replacedIn(item) === id || !!withdrawnIn(item)"
            @change="resolve(item, id)"
          />
          <span class="option__body">
            <span class="option__code">{{ candidates[id]?.code ?? `기록 ${id}` }}</span>
            <StatusBadge v-if="candidates[id]" :status="candidates[id].trustStatus" />
            <span class="option__summary">{{ candidates[id]?.summary ?? '내용을 볼 수 없는 기록' }}</span>
            <span class="option__meta">{{ candidates[id]?.occurredOn }}</span>
            <span v-if="replacedIn(item) === id" class="option__meta">대체된 기록이라 최종 근거로 고를 수 없습니다</span>
          </span>
        </label>
      </div>
      <p v-if="item.resolvedByRecordCode" class="conflict__resolved">
        최종 근거 {{ item.resolvedByRecordCode }} — {{ item.resolutionNote }}
      </p>
      <p v-else-if="withdrawnIn(item)" class="conflict__withdrawn">
        철회된 관계 — {{ withdrawnIn(item)?.withdrawalReason }}. 패키지를 다시 만들어야 합니다.
      </p>
      <template v-else>
        <input
          v-model="notes[item.id]"
          class="reason-input"
          type="text"
          maxlength="300"
          aria-label="선택 사유"
          placeholder="선택 사유 (선택 시 함께 기록됨)"
        />
        <RelationWithdrawal
          :relation="relationOfItem(relations, item)"
          :disabled="busy"
          @pending="busy = $event"
          @done="emit('resolved')"
        />
      </template>
    </article>
    <p v-if="error" class="state state--failed">{{ error }}</p>
  </div>
</template>

<style scoped>
.hint { margin: 0; font-size: 12px; color: var(--grey); }
.conflict { border: 1px solid var(--line); border-radius: 8px; padding: 12px; margin-bottom: 10px; }
.conflict__title { margin: 0 0 8px; font-weight: 600; font-size: 13px; }
.conflict__options { display: flex; flex-direction: column; gap: 8px; }
.option { display: flex; gap: 8px; align-items: flex-start; font-size: 12px; cursor: pointer; }
.option__body { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.option__code { font-weight: 700; color: var(--navy); }
.option__summary { flex-basis: 100%; color: var(--grey-dark); }
.option__meta { color: var(--grey); font-size: 11px; }
.conflict__resolved { margin: 8px 0 0; font-size: 12px; color: var(--orange); }
.conflict__withdrawn { margin: 8px 0 0; font-size: 12px; color: var(--grey-dark); }
</style>
