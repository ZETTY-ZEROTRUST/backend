package com.zeti.api.security.application;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 보호 요청 상태확인(authVersion+발급대장)의 positive cache.
 * 검증에 성공한 (jti,sub,authVersion,digest) 조합만 짧은 TTL로 캐시한다.
 * 캐시 적중 시 DB 조회 2회를 건너뛴다. 로그아웃 등 회수는 Auth가 키를 삭제해 반영한다(TTL은 상한).
 * Redis 장애 시 캐시를 무시하고 DB 경로로 폴백한다(fail-safe, 보안 약화 아님).
 */
@Slf4j
@Component
public class AuthStateCache {

    private static final String PREFIX = "authstate:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    private final boolean enabled;

    public AuthStateCache(StringRedisTemplate redis,
                          @Value("${zetty.authstate-cache-ttl-seconds:30}") long ttlSeconds,
                          @Value("${zetty.authstate-cache-enabled:false}") boolean enabled) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.enabled = enabled;
    }

    private static String token(Long sub, int authVersion, String digest) {
        return sub + "|" + authVersion + "|" + digest;
    }

    /** 캐시된 검증 결과가 현재 요청과 일치하면 true. 미스·불일치·장애는 false(→ DB 확인). */
    public boolean isValidated(String jti, Long sub, int authVersion, String digest) {
        if (!enabled || jti == null) {
            return false;
        }
        try {
            String cached = redis.opsForValue().get(PREFIX + jti);
            return token(sub, authVersion, digest).equals(cached);
        } catch (RuntimeException e) {
            log.debug("authstate 캐시 조회 실패, DB로 폴백: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    public void store(String jti, Long sub, int authVersion, String digest) {
        if (!enabled || jti == null) {
            return;
        }
        try {
            redis.opsForValue().set(PREFIX + jti, token(sub, authVersion, digest), ttl);
        } catch (RuntimeException e) {
            log.debug("authstate 캐시 저장 실패(무시): {}", e.getClass().getSimpleName());
        }
    }
}
