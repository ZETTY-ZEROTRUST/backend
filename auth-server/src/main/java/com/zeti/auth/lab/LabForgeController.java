package com.zeti.auth.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zeti.auth.token.application.port.outbound.JwtSigner;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S3 위협 재현 전용(lab 프로필). 공격자가 KMS Sign 권한을 얻었지만 발급 대장에는 쓸 수 없는 상황을 모사한다.
 * 실제 서명키로 유효 서명을 만들되 token_ledger에는 기록하지 않는다. secure 기본 프로필에는 로드되지 않는다.
 */
@RestController
@RequestMapping("/lab")
@RequiredArgsConstructor
@Profile("lab")
public class LabForgeController {

    private final JwtSigner jwtSigner;
    private final ObjectMapper objectMapper;

    @PostMapping("/forge")
    public String forge(@RequestParam long sub,
                        @RequestParam(defaultValue = "0") int authv,
                        @RequestParam(required = false) String jti) throws Exception {
        String kid = jwtSigner.keyId();
        String header = b64("{\"alg\":\"RS256\",\"kid\":\"" + kid + "\",\"typ\":\"JWT\"}");

        long now = System.currentTimeMillis() / 1000;
        ObjectNode p = objectMapper.createObjectNode();
        p.put("sub", String.valueOf(sub));
        p.put("authv", authv);
        p.put("jti", jti != null ? jti : UUID.randomUUID().toString());
        p.put("iss", "https://auth.zeti.com/");
        p.putArray("aud").add("https://api.zeti.com");
        p.put("iat", now);
        p.put("nbf", now);
        p.put("exp", now + 600);
        String payload = b64(objectMapper.writeValueAsString(p));

        String headerPayload = header + "." + payload;
        // 실제 서명키로 서명(공격자가 Sign 권한을 얻은 상황). 대장 기록은 하지 않는다.
        return headerPayload + "." + jwtSigner.sign(headerPayload);
    }

    /**
     * 임의 claim을 실제 서명키로 서명한다(대장 미기록). v2:start에서 '서명은 유효하나 조건 위반'
     * 토큰(잘못된 iss/aud/typ, exp 없음 등)을 만들어 검증기 단독 방어를 시험한다.
     * 헤더 typ은 body의 "typ"을 쓰고(없으면 at+jwt), typ은 payload에서 제외한다.
     */
    @PostMapping("/forge-claims")
    public String forgeClaims(@RequestBody JsonNode claims) throws Exception {
        String typ = claims.hasNonNull("typ") ? claims.get("typ").asText() : "at+jwt";
        String header = b64("{\"alg\":\"RS256\",\"kid\":\"" + jwtSigner.keyId() + "\",\"typ\":\"" + typ + "\"}");
        ObjectNode payload = objectMapper.createObjectNode();
        claims.fields().forEachRemaining(e -> {
            if (!"typ".equals(e.getKey())) {
                payload.set(e.getKey(), e.getValue());
            }
        });
        String headerPayload = header + "." + b64(objectMapper.writeValueAsString(payload));
        return headerPayload + "." + jwtSigner.sign(headerPayload);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
