# ZETTY Backend

Java 21·Spring Boot·Gradle 기반의 **Auth / API / BFF** 세 앱이다.
현재 로컬 실행은 RS256·MySQL·Redis·KMS 에뮬레이터를 사용한다.

## 앱 역할

| 앱 | 포트(내부) | 주요 책임 |
|---|---:|---|
| auth-server | 8080 | 로그인, AT/RT, 발급대장, JWKS, 회수·대응 명령 |
| api-server | 8081 | JWT·원본 상태·객체 소유권 확인, 업무 API, 감사 이벤트 |
| bff-server | 8082 | 브라우저 세션·CSRF·vault·refresh·허용 proxy |

외부 진입은 형제 저장소 Compose의 nginx HTTPS 8443이다. management 포트는 내부 전용이다.
브라우저는 세션 쿠키를 사용하며 AT/RT는 BFF 서버에서 보관한다.

## 실행과 확인

[현재 Compose 절차](../zero-trust-architecture/compose/README.md)를 따른다.
기존 DB를 유지하는 환경에서는 새로운 secret으로 core를 재생성하지 않는다.

```sh
# ZETTY workspace 루트
python3 ops/workspace.py status

# 각 앱 디렉토리에서; JDK 21 필요
./gradlew test bootJar
```

Swagger 사용은 [가이드](docs/swagger-guide.md), 인증 연결은 [인증·토큰 구조](docs/auth-token-architecture.md)를 본다.
위 Gradle 명령은 실행 안내다. 이번 문서 정리에서 세 앱 전체 검사를 재실행한 기록은 아니다.

## 코드 구조

각 앱의 `src/main/java/com/zeti/` 아래 책임별 패키지를 읽는다.

- **Auth**: identity(로그인/계정), token(서명·RT·대장), response(대응 명령), securityevent(감사).
- **API**: security(JWT·원본 상태), mypage(집계/캐시/bulkhead), user/address/order/payment(업무), securityevent(감사).
- **BFF**: session(CSRF·Origin), vault(암호화 서버 토큰), proxy(refresh/허용 경로), upstream(Auth/API 호출).

JWT 서명은 `auth-server/.../token/infrastructure/kms/KmsJwtSigner.java`의 RS256이다.
API 원본 확인은 `api-server/.../security/application/AuthStateVerifier.java`의 단일 JOIN 쿼리다.
TTL 조회 캐시와 보안 인가 상태 확인을 혼동하지 않는다.

## 검증 시나리오

[attack-simulation v2](../attack-simulation/README.md)가 현행 검증 진입점이다.

- start: 잘못된 JWT/claim 거부.
- S1: 객체 ID와 소유자 범위를 함께 확인.
- S2: RT 회전·재사용 폐기·상태 회수.
- S3: 미발급 토큰·서명키 신뢰 철회.
- S4: 유효 토큰의 이상 사용을 행동으로 평가.

이 시나리오는 승인된 로컬 모의 계정 실험이다. 실제 사건의 내부 시스템·페이로드를 확인했다는 주장을 하지 않는다.

## 이벤트·계약

Auth/API가 [SecurityEvent v2](../log-pipeline/contracts/README.md)를 Outbox에 기록한다.
[전달 파이프라인](../log-pipeline/pipeline/README.md)이 Streams→ES를 담당한다.
schema revision 변경 시 `scripts/sync-contracts.sh`와 앱 ContractRevisionTest의 pin을 함께 확인한다.

## 현재 코드와 측정 기록

- [현행 문서 지도](docs/README.md)
- [인증·토큰 아키텍처](docs/auth-token-architecture.md): 현재 BFF/Auth/API 경계.
- [scripts 안내](scripts/README.md): 계약 동기화 명령.
- [STAR 기록](docs/star/README.md): 구현·실측·철회/한계 기록.

과거 위조 성공 기대값이나 raw-token 로그 경로는 현재 실행 기준이 아니다.
모의 fixture 민감 응답을 실제 개인정보 데이터로 표현하지 않는다.

## 보안·Git

토큰·쿠키·비밀번호·개인키를 로그/결과/학습 입력에 저장하지 않는다.
원본 사용자 ID 대신 검증된 가명 actor를 이벤트에 사용한다.
Git commit/push는 별도 요청 범위에서 수행한다.
