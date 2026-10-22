import type { HttpClient } from '../http'
import type { DashboardSummary, Page, ProjectDetail, ProjectMember } from '../types'
import { readAll } from './listLimit'

/** 대시보드와 프로젝트 조회. 숫자는 서버가 권한으로 걸러 센 값이다. */
export interface ProjectRepository {
  getDashboard(): Promise<DashboardSummary>
  getProject(projectId: number): Promise<ProjectDetail>
  /** 그 프로젝트의 현재 구성원. 같은 프로젝트의 현재 구성원만 읽는다(관리자용 목록과 달리 종료된 배정은 없다). */
  listCurrentProjectMembers(projectId: number): Promise<Page<ProjectMember>>
}

export function apiProjectRepository(http: HttpClient): ProjectRepository {
  return {
    getDashboard: () => http.get<DashboardSummary>('/dashboard'),
    getProject: (projectId) => http.get<ProjectDetail>(`/projects/${projectId}`),
    listCurrentProjectMembers: (projectId) =>
      readAll((query) => http.get<Page<ProjectMember>>(`/projects/${projectId}/members`, { ...query })),
  }
}
