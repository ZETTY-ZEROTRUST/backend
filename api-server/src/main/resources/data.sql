-- ZETTY API Server: 시연용 더미 데이터
-- 500명 + 쿠팡 유출 카테고리(이름/이메일/주소록/현관비번/주문 5건/결제) 전 영역 재현.
-- 매 부팅마다 schema.sql 직후 자동 실행 (sql.init.mode=always).

-- 1) 1~500 시퀀스 임시 테이블
DROP TABLE IF EXISTS tmp_seq;
CREATE TEMPORARY TABLE tmp_seq (n INT PRIMARY KEY);
INSERT INTO tmp_seq (n)
WITH RECURSIVE r(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM r WHERE n < 500
)
SELECT n FROM r;

-- 2) 사용자 500명 (user_id는 AUTO_INCREMENT 140000000부터)
INSERT INTO users (email, password_hash, name, phone)
SELECT
  CONCAT('user', LPAD(n, 3, '0'), '@zetty.test'),
  'demo-password-hash-not-real',
  CONCAT('사용자', LPAD(n, 3, '0')),
  CONCAT('010-', LPAD((n * 37) % 10000, 4, '0'), '-', LPAD((n * 113) % 10000, 4, '0'))
FROM tmp_seq;

-- 3) 사용자별 기본 주소 1개 (500건). 시연 자산: door_password 평문.
INSERT INTO addresses (
  user_id, recipient_name, recipient_phone, postal_code,
  address_line1, address_line2, door_password, delivery_note, is_default
)
SELECT
  u.user_id, u.name, u.phone,
  LPAD((u.user_id % 99999), 5, '0'),
  CONCAT('서울시 송파구 올림픽로 ', (u.user_id % 500) + 1),
  CONCAT((u.user_id % 100) + 1, '동 ', (u.user_id % 30) + 1, '0', (u.user_id % 9) + 1, '호'),
  LPAD((u.user_id % 9999), 4, '0'),
  '부재 시 경비실에 맡겨주세요',
  TRUE
FROM users u;

-- 4) 짝수 user_id에 직장 주소 추가 (~250건). UBA 다양성용.
INSERT INTO addresses (
  user_id, recipient_name, recipient_phone, postal_code,
  address_line1, address_line2, door_password, delivery_note, is_default
)
SELECT
  u.user_id,
  CONCAT(u.name, ' (직장)'),
  u.phone,
  LPAD(((u.user_id + 333) % 99999), 5, '0'),
  CONCAT('서울시 강남구 테헤란로 ', (u.user_id % 700) + 1),
  CONCAT((u.user_id % 50) + 1, '층'),
  LPAD(((u.user_id + 7) % 9999), 4, '0'),
  '리셉션에 맡겨주세요',
  FALSE
FROM users u
WHERE u.user_id % 2 = 0;

-- 5) 사용자별 주문 5건 (시간순 + status 진행)
INSERT INTO orders (user_id, address_id, total_amount, status, ordered_at)
SELECT u.user_id, a.address_id, 19900 + (u.user_id % 50000), 'DELIVERED', DATE_SUB(NOW(), INTERVAL 30 DAY)
FROM users u JOIN addresses a ON a.user_id = u.user_id AND a.is_default = TRUE;

INSERT INTO orders (user_id, address_id, total_amount, status, ordered_at)
SELECT u.user_id, a.address_id, 12500 + (u.user_id % 30000), 'DELIVERED', DATE_SUB(NOW(), INTERVAL 21 DAY)
FROM users u JOIN addresses a ON a.user_id = u.user_id AND a.is_default = TRUE;

INSERT INTO orders (user_id, address_id, total_amount, status, ordered_at)
SELECT u.user_id, a.address_id, 8900 + (u.user_id % 20000), 'DELIVERED', DATE_SUB(NOW(), INTERVAL 14 DAY)
FROM users u JOIN addresses a ON a.user_id = u.user_id AND a.is_default = TRUE;

INSERT INTO orders (user_id, address_id, total_amount, status, ordered_at)
SELECT u.user_id, a.address_id, 22500 + (u.user_id % 40000), 'SHIPPED', DATE_SUB(NOW(), INTERVAL 7 DAY)
FROM users u JOIN addresses a ON a.user_id = u.user_id AND a.is_default = TRUE;

