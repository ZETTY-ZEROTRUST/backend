---
name: zetty-event-contract-guard
description: "ZETTY 감사 이벤트와 PostgreSQL 전달 상태, backend HTTP 전송 및 UBA 수신·분석 작업의 계약과 재처리를 변경하거나 검증할 때 사용한다."
---

# 감사 이벤트와 전달 계약

backend/docs/audit-event-design.md, uba-analyzer/README.md와 실제 producer/consumer를 읽는다. 설계 문서의 제안 필드를 이미 구현된 schema로 취급하지 않는다.

## 의미와 호환성

- 검증된 서버 신원과 실제 관측 사실만 기록한다. event_id·발생 시각·사건 종류·결과·사유·가명 연계값의 타입, null, 버전을 생산자/소비자에서 맞춘다.
- 요청 연계값과 세션 연계값은 구별한다. 세션 연계값으로 로그인할 수 없어야 한다. 신뢰 프록시 검증 없는 XFF는 확정 IP가 아니다.
- OBJECT_NOT_FOUND_OR_NOT_OWNED는 미존재와 소유권 위반을 구별하지 않는다. HMAC 대상값의 다양성과 원래 번호의 순서는 다른 정보다.
- 미관측은 null/누락 상태로 표현하고 0·정상으로 채우지 않는다. 개인정보·자격증명·원문 query/본문을 보내지 않는다.

## 저장·전송·수신

- 감사 원본과 audit_delivery 대기를 같은 PostgreSQL 트랜잭션으로 저장한다. 거부·실패 감사는 업무 rollback에도 남아야 한다.
- backend Java job이 내부 인증된 HTTP로 전송한다. 최초 worker 하나를 기준으로 하며 네트워크 I/O는 DB 트랜잭션 밖에서 수행한다.
- UBA는 received_events와 analysis_jobs 저장을 커밋한 뒤 ACK한다. 같은 event_id·내용은 중복 성공, 같은 ID·다른 내용은 충돌이다. ACK는 Jev 분석 완료가 아니다.
- ACK 유실·timeout·재시작의 at-least-once와 멱등 수신, 재시도 한도·영구 실패·운영 재처리를 함께 정의한다. UBA DB와 backend DB를 공유 쓰기하지 않는다.
- 늦은 이벤트와 event-time 창, 이력 기준 시점, 분석 작업 중복을 확인한다. Redis 세션 저장과 PostgreSQL 감사는 분산 원자성이 없다.

## 검증

실제 schema/fixture가 있으면 양쪽 round trip, 저장 전 ACK 금지, 중복·내용 충돌·재시작·ACK 유실·지연 이벤트·관측 누락을 검증한다. PostgreSQL 제약/잠금은 실제 엔진으로 검사한다. mock만으로 영속성이나 실환경 통신 성공을 주장하지 않는다.

## 구조와 작업 범위

[현재 선택한 구조와 작업 경계](../../references/system-design.md)를 먼저 읽는다. 목표는 서버 세션을 사용하는 단일 backend(네 Gradle 모듈, 실행 앱 하나)와 uba-analyzer, PostgreSQL이다. 실제 구현은 단계별로 확인한다. 기존 Auth/API/BFF·MySQL 코드를 목표 구조가 구현됐다는 증거로 쓰거나, 기존 JWT 기능을 반드시 보존할 목표로 취급하지 않는다.

사용자와 변경 이유·흐름·대상 파일·검증 방법을 먼저 정하고 승인한 단계만 구현한다. 같은 범위의 승인을 반복 요청하지 않는다. 설계·문서·에이전트 구성 요청은 실행 코드·SQL·의존성·Compose·데이터 변경이나 서비스/공격/유료 API 실행 권한이 아니다. trust-chain-server 원본은 읽기 전용이다.

경로는 워크스페이스 기준이다. 개별 clone에서는 자기 README와 실제 파일을 먼저 확인하고 없는 형제 레포가 필요한 검증만 미실행으로 남긴다. 삭제된 log-pipeline 런타임·계약·테스트를 복원하지 않는다. Java 전달 작업은 backend, Python 수신·집계·Jev·보고서는 uba-analyzer가 소유한다.
