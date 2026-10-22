<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { currentDataMode } from '@/api/repositoryProvider'
import { errorLine } from '@/api/problemText'
import { safeRedirect } from '@/router'
import { useSessionStore } from '@/stores/session'

/**
 * S01 로그인.
 *
 * 자격증명 확인과 세션 쿠키 발급은 서버가 한다(C01). 이 화면은 제출하고 답을 설명할 뿐이다. 서버는 없는 계정과
 * 틀린 비밀번호에 일부러 같은 401을 주므로 화면도 둘을 구분하지 않는다. 가상 데모 계정 안내는 src/demo의 별도
 * 컴포넌트이며 demo 빌드에서만 불러온다. api 빌드에는 계정도 비밀번호도 들어가지 않는다.
 */
const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const isDemo = currentDataMode() === 'demo'

const employeeNo = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

const canSubmit = computed(() => employeeNo.value.trim() !== '' && password.value !== '' && !busy.value)
const notice = computed(() => {
  if (route.query.unavailable) return '서버에 연결할 수 없어 로그인 상태를 확인하지 못했습니다.'
  if (route.query.expired) return '세션이 만료되었습니다. 다시 로그인하십시오.'
  return ''
})
// 공개 데모 배포(work-orders D7)는 누구나 열고 시연 계정은 저장소 README에 있다. 방문자에게 데이터가 합성이고 매일
// 초기화되어 입력한 것이 남지 않는다는 것, 그리고 실제 개인정보를 넣으면 안 된다는 것을 알린다. 이 배포는 그 정보의
// 주인이 처리에 동의한 시스템이 아니다. 로그인은 접속 주소를 감사 행에 남기므로(AuthController) 그 사실과 보관 기간도
// 함께 알린다.
const isPublicDemo = import.meta.env.VITE_PUBLIC_DEMO === 'true'
const DemoAccountGuide = import.meta.env.VITE_DATA_MODE === 'demo'
  ? defineAsyncComponent(() => import('@/demo/DemoAccountGuide.vue'))
  : null

async function submit() {
  if (!canSubmit.value) return
  busy.value = true
  error.value = ''
  try {
    await session.signIn(employeeNo.value, password.value)
    await router.push(safeRedirect(route.query.redirect))
  } catch (e) {
    error.value = errorLine(e)
    password.value = ''
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <main class="login">
    <section class="panel">
      <p class="brand">Project Baton</p>
      <h1 class="title">담당자가 바뀌어도<br />프로젝트 맥락은 끊기지 않는다</h1>
      <p class="lead">
        사내 계정으로 로그인하면 자신에게 권한이 부여된 프로젝트만 보입니다.
      </p>

      <p v-if="isPublicDemo" class="public-demo">
        합성 데이터로 만든 공개 데모입니다. 데이터는 매일 04:00(KST)에 처음 상태로 돌아갑니다.
        실제 개인정보나 회사 자료를 입력하지 마십시오. 로그인하면 접속 주소(IP)가 감사 기록에 남고, 이 기록도
        매일 04:00에 지워집니다. 시연 계정은 저장소 README에 있습니다.
      </p>

      <p v-if="notice" class="error" role="status">{{ notice }}</p>

      <form class="form" @submit.prevent="submit">
        <label class="field">
          <span>사내 계정</span>
          <input
            v-model="employeeNo"
            type="text"
            aria-label="사내 계정"
            placeholder="예: A-10421"
            autocomplete="username"
            :disabled="busy"
          />
        </label>
        <label class="field">
          <span>비밀번호</span>
          <input
            v-model="password"
            type="password"
            aria-label="비밀번호"
            placeholder="비밀번호"
            autocomplete="current-password"
            :disabled="busy"
          />
        </label>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <button class="submit" type="submit" :disabled="!canSubmit">
          {{ busy ? '확인 중…' : '로그인' }}
        </button>
      </form>

      <p class="setup-link">
        <a href="/credentials">관리자에게서 설정 코드를 받았다면 비밀번호 정하기</a>
      </p>

      <component :is="DemoAccountGuide" v-if="DemoAccountGuide" />

      <p v-if="isDemo" class="note">본 화면의 데이터는 시연을 위한 가상 정보입니다.</p>
    </section>
  </main>
</template>

<style scoped>
.login { min-height: 100vh; display: grid; place-items: center; background: var(--navy); padding: 40px 20px; }
.panel { width: 520px; background: var(--white); border-radius: 14px; padding: 34px 38px; }
.brand { margin: 0; font-size: 13px; font-weight: 700; color: var(--orange); letter-spacing: 0.3px; }
.title { margin: 10px 0 12px; font-size: 25px; line-height: 1.4; }
.lead { margin: 0 0 22px; font-size: 13px; color: var(--grey-dark); line-height: 1.7; }

.form { display: flex; flex-direction: column; gap: 12px; }
.field { display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: var(--grey-dark); }
.field input {
  border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px;
  font-family: var(--font); font-size: 14px; color: var(--navy); background: var(--white);
}
.field input:focus { outline: none; border-color: var(--navy); }
.field input:disabled { background: var(--paper); }

.public-demo { margin: 0; padding: 9px 12px; background: var(--navy-soft); border-left: 3px solid var(--navy); border-radius: 6px; font-size: 12px; color: var(--grey-dark); }
.error { margin: 0; padding: 9px 12px; background: #fff3ec; border-left: 3px solid var(--orange); border-radius: 6px; font-size: 12px; color: var(--grey-dark); }

.submit {
  height: 42px; border: none; border-radius: 8px; background: var(--orange);
  color: var(--white); font-family: var(--font); font-size: 15px; font-weight: 700; cursor: pointer;
}
.submit:disabled { opacity: 0.45; cursor: not-allowed; }


.setup-link { margin: 14px 0 0; font-size: 12px; }
.setup-link a { color: var(--navy); }

.note { margin: 16px 0 0; font-size: 11px; color: var(--grey); }
</style>
