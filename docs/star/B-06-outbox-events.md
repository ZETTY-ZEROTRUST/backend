# B-06 보안 이벤트 생산: Transactional Outbox (security-event/2.0)

- 상태: 계획
- 연결: Jira A-06 · I-02(relay/indexer, 별도 트랙) · `docs/auth-token-architecture.md` §7 · 공용 계약 `shared/outbox-contract.md` · C-02 `log-pipeline/contracts/security-event/v2/` (MANIFEST revision `sha256:30ced95365487033d924fdbcd956f9fea629b09b997fd747e52466fddfae8e50`)
- 작성/갱신: 2026-09-27

## S — 문제 발생 (Situation)

- 인증·인가·업무 사실이 **이벤트로 남지 않는다.** auth-server·api-server 어디에도 보안 이벤트를 기록하는 코드가 없다.
  - api-server의 거부는 `SecurityContextHolder.clearContext()` 후 401로 끝난다(`api-server/.../JwtAuthenticationFilter.java:53-68,78-81`). 어떤 이유(서명·만료·미발급·폐기·version 불일치)로 거부했는지 남지 않는다.
  - `AuthStateVerifier.verify`는 `boolean`만 돌려준다(`AuthStateVerifier.java:23-45`). 거부 사유가 호출자에게도 전달되지 않는다.
  - auth-server의 로그인 실패·RT 재사용 감지는 예외/응답 코드로만 드러난다(`AuthService.java:46-50,59-63`).
- 그래서 UBA/탐지가 **신뢰할 입력이 없다.** 현재 v1 경로는 Nginx 접근 로그다.
  - Nginx가 raw `Authorization` 헤더를 그대로 로그에 쓴다(`log-pipeline/nginx-pep/uba.conf:33-34`, `"jwt":"$http_authorization"`).
  - 결과: ① bearer 원문이 로그·ES에 남는다. ② 서명 검증 전 claim을 decode해 `sub/jti`를 신원처럼 쓴다(검증 실패 요청도 "사용자 행동"이 된다). ③ 업무 commit/rollback을 알 수 없다.
- 거부 이벤트를 요청 트랜잭션 안에서 쓰면 업무 롤백과 함께 사라지고, 무조건 `REQUIRES_NEW`로 중첩하면 요청당 커넥션 2개를 잡아 pool 고갈 위험이 있다(owner 문서 §7).

## T — 왜 / 목표 (Task)

C-02 계약(security-event/2.0)을 통과하는 이벤트를 **MySQL Outbox**에 남긴다. relay·indexer(Redis Streams → ES)는 I-02 트랙이 맡고, 이번 범위는 producer(auth/api)까지다.

성공 기준(모두 자동 테스트로 판정):

1. `schema.sql`에 공용 계약의 `security_event_outbox`·`security_event_receipt`를 추가한다(기존 DDL 순서 유지).
2. 두 앱이 만드는 모든 이벤트가 **Java validator(draft 2020-12, format 검사 on)** 로 C-02 schema를 통과한다.
3. 같은 revision 증명: 복사한 schema·fixture의 sha256이 MANIFEST와 같고, valid fixture는 Java에서 전부 통과, schema 층 invalid fixture는 전부 실패한다.
4. api-server
   - 보호 요청마다 `ACCESS_DECISION`. authn 실패 reason(TOKEN_MISSING/MALFORMED/INVALID_SIGNATURE/EXPIRED/CLAIM_INVALID), 발급 검사 reason(NOT_ISSUED/REVOKED/VERSION_MISMATCH/STATE_UNAVAILABLE), 소유권 404는 `OBJECT_NOT_FOUND_OR_NOT_OWNED`.
   - **HTTP 응답 코드는 기존과 같다**(거부 사유 기록을 위해 동작을 바꾸지 않는다).
   - `PUT /users/me`, `PUT /addresses/{addressId}`: 업무 변경과 `BUSINESS_RESULT` outbox 행이 같은 트랜잭션으로 commit/rollback.
   - 거부·조회 감사는 요청 트랜잭션과 분리된 짧은 저장. 보호 **성공**의 감사 저장이 실패하면 503, 거부는 거부 응답 유지.
   - `route_template`은 서버 template(`/orders/{orderId}/detail`)이며 원시 ID·query가 없다.
