# ZETTY 선택 구조와 에이전트 작업 기준

## 목표와 실제 구현을 구분

2026-11-28 기준 선택한 목표는 **서버 세션 기반 단일 Java backend + Python uba-analyzer**다. 구형 Auth/API/BFF 코드·테스트·MySQL 실행 설정과 이전 Compose는 제거했다. 네 Gradle 모듈의 빌드 설정만 준비했으며 PostgreSQL·세션 인증·감사·전달·UBA·새 Compose 런타임은 구현 전이다. 기존 로그·비밀값·사용자 데이터·볼륨은 보존했다. README와 실제 파일에서 작업 당시 상태를 확인한다.

사용자가 backend와 uba-analyzer를 직접 이해하면서 함께 구현한다. 변경할 이유, 요청부터 저장·전달까지의 흐름, 파일과 검증 방법을 먼저 설명하고 승인한 단계만 구현한다. 이미 승인된 범위는 반복 확인하지 않는다. 문서·.agents·.codex만 정리하는 요청으로 코드를 바꾸거나 Docker·DB·공격 runner·외부 AI 호출을 실행하지 않는다. trust-chain-server 원본은 참고용 읽기 전용이다.

## 코드 구조

backend는 Java 21, Spring Boot와 Spring Security를 사용한다. 하나의 Gradle 빌드에서 네 모듈을 관리하고 **실행 앱은 bootstrap-app 하나**다. 모듈별 의존성 선언과 독립 실행 앱은 다른 개념이다.

| 모듈 | 책임 | 의존 방향 |
|---|---|---|
| domain | 엔티티, 도메인 DTO, 서비스·저장소 인터페이스 | 업무 규칙의 중심 |
| application | 서비스 구현과 업무 흐름 | domain |
| infrastructure | DB·외부 HTTP 등 인터페이스 구현 | domain |
| bootstrap-app | controller, facade, command/result, security, config, support, job 및 DI 조립 | 나머지 모듈 |

trust-chain-server의 실제 계층과 인증 구현을 읽고 필요한 부분을 가져온다. 회사 전용 설정이나 의존성을 그대로 복사하지 않는다. 별도 auth-server/API/BFF와 토큰 vault·RT 회전·JWKS·KMS는 이번 목표의 필수 구성으로 유지하지 않는다. 새 모듈이나 Gradle task가 실제로 생기기 전에 존재하는 것처럼 실행하지 않는다.

## 실행 및 저장 경계

- 브라우저 → 리버스 프록시 → backend. 서버 세션은 Redis에 저장한다.
- 일반 운영 로그는 회전되는 파일과 지속 볼륨에 남긴다. 모든 일반 로그를 AI로 보내지 않는다.
- PostgreSQL `zetty_backend`: 업무 데이터, `audit_events` 감사 사실, `audit_delivery` 전달 상태.
- backend의 Java 전달 job → 인증된 내부 HTTP → uba-analyzer.
- PostgreSQL `zetty_uba`: `received_events`, `analysis_jobs`, `reports` 등 UBA 수신·분석·보고서 상태.
- UBA는 애플리케이션이 계속 도는 동안 수신·집계·분석한다. 실험 종료 후 배치 실행을 전제로 하지 않는다.
- UBA 내부 집계·분석 작업이 Jev를 호출하고, UBA가 관측 사실과 검증된 응답을 조합해 보고서를 저장한다.

로컬에서는 PostgreSQL 인스턴스 하나에 논리 DB와 계정을 분리한다. 이것은 물리적 장애 격리가 아니다. backend와 UBA의 권한을 분리하고 UBA가 backend DB나 세션 Redis에 직접 접근하지 않게 한다. 초기 전달 worker는 하나로 제한하되 중복 스케줄·중단·재시작 복구와 조건부 완료를 처음부터 검증한다. 다중 worker가 필요해질 때 잠금/lease를 별도 설계한다.

PostgreSQL 선택 근거는 업무·감사·전달 상태의 트랜잭션과 제약조건, UBA의 가변 분석 자료에 대한 JSONB 조회를 같은 관계형 DB 기술로 학습·관리하기 위해서다. 고정 핵심 필드는 타입 있는 컬럼으로 둔다. MySQL이 로그를 못 저장한다거나 PostgreSQL만으로 로그 보존·위변조 방지가 해결된다고 설명하지 않는다. 용량·인덱스·보존·권한은 별도로 설계한다.

## 인증·인가

