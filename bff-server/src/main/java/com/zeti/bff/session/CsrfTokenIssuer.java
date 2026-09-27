package com.zeti.bff.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.stereotype.Component;

/** 로그인 직후 새 세션에 CSRF 토큰을 발급한다. 응답 값은 Spring Security 기본과 같은 XOR 마스킹 형태다. */
@Component
public class CsrfTokenIssuer {

    private final CsrfTokenRepository repository;
    private final XorCsrfTokenRequestAttributeHandler handler = new XorCsrfTokenRequestAttributeHandler();

    public CsrfTokenIssuer(CsrfTokenRepository repository) {
        this.repository = repository;
    }

    /** 호출 전에 세션이 있어야 한다. */
    public String issue(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = repository.generateToken(request);
        repository.saveToken(token, request, response);
        handler.handle(request, response, () -> token);
        CsrfToken masked = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return masked.getToken();
    }
}