5. auth-server: `AUTHENTICATION`(성공/실패, 실패는 `INVALID_CREDENTIALS`·actor null), `TOKEN_ISSUED`, `TOKEN_REFRESHED`, `TOKEN_REUSE`가 상태 변경과 같은 트랜잭션. 재사용 감지 시 family 폐기와 `TOKEN_REUSE`가 **함께 commit**되고 이후 예외로 롤백되지 않는다.
6. 어떤 payload에도 테스트에서 쓴 AT·RT·비밀번호 원문이 없다.
7. Java 21 컨테이너에서 두 앱 `./gradlew test bootJar` 통과.

## A — 어떻게 (Action)

### 계획

1. **테이블**: `api-server/src/main/resources/schema.sql` 끝에 공용 계약 DDL을 그대로 추가한다.
   - 파일 앞부분의 `DROP TABLE` 목록에는 넣지 않고 `CREATE TABLE IF NOT EXISTS`로 둔다. schema를 다시 적용해도 미발행 이벤트(원본)가 지워지지 않게 하기 위해서다.
   - H2 테스트는 JSON 타입 해석이 MySQL과 달라 테스트 전용 DDL에서 `payload`를 CLOB로 둔다(운영 DDL은 JSON 그대로).
2. **이벤트 빌더**(앱마다 작은 패키지 하나, Gradle 멀티모듈 재구성은 하지 않음)
   - schema 필드 순서대로 JSON을 만든다. `event_id` = UUID v4 소문자, `occurred_at` = UTC `Z`(마이크로초까지), `environment` = `local-secure`.
   - `request_id`: `X-Request-Id`가 UUID면 소문자로 사용. 다른 신원 헤더는 읽지 않는다.
   - 가명 = HMAC-SHA256(`EVENT_HMAC_KEY`, 목적 namespace + 원값) → base64url(패딩 없음), `key_version` = `EVENT_HMAC_KEY_VERSION`(기본 1). namespace는 두 앱이 같다(`subject`, `session`, `token`, `resource:<type>`).
   - 키가 없거나 32바이트 미만이면 **기동 실패**(임시 키로 조용히 동작하지 않는다).
   - actor/token_ref는 **검증을 통과한 신원**에서만 만든다. authn 실패·발급 검사 실패·상태 미확인이면 null.
3. **api-server — 접근 결정**
   - `JwtVerifier`는 실패를 reason이 있는 예외로, `AuthStateVerifier`는 `boolean` 대신 결과 enum을 돌려준다. 필터는 reason을 요청 attribute에 기록만 하고, 응답 코드는 그대로 둔다.
   - Spring Security 바깥을 감싸는 감사 필터가 route catalog(메서드 + template + 분류)와 매칭되는 요청만 처리한다. chain이 끝난 뒤(요청의 트랜잭션·커넥션이 모두 반납된 뒤) 최종 status로 `ACCESS_DECISION`을 만들어 autocommit INSERT 한 번으로 저장한다.
   - 응답 body는 `ContentCachingResponseWrapper`로 잡아 둔다. 성공(조회) 감사 저장이 실패하면 body를 버리고 503을 보낸다.
   - 쓰기 성공은 업무 트랜잭션의 `BUSINESS_RESULT`로 이미 감사가 보장된다. 이 경우 뒤따르는 `ACCESS_DECISION` 저장 실패는 로그만 남기고 응답을 바꾸지 않는다(이미 commit된 쓰기를 503으로 보이게 하지 않기 위해).
   - 소유권 거부는 서비스가 전용 예외(404 유지)를 던지고, 감사 필터가 이 예외를 `OBJECT_NOT_FOUND_OR_NOT_OWNED`로 분류한다.
   - route catalog와 실제 컨트롤러 매핑이 어긋나지 않는지 테스트로 고정한다.
4. **api-server — 업무 결과**
   - 서비스가 업무 변경 직후 같은 트랜잭션에서 `BUSINESS_RESULT(SUCCEEDED)`를 INSERT한다(JdbcTemplate가 JPA 트랜잭션의 커넥션에 참여).
   - 롤백되면 트랜잭션 동기화로 표시만 해 두고, 감사 필터가 트랜잭션 종료 뒤 `BUSINESS_RESULT(FAILED)`를 따로 저장한다(owner 문서 §7 표: rollback은 독립된 짧은 감사).
   - 업무 트랜잭션 안의 outbox 쓰기 실패는 업무를 함께 롤백하고 503으로 응답한다.
