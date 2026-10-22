import { ApiProblem } from '@/api/http'
import type { AdminRepository } from '@/api/repositories/admin'
import type { Project, ProjectMember, User } from '@/api/types'
import { requireAdmin, userName } from '../access'
import { DEMO_PROJECT_ID } from '../scenario'
import { clone, fail, page, type DemoState } from '../state'

/**
 * 데모 시스템 관리. 서버처럼 아무도 참조하지 않는 행만 지우고, 참조가 있으면 409 REFERENCE_CONFLICT로 막는
 * 참조를 알린다. 마지막 활성 시스템 관리자는 비활성화·강등할 수 없다.
 */

function patchRow<T extends { id: number; version: number }>(rows: T[], id: number, version: number, patch: Partial<T>): T {
  const row = rows.find((r) => r.id === id) ?? fail(404, 'NOT_FOUND')
  if (row.version !== version) fail(412, 'STALE_VERSION')
  Object.assign(row, patch)
  row.version += 1
  return clone(row)
}

function deleteRow(rows: { id: number; version: number }[], id: number, version: number, refs: [string, number][]): void {
  const row = rows.find((r) => r.id === id) ?? fail(404, 'NOT_FOUND')
  if (row.version !== version) fail(412, 'STALE_VERSION')
  const references = refs.filter(([, count]) => count > 0).map(([resource, count]) => ({ resource, count }))
  if (references.length > 0) throw new ApiProblem({ status: 409, code: 'REFERENCE_CONFLICT', traceId: 'demo', references })
  rows.splice(rows.indexOf(row), 1)
}

export function demoAdminRepository(state: DemoState): AdminRepository {
  const admin = async () => {
    await state.pause()
    requireAdmin(state)
  }
  const recordsTouchedBy = (name: string | undefined) =>
    state.records.filter((r) => r.confirmedByName === name || r.authorName === name).length

  return {
    listOrganizations: async (range) => {
      await admin()
      return page(state.organizations.map((o) => ({ ...clone(o), userCount: state.users.filter((u) => u.organizationId === o.id).length })), range)
    },
    createOrganization: async (name) => {
      await admin()
      if (state.organizations.some((o) => o.name === name)) fail(409, 'DUPLICATE_ORGANIZATION_NAME')
      const created = { id: state.nextId(), version: 0, name, isActive: true }
      state.organizations.push(created)
      return clone(created)
    },
    updateOrganization: async (organizationId, version, patch) => {
      await admin()
      return patchRow(state.organizations, organizationId, version, patch)
    },
    deleteOrganization: async (organizationId, version) => {
      await admin()
      deleteRow(state.organizations, organizationId, version,
        [['users', state.users.filter((u) => u.organizationId === organizationId).length]])
    },
    listUsers: async (range) => {
      await admin()
      return page(clone(state.users), range)
    },
    createUser: async (input) => {
      await admin()
      const organization = state.organizations.find((o) => o.id === input.organizationId) ?? fail(400, 'INVALID_INPUT')
      const created: User = {
        id: state.nextId(), version: 0, ...input, systemRole: input.systemRole ?? 'USER', isActive: true,
        organizationName: organization.name, authProvider: 'LOCAL', loginEnabled: false,
      }
      state.users.push(created)
      return clone(created)
    },
    issueCredentialToken: async (userId) => {
      await admin()
      const target = state.users.find((u) => u.id === userId) ?? fail(404, 'USER_NOT_FOUND')
      if (!target.isActive || target.authProvider !== 'LOCAL') fail(409, 'INVALID_STATE')
      // 서버와 같다: 시스템 관리자 계정에는 설정 코드를 발급하지 않는다(복구는 운영자). 서버는 발급 뒤 관리자가 된 계정의 코드도 거절한다.
      if (target.systemRole === 'SYSTEM_ADMIN') fail(409, 'INVALID_STATE')
      const token = `demo-${state.nextId()}-${Math.random().toString(36).slice(2, 12)}`
      state.setupCodes.set(userId, token)
      return { token, expiresAt: new Date(state.options.now() + 24 * 60 * 60 * 1000).toISOString() }
    },
    updateUser: async (userId, version, patch) => {
      await admin()
      const target = state.users.find((u) => u.id === userId)
      const removesAdmin = target?.systemRole === 'SYSTEM_ADMIN' && (patch.isActive === false || patch.systemRole === 'USER')
      if (removesAdmin && state.users.filter((u) => u.systemRole === 'SYSTEM_ADMIN' && u.isActive).length === 1) {
        fail(409, 'LAST_SYSTEM_ADMIN')
      }
      return patchRow(state.users, userId, version, patch)
    },
    deleteUser: async (userId, version) => {
      await admin()
      const name = state.users.find((u) => u.id === userId)?.name
      deleteRow(state.users, userId, version, [
        ['project_members', state.members.filter((m) => m.userId === userId).length],
        ['work_records', recordsTouchedBy(name)],
        ['audit_events', state.actors.has(userId) ? 1 : 0],
      ])
    },
    listAllProjects: async (range) => {
      await admin()
      return page(clone(state.projects), range)
    },
    createProject: async (input) => {
      await admin()
      const created: Project = { id: state.nextId(), version: 0, ...input, isActive: true }
      state.projects.push(created)
      return clone(created)
    },
    updateProject: async (projectId, version, patch) => {
      await admin()
      return patchRow(state.projects, projectId, version, patch)
    },
    deleteProject: async (projectId, version) => {
      await admin()
      deleteRow(state.projects, projectId, version, [
        ['project_members', projectId === DEMO_PROJECT_ID ? state.members.length : 0],
        ['work_records', projectId === DEMO_PROJECT_ID ? state.records.length : 0],
      ])
    },
    listProjectMembers: async (projectId) => {
      await admin()
      return page(projectId === DEMO_PROJECT_ID ? clone(state.members) : [])
    },
    addProjectMember: async (projectId, input) => {
      await admin()
      if (projectId !== DEMO_PROJECT_ID) fail(404, 'PROJECT_NOT_FOUND')
      const user = state.users.find((u) => u.id === input.userId) ?? fail(400, 'INVALID_INPUT')
      if (state.members.some((m) => m.userId === input.userId)) fail(409, 'INVALID_STATE')
      const created: ProjectMember = { id: state.nextId(), version: 0, userId: user.id, userName: user.name, userTitle: user.title,
        projectRole: input.projectRole, joinedOn: input.joinedOn, leftOn: null }
      state.members.push(created)
      return clone(created)
    },
    updateProjectMember: async (projectId, memberId, version, patch) => {
      await admin()
      if (projectId !== DEMO_PROJECT_ID) fail(404, 'PROJECT_NOT_FOUND')
      return patchRow(state.members, memberId, version, patch)
    },
    deleteProjectMember: async (projectId, memberId, version) => {
      await admin()
      if (projectId !== DEMO_PROJECT_ID) fail(404, 'PROJECT_NOT_FOUND')
      const member = state.members.find((m) => m.id === memberId)
      deleteRow(state.members, memberId, version, [['work_records', recordsTouchedBy(member ? userName(state, member.userId) : '')]])
    },
  }
}
