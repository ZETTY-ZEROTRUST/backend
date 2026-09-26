package com.zeti.api.user.infrastructure.persistence;

import com.zeti.api.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    // 보호 요청마다 최신 authVersion을 원본에서 확인한다(positive cache 없음).
    @Query(value = "SELECT auth_version FROM users WHERE user_id = :id", nativeQuery = true)
    Integer findAuthVersion(@Param("id") Long id);
}
