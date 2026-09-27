package com.zeti.bff.session;

import com.zeti.bff.global.web.JsonErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 상태 변경 메서드는 {@code Origin}이 설정값과 정확히 같아야 한다(없거나 다르면 403).
 * CSRF 토큰 검사와 별개의 층이며, 토큰이 아직 없는 {@code /bff/login}의 로그인 CSRF도 이 검사로 막는다.
 * Bean으로 등록하지 않는다 — SecurityConfig에서 CsrfFilter 앞에 추가.
 */
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OriginCheckFilter.class);
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final String allowedOrigin;

    public OriginCheckFilter(String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (!allowedOrigin.equals(origin)) {
            log.info("Origin 불일치로 거부 method={} path={} originPresent={}",
                    request.getMethod(), request.getRequestURI(), origin != null);
            JsonErrors.write(response, HttpServletResponse.SC_FORBIDDEN, "origin_mismatch");
            return;
        }
        chain.doFilter(request, response);
    }
}
