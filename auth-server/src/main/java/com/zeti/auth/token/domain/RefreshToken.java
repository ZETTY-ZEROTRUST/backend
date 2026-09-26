package com.zeti.auth.token.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회전형 리프레시 토큰. 원문은 저장하지 않고 SHA-256 해시만 보관한다. */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    public enum Status { ACTIVE, CONSUMED, REVOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(nullable = false)
    private int generation;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "ENUM('ACTIVE','CONSUMED','REVOKED')")
    private Status status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public static RefreshToken create(Long userId, String familyId, String tokenHash,
                                      int generation, LocalDateTime expiresAt) {
        RefreshToken t = new RefreshToken();
        t.userId = userId;
        t.familyId = familyId;
        t.tokenHash = tokenHash;
        t.generation = generation;
        t.status = Status.ACTIVE;
        t.expiresAt = expiresAt;
        return t;
    }

    public void consume() { this.status = Status.CONSUMED; }
    public void revoke() { this.status = Status.REVOKED; }
    public boolean isActive() { return status == Status.ACTIVE; }
    public boolean isExpired(LocalDateTime now) { return expiresAt.isBefore(now); }
}
