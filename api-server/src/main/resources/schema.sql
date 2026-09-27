-- ZETTY API Server: 시연용 스키마
-- 쿠팡 2025 JWT 키 유출 사고 재현용. 민감 응답 fixture(door_password 평문) 포함.
-- spring.sql.init.mode=always 환경에서 매 부팅마다 재생성되므로 DROP 우선.

-- FK 역순으로 drop (의존하는 쪽부터)
DROP TABLE IF EXISTS payment_history;
DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS addresses;
DROP TABLE IF EXISTS response_command_log;
DROP TABLE IF EXISTS actor_identity_map;
DROP TABLE IF EXISTS token_ledger;
DROP TABLE IF EXISTS refresh_tokens;
DROP TABLE IF EXISTS users;

-- users: 이름, 이메일 유출 재현
CREATE TABLE users (
  user_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  email VARCHAR(255) UNIQUE NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  name VARCHAR(100) NOT NULL,
  phone VARCHAR(20),
  auth_version INT NOT NULL DEFAULT 0,   -- 전체 로그아웃/권한 회수 시 증가. AT 클레임과 대조.
  locked BOOLEAN NOT NULL DEFAULT FALSE, -- LOCK_ACCOUNT 집행 시 TRUE. 잠긴 계정은 로그인 거부.
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) AUTO_INCREMENT = 140000000;  -- 쿠팡 9자리 정수 모방

-- refresh_tokens: RT 회전·재사용 감지·family 폐기(S2). 원문 RT는 저장하지 않고 SHA-256 해시만.
CREATE TABLE refresh_tokens (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  family_id CHAR(36) NOT NULL,
  token_hash CHAR(64) NOT NULL UNIQUE,
  generation INT NOT NULL,
  status ENUM('ACTIVE','CONSUMED','REVOKED') NOT NULL DEFAULT 'ACTIVE',
  issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(user_id)
);
CREATE INDEX idx_rt_family ON refresh_tokens (family_id);
CREATE INDEX idx_rt_user ON refresh_tokens (user_id);

