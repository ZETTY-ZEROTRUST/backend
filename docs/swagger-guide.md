# Swagger(API 문서) 사용 가이드

> 로컬 전용 도구다. 운영 배포에서는 끈다(`SWAGGER_ENABLED=false`).
> 도입 과정(문제 → 대안 → 결과)은 [B-05 STAR](./star/B-05-swagger.md)에 있다.

## 1. 왜 도입했나

### 해결하려던 문제
| 문제 | 영향 |
|---|---|
| API 명세가 코드와 설계 문서에 흩어져 있었다 | 공격 시나리오·부하 스크립트·Codex(이벤트 소비자)가 엔드포인트·요청 본문·응답 형식을 코드를 읽어 추측해야 했다 |
| 보호 API를 손으로 시험하려면 로그인 → 토큰 복사 → curl 헤더 작성을 매번 반복해야 했다 | 공격→방어 사이클에서 재현·확인 속도가 느렸다 |
| 명세를 손으로 따로 쓰면 코드와 어긋난다 | 문서를 믿고 만든 시나리오가 틀릴 수 있다 |

### 선택과 이유
| 선택지 | 판단 |
|---|---|
| **springdoc-openapi(코드에서 자동 생성) + Swagger UI** | 컨트롤러·DTO에서 명세를 만들어 코드와 어긋나지 않는다. 화면에서 바로 호출할 수 있다. **채택** |
| 수기 OpenAPI YAML | 코드가 바뀔 때마다 동기화해야 하고 누락 위험이 있다 |
| Spring REST Docs | 테스트 기반이라 가장 정확하지만 작성 비용이 크다. 계약 테스트 단계에서 다시 검토한다 |
| Postman 컬렉션 공유 | 명세가 아니라 요청 모음이다. 코드와 동기화되지 않는다 |

### 보안 판단
- **API 문서는 공격 표면을 그대로 보여준다**(엔드포인트·파라미터·응답 구조). 그래서 기본값은 **비활성**, 로컬 Compose에서만 켠다.
- springdoc 자체 기본값은 "활성"이다. 설정이 빠지면 문서가 노출되므로 운영·테스트 설정 모두에 비활성을 명시했고, 켜짐/꺼짐을 테스트로 고정했다.
- **토큰을 브라우저 저장소에 남기지 않는다.** `persist-authorization: false`로 명시했다. Authorize에 넣은 토큰은 페이지 메모리에만 있고 새로고침하면 사라진다(이 프로젝트의 "브라우저에 AT/RT를 저장하지 않는다" 원칙과 같은 기준).
- lab 전용 토큰 위조 엔드포인트(`/lab/forge`)는 secure 프로필에서 로드되지 않으므로 명세에도 나타나지 않는다.

## 2. 준비

1. 로컬 스택 기동(`zero-trust-architecture/compose/README.md` 참고).
   ```sh
   cd zero-trust-architecture/compose
   docker compose --env-file .secrets/env up -d --wait
   ```
2. 브라우저에서 아래 주소를 연다. 로컬 자체 서명 인증서라 처음에 경고가 뜬다. `compose/tls/ca.crt`를 OS/브라우저 신뢰 저장소에 추가하거나, 로컬에서만 경고를 수락한다.

| 문서 | 주소 | 내용 |
|---|---|---|
| Auth | https://127.0.0.1:8443/auth/swagger-ui.html | 회원가입, 로그인, 토큰 갱신, 로그아웃, 공개키(JWKS) |
| API | https://127.0.0.1:8443/swagger-ui.html | 내 정보, 마이페이지, 주문, 주소, 결제 |
| **BFF(브라우저 진입점)** | https://127.0.0.1:8443/bff/swagger-ui.html | 세션 쿠키 로그인, 허용 경로만 API로 전달, 로그아웃 |

명세 JSON: `/auth/v3/api-docs`, `/v3/api-docs`

## 3. 빠른 시작 — 로그인해서 보호 API 호출하기

