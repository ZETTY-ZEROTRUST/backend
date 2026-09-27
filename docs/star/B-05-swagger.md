# B-05 API 문서화: springdoc Swagger UI (로컬 전용)

- 상태: 계획
- 연결: backend api-server·auth-server · Compose nginx 라우팅
- 작성/갱신: 2026-09-27

## S — 문제

- API 명세가 코드와 문서(`auth-token-architecture.md`)에 흩어져 있다. 공격 시나리오·부하 스크립트·Codex(이벤트 소비자)가 엔드포인트·요청 본문·응답 형식을 코드를 읽어 추측해야 한다.
- 보호 API를 손으로 시험하려면 로그인 → 토큰 복사 → curl 헤더 작성을 매번 반복해야 한다.

## T — 목표

- api-server와 auth-server의 OpenAPI 명세를 자동 생성하고 Swagger UI로 조회·호출할 수 있게 한다.
- 보호 API는 Swagger의 Authorize에 Bearer JWT를 넣어 바로 호출할 수 있어야 한다.
- **운영 기본값은 비활성**. 로컬 Compose에서만 켠다.
- 성공 기준: 로컬에서 `https://127.0.0.1:8443/swagger-ui.html`(api), `/auth/swagger-ui.html`(auth)이 열리고 `/v3/api-docs`, `/auth/v3/api-docs`가 JSON 명세를 반환한다. 비활성 설정에서는 두 경로가 404다.

## A — 어떻게

### 계획
- 의존성: `springdoc-openapi-starter-webmvc-ui`(Spring Boot 3.5 호환 2.8.x).
- 활성화: `springdoc.api-docs.enabled`·`springdoc.swagger-ui.enabled` = `${SWAGGER_ENABLED:false}`.
- 경로 분리: nginx가 `/auth/`는 auth로, 나머지는 api로 보낸다. auth는 `springdoc.api-docs.path=/auth/v3/api-docs`, `swagger-ui.path=/auth/swagger-ui.html`로 옮겨 api와 겹치지 않게 한다.
- Security: 두 앱 SecurityConfig에서 문서 경로만 permitAll(비활성이면 핸들러가 없어 404).
- api에는 `bearerAuth`(HTTP bearer, JWT) SecurityScheme을 전역 적용한다.

### 대안 비교
| 방식 | 판단 |
|---|---|
| springdoc(코드에서 자동 생성) | 코드와 명세가 어긋나지 않음. **채택** |
| 수기 OpenAPI YAML | 코드 변경 때마다 동기화 필요, 누락 위험 |
| Spring REST Docs | 테스트 기반이라 정확하지만 작성 비용 큼. 계약 테스트 단계에서 재검토 |
| 운영에서도 공개 | 공격 표면(엔드포인트·파라미터 목록) 노출 → **로컬 전용** |

### 시행착오
(진행 중 추가)

## R — 결과

미측정.
