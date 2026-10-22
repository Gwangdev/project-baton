package com.projectbaton.project;

import java.util.List;

import com.projectbaton.identity.SystemAdminAccessPolicy;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
    private static final int DASHBOARD_CARDS = 20;

    private final ProjectQueryService projects;
    private final SystemAdminAccessPolicy systemAdminAccess;

    public DashboardService(ProjectQueryService projects, SystemAdminAccessPolicy systemAdminAccess) {
        this.projects = projects;
        this.systemAdminAccess = systemAdminAccess;
    }

    public DashboardSummary summary(long actorId) {
        List<ProjectQueryService.ConnectionHealth> connectionHealth = systemAdminAccess.isSystemAdmin(actorId)
                ? projects.allConnectionHealth()
                : projects.memberConnectionHealth(actorId);
        // 요약 합계와 카드를 따로 계산하면 대시보드에서 가장 비싼 문장을 60 RPS에서 두 번 돌리게
        // 되어서(evidence/performance.md), 둘 다 공개 범위 규칙을 한 번 평가한 결과에서 얻는다.
        List<ProjectQueryService.ProjectDetail> accessible = projects.dashboardProjects(actorId, DASHBOARD_CARDS);
        long pending = accessible.stream().mapToLong(ProjectQueryService.ProjectDetail::pendingRecordCount).sum();
        return new DashboardSummary(accessible.size(), pending, projects.activeAssignedHandoverCount(actorId), 0,
                accessible.subList(0, Math.min(DASHBOARD_CARDS, accessible.size())), connectionHealth);
    }

    public record DashboardSummary(long projectCount, long pendingRecordCount, long activeHandoverCount,
                                   long failedJobCount, List<ProjectQueryService.ProjectDetail> projects,
                                   List<ProjectQueryService.ConnectionHealth> connectionHealth) { }
}
