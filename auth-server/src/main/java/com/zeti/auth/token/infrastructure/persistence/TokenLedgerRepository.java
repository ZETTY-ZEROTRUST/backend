package com.zeti.auth.token.infrastructure.persistence;

import com.zeti.auth.token.domain.TokenLedgerEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TokenLedgerRepository extends JpaRepository<TokenLedgerEntry, String> {

    @Query("select e.jti from TokenLedgerEntry e where e.sub = :sub "
            + "and e.status = com.zeti.auth.token.domain.TokenLedgerEntry.Status.ACTIVE")
    List<String> activeJtisOf(@Param("sub") Long sub);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update TokenLedgerEntry e set e.status = com.zeti.auth.token.domain.TokenLedgerEntry.Status.REVOKED "
            + "where e.sub = :sub and e.status <> com.zeti.auth.token.domain.TokenLedgerEntry.Status.REVOKED")
    int revokeAllForUser(@Param("sub") Long sub);
}
