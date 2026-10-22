package com.projectbaton.project;

import com.projectbaton.identity.CurrentActor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    public DashboardService.DashboardSummary summary(@AuthenticationPrincipal CurrentActor actor) {
        return dashboard.summary(actor.userId());
    }
}
