<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRepository } from '@/api/repositoryProvider'
import { errorLine } from '@/api/problemText'

/**
 * S01의 한 경로: 관리자에게서 받은 설정 코드로 비밀번호를 정한다(README §4.1).
 *
 * 처음 정하는 경우와 분실 뒤 재설정이 같은 화면이다. 코드가 맞는지, 비밀번호 규칙을 지켰는지는 서버가 판단한다. 이
 * 화면은 두 비밀번호가 같은지만 먼저 본다. 틀린 코드·없는 계정은 서버가 같은 답을 주므로 화면도 구분하지 않는다.
 * 성공해도 로그인되지 않는다. 비밀번호를 정한 사람이 그 비밀번호로 직접 로그인한다.
 */
const repository = useRepository()

const employeeNo = ref('')
const token = ref('')
const password = ref('')
const confirm = ref('')
const busy = ref(false)
const error = ref('')
const done = ref(false)

const mismatch = computed(() => confirm.value !== '' && password.value !== confirm.value)
const canSubmit = computed(() =>
  employeeNo.value.trim() !== '' && token.value.trim() !== '' && password.value !== '' && password.value === confirm.value && !busy.value)

async function submit() {
  if (!canSubmit.value) return
  busy.value = true
  error.value = ''
  try {
    await repository.setCredential({ employeeNo: employeeNo.value.trim(), token: token.value.trim(), newPassword: password.value })
    done.value = true
  } catch (e) {
    error.value = errorLine(e)
    password.value = ''
    confirm.value = ''
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="setup">
    <section class="panel">
      <p class="brand">Project Baton</p>
      <h1 class="title">설정 코드로 비밀번호 정하기</h1>

      <template v-if="done">
        <p class="lead" role="status">비밀번호를 정했습니다. 이 비밀번호로 로그인하십시오.</p>
        <a class="link" href="/login">로그인 화면으로</a>
      </template>

      <template v-else>
        <p class="lead">관리자에게서 받은 설정 코드와 사내 계정을 입력하고 새 비밀번호를 정합니다. 코드는 한 번만 쓸 수 있습니다.</p>
        <form class="form" @submit.prevent="submit">
          <label class="field">
            <span>사내 계정</span>
            <input v-model="employeeNo" type="text" aria-label="사내 계정" autocomplete="username" :disabled="busy" />
          </label>
          <label class="field">
            <span>설정 코드</span>
            <input v-model="token" type="text" aria-label="설정 코드" autocomplete="off" spellcheck="false" :disabled="busy" />
          </label>
          <label class="field">
            <span>새 비밀번호 (12자 이상)</span>
            <input v-model="password" type="password" aria-label="새 비밀번호" autocomplete="new-password" :disabled="busy" />
          </label>
          <label class="field">
            <span>새 비밀번호 확인</span>
            <input v-model="confirm" type="password" aria-label="새 비밀번호 확인" autocomplete="new-password" :disabled="busy" />
          </label>
          <p v-if="mismatch" class="error" role="status">두 비밀번호가 서로 다릅니다.</p>
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <button class="submit" type="submit" :disabled="!canSubmit">{{ busy ? '확인 중…' : '비밀번호 정하기' }}</button>
        </form>
        <a class="link" href="/login">로그인 화면으로</a>
      </template>
    </section>
  </main>
</template>

<style scoped>
.setup { min-height: 100vh; display: grid; place-items: center; background: var(--navy); padding: 40px 20px; }
.panel { width: 520px; background: var(--white); border-radius: 14px; padding: 34px 38px; }
.brand { margin: 0; font-size: 13px; font-weight: 700; color: var(--orange); letter-spacing: 0.3px; }
.title { margin: 10px 0 12px; font-size: 22px; line-height: 1.4; }
.lead { margin: 0 0 22px; font-size: 13px; color: var(--grey-dark); line-height: 1.7; }
.form { display: flex; flex-direction: column; gap: 12px; }
.field { display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: var(--grey-dark); }
.field input {
  border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px;
  font-family: var(--font); font-size: 14px; color: var(--navy); background: var(--white);
}
.field input:focus { outline: none; border-color: var(--navy); }
.field input:disabled { background: var(--paper); }
.error { margin: 0; padding: 9px 12px; background: #fff3ec; border-left: 3px solid var(--orange); border-radius: 6px; font-size: 12px; color: var(--grey-dark); }
.submit {
  height: 42px; border: none; border-radius: 8px; background: var(--orange);
  color: var(--white); font-family: var(--font); font-size: 15px; font-weight: 700; cursor: pointer;
}
.submit:disabled { opacity: 0.45; cursor: not-allowed; }
.link { display: inline-block; margin-top: 16px; font-size: 12px; color: var(--navy); }
</style>
