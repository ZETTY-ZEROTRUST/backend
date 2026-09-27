package com.zeti.api.user.application;

import com.zeti.api.user.application.dto.UserResponse;
import com.zeti.api.user.application.dto.UserUpdateRequest;
import com.zeti.api.user.domain.User;
import com.zeti.api.user.infrastructure.persistence.UserRepository;
import com.zeti.api.mypage.application.MyPageCache;
import com.zeti.api.security.application.ObjectAccessDeniedException;
import com.zeti.api.securityevent.application.ApiSecurityEventRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final MyPageCache myPageCache;
    private final ApiSecurityEventRecorder securityEvents;

    public UserResponse getById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(ObjectAccessDeniedException::new);
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse update(Long userId, UserUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(ObjectAccessDeniedException::new);
        user.updateProfile(request.name(), request.phone());
        // 업무 변경과 같은 트랜잭션: 함께 commit되고 함께 롤백된다.
        securityEvents.recordCommittedWrite();
        myPageCache.evictAfterCommit(userId);
        return UserResponse.from(user);
    }
}
