package com.zeti.auth.global.config;

import com.zeti.auth.token.application.port.outbound.JwtSigner;
import com.zeti.auth.token.infrastructure.kms.KmsJwtSigner;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.KmsClientBuilder;

@Configuration
public class JwtConfig {

    /**
     * endpoint를 지정하면 로컬 KMS 에뮬레이터만 호출한다. 에뮬레이터는 서명 검증을 하지 않으므로
     * 실제 AWS 자격증명을 쓰지 않고 고정 더미 값을 사용한다. 미지정 시 AWS 기본 공급자를 사용한다.
     */
    @Bean
    public KmsClient kmsClient(
            @Value("${zetty.kms.endpoint:}") String endpoint,
            @Value("${zetty.kms.region:ap-northeast-2}") String region) {
        KmsClientBuilder builder = KmsClient.builder().region(Region.of(region));
        if (endpoint.isBlank()) {
            return builder.credentialsProvider(DefaultCredentialsProvider.create()).build();
        }
        return builder
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("local-emulator", "local-emulator")))
                .build();
    }

    @Bean
    public JwtSigner jwtSigner(KmsJwtSigner kmsJwtSigner) {
        return kmsJwtSigner;
    }
}
