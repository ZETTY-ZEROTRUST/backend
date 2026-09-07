package com.zeti.auth.global.config;

import com.zeti.auth.token.application.port.outbound.JwtSigner;
import com.zeti.auth.token.infrastructure.kms.KmsJwtSigner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

@Configuration
public class JwtConfig {

    @Bean
    public KmsClient kmsClient() {
        return KmsClient.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public JwtSigner jwtSigner(KmsJwtSigner kmsJwtSigner) {
        return kmsJwtSigner;
    }
}
