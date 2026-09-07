package com.zeti.auth.token.application.port.outbound;

public interface JwtSigner {
    String sign(String headerPayload) throws Exception;
}
