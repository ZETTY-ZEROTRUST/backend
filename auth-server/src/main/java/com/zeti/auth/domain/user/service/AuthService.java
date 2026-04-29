// src/main/java/com/zeti/auth/domain/user/service/AuthService.java
package com.zeti.auth.domain.user.service;

import com.zeti.auth.domain.user.dto.LoginRequest;
import com.zeti.auth.domain.user.dto.SignupRequest;
import com.zeti.auth.domain.user.dto.TokenResponse;
import com.zeti.auth.domain.user.entity.User;
import com.zeti.auth.domain.user.repository.UserRepository;
import com.zeti.auth.jwt.JwtIssuer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtIssuer jwtIssuer;

    public void signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("이미 존재하는 이메일입니다.");
        }

        User user = User.create(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.name(),
                request.phone()
        );

        userRepository.save(user);
    }

    public TokenResponse login(LoginRequest request) throws Exception {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        String token = jwtIssuer.issue(user.getUserId());
        return new TokenResponse(token);
    }
}
