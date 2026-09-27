package com.zeti.bff.login;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zeti.bff.upstream.AuthClient;
import com.zeti.bff.vault.TokenPair;
import com.zeti.bff.vault.TokenVault;
import com.zeti.bff.vault.VaultUnreadableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** BFF 로그인·로그아웃. 토큰 원문은 이 계층과 vault 사이에서만 오간다. */
@Service
public class BffAuthService {

    private static final Logger log = LoggerFactory.getLogger(BffAuthService.class);

    public sealed interface LoginResult permits LoggedIn, InvalidCredentials, AuthUnavailable {
    }

    public record LoggedIn(long userId, String vaultRef) implements LoginResult {
        @Override
        public String toString() {
            return "LoggedIn[userId=" + userId + "]";
        }
    }

    public record InvalidCredentials() implements LoginResult {
    }

    public record AuthUnavailable() implements LoginResult {
    }

    private final AuthClient authClient;
    private final TokenVault vault;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;

    public BffAuthService(AuthClient authClient, TokenVault vault, ObjectMapper objectMapper, MeterRegistry meters) {
        this.authClient = authClient;
        this.vault = vault;
        this.objectMapper = objectMapper;
        this.meters = meters;
    }

    public LoginResult login(String email, String password) {
        return switch (authClient.login(email, password)) {
            case AuthClient.Issued issued -> {
                Optional<Long> userId = AccessTokenSubject.userIdOf(issued.tokens().accessToken(), objectMapper);
                if (userId.isEmpty()) {
                    log.warn("auth 로그인 응답 AT에서 sub를 읽을 수 없어 로그인 중단");
                    yield new AuthUnavailable();
                }
                String vaultRef = vault.store(userId.get(), issued.tokens());
                log.info("BFF 로그인 성공 userId={}", userId.get());
                yield new LoggedIn(userId.get(), vaultRef);
            }
            case AuthClient.Rejected rejected -> new InvalidCredentials();
            case AuthClient.Failed failed -> new AuthUnavailable();
        };
    }

    /**
     * 로컬 폐기 1단계: vault에서 RT를 꺼내고 행을 지운다(Auth 호출 결과와 무관하게 먼저 적용).
     * @return Auth 회수 요청에 쓸 RT(행이 없거나 손상이면 empty)
     */
    public Optional<String> discardLocalTokens(String vaultRef) {
        Optional<String> refreshToken;
        try {
            refreshToken = vault.load(vaultRef).map(TokenPair::refreshToken);
        } catch (VaultUnreadableException e) {
            refreshToken = Optional.empty();
        }
        vault.delete(vaultRef);
        return refreshToken;
    }

    /**
     * 서버 측 회수(Auth logout). 실패해도 로컬 로그아웃은 이미 끝났다 — 결과를 구분해 기록한다.
     * @return Auth가 회수를 확인했으면 true
     */
    public boolean revokeAtAuthServer(Optional<String> refreshToken, long userId) {
        boolean confirmed = refreshToken.map(authClient::logout).orElse(false);
        meters.counter("bff.logout", "server_revocation", confirmed ? "confirmed" : "unconfirmed").increment();
        if (confirmed) {
            log.info("BFF 로그아웃 완료(서버 측 회수 확인) userId={}", userId);
        } else {
            log.warn("BFF 로그아웃: 로컬 세션·vault 폐기 완료, 서버 측 토큰 회수 미확인 userId={} rtAvailable={}",
                    userId, refreshToken.isPresent());
        }
        return confirmed;
    }
}
