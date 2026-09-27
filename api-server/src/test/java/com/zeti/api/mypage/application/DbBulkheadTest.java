package com.zeti.api.mypage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** 세마포어 bulkhead: 허가 수만큼만 동시 실행, 초과는 짧은 대기 후 503. permits=0이면 게이팅 없음. */
class DbBulkheadTest {

    @Test
    void disabledPassesThrough() {
        assertThat(new DbBulkhead(0, 200).call(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void limitsConcurrencyAndRejectsExcessFast() throws Exception {
        DbBulkhead bh = new DbBulkhead(1, 100); // 허가 1개
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> bh.call(() -> {
            inside.countDown();
            try { release.await(); } catch (InterruptedException ignored) { }
            return null;
        }));
        inside.await(); // 허가 1개를 점유한 상태
        long t0 = System.currentTimeMillis();
        assertThatThrownBy(() -> bh.call(() -> "second"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        long waited = System.currentTimeMillis() - t0;
        assertThat(waited).isBetween(80L, 2000L); // 무한 대기 아니라 timeout 후 거절
        release.countDown();
        holder.join();
        // 해제 후에는 다시 통과
        assertThat(bh.call(() -> "ok")).isEqualTo("ok");
    }
}
