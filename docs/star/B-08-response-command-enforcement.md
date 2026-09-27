# B-08 ResponseCommand 집행(I-04) — Auth 집행 측 수신·검증·멱등 적용

- 상태: 완료
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

- `@Transactional`을 내부 `process()`에만 두고 `handle()`이 같은 빈에서 self-invocation하면 프록시를 우회해 트랜잭션이 시작되지 않는다 → 단일 public `@Transactional handle`로 합쳤다(집행·결과 저장·이벤트가 한 트랜잭션).
- `ResponseCommandService`에 생성자가 2개(`@Autowired` 없음)면 Spring이 생성자를 고르지 못한다 → Clock 주입용 보조 생성자를 없애고 `Clock.systemUTC()` 필드로 단일 생성자.
- 재전송 재생 때 `applied_at` 문자열이 정확히 일치하도록 `now`를 마이크로초로 잘라 저장·포맷을 맞췄다(H2 `DATETIME(6)` 왕복 오차 제거).

## R — 개선 결과 (Result)

- 환경: 컨테이너 빌드 `eclipse-temurin:21-jdk`, gradle 8.14.4. 명령 `./gradlew --no-daemon test bootJar`(auth-server, api-server), exit 0(BUILD SUCCESSFUL).
- 단위/통합 테스트
  - auth-server **33건 통과**(신규 `ResponseCommandEnforcementTest` 11건 + 기존 22건), 실패·오류·skip 0.
  - api-server **48건 통과**(FK/`locked`/새 테이블 DDL만 `schema.sql`에 추가, 회귀 0). api-server 테스트는 `sql.init.mode=never`라 `schema.sql`을 로드하지 않는다 → 운영 DDL 자체는 테스트로 검증되지 않는다(수동 확인).
- 신규 11건이 검증한 것(결과는 response-result/1.0, 이벤트는 security-event/2.0 검증기로 대조):
  - DRY_RUN은 authVersion/locked 불변, 결과 `DRY_RUN`(RESPONSE_APPLIED/SUCCEEDED 발행).
  - local-lab ENFORCE `REVOKE_SESSION` → authVersion 0→1(옛 AT의 `authv=0`이 현재 version보다 작아 stale), RT family·발급대장 전부 REVOKED.
  - `LOCK_ACCOUNT` → `locked=true`·회수, 이후 로그인 400(“잠금” 사유). `unlockAccount` 후 재로그인 200.
  - 같은 command_id 재전송 → `ALREADY_APPLIED`, applied_at 동일, authVersion 재증가 없음, 새 이벤트 없음.
  - 만료 → `REJECTED/EXPIRED`, 상태 version 불일치 → `REJECTED/STALE_STATE_VERSION`(observed=현재 version).
  - synthetic ENFORCE → `REJECTED/ENVIRONMENT_MISMATCH`.
  - RESPONSE_APPLIED는 secret·raw userId·토큰 없이 발행(C-02 통과), 구조 위반은 `REJECTED/INVALID_COMMAND`로 로그 미저장, command_id 누락은 400.
- 계약 관찰 / 이탈(요청서 표현 대비)
  - 거부 사유는 계약 enum을 따른다: 환경 불가 → `ENVIRONMENT_MISMATCH`(요청서의 `ENV_NOT_ALLOWED`는 enum에 없음), 상태 불일치 → `STALE_STATE_VERSION`(요청서의 `STATE_MISMATCH` 아님).
  - `RATE_LIMIT` ENFORCE는 `status=APPLIED / reason=NONE`(APPLIED는 reason NONE 강제)로 의도만 기록. `RATE_LIMIT_RECORDED` 의미는 로그의 action 컬럼이 나타낸다. 실제 rate limit은 nginx/app 몫으로 범위 밖.
  - RESPONSE_APPLIED는 계약상 actor·operation·http=null이라 이벤트가 대상을 싣지 못한다 → 대상(가명을 해석한 userId)은 `response_command_log`·결과(command_id)가 소유. REJECTED에는 이벤트를 발행하지 않는다(집행 없음, outcome enum에 rejected 없음).
  - 대상 해석은 SUBJECT 가명만 지원한다. SESSION/IP는 `REJECTED/TARGET_OUT_OF_SCOPE`(세션→userId 매핑은 유지하지 않음).
- 미측정: 단계별 처리 시간(성능), `attack-simulation` 러너로 실제 스택 재공격(집행 후 재요청 차단율)은 별도 단계.

## A(추가) — compose 배선 + 실제 스택 종단 검증 (계획)

단위/통합 테스트(H2)는 집행 로직을 검증하지만, **운영 DDL·최소권한 grant·내부 전용 노출·가명 역매핑 upsert**는 실제 스택에서만 확인된다. Codex의 탐지 모델 없이도, 손으로 만든 대응 명령을 집행 경로에 넣어 **탐지→정책→집행 루프의 집행 절반**을 실증한다(탐지 절반은 Codex 몫).

