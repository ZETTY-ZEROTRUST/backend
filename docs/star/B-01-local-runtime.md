# B-01 AWS 없는 로컬 런타임: Java 21·RS256·로컬 KMS·JWKS

- 상태: 완료
- 연결: Jira A-01 · zero-trust-architecture `docs/star/Z-01`, `Z-02`
- 작성/갱신: 2026-09-26

## S — 문제 발생

- API는 기동 시 AWS KMS에서 공개키를 가져온다(`api-server/.../KmsPublicKeyProvider.java:31-38`). AWS 없이는 기동하지 않는다.
- Auth는 KMS ES256으로 서명하고, 리전·자격증명 공급자가 코드에 고정돼 있다(`auth-server/.../JwtConfig.java`).
- 두 앱의 기반이 다르다.
  - Java 17 toolchain이다(`api-server/build.gradle:12`, `auth-server/build.gradle.kts:12`).
  - Spring Boot 패치가 다르다(API 3.5.5 / Auth 3.5.14).
- 합의 계약은 RS256·JWKS 기반 검증인데, 현재는 kid가 KMS alias 문자열 그대로다(`jwt.kid: alias/...`). 키 경로가 토큰에 노출된다.
- `@SpringBootTest` contextLoads는 DB·KMS가 없으면 실패한다. 로컬 빌드 검증을 할 수 없다.

## T — 왜 / 목표

- AWS 없이 Compose로 기동되고, 이후 공격·부하 사이클을 반복할 수 있는 **기준 런타임**을 만든다.
- 이 단계는 **실행 기반 이전만** 한다. 검증 규칙(exp 선택적, iss/aud 미검증 등)은 현재 동작을 유지한다. 이후 v2:start 공격 사이클에서 취약점을 확인하고 고친다.
- 성공 기준
  - Java 21 컨테이너 빌드에서 `./gradlew test bootJar`가 통과한다.
  - Compose에서 로그인 → RS256 토큰 발급 → API 호출이 성공한다.
  - API는 KMS에 접근하지 않는다.

## A — 어떻게

### 계획

1. 두 앱 모두 Java 21 toolchain, Boot 3.5.14, Actuator + Prometheus registry. management port는 9090으로 분리한다.
2. **Auth**
   - `KmsClient`: endpoint override를 설정하면 에뮬레이터 전용 더미 자격증명을 쓴다. 설정이 없으면 기본 공급자를 쓴다.
   - `KmsJwtSigner`: `RSASSA_PKCS1_V1_5_SHA_256`. RSA 서명은 DER 변환이 필요 없다.
   - kid는 KMS alias가 아니라 **공개키의 RFC 7638 JWK thumbprint**로 한다.
   - `/.well-known/jwks.json`으로 공개키를 제공한다.
3. **API**: `KmsPublicKeyProvider`를 JWKS 조회로 교체한다(kid 단위 캐시, 미등록 kid면 1회 재조회). `JwtVerifier`의 기존 규칙은 알고리즘만 RS256으로 바꾸고 그대로 둔다.
4. Dockerfile(temurin 21 multi-stage, non-root)을 추가한다. 테스트는 H2(MySQL 모드) test profile로 context를 띄운다.

### 검토한 대안과 선택 이유

- **바로 Spring Resource Server로 전환:** 최종 목표이긴 하다. 하지만 기존 검증의 취약점을 공격으로 먼저 확인하는 사이클을 위해 A-02 단계로 분리했다.
- **API가 KMS `GetPublicKey`를 직접 호출:** 이러면 API에도 KMS 네트워크 접근이 필요하다. 서명 권한 경계가 약해져 제외했다.

### 시행착오

(진행 중 추가)

## R — 개선 결과

| 지표 | 결과 | 근거 |
|---|---|---|
| Java 21 컨테이너 `test bootJar` | 통과 (auth 2, api 7, 실패 0) | temurin:21에서 실행 |
| AWS 없이 Compose 기동 | 성공 | 전 서비스 healthy |
| 로그인→RS256→API | 성공 | `/auth/login` accessToken(RS256), `/users/me` 200 |
| 무토큰 / 변조 서명 | 각각 401 | smoke |
| API의 KMS 접근 | 없음 | JWKS만 조회 |

### 시행착오
- `JwksPublicKeyProvider`에 생성자가 둘이라 Spring이 기본 생성자를 찾다 실패 → 공개 생성자에 `@Autowired`.
- 응답 필드는 `token`이 아니라 `accessToken`(기존 DTO 유지).

## 자소서 한 줄 (R 확정 후)
