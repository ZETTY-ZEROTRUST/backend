# B-04 BFF 1단계: 브라우저에서 AT·RT 제거 (세션 쿠키 + 암호화 vault + 제한 proxy)

- 상태: 계획
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

### 시행착오

(진행 중 추가)

## R — 개선 결과

미측정.

## 자소서 한 줄 (R 확정 후)
