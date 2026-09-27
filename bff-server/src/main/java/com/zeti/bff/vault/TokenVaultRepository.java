package com.zeti.bff.vault;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code bff_token_vault} 접근. 암호문만 다룬다(복호화는 {@link TokenVault}). */
@Repository
public class TokenVaultRepository {

    public record Row(long userId, byte[] atCiphertext, byte[] rtCiphertext, int keyVersion) {
        @Override
        public String toString() {
            return "Row[userId=" + userId + ", keyVersion=" + keyVersion + "]";
        }
    }

    private final JdbcClient jdbc;

    public TokenVaultRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long userId, String sessionRef, byte[] at, byte[] rt, int keyVersion, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbc.sql("""
                        INSERT INTO bff_token_vault
                            (user_id, session_ref, at_ciphertext, rt_ciphertext, key_version, created_at, updated_at)
                        VALUES (:userId, :ref, :at, :rt, :keyVersion, :now, :now)
                        """)
                .param("userId", userId)
                .param("ref", sessionRef)
                .param("at", at)
                .param("rt", rt)
                .param("keyVersion", keyVersion)
                .param("now", ts)
                .update();
    }

    public Optional<Row> find(String sessionRef) {
        return jdbc.sql("""
                        SELECT user_id, at_ciphertext, rt_ciphertext, key_version
                          FROM bff_token_vault
                         WHERE session_ref = :ref
                        """)
                .param("ref", sessionRef)
                .query((rs, n) -> new Row(rs.getLong(1), rs.getBytes(2), rs.getBytes(3), rs.getInt(4)))
                .optional();
    }

    /** @return 갱신된 행 수(0이면 이미 삭제됨 — 동시 logout 등) */
    public int update(String sessionRef, byte[] at, byte[] rt, int keyVersion, Instant now) {
        return jdbc.sql("""
                        UPDATE bff_token_vault
                           SET at_ciphertext = :at, rt_ciphertext = :rt, key_version = :keyVersion, updated_at = :now
                         WHERE session_ref = :ref
                        """)
                .param("at", at)
                .param("rt", rt)
                .param("keyVersion", keyVersion)
                .param("now", Timestamp.from(now))
                .param("ref", sessionRef)
                .update();
    }

    public int delete(String sessionRef) {
        return jdbc.sql("DELETE FROM bff_token_vault WHERE session_ref = :ref")
                .param("ref", sessionRef)
                .update();
    }

    public int deleteCreatedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM bff_token_vault WHERE created_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }
}
