# Backend 문서 지도

## 현재 구현

- [backend README](../README.md): 세 앱 역할·빌드·검증 진입점.
- [현재 전체 구조](../../docs/CURRENT_ARCHITECTURE.md): 로컬 실행·컨테이너·데이터 흐름.
- [Swagger](swagger-guide.md): 로컬 API 문서 사용.
- [STAR](star/README.md): B-01 실행 기반, BFF, JWT 강화, Outbox, 대응 집행, 캐시와 부하 측정.
- [실시간 통신 판단](realtime-channels.md): 방식 선택 논의.

## 이전 설계·조사

- [인증·토큰 아키텍처](auth-token-architecture.md): 2026-09-06 제안/이후 결정 기록. 미구현 설명은 당시 기준이며 현재는 BFF·RT·발급대장 구현이 있다.
- [이전 AS-IS C4](C4-as-is.md): AWS/ES256/Filebeat 경로에 관한 이전 소스 조사.
- [이전 TO-BE C4](c4-to-be.md): 이전 목표/전환 계획.
- [이전 backend README](history/README-pre-local-20261003.md): 원문 보존.

위 역사 자료는 현재 기동 명령·컨테이너 구조의 기준이 아니다.
현재 구현 여부는 실제 소스와 가까운 STAR 결과, Compose에서 확인한다.
