package com.edp.iot.deadletter;

import com.edp.iot.common.security.ApiKeys;
import com.edp.iot.common.security.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/** Dead letters can hold any tenant's events, so the whole API is for operator keys only. */
@Configuration
class SecurityConfig implements WebMvcConfigurer {

    static final String PRINCIPAL_ATTRIBUTE = "iot.principal";

    private final JdbcTemplate jdbc;

    SecurityConfig(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Bean
    ApiKeys apiKeys() {
        return new ApiKeys(jdbc, Duration.ofSeconds(30));
    }

    @Bean
    AuditLog auditLog() {
        return new AuditLog(jdbc);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        ApiKeys keys = apiKeys();
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                String header = request.getHeader("Authorization");
                String presented = header != null && header.startsWith("ApiKey ") ? header.substring(7).trim() : null;
                var principal = keys.authenticate(presented);
                if (principal.isEmpty() || !principal.get().isOperator()) {
                    response.setStatus(principal.isEmpty() ? HttpServletResponse.SC_UNAUTHORIZED : HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write(principal.isEmpty() ? "{\"code\":\"UNAUTHENTICATED\"}" : "{\"code\":\"OPERATOR_KEY_REQUIRED\"}");
                    return false;
                }
                request.setAttribute(PRINCIPAL_ATTRIBUTE, principal.get());
                return true;
            }
        }).addPathPatterns("/v1/**");
    }
}
