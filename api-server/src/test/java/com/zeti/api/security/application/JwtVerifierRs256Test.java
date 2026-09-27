package com.zeti.api.security.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A-01 전환 확인: 기존 검증 규칙을 유지한 채 알고리즘만 RS256으로 바뀌었는지 본다. */
class JwtVerifierRs256Test {

    private KeyPair keyPair;
    private JwtVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        JwksPublicKeyProvider provider = mock(JwksPublicKeyProvider.class);
        when(provider.getPublicKey("kid-1")).thenReturn((RSAPublicKey) keyPair.getPublic());
        verifier = new JwtVerifier(provider, "https://auth.zeti.com/", "https://api.zeti.com");
    }

    private String sign(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("kid-1")
                        .type(new com.nimbusds.jose.JOSEObjectType("at+jwt")).build(),
                claims);
        jwt.sign(new RSASSASigner(keyPair.getPrivate()));
        return jwt.serialize();
    }

    /** iss·aud·sub·jti·iat·nbf·exp를 모두 갖춘 정상 AT claim. */
    private JWTClaimsSet.Builder validClaims() {
        long now = System.currentTimeMillis();
        return new JWTClaimsSet.Builder().subject("140000001").jwtID("jti-1")
                .issuer("https://auth.zeti.com/").audience("https://api.zeti.com")
                .issueTime(new Date(now)).notBeforeTime(new Date(now))
                .expirationTime(new Date(now + 900_000));
    }

    @Test
    void acceptsValidRs256Token() throws Exception {
        String token = sign(validClaims().build());
        assertThat(verifier.verify(token).getSubject()).isEqualTo("140000001");
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String token = sign(validClaims()
                .issueTime(new Date(System.currentTimeMillis() - 1_000_000))
                .expirationTime(new Date(System.currentTimeMillis() - 60_000)).build());
        assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOfSatisfying(JwtVerificationException.class,
                        e -> assertThat(e.reason()).isEqualTo(AuthnReason.EXPIRED));
    }

    @Test
    void failureReasonsDistinguishMalformedFromSignature() throws Exception {
        assertThatThrownBy(() -> verifier.verify("not-a-jwt"))
                .isInstanceOfSatisfying(JwtVerificationException.class,
                        e -> assertThat(e.reason()).isEqualTo(AuthnReason.MALFORMED));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair attacker = generator.generateKeyPair();
        SignedJWT forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("kid-1").build(),
                new JWTClaimsSet.Builder().subject("140000001").build());
        forged.sign(new RSASSASigner(attacker.getPrivate()));
        assertThatThrownBy(() -> verifier.verify(forged.serialize()))
                .isInstanceOfSatisfying(JwtVerificationException.class,
                        e -> assertThat(e.reason()).isEqualTo(AuthnReason.INVALID_SIGNATURE));
    }
}
