# 현재 인증·인가·토큰 아키텍처

> 2026-10-05 기준 로컬 Compose와 구현 코드. 구현·실행 기록은 [backend STAR](star/README.md), 이벤트 전달과 재처리는 [파이프라인](../../log-pipeline/pipeline/README.md)을 본다.

## 요청 경계

```text
브라우저 ──HTTPS·세션 쿠키──▶ nginx ──▶ BFF ──Bearer AT──▶ API
                                        │                   │
                                        └──로그인·갱신──▶ Auth │
                                                          └──MySQL 원본 상태 검사
Auth ──서명 요청──▶ 로컬 KMS     Auth/API ──SecurityEvent──▶ MySQL Outbox
```

- [Compose nginx](../../zero-trust-architecture/compose/nginx/nginx.conf)는 TLS를 종료하고 경로를 라우팅하며 신뢰 헤더와 request ID를 다시 설정한다. `access_log off`이고 Filebeat는 현재 실행 구성에 없다.
- [BFF](../bff-server/src/main/java/com/zeti/bff/login/BffAuthController.java)는 `/bff/login`에서 Auth에 로그인한 뒤 브라우저에 세션 쿠키와 CSRF 토큰만 준다. [Redis 공유 세션](../bff-server/src/main/java/com/zeti/bff/global/config/SessionConfig.java)과 [MySQL 암호화 토큰 vault](../bff-server/src/main/java/com/zeti/bff/vault/TokenVault.java)가 서버 쪽 상태를 보관한다. [프록시](../bff-server/src/main/java/com/zeti/bff/proxy/ApiProxyController.java)는 허용한 API 경로에만 AT를 붙인다.
- [Auth](../auth-server/src/main/java/com/zeti/auth/identity/presentation/AuthController.java)는 로그인·AT/RT 발급·회전·폐기를 담당한다. 현재 경로는 JSON `/auth/login`과 `/auth/refresh`다. Authorization Code/OIDC 흐름으로 설명하지 않는다.
- [API](../api-server/src/main/java/com/zeti/api/security/presentation/JwtAuthenticationFilter.java)는 Bearer AT를 검증한 뒤 서버가 만든 principal로 객체 소유권을 조회한다. 요청의 user ID나 미검증 JWT claim을 신원으로 쓰지 않는다.

## AT 발급과 검증

1. [JwtIssuer](../auth-server/src/main/java/com/zeti/auth/token/application/JwtIssuer.java)가 RS256 AT를 만들고 [KmsJwtSigner](../auth-server/src/main/java/com/zeti/auth/token/infrastructure/kms/KmsJwtSigner.java)가 로컬 KMS 에뮬레이터로 서명한다. 기본 AT 수명은 900초다. Auth만 서명 권한을 가진다.
2. [TokenLedgerService](../auth-server/src/main/java/com/zeti/auth/token/application/TokenLedgerService.java)는 compact JWT의 SHA-256 digest·사용자·jti·kid·상태를 MySQL 발급대장에 저장한다. 이는 서명만 맞는 미발급 토큰을 거부하기 위한 원본이다.
3. [JwtVerifier](../api-server/src/main/java/com/zeti/api/security/application/JwtVerifier.java)는 신뢰한 JWKS 공개키로 RS256 서명과 `kid`, `iss`, `aud`, `sub`, `jti`, 시간·수명을 검사한다.
4. [AuthStateVerifier](../api-server/src/main/java/com/zeti/api/security/application/AuthStateVerifier.java)는 매 보호 요청의 대장 digest·ACTIVE·sub와 사용자 `authVersion`을 MySQL JOIN으로 확인한다. 기본 운영 설정에서 positive cache는 꺼져 있다. DB 검증 실패는 허용으로 바꾸지 않는다.
5. 인증을 통과해도 객체 인가는 별도다. 예를 들어 [AddressRepository](../api-server/src/main/java/com/zeti/api/address/infrastructure/persistence/AddressRepository.java)의 객체 ID와 소유자 ID를 함께 조건으로 쓰는 조회가 다른 사용자의 주소를 막는다.

## RT·브라우저 세션

- [RefreshTokenService](../auth-server/src/main/java/com/zeti/auth/token/application/RefreshTokenService.java)는 RT 원문 대신 hash·family·generation·상태를 저장한다. 정상 갱신은 이전 RT를 소비하고 다음 세대를 발급한다. 소비된 RT 재사용은 family 폐기로 이어진다.
- BFF는 [TokenRefreshCoordinator](../bff-server/src/main/java/com/zeti/bff/proxy/TokenRefreshCoordinator.java)로 갱신을 조율한다. [TokenVault](../bff-server/src/main/java/com/zeti/bff/vault/TokenVault.java)는 AT/RT를 서버 측에 보관하고 브라우저 응답에는 넣지 않는다.
- BFF 세션 쿠키는 `__Host-zetty-session`이며 Secure·HttpOnly·SameSite=Lax다. [CSRF 토큰](../bff-server/src/main/java/com/zeti/bff/session/SessionBoundCsrfTokenRepository.java)과 Origin 검사, 제한된 프록시 경로를 함께 적용한다.
- 키의 정상 회전과 compromised `kid`의 긴급 신뢰 철회는 다른 절차다. 공개키 캐시만 남은 채 개인키를 비활성화하는 것으로는 기존 AT 검증이 즉시 멈추지 않는다.

## 감사·탐지로 이어지는 데이터

Auth/API는 검증이 끝난 신원과 판단 결과로 `security-event/2.0`을 생성한다. 업무 성공 이벤트는 업무와 같은 트랜잭션의 Outbox에, 실패·거부·조회 이벤트는 별도 저장 경계에 남긴다. 원문 JWT·RT·Cookie·password는 이벤트에 넣지 않는다. 전달은 **MySQL Outbox → Redis Streams → event-indexer → Elasticsearch**이고, ES에는 `event_id`를 문서 ID로 사용한다. 실패·재처리 규칙은 [파이프라인](../../log-pipeline/pipeline/README.md), payload는 [공통 계약](../../log-pipeline/contracts/README.md)을 본다.
