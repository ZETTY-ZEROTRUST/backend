package com.zeti.api.user;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // 정상 엔드포인트: JWT sub 기반 본인 조회
    @GetMapping("/me")
    public UserResponse getMe(@AuthenticationPrincipal Long userId) {
        return userService.getById(userId);
    }

    // 의도된 IDOR: path param userId vs JWT sub 일치 검증 없음 (ZETTY 시연 자산)
    @GetMapping("/{userId}")
    public UserResponse getUser(@PathVariable Long userId) {
        return userService.getById(userId);
    }

    // 의도된 IDOR: 타인 프로필 변조 가능 (ZETTY 시연 자산)
    @PutMapping("/{userId}")
    public UserResponse updateUser(
            @PathVariable Long userId,
            @Valid @RequestBody UserUpdateRequest request) {
        return userService.update(userId, request);
    }
}
