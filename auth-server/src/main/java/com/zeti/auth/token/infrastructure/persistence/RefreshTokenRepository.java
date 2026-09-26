package com.zeti.auth.token.infrastructure.persistence;

import com.zeti.auth.token.domain.RefreshToken;
import com.zeti.auth.token.domain.RefreshToken.Status;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyId(String familyId);

    // family 전체 폐기(재사용 감지 시). enum은 파라미터로 바인딩한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.status = com.zeti.auth.token.domain.RefreshToken.Status.REVOKED "
            + "where t.familyId = :familyId and t.status <> com.zeti.auth.token.domain.RefreshToken.Status.REVOKED")
    int revokeFamily(@Param("familyId") String familyId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.status = com.zeti.auth.token.domain.RefreshToken.Status.REVOKED "
            + "where t.userId = :userId and t.status <> com.zeti.auth.token.domain.RefreshToken.Status.REVOKED")
    int revokeAllForUser(@Param("userId") Long userId);
}
