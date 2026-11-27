# 서버 세션 인증·인가 설계

2026-11-27 목표 설계. 기존 참조 경로를 유지하기 위해 파일명은 그대로 두었지만, 본문은 서버 세션 구조로 교체했다. Java 코드에는 아직 적용하지 않았다.

## 구조와 책임

```text
bootstrap-app: Controller → Facade → domain의 Service 인터페이스
application:                          Service 구현
infrastructure:                       domain의 Repository 구현 → PostgreSQL
```

trust-chain-server의 AuthFacade와 SessionLoginAuthenticationProvider를 참고한다. 별도 Auth/BFF 배포 없이 bootstrap-app 하나를 실행한다. Python UBA는 독립 앱으로 둔다.

## 로그인

1. 요청 검증과 로그인 시도 제한을 수행한다.
2. AuthenticationManager가 Provider에 계정 상태·비밀번호 검증을 위임한다.
3. 검증된 userId·권한·authVersion으로 인증 주체를 만든다. 감사용 세션 식별자는 실제 세션 쿠키와 별개다.
4. SessionAuthenticationStrategy로 세션 고정을 방지하고 SecurityContextRepository로 인증 결과를 저장한다. Spring Session 저장소는 Redis다.
5. CSRF 토큰을 준비하고 로그인 결과를 감사 DB에 저장한다.
6. 세션 쿠키와 사용자 정보·CSRF 토큰을 응답한다. 브라우저용 Access/Refresh Token 발급은 목표 범위에 없다.

Redis와 PostgreSQL 사이의 원자적 커밋을 가정하지 않는다. 세션·감사 저장 실패 시 성공 응답을 내보내지 않고 생성된 인증 세션을 정리한다. 실제 세션 저장과 응답 커밋 시점은 통합 테스트로 확인한다.

## 후속 요청과 접근 회수

- 세션을 복원한 뒤 현재 계정 상태와 authVersion을 확인한다. 잠금·탈퇴·전체 로그아웃으로 상태가 바뀐 세션은 거부한다.
- Secure·HttpOnly·SameSite·__Host- 쿠키 조건과 상태 변경 요청의 CSRF·Origin 검증을 적용한다.
- 유휴 만료와 절대 만료를 구분한다. 로그아웃은 서버 세션 무효화와 쿠키 제거를 수행한다.
- 세션 저장소나 계정 상태 조회 장애를 인증 성공으로 처리하지 않는다.

## 주문 객체 인가

```text
인증된 principal.userId + 요청 orderId
  → OrderService
  → findByOrderIdAndUserId(orderId, userId)
     ├─ 있음: 허용 기록 후 주문 응답
     └─ 없음: 거부 기록 후 404
```

주문이 없는 경우와 타인 소유인 경우는 `OBJECT_NOT_FOUND_OR_NOT_OWNED`로 기록한다. 이 사실만으로 실제 소유자를 추정하지 않는다. 일반 경로 오류의 404와 인가 거부를 구분한다.

거부 감사는 업무 롤백과 분리해 저장한다. 감사 저장 실패 시 보호 응답 본문을 반환하지 않는 정책을 적용할 계획이다. 기록 성공이 브라우저 수신이나 외부 반출까지 증명하는 것은 아니다.

## 확인할 동작

로그인 성공·실패, 세션 ID 교체, CSRF 거부, 유휴·절대 만료, 로그아웃, 계정 상태 변경, 본인 주문 허용, 타인·미존재 주문 거부, 저장소 장애를 함께 확인한다. 아직 실행한 검증 결과는 아니다.

[감사 이벤트 설계](audit-event-design.md) · [C4 로그인·인가 도면](architecture/session-backend-c4/index.html)
