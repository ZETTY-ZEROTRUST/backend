package com.zeti.auth.token.infrastructure.persistence;

import com.zeti.auth.token.domain.TokenLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TokenLedgerRepository extends JpaRepository<TokenLedgerEntry, String> {
}
