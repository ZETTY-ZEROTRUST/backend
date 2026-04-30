// src/main/java/com/zeti/auth/domain/user/dto/LoginRequest.java
package com.zeti.auth.domain.user.dto;

public record LoginRequest(
        String email,
        String password
) {}