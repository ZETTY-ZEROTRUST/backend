---
name: zetty-new-endpoint
description: "ZETTY 단일 backend의 endpoint를 서버 세션·객체 인가·업무·감사 경계와 네 모듈 구조에 맞춰 추가할 때 사용한다."
---

# ZETTY 새 엔드포인트

## 작업 순서

1. 사용자와 endpoint의 입력·출력·인증·객체 권한·감사 기록과 대상 파일을 먼저 정한다. backend/README.md와 auth-token-architecture.md, 실제 유사 코드를 읽는다.
2. domain의 모델·서비스/저장소 인터페이스, application의 서비스 구현, infrastructure의 저장소 구현, bootstrap-app의 controller/facade/command/result/security 조립으로 책임을 나눈다. 실제 승인된 패키지와 기존 패턴을 따른다.
3. 검증 principal과 소유권 query로 제한한다. 서버 세션, CSRF/Origin, 만료·계정 상태를 적용한다. Controller에 업무 로직을 넣지 않는다.
4. PostgreSQL 스키마·제약·최소 권한을 검토한다. 일반 앱 기동마다 DDL/seed를 재실행하지 않는다. 기존 MySQL 코드의 전환은 승인한 단계에 포함될 때만 한다.
5. 감사 사실과 전달 상태의 트랜잭션, 거부/rollback 감사 경계를 확인한다. Redis와 DB의 부분 실패를 설명한다. HTTP 전송은 backend job이 담당하고 DB 트랜잭션 안에서 호출하지 않는다.
6. 실제 계약/fixture가 존재하면 UBA 수신 의미를 함께 확인한다. 설계 문서만으로 이벤트 schema 구현 완료를 주장하지 않는다.
7. 정상·거부·소유권·세션 만료/회수·CSRF·rollback을 변경 범위에 맞춰 검사한다. Java 21의 실제 Gradle task를 실행하고 PostgreSQL 의미는 실제 엔진으로 검증한다.
8. 승인된 변경에 맞춰 API 문서·인증·감사 설계를 갱신한다. 적용 경계·수정 경로·실행 결과·미실행 항목을 보고한다.

bootstrap-app이나 테스트가 아직 없으면 먼저 승인된 생성/이관 단계가 필요하다. 엔드포인트 하나를 추가하면서 전체 backend를 자동 재작성하지 않는다.

## 구조와 작업 범위

[현재 선택한 구조와 작업 경계](../../references/system-design.md)를 먼저 읽는다. 목표는 서버 세션을 사용하는 단일 backend(네 Gradle 모듈, 실행 앱 하나)와 uba-analyzer, PostgreSQL이다. 실제 구현은 단계별로 확인한다. 기존 Auth/API/BFF·MySQL 코드를 목표 구조가 구현됐다는 증거로 쓰거나, 기존 JWT 기능을 반드시 보존할 목표로 취급하지 않는다.

사용자와 변경 이유·흐름·대상 파일·검증 방법을 먼저 정하고 승인한 단계만 구현한다. 같은 범위의 승인을 반복 요청하지 않는다. 설계·문서·에이전트 구성 요청은 실행 코드·SQL·의존성·Compose·데이터 변경이나 서비스/공격/유료 API 실행 권한이 아니다. trust-chain-server 원본은 읽기 전용이다.

경로는 워크스페이스 기준이다. 개별 clone에서는 자기 README와 실제 파일을 먼저 확인하고 없는 형제 레포가 필요한 검증만 미실행으로 남긴다. 삭제된 log-pipeline 런타임·계약·테스트를 복원하지 않는다. Java 전달 작업은 backend, Python 수신·집계·Jev·보고서는 uba-analyzer가 소유한다.
