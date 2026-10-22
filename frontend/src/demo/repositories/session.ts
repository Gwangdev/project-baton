import { ApiProblem } from '@/api/http'
import type { SessionRepository } from '@/api/repositories/session'
import { currentUser, requireUser } from '../access'
import { DEMO_PASSWORDS } from '../demoCredentials'
import { fail, type DemoState } from '../state'

/** 서버의 비밀번호 규칙(12~128자, 사내 계정과 다름)을 그대로 따른다. */
const invalidPassword = () => new ApiProblem({
  status: 400, code: 'INVALID_INPUT', traceId: 'demo', errors: [{ field: 'newPassword', message: 'invalid' }],
})

/** 데모 로그인. 가상 계정은 demoCredentials.ts의 비밀번호, 설정 코드로 정한 계정은 그 비밀번호로만 들어간다. */
export function demoSessionRepository(state: DemoState): SessionRepository {
  return {
    getCurrentUser: async () => {
      await state.pause()
      return currentUser(state, requireUser(state))
    },
    signIn: async (employeeNo, password) => {
      await state.pause()
      // C01이 서버에 요구하는 대로, 없는 계정과 틀린 비밀번호에 같은 답을 준다.
      const expected = state.passwords[employeeNo] ?? DEMO_PASSWORDS[employeeNo]
      const user = state.users.find((u) => u.employeeNo === employeeNo)
      if (!user || expected === undefined || expected !== password) fail(401, 'INVALID_CREDENTIALS')
      if (!user!.isActive) fail(403, 'ACCOUNT_INACTIVE')
      state.signedIn = user!.id
      state.actors.add(user!.id)
      return currentUser(state, user!.id)
    },
    signOut: async () => {
      await state.pause()
      state.signedIn = null
    },
    setCredential: async ({ employeeNo, token, newPassword }) => {
      await state.pause()
      if (newPassword.length < 12 || newPassword.length > 128 || newPassword === employeeNo) throw invalidPassword()
      const user = state.users.find((u) => u.employeeNo === employeeNo)
      // 없는 계정·틀린 코드·이미 쓴 코드는 서버처럼 같은 답이다.
      if (!user || !user.isActive || state.setupCodes.get(user.id) !== token) fail(400, 'INVALID_CREDENTIAL_TOKEN')
      state.setupCodes.delete(user!.id)
      state.passwords[employeeNo] = newPassword
      user!.loginEnabled = true
      user!.version += 1
    },
  }
}
