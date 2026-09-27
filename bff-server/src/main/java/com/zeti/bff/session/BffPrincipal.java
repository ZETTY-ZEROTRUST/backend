package com.zeti.bff.session;

import java.time.Instant;

/**
 * 로그인된 BFF 세션의 주체. 세션 attribute에서 요청마다 만든다(세션에 SecurityContext를 저장하지 않음).
 * vaultRef는 vault 행 조회 키이므로 toString에서 가린다.
 */
public record BffPrincipal(long userId, String vaultRef, Instant authenticatedAt) {

    @Override
    public String toString() {
        return "BffPrincipal[userId=" + userId + "]";
    }
}
