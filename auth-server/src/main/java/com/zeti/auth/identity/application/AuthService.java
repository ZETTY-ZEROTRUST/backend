package com.zeti.auth.identity.application;

import com.zeti.auth.identity.application.dto.LoginRequest;
import com.zeti.auth.identity.application.dto.SignupRequest;
import com.zeti.auth.identity.application.dto.TokenResponse;
import com.zeti.auth.identity.domain.User;
import com.zeti.auth.identity.infrastructure.persistence.UserRepository;
import com.zeti.auth.token.application.JwtIssuer;
import com.zeti.auth.token.application.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public void signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("이미 존재하는 이메일입니다.");
        }
        User user = User.create(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.name(),
                request.phone());
        userRepository.save(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) throws Exception {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        String accessToken = jwtIssuer.issue(user.getUserId(), user.getAuthVersion());
        RefreshTokenService.Issued rt = refreshTokenService.issueNewFamily(user.getUserId());
        return new TokenResponse(accessToken, rt.rawToken());
    }

    /** RT 회전. 재사용 감지 시 rotate가 자체 트랜잭션에서 family를 폐기(커밋)한 뒤 예외를 던진다.
     * 이 메서드에 @Transactional을 두면 예외 롤백으로 폐기가 취소되므로 트랜잭션 경계는 rotate가 갖는다. */
    public TokenResponse refresh(String refreshToken) throws Exception {
        RefreshTokenService.Rotation r = refreshTokenService.rotate(refreshToken);
        if (r.reuseDetected()) {
            throw new ReuseDetectedException();
        }
        if (!r.ok()) {
            throw new IllegalArgumentException("유효하지 않은 refresh token");
        }
        User user = userRepository.findById(r.userId())
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음"));
        String accessToken = jwtIssuer.issue(user.getUserId(), user.getAuthVersion());
        return new TokenResponse(accessToken, r.next().rawToken());
    }

    /** 전체 로그아웃: authVersion 증가(기존 AT 무효화) + 모든 RT family 폐기. */
    @Transactional
    public void logoutAll(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음"));
        user.bumpAuthVersion();
        refreshTokenService.revokeAllForUser(userId);
    }

    public static class ReuseDetectedException extends RuntimeException {
        public ReuseDetectedException() { super("refresh token 재사용 감지: family 폐기"); }
    }
}
