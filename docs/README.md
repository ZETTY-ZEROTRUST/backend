# Backend 설계 문서

2026-11-27 기준 서버 세션 backend 하나와 PostgreSQL, 별도 UBA를 목표로 한다. 현재 문서는 구현 계획이며 실행·성능 검증 완료를 뜻하지 않는다.

| 문서 | 내용 |
|---|---|
| [backend 소개](../README.md) | 역할·모듈·구현 순서 |
| [인증·인가](auth-token-architecture.md) | 로그인·세션·현재 계정 상태·주문 소유권 |
| [감사·전달](audit-event-design.md) | PostgreSQL 기록·JSONB·HTTP 수신 확인·재시도 |
| [통합 설계안](architecture/session-backend-c4/proposal.html) | 디렉토리·실패 처리·전환 순서·근거 코드 |
| [C1–C4 도면](architecture/session-backend-c4/index.html) | 시스템·컨테이너·컴포넌트·코드 구조 |

이전 구조의 STAR 기록·부하 측정 설명·Swagger 실행 가이드는 제거했다. 새 구현에서 직접 확인한 명령과 결과만 이후 문서에 추가한다.
