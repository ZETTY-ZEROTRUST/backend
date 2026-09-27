package com.zeti.auth.token.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zeti.auth.token.application.port.outbound.JwtSigner;
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

    public JwtIssuer(
            JwtSigner jwtSigner,
            ObjectMapper objectMapper,
            @Value("${jwt.expiration}") long expiration
    ) {
        this.jwtSigner = jwtSigner;
        this.objectMapper = objectMapper;
        this.expiration = expiration;
    }

    /** @param lsid 로그인 상관 ID(ext.LSID). 보안 이벤트의 session 가명 원값으로만 쓴다. */
    public record Issued(String token, String jti, String digest, long expiresAtEpoch, String kid, String lsid) {}

    public Issued issue(Long userId, int authVersion) throws Exception {
        String headerJson = """
            {"alg":"RS256","kid":"%s","typ":"JWT"}
            """.strip().formatted(jwtSigner.keyId());
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
        String lsid = UUID.randomUUID().toString();
        ext.put("LSID", lsid);
        ext.put("fiat", now);
        ext.put("v", 2);

        payload.put("iat", now);
        payload.put("iss", "https://auth.zeti.com/");
        String jti = UUID.randomUUID().toString();
        payload.put("jti", jti);
        payload.put("nbf", now);

        ArrayNode scpArray = payload.putArray("scp");
        scpArray.add("openid");
        scpArray.add("core");

        payload.put("sub", String.valueOf(userId));
        payload.put("authv", authVersion);

        if (expiration > 0) {
            payload.put("exp", now + expiration);
        }

        String payloadJson = objectMapper.writeValueAsString(payload);
        String payloadEncoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

        String headerPayload = header + "." + payloadEncoded;
        String signature = jwtSigner.sign(headerPayload);
        String compact = headerPayload + "." + signature;

        String digest = sha256Hex(compact);
        long exp = expiration > 0 ? now + expiration : 0;
        return new Issued(compact, jti, digest, exp, jwtSigner.keyId(), lsid);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
