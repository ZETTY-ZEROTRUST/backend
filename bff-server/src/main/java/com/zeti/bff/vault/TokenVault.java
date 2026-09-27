package com.zeti.bff.vault;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 서버 측 토큰 보관소. 세션에는 여기서 발급한 {@code session_ref}만 둔다.
 * session_ref는 세션 ID와 별개의 난수다 — vault 행이 유출돼도 세션 쿠키 값을 알 수 없다.
 */
@Component
public class TokenVault {

    private static final int REF_BYTES = 32;

    private final TokenVaultRepository repository;
    private final AesGcmTokenCipher cipher;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public TokenVault(TokenVaultRepository repository, AesGcmTokenCipher cipher, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.clock = clock;
    }

    /** @return 새 session_ref */
    public String store(long userId, TokenPair tokens) {
        String ref = newSessionRef();
        repository.insert(userId, ref,
                cipher.encrypt(tokens.accessToken(), ref),
                cipher.encrypt(tokens.refreshToken(), ref),
                cipher.keyVersion(), clock.instant());
        return ref;
    }

    /**
     * @return 행이 없으면 empty(로그아웃·만료 정리됨)
     * @throws VaultUnreadableException 변조·키 불일치로 복호화할 수 없음
     */
    public Optional<TokenPair> load(String sessionRef) {
        return repository.find(sessionRef).map(row -> {
            if (row.keyVersion() != cipher.keyVersion()) {
                throw new VaultUnreadableException("알 수 없는 vault 키 버전: " + row.keyVersion());
            }
            return new TokenPair(
                    cipher.decrypt(row.atCiphertext(), sessionRef),
                    cipher.decrypt(row.rtCiphertext(), sessionRef));
        });
    }

    /** @return false면 행이 이미 삭제됨(동시 logout 등) */
    public boolean replace(String sessionRef, TokenPair tokens) {
        return repository.update(sessionRef,
                cipher.encrypt(tokens.accessToken(), sessionRef),
                cipher.encrypt(tokens.refreshToken(), sessionRef),
                cipher.keyVersion(), clock.instant()) == 1;
    }

    public void delete(String sessionRef) {
        repository.delete(sessionRef);
    }

    public int purgeCreatedBefore(Instant cutoff) {
        return repository.deleteCreatedBefore(cutoff);
    }

    private String newSessionRef() {
        byte[] b = new byte[REF_BYTES];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
