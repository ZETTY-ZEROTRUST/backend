# Backend 문서 지도

## 현재 구현

- [backend README](../README.md): 세 앱 역할·빌드·검증 진입점.
- [Compose 실행 구조](../../zero-trust-architecture/compose/README.md): 로컬 실행·컨테이너·TLS.
- [이벤트 전달 구조](../../log-pipeline/pipeline/README.md): Outbox·Streams·ES와 재처리.
- [인증·토큰 아키텍처](auth-token-architecture.md): 현재 BFF·Auth·API의 발급·검증·회수 경계.
- [Swagger](swagger-guide.md): 로컬 API 문서 사용.
- [STAR](star/README.md): B-01 실행 기반, BFF, JWT 강화, Outbox, 대응 집행, 캐시와 부하 측정.

현재 구현 여부는 실제 소스와 가까운 STAR 결과, Compose에서 확인한다.
