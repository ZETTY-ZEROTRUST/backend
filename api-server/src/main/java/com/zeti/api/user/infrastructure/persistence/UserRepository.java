package com.zeti.api.user.infrastructure.persistence;

import com.zeti.api.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}
