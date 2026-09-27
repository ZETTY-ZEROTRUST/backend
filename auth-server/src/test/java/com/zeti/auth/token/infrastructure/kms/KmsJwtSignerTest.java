package com.zeti.auth.token.infrastructure.kms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeti.auth.token.application.JwtIssuer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SignResponse;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

class KmsJwtSignerTest {

    private KeyPair keyPair;
    private KmsJwtSigner signer;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();

        KmsClient kms = mock(KmsClient.class);
        when(kms.getPublicKey(any(GetPublicKeyRequest.class))).thenReturn(GetPublicKeyResponse.builder()
                .publicKey(SdkBytes.fromByteArray(keyPair.getPublic().getEncoded())).build());
        when(kms.sign(any(SignRequest.class))).thenAnswer(invocation -> {
            SignRequest request = invocation.getArgument(0);
            assertThat(request.signingAlgorithm()).isEqualTo(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256);
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(keyPair.getPrivate());
            rsa.update(request.message().asByteArray());
            return SignResponse.builder().signature(SdkBytes.fromByteArray(rsa.sign())).build();
        });
        signer = new KmsJwtSigner(kms, "alias/test");
    }

    @Test
    void issuedTokenIsRs256AndVerifiableWithPublishedJwk() throws Exception {
        String token = new JwtIssuer(signer, new ObjectMapper(), 600).issue(140000001L);
        SignedJWT jwt = SignedJWT.parse(token);

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(signer.keyId());
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic()))).isTrue();
        assertThat(jwt.verify(new RSASSAVerifier(signer.publicJwk().toRSAPublicKey()))).isTrue();
    }

    @Test
    void kidIsThumbprintNotKmsAliasAndJwkHasNoPrivatePart() {
        assertThat(signer.keyId()).doesNotContain("alias").hasSizeGreaterThan(20);
        assertThat(signer.publicJwk().isPrivate()).isFalse();
    }
}