1. **Auth 문서** → `POST /auth/login` → **Try it out** → 본문 입력 → **Execute**
   ```json
   { "email": "user001@zetty.test", "password": "loadtest-pw-1234" }
   ```
   시드 계정은 `user001`~`user500@zetty.test`, 공용 비밀번호 `loadtest-pw-1234`(로컬 모의 계정).
2. 응답의 `accessToken` 값을 복사한다. `refreshToken`은 갱신·로그아웃 시험에 쓴다.
3. **API 문서** 우측 상단 **Authorize** → `bearerAuth` 칸에 accessToken만 붙여 넣는다(`Bearer ` 접두사는 Swagger가 붙인다) → Authorize.
4. `GET /mypage`, `GET /users/me`, `GET /orders` 등을 **Try it out → Execute**.
5. Access Token 수명은 **900초(15분)**다. 이후 401이 나오면 다시 로그인하거나 `POST /auth/refresh`로 갱신해 Authorize를 새 토큰으로 바꾼다.

## 3-1. BFF 문서로 쓰기 (브라우저가 실제로 쓰는 방식)

Auth·API 문서는 토큰을 직접 다루는 **개발·시험용**이다. 실제 브라우저 흐름은 BFF를 거치며 **토큰을 보지 않는다.**

1. **BFF 문서** → `POST /bff/login` → Try it out → `{ "email": "user001@zetty.test", "password": "loadtest-pw-1234" }` → Execute
   - 응답에는 `userId`, `csrfToken`만 있다. **AT·RT는 응답에 없다**(BFF 서버의 암호화 vault에만 있다).
   - 세션 쿠키 `__Host-zetty-session`(HttpOnly·Secure)은 브라우저가 저장하고 이후 요청에 자동으로 붙인다. Swagger 화면이나 JS로는 값을 읽을 수 없다.
2. **Authorize** → `csrfToken` 칸에 응답의 `csrfToken`을 넣는다. PUT·POST 같은 상태 변경 요청에 `X-CSRF-Token` 헤더로 붙는다.
3. `GET /bff/api/**`의 경로에 `mypage`, `users/me`, `orders` 등을 넣어 실행 → BFF가 서버 측에서 AT를 붙여 API로 전달한다.
4. 허용 목록 밖 경로는 404, CSRF 토큰 없는 상태 변경은 403, 다른 Origin은 403이다.
5. `POST /bff/logout` → 세션·vault 폐기 + 서버 측 토큰 회수. 응답 헤더 `Zetty-Server-Revocation: confirmed|unconfirmed`로 회수 확인 여부를 알려준다.
6. 새로고침 후 CSRF 토큰이 필요하면 `GET /bff/session`으로 다시 받는다.

## 4. 엔드포인트 한눈에

### Auth (`/auth/swagger-ui.html`)
| 메서드·경로 | 용도 | 비고 |
|---|---|---|
| `POST /auth/signup` | 회원가입 | |
| `POST /auth/login` | AT(RS256) + RT 발급 | 발급대장에 토큰 digest 기록 |
| `POST /auth/refresh` | RT 회전(새 AT·RT) | **한 번 쓴 RT를 다시 쓰면 해당 계열 전체가 폐기된다** |
| `POST /auth/logout` | 전체 로그아웃 | authVersion 증가, RT·발급대장 폐기 → 기존 AT도 즉시 401 |
| `GET /.well-known/jwks.json` | 서명 검증용 공개키 | 개인키 정보 없음 |

### API (`/swagger-ui.html`, 모두 Bearer 필요)
| 메서드·경로 | 용도 |
|---|---|
| `GET /users/me`, `PUT /users/me` | 내 정보 조회·수정(이름·전화) |
| `GET /mypage` | 내 정보 + 기본 배송지 + 최근 주문 5건 + 결제수단 |
| `GET /orders?page=0&size=20` | 내 주문 목록(페이지당 최대 100건) |
| `GET /orders/{orderId}/detail` | 주문 상세(본인 주문만) |
| `GET /addresses`, `PUT /addresses/{addressId}` | 주소 조회·수정(본인 주소만) |
| `GET /payments/balance`, `GET /payments/history` | 결제수단 잔액·내역 |