5. **auth-server**
   - 로그인 성공: 대장·RT 저장과 같은 트랜잭션에서 `AUTHENTICATION(SUCCESS)` + `TOKEN_ISSUED`.
   - 로그인 실패: 로그인 트랜잭션이 롤백된 **뒤** 짧은 autocommit INSERT로 `AUTHENTICATION(FAILURE, INVALID_CREDENTIALS)`. 응답(400·본문)은 그대로.
   - refresh: 회전·AT 발급·대장 기록·`TOKEN_REFRESHED`를 **한 트랜잭션**(`TransactionTemplate`)으로 묶는다.
   - 재사용 감지: 같은 트랜잭션에서 family 폐기 + `TOKEN_REUSE`를 정상 commit한 뒤, 트랜잭션 **밖에서** 예외를 던진다(기존 401 `reuse_detected` 유지).
6. **C-02 Java 검증**
   - `com.networknt:json-schema-validator:1.5.9`(test scope, draft 2020-12, `formatAssertionsEnabled=true`).
   - `scripts/sync-contracts.sh`가 schema 4개 + MANIFEST + security-event fixture를 두 앱 `src/test/resources/contracts/`로 복사한다.
   - pin 테스트: MANIFEST revision 상수 일치, MANIFEST의 파일 목록으로 revision 재계산 일치, 복사한 파일마다 sha256 일치.
   - fixture 테스트: valid 전부 통과, `layer=schema` invalid 전부 실패(가능하면 위반 규칙 title/위치까지 대조), `layer=parse` 중복 key는 Jackson strict 파싱 실패.
7. **테스트**: 업무+outbox 동시 commit, 업무 후 강제 예외 시 둘 다 없음, 로그인 실패 actor null, 재사용 시 family 폐기와 `TOKEN_REUSE` 존재, payload에 원문 토큰·비밀번호 없음.

### 검토한 대안과 선택 이유

| 대안 | 장점 | 문제 | 판단 |
|---|---|---|---|
| 요청 처리 중 Redis Streams/ES에 직접 쓰기 | 구현 단순, 지연 짧음 | DB commit과 원자적이지 않다(commit 후 XADD 실패 = 유실, XADD 후 롤백 = 없던 사실 기록). 네트워크 대기가 요청 경로에 들어간다. owner 문서 §7은 Redis AOF만으로 무손실을 주장하지 않는다 | 채택 안 함 |
| **Transactional Outbox**(MySQL 테이블 + 별도 relay) | 업무와 이벤트가 같은 commit. relay가 재시도·중복을 event_id로 흡수 | 테이블·relay 운영 필요, 이벤트 지연(폴링 주기), producer DB 쓰기 증가 | **채택**. 공용 계약이 이미 테이블·lease·receipt를 정했다 |
| CDC(Debezium, binlog → Kafka 등) | 앱 코드 변경 최소, 모든 변경 포착 | binlog·커넥터·Kafka 계열 인프라가 추가된다. "행 변경"이지 "인증 거부 사유" 같은 의미 이벤트가 아니다(거부는 DB 변경이 없음). 로컬 lab 규모에 과하다 | 채택 안 함. 나중에 outbox 테이블을 CDC로 읽는 전환은 가능 |

트랜잭션 경계:

| 대안 | 판단 |
|---|---|
| 거부 감사를 `REQUIRES_NEW`로 요청 트랜잭션 안에 중첩 | 요청당 커넥션 2개를 동시에 잡는다. 부하 시 pool 고갈(owner 문서 §7 금지) → 채택 안 함 |
| 감사 전용 작은 pool(DataSource 추가) | 업무 pool과 격리되지만, 모든 보호 요청이 감사를 쓰므로 작은 pool이 전체 처리량의 병목이 된다. DB 계정·설정도 늘어난다 → 채택 안 함 |
| 비동기 큐(메모리)에 넣고 나중에 저장 | 응답 전에 저장이 보장되지 않아 "성공 감사 실패 시 503" 정책을 지킬 수 없고, 프로세스 종료 시 유실 → 채택 안 함 |
| **요청 트랜잭션이 끝난 뒤 같은 pool에서 autocommit INSERT 1회** | 중첩이 없어 한 요청이 동시에 잡는 커넥션은 최대 1개. 성공 응답 전에 저장 결과를 알 수 있다 → **채택** |
| 업무 성공은 업무 트랜잭션 안에서 INSERT | 업무와 이벤트가 함께 commit/rollback → **채택**(owner 문서 §7 표) |

### 시행착오

(진행하며 날짜와 함께 추가)

## R — 개선 결과 (Result)

미측정

## 자소서 한 줄 (R 확정 후)

(R 확정 후 작성)
