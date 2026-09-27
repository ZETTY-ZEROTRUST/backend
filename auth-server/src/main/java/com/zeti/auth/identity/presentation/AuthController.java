package com.zeti.auth.identity.presentation;

import com.zeti.auth.identity.application.AuthService;
import com.zeti.auth.identity.application.dto.LoginRequest;
import com.zeti.auth.identity.application.dto.RefreshRequest;
import com.zeti.auth.identity.application.dto.SignupRequest;
import com.zeti.auth.identity.application.dto.TokenResponse;
import com.zeti.auth.token.application.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@RequestBody SignupRequest request) {
        authService.signup(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest request) throws Exception {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshRequest request) throws Exception {
        try {
            return ResponseEntity.ok(authService.refresh(request.refreshToken()));
        } catch (AuthService.ReuseDetectedException e) {
            // 재사용 감지: family 폐기됨. 재인증 필요.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "reuse_detected");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid_refresh");
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest request) {
        // 데모: RT로 사용자를 식별해 전체 로그아웃(authVersion 증가 + family 폐기).
        refreshTokenService.resolveUserId(request.refreshToken())
                .ifPresent(authService::logoutAll);
        return ResponseEntity.noContent().build();
    }
}
