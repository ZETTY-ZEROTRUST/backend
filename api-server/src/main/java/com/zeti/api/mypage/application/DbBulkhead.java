package com.zeti.api.mypage.application;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * DB 바운드 구간의 동시성 제한(bulkhead). Virtual Thread는 요청마다 무제한 생성되지만
 * DB 커넥션 풀은 유한하므로, 풀 앞에서 세마포어로 동시 진입을 제한한다(풀 경합·무한 적체 방지).
 * permits=0이면 비활성(게이팅 없음). permits는 보통 DB 풀 크기 이하로 둔다.
 * 허가를 짧은 대기 안에 못 얻으면 503으로 빠르게 거절(fail-fast)해 밀림을 막는다.
 */
@Component
public class DbBulkhead {

    private final Semaphore semaphore;
    private final boolean enabled;
    private final long acquireTimeoutMs;

    public DbBulkhead(@Value("${zetty.db-bulkhead.permits:0}") int permits,
                      @Value("${zetty.db-bulkhead.acquire-timeout-ms:200}") long acquireTimeoutMs) {
        this.enabled = permits > 0;
        this.semaphore = enabled ? new Semaphore(permits, true) : null;
        this.acquireTimeoutMs = acquireTimeoutMs;
    }

    public <T> T call(java.util.function.Supplier<T> work) {
        if (!enabled) {
            return work.get();
        }
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "interrupted");
        }
        if (!acquired) {
            // 유한 자원(DB 풀) 앞에서 무한정 밀리는 대신 빠르게 거절한다.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "db_bulkhead_full");
        }
        try {
            return work.get();
        } finally {
            semaphore.release();
        }
    }

    Duration acquireTimeout() {
        return Duration.ofMillis(acquireTimeoutMs);
    }
}
