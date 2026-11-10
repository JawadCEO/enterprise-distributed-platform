package com.edp.iot.api;

import com.edp.iot.common.security.ApiKeys;
import com.edp.iot.common.security.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Every {@code /v1} call needs {@code Authorization: ApiKey <key>}. TLS is terminated in front of
 * the service (ingress in Kubernetes), so keys never cross the network in clear outside the cluster.
 */
@Configuration
class SecurityConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

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

    /** Seeds the first operator key from the environment so a fresh install can be administered. */
    @Bean
    ApplicationRunner bootstrapOperatorKey(ApiKeys keys, @Value("${iot.security.bootstrap-operator-key:}") String key) {
        return args -> {
            if (!key.isBlank()) {
                keys.insertIfAbsent(key, ApiKeys.Role.OPERATOR, null);
                log.info("Bootstrap operator key is registered");
            }
        };
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
                if (principal.isEmpty()) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"UNAUTHENTICATED\"}");
                    return false;
                }
                request.setAttribute(CallerTenant.PRINCIPAL_ATTRIBUTE, principal.get());
                return true;
            }
        }).addPathPatterns("/v1/**");
    }
}
