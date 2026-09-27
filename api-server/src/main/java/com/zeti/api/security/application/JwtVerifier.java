package com.zeti.api.security.application;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.zeti.api.security.infrastructure.jwks.JwksPublicKeyProvider;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import com.zeti.api.securityevent.application.SecurityEvent.AuthnReason;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JwtVerifier {

    private static final long CLOCK_SKEW_SECONDS = 30;
    private static final long MAX_LIFETIME_SECONDS = 900;
    private static final String EXPECTED_TYP = "at+jwt";

    private final JwksPublicKeyProvider publicKeyProvider;
    private final String expectedIssuer;
    private final String expectedAudience;

    public JwtVerifier(JwksPublicKeyProvider publicKeyProvider,
            @Value("${zetty.jwt.issuer:https://auth.zeti.com/}") String expectedIssuer,
            @Value("${zetty.jwt.audience:https://api.zeti.com}") String expectedAudience) {
        this.publicKeyProvider = publicKeyProvider;
        this.expectedIssuer = expectedIssuer;
        this.expectedAudience = expectedAudience;
    }

    /**
     * 서명·알고리즘·만료를 검증한다. 실패하면 사유가 붙은 {@link JwtVerificationException}을 던진다.
     * 검사 순서(파싱 → 알고리즘 → kid/공개키 → 서명 → 만료)와 허용 조건은 이전과 같다.
     */
    public JWTClaimsSet verify(String token) {
        SignedJWT signedJwt;
        try {
            signedJwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new JwtVerificationException(AuthnReason.MALFORMED, "JWT 파싱 실패", e);
        }

        // 1. 알고리즘 검증 (RS256만 허용)
        if (!JWSAlgorithm.RS256.equals(signedJwt.getHeader().getAlgorithm())) {
            throw new JwtVerificationException(AuthnReason.INVALID_SIGNATURE,
                    "지원하지 않는 알고리즘: " + signedJwt.getHeader().getAlgorithm());
        }

        // 1-1. 토큰 종류: Access Token(at+jwt)만. ID 토큰 등 다른 typ을 AT로 받지 않는다.
        Object typ = signedJwt.getHeader().getType() == null ? null : signedJwt.getHeader().getType().toString();
        if (typ != null && !EXPECTED_TYP.equalsIgnoreCase(String.valueOf(typ))) {
            throw new JwtVerificationException(AuthnReason.CLAIM_INVALID, "허용하지 않는 typ");
        }

        // 2. kid 추출 → 공개키 조회 (신뢰한 JWKS에 없는 kid는 서명을 검증할 수 없다)
        String kid = signedJwt.getHeader().getKeyID();
        if (kid == null) {
            throw new JwtVerificationException(AuthnReason.INVALID_SIGNATURE, "JWT 헤더에 kid 없음");
        }
        RSAPublicKey publicKey;
        try {
            publicKey = publicKeyProvider.getPublicKey(kid);
        } catch (IllegalArgumentException e) {
            throw new JwtVerificationException(AuthnReason.INVALID_SIGNATURE, "알 수 없는 kid", e);
        }

        // 3. RSA 서명 검증
        try {
            JWSVerifier verifier = new RSASSAVerifier(publicKey);
            if (!signedJwt.verify(verifier)) {
                throw new JwtVerificationException(AuthnReason.INVALID_SIGNATURE, "JWT 서명 검증 실패");
            }
        } catch (JOSEException e) {
            throw new JwtVerificationException(AuthnReason.INVALID_SIGNATURE, "JWT 검증 실패", e);
        }

        // 4. 만료 검증 (exp 있으면 검증, 없으면 통과)
        JWTClaimsSet claims;
        try {
            claims = signedJwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new JwtVerificationException(AuthnReason.MALFORMED, "JWT claim 파싱 실패", e);
        }
        validateClaims(claims);
        return claims;
    }

    /** 필수 claim·발급자·대상·시각을 강제한다. 발급대장(S3)과 별개의 독립 방어층이다. */
    private void validateClaims(JWTClaimsSet claims) {
        long now = System.currentTimeMillis() / 1000;

        for (String required : List.of("sub", "jti")) {
            if (claims.getClaim(required) == null) {
                throw new JwtVerificationException(AuthnReason.CLAIM_INVALID, "필수 claim 없음: " + required);
            }
        }
        if (!expectedIssuer.equals(claims.getIssuer())) {
            throw new JwtVerificationException(AuthnReason.CLAIM_INVALID, "발급자 불일치");
        }
        List<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(expectedAudience)) {
            throw new JwtVerificationException(AuthnReason.CLAIM_INVALID, "대상(aud) 불일치");
        }

        Date iat = claims.getIssueTime();
        Date nbf = claims.getNotBeforeTime();
        Date exp = claims.getExpirationTime();
        if (iat == null || exp == null) {
            throw new JwtVerificationException(AuthnReason.CLAIM_INVALID, "iat·exp는 필수");
        }
        long iatS = iat.getTime() / 1000, expS = exp.getTime() / 1000;
        if (iatS > now + CLOCK_SKEW_SECONDS) {
            throw new JwtVerificationException(AuthnReason.CLAIM_INVALID, "미래 발급(iat)");
        }
        if (nbf != null && nbf.getTime() / 1000 > now + CLOCK_SKEW_SECONDS) {
            throw new JwtVerificationException(AuthnReason.CLAIM_INVALID, "아직 유효하지 않음(nbf)");
        }
        if (expS <= now - CLOCK_SKEW_SECONDS) {
            throw new JwtVerificationException(AuthnReason.EXPIRED, "JWT 만료됨");
        }
        if (expS - iatS > MAX_LIFETIME_SECONDS + CLOCK_SKEW_SECONDS) {
            throw new JwtVerificationException(AuthnReason.CLAIM_INVALID, "최대 수명 초과");
        }
    }
}
