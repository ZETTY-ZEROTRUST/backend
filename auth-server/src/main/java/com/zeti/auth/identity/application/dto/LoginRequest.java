package com.zeti.auth.identity.application.dto;

public record LoginRequest(
        String email,
        String password
) {}