1. **배선**: `gen-secrets.sh`에 auth_app의 `actor_identity_map`·`response_command_log` grant 추가(api/bff는 접근 없음). 스키마는 backend-resp의 `schema.sql`(새 테이블·`users.locked` 포함)을 mysql-init로 재생성. `BACKEND_PATH=backend-resp`로 스택 기동(auth=집행 코드 포함, api=VT 제외·기본 동일).
2. **내부 전용 확인**: edge(nginx)로 `/internal/response-commands` 호출 시 auth에 닿지 않음(=`/`→api→404), auth는 host 포트 미노출. 명령 투입은 compose 네트워크 안(`application`)에서만.
3. **종단 시나리오**(대상 해석은 DB의 `actor_identity_map`에서 subject_key를 읽어 실제 가명으로 명령 구성):
   - 로그인 → `actor_identity_map`에 subject_key↦userId upsert 확인.
   - DRY_RUN LOCK_ACCOUNT → `status=DRY_RUN`, `locked` 불변, 로그인 계속 성공.
   - ENFORCE LOCK_ACCOUNT → `status=APPLIED`, `locked=true`, authVersion++, RT family·대장 폐기, 이후 **로그인 400(잠금)**.
   - 같은 command_id 재전송 → `ALREADY_APPLIED`, 추가 상태 변경 없음(멱등).
   - synthetic ENFORCE → `REJECTED/ENVIRONMENT_MISMATCH`.
   - `response_command_log`에 기록, RESPONSE_APPLIED가 secret·raw userId 없이 Outbox→ES까지(analysis profile) 전달.
   - 마무리: `unlockAccount`로 계정 원복(lab 재현성).

## R(추가) — 실제 스택 종단 검증 결과 (실측)

BACKEND_PATH=backend-resp로 auth 재빌드, 실행 중 MySQL에 추가형 DDL(actor_identity_map·response_command_log·users.locked)과 auth_app grant만 적용(볼륨 파괴 없음). user001로 종단 시나리오 실행:

| 단계 | 명령 | 결과(response-result) | 상태 변화 | 로그인 |
|---|---|---|---|---|
| 로그인 | — | 200 | actor_identity_map에 subject_key↦userId **upsert**(43자 base64url 가명) | 200 |
| DRY_RUN | LOCK_ACCOUNT / local-lab | `DRY_RUN`, NONE | authVersion·locked **불변** | 200 |
| ENFORCE | LOCK_ACCOUNT / local-lab / expected=현재ver | `APPLIED`, applied_at 기록 | **authVersion 2→3, locked=1**, RT family·대장 폐기 | **400(잠금)** |
| 재전송 | 같은 command_id | `ALREADY_APPLIED`, **applied_at 동일** | authVersion **재증가 없음**(멱등) | 400 |
| synthetic | ENFORCE / synthetic | `REJECTED / ENVIRONMENT_MISMATCH` | 불변 | — |
| 원복 | (운영 unlock) | — | locked=0 | 200 |

- `response_command_log` 3행 확인: DRY_RUN→`DRY_RUN`(observed_ver=현재), ENFORCE→`APPLIED`, synthetic→`REJECTED/ENVIRONMENT_MISMATCH`(observed=NULL, 대상 해석 전 거부).
- **내부 전용 실증**: edge(nginx)는 `/internal`을 auth로 라우팅하지 않고(`/`→api→404), auth는 host 포트 미노출. 명령 투입은 compose 네트워크 안(`auth:8080`)에서만 가능.
- 대상 해석: 명령은 raw userId가 아닌 **가명(subject_key)** 만 싣고, 집행 측 `actor_identity_map`으로만 userId를 얻음(위조 명령으로 임의 계정 집행 불가).

### 집행 이벤트(RESPONSE_APPLIED)의 전달 — 파이프라인 장애 발견·해결(교차 연결 `log-pipeline/P-03`)

집행 트랜잭션이 남긴 RESPONSE_APPLIED가 **Outbox에 정상 적재**됨(감사-후-집행 성립). 종단(ES)을 확인하려다 **파이프라인 정지**를 발견: 이전 부하 시험 22만 건으로 redis-events(maxmemory 256M, noeviction)의 스트림이 가득 차 XADD가 OOM 거부 → relay 정체(Outbox PENDING 20만+ 미배출). indexer가 색인 후 스트림을 트림하지 않은 게 원인. **indexer에 "색인·ACK 후 MINID 트림"을 추가(P-03)** 해 메모리 256M→11.6M 회수, Outbox 205,075→**0**(약 42초) 배출. RESPONSE_APPLIED는 Outbox `PUBLISHED`로 스트림 발행 완료 후 **ES 색인 도달 확인**(ES `event_type:RESPONSE_APPLIED` **count=4**, 스트림 배출 완료). 집행→감사→Outbox→Streams→ES **전 구간 종단 성립**(DLQ=0, 유실 없음).

- 검증: auth-server 33/33(재실행), 실제 스택 종단 표의 전 항목 통과. 미측정: 단계별 처리 시간(성능).

## 자소서 한 줄 (R 확정 후)

탐지·정책이 만든 대응 명령을 집행 측이 다시 검증(멱등 command_id·만료·환경 allowlist·상태 version·가명 역해석)하고, 회수·잠금·감사 이벤트를 한 트랜잭션으로 집행하는 경로를 구현해 통합 11건으로 DRY_RUN 안전 기본값과 멱등·거부 경로를 검증했다.
