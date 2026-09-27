package com.zeti.bff.session;

import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Optional;

/**
 * BFF 세션 attribute 계약. 세션(Redis)에는 아래 세 값과 CSRF 토큰만 둔다.
 * AT/RT 원문은 절대 넣지 않는다 — 원문은 vault 암호문으로만 존재한다.
 */
public final class BffSession {

    public static final String USER_ID = "zetty.bff.userId";
    public static final String VAULT_REF = "zetty.bff.vaultRef";
    public static final String AUTHENTICATED_AT = "zetty.bff.authenticatedAt";
    public static final String CSRF_TOKEN = "zetty.bff.csrf";

    private BffSession() {
    }

    public static void bind(HttpSession session, long userId, String vaultRef, Instant authenticatedAt) {
        session.setAttribute(USER_ID, userId);
        session.setAttribute(VAULT_REF, vaultRef);
        session.setAttribute(AUTHENTICATED_AT, authenticatedAt.toEpochMilli());
    }

    public static Optional<BffPrincipal> principal(HttpSession session) {
        if (session.getAttribute(USER_ID) instanceof Long userId
                && session.getAttribute(VAULT_REF) instanceof String vaultRef
                && session.getAttribute(AUTHENTICATED_AT) instanceof Long authenticatedAt) {
            return Optional.of(new BffPrincipal(userId, vaultRef, Instant.ofEpochMilli(authenticatedAt)));
        }
        return Optional.empty();
    }

    public static Optional<String> vaultRef(HttpSession session) {
        return session.getAttribute(VAULT_REF) instanceof String ref ? Optional.of(ref) : Optional.empty();
    }
}
