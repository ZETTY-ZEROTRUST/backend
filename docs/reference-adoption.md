# trust-chain-server 참고 코드와 ZETTY 적용 기준

이 문서는 기존 네 모듈·서버 세션 구조를 구현할 때 사용할 학습 지도다. 원본 `b30dd3ca`의 실제 코드·설정을 조사한 결과이며, 원본 전체의 무결성이나 ZETTY 구현 완료를 보증하지 않는다. 현재 ZETTY는 네 모듈의 Gradle 설정, 단일 실행 진입점, 기본 실행·파일 로그 설정과 UTC Clock 빈을 준비했다. 인증·감사·전달 런타임은 아직 구현 전이다. 실제 파일 상태는 작업마다 확인한다.

trust-chain-server는 읽기 전용이다. 원본은 Spring Boot 4.0.0·JDBC 세션, 현재 ZETTY 빌드 기준은 Boot 3.5.14·목표 Redis 세션이므로 import, Security 설정, 세션 인덱스·회수 방식은 호환성을 확인한다. 원본을 복사하는 것과 같은 기능이 검증되는 것은 다르다.

## 책임 배치와 API

| ID | 참고 대상 | ZETTY 적용 기준 |
|---|---|---|
| R01 | ProjectController / ProjectFacade | Controller는 HTTP 입력·응답과 진입 권한, Facade는 여러 서비스의 조합을 맡는다. 조합이 필요 없는 호출을 위해 Facade·조정용 Service를 중복 생성하지 않는다. |
| R02 | User / UserServiceImpl | 상태 변경은 `changePassword` 같은 동작으로 표현하고 불변식을 검증한다. 엔티티에 무제한 setter를 열지 않는다. domain에 JPA를 사용하는 원본의 선택을 순수 Java 도메인으로 오해하지 않는다. |
| R03 | ProjectRepository / ProjectJpaRepository | domain은 저장소 계약, infrastructure는 JPA·JDBC·HTTP 구현을 소유한다. application은 infrastructure 구현체를 직접 참조하지 않는다. bootstrap-app이 조립한다. |
| R04 | ProjectCreateRequest / 응답 DTO | 입력 DTO의 Bean Validation·정규화와 업무 규칙을 구분하고, 엔티티를 그대로 HTTP로 노출하지 않는다. 비밀값이 포함된 DTO의 자동 문자열 출력도 확인한다. |
| R05 | GlobalExceptionHandler / ApiResponseAdvice | 안정적인 오류 코드와 HTTP 상태를 정하고 내부 예외·SQL 정보를 응답에 노출하지 않는다. 인증 필터의 오류도 계약을 맞춘다. 다운로드·빈 응답을 공통 JSON으로 강제하지 않는다. |
| R06 | ClockConfig / PropertiesConfig | UTC Clock을 주입해 시간 경계 테스트를 결정적으로 만들고 설정을 타입으로 묶어 검증한다. 환경값·시크릿은 실행 시 주입하며 원본 회사 설정은 복사하지 않는다. |

원본의 모든 Facade에 트랜잭션이 있는 것은 아니다. 함께 성공해야 하는 업무 변경·감사 기록을 먼저 정한 뒤 Facade 또는 Service의 경계를 정한다. 원본의 지원하지 않는 HTTP 메서드→404, 모든 무결성 오류→중복 리소스 409 처리는 우리 API의 정답으로 복사하지 않는다. 제약 종류와 실제 API 계약에 맞춘다.

근거: [ProjectFacade](../../trust-chain-server/bootstrap-app/src/main/java/com/coontec/trustchain/bootstrap/api/inventory/facade/ProjectFacade.java), [GlobalExceptionHandler](../../trust-chain-server/bootstrap-app/src/main/java/com/coontec/trustchain/bootstrap/support/error/GlobalExceptionHandler.java), [ClockConfig](../../trust-chain-server/bootstrap-app/src/main/java/com/coontec/trustchain/bootstrap/config/ClockConfig.java).

## 인증과 객체 인가