검증된 서버 principal을 actor로 삼는다. 요청 user ID, XFF, 미검증 주장으로 신원을 확정하지 않는다. 세션 쿠키의 Secure/HttpOnly/SameSite, CSRF·Origin, 로그인 시 세션 고정 방지, 유휴·절대 만료, 로그아웃과 계정 상태/authVersion의 회수를 검토한다. 프록시 전달 헤더는 신뢰 프록시 경계를 확인한다.

주문은 인증된 사용자의 소유권 범위 쿼리로 접근한다. DB·Redis 확인 실패를 허용으로 바꾸지 않는다. AI 결과는 실시간 인가를 완화하지 않는다. Redis 세션과 PostgreSQL 감사 기록은 하나의 원자적 트랜잭션이 아니므로 로그인 도중 부분 실패 처리를 설명해야 한다.

## 감사와 전달

감사 사실과 전달 대기 상태는 같은 PostgreSQL 트랜잭션으로 저장한다. 거부·실패 감사는 업무 롤백으로 함께 사라지지 않는 경계를 둔다. 원본 event_id는 재전송에도 유지한다. SQL 트랜잭션 안에서 HTTP나 Jev를 호출하지 않는다.

UBA는 이벤트와 분석 작업을 내구적으로 저장한 뒤 ACK한다. 같은 event_id·같은 내용은 멱등 수신하고 같은 ID·다른 내용은 충돌로 처리한다. backend는 ACK 유실·timeout·재시작에 재시도하며 전달 완료 상태를 관리한다. ACK는 분석 완료를 뜻하지 않는다. 인증 실패나 계약 위반을 무한 재시도하지 않도록 상태·한도·운영 확인 방법을 정한다.

로그인 성공과 주문 접근 결과 등 필요한 사건만 허용 필드로 전달한다. event_id, 발생 시각, 요청 연계값, 가명 actor/세션 연계값, 사건 종류·결과·사유, 허용된 관측 맥락은 **계약 제안**이다. 실제 schema와 fixture가 생기면 생산자/소비자를 함께 검증한다. 미관측 값은 0이나 정상으로 채우지 않는다.

JWT·세션 쿠키·비밀번호·키·원문 개인정보·업무 본문·URL query를 로그·fixture·AI 입력에 넣지 않는다. 가명 식별자도 접근과 보존을 제한한다. 세션 연계값은 사용 가능한 세션 자격증명이 아니어야 한다.

## 두 시나리오와 AI

1. 정상 로그인 이후의 계정 탈취 의심: 로그인 맥락·이전 이력·이후 행동을 연결하며 정상적인 환경 변화와 비교한다. 로그인 성공만으로 탈취를 증명하지 않는다.
2. 반복되는 주문 접근 거부: 인증된 actor의 시간 창, 거부 수·비율, 관측 가능한 대상 다양성 등을 정상 오입력과 비교한다.

`OBJECT_NOT_FOUND_OR_NOT_OWNED`는 객체 미존재와 타인 소유를 구별해 증명하지 않는다. HMAC 대상 식별자로 원래 주문 번호의 연속성이나 증가 방향을 추정하지 않는다. 실제 관측이 동일한 공격·정상 쌍은 근거 부족으로 남긴다.

집계는 이벤트 발생 시각, 지연 도착, 중복, 판단 시점 이전 이력과 누락 상태를 고려한다. 숫자 집계·명시적 인가는 코드가 수행한다. 학습 코드, 직원 업무 모델, 자동 차단, Slack 알림, 독립 log-pipeline·Streams·Elasticsearch는 이번 최소 범위에 넣지 않는다.

Jev의 실제 지원 입력·출력·모델 버전·호출 한도·비용·데이터 취급을 공식 문서와 승인된 검증으로 확인한다. 자유 서술 보고서 생성 기능을 가정하지 않는다. UBA가 README의 보고서 형식에 맞춰 사실과 지원되는 모델 출력을 조합한다. 다른 AI 제공자를 임의로 추가하지 않는다.

판정은 검토 필요 / 정상 흐름과 일치 / 근거 부족으로 설계한다. 필수 입력 누락·호출 실패·출력 계약 위반은 별도 미평가다. 시나리오 정답·공격 역할 라벨·미래 이력을 입력에서 제외한다. 규칙과 AI의 경쟁 실험을 필수 과제로 추가하지 않는다. mock 성공과 실제 탐지 성능은 구분한다.

## 참고 코드 적용과 공통 구현 기준

상세 학습 지도는 워크스페이스의 `backend/docs/reference-adoption.md` R01–R24, 인증·감사는 각 owner 문서를 읽는다. 원본 조사 기준은 b30dd3ca다. 원본 Boot 4/JDBC 세션과 현재 ZETTY Boot 3.5/목표 Redis 세션의 호환성을 확인한다. 네 모듈의 빌드 설정과 인증·감사 런타임 구현 여부를 구분한다.