INSERT INTO orders (user_id, address_id, total_amount, status, ordered_at)
SELECT u.user_id, a.address_id, 15500 + (u.user_id % 25000), 'PAID', DATE_SUB(NOW(), INTERVAL 2 DAY)
FROM users u JOIN addresses a ON a.user_id = u.user_id AND a.is_default = TRUE;

-- 6) 끝자리 0,1 사용자에 PENDING 추가 (~100건). 어뷰징/장바구니 시연용.
INSERT INTO orders (user_id, address_id, total_amount, status, ordered_at)
SELECT u.user_id, a.address_id, 5500 + (u.user_id % 18000), 'PENDING', DATE_SUB(NOW(), INTERVAL 1 DAY)
FROM users u JOIN addresses a ON a.user_id = u.user_id AND a.is_default = TRUE
WHERE u.user_id % 10 IN (0, 1);

-- 7) 주문별 상품 1~3개 분산
INSERT INTO order_items (order_id, product_name, quantity, price)
SELECT
  o.order_id,
  ELT((o.order_id % 6) + 1,
    '쿠팡 미네랄워터 2L 12입', '곰표 밀가루 1kg', '서울우유 1L',
    '햇반 210g 24개입', '비비고 만두 350g', '하이트제로 350ml 24캔'),
  1 + (o.order_id % 5),
  o.total_amount * 0.5
FROM orders o;

INSERT INTO order_items (order_id, product_name, quantity, price)
SELECT
  o.order_id,
  ELT((o.order_id % 5) + 1,
    '롯데 칠성사이다 1.5L 6입', '오뚜기 진라면 5개입', '농심 새우깡',
    '풀무원 두부 300g', '동원 참치 100g 4입'),
  1 + (o.order_id % 3),
  o.total_amount * 0.3
FROM orders o
WHERE o.order_id % 3 != 0;

INSERT INTO order_items (order_id, product_name, quantity, price)
SELECT
  o.order_id,
  ELT((o.order_id % 4) + 1,
    '커클랜드 키친타올 12롤', '다우니 섬유유연제 5L',
    '아이깨끗해 핸드워시 500ml', '크리넥스 미용티슈 30입'),
  1,
  o.total_amount * 0.2
FROM orders o
WHERE o.order_id % 3 = 1;

-- 8) 사용자별 결제수단 1개. method/masked_info는 user_id 기반 순환.
INSERT INTO payments (user_id, method, masked_info, balance)
SELECT
  u.user_id,
  ELT((u.user_id % 3) + 1, 'CARD', 'BANK', 'ROCKET_PAY'),
  CASE (u.user_id % 3)
    WHEN 0 THEN CONCAT('****-****-****-', LPAD((u.user_id % 10000), 4, '0'))
    WHEN 1 THEN CONCAT('국민은행 ****-', LPAD((u.user_id % 10000), 4, '0'))
    ELSE CONCAT('쿠페이 #', LPAD((u.user_id % 10000), 4, '0'))
  END,
  (u.user_id % 100000) * 100
FROM users u;

-- 9) 결제수단별 결제내역 5건 (시간순)
INSERT INTO payment_history (payment_id, amount, description, paid_at)
SELECT p.payment_id, 19900 + (p.payment_id % 50000), '쿠팡 주문 결제', DATE_SUB(NOW(), INTERVAL 30 DAY)
FROM payments p;

INSERT INTO payment_history (payment_id, amount, description, paid_at)
SELECT p.payment_id, 12500 + (p.payment_id % 30000), '로켓프레시 정기결제', DATE_SUB(NOW(), INTERVAL 21 DAY)
FROM payments p;

INSERT INTO payment_history (payment_id, amount, description, paid_at)
SELECT p.payment_id, 5500 + (p.payment_id % 15000), '쿠팡플레이 월구독', DATE_SUB(NOW(), INTERVAL 14 DAY)
FROM payments p;

INSERT INTO payment_history (payment_id, amount, description, paid_at)
SELECT p.payment_id, 8800 + (p.payment_id % 20000), '와우 멤버십', DATE_SUB(NOW(), INTERVAL 7 DAY)
FROM payments p;

INSERT INTO payment_history (payment_id, amount, description, paid_at)
SELECT p.payment_id, 15500 + (p.payment_id % 25000), '로켓배송 결제', DATE_SUB(NOW(), INTERVAL 2 DAY)
FROM payments p;

-- 정리
DROP TEMPORARY TABLE tmp_seq;
