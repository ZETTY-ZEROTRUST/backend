package com.zeti.bff.upstream;

import com.zeti.bff.global.config.BffProperties;
import com.zeti.bff.vault.TokenPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Auth 서버 호출(서버 간). 요청/응답 본문에 비밀이 있으므로 DTO toString을 가리고,
 * 실패 로그에는 상태 코드·예외 종류만 남긴다.
 */
@Component
public class AuthClient {

    private static final Logger log = LoggerFactory.getLogger(AuthClient.class);

    /** 로그인/refresh 결과. */
    public sealed interface TokenResult permits Issued, Rejected, Failed {
    }

    /** 새 토큰 발급 성공. */
    public record Issued(TokenPair tokens) implements TokenResult {
    }

    /** Auth가 자격증명/RT를 거부(400·401·403). refresh에서는 재사용 감지·폐기·만료를 포함한다. */
    public record Rejected(int status) implements TokenResult {
    }

    /** Auth 장애·응답 형식 오류. 사용자의 자격 문제로 단정하지 않는다. */
    public record Failed(String reason) implements TokenResult {
    }

    private final RestClient rest;

    public AuthClient(BffProperties properties) {
        this.rest = RestClient.builder()
                .baseUrl(BffProperties.withoutTrailingSlash(properties.authBaseUrl()))
                .requestFactory(UpstreamHttp.requestFactory(properties))
                .build();
    }

    public TokenResult login(String email, String password) {
        return exchangeForTokens("/auth/login", new LoginBody(email, password), "login");
    }

    public TokenResult refresh(String refreshToken) {
        return exchangeForTokens("/auth/refresh", new RefreshBody(refreshToken), "refresh");
    }

    /** @return Auth가 2xx로 응답하면 true(서버 측 회수 확인). 그 외·장애는 false. */
    public boolean logout(String refreshToken) {
        try {
            int status = rest.post().uri("/auth/logout")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RefreshBody(refreshToken))
                    .exchange((request, response) -> response.getStatusCode().value());
            if (status >= 200 && status < 300) {
                return true;
            }
            log.warn("auth logout 비정상 응답 status={}", status);
            return false;
        } catch (RuntimeException e) {
            log.warn("auth logout 호출 실패: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private TokenResult exchangeForTokens(String path, Object body, String operation) {
        try {
            return rest.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 200) {
                            TokenBody tokens = response.bodyTo(TokenBody.class);
                            if (tokens == null || isBlank(tokens.accessToken()) || isBlank(tokens.refreshToken())) {
                                log.warn("auth {} 응답에 토큰 필드가 없음", operation);
                                return new Failed("malformed_response");
                            }
                            return new Issued(new TokenPair(tokens.accessToken(), tokens.refreshToken()));
                        }
                        if (status == 400 || status == 401 || status == 403) {
                            return new Rejected(status);
                        }
                        log.warn("auth {} 비정상 응답 status={}", operation, status);
                        return new Failed("status_" + status);
                    });
        } catch (RuntimeException e) {
            log.warn("auth {} 호출 실패: {}", operation, e.getClass().getSimpleName());
            return new Failed("unreachable");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    record LoginBody(String email, String password) {
        @Override
        public String toString() {
            return "LoginBody[password=***]";
        }
    }

    record RefreshBody(String refreshToken) {
        @Override
        public String toString() {
            return "RefreshBody[***]";
        }
    }

    record TokenBody(String accessToken, String refreshToken) {
        @Override
        public String toString() {
            return "TokenBody[***]";
        }
    }
}
