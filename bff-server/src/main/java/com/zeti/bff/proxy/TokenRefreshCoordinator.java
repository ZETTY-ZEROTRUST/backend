package com.zeti.bff.proxy;

import com.zeti.bff.global.config.BffProperties;
import com.zeti.bff.upstream.AuthClient;
import com.zeti.bff.vault.TokenPair;
import com.zeti.bff.vault.TokenVault;
import com.zeti.bff.vault.VaultUnreadableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * vault 항목(session_ref)당 refresh를 하나만 실행한다(single-flight).
 *
 * <p>RT는 회전하므로 같은 RT로 두 번 refresh하면 Auth가 재사용으로 판정해 family를 폐기한다.
 * 동시에 401을 받은 요청들은 먼저 들어온 한 요청(leader)의 결과를 기다려 공유한다.
 * leader는 호출 전에 vault의 AT가 요청이 쓴 AT와 같은지 확인한다 — 이미 다른 요청이 갱신했으면
 * Auth를 부르지 않고 현재 AT를 돌려준다(앞선 refresh가 끝난 뒤 늦게 401을 받은 요청 대비).
 *
 * <p>한계: 단일 BFF 인스턴스 내부 조정이다. 여러 인스턴스는 DB lease/version 조정이 별도로 필요하다.
 */
@Component
public class TokenRefreshCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TokenRefreshCoordinator.class);

    /** refresh 결과. */
    public sealed interface Outcome permits Refreshed, Revoked, Unavailable {
    }

    /** 새 AT(또는 이미 갱신된 현재 AT). */
    public record Refreshed(String accessToken) implements Outcome {
        @Override
        public String toString() {
            return "Refreshed[***]";
        }
    }

    /** RT 거부(재사용 감지·폐기·만료) 또는 vault 항목 없음/손상 → 세션 종료. */
    public record Revoked() implements Outcome {
    }

    /** Auth 장애·대기 시간 초과. 세션은 유지한다. */
    public record Unavailable() implements Outcome {
    }

    private final ConcurrentHashMap<String, CompletableFuture<Outcome>> inFlight = new ConcurrentHashMap<>();
    private final TokenVault vault;
    private final AuthClient authClient;
    private final Duration waitTimeout;
    private final MeterRegistry meters;

    public TokenRefreshCoordinator(TokenVault vault, AuthClient authClient, BffProperties properties,
            MeterRegistry meters) {
        this.vault = vault;
        this.authClient = authClient;
        this.waitTimeout = properties.refreshWaitTimeout();
        this.meters = meters;
    }

    /**
     * @param sessionRef       vault 항목
     * @param staleAccessToken API가 401로 거부한 AT
     */
    public Outcome refresh(String sessionRef, String staleAccessToken) {
        CompletableFuture<Outcome> mine = new CompletableFuture<>();
        CompletableFuture<Outcome> running = inFlight.putIfAbsent(sessionRef, mine);
        if (running != null) {
            meters.counter("bff.token.refresh.coalesced").increment();
            return await(running);
        }
        Outcome outcome = new Unavailable();
        try {
            outcome = refreshAsLeader(sessionRef, staleAccessToken);
        } catch (RuntimeException e) {
            log.warn("refresh 처리 중 오류: {}", e.getClass().getSimpleName());
        } finally {
            mine.complete(outcome);
            inFlight.remove(sessionRef, mine);
        }
        return outcome;
    }

    private Outcome refreshAsLeader(String sessionRef, String staleAccessToken) {
        Optional<TokenPair> current;
        try {
            current = vault.load(sessionRef);
        } catch (VaultUnreadableException e) {
            log.warn("vault 항목 복호화 실패로 세션 종료");
            vault.delete(sessionRef);
            return count(new Revoked(), "vault_unreadable");
        }
        if (current.isEmpty()) {
            return count(new Revoked(), "vault_missing");
        }
        TokenPair tokens = current.get();
        if (!sameToken(tokens.accessToken(), staleAccessToken)) {
            // 다른 요청이 이미 갱신했다. 같은 RT를 다시 제시하지 않는다.
            return count(new Refreshed(tokens.accessToken()), "already_refreshed");
        }
        return switch (authClient.refresh(tokens.refreshToken())) {
            case AuthClient.Issued issued -> {
                if (!vault.replace(sessionRef, issued.tokens())) {
                    // 대기 중 logout 등으로 행이 사라졌다. 새 토큰은 버린다.
                    yield count(new Revoked(), "vault_missing");
                }
                yield count(new Refreshed(issued.tokens().accessToken()), "refreshed");
            }
            case AuthClient.Rejected rejected -> {
                // 재사용 감지(family 폐기) 포함. 더 쓸 수 없는 토큰이므로 vault에서 지운다.
                vault.delete(sessionRef);
                log.info("refresh 거부(status={}) → vault 삭제, 세션 종료", rejected.status());
                yield count(new Revoked(), "rejected");
            }
            case AuthClient.Failed failed -> count(new Unavailable(), "unavailable");
        };
    }

    private Outcome await(CompletableFuture<Outcome> running) {
        try {
            return running.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Unavailable();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("진행 중인 refresh 대기 실패: {}", e.getClass().getSimpleName());
            return new Unavailable();
        }
    }

    private Outcome count(Outcome outcome, String result) {
        meters.counter("bff.token.refresh", "result", result).increment();
        return outcome;
    }

    private static boolean sameToken(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
