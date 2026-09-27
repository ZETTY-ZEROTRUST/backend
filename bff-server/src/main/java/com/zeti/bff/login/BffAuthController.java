package com.zeti.bff.login;

import com.zeti.bff.global.web.JsonErrors;
import com.zeti.bff.session.BffPrincipal;
import com.zeti.bff.session.BffSession;
import com.zeti.bff.session.CsrfTokenIssuer;
import com.zeti.bff.vault.TokenVault;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 브라우저용 인증 엔드포인트. 응답에 AT/RT를 넣지 않는다 — 브라우저는 세션 쿠키와 CSRF 토큰만 받는다.
 */
@RestController
public class BffAuthController {

    static final String REVOCATION_HEADER = "Zetty-Server-Revocation";

    public record LoginRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 256) String password) {
        @Override
        public String toString() {
            return "LoginRequest[password=***]";
        }
    }

    public record SessionResponse(long userId, String csrfToken) {
        @Override
        public String toString() {
            return "SessionResponse[userId=" + userId + "]";
        }
    }

    private final BffAuthService authService;
    private final TokenVault vault;
    private final CsrfTokenIssuer csrfTokenIssuer;
    private final Clock clock;

    public BffAuthController(BffAuthService authService, TokenVault vault, CsrfTokenIssuer csrfTokenIssuer,
            Clock clock) {
        this.authService = authService;
        this.vault = vault;
        this.csrfTokenIssuer = csrfTokenIssuer;
        this.clock = clock;
    }

    @PostMapping("/bff/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        return switch (authService.login(body.email(), body.password())) {
            case BffAuthService.LoggedIn loggedIn -> {
                // 세션 고정 방어: 기존 세션(이전 로그인의 vault 포함)을 버리고 새 ID로 시작한다.
                HttpSession previous = request.getSession(false);
                if (previous != null) {
                    BffSession.vaultRef(previous).ifPresent(vault::delete);
                    JsonErrors.invalidateSession(request);
                }
                HttpSession session = request.getSession(true);
                BffSession.bind(session, loggedIn.userId(), loggedIn.vaultRef(), clock.instant());
                String csrfToken = csrfTokenIssuer.issue(request, response);
                yield ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(new SessionResponse(loggedIn.userId(), csrfToken));
            }
            case BffAuthService.InvalidCredentials invalid -> JsonErrors.entity(401, "invalid_credentials");
            case BffAuthService.AuthUnavailable unavailable -> JsonErrors.entity(502, "auth_unavailable");
        };
    }

    /** 새로고침 후 SPA가 CSRF 토큰을 다시 받는 경로. */
    @GetMapping("/bff/session")
    public ResponseEntity<SessionResponse> session(@AuthenticationPrincipal BffPrincipal principal,
            CsrfToken csrfToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SessionResponse(principal.userId(), csrfToken.getToken()));
    }

    /**
     * 로컬 폐기(vault 행 삭제 → 세션 무효화)를 먼저 적용한 뒤 Auth에 서버 측 회수를 요청한다.
     * Auth 호출이 실패해도 204. 회수 확인 여부는 {@value #REVOCATION_HEADER} 헤더로 구분한다.
     */
    @PostMapping("/bff/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal BffPrincipal principal, HttpServletRequest request) {
        Optional<String> refreshToken = authService.discardLocalTokens(principal.vaultRef());
        JsonErrors.invalidateSession(request);
        boolean confirmed = authService.revokeAtAuthServer(refreshToken, principal.userId());
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header(REVOCATION_HEADER, confirmed ? "confirmed" : "unconfirmed")
                .build();
    }
}
