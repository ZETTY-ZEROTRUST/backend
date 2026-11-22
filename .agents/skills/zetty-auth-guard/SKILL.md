---
name: zetty-auth-guard
description: "ZETTY Auth·BFF·API의 로그인, 세션, JWT, 객체 인가, 권한 변경과 접근 회수를 구현하거나 검토할 때 사용한다. 단순 DTO·주석 편집에는 전체 인증 검토를 강제하지 않는다."
---

# 인증·인가·회수 경계

현재 세션의 최신 사용자 지시와 backend/README.md, backend/docs/auth-token-architecture.md, 변경 대상 앱의 실제 filter/service/test를 읽는다. 현재 세션의 사용자 지시와 실제 owner 문서를 따른다. 경로는 ZETTY 워크스페이스 기준이다. 개별 레포를 열었으면 부모에서 형제 레포를 찾고, 없으면 해당 교차 검증을 미실행으로 보고한다.

## 현재 구성과 변경 범위

현재 Java 21·Auth/API/BFF·RS256·AT 900초·MySQL 원본·Redis 실행 기반을 출발점으로 삼는다. PostgreSQL은 사용자가 선택한 향후 전환 방향이며 현재 코드·DB가 전환 완료됐다는 뜻이 아니다. 에이전트 구성 작업만으로 실행 코드나 데이터베이스를 바꾸지 않는다. 현재 Auth는 JSON /auth/login·/auth/refresh이며 Authorization Code/OIDC를 구현했다고 쓰지 않는다. 새 업무의 인증 요구와 객체 권한을 먼저 정하고 재사용/수정/제거할 구성과 근거를 제시한다. 별도 인가 서버를 현재 구현으로 가정하지 않는다.

## 바뀌면 안 되는 신뢰 경계

- 브라우저는 BFF 세션 쿠키, 서버는 AT/RT를 보관한다. BFF cookie/CSRF/Origin과 API Bearer 검증은 다른 경계다.
- principal은 검증한 신원에서 얻는다. 요청 user ID·decoded claim·외부 신원/XFF header로 대체하지 않는다.
- 서명·issuer·audience·시간·kid 검증과 발급 digest·상태/authVersion 확인을 구분한다. Auth만 서명 권한을 갖는다. security positive cache 추가는 별도 일관성 검증이 필요하다.
- RT 회전·재사용 감지·BFF 갱신 경합·응답 유실을 함께 확인한다. DB/Redis 상태 확인 실패를 허용으로 바꾸지 않는다.
- 현재 본인 객체 조회는 소유자 범위 query로 제한한다. 직원/팀/담당 고객 모델은 명시적인 권한 설계와 query 변경이 필요하며 ROLE 하나로 전체 고객 접근을 열지 않는다.
- 정상 키 회전과 compromised kid 긴급 철회, 세션 회수와 전체 계정 회수의 실제 구현 효과를 각각 검사한다.
- AI는 명시적 인가를 완화하거나 Auth DB를 직접 수정하지 않는다. 정책 요청의 대상·만료·환경·멱등성과 집행 receipt를 확인한다. 의도 기록만 하는 조치를 집행 성공으로 보고하지 않는다.
- JWT·RT·Cookie·password·개인키를 로그/fixture/prompt/commit에 남기지 않는다. secure와 취약 lab은 issuer·키·쿠키·network·volume을 분리한다.

## 검증

권한 표의 정상/거부 경로, 권한 변경 뒤 기존 토큰, RT 동시성, 회수 전후 요청, 우회 진입점, 장애 시 실패 동작을 변경 범위에 맞춰 검사한다. Java 21을 확인하고 변경된 각 앱에서 ./gradlew test bootJar를 실행한다. 미실행은 그대로 보고한다. 다른 프로젝트의 모듈 구조나 Spring Security 도입 금지 규칙을 가져오지 않는다.

## 구조 설계 참조

[시나리오 기반 구조 설계](../../references/system-design.md)를 Trust Chain 계층으로 이동할 때 앱별 DI·DB 권한·Auth 전용 서명키와 기존 인증·인가·감사 transaction 경계를 검토한다. Jev 판단을 실시간 인가 조건으로 넣지 않는다.
