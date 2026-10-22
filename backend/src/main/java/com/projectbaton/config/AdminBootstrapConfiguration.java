package com.projectbaton.config;

import com.projectbaton.audit.AuditService;
import com.projectbaton.identity.AdminBootstrap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** BATON_BOOTSTRAP_ADMIN_* 환경변수를 묶는다(application.yml의 baton.bootstrap.admin). */
@Configuration
public class AdminBootstrapConfiguration {
    @Bean
    AdminBootstrap adminBootstrap(JdbcTemplate jdbc, PasswordEncoder passwords, TransactionTemplate transactions, AuditService audit,
                                  @Value("${baton.bootstrap.admin.employee-no:}") String employeeNo,
                                  @Value("${baton.bootstrap.admin.password:}") String password,
                                  @Value("${baton.bootstrap.admin.name:}") String name,
                                  @Value("${baton.bootstrap.admin.email:}") String email,
                                  @Value("${baton.bootstrap.admin.organization:}") String organization,
                                  @Value("${baton.bootstrap.admin.reset:false}") boolean reset,
                                  @Value("${baton.auth.password-min-length:12}") int minPasswordLength) {
        return new AdminBootstrap(jdbc, passwords, transactions, audit,
                new AdminBootstrap.Settings(employeeNo, password, name, email, organization, reset), minPasswordLength);
    }
}
