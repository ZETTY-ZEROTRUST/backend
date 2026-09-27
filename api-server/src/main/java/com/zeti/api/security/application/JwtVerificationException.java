package com.zeti.api.security.application;

import com.zeti.api.securityevent.application.SecurityEvent.AuthnReason;

/**
 * JWT 검증 실패와 그 사유(C-02 authn reason). 기존 호출자와 같게 {@link IllegalArgumentException}으로 취급된다.
 * 메시지·사유에 토큰 원문을 넣지 않는다.
 */
public class JwtVerificationException extends IllegalArgumentException {

    private final AuthnReason reason;

    public JwtVerificationException(AuthnReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public JwtVerificationException(AuthnReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public AuthnReason reason() {
        return reason;
    }
}
