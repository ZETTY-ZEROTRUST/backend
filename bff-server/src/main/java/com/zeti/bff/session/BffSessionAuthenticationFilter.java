package com.zeti.bff.session;

import com.zeti.bff.vault.TokenVault;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 기존 세션 attribute로 요청 단위 인증 객체를 만든다. 세션을 새로 만들지 않는다.
 * 로그인 시각 기준 절대 만료가 지나면 vault 행과 세션을 폐기한다(유휴 만료는 Spring Session TTL).
 * Bean으로 등록하지 않는다(서블릿 필터로 중복 등록 방지) — SecurityConfig에서 체인에만 추가.
 */
public class BffSessionAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BffSessionAuthenticationFilter.class);
    private static final List<SimpleGrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private final TokenVault vault;
    private final Clock clock;
    private final Duration absoluteTimeout;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public BffSessionAuthenticationFilter(TokenVault vault, Clock clock, Duration absoluteTimeout) {
        this.vault = vault;
        this.clock = clock;
        this.absoluteTimeout = absoluteTimeout;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Optional<BffPrincipal> principal = session == null ? Optional.empty() : BffSession.principal(session);
        if (principal.isPresent()) {
            BffPrincipal p = principal.get();
            if (clock.instant().isAfter(p.authenticatedAt().plus(absoluteTimeout))) {
                expire(session, p);
            } else {
                SecurityContext context = contextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(p, null, AUTHORITIES));
                contextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }

    private void expire(HttpSession session, BffPrincipal p) {
        try {
            vault.delete(p.vaultRef());
        } catch (DataAccessException e) {
            // 행은 VaultJanitor가 절대 만료 기준으로 다시 정리한다.
            log.warn("절대 만료 세션의 vault 삭제 실패 userId={}: {}", p.userId(), e.getClass().getSimpleName());
        }
        try {
            session.invalidate();
        } catch (IllegalStateException alreadyInvalidated) {
            // 동시 요청이 먼저 무효화했다.
        }
        log.info("BFF 세션 절대 만료로 종료 userId={}", p.userId());
    }
}
