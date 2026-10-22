import type { HttpClient } from '../http'
import type { AssessmentAttempt, AssessmentDetail, SubmittedAnswer } from '../types'

/** 업무 준비도 문항과 제출 (FR-17, C08). 채점과 정답 키는 서버에만 있다. */
export interface AssessmentRepository {
  getAssessment(handoverId: number): Promise<AssessmentDetail>
  submitAssessmentAttempt(handoverId: number, answers: SubmittedAnswer[]): Promise<AssessmentAttempt>
}

export function apiAssessmentRepository(http: HttpClient): AssessmentRepository {
  return {
    getAssessment: (handoverId) => http.get<AssessmentDetail>(`/handovers/${handoverId}/assessment`),
    submitAssessmentAttempt: (handoverId, answers) =>
      http.command<AssessmentAttempt>('POST', `/handovers/${handoverId}/assessment/attempts`, { body: { answers } }),
  }
}
