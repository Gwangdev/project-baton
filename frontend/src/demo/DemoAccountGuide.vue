<script setup lang="ts">
import { ref } from 'vue'
import { DEMO_PASSWORDS } from './demoCredentials'
import { ACCOUNTS } from './scenario'

/**
 * 1단계 발표용 로그인 안내. 계정은 가상이며 메모리 데모 저장소에만 있다.
 * 이 컴포넌트는 VITE_DATA_MODE=demo일 때만 불러온다.
 */
const showGuide = ref(false)
const GUIDE = ACCOUNTS.map((a) => ({
  role: a.roleLabel,
  name: `${a.user.name} ${a.user.title}`,
  id: a.user.employeeNo,
  pw: DEMO_PASSWORDS[a.user.employeeNo],
}))
</script>

<template>
  <div class="guide">
    <button class="guide__toggle" type="button" @click="showGuide = !showGuide">
      {{ showGuide ? '데모 계정 안내 접기' : '데모 계정 안내' }}
    </button>
    <table v-if="showGuide" class="guide__table">
      <thead><tr><th>역할</th><th>이름</th><th>사내 계정</th><th>비밀번호</th></tr></thead>
      <tbody>
        <tr v-for="g in GUIDE" :key="g.id">
          <td>{{ g.role }}</td><td>{{ g.name }}</td>
          <td><code>{{ g.id }}</code></td><td><code>{{ g.pw }}</code></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.guide { margin-top: 20px; padding-top: 16px; border-top: 1px solid var(--line); }
.guide__toggle { border: none; background: none; padding: 0; color: var(--grey-dark); font-family: var(--font); font-size: 12px; cursor: pointer; text-decoration: underline; }
.guide__table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 12px; }
.guide__table th { text-align: left; padding: 5px 6px; color: var(--grey); font-weight: 600; border-bottom: 1px solid var(--line); }
.guide__table td { padding: 6px; border-bottom: 1px solid var(--line); }
.guide__table code { font-family: ui-monospace, monospace; font-size: 12px; color: var(--navy); }
</style>
