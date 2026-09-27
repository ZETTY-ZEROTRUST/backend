package com.zeti.bff.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * CSRF 토큰을 세션에 저장하되, 세션이 없으면 만들지 않는다.
 * 기본 HttpSessionCsrfTokenRepository는 익명 unsafe 요청에서도 새 세션을 만들어
 * Redis에 빈 세션이 쌓인다. 세션은 로그인만 만든다.
 */
public class SessionBoundCsrfTokenRepository implements CsrfTokenRepository {

    public static final String HEADER_NAME = "X-CSRF-Token";

    private final HttpSessionCsrfTokenRepository delegate = new HttpSessionCsrfTokenRepository();

    public SessionBoundCsrfTokenRepository() {
        delegate.setHeaderName(HEADER_NAME);
        delegate.setSessionAttributeName(BffSession.CSRF_TOKEN);
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return delegate.generateToken(request);
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        if (request.getSession(false) == null) {
            return; // 세션 없는 요청: 저장하지 않음 → 요청 토큰과 일치할 수 없어 거부된다.
        }
        delegate.saveToken(token, request, response);
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        return delegate.loadToken(request);
    }
}
