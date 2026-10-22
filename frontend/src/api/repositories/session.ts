import type { HttpClient } from '../http'
import type { CurrentUser, SetCredentialInput } from '../types'

/** 로그인·로그아웃과 현재 사용자 (FR-01, C01 쿠키 세션). */
export interface SessionRepository {
  getCurrentUser(): Promise<CurrentUser>
  signIn(employeeNo: string, password: string): Promise<CurrentUser>
  signOut(): Promise<void>
  /** 관리자에게서 받은 설정 코드로 비밀번호를 정한다(로그인 전 호출). 성공해도 로그인되지 않는다. */
  setCredential(input: SetCredentialInput): Promise<void>
}

export function apiSessionRepository(http: HttpClient): SessionRepository {
  return {
    getCurrentUser: () => http.get<CurrentUser>('/me'),
    signIn: (employeeNo, password) => http.signIn<CurrentUser>({ employeeNo, password }),
    signOut: () => http.signOut(),
    setCredential: (input) => http.command<void>('POST', '/auth/credentials', { body: input, idempotent: false }),
  }
}
