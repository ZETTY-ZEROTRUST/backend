package com.zeti.auth.jwt;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SignRequest;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;
import java.util.Base64;

@Component
public class KmsJwtSigner implements JwtSigner {

    private final KmsClient kmsClient;
    private final String keyId;

    public KmsJwtSigner(
            KmsClient kmsClient,
            @Value("${jwt.kid}") String keyId
    ) {
        this.kmsClient = kmsClient;
        this.keyId = keyId;
    }

    @Override
    public String sign(String headerPayload) throws Exception {
        SignRequest request = SignRequest.builder()
                .keyId(keyId)
                .messageType(MessageType.RAW)
                .message(SdkBytes.fromByteArray(
                        headerPayload.getBytes(StandardCharsets.UTF_8)))
                .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256)
                .build();

        byte[] derSignature = kmsClient.sign(request).signature().asByteArray();

        // DER → JWT용 R+S 변환
        byte[] jwtSignature = derToJwtSignature(derSignature);

        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(jwtSignature);
    }

    // DER 인코딩된 ECDSA 서명을 JWT R+S 형식으로 변환
    private byte[] derToJwtSignature(byte[] derSignature) {
        // DER 구조: 0x30 [총길이] 0x02 [R길이] [R] 0x02 [S길이] [S]
        int rLength = derSignature[3];
        int rOffset = 4;

        int sLength = derSignature[rOffset + rLength + 1];
        int sOffset = rOffset + rLength + 2;

        byte[] r = new byte[32];
        byte[] s = new byte[32];

        // R값 복사 (앞에 패딩 0x00이 있을 수 있음)
        System.arraycopy(derSignature, rOffset + Math.max(0, rLength - 32),
                r, Math.max(0, 32 - rLength), Math.min(rLength, 32));

        // S값 복사
        System.arraycopy(derSignature, sOffset + Math.max(0, sLength - 32),
                s, Math.max(0, 32 - sLength), Math.min(sLength, 32));

        byte[] result = new byte[64];
        System.arraycopy(r, 0, result, 0, 32);
        System.arraycopy(s, 0, result, 32, 32);

        return result;
    }
}