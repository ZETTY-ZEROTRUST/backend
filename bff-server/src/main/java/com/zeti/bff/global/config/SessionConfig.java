package com.zeti.bff.global.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * BFF 세션 쿠키. 세션 저장소(Redis, namespace {@code zetty:bff:session})는 application.yml의
 * {@code spring.session.redis.*}로 Spring Boot가 구성한다.
 */
@Configuration(proxyBeanMethods = false)
public class SessionConfig {

    /** {@code __Host-} 접두사: Secure + Path=/ + Domain 없음이어야 브라우저가 받아들인다. */
    public static final String SESSION_COOKIE_NAME = "__Host-zetty-session";

    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(SESSION_COOKIE_NAME);
        serializer.setCookiePath("/");
        // Domain은 설정하지 않는다(__Host- 요건, 하위 도메인 공유 금지).
        serializer.setUseSecureCookie(true);
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        return serializer;
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