- Controller는 HTTP, Facade는 여러 서비스 조합, Service는 업무 능력, infrastructure는 저장/외부 어댑터다. 조정용 계층을 중복 생성하지 않는다. 상태 변경 메서드·HTTP DTO·Bean Validation·안정된 오류 코드·UTC Clock·타입 설정을 참고한다. 필터와 MVC의 오류 경계도 맞춘다.
- 기능 권한은 객체 인가를 대체하지 않는다. 권한 변경/비밀번호 초기화 후 세션 회수와 현재 상태/authVersion을 검증한다. 로컬 권한 캐시의 TTL·무효화를 분산 즉시 회수로 설명하지 않는다. 로그인 IP/IP+계정 제한, 잠금 DoS와 실패 카운터의 독립 저장·동시 갱신을 검토하되 제한값은 측정 전 확정하지 않는다.
- UBA 수신은 사용자 세션과 별도 서비스 전용 Bearer 인증을 사용하는 안이다. JWT/토큰 발급 서버는 필수가 아니다. 모의용 자격증명의 격리된 로컬 HTTP 예외와 실제 배포 TLS를 구분한다. 비밀값의 생성·검증·교체/회수와 수신/운영 조회 권한을 분리한다.
- request_id는 요청 진입에서 한 번 정해 일반 로그·감사에 공유하고 로그 컨텍스트는 종료 시 정리한다. event_id·작업/시도 ID는 비동기 경계에 명시적으로 전달한다. 외부 request_id는 신원/고유성 보장이 아니다. occurred_at·recorded_at·received_at을 구분하고 당시 actor/target을 기록한다.
- 감사 필드 검사는 일반 로그를 보호하지 않는다. 양쪽 모두 허용 필드·크기 제한·민감정보/예외 마스킹을 검토한다. 원본 공통 403 응답이 모든 거부를 감사 DB에 저장한다고 가정하지 않는다.
- AFTER_COMMIT은 rollback 감사 보존이 아니고 fallbackExecution은 트랜잭션 없는 실행만 보완한다. @Async 메모리 큐는 내구성이 없으며 CallerRunsPolicy는 호출 스레드에서 실행될 수 있다. 트랜잭션 단위는 업무 원자성에 맞추고 커밋 후 추가 DB 쓰기 경계를 검증한다.
- DB 작업 상태·배치/큐 상한·dispatch 거부 반납·stuck 회수·재시도 상한을 정의한다. 짧은 선점/완료 트랜잭션 사이 HTTP/Jev를 실행한다. 이전 시도의 늦은 결과가 새 시도를 덮어쓰지 않게 한다. SKIP LOCKED만으로 exactly-once를 주장하지 않는다.
- 감사 조회는 기간·페이지 상한, 시간+ID 커서, 필요한 컬럼과 바인딩 SQL을 참고한다. LIKE 와일드카드 제한은 SQL 주입 방어와 별개다. FK/제약·감사 권한은 ZETTY 관계에 맞춘다. 원본 FK 전면 제거·파티션·삭제 방지 트리거 상태를 보안 정답으로 복사하지 않는다.
- 실제 HTTP의 쿠키/CSRF/회수/객체 인가, 필터 순서/중복, 실제 PG의 제약/rollback/권한, ACK 유실·재시작·포화·저장소 장애를 검증한다. 고정 Clock을 활용하고 unit/integration/실제 Jev 평가 결과를 구분한다. 존재하지 않는 task·서비스·테스트 실행을 주장하지 않는다.

원본 테넌트·동적 RBAC·workload JWT 전체·외부 DB 프레임워크·배포 파일·월별 감사 파티션은 최소 범위에 추가하지 않는다. 원본의 설정 수치를 실측 ZETTY 정책/SLA로 복사하지 않는다. 공통 기준은 관련 기능을 바꿀 때 적용하며 단순 문서 수정에 무관한 보안 점검/승인/테스트를 강제하지 않는다.

## 모니터링 경계

정본은 `backend/docs/monitoring-design.md`, UBA 세부 책임은 `uba-analyzer/README.md`, 실행 환경은 `zero-trust-architecture/docs/local-runtime-design.md`다. 아래는 형제 레포가 없는 clone에서도 유지할 최소 기준이다.

