# Backend 설계 문서

2026-11-27 기준 서버 세션 backend 하나와 PostgreSQL, 별도 UBA를 목표로 한다. 현재 문서는 구현 계획이며 실행·성능 검증 완료를 뜻하지 않는다.

| 문서 | 내용 |
|---|---|
| [backend 소개](../README.md) | 역할·모듈·구현 순서 |
| [인증·인가](auth-token-architecture.md) | 로그인·세션·현재 계정 상태·주문 소유권 |
| [감사·전달](audit-event-design.md) | PostgreSQL 기록·JSONB·HTTP 수신 확인·재시도 |
| [참고 코드 적용 기준](reference-adoption.md) | 원본에서 배울 24개 항목·보완점·제외 범위·구현 순서 |
| [모니터링](monitoring-design.md) | Health·필수 저장소·전달/분석 지연·운영 접근·인수 기준 |
| [통합 설계안](architecture/session-backend-c4/proposal.html) | 디렉토리·실패 처리·전환 순서·근거 코드 |
| [C1–C4 도면](architecture/session-backend-c4/index.html) | 시스템·컨테이너·컴포넌트·운영 관측·코드 구조 8장 |

이전 구조의 STAR 기록·부하 측정 설명·Swagger 실행 가이드는 제거했다. 새 구현에서 직접 확인한 명령과 결과만 이후 문서에 추가한다.

네 모듈의 Gradle 설정과 인증·감사 런타임 구현은 별개다. C4는 선택한 실행 경계를 표현하며, 세부 보안·작업 복구·모니터링 기준은 위 Markdown 정본을 함께 읽는다. 각 레포의 .agents/.codex도 이 기준을 사용한다.
