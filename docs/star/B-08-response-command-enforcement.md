# B-08 ResponseCommand 집행(I-04) — Auth 집행 측 수신·검증·멱등 적용

- 상태: 계획
- 연결: 로드맵 I-04(response-command 집행) · 계약 `log-pipeline/contracts/response-command/v1` · 이벤트 계약 C-02 `security-event/2.0`(RESPONSE_APPLIED)
- 작성/갱신: 2026-09-27

## S — 문제 발생 (Situation)

탐지(anomaly-detection)와 정책(response policy)이 대응 명령(response-command/1.0)을 만들어도, **Auth에는 그 명령을 받아 실제로 세션·계정 상태를 바꿀 집행 경로가 없다.**
현재 회수 수단은 사람이 부르는 `POST /auth/logout`(RT로 사용자 식별 → `authService.logoutAll`) 하나뿐이다. 자동 대응 명령은 다음 위험을 가진다.

| 약점 | 영향 |
|---|---|
| 명령 수신·검증 경로 없음 | 탐지가 만든 조치를 사람이 손으로 옮겨야 하고, 그 사이 탈취 세션이 계속 유효 |
| 대상 식별을 명령이 지정 | 명령이 raw `userId`를 실으면, 명령 위조 한 건으로 임의 계정을 잠글 수 있음 |
| 멱등성 없음 | 같은 명령 재전송이 상태를 반복 변경(중복 폐기·중복 감사) |
| 만료·상태 version 미확인 | 낡은(stale) 관측으로 만든 명령이 늦게 도착해 잘못된 상태를 덮어씀 |
| 환경 구분 없음 | `synthetic`·미지정 환경의 명령이 실제 계정을 집행 |

## T — 왜 / 목표 (Task)

- 계약(`response-command/1.0`)을 받는 **집행 전용 내부 엔드포인트**를 두되, schema 통과만으로 신뢰하지 않고 집행 측이 다시 검증한다:
  멱등(command_id) · 유효기간(expires_at) · 환경 allowlist · 상태 version(authVersion) · 대상 범위(가명→userId 해석) · 기본 안전값(DRY_RUN).
- 대상 `target_key.key`는 **가명(HMAC)** 이다. 가명→실제 userId 매핑은 **집행 측만** 가지며, 로그인 때 Auth가 채운다. 명령이 준 userId는 신뢰하지 않는다.
- 실제 집행은 기존 회수 경로(authVersion 증가 + RT family 폐기 + 발급대장 폐기)를 재사용하고, `LOCK_ACCOUNT`는 계정 `locked`까지 세운다(잠긴 계정은 로그인 거부).
- 조치 DB 변경 · 결과 기록 · `RESPONSE_APPLIED` 이벤트 Outbox를 **한 트랜잭션**으로 묶는다(계약: 결과와 Outbox는 같은 transaction).
- 성공 기준(테스트)
  - DRY_RUN은 authVersion/locked를 바꾸지 않고 결과 `DRY_RUN`.
  - local-lab ENFORCE `REVOKE_SESSION`은 authVersion을 올려 기존 AT가 다음 검증에서 거부된다(authVersion 대조로 시뮬레이션).
  - `LOCK_ACCOUNT`는 locked를 세우고 이후 로그인이 잠금 사유로 실패.
  - 같은 command_id 재전송 → `ALREADY_APPLIED`, 두 번째 상태 변경 없음.
  - 만료 → `REJECTED/EXPIRED`, 상태 version 불일치 → `REJECTED/STALE_STATE_VERSION`.
  - synthetic ENFORCE → `REJECTED/ENVIRONMENT_MISMATCH`.
  - `RESPONSE_APPLIED`가 가명 대상·secret 없이 발행되고, 결과는 `response-result/1.0` schema를 통과.

## A — 어떻게 (Action)

### 계획

- 엔드포인트 `POST /internal/response-commands`(브라우저 비노출). Compose에서는 내부 네트워크 전용이며 nginx가 프록시하지 않는다(경로만 permitAll).
- 파서/검증기(Java, 계약 shape을 코드로 검사): 런타임 JSON Schema 검증기는 test 전용 의존성이라 운영 classpath에 없다 → 필수 필드·enum·UUID·evidence_ref·근거(basis)·action/target 호환·시각(만료>요청)을 Java로 검사한다. 위반은 `REJECTED/INVALID_COMMAND`.
- 처리 파이프라인(첫 매치 우선): 구조 검증 → 멱등 조회 → **환경 gate**(ENFORCE는 allowlist 환경만; 그 외 `REJECTED/ENVIRONMENT_MISMATCH` — synthetic ENFORCE도 여기서 걸린다) → 만료 → 대상 해석(가명→userId) → 상태 version 대조(optimistic) → 적용(DRY_RUN이면 상태 불변).
- 대상 해석: 로그인 때 `actor_identity_map(subject_key, user_id)`에 역매핑을 upsert(기존 `Pseudonymizer` 재사용, `subject_key = HMAC(zetty.subject, userId)`). 집행 측은 `target_key.key`로 userId를 조회한다.
- 멱등: `response_command_log(command_id PK, ...)`. 이미 있으면 저장된 결과를 재생(APPLIED였으면 `ALREADY_APPLIED`, 상태 변경 없음).
- 집행: `REVOKE_SESSION`/`REQUIRE_REAUTH`/`LOCK_ACCOUNT` → `authService.logoutAll`(authVersion++ + RT family 폐기 + 대장 폐기) 재사용, `LOCK_ACCOUNT`는 `locked` 추가. `RATE_LIMIT` → 의도만 기록(실제 제한은 nginx/app 몫, 범위 밖).
- 결과·이벤트: 결과는 `response-result/1.0` shape으로 반환하고 `response_command_log`에 저장, `RESPONSE_APPLIED`는 기존 `AuthEventRecorder`/Outbox로 같은 트랜잭션에 발행.

### 검토한 대안과 선택 이유

| 결정 | 대안 | 선택 | 이유 |
|---|---|---|---|
| 명령 수신 | (a) 동기 내부 API 수신 (b) 메시지 큐 소비 | **(a) 동기 API** | 로컬 lab 초기 실행 단위는 Worker 1건(수신→검증→적용→저장). 큐는 재시도·순서·backpressure를 더하지만 지금 필요한 것은 집행 의미의 정확성. 큐는 부하·비동기 요구가 측정된 뒤 후속. |
| 멱등 저장 | (a) command_id PK 로그 테이블 (b) 이벤트 event_id 중복 방지에만 의존 | **(a) command_id PK** | 계약이 command_id를 멱등 키로 정의(재전송은 같은 command_id). PK 충돌/사전 조회로 재적용을 원천 차단하고 최초 적용 시각·observed version을 재생. |
| 대상 식별 | (a) 명령의 raw userId 신뢰 (b) 집행 측 가명 역매핑 | **(b) 역매핑** | 위협 규칙: 명령은 가명만 싣고 매핑 권한은 집행 측만. raw userId 신뢰는 명령 위조로 임의 계정 집행 가능. 로그인 때 채운 `actor_identity_map`으로만 해석. |
| 기본 mode | (a) 명령 mode 그대로 (b) 기본 DRY_RUN·ENFORCE는 허용 환경만 | **(b) 안전 기본값** | ENFORCE는 local-lab(allowlist)만. 나머지 환경은 DRY_RUN 또는 거부. 오집행 폭을 환경으로 제한. |

### 시행착오

- (기록 예정)

## R — 개선 결과 (Result)

미측정

## 자소서 한 줄 (R 확정 후)

(R 확정 후 작성)
