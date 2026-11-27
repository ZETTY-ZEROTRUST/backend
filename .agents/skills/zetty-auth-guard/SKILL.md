---
name: zetty-auth-guard
description: "ZETTY 서버 세션의 로그인, CSRF, 객체 인가, 계정 상태와 세션 회수를 설계·구현·검토할 때 사용한다. 단순 주석 편집에는 전체 인증 검토를 강제하지 않는다."
---

# 인증·인가·회수 경계

backend/README.md, backend/docs/auth-token-architecture.md와 변경 대상의 실제 security/service/test를 읽는다.

## 검토 순서

1. trust-chain-server의 서버 세션 흐름을 읽고 ZETTY에 가져올 설정과 제거할 기존 토큰 구성을 구분한다. 원본은 수정하지 않는다.
2. 검증된 principal에서 actor를 얻는다. 요청 user ID, 미검증 토큰, 외부 헤더를 신원으로 신뢰하지 않는다.
3. 세션 쿠키 Secure/HttpOnly/SameSite, CSRF·Origin, 로그인 시 세션 ID 교체, 유휴·절대 만료, 로그아웃을 확인한다. 신뢰 프록시 범위 없이 XFF를 확정 IP로 쓰지 않는다.
4. 계정 상태/authVersion 변경 후 기존 세션의 실제 접근 회수와 캐시 지연을 확인한다. DB·Redis 장애를 허용으로 바꾸지 않는다.
5. 주문 접근은 principal의 소유권 범위 query로 제한한다. 직원·팀 모델을 새로 도입하지 않는다. OBJECT_NOT_FOUND_OR_NOT_OWNED로 실제 소유자를 추정하지 않는다.
6. 로그인 성공·주문 접근 거부 감사의 발생 위치를 확인한다. Redis 세션 저장과 PostgreSQL 감사는 원자적이지 않으며 부분 실패 처리가 필요하다. 거부 감사는 업무 rollback과 분리한다.
7. AI는 실시간 인가 조건이나 자동 회수 집행자가 아니다. UBA가 backend DB/세션 Redis를 직접 변경하지 않는다.

## 검증

변경 범위에 맞춰 정상/거부, CSRF, 세션 고정 방지, 만료·로그아웃·계정 회수, 소유권, 장애와 감사 누락을 검사한다. Java 21과 실제 Gradle task를 확인한다. 아직 없는 네 모듈이나 테스트를 실행했다고 보고하지 않는다. JWT·쿠키·비밀번호·키를 로그/fixture/prompt에 남기지 않는다.

## 구조와 작업 범위

[현재 선택한 구조와 작업 경계](../../references/system-design.md)를 먼저 읽는다. 목표는 서버 세션을 사용하는 단일 backend(네 Gradle 모듈, 실행 앱 하나)와 uba-analyzer, PostgreSQL이다. 실제 구현은 단계별로 확인한다. 기존 Auth/API/BFF·MySQL 코드를 목표 구조가 구현됐다는 증거로 쓰거나, 기존 JWT 기능을 반드시 보존할 목표로 취급하지 않는다.

사용자와 변경 이유·흐름·대상 파일·검증 방법을 먼저 정하고 승인한 단계만 구현한다. 같은 범위의 승인을 반복 요청하지 않는다. 설계·문서·에이전트 구성 요청은 실행 코드·SQL·의존성·Compose·데이터 변경이나 서비스/공격/유료 API 실행 권한이 아니다. trust-chain-server 원본은 읽기 전용이다.

경로는 워크스페이스 기준이다. 개별 clone에서는 자기 README와 실제 파일을 먼저 확인하고 없는 형제 레포가 필요한 검증만 미실행으로 남긴다. 삭제된 log-pipeline 런타임·계약·테스트를 복원하지 않는다. Java 전달 작업은 backend, Python 수신·집계·Jev·보고서는 uba-analyzer가 소유한다.
