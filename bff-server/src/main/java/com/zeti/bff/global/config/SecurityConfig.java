package com.zeti.bff.global.config;

import com.zeti.bff.global.web.JsonErrors;
import com.zeti.bff.session.BffSessionAuthenticationFilter;
import com.zeti.bff.session.OriginCheckFilter;
import com.zeti.bff.session.SessionBoundCsrfTokenRepository;
import com.zeti.bff.vault.TokenVault;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * BFF 쿠키 세션 체인(stateful). CSRF를 끄지 않는다.
 * <ul>
 *   <li>Origin 정확 일치: 모든 unsafe 메서드(로그인 포함)</li>
 *   <li>CSRF 토큰({@code X-CSRF-Token}): 로그인을 제외한 unsafe 메서드 — 로그인 전에는 세션·토큰이 없으므로 Origin으로 막는다</li>
 *   <li>SecurityContext는 세션에 저장하지 않는다. 세션 attribute(userId·vault 참조)로 요청마다 만든다</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public CsrfTokenRepository bffCsrfTokenRepository() {
        return new SessionBoundCsrfTokenRepository();
    }

    @Bean
    public SecurityFilterChain bffFilterChain(HttpSecurity http, BffProperties properties,
            CsrfTokenRepository csrfTokenRepository, TokenVault vault, Clock clock) throws Exception {
        http
                .securityContext(sc -> sc.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                // 세션은 로그인 핸들러만 만든다. Spring Security 쪽 생성 경로(SecurityContext 저장·request cache·
                // CSRF 저장소)는 모두 막았다. sessionManagement는 설정하지 않는다: sessionCreationPolicy 등을 지정하면
                // SessionManagementFilter가 추가되어, 요청마다 세션 attribute로 만든 인증을 "새 로그인"으로 보고
                // 세션 ID·CSRF 토큰을 매 요청 교체한다(세션 고정 방어는 /bff/login에서 직접 수행).
                // 인증 실패 시 원 요청을 세션에 저장하는 기본 동작 제거(익명 세션 생성 방지)
                .requestCache(AbstractHttpConfigurer::disable)
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/bff/login")))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(new OriginCheckFilter(properties.allowedOrigin()), CsrfFilter.class)
                .addFilterBefore(new BffSessionAuthenticationFilter(vault, clock, properties.sessionAbsoluteTimeout()),
                        AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/bff/login").permitAll()
                        .requestMatchers("/bff/session", "/bff/logout", "/bff/api/**").authenticated()
                        .requestMatchers("/error").permitAll()
                        // management 포트(9090)에도 이 체인이 적용된다.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                JsonErrors.write(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated"))
                        .accessDeniedHandler((request, response, e) ->
                                JsonErrors.write(response, HttpServletResponse.SC_FORBIDDEN,
                                        e instanceof CsrfException ? "csrf_invalid" : "forbidden")));
        return http.build();
    }
}
