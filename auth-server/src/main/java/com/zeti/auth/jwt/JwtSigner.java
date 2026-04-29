package com.zeti.auth.jwt;

public interface JwtSigner {
    String sign(String headerPayload) throws Exception;  // ← throws Exception 추가
}