-- ZETTY API Server: 시연용 스키마
-- 쿠팡 2025 JWT 키 유출 사고 재현용. 민감 응답 fixture(door_password 평문) 포함.
-- spring.sql.init.mode=always 환경에서 매 부팅마다 재생성되므로 DROP 우선.

-- FK 역순으로 drop (의존하는 쪽부터)
DROP TABLE IF EXISTS payment_history;
DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS order_items;
DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS addresses;
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
