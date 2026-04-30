// src/main/java/com/zeti/auth/jwt/JwtIssuer.java

package com.zeti.auth.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

@Component
public class JwtIssuer {

    private final JwtSigner jwtSigner;
    private final ObjectMapper objectMapper;
    private final long expiration;
    private final String kid;

    public JwtIssuer(
            JwtSigner jwtSigner,
            ObjectMapper objectMapper,
            @Value("${jwt.expiration}") long expiration,
            @Value("${jwt.kid}") String kid
    ) {
        this.jwtSigner = jwtSigner;
        this.objectMapper = objectMapper;
        this.expiration = expiration;
        this.kid = kid;
    }

    public String issue(Long userId) throws Exception {
        String headerJson = """
            {"alg":"ES256","kid":"%s","typ":"JWT"}
            """.strip().formatted(kid);
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));

        long now = System.currentTimeMillis() / 1000;

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("acr", "aal1");

        ArrayNode amrArray = payload.putArray("amr");
        amrArray.add("pwd");

        ArrayNode audArray = payload.putArray("aud");
        audArray.add("https://api.zeti.com");

        payload.put("auth_time", now);
        payload.put("client_id", "zeti-web");

        ObjectNode ext = payload.putObject("ext");
        ext.put("LSID", UUID.randomUUID().toString());
        ext.put("fiat", now);
        ext.put("v", 2);

        payload.put("iat", now);
        payload.put("iss", "https://auth.zeti.com/");
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("nbf", now);

        ArrayNode scpArray = payload.putArray("scp");
        scpArray.add("openid");
        scpArray.add("core");

        payload.put("sub", String.valueOf(userId));

        if (expiration > 0) {
            payload.put("exp", now + expiration);
        }

        String payloadJson = objectMapper.writeValueAsString(payload);
        String payloadEncoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

        String headerPayload = header + "." + payloadEncoded;
        String signature = jwtSigner.sign(headerPayload);

        return headerPayload + "." + signature;
    }
}
