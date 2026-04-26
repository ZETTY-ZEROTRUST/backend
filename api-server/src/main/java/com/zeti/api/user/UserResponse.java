package com.zeti.api.user;

import java.time.LocalDateTime;

public record UserResponse(
        Long userId,
        String email,
        String name,
        String phone,
        LocalDateTime createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getUserId(),
                user.getEmail(),
                user.getName(),
                user.getPhone(),
                user.getCreatedAt());
    }
}
