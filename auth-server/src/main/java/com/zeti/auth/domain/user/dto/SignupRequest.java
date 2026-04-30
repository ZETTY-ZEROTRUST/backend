// src/main/java/com/zeti/auth/domain/user/dto/SignupRequest.java
package com.zeti.auth.domain.user.dto;

public record SignupRequest(
        String email,
        String password,
        String name,
        String phone
) {}