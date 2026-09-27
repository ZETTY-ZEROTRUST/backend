package com.zeti.auth.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zeti.auth.token.application.port.outbound.JwtSigner;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
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

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
