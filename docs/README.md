# ZETTY System Architecture

이 디렉터리의 C4 문서는 `backend`만이 아니라 ZETTY 워크스페이스의 여섯 독립 저장소를 하나의 Software System으로 설명한다.

- [인증·인가와 토큰 저장 아키텍처](./auth-token-architecture.md): 2026-09-06 웹 기준 제안. BFF의 access/refresh token 보관, 브라우저 세션 쿠키, 갱신·폐기·CSRF와 UBA 입력 전환. 미구현이며 인증 부분은 기존 TO-BE보다 이 문서를 우선한다.
- [AS-IS C4 Architecture](./C4-as-is.md): 현재 코드·설정·mapping·Terraform에서 확인되는 전체 runtime·data·deployment 구조와 정합성 차이
- [TO-BE C4 Architecture](./c4-to-be.md): 기존 전체 목표 구조와 전환 순서. 새 인증 설계 및 ML 전환 요청의 우선 범위를 문서 상단에 표시한다.

## 문서 범위

| 저장소 | C4에서 다루는 책임 |
|---|---|
| `.github/` | 전체 목적과 저장소 간 계약의 문서 소유권 |
| `zero-trust-architecture/` | VPC·SG·ALB/WAF·EC2·RDS·KMS Deployment 정의 |
| `backend/` | Auth Server의 JWT 발급과 API Server의 JWT 검증·업무 API |
| `log-pipeline/` | Nginx PEP, Filebeat, Elasticsearch ingest·mapping |
| `uba-analyzer/` | 집계, 7-factor, LLM ReAct, grounding, Slack·Kibana·KPI |
| `attack-simulation/` | S2/S4/S5/S5b/S6/S8 검증 트래픽과 evidence |

Git 저장소는 C4 Container가 아니다. 저장소는 코드 소유권을 나타내고, Container는 독립 실행·데이터 단위를 나타낸다. 그래서 하나의 저장소가 여러 Container를 소유할 수 있고, Elasticsearch처럼 여러 저장소가 배포·schema·writer 책임을 나눠 가질 수도 있다.

## 문서 기준

| 구분 | 의미 |
|---|---|
| AS-IS | 2026-08-29 현재 추적되는 코드와 설정을 기준으로 한 사실. 문서와 구현이 다르면 차이를 명시한다 |
| TO-BE | 아직 완전히 구현되지 않은 목표 상태. 저장소별 완료 조건과 호환 가능한 배포 순서를 포함한다 |
| Software System boundary | 승인된 공격 검증부터 Edge/PEP, Backend, Telemetry, UBA, LLM, SOC 산출까지의 ZETTY 전체 |
| External system | AWS KMS, Anthropic API, MITRE/NVD, Slack처럼 ZETTY가 소유하지 않는 시스템 |
| Deployment View | Terraform이 정의한 AWS topology. 별도 live 검증 없이는 현재 배포 상태를 뜻하지 않는다 |

## 우선하는 근거

1. 전체 목적·계약: `.github/profile/README.md`
2. 대상 저장소의 `README.md`와 가까운 운영 문서
3. 실제 code, config, mapping, Terraform, CI workflow
4. 공격 기대값: `attack-simulation/SCENARIOS.md`
5. 측정 결과: `uba-analyzer/docs/kpi/`

README의 목표나 예정 상태를 현재 동작으로 추정하지 않는다. 실제 코드와 문서가 다르면 AS-IS의 정합성 차이로 기록하고, TO-BE의 완료 조건으로 연결한다.

## 다이어그램 표기

- 실선 화살표: runtime 호출 또는 데이터 전달
- 점선 화살표: 구현·schema·간접 계약
- `<<software_system>>`, `<<container>>`, `<<component>>`: C4 추상화 수준
- `[기술]`: 현재 또는 목표 실행 기술
- 저장소명: 요소를 배포 단위로 오해하지 않도록 소유권 설명에만 사용

클래스·메서드·모든 테이블 컬럼을 펼치는 Code diagram은 만들지 않는다. 상세 구현은 각 저장소의 가까운 문서와 소스가 소유한다.
