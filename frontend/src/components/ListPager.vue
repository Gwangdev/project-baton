<script setup lang="ts">
import { computed } from 'vue'

/** 표 아래의 이전·다음 페이지. offset만 바꾸고, 목록을 다시 읽는 일은 부모가 한다. */
const props = defineProps<{ total: number; offset: number; limit: number; label: string }>()
const emit = defineEmits<{ (e: 'update:offset', offset: number): void }>()

const first = computed(() => (props.total === 0 ? 0 : props.offset + 1))
const last = computed(() => Math.min(props.offset + props.limit, props.total))
</script>

<template>
  <nav class="pager" :aria-label="`${label} 페이지`">
    <span class="pager__range">{{ first }}–{{ last }} / {{ total }}</span>
    <button class="pager__btn" type="button" :disabled="offset === 0" :aria-label="`${label} 이전 페이지`"
            @click="emit('update:offset', Math.max(0, offset - limit))">이전</button>
    <button class="pager__btn" type="button" :disabled="offset + limit >= total" :aria-label="`${label} 다음 페이지`"
            @click="emit('update:offset', offset + limit)">다음</button>
  </nav>
</template>

<style scoped>
.pager { display: flex; justify-content: flex-end; align-items: center; gap: 6px; margin-top: 8px; font-size: 11px; color: var(--grey-dark); }
.pager__btn { height: 24px; padding: 0 10px; border: 1px solid var(--line); border-radius: 6px; background: var(--white); color: var(--navy); font-family: var(--font); font-size: 11px; cursor: pointer; }
.pager__btn:disabled { opacity: 0.45; cursor: not-allowed; }
</style>
