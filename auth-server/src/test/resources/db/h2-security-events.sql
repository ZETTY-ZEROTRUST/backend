-- 테스트 전용 H2 DDL(MySQL 모드). 운영 DDL은 api-server/src/main/resources/schema.sql(같은 zeti_db).
-- 차이: payload는 JSON 대신 CLOB. H2의 JSON 타입은 문자열 파라미터를 "JSON 문자열 값"으로 저장해
-- 되읽으면 따옴표로 감싼 문자열이 된다. MySQL JSON 컬럼은 같은 문자열을 JSON 문서로 해석하므로 운영 의미는 그대로다.
-- ENUM/KEY 절은 H2 호환 형태(VARCHAR, UNIQUE 제약)로 바꿨다.
CREATE TABLE IF NOT EXISTS security_event_outbox (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  event_id      CHAR(36)     NOT NULL,
  producer      VARCHAR(16)  NOT NULL,
  event_type    VARCHAR(32)  NOT NULL,
  occurred_at   TIMESTAMP(6) NOT NULL,
  payload       CLOB         NOT NULL,
  status        VARCHAR(16)  DEFAULT 'PENDING' NOT NULL,
  lease_owner   VARCHAR(64),
  lease_until   TIMESTAMP(6),
  attempts      INT          DEFAULT 0 NOT NULL,
  published_at  TIMESTAMP(6),
  created_at    TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
  CONSTRAINT uk_outbox_event UNIQUE (event_id)
);

CREATE TABLE IF NOT EXISTS security_event_receipt (
  event_id    CHAR(36)     PRIMARY KEY,
  es_index    VARCHAR(64)  NOT NULL,
  indexed_at  TIMESTAMP(6) NOT NULL
);
