# 서버 세션 인증·인가 설계

목표 설계. 기존 참조 경로를 유지하기 위해 파일명은 그대로 두었지만 본문은 서버 세션 구조다. 네 Gradle 모듈의 빌드 설정을 준비했으며 구형 앱은 제거했다. 인증·인가 런타임은 아직 구현 전이다.

## 구조와 책임

```text
bootstrap-app: Controller → Facade → domain의 Service 인터페이스
application:                          Service 구현
infrastructure:                       domain의 Repository 구현 → PostgreSQL
```

trust-chain-server의 AuthFacade와 SessionLoginAuthenticationProvider를 참고한다. 별도 Auth/BFF 배포 없이 bootstrap-app 하나를 실행한다. Python UBA는 독립 앱으로 둔다.

Controller는 HTTP 입력·응답, Facade는 서비스 조합, Service는 업무 능력, Repository는 저장 계약을 담당한다. 엔티티의 상태 변경 메서드·분리된 DTO·공통 오류 코드·UTC Clock을 사용한다. 모든 Facade에 트랜잭션을 붙이지 않고 함께 커밋할 업무 단위로 정한다. 원본 Boot 4/JDBC 세션을 우리 Boot 3.5/Redis 설정에 그대로 복사하지 않는다. [적용 기준 R01–R13](reference-adoption.md)을 참고한다.

## 로그인

1. 요청 검증과 로그인 시도 제한을 수행한다.
2. AuthenticationManager가 Provider에 계정 상태·비밀번호 검증을 위임한다.
3. 검증된 userId·권한·authVersion으로 인증 주체를 만든다. 감사용 세션 식별자는 실제 세션 쿠키와 별개다.
4. SessionAuthenticationStrategy로 세션 고정을 방지하고 SecurityContextRepository로 인증 결과를 저장한다. Spring Session 저장소는 Redis다.
5. CSRF 토큰을 준비하고 로그인 결과를 감사 DB에 저장한다.
6. 세션 쿠키와 사용자 정보·CSRF 토큰을 응답한다. 브라우저용 Access/Refresh Token 발급은 목표 범위에 없다.

Redis와 PostgreSQL 사이의 원자적 커밋을 가정하지 않는다. 세션·감사 저장 실패 시 성공 응답을 내보내지 않고 생성된 인증 세션을 정리한다. 실제 세션 저장과 응답 커밋 시점은 통합 테스트로 확인한다.

로그인 시도 제한은 IP → IP+정규화 계정 → 잠금 검사 순서를 참고하되, IP 미상·공유 NAT·잠금 DoS를 함께 고려한다. 실패 카운터는 인증 실패로 롤백되지 않아야 하고 동시 갱신도 검증한다. 제한 수치와 저장 구현은 해당 단계에서 정한다. 프록시에서 신뢰할 수 있는 IP만 한 번 해석하고 User-Agent는 조작 가능한 맥락으로 취급한다.

로그인 실패에서 입력한 계정은 검증 actor가 아니다. 실패·잠금·rate-limit 거부의 감사 종류와 위치를 명시하고, 보안 필터에서 반환하는 401/403/429와 MVC 오류의 형식을 맞춘다. 원본의 공개 로그인 CSRF 예외를 그대로 복사하지 않고 로그인 요청 자체의 CSRF/Origin 정책도 확인한다.

## 후속 요청과 접근 회수

- 세션을 복원한 뒤 현재 계정 상태와 authVersion을 확인한다. 잠금·탈퇴·전체 로그아웃으로 상태가 바뀐 세션은 거부한다.
- Secure·HttpOnly·SameSite·__Host- 쿠키 조건과 상태 변경 요청의 CSRF·Origin 검증을 적용한다.
- 유휴 만료와 절대 만료를 구분한다. 로그아웃은 서버 세션 무효화와 쿠키 제거를 수행한다.
- 세션 저장소나 계정 상태 조회 장애를 인증 성공으로 처리하지 않는다.
- 권한 변경·비밀번호 초기화 뒤 기존 세션을 회수하고 현재 상태/authVersion으로 거부한다. AFTER_COMMIT 이벤트가 세션 삭제와 DB 변경을 원자적으로 보장하지 않으므로 회수 실패도 검증한다.
- 초기에는 복잡한 RBAC·권한 캐시를 추가하지 않는다. 도입할 경우 로컬 캐시 TTL·무효화·다중 인스턴스의 지연이 권한 회수에 미치는 영향을 명시한다.

## 주문 객체 인가

```text
인증된 principal.userId + 요청 orderId
  → OrderService
  → findByOrderIdAndUserId(orderId, userId)
     ├─ 있음: 허용 기록 후 주문 응답
     └─ 없음: 거부 기록 후 404
```

주문이 없는 경우와 타인 소유인 경우는 `OBJECT_NOT_FOUND_OR_NOT_OWNED`로 기록한다. 이 사실만으로 실제 소유자를 추정하지 않는다. 일반 경로 오류의 404와 인가 거부를 구분한다.

기능 권한 검사는 객체 인가를 대체하지 않는다. 우리 API에 필요한 기능 권한이 있으면 진입점에서 검사하고, 주문 소유권은 항상 조회 범위에 포함한다. UUID·추측하기 어려운 ID도 권한 검사 대신 사용할 수 없다.

거부 감사는 업무 롤백과 분리해 저장한다. 감사 저장 실패 시 보호 응답 본문을 반환하지 않는 정책을 적용할 계획이다. 기록 성공이 브라우저 수신이나 외부 반출까지 증명하는 것은 아니다.

## 확인할 동작

로그인 성공·실패, 세션 ID 교체, CSRF 거부, 유휴·절대 만료, 로그아웃, 계정 상태 변경, 본인 주문 허용, 타인·미존재 주문 거부, 저장소 장애를 함께 확인한다. 아직 실행한 검증 결과는 아니다.

실제 HTTP 쿠키·CSRF·세션 회수와 필터 순서/중복 등록을 확인한다. 고정 Clock으로 시간 경계를 검사하고 Mockito 성공을 실제 Redis/PG 통합 성공으로 보고하지 않는다. 보안 필터 오류와 주문 거부의 감사 기록은 각각 확인한다.

## backend → UBA 서비스 인증

브라우저 사용자 세션과 별개의 서비스 전용 Bearer 자격증명을 사용하는 안이다. Bearer는 JWT를 뜻하지 않으며 발급 서버·Refresh Token을 필수로 추가하지 않는다. 고엔트로피 시크릿은 실행 시 주입하고 원문을 로그·fixture·보고서에 넣지 않는다. 검증·회수/교체·비교 방식·수신 전용 권한을 계약으로 정한다. UBA 운영 조회 권한은 수신 권한과 분리한다.

실제 배포에서 Bearer 전송은 TLS로 보호한다. 격리된 로컬 HTTP는 모의용 자격증명을 사용하는 제한된 개발 예외이며 외부 배포 안전성을 증명하지 않는다. UBA는 인증 실패에 ACK하지 않고, backend는 해당 실패를 무한 재시도하지 않는다. 구현·실제 자격증명 발급은 아직 하지 않았다.

[감사 이벤트 설계](audit-event-design.md) · [C4 로그인·인가 도면](architecture/session-backend-c4/index.html)
