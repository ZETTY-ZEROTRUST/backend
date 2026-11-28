# ZETTY Backend

서버 세션 인증·객체 인가·감사 기록을 담당하는 **Spring Boot 애플리케이션 하나**로 통합한다. 업무·감사 DB는 PostgreSQL, 세션 저장소는 Redis를 사용하고, 별도 Python 앱인 uba-analyzer에 감사 이벤트를 전달하는 설계다.

> 인증·인가·감사·전달은 목표 설계다. 현재 네 Gradle 모듈, 단일 실행 진입점, 기본 실행 설정, UTC Clock 빈과 일반 파일 로그를 준비했다. 기존 auth-server·api-server·bff-server 코드와 테스트·실행 설정은 제거했다. 보존한 기존 로그는 새 앱의 실행 결과가 아니다.

## 목표 구조

```text
브라우저 → Nginx HTTPS → backend
                         ├─ Redis: 로그인 세션
                         ├─ PostgreSQL: 업무·감사·전달 상태
                         ├─ 파일: 일반 운영 로그
                         └─ HTTP 감사 전달 → uba-analyzer → Jev → 보고서
```

| Gradle 모듈 | 책임 |
|---|---|
| domain | 모델·DTO·서비스 및 저장소 인터페이스 |
| application | 업무 규칙과 유스케이스 구현 |
| infrastructure | PostgreSQL 저장소·UBA HTTP 어댑터 |
| bootstrap-app | Controller·Facade·보안·설정·스케줄·실행 진입점 |

4개 모듈을 하나의 실행 JAR와 컨테이너로 묶는다. trust-chain-server의 모듈 경계와 세션 로그인 흐름을 참고한다.

## 인증·인가·감사

- 로그인 자격을 검증한 뒤 세션 ID를 교체하고 인증 정보를 서버 세션에 저장한다. CSRF·Origin 검사, 만료·로그아웃, 현재 계정 상태·인증 버전 확인을 유지한다.
- 객체 인가는 인증된 사용자와 주문 소유자를 함께 조회한다. 클라이언트가 보낸 userId를 인증 주체로 사용하지 않는다.
- 일반 로그는 회전 파일에 남긴다. 감사 사실과 전달 대기는 PostgreSQL에 함께 저장하고 UBA 저장 확인 후 전달 상태를 갱신한다.
- UBA 장애는 재전송으로 처리한다. 감사 DB 장애를 정상 접근으로 숨기지 않는다. Jev 결과를 기다리며 사용자 요청을 처리하지 않는다.

## 문서

- [문서 지도](docs/README.md)
- [인증·인가 설계](docs/auth-token-architecture.md)
- [감사 기록·이벤트 전달 설계](docs/audit-event-design.md)
- [trust-chain-server 참고 코드와 적용 기준](docs/reference-adoption.md)
- [상태 확인·감사 전달·분석 모니터링](docs/monitoring-design.md)
- [통합 설계안](docs/architecture/session-backend-c4/proposal.html)
- [C1–C4 도면 8장](docs/architecture/session-backend-c4/index.html)
- [PlantUML·PNG·SVG·설계안 묶음](docs/architecture/zetty-session-c4.zip)

## 구현 순서

모듈 구성 → 별도 PostgreSQL 환경 → 세션 로그인 → 주문 인가 → 파일·DB 감사 기록 → UBA 전달 → Jev 분석·보고서 순서로 진행한다. 각 단계의 파일과 동작을 먼저 설명하고 승인받은 범위만 구현한다.

DB/모델 단계에서 공통 오류·DTO·Clock·설정을 함께 다루고 로그인/주문 단계부터 감사 관측 위치를 정한다. 전달/분석 단계에는 단일 worker 재시작 복구와 상태·지연 관측을 포함한다. 역할 이름과 설계→검토→구현→검토→테스트→문서 흐름은 유지한다. Prometheus/Grafana/APM이나 별도 토큰 서버를 자동 추가하지 않는다.

## 현재 실행 범위

Java 21을 선택한 터미널에서 backend 루트를 기준으로 실행한다.

```sh
./gradlew :bootstrap-app:bootRun --args='--server.address=127.0.0.1'
```

기본 포트는 8080이며 `ZETTY_BACKEND_PORT`로 변경할 수 있다. `/`는 업무 API가 없어 404, `/actuator/health`는 현재 구성의 상태를 반환한다. DB·Redis 연결 상태나 인증·감사 기능의 동작을 검증하는 단계는 아니다. 종료는 Control+C다.

일반 로그는 콘솔과 `logs/zetty-backend.log`에 기록하며 `ZETTY_LOG_FILE`로 경로를 변경할 수 있다. 일별·100MB 단위 회전, 최대 30일분 보관, 보관 파일 총량 3GB를 설정했다. 용량 상한에 먼저 도달하면 30일 이전 기록도 삭제될 수 있으며 현재 기록 중인 파일은 보관 총량에서 제외된다. DB 감사 기록과 원격 이관은 아직 구현 전이다.

`ClockConfig`는 UTC Clock 빈을 제공한다. 로그인·업무 코드에서 이 빈을 사용하는 단계는 이후 구현한다. 새 Compose와 업무 API도 구현 전이다. [스크립트 상태](scripts/README.md)를 확인한다. 커밋·push는 승인된 범위에서 진행한다.
