<script setup lang="ts">
import { computed } from 'vue'
import type { RecordTrustStatus } from '@/api/types'

/**
 * 기록 신뢰 상태 배지.
 *
 * 색은 tokens.css의 상태 토큰만 쓴다. 여기서 색을 직접 정하면 화면마다
 * 같은 상태가 다른 색으로 보인다. 「확정된 기록과 미확정 기록을 한눈에
 * 구분한다」가 이 서비스의 주장이라 그 구분이 흔들리면 안 된다.
 */
const props = defineProps<{ status: RecordTrustStatus }>()

const LABEL: Record<RecordTrustStatus, string> = {
  AUTO_DRAFT: '확인 대기',
  CONFIRMED: '확인됨',
  CORRECTED: '수정 확정',
  DISMISSED: '제외됨',
}

const label = computed(() => LABEL[props.status])
</script>

<template>
  <span class="badge" :class="`badge--${status.toLowerCase().replace('_', '-')}`">{{ label }}</span>
</template>

<style scoped>
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}
.badge--auto-draft { background: #fff0e8; color: var(--status-auto-draft); border: 1px solid var(--status-auto-draft); }
.badge--confirmed { background: var(--status-confirmed); color: var(--white); }
.badge--corrected { background: var(--status-corrected); color: var(--white); }
.badge--dismissed { background: var(--line); color: var(--status-dismissed); }
</style>
