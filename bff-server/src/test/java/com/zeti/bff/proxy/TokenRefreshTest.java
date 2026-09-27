package com.zeti.bff.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zeti.bff.support.BffIntegrationTest;
import com.zeti.bff.support.FakeUpstreams.ApiCall;
import com.zeti.bff.vault.TokenVault;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

class TokenRefreshTest extends BffIntegrationTest {

    private static final int CONCURRENT_REQUESTS = 8;

    @LocalServerPort
    int port;

    @Autowired
    TokenVault vault;

    @Autowired
    MeterRegistry meters;

    /** leader가 아닌 요청 수: 진행 중 refresh를 기다린 요청 + 이미 갱신된 AT를 받은 요청. */
    private double sharedRefreshCount() {
        return meters.counter("bff.token.refresh.coalesced").count()
                + meters.counter("bff.token.refresh", "result", "already_refreshed").count();
    }

    @Test
    void concurrentRequestsWithExpiredAccessToken_triggerExactlyOneRefresh() throws Exception {
        Login login = login();
        String vaultRef = vaultRefOf(login);
        String oldAccessToken = UPSTREAMS.currentAccessToken();
        double sharedBefore = sharedRefreshCount();
        UPSTREAMS.expireAccessToken();
        UPSTREAMS.refreshDelayMs(300); // 동시 요청들이 진행 중인 refresh와 확실히 겹치도록

        // 실제 Tomcat에 동시 요청(스레드 풀에서 병렬 처리)
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/bff/api/users/me"))
                .header("Cookie", login.sessionCookie().getName() + "=" + login.sessionCookie().getValue())
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        List<CompletableFuture<HttpResponse<String>>> inFlight = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            inFlight.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(inFlight.toArray(CompletableFuture[]::new)).join();

        for (CompletableFuture<HttpResponse<String>> f : inFlight) {
            assertThat(f.join().statusCode()).isEqualTo(200);
        }
        assertThat(UPSTREAMS.refreshCalls.get()).as("Auth refresh 호출 수").isEqualTo(1);

        String newAccessToken = UPSTREAMS.currentAccessToken();
        assertThat(newAccessToken).isNotEqualTo(oldAccessToken);
        assertThat(UPSTREAMS.apiCalls.stream().map(c -> c.header("Authorization")))
                .containsOnly("Bearer " + oldAccessToken, "Bearer " + newAccessToken);
        long rejectedWithOld = UPSTREAMS.apiCalls.stream()
                .filter(c -> ("Bearer " + oldAccessToken).equals(c.header("Authorization"))).count();
        long servedWithNew = UPSTREAMS.apiCalls.stream()
                .filter(c -> ("Bearer " + newAccessToken).equals(c.header("Authorization"))).count();
        System.out.printf("[single-flight] concurrent=%d rejectedWithOldAt=%d sharedResult=%.0f refreshCalls=%d%n",
                CONCURRENT_REQUESTS, rejectedWithOld, sharedRefreshCount() - sharedBefore,
                UPSTREAMS.refreshCalls.get());
        // 모든 요청은 결국 새 AT로 한 번씩 성공
        assertThat(servedWithNew).isEqualTo(CONCURRENT_REQUESTS);
        // 실제로 여러 요청이 동시에 401을 받았고, leader 1건을 뺀 나머지는 결과를 공유했다(추가 refresh 없음)
        assertThat(rejectedWithOld).as("동시에 401을 받은 요청 수").isGreaterThanOrEqualTo(2);
        assertThat(sharedRefreshCount() - sharedBefore).isEqualTo(rejectedWithOld - 1);
        // vault는 회전된 새 토큰으로 갱신됨
        assertThat(vault.load(vaultRef)).get().satisfies(t -> {
            assertThat(t.accessToken()).isEqualTo(newAccessToken);
            assertThat(t.refreshToken()).isEqualTo(UPSTREAMS.currentRefreshToken());
        });

        // 이후 요청은 refresh 없이 새 AT 사용
        UPSTREAMS.apiCalls.clear();
        mvc.perform(get("/bff/api/users/me").cookie(login.sessionCookie())).andExpect(status().isOk());
        assertThat(UPSTREAMS.refreshCalls.get()).isEqualTo(1);
        assertThat(UPSTREAMS.apiCalls).extracting(ApiCall::path).containsExactly("/users/me");
    }

    @Test
    void refreshRejected_reuseDetected_destroysVaultAndSession_401() throws Exception {
        Login login = login();
        String vaultRef = vaultRefOf(login);
        UPSTREAMS.attackerRotatesRefreshToken(); // BFF가 가진 RT는 이미 소비됨 → Auth가 재사용으로 판정

        mvc.perform(get("/bff/api/mypage").cookie(login.sessionCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("session_expired"));

        assertThat(UPSTREAMS.refreshCalls.get()).isEqualTo(1);
        assertThat(vaultRows(vaultRef)).as("vault 행 삭제").isZero();
        assertThat(sessions.findById(login.sessionId())).as("세션 무효화").isNull();

        // 같은 쿠키로 다시 와도 401, API·Auth 호출 없음
        UPSTREAMS.apiCalls.clear();
        mvc.perform(get("/bff/api/mypage").cookie(login.sessionCookie()))
                .andExpect(status().isUnauthorized());
        assertThat(UPSTREAMS.apiCalls).isEmpty();
        assertThat(UPSTREAMS.refreshCalls.get()).isEqualTo(1);
    }

    @Test
    void refreshSucceeds_originalRequestRetriedOnce() throws Exception {
        Login login = login();
        UPSTREAMS.expireAccessToken();

        mvc.perform(get("/bff/api/payments/history").cookie(login.sessionCookie()))
                .andExpect(status().isOk());

        assertThat(UPSTREAMS.refreshCalls.get()).isEqualTo(1);
        assertThat(UPSTREAMS.apiCalls).hasSize(2);
        assertThat(UPSTREAMS.apiCalls.get(1).header("Authorization"))
                .isEqualTo("Bearer " + UPSTREAMS.currentAccessToken());
    }
}
