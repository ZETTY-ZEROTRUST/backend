package com.zeti.api.mypage.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeti.api.mypage.application.dto.MyPageResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * /mypage Cache-Aside. 키 mypage:{userId}, 값은 응답 JSON.
 * - 조회: 적중 시 반환, 미스 시 호출자가 DB로 만든 값을 put.
 * - 쓰기: 프로필·주소 수정 커밋 이후 evict(롤백된 쓰기는 캐시를 지우지 않음).
 * - TTL에 ±20% 지터를 줘 대량 동시 만료(스탬피드)를 분산한다.
 * - Redis 장애는 미스로 취급해 DB로 폴백한다(요청 실패로 만들지 않음).
 * 적중률은 이 API 단위로 직접 집계한다(mypage_cache_requests_total{result}).
 */
@Slf4j
@Component
public class MyPageCache {

    private static final String PREFIX = "mypage:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long ttlSeconds;
    private final Counter hit;
    private final Counter miss;
    private final Counter error;

    public MyPageCache(StringRedisTemplate redis, ObjectMapper objectMapper, MeterRegistry registry,
                       @Value("${zetty.mypage-cache-enabled:false}") boolean enabled,
                       @Value("${zetty.mypage-cache-ttl-seconds:60}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttlSeconds = ttlSeconds;
        this.hit = Counter.builder("mypage_cache_requests").tag("result", "hit").register(registry);
        this.miss = Counter.builder("mypage_cache_requests").tag("result", "miss").register(registry);
        this.error = Counter.builder("mypage_cache_requests").tag("result", "error").register(registry);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<MyPageResponse> get(Long userId) {
        try {
            String json = redis.opsForValue().get(PREFIX + userId);
            if (json == null) {
                miss.increment();
                return Optional.empty();
            }
            hit.increment();
            return Optional.of(objectMapper.readValue(json, MyPageResponse.class));
        } catch (Exception e) {
            error.increment();
            log.debug("mypage 캐시 조회 실패, DB 폴백: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public void put(Long userId, MyPageResponse value) {
        try {
            long jitter = Math.max(1, ttlSeconds / 5);
            // TTL이 짧으면 지터로 0초가 나올 수 있다(SET EX 0은 Redis 오류). 최소 1초를 보장한다.
            long ttl = Math.max(1, ttlSeconds - jitter + ThreadLocalRandom.current().nextLong(2 * jitter + 1));
            redis.opsForValue().set(PREFIX + userId, objectMapper.writeValueAsString(value),
                    Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.debug("mypage 캐시 저장 실패(무시): {}", e.getClass().getSimpleName());
        }
    }

    public void evict(Long userId) {
        try {
            redis.delete(PREFIX + userId);
        } catch (Exception e) {
            // 삭제 실패 시 TTL이 불일치 상한이 된다.
            log.warn("mypage 캐시 무효화 실패(TTL로 상한): {}", e.getClass().getSimpleName());
        }
    }

    /** 현재 트랜잭션이 커밋된 뒤에만 무효화한다. 캐시가 꺼져 있으면 아무것도 하지 않는다. */
    public void evictAfterCommit(Long userId) {
        if (!enabled) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(userId);
                }
            });
        } else {
            evict(userId);
        }
    }
}