| ID | 참고 대상 | ZETTY 적용 기준 |
|---|---|---|
| R07 | AuthFacade / SessionLoginAuthenticationProvider | AuthenticationManager에 상태·비밀번호 검증을 위임하고 세션 ID 교체→SecurityContext 저장→CSRF 준비→감사 기록 흐름을 이해한다. Redis와 PostgreSQL 부분 실패도 검증한다. |
| R08 | SecurityConfig | 브라우저 세션과 서비스 자격증명을 구분하고 쿠키·CSRF·Origin 정책을 정한다. 원본의 공개 POST/로그인 CSRF 예외나 Swagger 전체 필터 우회를 그대로 복사하지 않는다. |
| R09 | ProjectController / InventoryGuardServiceImpl | 기능 사용 권한과 특정 객체 접근권한은 별개다. 주문은 검증 principal과 소유권 범위 쿼리로 제한한다. 역할·테넌트 모델 전체는 가져오지 않는다. |
| R10 | UserRoleChangedSessionInvalidator / UserPasswordResetSessionInvalidator | 권한 변경·비밀번호 초기화 후 기존 세션도 회수한다. 현재 상태/authVersion 확인을 두고 세션 삭제 실패·재시작·캐시 지연을 고려한다. |
| R11 | AuthFacade / AuthLockoutServiceImpl / AuthLockoutJpaRepository | IP 제한, IP+계정 제한, 잠금 검사의 목적을 구분한다. IP 미상·공유 NAT·다계정 시도·잠금 DoS를 검토한다. 실패 카운터는 독립 트랜잭션, 동시 갱신은 실제 PG로 검증한다. 수치·저장 기술은 승인 단계에서 결정한다. |
| R12 | ClientIpResolver / ClientIpResolveFilter | 신뢰 프록시만 전달 헤더를 제공하도록 하고 IP를 한 번 해석해 공유한다. 원본은 IPv4 중심이므로 IPv6·잘못된 체인·IP 미상의 동작을 명시한다. |
| R13 | CredentialServiceImpl / SecureTokenGenerator / JwtVerifier | 고엔트로피 서비스 비밀값의 생성·검증·회수와 비밀번호 해시를 구별한다. UBA 전용 Bearer 인증에 사용자 쿠키를 재사용하지 않는다. JWT/발급 서버 전체는 필수가 아니다. |

현재 권한 캐시는 필요성이 입증되기 전 도입하지 않는다. 원본 Caffeine의 로컬 무효화·TTL을 분산된 즉시 권한 회수로 설명하지 않는다. 원본의 권한 변경 AFTER_COMMIT 처리도 DB 변경과 세션 삭제를 원자적으로 만드는 장치는 아니다.

근거: [AuthFacade](../../trust-chain-server/bootstrap-app/src/main/java/com/coontec/trustchain/bootstrap/api/identity/facade/AuthFacade.java), [SecurityConfig](../../trust-chain-server/bootstrap-app/src/main/java/com/coontec/trustchain/bootstrap/config/SecurityConfig.java), [ClientIpResolver](../../trust-chain-server/bootstrap-app/src/main/java/com/coontec/trustchain/bootstrap/security/ClientIpResolver.java). ZETTY 정본은 [인증·인가 설계](auth-token-architecture.md)다.

## 감사와 비동기 처리

| ID | 참고 대상 | ZETTY 적용 기준 |
|---|---|---|
| R14 | logging 설정 / AuditServiceImpl | 일반 파일 로그와 보안 감사 DB를 목적별로 분리한다. 고정 핵심 컬럼·허용된 details와 크기/개수 제한을 사용한다. 키 이름 차단만으로 개인정보 제거를 보장하지 않는다. |
| R15 | AuditRequestContext / AuditServiceImpl | request_id는 요청 진입에서 한 번 정해 파일·감사에 공유한다. 외부 ID는 신원/고유성 증거가 아니다. occurred_at·recorded_at·received_at을 구분하고 당시 actor/target을 기록한다. |
| R16 | AuditHandler / AuditFacade / AuditAsyncConfig | AFTER_COMMIT+메모리 비동기 큐는 감사 내구성이나 전송 보장이 아니다. 원본 저장 실패는 오류 로그로 끝나므로 우리 감사+전달 대기를 PG에 함께 저장한다. |
| R17 | AuthLockoutServiceImpl / 트랜잭션 이벤트 | REQUIRES_NEW, AFTER_COMMIT, Async, Scheduled의 역할을 구분한다. 업무 롤백에도 거부 기록이 남아야 하며 트랜잭션을 무조건 REQUIRES_NEW로 만들지 않는다. |
| R18 | ReportWorker / ReportJpaRepository | DB 대기 작업·짧은 선점·실행 용량 제한을 학습한다. 초기 전달 worker는 하나다. HTTP/Jev 호출은 트랜잭션 밖에서 하고 완료 상태는 별도 짧은 트랜잭션으로 기록한다. |
| R19 | AibomMatchEventHandler / ReportProcessingServiceImpl | dispatch 거부·중단 작업 복구·재시도 상한을 정한다. 단일 worker도 재시작 복구가 필요하다. 늦게 끝난 이전 시도가 새 상태를 덮어쓰지 않도록 조건부 완료를 설계한다. |
| R20 | AuditEventRepositoryImpl / LikeSearchTerm / PageableFactory | 기간·페이지 크기 제한, 같은 시각의 ID tie-breaker, 필요한 컬럼 조회, 조건별 바인딩 SQL을 학습한다. LIKE 패턴 제한은 SQL 파라미터 바인딩과 별개다. |

원본 감사의 AFTER_COMMIT은 롤백 시 실행되지 않고 fallbackExecution은 트랜잭션이 없는 경우에만 적용된다. 감사 큐의 CallerRunsPolicy는 포화 시 호출 스레드로 돌아오므로 항상 별도 스레드라고 가정하지 않는다. 커밋 후 콜백에서 추가 DB 쓰기를 한다면 종료된 트랜잭션 자원에 참여하지 않도록 경계를 검증한다.

