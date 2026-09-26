package com.zeti.api.security.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 발급 증명: 제시된 compact JWT의 digest가 대장에 존재·일치·ACTIVE·sub 일치해야 한다(S3).
 * 서명 키가 유출돼 유효 서명이어도, 실제 발급되지 않은 토큰은 대장에 없어 거부된다. */
@Component
@RequiredArgsConstructor
public class LedgerVerifier {

    private final JdbcTemplate jdbcTemplate;

    public boolean isIssued(String compactToken, String jti, Long sub) {
        if (jti == null) {
            return false;
        }
        var rows = jdbcTemplate.queryForList(
                "SELECT digest, sub, status FROM token_ledger WHERE jti = ?", jti);
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
        return constantTimeEquals(String.valueOf(row.get("digest")), sha256Hex(compactToken));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
