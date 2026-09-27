package com.zeti.bff.support;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

/**
 * 테스트용 세션 저장소. Redis 대신 in-memory를 쓴다(쿠키·세션 수명·필터 동작은 운영과 같은 Spring Session).
 * SessionRepository bean이 있으면 Boot의 Redis 세션 자동 구성은 물러난다.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableSpringHttpSession
public class InMemorySessionConfig {

    @Bean
    public MapSessionRepository sessionRepository() {
        MapSessionRepository repository = new MapSessionRepository(new ConcurrentHashMap<>());
        repository.setDefaultMaxInactiveInterval(Duration.ofMinutes(30));
        return repository;
    }
}