-- token_ledger: 발급 증명(S3). Auth만 기록. 정확히 발급한 compact JWT의 SHA-256 digest와 대조한다.
CREATE TABLE token_ledger (
  jti CHAR(36) PRIMARY KEY,
  digest CHAR(64) NOT NULL,
  sub BIGINT NOT NULL,
  kid VARCHAR(128) NOT NULL,
  status ENUM('ACTIVE','REVOKED') NOT NULL DEFAULT 'ACTIVE',
  issued_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  expires_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_ledger_sub ON token_ledger (sub);

-- ============================================================================
-- 대응 명령 집행(I-04, response-command/1.0). Auth가 같은 zeti_db에 기록한다.
-- 접근은 Auth 서비스 계정만 필요하다(BFF/브라우저 아님).
-- ============================================================================

-- actor_identity_map: 가명(subject_key) → 실제 userId 역매핑. 로그인 때 Auth가 upsert한다.
-- 명령은 raw userId를 싣지 않고 가명만 싣는다 — 이 매핑 권한은 집행 측(Auth)에만 있다.
-- subject_key = base64url HMAC-SHA256(zetty.subject, userId), 이벤트 actor.subject_key와 같은 값.
CREATE TABLE actor_identity_map (
  subject_key VARCHAR(128) PRIMARY KEY,
  user_id     BIGINT       NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(user_id)
);
CREATE INDEX idx_aim_user ON actor_identity_map (user_id);

-- response_command_log: 처리한 대응 명령의 멱등·감사 기록. command_id(PK)로 재전송을 한 번만 적용한다.
-- target_user_id는 가명을 해석한 실제 userId(미해석 거부는 NULL). 시각은 UTC.
CREATE TABLE response_command_log (
  command_id             CHAR(36)     PRIMARY KEY,   -- 멱등 키(재전송은 같은 값)
  action                 VARCHAR(32)  NOT NULL,      -- RATE_LIMIT|REQUIRE_REAUTH|REVOKE_SESSION|LOCK_ACCOUNT
  target_user_id         BIGINT       NULL,          -- 해석한 실제 userId(미해석 거부는 NULL)
  mode                   VARCHAR(16)  NOT NULL,      -- DRY_RUN|ENFORCE
  status                 VARCHAR(16)  NOT NULL,      -- APPLIED|ALREADY_APPLIED|REJECTED|FAILED|DRY_RUN
  reason                 VARCHAR(32)  NOT NULL,      -- response-result reason enum
  observed_state_version INT          NULL,          -- 집행 시점 authVersion
  applied_at             DATETIME(6)  NULL,          -- 상태 변경 commit 시각(APPLIED만)
  recorded_at            DATETIME(6)  NOT NULL,      -- 결과 기록 시각
  FOREIGN KEY (target_user_id) REFERENCES users(user_id)
);
CREATE INDEX idx_rcl_target ON response_command_log (target_user_id);

-- addresses: 배송지 주소록 유출 재현 (가장 민감)
CREATE TABLE addresses (
  address_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  recipient_name VARCHAR(100) NOT NULL,
  recipient_phone VARCHAR(20) NOT NULL,
  postal_code VARCHAR(10),
  address_line1 VARCHAR(255) NOT NULL,
  address_line2 VARCHAR(255),
  door_password VARCHAR(20),                 -- 시연 자산: 공동현관 비밀번호 평문
  delivery_note TEXT,
  is_default BOOLEAN DEFAULT FALSE,
  FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- orders: 주문 정보 유출 재현
CREATE TABLE orders (
  order_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  address_id BIGINT,
  total_amount DECIMAL(10, 2) NOT NULL,
  status ENUM('PENDING', 'PAID', 'SHIPPED', 'DELIVERED') DEFAULT 'PENDING',
  ordered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES users(user_id),
  FOREIGN KEY (address_id) REFERENCES addresses(address_id)
);

-- order_items: 주문 상품
CREATE TABLE order_items (
  item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  product_name VARCHAR(255) NOT NULL,
  quantity INT NOT NULL,
  price DECIMAL(10, 2) NOT NULL,
  FOREIGN KEY (order_id) REFERENCES orders(order_id)
);

-- payments: 쿠페이 모방
CREATE TABLE payments (
  payment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  method ENUM('CARD', 'BANK', 'ROCKET_PAY') NOT NULL,
  masked_info VARCHAR(50),
  balance DECIMAL(10, 2) DEFAULT 0,
  FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- payment_history: 결제 내역
CREATE TABLE payment_history (
  history_id BIGINT AUTO_INCREMENT PRIMARY KEY,
  payment_id BIGINT NOT NULL,
  amount DECIMAL(10, 2) NOT NULL,
  description VARCHAR(255),
  paid_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (payment_id) REFERENCES payments(payment_id)
);

-- 마이페이지 최근 주문·주문 목록 페이지네이션용. user_id로 seek 후 ordered_at 역순 정렬(filesort 제거).
CREATE INDEX idx_orders_user_ordered ON orders (user_id, ordered_at DESC);

-- 마이페이지 주소 정렬(user_id, is_default DESC, address_id) filesort 제거.
CREATE INDEX idx_addr_user_default ON addresses (user_id, is_default DESC, address_id);

-- ============================================================================
-- 보안 이벤트 Outbox (A-06 · I-02 공용 계약: shared/outbox-contract.md)
-- producer(auth/api)는 outbox INSERT만, relay는 outbox SELECT/UPDATE, indexer는 receipt INSERT/SELECT.
-- 위 DROP 목록에 넣지 않고 IF NOT EXISTS로 둔다: schema를 다시 적용해도 미발행 이벤트(원본)를 지우지 않는다.
-- payload는 C-02 security-event/2.0 schema를 통과한 문서다. event_id는 최초 생성 후 바뀌지 않는다.
-- ============================================================================
CREATE TABLE IF NOT EXISTS security_event_outbox (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id      CHAR(36)     NOT NULL,
  producer      VARCHAR(16)  NOT NULL,          -- auth | api | bff
  event_type    VARCHAR(32)  NOT NULL,
  occurred_at   DATETIME(6)  NOT NULL,          -- UTC, payload.occurred_at과 동일
  payload       JSON         NOT NULL,          -- security-event/2.0 문서(C-02 스키마 통과본)
  status        ENUM('PENDING','PUBLISHED') NOT NULL DEFAULT 'PENDING',
  lease_owner   VARCHAR(64)  NULL,
  lease_until   DATETIME(6)  NULL,
  attempts      INT          NOT NULL DEFAULT 0,
  published_at  DATETIME(6)  NULL,
  created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_outbox_event (event_id),
  KEY idx_outbox_pending (status, id)
);

CREATE TABLE IF NOT EXISTS security_event_receipt (
  event_id    CHAR(36)     PRIMARY KEY,
  es_index    VARCHAR(64)  NOT NULL,
  indexed_at  DATETIME(6)  NOT NULL
);
