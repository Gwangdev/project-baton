package com.projectbaton.project;

import com.projectbaton.identity.CurrentActor;
import com.projectbaton.shared.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectQueryController {

    private final ProjectQueryService projects;
    private final ProjectRosterService roster;

    public ProjectQueryController(ProjectQueryService projects, ProjectRosterService roster) {
        this.projects = projects;
        this.roster = roster;
    }

    @GetMapping
    public ProjectQueryService.ProjectList list(@AuthenticationPrincipal CurrentActor actor,
                                                @RequestParam(defaultValue = "20") int limit,
                                                @RequestParam(defaultValue = "0") int offset) {
        return projects.list(actor.userId(), limit, offset);
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectQueryService.ProjectDetail> detail(@AuthenticationPrincipal CurrentActor actor,
                                                                     @PathVariable long projectId) {
        ProjectQueryService.ProjectDetail project = projects.detail(actor.userId(), projectId);
        return ResponseEntity.ok().eTag("\"" + project.version() + "\"").body(project);
    }

    @GetMapping("/{projectId}/members")
    public ProjectMemberService.MemberList members(@AuthenticationPrincipal CurrentActor actor, @PathVariable long projectId,
                                                   @RequestParam(required = false) Integer limit,
                                                   @RequestParam(required = false) Integer offset) {
        return roster.list(actor.userId(), projectId, PageRequest.of(limit, offset));
    }
}
