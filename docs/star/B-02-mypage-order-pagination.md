# B-02 마이페이지·주문목록 전량조회 → 인덱스 + 페이지네이션

- 상태: 완료(L-2 사이클)
- 연결: 부하 실험 L-2 · 로드맵 `attack-simulation/docs/star/S-00`
- 작성/갱신: 2026-09-27

## S — 문제 발생 (부하 테스트로 드러남)

- `MyPageService`가 주문을 **전부 조회한 뒤 자바에서 `.limit(5)`** 했다. 주문 목록 API도 전량을 직렬화했다.
- `orders(user_id, ordered_at)` 인덱스가 없어 `ORDER BY ordered_at DESC`가 filesort였다.
- heavy 데이터(사용자당 주문 1만 건, 총 20만 건)로 k6 부하:
  - 처리량 44 req/s, iteration p95 14.3s, **수신 4.0GB**(주문 응답 단건 ~932KB), 실패 0.
- 금융 관점 리스크: 주문·거래 이력이 쌓인 활성 고객일수록 마이페이지·목록이 느려지고, 응답이 커져 대역폭·힙을 잠식한다. 데이터가 늘수록 악화된다.

## T — 왜 / 목표

- 데이터량과 무관하게 마이페이지·목록 지연을 일정하게 유지한다.
- 서버가 페이지 크기를 상한해 클라이언트가 전량 조회로 되돌리지 못하게 한다.
- 성공 기준: 같은 heavy 데이터·부하에서 p95와 수신 데이터를 크게 낮추고 실패 0 유지.

## A — 어떻게

### 웹 조사로 확인한 표준 해결 (새 기술 도입 전 근거 수집)

- **오프셋 vs keyset 페이지네이션:** 오프셋은 깊은 페이지에서 O(n)으로 악화(100만 행에서 offset 0=0.28ms → offset 999,990=138ms), keyset은 인덱스 seek로 O(1) 유지. 다만 복합 인덱스·순차 접근 전제. [Postgres Pagination: Keyset vs Offset](https://www.stacksync.com/blog/keyset-cursors-postgres-pagination-fast-accurate-scalable), [Keyset Cursors, Not Offsets](https://blog.sequinstream.com/keyset-cursors-not-offsets-for-postgres-pagination/)
- **"전량 fetch 후 메모리 컷" 안티패턴:** Hibernate가 결과를 전부 힙에 올리고 대부분 버린다(사례: 힙 310MB·응답 895ms). 첫 수천 건을 넘긴 Spring Data JPA 앱의 가장 흔한 성능 결함. [Spring pagination in-memory](https://goldlapel.com/grounds/spring-java/spring-pagination-join-fetch-memory), [JPA anti-patterns](https://codewiz.info/blog/jpa-performance-anti-patterns/), [Baeldung: Limiting Query Results](https://www.baeldung.com/jpa-limit-query-results)

### 적용

1. 인덱스 `idx_orders_user_ordered (user_id, ordered_at DESC)`를 schema.sql에 추가. EXPLAIN이 `ref` + filesort 제거로 바뀜.
2. 마이페이지: `findTop5ByUserIdOrderByOrderedAtDesc`로 **DB LIMIT 5** (자바 `.limit` 제거).
3. 주문 목록: `Page<Order> findByUserIdOrderByOrderedAtDesc(userId, Pageable)`, 컨트롤러에서 **size 상한 100·기본 20**.
4. mock 테스트로 서비스가 top5/Pageable 경로를 호출함을 고정.

### 왜 오프셋을 먼저 택했나 (대안 비교)

| 방식 | 장점 | 단점 | 선택 |
|---|---|---|---|
| 오프셋(Page) | Spring Data 기본, 총 개수·임의 페이지 이동 쉬움 | 깊은 페이지 O(n) | **1차 채택**. 마이페이지 UX는 최근 위주라 깊은 페이지 접근이 드묾 |
| keyset(커서) | 깊은 페이지도 O(1) | 총 개수·페이지 점프 어려움, 커서 계약 필요 | 딥 페이지가 문제되면 도입(후속 L-2b) |

인덱스는 두 방식 모두의 전제다. 이번엔 **행 수를 줄이는 것이 핵심**이라 오프셋+size 상한으로 충분히 해결됐다.

### 시행착오
- "인덱스만" 먼저 측정 → 44→41 req/s, 4.0→3.7GB로 **거의 무변화**. 병목이 정렬이 아니라 전 행 fetch·직렬화임을 실측으로 확인(웹 근거와 일치). 인덱스 추가만으로 끝냈다면 오진했을 것.

## R — 개선 결과

heavy 데이터(20 사용자 × 1만 주문), k6 ramp 최대 50 VU, 3.5분. 호스트 12CPU/32GB, 컨테이너 상한 적용.

| 지표 | baseline | 인덱스만 | 인덱스+페이지네이션 |
|---|---|---|---|
| 처리량 | 44 req/s | 41 req/s | **139 req/s** (약 3.2배) |
| iteration p95 | 14.3s | 15.3s | **2.76s** (약 5.2배↓) |
| 수신 데이터 | 4.0GB | 3.7GB | **54MB** (약 74배↓) |
| 실패율 | 0% | 0% | 0% |
| /orders 단건 응답 | ~932KB(1만건) | 동일 | **2.1KB(20건)** |

산출물: `attack-simulation/load/results/l2_{baseline,index_only,paginated}.json`

## 자소서 한 줄 (초안)

주문 이력이 많은 계정에서 마이페이지 응답이 지연되는 문제를 부하 테스트로 재현하고, 원인이 "전량 조회 후 메모리 컷"임을 인덱스-단독 측정으로 분리한 뒤, DB LIMIT·페이지네이션(서버 측 페이지 크기 상한)으로 동일 부하에서 처리량 3.2배·응답 데이터 74배 감소를 확인했습니다.
