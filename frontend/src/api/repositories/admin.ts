import type { HttpClient } from '../http'
import type {
  CredentialToken,
  Organization,
  Page,
  Project,
  ProjectCreateInput,
  ProjectMember,
  ProjectMemberInput,
  ProjectRole,
  ProjectStatus,
  SystemRole,
  User,
  UserCreateInput,
} from '../types'
import { type PageQuery, readAll } from './listLimit'

export interface OrganizationPatch { name?: string; isActive?: boolean }
export interface UserPatch { name?: string; title?: string; organizationId?: number; systemRole?: SystemRole; isActive?: boolean }
export interface ProjectPatch { name?: string; phase?: string; status?: ProjectStatus; isActive?: boolean }
export interface ProjectMemberPatch { projectRole?: ProjectRole; leftOn?: string }

/** 시스템 관리의 조직·사용자·프로젝트·구성원 배정 (FR-18). 모두 시스템 관리자 전용이다. */
export interface AdminRepository {
  /** page가 없으면 끝까지 읽는다(선택 목록용). 있으면 그 한 페이지만 읽는다(표용). */
  listOrganizations(page?: PageQuery): Promise<Page<Organization>>
  createOrganization(name: string): Promise<Organization>
  updateOrganization(organizationId: number, version: number, patch: OrganizationPatch): Promise<Organization>
  deleteOrganization(organizationId: number, version: number): Promise<void>
  listUsers(page?: PageQuery): Promise<Page<User>>
  createUser(input: UserCreateInput): Promise<User>
  /** 활성 LOCAL 계정에 일회용 설정 코드를 발급한다. 코드는 이 응답에서만 볼 수 있고 다시 발급하면 이전 코드는 무효다. */
  issueCredentialToken(userId: number): Promise<CredentialToken>
  updateUser(userId: number, version: number, patch: UserPatch): Promise<User>
  deleteUser(userId: number, version: number): Promise<void>
  listAllProjects(page?: PageQuery): Promise<Page<Project>>
  createProject(input: ProjectCreateInput): Promise<Project>
  updateProject(projectId: number, version: number, patch: ProjectPatch): Promise<Project>
  deleteProject(projectId: number, version: number): Promise<void>
  /** 프로젝트의 배정 전체를 페이지를 이어 읽어 돌려준다. */
  listProjectMembers(projectId: number): Promise<Page<ProjectMember>>
  addProjectMember(projectId: number, input: ProjectMemberInput): Promise<ProjectMember>
  updateProjectMember(projectId: number, memberId: number, version: number, patch: ProjectMemberPatch): Promise<ProjectMember>
  deleteProjectMember(projectId: number, memberId: number, version: number): Promise<void>
}

export function apiAdminRepository(http: HttpClient): AdminRepository {
  return {
    listOrganizations: (page) => pageOrAll(page, (query) => http.get<Page<Organization>>('/admin/organizations', { ...query })),
    createOrganization: (name) => http.command<Organization>('POST', '/admin/organizations', { body: { name } }),
    updateOrganization: (organizationId, version, patch) =>
      http.command<Organization>('PATCH', `/admin/organizations/${organizationId}`, { body: patch, version }),
    deleteOrganization: (organizationId, version) =>
      http.command<void>('DELETE', `/admin/organizations/${organizationId}`, { version }),
    listUsers: (page) => pageOrAll(page, (query) => http.get<Page<User>>('/admin/users', { ...query })),
    createUser: (input) => http.command<User>('POST', '/admin/users', { body: input }),
    issueCredentialToken: (userId) =>
      http.command<CredentialToken>('POST', `/admin/users/${userId}/credential-tokens`, { idempotent: false }),
    updateUser: (userId, version, patch) => http.command<User>('PATCH', `/admin/users/${userId}`, { body: patch, version }),
    deleteUser: (userId, version) => http.command<void>('DELETE', `/admin/users/${userId}`, { version }),
    listAllProjects: (page) => pageOrAll(page, (query) => http.get<Page<Project>>('/admin/projects', { ...query })),
    createProject: (input) => http.command<Project>('POST', '/admin/projects', { body: input }),
    updateProject: (projectId, version, patch) =>
      http.command<Project>('PATCH', `/admin/projects/${projectId}`, { body: patch, version }),
    deleteProject: (projectId, version) => http.command<void>('DELETE', `/admin/projects/${projectId}`, { version }),
    listProjectMembers: (projectId) =>
      readAll((query) => http.get<Page<ProjectMember>>(`/admin/projects/${projectId}/members`, { ...query })),
    addProjectMember: (projectId, input) =>
      http.command<ProjectMember>('POST', `/admin/projects/${projectId}/members`, { body: input }),
    updateProjectMember: (projectId, memberId, version, patch) =>
      http.command<ProjectMember>('PATCH', `/admin/projects/${projectId}/members/${memberId}`, { body: patch, version }),
    deleteProjectMember: (projectId, memberId, version) =>
      http.command<void>('DELETE', `/admin/projects/${projectId}/members/${memberId}`, { version }),
  }
}

function pageOrAll<T>(page: PageQuery | undefined, fetch: (query: PageQuery) => Promise<Page<T>>): Promise<Page<T>> {
  return page ? fetch(page) : readAll(fetch)
}
