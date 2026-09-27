# B-04 BFF 1단계: 브라우저에서 AT·RT 제거 (세션 쿠키 + 암호화 vault + 제한 proxy)

- 상태: 완료(1단계)(1단계 구현·자동 테스트 완료 / Compose·실 Redis·MySQL 연결 전)
- 연결: Jira A-03(1단계) · `docs/auth-token-architecture.md` §2·§4·§5
- 작성/갱신: 2026-09-27

## S — 문제 발생

- `POST /auth/login`이 AT·RT **원문**을 JSON으로 응답한다(`auth-server/.../AuthController.java:32-35`, `TokenResponse(accessToken, refreshToken)`).
- 그래서 브라우저(클라이언트 JS)가 두 토큰을 직접 들고 있어야 한다.
  - XSS 한 번이면 AT(900초)뿐 아니라 **RT(family 최대 8시간)** 까지 외부로 반출된다.
  - 공격자는 반출한 RT로 회전을 이어가며 세션을 연장할 수 있다. 원래 사용자가 먼저 회전하면 재사용 감지로 family가 폐기되지만, 그 전까지는 공격자가 유효 토큰을 쥔다.
- `/auth/refresh`·`/auth/logout`도 RT 원문을 body로 받는다(`AuthController.java:37-56`). 즉 클라이언트가 RT를 보관한다는 전제다.
- 시연 스크립트도 토큰을 파일에 저장한다(`scripts/02_login.sh` → `scripts/.token`).
- 채택한 목표 구조(BFF)의 모듈이 없다(auth-token-architecture §1 표: "BFF 모듈 없음; 기존 JSON login은 AT를 응답").

## T — 왜 / 목표

- 브라우저는 **불투명 세션 쿠키 `__Host-zetty-session`만** 받는다. AT/RT는 BFF 서버 측 암호화 vault에만 있다.
- 1단계 범위: 기존 JSON login(`/auth/login`)을 **서버 간 호출**로 감싸는 BFF를 만든다.
  - Authorization Server(Code+PKCE) 전환, 두 BFF 인스턴스 간 refresh 조정, Compose·Nginx 연결은 이후 단계로 둔다.
- 성공 기준(모두 자동 테스트로 판정)
  1. `/bff/login` 응답 body·헤더, 세션 attribute, vault 컬럼 어디에도 AT/RT 원문이 없다.
  2. 로그인하면 세션 ID가 바뀐다(세션 고정 방어).
  3. vault: AES-256-GCM 암·복호화 왕복 성공. 암호문 변조·AAD 불일치는 복호화 실패.
  4. proxy: allowlist 밖 경로는 404. 브라우저가 보낸 Authorization·Cookie·X-Forwarded-*는 제거하고 vault의 AT만 붙인다.
  5. 상태 변경 요청(PUT 등)·logout에 CSRF 토큰이 없거나 Origin이 다르면 403.
  6. AT가 만료된 상태에서 동시 요청 N개 → Auth refresh 호출은 **정확히 1회**, 모든 요청 성공.
  7. refresh가 401(재사용 감지 등)이면 vault 행 삭제 + 세션 무효화 + 브라우저에 401.
  8. logout: Auth `/auth/logout` 호출, vault 행 삭제, 세션 무효화, 204.
  9. 로그인~logout 흐름의 로그(DEBUG 포함)에 토큰·비밀번호 원문이 없다.
  10. Java 21 컨테이너에서 `./gradlew test bootJar` 통과.

## A — 어떻게

### 계획

1. 모듈 `bff-server/`: Java 21 toolchain, Boot 3.5.14, Actuator + Prometheus(management 9090), api-server와 같은 형식의 Dockerfile(`JAVA_OPTS` ENTRYPOINT), H2 test profile.
2. **세션**: Spring Session Data Redis, namespace `zetty:bff:session`.
   - 쿠키 `__Host-zetty-session`: Secure·HttpOnly·SameSite=Lax·Path=/·Domain 없음.
   - 세션 attribute는 **vault 참조 ID·userId·로그인 시각·CSRF 토큰**만 둔다.
   - Spring Security context는 세션에 저장하지 않는다. 요청마다 세션 attribute로 인증 객체를 만든다.
