package com.zeti.auth.token.application;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 회수 시 API의 positive cache(authstate:{jti})를 즉시 삭제해 TTL 이전에도 반영되게 한다. */
@Slf4j
@Component
public class AuthStateCacheInvalidator {

    private static final String PREFIX = "authstate:";
    private final StringRedisTemplate redis;

    public AuthStateCacheInvalidator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void invalidate(List<String> jtis) {
        if (jtis == null || jtis.isEmpty()) {
            return;
        }
        try {
            redis.delete(jtis.stream().map(j -> PREFIX + j).toList());
        } catch (RuntimeException e) {
            // 삭제 실패해도 TTL로 상한된다. API는 DB 재확인으로 폴백.
            log.warn("authstate 캐시 무효화 실패(무시): {}", e.getClass().getSimpleName());
        }
    }
}
