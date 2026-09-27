package com.zeti.bff.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * auth-server / api-server 흉내. 실제 계약을 따른다.
 * <ul>
 *   <li>login → {accessToken, refreshToken}, 틀린 비밀번호 400</li>
 *   <li>refresh → RT 회전. 현재 RT가 아니면 401 reuse_detected + family 폐기</li>
 *   <li>API → Authorization이 현재 유효 AT일 때만 200, 아니면 401</li>
 * </ul>
 * 토큰은 테스트 실행마다 난수로 만든다.
 */
public final class FakeUpstreams {

    public record ApiCall(String method, String path, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue().isEmpty() ? null : e.getValue().get(0);
                }
            }
            return null;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    public final MockWebServer auth = new MockWebServer();
    public final MockWebServer api = new MockWebServer();

    public final AtomicInteger loginCalls = new AtomicInteger();
    public final AtomicInteger refreshCalls = new AtomicInteger();
    public final AtomicInteger logoutCalls = new AtomicInteger();
    public final List<String> logoutRefreshTokens = new CopyOnWriteArrayList<>();
    public final List<String> issuedTokens = new CopyOnWriteArrayList<>();
    public final List<ApiCall> apiCalls = new CopyOnWriteArrayList<>();

    private volatile String password;
    private volatile long userId;
    private volatile long refreshDelayMs;
    private volatile int logoutStatus;
    private volatile String currentAccessToken;
    private volatile String currentRefreshToken;
    private volatile String apiAcceptedAccessToken;

    private FakeUpstreams() {
        reset();
    }

    public static FakeUpstreams start() {
        FakeUpstreams f = new FakeUpstreams();
        f.auth.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return f.dispatchAuth(request);
            }
        });
        f.api.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return f.dispatchApi(request);
            }
        });
        try {
            f.auth.start();
            f.api.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return f;
    }

    public String authBaseUrl() {
        return "http://" + auth.getHostName() + ":" + auth.getPort();
    }

    public String apiBaseUrl() {
        return "http://" + api.getHostName() + ":" + api.getPort();
    }

    public synchronized void reset() {
        password = "pw-" + random(12);
        userId = 42;
        refreshDelayMs = 0;
        logoutStatus = 204;
        currentAccessToken = null;
        currentRefreshToken = null;
        apiAcceptedAccessToken = null;
        loginCalls.set(0);
        refreshCalls.set(0);
        logoutCalls.set(0);
        logoutRefreshTokens.clear();
        issuedTokens.clear();
        apiCalls.clear();
    }

    public String password() {
        return password;
    }

    public long userId() {
        return userId;
    }

    public void userId(long id) {
        this.userId = id;
    }

    public String currentAccessToken() {
        return currentAccessToken;
    }

    public String currentRefreshToken() {
        return currentRefreshToken;
    }

    public void refreshDelayMs(long ms) {
        this.refreshDelayMs = ms;
    }

    public void logoutStatus(int status) {
        this.logoutStatus = status;
    }

    /** API가 현재 AT를 거부한다(만료 흉내). refresh로 새 AT를 받으면 다시 통과한다. */
    public void expireAccessToken() {
        apiAcceptedAccessToken = "expired";
    }

    /** 탈취자가 같은 RT로 먼저 회전했다 → BFF가 가진 RT는 이미 소비됨(다음 refresh는 재사용 감지). */
    public synchronized void attackerRotatesRefreshToken() {
        currentRefreshToken = "attacker-" + random(24);
        apiAcceptedAccessToken = "expired";
    }

    private MockResponse dispatchAuth(RecordedRequest request) {
        String path = request.getPath();
        JsonNode body = readJson(request.getBody().readUtf8());
        if ("/auth/login".equals(path)) {
            loginCalls.incrementAndGet();
            if (!password.equals(text(body, "password"))) {
                return new MockResponse().setResponseCode(400).setBody("이메일 또는 비밀번호가 올바르지 않습니다.");
            }
            return tokenResponse(issue());
        }
        if ("/auth/refresh".equals(path)) {
            refreshCalls.incrementAndGet();
            sleep(refreshDelayMs);
            synchronized (this) {
                String presented = text(body, "refreshToken");
                if (presented != null && presented.equals(currentRefreshToken)) {
                    return tokenResponse(issue());
                }
                currentRefreshToken = null; // family 폐기
                return new MockResponse().setResponseCode(401)
                        .setHeader("Content-Type", "application/json")
                        .setBody("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"reuse_detected\"}");
            }
        }
        if ("/auth/logout".equals(path)) {
            logoutCalls.incrementAndGet();
            logoutRefreshTokens.add(text(body, "refreshToken"));
            return new MockResponse().setResponseCode(logoutStatus);
        }
        return new MockResponse().setResponseCode(404);
    }

    private MockResponse dispatchApi(RecordedRequest request) {
        String body = request.getBody().readUtf8();
        apiCalls.add(new ApiCall(request.getMethod(), request.getPath(), request.getHeaders().toMultimap(), body));
        String authorization = request.getHeader("Authorization");
        String accepted = apiAcceptedAccessToken;
        if (accepted == null || authorization == null || !authorization.equals("Bearer " + accepted)) {
            return new MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Bearer");
        }
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setHeader("Set-Cookie", "api-internal=1; Path=/")
                .setBody("{\"ok\":true,\"method\":\"" + request.getMethod() + "\",\"echo\":" + JSON.valueToTree(body) + "}");
    }

    private synchronized String[] issue() {
        currentAccessToken = fakeJwt(userId);
        currentRefreshToken = random(32);
        apiAcceptedAccessToken = currentAccessToken;
        issuedTokens.add(currentAccessToken);
        issuedTokens.add(currentRefreshToken);
        return new String[] {currentAccessToken, currentRefreshToken};
    }

    private static MockResponse tokenResponse(String[] pair) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"accessToken\":\"" + pair[0] + "\",\"refreshToken\":\"" + pair[1] + "\"}");
    }

    private static String fakeJwt(long sub) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(("{\"sub\":\"" + sub + "\",\"jti\":\"" + random(16) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + random(64);
    }

    public static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static JsonNode readJson(String s) {
        try {
            return s == null || s.isBlank() ? JSON.createObjectNode() : JSON.readTree(s);
        } catch (IOException e) {
            return JSON.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