3. **vault**: MySQL `bff_token_vault`(DDL은 `bff-server/src/main/resources/bff-schema.sql`).
   - AES-256-GCM, 암호화마다 12바이트 난수 IV, AAD = `session_ref`.
   - 키는 `BFF_VAULT_KEY`(base64 32바이트). 없거나 길이가 틀리면 기동 실패.
   - `session_ref`는 세션 ID와 별개의 난수다. vault 행이 새도 쿠키 값을 알 수 없다.
4. **엔드포인트**
   - `POST /bff/login`: Auth에 서버 간 로그인 → vault 저장 → 세션 교체 → `{userId, csrfToken}`만 응답.
   - `GET /bff/session`: 새로고침 후 CSRF 토큰 재발급용.
   - `GET|POST|PUT|DELETE /bff/api/**`: allowlist proxy.
   - `POST /bff/logout`: Auth logout → vault 삭제 → 세션 무효화.
5. **proxy**
   - (method, path) allowlist만 통과: `/users/me`, `/mypage`, `/orders`, `/orders/*/detail`, `/addresses`, `/addresses/*`, `/payments/balance`, `/payments/history`.
   - 헤더는 허용 목록(Content-Type, Accept)만 복사한다. 결과적으로 브라우저의 Authorization·Cookie·X-Forwarded-*가 API로 가지 않는다.
   - redirect를 따라가지 않는다(Authorization 전달 방지). 응답은 `Cache-Control: no-store`.
6. **refresh**: API 401 → 세션 vault 항목당 single-flight refresh(나머지는 대기 후 결과 공유) → 원 요청 1회 재시도.
   - API의 401은 인증 필터가 업무 실행 전에 내는 응답이다(`api-server/.../SecurityConfig.java` entry point). 그래서 상태 변경 요청도 1회 재시도를 허용한다.
   - refresh 401 → vault 삭제·세션 무효화·401.
7. **CSRF**: Spring Security CsrfFilter를 끄지 않는다.
   - 저장소는 세션, 헤더는 `X-CSRF-Token`. 세션이 없는 요청에는 새 세션을 만들지 않는 저장소 래퍼를 쓴다(익명 세션 남발 방지).
   - `/bff/login`만 CSRF 토큰 대신 Origin 검사로 막는다(로그인 전에는 세션이 없음).
   - 모든 unsafe 메서드에 `Origin` 정확 일치(`BFF_ALLOWED_ORIGIN`)를 요구한다.
8. **로그 위생**: 요청/응답 DTO의 `toString`을 마스킹한다(Spring DEBUG 로그가 body를 출력해도 원문 없음). 토큰·쿠키·비밀번호를 로그에 남기지 않는다.
9. **테스트**: MockWebServer로 auth/api stub. H2 test profile. 세션 저장소는 테스트에서 in-memory(`MapSessionRepository`)로 교체한다. 테스트 키는 테스트 안에서 생성한다.

### 검토한 대안과 선택 이유

| 비교 | 대안 | 선택 | 이유 |
|---|---|---|---|
| 브라우저 토큰 보관 | AT 메모리 + RT HttpOnly 쿠키 | **BFF**(불투명 세션 쿠키, 서버 vault) | 대안도 RT는 JS에서 숨기지만 AT는 JS가 다룬다(XSS 시 AT 반출). refresh/logout 쿠키 경로의 CSRF·CORS·멀티탭 refresh 경합도 브라우저 쪽에서 풀어야 한다. BFF는 토큰이 브라우저에 없다. 대가는 BFF의 상태·가용성 부담. 둘 다 XSS가 사용자 권한으로 요청을 실행하는 것은 막지 못한다(CSP 등 별도 필요) |
| 토큰 저장 위치 | Redis 세션 attribute에 AT/RT 저장 | **세션엔 참조 ID만, MySQL vault에 AEAD 암호문** | 대안은 Redis 덤프·세션 직렬화·모니터링 노출이 곧 원문 토큰 유출이다. 선택안은 BFF만 가진 키로 복호화하고, DB 권한을 분리하고, `key_version`으로 키를 회전할 수 있다. 비용은 proxy 요청마다 DB 조회 1회 + 복호화 |
| refresh 동시성 | 요청마다 개별 refresh | **세션별 single-flight** | 개별 refresh면 이미 회전된 RT를 두 번째 요청이 다시 제시한다. Auth가 재사용으로 판정해 family를 폐기한다 → 정상 사용자가 강제 로그아웃된다 |
| 로그인 방식 | Authorization Server Code+PKCE(문서 최종 목표) | **기존 `/auth/login` 서버 간 호출** | 1단계 목표는 "브라우저에서 토큰 제거"다. Auth 서버 전환은 별도 단계로 분리한다 |
| 테스트 stub | WireMock | **MockWebServer** | 의존성이 가볍고, 요청 기록·호출 횟수 확인이 쉽다 |

