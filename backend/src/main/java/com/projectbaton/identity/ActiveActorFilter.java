package com.projectbaton.identity;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.projectbaton.shared.ApiProblemWriter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class ActiveActorFilter extends OncePerRequestFilter {

    private final LocalAuthenticationService authenticationService;

    ActiveActorFilter(LocalAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof UsernamePasswordAuthenticationToken
                && authentication.isAuthenticated() && authentication.getPrincipal() instanceof CurrentActor actor
                && !authenticationService.isActive(actor)) {
            if (request.getSession(false) != null) {
                request.getSession(false).invalidate();
            }
            SecurityContextHolder.clearContext();
            ApiProblemWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, "ACCOUNT_INACTIVE");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
