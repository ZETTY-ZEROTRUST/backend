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
 *
 * <p>결과는 허용/거부만이 아니라 거부 사유를 돌려준다(감사 이벤트용). 허용 조건은 이전 boolean 판정과 같다:
 * PASSED ⇔ 대장 행 1건 ∧ sub 일치 ∧ digest 일치 ∧ ACTIVE ∧ authVersion 일치.
 * 조회 실패(DB 장애)는 예외로 전파되며 호출자가 fail-closed(STATE_UNAVAILABLE)로 처리한다.
 */
@Component
@RequiredArgsConstructor
public class AuthStateVerifier {

    public enum Result {
        PASSED,
        /** 대장에 없거나, 대장의 sub·digest가 제시된 토큰과 다름(발급하지 않은 토큰). */
        NOT_ISSUED,
        /** 발급한 토큰이지만 폐기됨. */
        REVOKED,
        /** 발급한 유효 토큰이지만 사용자 authVersion이 바뀜(로그아웃·권한 회수). */
        VERSION_MISMATCH
    }

    private final JdbcTemplate jdbcTemplate;

    public Result verify(String compactToken, String jti, Long sub, int claimAuthVersion) {
        if (jti == null) {
            return Result.NOT_ISSUED;
        }
        var rows = jdbcTemplate.queryForList(
                "SELECT l.digest AS digest, l.status AS status, l.sub AS sub, u.auth_version AS authv "
                        + "FROM token_ledger l JOIN users u ON u.user_id = l.sub WHERE l.jti = ?", jti);
        if (rows.size() != 1) {
            return Result.NOT_ISSUED;
        }
        Map<String, Object> row = rows.get(0);
        // 먼저 "우리가 발급한 바로 그 토큰인가"를 본다. 위조 토큰을 REVOKED 등으로 잘못 분류하지 않기 위해서다.
        boolean sameSubject = sub.equals(((Number) row.get("sub")).longValue());
        boolean sameDigest = MessageDigest.isEqual(
                String.valueOf(row.get("digest")).getBytes(StandardCharsets.UTF_8),
                sha256Hex(compactToken).getBytes(StandardCharsets.UTF_8));
        if (!sameSubject || !sameDigest) {
            return Result.NOT_ISSUED;
        }
        if (!"ACTIVE".equals(String.valueOf(row.get("status")))) {
            return Result.REVOKED;
        }
        if (((Number) row.get("authv")).intValue() != claimAuthVersion) {
            return Result.VERSION_MISMATCH;
        }
        return Result.PASSED;
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
