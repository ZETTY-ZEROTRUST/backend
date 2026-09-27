-- BFF 전용 토큰 vault. BFF만 읽고 쓰고 복호화한다(BFF 전용 DB 계정 권장, Auth/API 계정에는 권한 없음).
-- 원문 토큰은 저장하지 않는다.
--   at_ciphertext / rt_ciphertext = IV(12바이트) || AES-256-GCM(ciphertext || tag 16바이트), AAD = session_ref
--   key_version = 암호화에 사용한 BFF_VAULT_KEY 버전(키 회전 대비)
--   session_ref = 세션 ID와 별개인 난수(base64url 43자). 세션에는 이 값만 저장한다.
-- created_at 기준 절대 만료(기본 8시간)가 지난 행은 BFF가 주기적으로 삭제한다.
CREATE TABLE IF NOT EXISTS bff_token_vault (
    id            BIGINT          NOT NULL AUTO_INCREMENT,
    user_id       BIGINT          NOT NULL,
    session_ref   VARCHAR(64)     NOT NULL,
    at_ciphertext VARBINARY(8192) NOT NULL,
    rt_ciphertext VARBINARY(1024) NOT NULL,
    key_version   INT             NOT NULL,
    created_at    DATETIME(6)     NOT NULL,
    updated_at    DATETIME(6)     NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_bff_token_vault_session_ref UNIQUE (session_ref),
    KEY idx_bff_token_vault_user (user_id),
    KEY idx_bff_token_vault_created (created_at)
);