원본 공통 403 응답은 모든 객체 거부 감사를 자동 저장하지 않는다. 원본 감사 시각은 저장 시각이고 대상 보완도 비동기 실행 시 조회한다. 요청 ID는 헤더가 없으면 여러 호출에서 다르게 생성될 수 있다. ZETTY는 위 R15~R17을 따라 명시적으로 보완한다.

원본 보고서 생성 전체의 긴 트랜잭션을 외부 Jev 호출에 복사하지 않는다. SKIP LOCKED는 선점 충돌을 줄이는 도구이며 exactly-once나 만료된 실행의 결과 덮어쓰기 방지가 아니다. 다중 worker·lease를 도입할 때는 승인된 필요와 소유권/만료/완료 조건을 검증한다.

근거: [AuditHandler](../../trust-chain-server/bootstrap-app/src/main/java/com/coontec/trustchain/bootstrap/api/audit/handler/AuditHandler.java), [AuditFacade](../../trust-chain-server/bootstrap-app/src/main/java/com/coontec/trustchain/bootstrap/api/audit/facade/AuditFacade.java), [ReportWorker](../../trust-chain-server/bootstrap-app/src/main/java/com/coontec/trustchain/bootstrap/job/ReportWorker.java), [감사·전달 정본](audit-event-design.md).

## 운영과 검증

| ID | 참고 대상 | ZETTY 적용 기준 |
|---|---|---|
| R21 | DB DDL·마이그레이션 / application-dev.yml | 스키마 검증과 변경을 분리하고 실제 PG 제약·권한을 확인한다. 원본의 전면적 FK 제거, 월별 파티션·복합 PK는 기본값으로 복사하지 않는다. |
| R22 | Actuator 설정 / health 지원 클래스 | 필수 저장소·외부 의존성·프로세스 상태를 구분하고 상태 상세를 제한한다. Health UP만으로 감사 전달·분석 정상이라고 판단하지 않는다. |
| R23 | Worker / 운영 로그 / Dockerfile | 대기 수·최장 대기·실패·최종 성공을 관측한다. 파일 회전·보존·쓰기 권한·지속 볼륨·비루트 실행을 검증한다. 원본에는 Prometheus/Grafana/APM 배포 구성이 확인되지 않았다. |
| R24 | SecurityFilterChainWiringTest / HTTP E2E / AuditServiceImplTest / ReportWorkerTest | 필터 순서·중복 등록, 실제 쿠키/CSRF/회수, 감사 누락·동시성·포화·재시작을 검증한다. 단위 테스트와 실제 PG·HTTP 통합 테스트, 실제 Jev 평가는 따로 보고한다. |

원본 감사 삭제 방지 트리거는 제거되어 있다. DB 저장 자체를 위변조 방지로 설명하지 않는다. ZETTY의 감사 INSERT/read, 전달 상태 UPDATE, DDL/보존 삭제 권한은 분리하고 실효 권한으로 검사한다. 일반 로그의 민감정보 통제도 감사 JSON 검증과 별도로 한다.

원본 `test`는 integration 태그를 제외한다. 실제 task가 존재할 때 단위·통합 검증을 분리해 실행한다. 현재 문서 보완은 테스트·서비스·모델 실행 결과가 아니다. 원본의 기본 제한값·작업 시간·보존 기간을 실측한 ZETTY SLA로 복사하지 않는다.

모니터링의 관측값·공개 범위·장애 판정·인수 기준은 [모니터링 설계](monitoring-design.md)에 둔다. 원본 상세 근거는 [health 설정](../../trust-chain-server/bootstrap-app/src/main/resources/application.yml), [감사 변경 방지 트리거 제거](../../trust-chain-server/db/migration/2026-07-29_audit_events_drop_mutation_guard.sql), [Gradle 테스트 분리](../../trust-chain-server/build.gradle.kts)를 참고한다. 단독 clone에 원본이 없으면 읽지 않은 소스 검증은 미실행으로 남긴다.

## 함께 구현하는 순서

1. 현재 Gradle 모듈 설정을 확인하고 DB 환경·공통 오류/설정/Clock·사용자 모델의 파일과 검증 방법을 설명한다.
2. 세션 로그인과 Redis·PG 부분 실패, 쿠키·CSRF·계정 상태·회수를 구현한다.
3. 주문 기능 권한과 객체 소유권을 분리하고 허용·거부의 관측 지점을 정한다.
4. 요청 연계·파일 로그·감사와 전달 대기 저장을 구현한다. 감사 설계는 앞선 로그인/주문 단계부터 함께 정한다.
5. UBA 수신·ACK 계약, 전달 재시도·중단 복구, 상태/지연 관측을 연결한다.
6. 두 시나리오와 정상 대조군, Jev 입력·실패·근거 보고서를 검증한다.

매 단계는 동작·대상 파일·왜 필요한지·검증 방법을 사용자가 이해하고 승인한 범위로 진행한다. 기존 역할과 설계→검토→구현→검토→테스트→문서 흐름은 유지한다. 문서/지침 변경 승인은 실행 코드 변경이나 서비스 실행 승인이 아니다. 테넌트·복잡한 RBAC·승인 업무·배포 파일·다중 외부 DB·별도 토큰 서버·모니터링 플랫폼을 자동 추가하지 않는다.
