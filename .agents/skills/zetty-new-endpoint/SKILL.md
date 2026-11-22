---
name: zetty-new-endpoint
description: "ZETTY BFF/Auth/API endpoint를 기존 인증·인가·업무·감사 경계에 맞춰 추가한다."
---

# ZETTY 새 엔드포인트

## 전제
사용자 요청과 실제 라우팅에서 BFF cookie/CSRF, Auth 로그인·RT, API Bearer/객체 소유권 중 인증 표면을 먼저 식별한다. 불명확하고 구현에 영향을 줄 때만 질문한다.

## 작업 순서
1. 대상 앱의 기존 도메인 모델·상태·repository와 비슷한 endpoint를 읽는다.
2. 독립 업무 기능은 application service에 두고 기존 transaction 경계를 따른다. Controller에 업무 로직을 넣지 않는다. 필요한 orchestration만 기존 패턴에 맞게 추가한다.
3. presentation/controller·입출력 DTO·service·repository를 실제 com/zeti 패키지 규칙에 맞춰 구현한다. 다른 프로젝트의 네 모듈 구조를 만들지 않는다.
4. 요청 user ID가 아닌 검증 principal과 ownership query로 제한한다. BFF에서는 CSRF/Origin/허용 proxy를 확인한다.
5. 현재 MySQL schema owner와 최소 권한을 함께 검토한다. PostgreSQL 전환 구현이 별도로 요청된 경우에만 타입·쿼리·sequence 권한 등 변환 영향을 포함한다. 정상 앱 기동에서 DDL/seed를 재실행하지 않는다.
6. 업무 성공 Outbox의 원자성과 거부/rollback 감사 경계를 확인한다. 계약 변경 시 fixture/revision/consumer pin을 검토한다.
7. 정상·거부·소유권·상태 회수·rollback 등 변경된 동작을 실제 테스트 패턴으로 검증한다.
8. API 문서와 backend/docs/auth-token-architecture.md의 영향 부분을 갱신한다.

## 작업 후 확인
Java 21에서 변경된 앱의 ./gradlew test bootJar. 현재 MySQL 동작과 향후 PostgreSQL 검증을 구분한다. PostgreSQL 전환 구현 요청이 있을 때는 실제 PostgreSQL 통합 검증을 별도로 한다. 적용한 인증 경계, 수정 경로, 실행 결과와 미실행 항목을 보고한다.

## 현재 구현과 구성의 범위
현재 ZETTY DB 구현은 MySQL이며 PostgreSQL은 향후 전환 방향이다. 스킬·에이전트 구성만 요청된 작업에서는 실행 코드·SQL·Compose·의존성·데이터를 변경하지 않는다. 위 절차의 구현·실행 단계는 해당 작업이 실제 요청된 경우에만 적용한다. 경로는 ZETTY 워크스페이스 기준이며 개별 clone에서는 자기 레포의 README와 실제 코드를 먼저 읽고 없는 형제 레포의 교차 검증만 미실행으로 남긴다.
