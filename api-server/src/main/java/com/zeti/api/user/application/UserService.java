package com.zeti.api.user.application;

import com.zeti.api.user.application.dto.UserResponse;
import com.zeti.api.user.application.dto.UserUpdateRequest;
import com.zeti.api.user.domain.User;
import com.zeti.api.user.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public UserResponse getById(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse update(Long userId, UserUpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        user.updateProfile(request.name(), request.phone());
        return UserResponse.from(user);
    }
}
