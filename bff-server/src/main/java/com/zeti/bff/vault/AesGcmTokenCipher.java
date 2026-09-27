package com.zeti.bff.vault;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 토큰 암호화.
 * 저장 형식: {@code IV(12바이트) || ciphertext || tag(16바이트)}. IV는 암호화마다 새 난수다.
 * AAD는 호출자가 지정한다(vault는 session_ref) — 다른 행의 암호문을 옮겨 붙이면 복호화가 실패한다.
 */
public final class AesGcmTokenCipher {

    public static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKey key;
    private final int keyVersion;
    private final SecureRandom random = new SecureRandom();

    private AesGcmTokenCipher(byte[] rawKey, int keyVersion) {
        this.key = new SecretKeySpec(rawKey, "AES");
        this.keyVersion = keyVersion;
    }

    /**
     * base64 32바이트 키로 생성한다. 키가 없거나 형식이 틀리면 기동을 멈춘다(fail fast).
     * 예외 메시지에는 키 값을 넣지 않는다.
     */
    public static AesGcmTokenCipher fromBase64(String base64Key, int keyVersion) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("BFF_VAULT_KEY가 설정되지 않았다(base64 인코딩한 32바이트 키 필요)");
        }
        if (keyVersion <= 0) {
            throw new IllegalStateException("BFF_VAULT_KEY_VERSION은 양수여야 한다");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("BFF_VAULT_KEY가 올바른 base64가 아니다");
        }
        try {
            if (raw.length != KEY_BYTES) {
                throw new IllegalStateException("BFF_VAULT_KEY는 32바이트(AES-256)여야 한다");
            }
            return new AesGcmTokenCipher(raw, keyVersion);
        } finally {
            Arrays.fill(raw, (byte) 0); // SecretKeySpec은 복사본을 가진다.
        }
    }

    public int keyVersion() {
        return keyVersion;
    }

    public byte[] encrypt(String plaintext, String aad) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("vault 암호화 실패", e);
        }
    }

    public String decrypt(byte[] stored, String aad) {
        if (stored == null || stored.length < IV_BYTES + TAG_BITS / 8) {
            throw new VaultUnreadableException("vault 암호문 길이가 올바르지 않다");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException: 암호문/IV/태그 변조 또는 AAD·키 불일치
            throw new VaultUnreadableException("vault 암호문 인증 실패", e);
        }
    }
}
