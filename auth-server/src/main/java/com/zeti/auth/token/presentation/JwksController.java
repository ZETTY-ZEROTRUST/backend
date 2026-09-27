package com.zeti.auth.token.presentation;

import com.nimbusds.jose.jwk.JWKSet;
import com.zeti.auth.token.application.port.outbound.JwtSigner;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtSigner jwtSigner;

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return new JWKSet(jwtSigner.publicJwk().toPublicJWK()).toJSONObject(true);
    }
}
