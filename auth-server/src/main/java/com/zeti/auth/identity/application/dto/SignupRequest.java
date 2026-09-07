package com.zeti.auth.identity.application.dto;

public record SignupRequest(
        String email,
        String password,
        String name,
        String phone
) {}
