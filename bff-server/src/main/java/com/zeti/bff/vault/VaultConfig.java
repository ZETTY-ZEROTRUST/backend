package com.zeti.bff.vault;

import com.zeti.bff.global.config.BffProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class VaultConfig {

    /** 키가 없거나 형식이 틀리면 여기서 예외 → 애플리케이션 기동 실패(fail fast). */
    @Bean
    public AesGcmTokenCipher aesGcmTokenCipher(BffProperties properties) {
        BffProperties.Vault vault = properties.vault();
        return AesGcmTokenCipher.fromBase64(vault.key(), vault.keyVersion());
    }
}
