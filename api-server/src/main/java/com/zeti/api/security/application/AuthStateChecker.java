package com.zeti.api.security.application;

import com.zeti.api.user.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 보호 요청마다 AT의 authVersion을 MySQL 원본과 대조한다. positive cache를 두지 않는다(S2 회수 반영). */
@Component
@RequiredArgsConstructor
public class AuthStateChecker {

    private final UserRepository userRepository;

    /** 원본 authVersion과 클레임이 같아야 유효. 사용자 부재·불일치는 거부. */
    public boolean isCurrent(Long userId, int claimAuthVersion) {
        Integer current = userRepository.findAuthVersion(userId);
        return current != null && current == claimAuthVersion;
    }
}