### 1단계에서 하지 않는 것 (한계)

- 두 BFF 인스턴스 간 refresh 조정(DB lease/version). 이번에는 인스턴스 내부 single-flight만 한다.
- Auth logout 실패 시 내구성 있는 폐기 재시도 작업. 이번에는 '서버 측 회수 미확인'을 로그·지표·응답 헤더로만 구분한다.
- Compose·Nginx·TLS 연결, Authorization Server 전환, XFF provenance 전달.

### 구현하며 정한 것과 owner 문서(auth-token-architecture)와 다른 점

| 항목 | 1단계 구현 | 문서 목표 / 남은 결정 |
|---|---|---|
| 로그인 | 기존 `/auth/login`을 서버 간 호출 | §4 Code+PKCE·OIDC(Authorization Server 전환 후) |
| userId | Auth 응답 AT의 `sub`를 서명 검증 없이 읽음(표시·vault 소유자용, 인가 근거 아님) | Code 흐름 전환 시 ID token 검증값으로 교체 |
| `/bff/login` CSRF | 세션·토큰이 없으므로 Origin 정확 일치만 | 로그인 트랜잭션 쿠키 도입 시 재검토 |
| refresh 조정 | 인스턴스 내부 single-flight | §5 DB lease/version(두 BFF 인스턴스) |
| 401 후 재시도 | 상태 변경 요청도 1회 재시도(API 401 = 업무 실행 전 거부라는 현재 계약에 의존) | API가 업무 후 401을 내면 재검토, idempotency 계약 |
| logout | Auth `/auth/logout`은 전체 로그아웃(authVersion 증가 → 다른 기기도 종료) | 현재 세션 logout과 전체 logout 구분 |
| 회수 실패 | 로그·지표(`bff_logout_total{server_revocation}`)·응답 헤더로 '미확인' 표시만 | 원문 비밀 없는 내구성 있는 폐기 재시도 작업 |
| 재로그인 | 같은 브라우저 재로그인 시 이전 vault 행만 삭제(이전 RT family는 Auth에서 만료까지 유효) | 이전 family 폐기 여부 |
| 세션 직렬화 | Spring Session 기본(JDK 직렬화). attribute는 Long·String·CSRF 토큰뿐 | Redis 쓰기 권한 = 역직렬화 위험 → 내부망·AUTH 필수, JSON 직렬화 검토 |
| vault 키 회전 | 활성 키 1개. 다른 `key_version` 행은 복호화 불가 → 재로그인 | 이전 키 병행 복호화 |
| proxy 메서드 | 경로마다 API가 실제 제공하는 메서드만 허용(현재 GET·PUT) | 새 API 추가 시 allowlist 갱신 |
| DDL 적용 | `BFF_SCHEMA_INIT_MODE` 기본 never(별도 적용) | BFF 전용 DB 계정·권한 |

### Compose 연결에 필요한 env (값은 기록하지 않음)

| env | 용도 |
|---|---|
| `BFF_VAULT_KEY` | **필수**. base64 32바이트 vault 키. 실행 환경에서 생성, Git·로그 금지 |
| `BFF_VAULT_KEY_VERSION` | 키 버전(기본 1) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | vault MySQL(BFF 전용 계정 권장) |
| `REDIS_HOST`, `REDIS_PORT` | 세션 Redis(기본 localhost:6379). AUTH 사용 시 `SPRING_DATA_REDIS_PASSWORD` |
| `AUTH_BASE_URL`, `API_BASE_URL` | 서버 간 호출 대상(기본 `http://auth:8080`, `http://api:8081`) |
| `BFF_ALLOWED_ORIGIN` | 브라우저가 보는 정확한 origin(기본 `https://127.0.0.1:8443`) |
| `BFF_SCHEMA_INIT_MODE` | `always`면 기동 시 `bff-schema.sql` 실행(기본 never) |
| `BFF_SESSION_IDLE_TIMEOUT`, `BFF_SESSION_ABSOLUTE_TIMEOUT` | 세션 유휴 30m / 절대 8h |
| `JAVA_OPTS`, `TOMCAT_*`, `DB_POOL_MAX`, `DB_CONN_TIMEOUT_MS` | api-server와 같은 튜닝 항목. BFF 포트 8082, management 9090 |

