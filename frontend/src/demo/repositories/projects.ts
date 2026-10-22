import type { ProjectRepository } from '@/api/repositories/projects'
import { currentMembers, requireMember, requireUser, roleOf } from '../access'
import { isParticipant } from '../handoverLifecycle'
import { connectionSummary, projectDetail, visibleConnections } from '../views'
import { clone, page, type DemoState } from '../state'

/** 데모 대시보드와 프로젝트 조회. 숫자는 보는 사람에게 보이는 범위에서 센다. */
export function demoProjectRepository(state: DemoState): ProjectRepository {
  return {
    getDashboard: async () => {
      await state.pause()
      const userId = requireUser(state)
      const projects = state.projects.filter((p) => roleOf(state, userId, p.id)).map((p) => projectDetail(state, p, userId))
      const visible = visibleConnections(state, userId)
      const h = state.handover
      return {
        projectCount: projects.length,
        pendingRecordCount: projects.reduce((n, p) => n + p.pendingRecordCount, 0),
        activeHandoverCount: h && isParticipant(state, userId) && h.detail.status !== 'COMPLETED' ? 1 : 0,
        failedJobCount: visible.filter((c) => c.health === 'FAILED').length,
        projects,
        connectionHealth: visible.map(connectionSummary),
      }
    },
    getProject: async (projectId) => {
      await state.pause()
      const userId = requireMember(state, projectId)
      return projectDetail(state, state.projects.find((p) => p.id === projectId)!, userId)
    },
    listCurrentProjectMembers: async (projectId) => {
      await state.pause()
      requireMember(state, projectId)
      return page(clone(currentMembers(state)))
    },
  }
}
