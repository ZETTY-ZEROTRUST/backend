package com.zeti.bff.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmTokenCipherTest {

    private static final String AAD = "session-ref-A";
    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiI0MiJ9.signature-" + System.nanoTime();

    private static String newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private final AesGcmTokenCipher cipher = AesGcmTokenCipher.fromBase64(newKey(), 1);

    @Test
    void roundTrip() {
        byte[] sealed = cipher.encrypt(TOKEN, AAD);

        assertThat(cipher.decrypt(sealed, AAD)).isEqualTo(TOKEN);
    }

    @Test
    void ciphertextDoesNotContainPlaintextAndIvIsRandomPerEncryption() {
        byte[] a = cipher.encrypt(TOKEN, AAD);
        byte[] b = cipher.encrypt(TOKEN, AAD);

        assertThat(new String(a, StandardCharsets.ISO_8859_1)).doesNotContain(TOKEN);
        assertThat(a).isNotEqualTo(b);
        // IV(12) + 평문 길이 + tag(16)
        assertThat(a).hasSize(12 + TOKEN.getBytes(StandardCharsets.UTF_8).length + 16);
    }

    @Test
    void tamperedCiphertextFails() {
        byte[] sealed = cipher.encrypt(TOKEN, AAD);
        sealed[20] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(sealed, AAD)).isInstanceOf(VaultUnreadableException.class);
    }

    @Test
    void tamperedIvOrTagFails() {
        byte[] ivTampered = cipher.encrypt(TOKEN, AAD);
        ivTampered[0] ^= 0x01;
        byte[] tagTampered = cipher.encrypt(TOKEN, AAD);
        tagTampered[tagTampered.length - 1] ^= 0x01;

        assertThatThrownBy(() -> cipher.decrypt(ivTampered, AAD)).isInstanceOf(VaultUnreadableException.class);
        assertThatThrownBy(() -> cipher.decrypt(tagTampered, AAD)).isInstanceOf(VaultUnreadableException.class);
    }

    @Test
    void wrongAadFails_ciphertextMovedToAnotherSessionRow() {
        byte[] sealed = cipher.encrypt(TOKEN, AAD);

        assertThatThrownBy(() -> cipher.decrypt(sealed, "session-ref-B"))
                .isInstanceOf(VaultUnreadableException.class);
    }

    @Test
    void wrongKeyFails() {
        byte[] sealed = cipher.encrypt(TOKEN, AAD);
        AesGcmTokenCipher other = AesGcmTokenCipher.fromBase64(newKey(), 1);

        assertThatThrownBy(() -> other.decrypt(sealed, AAD)).isInstanceOf(VaultUnreadableException.class);
    }

    @Test
    void truncatedCiphertextFails() {
        assertThatThrownBy(() -> cipher.decrypt(new byte[10], AAD)).isInstanceOf(VaultUnreadableException.class);
        assertThatThrownBy(() -> cipher.decrypt(null, AAD)).isInstanceOf(VaultUnreadableException.class);
    }

    @Test
    void missingOrMalformedKeyFailsFastWithoutEchoingKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> AesGcmTokenCipher.fromBase64(null, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BFF_VAULT_KEY");
        assertThatThrownBy(() -> AesGcmTokenCipher.fromBase64("  ", 1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AesGcmTokenCipher.fromBase64("not base64 !!", 1))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("not base64 !!");
        assertThatThrownBy(() -> AesGcmTokenCipher.fromBase64(shortKey, 1))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> AesGcmTokenCipher.fromBase64(newKey(), 0))
                .isInstanceOf(IllegalStateException.class);
    }
}
