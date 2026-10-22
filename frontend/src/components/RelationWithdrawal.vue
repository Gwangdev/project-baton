<script setup lang="ts">
import { ref } from 'vue'
import { errorLine } from '@/api/problemText'
import { useRepository } from '@/api/repositoryProvider'
import type { RecordRelation } from '@/api/types'

/**
 * 잘못 등록한 기록 관계의 철회 (PM 전용).
 *
 * 관계는 기록 상세에서 등록하고, 대체·상충 관계는 인수인계 충돌 화면에도 보인다. 철회가 충돌 화면에만 있으면 참조
 * 관계나 패키지 밖의 관계는 화면에서 되돌릴 수 없었으므로, 두 화면이 이 한 곳을 쓴다. 서버는 사유와 관계 version
 * (If-Match)을 받고, 행은 철회 사유와 함께 남긴다. 실패 문구는 이 부품이 보이고 다음 시도에서 지운다. 요청 중에는
 * `pending`으로 알려 부모가 같은 대상의 다른 명령(충돌 해소 등)을 막게 하고, 끝나면 성공·실패와 상관없이 부모가
 * 다시 읽도록 `done`을 알린다.
 */
const props = defineProps<{ relation: Pick<RecordRelation, 'id' | 'version'> | null | undefined; disabled?: boolean }>()
const emit = defineEmits<{ done: []; pending: [busy: boolean] }>()
const repository = useRepository()

const open = ref(false)
const reason = ref('')
const busy = ref(false)
const error = ref('')

async function withdraw() {
  if (!props.relation || !reason.value.trim()) return
  busy.value = true
  error.value = ''
  emit('pending', true)
  try {
    await repository.withdrawRecordRelation(props.relation.id, props.relation.version, reason.value.trim())
    open.value = false
    reason.value = ''
  } catch (e) {
    error.value = errorLine(e)
  } finally {
    busy.value = false
    emit('pending', false)
    emit('done')
  }
}
</script>

<template>
  <div v-if="open" class="withdraw">
    <input
      v-model="reason"
      class="withdraw__reason"
      type="text"
      maxlength="300"
      aria-label="철회 사유"
      placeholder="관계가 잘못 등록된 이유"
    />
    <button class="link" type="button" :disabled="busy || !reason.trim()" @click="withdraw">철회 확정</button>
    <button class="link" type="button" :disabled="busy" @click="open = false">취소</button>
  </div>
  <button v-else class="link" type="button" :disabled="disabled || busy || !relation" @click="open = true; reason = ''">
    관계 철회
  </button>
  <p v-if="error" class="state state--failed">{{ error }}</p>
</template>

<style scoped>
.withdraw { display: flex; gap: 8px; align-items: center; }
.withdraw__reason {
  margin-top: 8px; width: 100%; height: 30px; padding: 0 8px; border: 1px solid var(--line); border-radius: 6px;
  font-family: var(--font); font-size: 12px;
}
.link { border: none; background: none; padding: 0; margin-top: 6px; color: var(--orange); font-family: var(--font); font-size: 12px; cursor: pointer; text-decoration: underline; }
.link:disabled { color: var(--grey); cursor: default; text-decoration: none; }
</style>
