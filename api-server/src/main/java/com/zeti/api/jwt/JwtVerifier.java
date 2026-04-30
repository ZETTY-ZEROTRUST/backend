package com.zeti.api.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtVerifier {

    private final KmsPublicKeyProvider publicKeyProvider;

    public JWTClaimsSet verify(String token) {
        try {
            SignedJWT signedJwt = SignedJWT.parse(token);

            // 1. 알고리즘 검증 (ES256만 허용)
            if (!JWSAlgorithm.ES256.equals(signedJwt.getHeader().getAlgorithm())) {
                throw new IllegalArgumentException(
                        "지원하지 않는 알고리즘: " + signedJwt.getHeader().getAlgorithm());
            }

            // 2. kid 추출 → 공개키 조회
            String kid = signedJwt.getHeader().getKeyID();
            if (kid == null) {
                throw new IllegalArgumentException("JWT 헤더에 kid 없음");
            }

            // 3. ECDSA 서명 검증
            JWSVerifier verifier = new ECDSAVerifier(publicKeyProvider.getPublicKey(kid));
            if (!signedJwt.verify(verifier)) {
                throw new IllegalArgumentException("JWT 서명 검증 실패");
            }

            // 4. 만료 검증 (exp 있으면 검증, 없으면 통과)
            JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            if (exp != null && exp.before(new Date())) {
                throw new IllegalArgumentException("JWT 만료됨: exp=" + exp);
            }

            return claims;

        } catch (ParseException e) {
            throw new IllegalArgumentException("JWT 파싱 실패", e);
        } catch (JOSEException e) {
            throw new IllegalArgumentException("JWT 검증 실패", e);
        }
    }
}
