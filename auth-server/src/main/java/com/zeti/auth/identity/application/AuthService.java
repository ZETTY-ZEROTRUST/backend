package com.zeti.auth.identity.application;

import com.zeti.auth.identity.application.dto.LoginRequest;
import com.zeti.auth.identity.application.dto.SignupRequest;
import com.zeti.auth.identity.application.dto.TokenResponse;
import com.zeti.auth.identity.domain.User;
import com.zeti.auth.identity.infrastructure.persistence.UserRepository;
import com.zeti.auth.securityevent.application.AuthEventRecorder;
import com.zeti.auth.token.application.JwtIssuer;
import com.zeti.auth.token.application.RefreshTokenService;
import com.zeti.auth.token.application.TokenLedgerService;
import com.zeti.auth.token.application.AuthStateCacheInvalidator;
import com.zeti.auth.token.infrastructure.persistence.TokenLedgerRepository;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;
    private final RefreshTokenService refreshTokenService;
    private final TokenLedgerService tokenLedgerService;
    private final TokenLedgerRepository tokenLedgerRepository;
    private final AuthStateCacheInvalidator cacheInvalidator;
    private final AuthEventRecorder securityEvents;
    private final TransactionTemplate transactionTemplate;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtIssuer jwtIssuer,
                       RefreshTokenService refreshTokenService,
                       TokenLedgerService tokenLedgerService,
                       TokenLedgerRepository tokenLedgerRepository,
                       AuthStateCacheInvalidator cacheInvalidator,
                       AuthEventRecorder securityEvents,
                       PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtIssuer = jwtIssuer;
        this.refreshTokenService = refreshTokenService;
        this.tokenLedgerService = tokenLedgerService;
        this.tokenLedgerRepository = tokenLedgerRepository;
        this.cacheInvalidator = cacheInvalidator;
        this.securityEvents = securityEvents;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

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

    /**
     * 로그인. 자격 증명 확인 → AT 발급·대장 기록 → RT family 생성 → AUTHENTICATION·TOKEN_ISSUED outbox를
     * 한 트랜잭션으로 commit한다. 실패하면 그 트랜잭션이 롤백된 뒤 AUTHENTICATION(FAILURE)을 따로 짧게 저장하고,
     * 이전과 같은 예외(400, 같은 메시지)를 던진다.
     */
    public TokenResponse login(LoginRequest request) throws Exception {
        try {
            return inTransaction(() -> loginInTransaction(request));
        } catch (InvalidCredentialsException e) {
            securityEvents.loginFailed();
            throw e;
        }
    }

    private TokenResponse loginInTransaction(LoginRequest request) throws Exception {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        JwtIssuer.Issued at = jwtIssuer.issue(user.getUserId(), user.getAuthVersion());
        tokenLedgerService.record(at, user.getUserId());
        RefreshTokenService.Issued rt = refreshTokenService.issueNewFamily(user.getUserId());
        securityEvents.loginSucceeded(user.getUserId(), at.lsid(), at.jti());
        return new TokenResponse(at.token(), rt.rawToken());
    }

    /**
     * RT 회전. 회전(소비+다음 세대)·새 AT 발급·대장 기록·TOKEN_REFRESHED를 한 트랜잭션으로 묶는다.
     * 재사용 감지 시 family 폐기와 TOKEN_REUSE를 같은 트랜잭션에서 정상 commit한 뒤, 트랜잭션 밖에서 예외를 던진다.
     * (예외를 트랜잭션 안에서 던지면 폐기까지 롤백되므로 경계 밖으로 뺀다.)
     */
    public TokenResponse refresh(String refreshToken) throws Exception {
        RefreshOutcome outcome = inTransaction(() -> rotateInTransaction(refreshToken));
        if (outcome.reuseDetected()) {
            if (!outcome.reuseEventStored()) {
                securityEvents.tokenReuseDetectedAfterCommit();
            }
            throw new ReuseDetectedException();
        }
        if (outcome.tokens() == null) {
            throw new IllegalArgumentException("유효하지 않은 refresh token");
        }
        return outcome.tokens();
    }

    private record RefreshOutcome(TokenResponse tokens, boolean reuseDetected, boolean reuseEventStored) {
    }

    private RefreshOutcome rotateInTransaction(String refreshToken) throws Exception {
        RefreshTokenService.Rotation r = refreshTokenService.rotate(refreshToken);
        if (r.reuseDetected()) {
            boolean stored = securityEvents.tokenReuseDetected();
            return new RefreshOutcome(null, true, stored);
        }
        if (!r.ok()) {
            // 미등록·만료 RT: 만료 RT의 폐기 표시는 이전과 같이 commit된다.
            return new RefreshOutcome(null, false, false);
        }
        User user = userRepository.findById(r.userId())
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음"));
        JwtIssuer.Issued at = jwtIssuer.issue(user.getUserId(), user.getAuthVersion());
        tokenLedgerService.record(at, user.getUserId());
        securityEvents.tokenRefreshed(user.getUserId(), at.lsid(), at.jti());
        return new RefreshOutcome(new TokenResponse(at.token(), r.next().rawToken()), false, false);
    }

    /** 전체 로그아웃: authVersion 증가(기존 AT 무효화) + 모든 RT family 폐기. */
    @Transactional
    public void logoutAll(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자 없음"));
        // 캐시 무효화용 jti는 대장 폐기 전에 확보한다.
        List<String> jtis = tokenLedgerRepository.activeJtisOf(userId);
        user.bumpAuthVersion();
        refreshTokenService.revokeAllForUser(userId);
        tokenLedgerRepository.revokeAllForUser(userId);
        cacheInvalidator.invalidate(jtis);
    }

    @FunctionalInterface
    private interface TransactionalWork<T> {
        T run() throws Exception;
    }

    /**
     * TransactionTemplate 실행. 서명기 등의 체크 예외도 롤백시키고, 롤백 뒤 원래 예외 타입으로 다시 던진다
     * (호출자·HTTP 응답은 이전과 같은 예외를 본다).
     */
    private <T> T inTransaction(TransactionalWork<T> work) throws Exception {
        try {
            return transactionTemplate.execute(status -> {
                try {
                    return work.run();
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CheckedFailure(e);
                }
            });
        } catch (CheckedFailure failure) {
            throw failure.checked;
        }
    }

    private static final class CheckedFailure extends RuntimeException {
        private final Exception checked;

        private CheckedFailure(Exception checked) {
            super(null, checked, false, false);
            this.checked = checked;
        }
    }

    /** 이메일 없음·비밀번호 불일치(구분하지 않음). 기존 400 응답·메시지를 그대로 유지한다. */
    public static class InvalidCredentialsException extends IllegalArgumentException {
        public InvalidCredentialsException() {
            super("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
    }

    public static class ReuseDetectedException extends RuntimeException {
        public ReuseDetectedException() { super("refresh token 재사용 감지: family 폐기"); }
    }
}
