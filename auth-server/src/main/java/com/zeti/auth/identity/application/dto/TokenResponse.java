package com.zeti.auth.identity.application.dto;

public record TokenResponse(String accessToken, String refreshToken) {
}
