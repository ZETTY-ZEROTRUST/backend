package com.zeti.bff.login;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.Optional;

/**
 * Auth가 방금 발급해 서버 간 응답으로 받은 AT에서 {@code sub}(userId)만 읽는다.
 *
 * <p>서명은 검증하지 않는다: 이 값은 BFF 세션·vault 행의 소유자 표시용이며 인가 근거가 아니다.
 * API는 매 요청 JWT 서명·상태를 독립적으로 검증한다. 브라우저가 준 토큰에는 이 메서드를 쓰지 않는다.
 */
final class AccessTokenSubject {

    private static final int MAX_PAYLOAD_CHARS = 16 * 1024;

    private AccessTokenSubject() {
    }

    static Optional<Long> userIdOf(String accessToken, ObjectMapper objectMapper) {
        try {
            String[] parts = accessToken.split("\\.", -1);
            if (parts.length != 3 || parts[1].length() > MAX_PAYLOAD_CHARS) {
                return Optional.empty();
            }
            JsonNode sub = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1])).get("sub");
            if (sub == null || !sub.isTextual()) {
                return Optional.empty();
            }
            return Optional.of(Long.parseLong(sub.asText()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