- 원본에 확인된 것은 Actuator health/info, 외부 DB health 분리, 작업 watchdog이다. Prometheus/Grafana/APM·알림 플랫폼이 구현됐다고 가정하거나 필수로 추가하지 않는다.
- backend liveness와 PostgreSQL/Redis를 포함한 readiness, UBA 수신 저장 가능 여부와 Jev 분석 진행 상태를 구분한다. probe 활성화만으로 DB/Redis가 readiness에 들어간다고 가정하지 않는다. UBA/Jev 장애는 backend liveness 실패가 아니며 backend 시작을 외부 분석 응답에 묶지 않는다.
- 전달 대기 수·최장 대기·재시도/영구 실패·최종 ACK, UBA 수신 중복/충돌·작업 대기/실패·분석 소요 시간·최종 보고서 성공을 관측한다. 단위·기간·재시작 시 초기화 여부를 명시한다. DB 조회 불가는 unknown, 대기 없음은 유휴이며 한 번도 성공하지 않은 시각은 null이다.
- 공개 health는 최소 상태, 상세 오류·토폴로지·작업 조회는 내부 운영 권한으로 제한한다. event_id/actor/IP/request_id/원문 URL을 메트릭 label로 넣지 않는다. 모니터링 오류를 공격 판정·정상 판정으로 바꾸지 않는다.
- 파일 회전/총량/보존·볼륨·비루트 권한·디스크 부족을 확인한다. UBA/Jev 장애 중 기록/수신과 복구 후 대기 해소를 검증한다. 수신 ACK는 보고서 완료가 아니고 Health UP은 탐지 성능 증거가 아니다.
- 최초 관측 수단은 내부 조회/CLI와 파일 로그를 목표로 한다. 실제 endpoint·도구·임계값은 구현 단계에서 정한다. 문서 작업에서 Compose/DB/서비스/유료 모델을 실행하지 않는다.

## 역할과 작업 흐름

기존 여덟 역할 이름을 유지한다. architect는 계획, reviewer는 독립 검토, java-spring-coder는 backend와 전달 job, python-pipeline-coder는 UBA 수신·집계·Jev·보고서, postgres-expert는 DB 읽기 전용 검토, tester는 테스트, scenario-designer는 두 시나리오, devops는 승인된 로컬 인프라를 맡는다. python-pipeline-coder라는 호환 이름은 삭제한 log-pipeline을 복원하라는 뜻이 아니다.

설계 → 계획 검토(재계획 최대 1회) → 사용자와 구현 단계 확정 → 구현 → 코드 검토(최대 3회) → 테스트 → 문서 순서를 유지한다. 가벼운 문서·지침 편집에는 전체 체인을 강제하지 않는다. 역할 모델은 상위 설정을 상속하고 같은 파일의 편집자는 한 명으로 한다. 사용 가능한 위임 도구/역할이 없으면 주 세션이 수행하되 독립 검토 여부를 사실대로 보고한다.

## 근거 문서와 배치

아래는 워크스페이스 상대 경로다. 개별 clone은 자기 README부터 읽고 없는 형제 레포가 필요한 확인만 미실행으로 남긴다.

- `backend/README.md`: 선택한 네 모듈과 실제 구현 상태.
- `backend/docs/auth-token-architecture.md`: 파일명은 유지했지만 서버 세션 설계 문서.
- `backend/docs/audit-event-design.md`: 감사·전달 계약 제안.
- `backend/docs/reference-adoption.md`: 원본 참고 항목 R01–R24, 적용/보완/제외와 학습 순서.
- `backend/docs/monitoring-design.md`: Health·전달/분석 진행 상태·공개 범위·검증 책임.
- `backend/docs/architecture/session-backend-c4/proposal.html`, `index.html`: 구조 설명과 C4.
- `uba-analyzer/README.md`: 수신·집계·Jev·보고서. 현재 로컬 디렉터리이며 Git 저장소 여부를 작업 시 확인한다.
- `attack-simulation/README.md`: 두 시나리오와 정상 대조군. runner 존재와 실행 결과는 별도 확인.
- `zero-trust-architecture/docs/local-runtime-design.md`, `compose/README.md`: 새 로컬 실행 설계와 구형 Compose 제거 후 구현 상태.
- `log-pipeline/README.md`: 독립 실행 역할 종료. 삭제된 코드를 되살리지 않는다.

루트 키트를 기준으로 기존 레포별 역할 배치를 유지하고 UBA에 필요한 역할/스킬을 둔다. 사본을 수정할 때 같은 이름의 설치본을 함께 맞춘다. trust-chain-server의 키트는 동기화 대상이 아니다. Git branch/commit/push는 별도 요청과 승인한 이름·메시지 범위에서만 수행한다.
