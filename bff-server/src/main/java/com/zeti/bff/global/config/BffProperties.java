package com.zeti.bff.global.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * BFF 설정. 값의 출처는 application.yml의 env 매핑이다.
 *
 * @param authBaseUrl            Auth 서버 base URL(서버 간 호출)
 * @param apiBaseUrl             API 서버 base URL(proxy 대상)
 * @param allowedOrigin          unsafe 메서드에서 정확히 일치해야 하는 Origin
 * @param sessionAbsoluteTimeout 로그인 시각 기준 절대 만료(세션 사용·refresh로 연장하지 않음)
 * @param refreshWaitTimeout     single-flight refresh 결과를 기다리는 최대 시간
 */
@Validated
@ConfigurationProperties(prefix = "zetty.bff")
public record BffProperties(
        @NotNull URI authBaseUrl,
        @NotNull URI apiBaseUrl,
        @NotBlank String allowedOrigin,
        @NotNull Duration sessionAbsoluteTimeout,
        @NotNull Duration upstreamConnectTimeout,
        @NotNull Duration upstreamReadTimeout,
        @NotNull Duration refreshWaitTimeout,
        @Positive int maxRequestBodyBytes,
        @Positive int maxResponseBodyBytes,
        @NotNull @Valid Vault vault) {

    /** base URL 끝의 '/'를 제거한 문자열. 경로는 항상 '/'로 시작하게 붙인다. */
    public static String withoutTrailingSlash(URI uri) {
        String s = uri.toString();
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * vault 암호화 키 설정. 키 검증(존재·base64·32바이트)은 {@code AesGcmTokenCipher}가 기동 시 수행한다.
     * 바인딩 실패 보고서에 키 값이 찍히지 않도록 여기서는 제약을 걸지 않는다.
     */
    public record Vault(String key, @Positive int keyVersion) {
        @Override
        public String toString() {
            return "Vault[key=***, keyVersion=" + keyVersion + "]";
        }
    }
}
