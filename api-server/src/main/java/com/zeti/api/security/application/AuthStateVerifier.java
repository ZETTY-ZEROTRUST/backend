package com.zeti.api.security.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 매 요청 원본 상태 확인을 단일 쿼리로 수행한다(왕복 1회).
 * token_ledger를 users에 join해 발급 증명(digest·ACTIVE·sub)과 authVersion을 한 번에 검증한다.
 * 이전에는 authVersion 조회 + 발급대장 조회로 2회였다(요청당 DB 왕복 절반).
 */
@Component
@RequiredArgsConstructor
public class AuthStateVerifier {

    private final JdbcTemplate jdbcTemplate;

    public boolean verify(String compactToken, String jti, Long sub, int claimAuthVersion) {
        if (jti == null) {
            return false;
        }
        var rows = jdbcTemplate.queryForList(
                "SELECT l.digest AS digest, l.status AS status, l.sub AS sub, u.auth_version AS authv "
                        + "FROM token_ledger l JOIN users u ON u.user_id = l.sub WHERE l.jti = ?", jti);
        if (rows.size() != 1) {
            return false;
        }
        Map<String, Object> row = rows.get(0);
        if (!"ACTIVE".equals(String.valueOf(row.get("status")))) {
            return false;
        }
        if (!sub.equals(((Number) row.get("sub")).longValue())) {
            return false;
        }
        if (((Number) row.get("authv")).intValue() != claimAuthVersion) {
            return false;
        }
        return MessageDigest.isEqual(
                String.valueOf(row.get("digest")).getBytes(StandardCharsets.UTF_8),
                sha256Hex(compactToken).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