### 시행착오

- 2026-09-27 첫 테스트에서 55건 중 7건 실패. 로그인 직후 **두 번째 요청부터 401**, logout 후에도 세션이 남음.
  - 원인: `sessionManagement().sessionCreationPolicy(NEVER)`를 지정하자 Spring Security가 `SessionManagementFilter`를 추가했다.
  - 이 필터는 요청마다 세션 attribute로 만든 인증을 "새 로그인"으로 보고 **세션 ID와 CSRF 토큰을 매 요청 교체**했다(`ChangeSessionIdAuthenticationStrategy`, `CsrfAuthenticationStrategy`). 브라우저가 가진 쿠키·CSRF 토큰이 매번 무효가 된다.
  - 같은 필터가 DEBUG에서 세션 ID를 로그에 출력했다("Changed session id from …") → 로그 위생 위반이기도 했다.
  - `requireExplicitAuthenticationStrategy(true)`를 함께 쓰려 했으나 Spring Security가 "정책 지정 + 명시 전략" 조합을 기동 오류로 거부했다.
  - 조치: `sessionManagement` 설정을 제거(Spring Security 쪽 세션 생성 경로는 request cache 비활성·요청 속성 SecurityContext·세션 없으면 저장 안 하는 CSRF 저장소로 이미 막음). 세션 고정 방어는 `/bff/login`에서 직접 수행한다.
  - 회귀 테스트: 같은 쿠키로 연속 요청 시 `Set-Cookie` 없음·세션 유지(`ApiProxyTest.consecutiveRequestsKeepSameSessionAndCsrfToken`).

## R — 개선 결과

실행: 2026-09-27, `eclipse-temurin:21-jdk` 컨테이너, `./gradlew --no-daemon test bootJar` → exit 0, BUILD SUCCESSFUL.
테스트 환경: auth/api는 MockWebServer stub(실제 계약: RT 회전, 소비된 RT 재제시 → 401 `reuse_detected` + family 폐기), vault는 H2(MySQL 모드), 세션 저장소는 in-memory `MapSessionRepository`(쿠키·필터는 운영과 같은 Spring Session).

| 성공 기준 | 결과 | 근거(테스트) |
|---|---|---|
| 10. `test bootJar` | **통과: 55건, 실패 0, 건너뜀 0**(8개 클래스) | `bff-server/build/test-results` |
| 1. 브라우저 응답에 토큰 없음 | 로그인 응답 body 필드는 `userId`·`csrfToken` 두 개뿐. body·모든 헤더에 AT/RT 원문 없음. `Cache-Control: no-store` | `BffLoginTest.loginReturnsOnlyUserIdAndCsrfToken_neverTokens` |
| 1. 세션·vault에 원문 없음 | 세션 attribute는 4개(userId·vaultRef·authenticatedAt·csrf)뿐, 원문 없음. vault 암호문 바이트에 평문 없음, BFF 키로만 원문 복원. `session_ref` ≠ 세션 ID | `BffLoginTest.sessionHoldsOnlyReference…` |
| 쿠키 속성 | `__Host-zetty-session`, Secure·HttpOnly·SameSite=Lax·Path=/, Domain 없음 | `BffLoginTest.sessionCookieIs…` |
| 2. 세션 고정 방어 | 심어 둔 세션 쿠키로 로그인 → 새 세션 ID 발급, 이전 세션·이전 vault 행 삭제, CSRF 토큰도 새 값 | `BffLoginTest.loginRotatesSessionId…` |
| 3. vault AEAD | 왕복 성공. 암호문·IV·tag 1비트 변조, AAD(다른 행) 불일치, 다른 키, 절단 → 모두 복호화 실패. 같은 평문도 매번 다른 암호문 | `AesGcmTokenCipherTest` 8건 |
| 키 fail fast | `BFF_VAULT_KEY`가 비면 애플리케이션 기동 실패. 오류 메시지에 키 값 없음 | `VaultKeyFailFastTest`, `AesGcmTokenCipherTest` |
| 4. allowlist | 목록 밖 경로(`/admin/users`, `/users/{id}`)·메서드(`DELETE /users/me`) 404, `..` 경로 4xx, API 호출 0건. 경로 판정 23케이스 | `ApiProxyTest`, `ProxyRouteAllowlistTest` |
| 4. 헤더 제거 | 브라우저의 Authorization·Cookie·X-Forwarded-For/Host/Proto·Forwarded·X-User-Id가 API에 도달하지 않음. API가 받은 Authorization은 vault AT. API의 Set-Cookie도 브라우저로 전달 안 됨 | `ApiProxyTest.getIsForwarded…` |
| 5. CSRF·Origin | CSRF 없음/위조 403, Origin 없음/유사 도메인 403(로그인 포함), 모두 API·Auth 호출 0건. 익명 unsafe 요청이 세션을 만들지 않음 | `ApiProxyTest`, `BffLoginTest`, `BffLogoutTest` |
| 6. single-flight | AT 만료 상태에서 실제 Tomcat에 동시 8건 → **8건 모두 동시에 401, Auth refresh 1회, 나머지 7건은 결과 공유, 8건 모두 200**. 5회 반복 모두 동일 | `TokenRefreshTest.concurrent…` |
| 7. refresh 거부 | 탈취자가 RT를 먼저 회전 → BFF refresh가 재사용 감지 401 → vault 행 삭제·세션 삭제·브라우저 401. 같은 쿠키 재요청 시 API·Auth 호출 없음 | `TokenRefreshTest.refreshRejected…` |
| 8. logout | Auth logout 1회(현재 RT), vault 행 삭제, 세션 삭제, 204 + `Zetty-Server-Revocation: confirmed`. Auth 500이면 로컬 폐기는 완료 + `unconfirmed` | `BffLogoutTest` 3건 |
| 9. 로그 위생 | web·http·security·session·jdbc·bff를 DEBUG로 켜고 로그인→조회→refresh→수정→logout 실행: 비밀번호·AT·RT(각 2세대)·세션 쿠키 값·세션 ID·CSRF 토큰 모두 로그에 없음 | `LogHygieneTest` |

