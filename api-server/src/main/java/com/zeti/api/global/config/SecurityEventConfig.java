package com.zeti.api.global.config;

import com.zeti.api.securityevent.application.ApiSecurityEventRecorder;
import com.zeti.api.securityevent.application.RouteCatalog;
import com.zeti.api.securityevent.presentation.AccessDecisionAuditFilter;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityEventConfig {

    /**
     * Spring Security 체인(-100)보다 바깥(-101)에 둔다. 인증 실패 401까지 포함한 최종 status를 보고,
     * 요청 처리(업무 트랜잭션 포함)가 모두 끝난 뒤 감사를 저장하기 위해서다.
     * RequestContextFilter(-105)는 이보다 먼저 실행된다.
     */
    @Bean
    public FilterRegistrationBean<AccessDecisionAuditFilter> accessDecisionAuditFilter(
            RouteCatalog routeCatalog, ApiSecurityEventRecorder recorder) {
        FilterRegistrationBean<AccessDecisionAuditFilter> registration =
                new FilterRegistrationBean<>(new AccessDecisionAuditFilter(routeCatalog, recorder));
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 1);
        return registration;
    }
}
