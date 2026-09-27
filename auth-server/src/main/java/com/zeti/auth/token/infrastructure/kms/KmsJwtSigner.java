package com.zeti.auth.token.infrastructure.kms;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.zeti.auth.token.application.port.outbound.JwtSigner;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

/** KMS 비대칭 키로 RS256(RSASSA-PKCS1-v1_5 SHA-256) 서명한다. 개인키는 KMS 밖으로 나오지 않는다. */
@Component
public class KmsJwtSigner implements JwtSigner {

    private final KmsClient kmsClient;
    private final String keyId;
    private volatile RSAKey publicJwk;

    public KmsJwtSigner(KmsClient kmsClient, @Value("${zetty.jwt.kms-key-id}") String keyId) {
        this.kmsClient = kmsClient;
        this.keyId = keyId;
    }

    @Override
    public String sign(String headerPayload) {
        SignRequest request = SignRequest.builder()
                .keyId(keyId)
                .messageType(MessageType.RAW)
                .message(SdkBytes.fromByteArray(headerPayload.getBytes(StandardCharsets.UTF_8)))
                .signingAlgorithm(SigningAlgorithmSpec.RSASSA_PKCS1_V1_5_SHA_256)
                .build();
        byte[] signature = kmsClient.sign(request).signature().asByteArray();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    @Override
    public String keyId() {
        return publicJwk().getKeyID();
    }

    @Override
    public RSAKey publicJwk() {
        RSAKey jwk = publicJwk;
        if (jwk == null) {
            synchronized (this) {
                if (publicJwk == null) {
                    publicJwk = loadPublicJwk();
                }
                jwk = publicJwk;
            }
        }
        return jwk;
    }

    private RSAKey loadPublicJwk() {
        byte[] der = kmsClient.getPublicKey(GetPublicKeyRequest.builder().keyId(keyId).build())
                .publicKey().asByteArray();
        try {
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
            RSAKey withoutKid = new RSAKey.Builder(key).build();
            // kid는 KMS key 경로가 아니라 공개키 thumbprint(RFC 7638)로 정한다.
            return new RSAKey.Builder(key)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                    .keyID(withoutKid.computeThumbprint().toString())
                    .build();
        } catch (JOSEException | java.security.GeneralSecurityException e) {
            throw new IllegalStateException("KMS 공개키를 RSA JWK로 변환하지 못했습니다.", e);
        }
    }
}
