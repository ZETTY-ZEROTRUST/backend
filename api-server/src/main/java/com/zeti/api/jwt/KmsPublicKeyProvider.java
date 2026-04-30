package com.zeti.api.jwt;

import jakarta.annotation.PostConstruct;
import java.security.KeyFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyRequest;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;

@Slf4j
@Component
public class KmsPublicKeyProvider {

    private final KmsClient kmsClient;
    private final String keyAlias;
    private final Map<String, ECPublicKey> publicKeyCache = new HashMap<>();

    public KmsPublicKeyProvider(
            KmsClient kmsClient,
            @Value("${aws.kms.jwt-key-alias}") String keyAlias) {
        this.kmsClient = kmsClient;
        this.keyAlias = keyAlias;
    }

    @PostConstruct
    public void loadPublicKey() {
        try {
            GetPublicKeyRequest request = GetPublicKeyRequest.builder()
                    .keyId(keyAlias)
                    .build();

            GetPublicKeyResponse response = kmsClient.getPublicKey(request);
            byte[] derPublicKey = response.publicKey().asByteArray();

            ECPublicKey ecPublicKey = (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(derPublicKey));

            // kid는 alias 그대로 사용 (팀원 Auth Server와 약속)
            publicKeyCache.put(keyAlias, ecPublicKey);

            log.info("KMS 공개키 캐시 완료: alias={}, keyId={}", keyAlias, response.keyId());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "KMS 공개키 로드 실패. alias=" + keyAlias, e);
        }
    }

    public ECPublicKey getPublicKey(String kid) {
        ECPublicKey key = publicKeyCache.get(kid);
        if (key == null) {
            throw new IllegalArgumentException("알 수 없는 kid: " + kid);
        }
        return key;
    }
}
