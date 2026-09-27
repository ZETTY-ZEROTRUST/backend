package com.zeti.bff.proxy;

import com.zeti.bff.session.BffPrincipal;
import com.zeti.bff.upstream.ApiClient;
import com.zeti.bff.upstream.ApiClient.UpstreamResponse;
import com.zeti.bff.vault.TokenPair;
import com.zeti.bff.vault.TokenVault;
import com.zeti.bff.vault.VaultUnreadableException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/**
 * allowlist를 통과한 요청에 vault의 AT를 붙여 API로 보낸다.
 * API가 401이면 single-flight refresh 후 원 요청을 한 번만 다시 보낸다.
 *
 * <p>재시도 근거: API의 401은 인증 필터가 업무 실행 전에 내는 응답이다(api-server SecurityConfig의
 * entry point). 그래서 상태 변경 요청도 "실행 전 거부가 확정된 경우"로 보고 1회 재시도한다.
 * API가 업무 처리 후 401을 내도록 바뀌면 이 계약을 다시 검토해야 한다.
 */
@Service
public class ApiProxyService {

    private static final Logger log = LoggerFactory.getLogger(ApiProxyService.class);

    /** 브라우저로 보낼 결과. */
    public sealed interface Outcome permits Forwarded, SessionEnded, RefreshUnavailable, UpstreamUnavailable {
    }

    public record Forwarded(UpstreamResponse response) implements Outcome {
    }

    /** vault 항목 없음/손상 또는 refresh 거부 → 세션을 끝내고 401. */
    public record SessionEnded() implements Outcome {
    }

    public record RefreshUnavailable() implements Outcome {
    }

    public record UpstreamUnavailable() implements Outcome {
    }

    /** 브라우저 요청에서 API로 옮길 값만 담는다. */
    public record ProxyRequest(HttpMethod method, String path, String query, MediaType contentType, String accept,
            byte[] body) {

        String pathAndQuery() {
            return query == null ? path : path + "?" + query;
        }
    }

    private final TokenVault vault;
    private final ApiClient apiClient;
    private final TokenRefreshCoordinator refresher;

    public ApiProxyService(TokenVault vault, ApiClient apiClient, TokenRefreshCoordinator refresher) {
        this.vault = vault;
        this.apiClient = apiClient;
        this.refresher = refresher;
    }

    public Outcome forward(BffPrincipal principal, ProxyRequest request) {
        TokenPair tokens;
        try {
            Optional<TokenPair> loaded = vault.load(principal.vaultRef());
            if (loaded.isEmpty()) {
                return new SessionEnded();
            }
            tokens = loaded.get();
        } catch (VaultUnreadableException e) {
            log.warn("vault 항목 복호화 실패로 세션 종료 userId={}", principal.userId());
            vault.delete(principal.vaultRef());
            return new SessionEnded();
        }

        Optional<UpstreamResponse> first = call(request, tokens.accessToken());
        if (first.isEmpty()) {
            return new UpstreamUnavailable();
        }
        if (first.get().status() != 401) {
            return new Forwarded(first.get());
        }

        return switch (refresher.refresh(principal.vaultRef(), tokens.accessToken())) {
            case TokenRefreshCoordinator.Refreshed refreshed -> call(request, refreshed.accessToken())
                    .<Outcome>map(Forwarded::new)
                    .orElseGet(UpstreamUnavailable::new);
            case TokenRefreshCoordinator.Revoked revoked -> new SessionEnded();
            case TokenRefreshCoordinator.Unavailable unavailable -> new RefreshUnavailable();
        };
    }

    private Optional<UpstreamResponse> call(ProxyRequest request, String accessToken) {
        try {
            return Optional.of(apiClient.send(request.method(), request.pathAndQuery(), request.contentType(),
                    request.accept(), request.body(), accessToken));
        } catch (RuntimeException e) {
            log.warn("API 호출 실패 method={} path={}: {}", request.method(), request.path(),
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
