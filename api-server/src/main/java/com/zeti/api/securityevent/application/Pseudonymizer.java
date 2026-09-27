package com.zeti.api.securityevent.application;

import com.zeti.api.securityevent.application.SecurityEvent.Actor;
import com.zeti.api.securityevent.application.SecurityEvent.KeyedRef;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 목적별 HMAC 가명: base64url(HMAC-SHA256(EVENT_HMAC_KEY, namespace + "\n" + 원값)), 패딩 없음(43자).
 * namespace·입력 형식은 auth-server 사본과 같아야 같은 사용자가 같은 subject_key로 이어진다(KAT 테스트로 고정).
 * 가명화는 익명화가 아니다. 키가 없거나 짧으면 기동을 실패시킨다(임시 키로 조용히 동작하지 않는다).
 */
@Component
public class Pseudonymizer {

    static final String NS_SUBJECT = "zetty.subject";
    static final String NS_SESSION = "zetty.session";
    static final String NS_TOKEN = "zetty.token";
    static final String NS_RESOURCE = "zetty.resource.";

    private static final int MIN_KEY_BYTES = 32;
    private static final Pattern VERSION_TOKEN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$");
    private static final Pattern RESOURCE_TYPE = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private final SecretKeySpec key;
    private final String keyVersion;

    public Pseudonymizer(@Value("${zetty.events.hmac-key:}") String base64Key,
                         @Value("${zetty.events.hmac-key-version:1}") String keyVersion) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.trim());
        } catch (IllegalArgumentException e) {
            // 값 자체는 메시지에 넣지 않는다.
            throw new IllegalStateException("EVENT_HMAC_KEY가 base64 형식이 아니다");
        }
        if (raw.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("EVENT_HMAC_KEY는 base64로 " + MIN_KEY_BYTES + "바이트 이상이어야 한다");
        }
        if (keyVersion == null || !VERSION_TOKEN.matcher(keyVersion).matches()) {
            throw new IllegalStateException("EVENT_HMAC_KEY_VERSION 형식이 올바르지 않다");
        }
        this.key = new SecretKeySpec(raw, "HmacSHA256");
        this.keyVersion = keyVersion;
    }

    public String keyVersion() {
        return keyVersion;
    }

    /** 검증된 subject(userId)와 session(검증된 LSID claim, 없으면 null). */
    public Actor actor(long userId, String sessionId) {
        String sessionKey = sessionId == null ? null : mac(NS_SESSION, sessionId);
        return new Actor(mac(NS_SUBJECT, Long.toString(userId)), sessionKey, keyVersion);
    }

    /** 검증된 jti. */
    public KeyedRef token(String jti) {
        return new KeyedRef(mac(NS_TOKEN, jti), keyVersion);
    }

    public KeyedRef resource(String resourceType, String id) {
        if (!RESOURCE_TYPE.matcher(resourceType).matches()) {
            throw new IllegalArgumentException("resource_type 형식 오류");
        }
        return new KeyedRef(mac(NS_RESOURCE + resourceType, id), keyVersion);
    }

    String mac(String namespace, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            byte[] out = mac.doFinal((namespace + "\n" + value).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 사용 불가", e);
        }
    }
}
