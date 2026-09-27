package com.zeti.api.security.infrastructure.jwks;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Auth가 공개한 JWKS에서 kid별 RSA 공개키를 가져온다. 설정된 URI만 사용하며 token의 jku/x5u는 따르지 않는다.
 * 미등록 kid는 최소 간격을 두고 한 번 재조회한다(임의 kid로 JWKS 요청 폭주를 막는다).
 */
@Slf4j
@Component
public class JwksPublicKeyProvider {

    private static final long REFRESH_MIN_INTERVAL_MS = 10_000;

    private final URI jwksUri;
    private final Clock clock;
    // 갱신 중 공백을 없애기 위해 맵 전체를 한 번에 교체한다(clear+putAll 대신 참조 스왑).
    private volatile Map<String, RSAPublicKey> keys = Map.of();
    private volatile long lastRefreshMs = Long.MIN_VALUE / 2;

    @Autowired
    public JwksPublicKeyProvider(@Value("${zetty.jwt.jwks-uri}") String jwksUri) {
        this(URI.create(jwksUri), Clock.systemUTC());
    }

    JwksPublicKeyProvider(URI jwksUri, Clock clock) {
        this.jwksUri = jwksUri;
        this.clock = clock;
    }

    public RSAPublicKey getPublicKey(String kid) {
        Map<String, RSAPublicKey> snapshot = keys;
        RSAPublicKey key = snapshot.get(kid);
        if (key == null && refreshAllowed()) {
            refresh();
            key = keys.get(kid);
        }
        if (key == null) {
            throw new IllegalArgumentException("알 수 없는 kid");
        }
        return key;
    }

    private synchronized boolean refreshAllowed() {
        long now = clock.millis();
        if (now - lastRefreshMs < REFRESH_MIN_INTERVAL_MS) {
            return false;
        }
        lastRefreshMs = now;
        return true;
    }

    void refresh() {
        try {
            replaceKeys(JWKSet.load(jwksUri.toURL(), 2_000, 2_000, 64 * 1024));
        } catch (Exception e) {
            log.warn("JWKS 조회 실패: {}", e.getClass().getSimpleName());
        }
    }

    void replaceKeys(JWKSet set) throws com.nimbusds.jose.JOSEException {
        Map<String, RSAPublicKey> loaded = new ConcurrentHashMap<>();
        for (JWK jwk : set.getKeys()) {
            if (jwk instanceof RSAKey rsa && rsa.getKeyID() != null) {
                loaded.put(rsa.getKeyID(), rsa.toRSAPublicKey());
            }
        }
        keys = loaded; // 원자적 참조 교체: 이전 맵을 읽던 요청은 그대로 유효
    }
}
