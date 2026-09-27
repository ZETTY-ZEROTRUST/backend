package com.zeti.auth.token.application;

import com.zeti.auth.token.domain.TokenLedgerEntry;
import com.zeti.auth.token.infrastructure.persistence.TokenLedgerRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 발급 시 대장에 digest를 기록한다. 등록에 실패하면 토큰을 응답하지 않는다(호출자 트랜잭션 내). */
@Service
@RequiredArgsConstructor
public class TokenLedgerService {

    private final TokenLedgerRepository repository;

    @Transactional
    public void record(JwtIssuer.Issued issued, Long sub) {
        LocalDateTime exp = issued.expiresAtEpoch() > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochSecond(issued.expiresAtEpoch()), ZoneOffset.UTC)
                : LocalDateTime.now(ZoneOffset.UTC).plusHours(1);
        repository.save(TokenLedgerEntry.of(issued.jti(), issued.digest(), sub, issued.kid(), exp));
    }
}
