# ZETTY Backend

서버 세션 인증·객체 인가·감사 기록을 담당하는 **Spring Boot 애플리케이션 하나**로 통합한다. 업무·감사 DB는 PostgreSQL, 세션 저장소는 Redis를 사용하고, 별도 Python 앱인 uba-analyzer에 감사 이벤트를 전달하는 설계다.

> 2026-11-27 설계 기준. 문서를 새 구조로 교체했으며 애플리케이션 전환은 아직 구현 전이다. 소스에는 기존 auth-server, api-server, bff-server가 남아 있다. 이 문서의 목표 구조를 현재 실행 상태로 해석하지 않는다.

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
- [통합 설계안](docs/architecture/session-backend-c4/proposal.html)
- [C1–C4 도면 7장](docs/architecture/session-backend-c4/index.html)
- [PlantUML·PNG·SVG·설계안 묶음](docs/architecture/zetty-session-c4.zip)

## 구현 순서

모듈 구성 → 별도 PostgreSQL 환경 → 세션 로그인 → 주문 인가 → 파일·DB 감사 기록 → UBA 전달 → Jev 분석·보고서 순서로 진행한다. 각 단계의 파일과 동작을 먼저 설명하고 승인받은 범위만 구현한다.

새 Gradle 진입점·Compose 실행 명령·API 경로는 구현 후 실제 검증 결과에 맞춰 작성한다. 기존 스크립트는 [현재 상태](scripts/README.md)를 확인한다. 커밋·push는 별도 승인 범위다.
