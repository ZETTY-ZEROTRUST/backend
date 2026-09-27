package com.zeti.auth.token.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.zeti.auth.token.domain.RefreshToken;
import com.zeti.auth.token.infrastructure.persistence.RefreshTokenRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** RT 회전·재사용 감지 로직 단위 검증(저장소는 mock). */
class RefreshRotationTest {

    private RefreshToken tokenWith(RefreshToken.Status status, String family, int gen) {
        RefreshToken t = RefreshToken.create(7L, family, "hash", gen,
                LocalDateTime.now().plusHours(1));
        ReflectionTestUtils.setField(t, "status", status);
        return t;
    }

    @Test
    void activeTokenRotatesAndConsumesOld() {
        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        RefreshToken active = tokenWith(RefreshToken.Status.ACTIVE, "F", 0);
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(active));
        RefreshTokenService svc = new RefreshTokenService(repo);
        ReflectionTestUtils.setField(svc, "refreshTtlSeconds", 3600L);

        RefreshTokenService.Rotation r = svc.rotate("raw");

        assertThat(r.ok()).isTrue();
        assertThat(r.reuseDetected()).isFalse();
        assertThat(active.getStatus()).isEqualTo(RefreshToken.Status.CONSUMED);
        verify(repo).save(any(RefreshToken.class)); // 다음 세대 저장
    }

    @Test
    void reusingConsumedTokenRevokesFamily() {
        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        RefreshToken consumed = tokenWith(RefreshToken.Status.CONSUMED, "F", 0);
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(consumed));
        RefreshTokenService svc = new RefreshTokenService(repo);

        RefreshTokenService.Rotation r = svc.rotate("raw");

        assertThat(r.reuseDetected()).isTrue();
        assertThat(r.ok()).isFalse();
        verify(repo).revokeFamily("F"); // family 전체 폐기
        verify(repo, never()).save(any());
    }

    @Test
    void unknownTokenIsInvalidNotReuse() {
        RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.empty());
        RefreshTokenService svc = new RefreshTokenService(repo);

        RefreshTokenService.Rotation r = svc.rotate("raw");

        assertThat(r.ok()).isFalse();
        assertThat(r.reuseDetected()).isFalse();
    }
}
