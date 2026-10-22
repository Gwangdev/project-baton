import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiProblem } from '@/api/http'
import { useRepository } from '@/api/repositoryProvider'
import type { AccountActivity, CurrentUser, ProjectMembership } from '@/api/types'

/**
 * 로그인한 사용자와 화면이 보고 있는 프로젝트.
 *
 * 세션 자체는 서버의 HttpOnly 쿠키이고, 이 store는 `GET /me`를 비춰 둘 뿐이다. 사용자가 무엇을 할 수 있는지는
 * 여기서 판단하지 않는다. `memberships`의 역할은 표시와 다음 행동 안내에만 쓰고, 권한의 결과는 실제 호출에 대한
 * 서버의 401/403/404로 정해진다 (README §4.2).
 *
 * HTTP 클라이언트는 어떤 401이든 `expire()`를 부른다. 그래서 어디서 세션이 만료돼도 로그인 화면으로 돌아간다.
 */
export const useSessionStore = defineStore('session', () => {
  const current = ref<CurrentUser | null>(null)
  const restored = ref(false)
  const selectedProjectId = ref<number | null>(null)
  /** 마지막 세션이 끝난 이유. 직접 로그아웃했다면 다음 사용자를 이전 화면으로 되돌리지 않는다. */
  const endReason = ref<'signed-out' | 'expired' | null>(null)
  let restoring: Promise<void> | null = null

  const isAuthenticated = computed(() => current.value !== null)
  const user = computed(() => current.value?.user ?? null)
  const memberships = computed<ProjectMembership[]>(() => current.value?.memberships ?? [])
  const accountActivity = computed<AccountActivity[]>(() => current.value?.accountActivity ?? [])
  const isSystemAdmin = computed(() => user.value?.systemRole === 'SYSTEM_ADMIN')
  const currentProjectId = computed<number | null>(() => {
    const selected = selectedProjectId.value
    if (selected !== null && memberships.value.some((m) => m.projectId === selected)) return selected
    return memberships.value[0]?.projectId ?? null
  })
  const currentMembership = computed(
    () => memberships.value.find((m) => m.projectId === currentProjectId.value) ?? null,
  )

  /** 페이지를 열 때 한 번 쿠키 세션을 읽는다. 401은 단지 로그인하지 않았다는 뜻이다. */
  async function restore(): Promise<void> {
    if (restored.value) return
    // 401이 아닌 실패(네트워크, 5xx)는 로그인 여부를 알 수 없는 상태다. 복원된 것으로 표시하지 않아야 다음
    // 이동에서 다시 묻는다. 그렇지 않으면 유효한 쿠키 세션이 있어도 새로 고칠 때까지 로그아웃으로 취급된다.
    restoring ??= (async () => {
      try {
        current.value = await useRepository().getCurrentUser()
        restored.value = true
      } catch (error) {
        if (!(error instanceof ApiProblem && error.status === 401)) throw error
        current.value = null
        restored.value = true
      } finally {
        restoring = null
      }
    })()
    await restoring
  }

  async function signIn(employeeNo: string, password: string): Promise<void> {
    current.value = await useRepository().signIn(employeeNo.trim(), password)
    selectedProjectId.value = null
    restored.value = true
    endReason.value = null
  }

  /**
   * 서버 세션을 먼저 끝낸다. 실패하면 로그인 상태를 유지하고 오류를 보여준다. 화면 상태만 지우면 로그아웃한
   * 것처럼 보이지만, 공용 PC에서는 쿠키 세션이 그대로 살아 있기 때문이다.
   */
  async function signOut(): Promise<void> {
    try {
      await useRepository().signOut()
    } catch (error) {
      // 401은 서버에 끝낼 세션이 이미 없다는 뜻이다. 만료된 세션의 요청은 인증 필터가 먼저 401로 막으므로 OAS의
      // signOut도 204와 함께 401을 선언한다. 어느 쪽이든 세션은 끝났으므로 실패로 보이지 않는다.
      if (!(error instanceof ApiProblem && error.status === 401)) throw error
    }
    expire()
    endReason.value = 'signed-out'
  }

  function expire(): void {
    if (current.value !== null) endReason.value = 'expired'
    current.value = null
    selectedProjectId.value = null
  }

  function selectProject(projectId: number): void {
    selectedProjectId.value = projectId
  }

  return {
    current,
    restored,
    endReason,
    isAuthenticated,
    user,
    memberships,
    accountActivity,
    isSystemAdmin,
    currentProjectId,
    currentMembership,
    restore,
    signIn,
    signOut,
    expire,
    selectProject,
  }
})
