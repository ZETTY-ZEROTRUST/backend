package com.zeti.api.security.presentation;

import com.nimbusds.jwt.JWTClaimsSet;
import com.zeti.api.security.application.AuthStateCache;
import com.zeti.api.security.application.AuthStateVerifier;
import com.zeti.api.security.application.JwtVerificationException;
import com.zeti.api.security.application.JwtVerifier;
import com.zeti.api.securityevent.application.AccessAudit;
import com.zeti.api.securityevent.application.AccessAudit.VerifiedIdentity;
import com.zeti.api.securityevent.application.SecurityEvent.AuthnReason;
import com.zeti.api.securityevent.application.SecurityEvent.IssuanceReason;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer JWT 인증 + 매 요청 원본 상태 확인. 인증에 실패하면 미인증으로 진행하고(보호 route는 entry point가 401),
 * 실패 사유는 감사 기록({@link AccessAudit})에만 남긴다. 응답 코드는 사유와 무관하게 이전과 같다.
 * 상태 저장소 장애도 이전과 같이 미인증(401)으로 끝나며, 감사에는 STATE_UNAVAILABLE로 구분해 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_SESSION_ID_LENGTH = 128;

    private final JwtVerifier jwtVerifier;
    private final AuthStateVerifier authStateVerifier;
    private final AuthStateCache authStateCache;

    /** 서명·claim 검증을 통과한 값(아직 발급대장 확인 전). */
    private record VerifiedClaims(long userId, int authVersion, String jti, String sessionId) {
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        AccessAudit audit = AccessAudit.forRequest(request);
        String header = request.getHeader("Authorization");
        if (header == null) {
            audit.authnFailed(AuthnReason.TOKEN_MISSING);
            chain.doFilter(request, response);
            return;
        }
        if (!header.startsWith(BEARER_PREFIX)) {
            // Bearer 이외의 방식은 받지 않는다(이전과 같이 미인증으로 진행).
            audit.authnFailed(AuthnReason.MALFORMED);
            chain.doFilter(request, response);
            return;
        }

        authenticate(header.substring(BEARER_PREFIX.length()), audit);
        chain.doFilter(request, response);
    }

    private void authenticate(String token, AccessAudit audit) {
        VerifiedClaims claims;
        try {
            claims = verifyClaims(token);
        } catch (JwtVerificationException e) {
            log.debug("JWT 인증 실패: {}", e.reason());
            audit.authnFailed(e.reason());
            SecurityContextHolder.clearContext();
            return;
        } catch (RuntimeException e) {
            log.debug("JWT 인증 실패: {}", e.getClass().getSimpleName());
            audit.authnFailed(AuthnReason.CLAIM_INVALID);
            SecurityContextHolder.clearContext();
            return;
        }
        audit.authnSucceeded();

        AuthStateVerifier.Result issuance;
        try {
            issuance = checkIssuance(token, claims);
        } catch (RuntimeException e) {
            // 원본 상태를 확인하지 못하면 허용하지 않는다(fail closed).
            log.debug("발급 상태 확인 실패: {}", e.getClass().getSimpleName());
            audit.issuanceUnavailable();
            SecurityContextHolder.clearContext();
            return;
        }
        if (issuance != AuthStateVerifier.Result.PASSED) {
            audit.issuanceFailed(IssuanceReason.valueOf(issuance.name()));
            SecurityContextHolder.clearContext();
            return;
        }

        audit.verified(new VerifiedIdentity(claims.userId(), claims.jti(), claims.sessionId()));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(claims.userId(), null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private VerifiedClaims verifyClaims(String token) {
        JWTClaimsSet claims = jwtVerifier.verify(token);
        long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new JwtVerificationException(AuthnReason.CLAIM_INVALID, "sub 형식 오류");
        }
        // 매 요청 원본 상태 확인: authVersion 클레임이 없으면 거부.
        if (!(claims.getClaim("authv") instanceof Number authv)) {
            throw new JwtVerificationException(AuthnReason.CLAIM_INVALID, "authv 없음");
        }
        String jti = claims.getJWTID();
        if (jti == null) {
            throw new JwtVerificationException(AuthnReason.CLAIM_INVALID, "jti 없음");
        }
        return new VerifiedClaims(userId, authv.intValue(), jti, sessionId(claims));
    }

    /**
     * 기본: 매 요청 단일 쿼리로 원본 확인(발급대장 digest+ACTIVE+sub+authVersion).
     * positive cache는 opt-in(기본 off). 켜졌고 적중하면 DB를 건너뛴다.
     */
    private AuthStateVerifier.Result checkIssuance(String token, VerifiedClaims claims) {
        String digest = sha256Hex(token);
        if (authStateCache.isValidated(claims.jti(), claims.userId(), claims.authVersion(), digest)) {
            return AuthStateVerifier.Result.PASSED;
        }
        AuthStateVerifier.Result result =
                authStateVerifier.verify(token, claims.jti(), claims.userId(), claims.authVersion());
        if (result == AuthStateVerifier.Result.PASSED) {
            authStateCache.store(claims.jti(), claims.userId(), claims.authVersion(), digest);
        }
        return result;
    }

    /** 서명 검증된 ext.LSID(로그인 상관 ID). 없거나 형식이 다르면 null(session_key null). */
    private static String sessionId(JWTClaimsSet claims) {
        try {
            Map<String, Object> ext = claims.getJSONObjectClaim("ext");
            Object lsid = ext == null ? null : ext.get("LSID");
            if (lsid instanceof String value && !value.isBlank() && value.length() <= MAX_SESSION_ID_LENGTH) {
                return value;
            }
            return null;
        } catch (ParseException e) {
            return null;
        }
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
