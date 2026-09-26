package com.zeti.auth.token.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 발급 증명 대장 항목. 정확히 발급한 compact JWT의 digest를 보관한다(원문 토큰은 저장 안 함). */
@Entity
@Table(name = "token_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TokenLedgerEntry {

    public enum Status { ACTIVE, REVOKED }

    @Id
    private String jti;

    @Column(nullable = false, length = 64)
    private String digest;

    @Column(nullable = false)
    private Long sub;

    @Column(nullable = false, length = 128)
    private String kid;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "ENUM('ACTIVE','REVOKED')")
    private Status status;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public static TokenLedgerEntry of(String jti, String digest, Long sub, String kid,
                                      LocalDateTime expiresAt) {
        TokenLedgerEntry e = new TokenLedgerEntry();
        e.jti = jti; e.digest = digest; e.sub = sub; e.kid = kid;
        e.status = Status.ACTIVE; e.expiresAt = expiresAt;
        return e;
    }
}
