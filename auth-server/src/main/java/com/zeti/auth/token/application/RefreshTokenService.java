package com.zeti.auth.token.application;

import com.zeti.auth.token.domain.RefreshToken;
import com.zeti.auth.token.infrastructure.persistence.RefreshTokenRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** RT 발급·회전·재사용 감지·폐기. 원문 RT는 저장하지 않고 SHA-256 해시만 대조한다. */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository repository;
    private final SecureRandom random = new SecureRandom();

    @Value("${jwt.refresh-ttl-seconds:28800}")
    private long refreshTtlSeconds;

    public record Issued(String rawToken, String familyId, int generation) {}

    public static String hash(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String randomToken() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Transactional
    public Issued issueNewFamily(Long userId) {
        String family = java.util.UUID.randomUUID().toString();
        return persist(userId, family, 0);
    }

    private Issued persist(Long userId, String family, int generation) {
        String raw = randomToken();
        repository.save(RefreshToken.create(userId, family, hash(raw), generation,
                LocalDateTime.now().plusSeconds(refreshTtlSeconds)));
        return new Issued(raw, family, generation);
    }

    /** 회전 결과. reuseDetected면 family를 폐기했고 새 토큰은 없다. */
    public record Rotation(boolean ok, boolean reuseDetected, Long userId, Issued next) {}

    @Transactional
    public Rotation rotate(String rawToken) {
        Optional<RefreshToken> found = repository.findByTokenHash(hash(rawToken));
        if (found.isEmpty()) {
            return new Rotation(false, false, null, null);
        }
        RefreshToken token = found.get();

        // 이미 소비·폐기된 RT의 재제시 = 재사용 공격. family 전체 폐기.
        if (token.getStatus() != RefreshToken.Status.ACTIVE) {
            repository.revokeFamily(token.getFamilyId());
            return new Rotation(false, true, token.getUserId(), null);
        }
        if (token.isExpired(LocalDateTime.now())) {
            token.revoke();
            return new Rotation(false, false, token.getUserId(), null);
        }
        // 정상 회전: 현재 토큰 소비 후 같은 family의 다음 세대 발급.
        token.consume();
        Issued next = persist(token.getUserId(), token.getFamilyId(), token.getGeneration() + 1);
        return new Rotation(true, false, token.getUserId(), next);
    }

    @Transactional
    public void revokeAllForUser(Long userId) {
        repository.revokeAllForUser(userId);
    }

    @Transactional(readOnly = true)
    public Optional<Long> resolveUserId(String rawToken) {
        return repository.findByTokenHash(hash(rawToken)).map(RefreshToken::getUserId);
    }
}