### 측정하지 않은 것 (정직한 한계)

- 실제 Redis 세션 저장소(직렬화·TTL·namespace)와 실제 MySQL 8 DDL 적용. 테스트는 in-memory 세션·H2였다.
- Docker 이미지 빌드, Compose·Nginx·TLS를 거친 브라우저 흐름. `__Host-`/Secure 쿠키는 HTTPS 경유에서만 브라우저가 저장한다.
- proxy 요청마다 추가되는 DB 조회 1회 + 복호화의 지연·처리량 영향.
- 여러 BFF 인스턴스 간 refresh 경합(인스턴스 내부 single-flight만 구현).
- single-flight를 끈 대조군(동시 refresh → 재사용 감지로 강제 로그아웃)은 실행하지 않았다. stub이 회전을 강제하므로 두 번째 refresh는 401이 되어 위 테스트가 실패하는 구조다.

## 자소서 한 줄 (R 확정 후)

## R-2 — Compose 통합 결과 (nginx HTTPS 경유, 2026-09-27)

| 확인 | 결과 |
|---|---|
| `POST /bff/login` 응답 | `userId`, `csrfToken`만. AT·RT 문자열 0건 |
| 세션 쿠키 | `__Host-zetty-session; Path=/; Secure; HttpOnly; SameSite=Lax` |
| 쿠키로 `GET /bff/api/mypage` | 200 |
| 쿠키 없이 가짜 `Authorization: Bearer` | 401(브라우저 Authorization 제거) |
| `PUT /bff/api/users/me`: CSRF 없음 / CSRF+정상 Origin / 다른 Origin | 403 / 200 / 403 |
| allowlist 밖 경로 | 404 |
| `bff_token_vault` | 암호문 961바이트, 평문 JWT 0건 |
| `POST /bff/logout` → 같은 쿠키 | 204(`Zetty-Server-Revocation: confirmed`) → 401 |
| DB 권한 | `bff_app`은 `bff_token_vault` DML만 |

### 통합 중 시행착오
- nginx 경유 요청이 전부 본문 없는 400. BFF에 직접 보내면 200. 원인: `location /bff/` 안에 `proxy_set_header`를 추가하자 server 수준 헤더(Host 등) 상속이 끊겨 Host가 upstream 이름 `bff_up`으로 전달됐고, 밑줄이 든 Host를 Tomcat이 400으로 거부. 공통 헤더를 server 수준으로 옮겨 해결.
- 기존 `gen-secrets.sh`는 `.secrets/env`가 있으면 종료해 새 비밀값을 추가하려면 전체 초기화가 필요했다 → 빠진 키만 추가하도록 개선.
