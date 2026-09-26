package com.zeti.api.security.presentation;

import com.nimbusds.jwt.JWTClaimsSet;
import com.zeti.api.security.application.AuthStateChecker;
import com.zeti.api.security.application.AuthStateCache;
import com.zeti.api.security.application.LedgerVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.zeti.api.security.application.JwtVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtVerifier jwtVerifier;
    private final AuthStateChecker authStateChecker;
    private final LedgerVerifier ledgerVerifier;
    private final AuthStateCache authStateCache;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length());
        try {
            JWTClaimsSet claims = jwtVerifier.verify(token);
            Long userId = Long.parseLong(claims.getSubject());

            // 매 요청 원본 상태 확인: authVersion 불일치(로그아웃·권한 회수)면 거부.
            Object authvClaim = claims.getClaim("authv");
            if (!(authvClaim instanceof Number)) {
                SecurityContextHolder.clearContext();
                chain.doFilter(request, response);
                return;
            }
            int authVersion = ((Number) authvClaim).intValue();
            String jti = claims.getJWTID();
            String digest = sha256Hex(token);

            // positive cache 적중 시 DB 조회(authVersion+대장)를 건너뛴다.
            if (!authStateCache.isValidated(jti, userId, authVersion, digest)) {
                // 원본 확인: 매 요청 authVersion 대조 + 발급 대장 digest 대조.
                if (!authStateChecker.isCurrent(userId, authVersion)
                        || !ledgerVerifier.isIssued(token, jti, userId)) {
                    SecurityContextHolder.clearContext();
                    chain.doFilter(request, response);
                    return;
                }
                authStateCache.store(jti, userId, authVersion, digest);
            }

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            userId, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(auth);

        } catch (Exception e) {
            log.debug("JWT 인증 실패: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
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
