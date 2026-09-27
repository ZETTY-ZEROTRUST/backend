package com.zeti.api.security.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.zeti.api.security.infrastructure.jwks.JwksPublicKeyProvider;
import com.zeti.api.securityevent.application.SecurityEvent.AuthnReason;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** B-07: 서명이 유효해도 필수 claim·iss·aud·typ·시각 조건을 어기면 거부한다(발급대장과 별개 방어층). */
class JwtValidationHardeningTest {

    private static final String ISS = "https://auth.zeti.com/";
    private static final String AUD = "https://api.zeti.com";
    private KeyPair keyPair;
    private JwtVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keyPair = g.generateKeyPair();
        JwksPublicKeyProvider provider = mock(JwksPublicKeyProvider.class);
        when(provider.getPublicKey("kid-1")).thenReturn((RSAPublicKey) keyPair.getPublic());
        verifier = new JwtVerifier(provider, ISS, AUD);
    }

    private JWTClaimsSet.Builder valid() {
        long now = System.currentTimeMillis();
        return new JWTClaimsSet.Builder().subject("140000001").jwtID("jti-1")
                .issuer(ISS).audience(AUD)
                .issueTime(new Date(now)).notBeforeTime(new Date(now))
                .expirationTime(new Date(now + 900_000));
    }

    private String sign(String typ, JWTClaimsSet claims) throws Exception {
        JWSHeader.Builder h = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("kid-1");
        if (typ != null) {
            h.type(new JOSEObjectType(typ));
        }
        SignedJWT jwt = new SignedJWT(h.build(), claims);
        jwt.sign(new RSASSASigner(keyPair.getPrivate()));
        return jwt.serialize();
    }

    private void rejects(String typ, JWTClaimsSet claims, AuthnReason reason) throws Exception {
        String token = sign(typ, claims);
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOfSatisfying(JwtVerificationException.class,
                        e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void acceptsValid() throws Exception {
        assertThat(verifier.verify(sign("at+jwt", valid().build())).getSubject()).isEqualTo("140000001");
    }

    @Test
    void rejectsWrongTyp() throws Exception {
        rejects("JWT", valid().build(), AuthnReason.CLAIM_INVALID); // ID 토큰 등
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        rejects("at+jwt", valid().issuer("https://evil.example/").build(), AuthnReason.CLAIM_INVALID);
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        rejects("at+jwt", valid().audience("https://other.api").build(), AuthnReason.CLAIM_INVALID);
    }

    @Test
    void rejectsMissingExp() throws Exception {
        rejects("at+jwt", valid().expirationTime(null).build(), AuthnReason.CLAIM_INVALID);
    }

    @Test
    void rejectsMissingSubOrJti() throws Exception {
        rejects("at+jwt", valid().subject(null).build(), AuthnReason.CLAIM_INVALID);
        rejects("at+jwt", valid().jwtID(null).build(), AuthnReason.CLAIM_INVALID);
    }

    @Test
    void rejectsFutureIssued() throws Exception {
        long now = System.currentTimeMillis();
        rejects("at+jwt", valid().issueTime(new Date(now + 120_000)).build(), AuthnReason.CLAIM_INVALID);
    }

    @Test
    void rejectsOverMaxLifetime() throws Exception {
        long now = System.currentTimeMillis();
        rejects("at+jwt", valid().issueTime(new Date(now))
                .expirationTime(new Date(now + 4_000_000)).build(), AuthnReason.CLAIM_INVALID); // >900s
    }

    @Test
    void expiredWithinSkewStillAccepted() throws Exception {
        long now = System.currentTimeMillis();
        // exp가 10초 전이어도 30초 skew 안이면 통과(시계 오차 허용)
        assertThat(verifier.verify(sign("at+jwt", valid()
                .issueTime(new Date(now - 100_000))
                .expirationTime(new Date(now - 10_000)).build()))).isNotNull();
    }
}
