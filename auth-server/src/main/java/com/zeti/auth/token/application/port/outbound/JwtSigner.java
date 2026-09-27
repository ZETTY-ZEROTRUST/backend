package com.zeti.auth.token.application.port.outbound;

import com.nimbusds.jose.jwk.RSAKey;

public interface JwtSigner {
    /** base64url(header).base64url(payload)에 대한 RS256 서명(base64url)을 반환한다. */
    String sign(String headerPayload);

    /** 현재 서명키의 kid. */
    String keyId();

    /** JWKS로 공개할 공개키. 개인키 정보는 포함하지 않는다. */
    RSAKey publicJwk();
}
