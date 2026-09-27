package com.zeti.api.security.application;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.zeti.api.security.infrastructure.jwks.JwksPublicKeyProvider;
import com.zeti.api.securityevent.application.SecurityEvent.AuthnReason;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtVerifier {

    private final JwksPublicKeyProvider publicKeyProvider;

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
        Date exp = claims.getExpirationTime();
        if (exp != null && exp.before(new Date())) {
            throw new JwtVerificationException(AuthnReason.EXPIRED, "JWT 만료됨: exp=" + exp);
        }

        return claims;
    }
}
