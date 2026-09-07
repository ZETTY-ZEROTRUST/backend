package com.zeti.api.user.presentation;

import com.zeti.api.user.application.UserService;
import com.zeti.api.user.application.dto.UserResponse;
import com.zeti.api.user.application.dto.UserUpdateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public UserResponse getMe(@AuthenticationPrincipal Long userId) {
        return userService.getById(userId);
    }

    @PutMapping("/me")
    public UserResponse updateUser(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UserUpdateRequest request) {
        return userService.update(userId, request);
    }
}