## 5. 응답 코드 읽는 법 (로컬에서 확인한 실제 동작)

| 상황 | 응답 | 이유 |
|---|---|---|
| 토큰 없이 보호 API 호출 | 401 | 인증 필요 |
| 본인 주문 상세(user001 → 주문 1) | 200 | |
| **다른 사람 주문 상세**(user001 → user002의 주문) | **404** | 객체 소유권 검사. 존재 여부를 숨기기 위해 403이 아니라 404 |
| 만료·로그아웃·발급되지 않은 토큰 | 401 | 매 요청 authVersion·발급대장 확인 |
| 로그인 비밀번호 틀림 | 400 | 현재 구현 동작. 이메일/비밀번호 중 무엇이 틀렸는지는 알려주지 않는다(로그인 실패를 401로 바꿀지는 후속 검토) |
| 같은 RT로 refresh 두 번 | 1회째 200, 2회째 401 | 재사용 감지 → 계열 폐기 |

## 6. Swagger로 공격 시나리오 직접 확인하기

자동 러너(`attack-simulation/scenarios/v2/`)와 같은 내용을 화면에서 손으로 확인할 수 있다.

- **S1 BOLA**: user001로 Authorize → `GET /orders/{orderId}/detail`에 다른 사용자의 주문 번호 입력 → 404.
- **S2 RT 재사용**: `POST /auth/refresh`를 같은 refreshToken으로 두 번 실행 → 두 번째 401. 첫 번째 응답으로 받은 새 RT도 이제 401(계열 폐기).
- **S2 로그아웃 반영**: API에서 보호 API 200 확인 → Auth에서 `POST /auth/logout` → 같은 AT로 다시 호출 → 401.

주의: 이런 시험은 시드 모의 계정에만 한다. 로그아웃·재사용 시험을 하면 해당 계정의 토큰이 폐기되므로 다시 로그인한다.

## 7. 켜고 끄기

| 위치 | 설정 |
|---|---|
| 로컬 Compose | `SWAGGER_ENABLED` 기본 `true`(`compose.yaml`의 api·auth 환경변수) |
| 앱 기본값 | `${SWAGGER_ENABLED:false}` → 환경변수가 없으면 꺼짐 |
| 운영 배포 | 반드시 `SWAGGER_ENABLED=false`. 꺼진 상태에서는 문서 경로가 404 |

## 8. 새 API를 문서에 반영하려면

- 별도 작업 없이 컨트롤러·DTO에서 자동으로 명세가 생성된다. 보호 API에는 전역 `bearerAuth`가 적용된다.
- 설명을 더하고 싶으면 `@Tag`(그룹), `@Operation(summary = "...")`, `@Parameter`를 붙인다.
- 인증이 필요 없는 공개 API라면 `@SecurityRequirements()`로 전역 bearer 요구를 해제한다.
- auth-server에 추가하는 경로는 nginx가 `/auth/` 아래만 auth로 보내므로 `/auth/...`로 둔다(`/.well-known/jwks.json`은 nginx에 별도 규칙이 있다).

## 9. 문제 해결

| 증상 | 원인·조치 |
|---|---|
| 브라우저 인증서 경고 | 로컬 자체 서명 인증서. `compose/tls/ca.crt` 신뢰 또는 로컬에서만 수락 |
| Execute 후 `Failed to fetch` 또는 502 | 스택이 내려가 있거나 api·auth가 재기동 중. `docker compose ps`로 healthy 확인 |
| 한동안 잘 되다가 401 | AT 900초 만료, 또는 다른 곳에서 같은 계정을 로그아웃·RT 재사용 시험을 해 폐기됨 → 재로그인 |
| 문서 경로가 404 | `SWAGGER_ENABLED`가 false(운영 기본값) |
| 새로고침 후 Authorize가 풀림 | 의도된 동작(`persist-authorization: false`). 토큰을 브라우저 저장소에 남기지 않는다 |
