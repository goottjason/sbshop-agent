# Defect Ledger — sbshop-agent

> 초판 작성: 2026-07-07  
> 진단 사이클: 사이클 1 (Phase 1)  
> 작성자: defect-scout

---

## 요약 표

| 지표 | 값 |
|------|-----|
| 총 결함 수 | 13 |
| P0 (빌드/시작 불가) | 2 |
| P1 (기능 불능) | 1 |
| P2 (오동작/아키텍처 리스크) | 5 |
| P3 (품질/부채) | 5 |

> 2026-07-07 갱신(tdd-fixer, 사이클 1 수정 중): 신규 D-009(AsyncConfig 2차 P0 빈 충돌)·D-010(Elevenst 클래스명 중복)·D-011(BatchPriceStockService bare @Async)·D-012(Coupang 탐색 테스트 잔재)·D-013(api 테스트 datasource/Flyway 순환 depends-on) 추가. D-001·D-003·D-009·D-006·D-012 수정완료(검증대기), D-001~D-006 qa 검증통과.
>
> 2026-07-07 사이클 1 종료(리더): **검증통과 5건**(D-001·D-003·D-006·D-009·D-012 — P0 2건 전부 해소), 보류 1건(D-002 오판 — 스케줄러는 실제 활성. 위 P1 카운트는 기록 보존 목적). 잔여 미해결 7건(D-004·D-005·D-007·D-008·D-010·D-011·D-013).
>
> 2026-07-07 사이클 9 갱신(리더, 운영 가동 이후 사용자 신고 3클러스터 병렬 진단): **신규 17건 등재 D-022~D-038**. 사용자 신고 3대 증상 직접원인 확정 — D-022(동기화 finally 이중이벤트 에러은폐)·D-027(쿠팡 오취소)·D-034(상품 cellRenderer HTML 이스케이프). P0 1(D-022)·P1 5(D-023·D-027·D-028·D-029·D-034)·P2 6·P3 5. 클러스터 C(주문상태)의 핵심은 "취소/반품/교환 전환을 동기화에 반영 못하는 구조적 공백"(쿠팡·11번가·ESM+). 클러스터 D(상품)는 UI 뼈대 방치 — 백엔드/API 완비, 상세모달·이미지변경 미배선.
>
> 2026-07-07 사이클 4 갱신(fixer-c4, D-005+D-013 통합 중대 배치·사용자 승인): **D-005·D-013 수정완료(검증대기)**. testcontainers-PostgreSQL 도입, api 순환 depends-on 해소(defer 제거), after-migrate.sql → V5 흡수(멱등). **D-013은 실 Postgres Red 재현으로 P0 확정**(운영 기동 차단). 배치 중 파생: **신규 D-015**(V1~V4 빈 DB 비자족 — V2 shipping_fee)·**D-016**(sb_market_credential/sb_market_registration 마이그레이션 부재), 둘 다 범위 밖 후보로 기록. Flyway 의존성 공백(`flyway-database-postgresql`)도 교정. 총 결함 16건(D-001~D-016).

> 2026-08-21 갱신(리더, 전면 구조 리팩토링 캠페인): **신규 18건 등재 D-163~D-180** (P1 2: D-167 Cafe24 거짓성공·D-175 상품 500건 상한 / P2 9 / P3 7). 캠페인 자체는 구조 변경만 수행(행위 변경 0) — 주석 전량 제거·FQN→import·스텝다운 정렬·데드코드 삭제(BusinessDayCalculator·UnipassUpdateRequest·프론트 supplierApi/App.css/assets/ag-grid 등). 제거 주석의 "왜" 지식은 `docs/normalize/refactor-20260821/salvage-*.md` 6부에 보존 — **추후 주석 재작성 규칙 수립 시 1차 원천.** 특기: `BannedIngredientSyncService` raw NUL(0x00)로 grep이 파일째 누락하던 함정 발견·교정(`"\0"` 이스케이프, 바이트 동일) — 미참조 판정은 `grep -ra` 필수.

### D-272: 쿠팡·11번가·스마트스토어의 "취소"는 우리 장부에만 있다 (2026-09-02, D-265 3단계 중 발견)

- 심각도: P2 | 위치: `OrderService.cancelOrder`
- `cancelOrder` 는 **G마켓/옥션에만 마켓 취소를 전파한다**(`marketplaceShippingService.cancelOrderToMarketplace`). 쿠팡·11번가·스마트스토어는 마켓 호출 없이 라인아이템을 `CANCELED` 로 찍기만 한다.
- 즉 이 세 마켓에서 "발주취소"를 누르면 **마켓에는 여전히 살아 있는 주문**이 우리 화면에서만 취소로 보인다. "마켓이 진실" 원칙과 정면으로 어긋난다.
- [[D-265]] 3단계에서 마켓 호출이 있는 경로(G마켓/옥션)만 재조회로 전환했다. 나머지를 함께 전환하면 재조회 결과가 `NEW` 라 취소가 통째로 사라진다 — 기능이 없어지므로 손대지 않았다.
- 착수 전 확인할 것: 세 마켓에 **판매자 발주취소 API 가 실재하는지**. 쿠팡은 판매자 임의취소가 제한적이고, 11번가는 `claimservice` 취소 API 가 있다([[D-270]] 조사에서 `cancelorders`/`canceledorders` 확인). 스마트스토어는 미확인.
- API 가 있으면 전파 + 재조회로 전환하고, 없으면 해당 마켓의 취소 버튼을 막거나 "우리 장부에만 반영됨"을 화면에 명시해야 한다.
- 상태: **발견** 2026-09-02

### D-273: 확증층의 120일 목록 조회가 마켓 API 기간 제한을 위반한다 (2026-09-02, 사용자 신고)

- 심각도: **P1(화면에 상시 Red)** | 위치: `OrderSyncScheduler.reconcileOrders`
- 증상: 쿠팡·G마켓/옥션 동기화 상태가 Red. `sb_market_sync_status` 에
  `COUPANG: 429 TOO_MANY_REQUESTS (status=NONE_TRACKING)` · `GMARKET: Cafe24 API 422 "date range ... should be within 3 months"` 가 기록됐고 **`last_sync_at` 이 둘 다 확증 트리거 시각(05:25)** 이었다.
- 원인: 확증층이 **120일 창을 그대로 마켓 목록 API 에 보냈다.** 카페24는 조회 기간이 최대 3개월, 쿠팡은 31일이다. [[D-265]] 설계 5절에 "쿠팡 31일 청크 4개"라고 적었지만 **청크 로직은 구현되지 않았다** — `CoupangOrderSyncService`/`CoupangOrderAdapter` 어디에도 없다.
- **[[D-265]] 1단계 배포 이후 계속 실패하고 있었다.** `runQuietly` 와 각 sync 서비스 내부의 예외 삼킴 때문에 조용히 넘어갔고 `sync_status` 에만 Red 로 남았다. 확증 로그는 "완료"로 찍혀 정상처럼 보였다.
- 수정: **확증층에서 목록 조회를 제거한다.** 없애도 잃는 것이 없다 — 확증은 `Set.of()` 를 넘겨 목록 결과를 쓰지 않고 전부 단건 프로브한다(사용자 결정 "전부 단건 조회로 확증"). 즉 작동한 적 없는 낭비 호출이 Red 를 만들고 있었다. 30일 창의 부가 필드 갱신은 정기 동기화가 그대로 맡는다.
- 회귀 고정: `OrderReconcileScheduleTest.reconcile_doesNotCallMarketLists` — 되돌리면 실패하는 것을 확인했다.
- 프로브(단건 조회)는 영향 없다. 라이브에서 229건 기록(FOUND 216·TERMINATED 11·UNKNOWN 2)이 정상 수집됐다.
- 상태: **수정완료** 2026-09-02

## 배포 전 수동 DDL — **적용 완료 2026-09-02**

Flyway 를 쓰지 않으므로 배포 전에 운영 DB 에 직접 친다.
`docker exec -i projects-postgres-1 psql -U canagent -d sbshop`

```sql
-- D-265 2단계: 확증 프로브 결과 기록 (확인 못 한 주문을 드러내기 위한 것)
ALTER TABLE sb_order ADD COLUMN IF NOT EXISTS last_probe_status VARCHAR(20);
ALTER TABLE sb_order ADD COLUMN IF NOT EXISTS last_probe_at TIMESTAMP;
```

운영 DB 에 적용 확인했다(`last_probe_status varchar`, `last_probe_at timestamp`). 순서는 DDL 이 먼저다 — 반대면 `sb_order` 조회가 전부 깨진다.

---

## 실행 백로그 — 순차 진행 (2026-09-02 사용자 확정)

위에서부터 순서대로 진행한다. 앞 항목이 끝나기 전에 뒤를 건드리지 않는다.

| # | 항목 | 내용 | 위험 | 선행 |
|---|------|------|------|------|
| ~~1~~ | ~~**[[D-265]] 2단계**~~ | **완료 2026-09-02** (`cfe32638` terminal 폐기 · `634ff082` 프로브 결과 기록). 배포 대기 — DDL 2줄 미적용 | 낮 | 1단계(완료) |
| ~~2~~ | ~~**[[D-265]] 3단계**~~ | **완료 2026-09-02** (`963d3b92`·`61eae989`). 부수 발견 [[D-272]] | 높음 | 1단계 |
| ~~3~~ | ~~**[[D-265]] 4단계**~~ | **완료 2026-09-02** (`3f568317`) | 중 | 3단계 |
| 4 | **[[D-270]]** | 클레임 축 분리 + 취소·반품·교환 전면 세분화 — 스키마(클레임 필드) + 매퍼 4개 + 프로브 + 프론트 배지. 4마켓 코드표 확보 완료 | 중~높 | D-265 4단계 |
| 5 | **[[D-267]]** | 마켓 동기화 실패 사유 원문을 DB 에 저장 (`ProductMarketSyncService:121` 1-arg `recordSyncError`) | 낮 | — |
| 6 | **[[D-269]]** | 배치 결과·활동로그 프론트 UI/UX 전면 개편 — 부분 실패가 성공으로 보이는 문제 | 높(프론트 전면) | D-267 · D-265 4단계 |
| 7 | **[[D-266]]** | 스마트스토어 옛 상품 `originProduct.statusType` 400 (66건) | 중 | D-267 |
| 8 | 잔여 | [[D-260]] 쿠팡 임시저장 232건 · [[D-261]] 쿠팡 품질 경고 · [[D-259]] 전송실패 543건 · [[D-240]] 정기 크롤 활성화 | — | — |

**부수 결함(해당 단계에서 함께 처리):**
- 카페24 매퍼가 `N01`(교환접수-교환상품)·`N03`(교환접수-카드결제대기)을 매핑하지 않아 UNKNOWN 으로 떨군다 → D-270
- 쿠팡 교환 API(`exchangeRequests`)를 아예 쓰지 않아 쿠팡 교환이 감지되지 않는다 → D-270
- 11번가 클레임 목록 API 9종이 실재하므로 현행 우회(사라진 주문을 단건조회로 되짚는 `detectClaims`)를 걷어낼 수 있다 → D-270

---

## 최우선 수정 권고 3건 (사이클 2 대상 — 리더 갱신)

1. **D-013 (P2)** — api 테스트 datasource/Flyway 미구성: 현재 api 모듈에 @SpringBootTest 실기동 검증이 0인 상태. 통합 테스트 확보의 선결 과제 (D-005와 연계 처리 권장).
2. **D-004 (P2)** — 이미지 다운로더 3벌 통합: 구조 변경 배치. D-010과 함께 처리 가능.
3. **D-005 (P2)** — after-migrate.sql ↔ Flyway 중복 정리: 스키마 이력 단일화 (중대 등급 — 사용자 승인 필요).

---

## 결함 레코드

### D-001: ElevenstRestClient 중복 빈 충돌 → API 모듈 시작 불가

- 심각도: P0 (빌드/데이터 손상 — 앱 컨텍스트 시작 불가)
- 리스크 등급: 중대
- 위치:
  - `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/elevenst/ElevenstRestClient.java:23`
  - `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/elevenst/client/ElevenstRestClient.java:17`
- 증상: `@SpringBootTest` 컨텍스트 로딩 시 `ConflictingBeanDefinitionException` 발생. `CoupangApiExplorationTest`·`CoupangDebugTest` 모두 context load failure로 실패. 동일 패키지 스캔 경로를 쓰는 운영 `ApiApplication`도 시작 불가.
- 재현: `cd backend && ./gradlew :api:test` → `ConflictingBeanDefinitionException: Annotation-specified bean name 'elevenstRestClient' for bean class [...client.ElevenstRestClient] conflicts with [...elevenst.ElevenstRestClient]`
- 원인(확인): 두 클래스가 모두 `@Component`이고 기본 빈 이름 `elevenstRestClient`로 동일하게 등록됨. elevenst 패키지에 GET 전용 구버전, elevenst/client 패키지에 GET/POST/PUT + ElevenstProperties 주입 신버전이 공존. 레거시 병합 시 정리되지 않은 중복.
- 수정(2026-07-07): 두 클래스 모두 실사용처 존재(구버전←`ElevenstOrderApiClient` 주문 API, 신버전←`ElevenstMarketClient` 상품 API). 주입은 전부 타입 기반이므로 삭제/병합 없이 빈 이름만 분리 — 구버전 `@Component("elevenstOrderRestClient")`, 신버전 `@Component("elevenstMarketRestClient")`. 회귀 테스트: `api/src/test/.../ElevenstRestClientBeanConflictTest`. 클래스 단순명 동일(`ElevenstRestClient`)로 인한 혼동은 구조 개선 후보 → [[D-010]].
- 검증(2026-07-07, qa-verifier): 회귀 테스트 `ElevenstRestClientBeanConflictTest` Green(tests=1, failures=0), `:infrastructure:compileJava` 정상, 두 소비처 타입 주입·시그니처 유지, 문자열 빈 이름 참조 0건, 실제 elevenst 충돌 예외 전무. 전체 `:api:test`의 잔여 실패 3건은 전부 별개 원인(D-009 asyncConfig ×2, D-006 실API ×1)이며 D-001 유발 회귀 아님. 판정서: `_workspace/verify/D-001_verdict.md`.
- 상태: 검증통과 (범위: elevenstRestClient 충돌 해소. 영향모듈 전체 `:api:test` 그린은 [[D-009]] 해소 전제 — 커밋 게이트 조건)
- 이력: 2026-07-07 발견 → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과(범위 한정, D-009 전제)

---

### D-002: OrderSyncScheduler 6개 스케줄 전부 비활성화

- 심각도: P1 (기능 불능)
- 리스크 등급: 표준
- 위치: `backend/worker/src/main/java/com/sbshop/agent/worker/scheduler/OrderSyncScheduler.java:37, 52, 67, 82, 97, 112`
- 증상: 이메일(IMAP), 쿠팡, ESM+(G마켓/옥션), 스마트스토어, 11번가, 쿠팡 정산 동기화 6개 메서드가 `// TODO: 리팩토링 완료 후 활성화` 주석으로 실질적 cron 어노테이션은 존재하나 실행 의도가 보류 상태. 통관 상태 동기화(`syncCustomsStatus`, line 127)만 활성화되어 있음.
- 재현: 앱 기동 후 30분 이상 대기해도 `IMAP 이메일 주문 동기화 시작...` 로그 미출력. `syncStatusService` 상태가 초기값에서 변경되지 않음.
- 원인(확인): `@Scheduled` 어노테이션이 선언되어 있어 Spring은 해당 메서드를 스케줄에 등록하지만, TODO 주석 자체는 코드 실행에 영향을 주지 않음. 즉 어노테이션은 살아있고 메서드는 실행됨. 재확인 필요 — 어노테이션이 제거된 게 아니라 주석만 남은 것이므로 실제 스케줄은 돌고 있을 가능성 있음. 소스코드 확인: 37번 라인의 `@Scheduled(cron = "0 0/30 * * * ?")` 어노테이션은 그대로 존재하고, `// TODO` 는 같은 줄 주석임. 따라서 **스케줄은 실제로 등록되어 있음**. TODO 주석이 오해를 유발하는 부채 수준이나, 기능 자체는 비활성화 아님. 스킬의 "비활성 기능" 패턴이 실제로는 코드 수준에서 해소된 상태. → 상태를 `보류`로 수정.
- 상태: 보류 (TODO 주석만 존재, 어노테이션·메서드 본체 모두 정상. 기능 비활성화 아님 — 오판)
- 이력: 2026-07-07 발견 → 2026-07-07 보류 (소스 재확인 결과 @Scheduled 어노테이션 정상 존재)

---

### D-003: api/AsyncConfig.productBatchExecutor() @Bean 어노테이션 누락

- 심각도: P2 (오동작)
- 리스크 등급: 표준
- 위치: `backend/api/src/main/java/com/sbshop/agent/api/config/AsyncConfig.java:13`
- 증상: `productBatchExecutor()` 메서드가 `@Bean` 없이 일반 메서드로 선언됨 → Spring 컨테이너에 빈으로 등록되지 않음. 비동기 처리 시 `product-batch-` 스레드가 아닌 기본 executor 또는 core 모듈의 `syncTaskExecutor`가 사용될 수 있음.
- 재현: `ApplicationContext.getBean("productBatchExecutor")` 시 `NoSuchBeanDefinitionException` 발생 예상. 또는 `@Async("productBatchExecutor")`로 주석된 메서드 존재 시 실행 오류.
- 원인(확인): `api/config/AsyncConfig.java` 13번 라인 — `public Executor productBatchExecutor()` 선언에 `@Bean` 없음. core 모듈 `AsyncConfig`(line 14)에는 `@Bean(name = "syncTaskExecutor")`가 정상 선언됨. api 모듈 AsyncConfig는 `@Configuration`만 있고 실제 빈 등록 메서드가 없는 껍데기 클래스가 됨.
- 수정(2026-07-07): `productBatchExecutor()`에 `@Bean(name = "productBatchExecutor")` 부여. 회귀 테스트: `api/src/test/.../config/ProductBatchExecutorBeanTest` (격리 컨텍스트에서 빈 등록 검증, Red=NoSuchBeanDefinitionException → Green).
- 파생 발견(범위 외, 후보): 원장 재현란은 `@Async("productBatchExecutor")` 참조 메서드를 가정했으나 **전수 검색 결과 그런 참조는 없음**. 의도된 소비자로 보이는 `core/.../product/BatchPriceStockService`의 배치 메서드 3곳(line 38·81·123)은 **한정자 없는 `@Async`**를 사용 → 빈을 등록해도 이름으로 자동 연결되지 않음. 빈이 2개 이상(syncTaskExecutor 등)이면 bare `@Async`는 전용 풀을 못 고르고 폴백. 즉 "product-batch- 스레드로 실제 처리" 목표는 `@Async("productBatchExecutor")` 한정자 지정이 추가로 필요 → 별도 결함 후보 [[D-011]]. D-003 자체(빈 등록)는 완료.
- 검증(2026-07-07, qa-verifier): `ProductBatchExecutorBeanTest` Green(tests=1, failures=0), `:core:test` 통과, D-009 수정과 동일 파일 공존 무충돌, 참조처 정합. 전용 풀 미배선은 [[D-011]]로 정당 분리(범위 밖). 판정서: `_workspace/verify/D-003_verdict.md`.
- 상태: 검증통과
- 이력: 2026-07-07 발견 → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과

---

### D-004: ImageDownloadService — ImageDownloadClient 인터페이스 우회 중복 구현

- 심각도: P2 (오동작/아키텍처 리스크)
- 리스크 등급: 표준
- 위치:
  - `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cloudflare/ImageDownloadService.java:18`
  - `backend/api/src/main/java/com/sbshop/agent/api/controller/ProductController.java:54`
- 증상: `ProductController`가 `ImageDownloadClient` 인터페이스를 거치지 않고 `ImageDownloadService`를 직접 주입받아 사용. `ImageDownloadService`는 `ImageDownloadClient`를 구현하지 않으므로 DI 계층 설계 위반. 동일한 `downloadAndConvert()` 로직이 `ImageDownloader`(RestTemplate)와 `ImageDownloadService`(OkHttpClient) 두 곳에 중복 구현됨.
- 재현: `ProductController.java:54` — `private final ImageDownloadService imageDownloadService;` (infrastructure 클래스 직접 참조). `ImageDownloadClient` 인터페이스(`core/domain/product/client/ImageDownloadClient.java`)를 구현하지 않은 `ImageDownloadService`가 API 계층에서 사용됨.
- 원인(추정): Cloudflare 이미지 차단 우회(User-Agent 헤더) 필요로 OkHttpClient 버전을 별도 작성했으나, 기존 `ImageDownloader`를 교체하지 않고 추가 생성. 레거시 병합 시 정리 누락.
- 수정(2026-07-07, 사이클 2 구조 배치): 두 소비처(ProductController·ProductCreateUseCase) 모두 `downloadAndConvert`만 호출·인터페이스 `download`/`downloadAll` 외부 소비처 0 확인. OkHttp+UA 구현 `ImageDownloadService`가 `ImageDownloadClient`를 구현하도록 변경(`downloadAndConvert` 본문 무변경 — @Override만; 계약 이행용 `download`/`downloadAll` 추가), `ProductController`를 인터페이스 주입으로 전환(계층 위반 해소), 잉여 RestTemplate 구현 `ImageDownloader` 삭제(사용처 0). 구조 변경이므로 특성화 테스트 `infrastructure/src/test/.../cloudflare/ImageDownloadServiceCharacterizationTest`(JDK HttpServer, 신규 의존성 0)로 출력 계약·User-Agent 전송·all-fail 예외를 통합 전후 그린으로 고정. 유일한 관측 변화: ProductCreateUseCase 다운로드 경로가 RestTemplate→OkHttp+UA(출력 계약 동일, User-Agent 추가만 — 리더 승인 방향). `:infrastructure:test :core:test :api:test` 전건 그린, worker 컴파일 무결. 수정 요지: `_workspace/fixes/D-004_fix.md`.
- 검증(2026-07-07, qa-verifier): **PASS**. 특성화 테스트 `ImageDownloadServiceCharacterizationTest` 실행 XML `tests=3 skipped=0 failures=0 errors=0`(User-Agent `Mozilla/5.0`+`Accept: image/*`·출력계약·all-fail 예외 고정). 경계면: `implements ImageDownloadClient` 정확히 1벌(ImageDownloadService), `ProductController`가 core 포트 주입(계층 위반 해소), 두 소비처 `downloadAndConvert`만 호출(신규 download/downloadAll 런타임 미도달), `ImageDownloader` 코드 참조 0. `downloadAndConvert` 본문 바이트 무변경 → 헤더 유실 회귀 없음. 전체 `./gradlew test` BUILD SUCCESSFUL. spotless 실측: D-004 3파일도 미준수이나 주변 하우스 스타일 정합(전역 [[D-014]], D-004 고유 결함 아님). 판정서: `_workspace/verify/D-004_verdict.md`.
- 상태: 검증통과
- 이력: 2026-07-07 발견 → 2026-07-07 수정중(사이클 2) → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과

---

### D-005: after-migrate.sql — Flyway 외부 스키마 변경이 V4 마이그레이션과 중복

- 심각도: P2 (오동작/데이터 리스크)
- 리스크 등급: 표준
- 위치: `backend/api/src/main/resources/after-migrate.sql`
- 증상: 앱 시작 시마다 `ALTER TABLE TYPE` DDL 재실행. `V4__product_vo_and_new_tables.sql`과 동일한 DDL(source_images/hosted_images jsonb 변환, detail_html text, delivery_fee/stock_status/restock_date 컬럼, sb_currency/sb_supplier/sb_process_status 테이블)이 after-migrate.sql에도 존재. `sb_order.market_specific_data VARCHAR(50000)` 타입 변경은 after-migrate.sql에만 존재하고 Flyway 마이그레이션에는 없음.
- 재현: 앱 기동 로그에서 `after-migrate.sql` 실행 확인. 반복 실행 시 `ALTER TABLE TYPE` 재실행으로 PostgreSQL에 불필요한 잠금 발생 가능.
- 원인(확인): Flyway 마이그레이션(V4)이 신규 추가된 시점에 after-migrate.sql을 정리하지 않음. `sb_order.market_specific_data` 컬럼은 Flyway 히스토리에 없어 마이그레이션 이력 추적 불가 상태.
- 수정(2026-07-07, 사이클 4 fixer-c4, D-013 통합·사용자 승인 / V5 설계 리더 확정): after-migrate.sql 항목별 대조 결과 V4와 중복이 아닌 유일 항목은 **`sb_order.market_specific_data`** 뿐(나머지 9개 문 — jsonb 변환×2·detail_html·신규 컬럼×3·신규 테이블×3 — 전부 V4가 커버, fix.md 대조표). 이를 신규 **`V5__absorb_after_migrate_market_specific_data.sql`**(infrastructure/db/migration)로 이전. **타입은 TEXT로 정규화**(엔티티 `Order.java`의 `@Column(columnDefinition = "TEXT")`와 정합; 구 after-migrate의 varchar(50000)은 무손실로 TEXT 수렴): `ADD COLUMN IF NOT EXISTS market_specific_data TEXT` + `DO $$ BEGIN ALTER COLUMN ... TYPE TEXT; END $$`(멱등 가드). `after-migrate.sql` 삭제(`git rm`), api `application.yml`의 `sql.init`(mode/schema-locations) 블록 제거. **실측 발견**: 구 after-migrate는 `market_specific_data`를 ADD 없이 `DO $$ ... EXCEPTION WHEN OTHERS THEN NULL`로 ALTER만 시도 → 컬럼이 없으면 조용히 no-op이라 실제로 컬럼을 보장하지 못했다(V5가 이 결함을 교정). 또한 after-migrate 전체를 V4 뒤에 재적용하면 jsonb 재변환부가 `invalid input syntax for type json`으로 **실패**(이미 jsonb인 컬럼을 `''`와 비교) → 구 after-migrate는 V4 이후 정상 실행 불가였음(제거의 추가 근거). 멱등 검증: `MigrationIdempotencyTest`(실 PostgreSQL/testcontainers, 3케이스) — 컬럼 부재(운영 V1~V4 재현·빈 DB)엔 TEXT 신규 생성, varchar(50000) 기존 존재 시 TEXT 수렴, 재적용 멱등. 수정 요지: `_workspace/fixes/D-005_fix.md`.
- 검증(2026-07-07, verifier-c4): **검증통과(PASS)** — 안정 상태 기준. 델타 완전성 재대조: after-migrate 8효과 중 7개는 V4 동일 존재, 유일 델타 market_specific_data만 V5 흡수(중복 0). 멱등 3경로(부재/빈DB/varchar 선존재) 모두 최종 `data_type='text'` 수렴 실증(`MigrationIdempotencyTest` 3/0/0). 타입 TEXT ↔ 엔티티 `Order.java:83 columnDefinition="TEXT"` 정합. 전체 백엔드 `test spotlessCheck --rerun-tasks` BUILD SUCCESSFUL(tests=31/0/0). **검증 무결성 주의**: fixer-c4가 "수정완료" 통보 후에도 편집 지속(V5→TEXT 전환 중), 초기 관측 flaky 실패는 mid-edit 아티팩트로 폐기·동결 상태 재검증. **미검증**: 운영 DB 실제 market_specific_data 상태 접근 불가(배포 시 실측 권고); 진짜 빈 DB V1~V5 전체 체인은 D-015로 미검증. 판정서: `_workspace/verify/D-005_verdict.md`.
- 상태: 검증통과 (사이클 4 verifier-c4 — V5 흡수·3경로 TEXT 수렴 멱등, 동결 상태 실증) — 이후 사이클 7에서 Flyway 자체 제거됨(사용자 결정: 스키마 수동 관리). V5 마이그레이션·`MigrationIdempotencyTest`·`pre_flyway_baseline.sql` 삭제. `market_specific_data` 컬럼은 운영 DB에 이미 존재(`Order.java` TEXT 매핑 유지).
- 이력: 2026-07-07 발견 → 2026-07-07 수정중(사이클 4, D-013 통합) → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과(verifier-c4) → 2026-07-07 Flyway 전면 제거로 V5 삭제(사이클 7 fixer-c7)

---

### D-006: SmartStoreApiExplorerTest 하드코딩 인증정보 + 실제 외부 API 호출

- 심각도: P2 (오동작 + 보안)
- 리스크 등급: 표준
- 위치: `backend/api/src/test/java/com/sbshop/agent/api/SmartStoreApiExplorerTest.java:18-19`
- 증상: `clientId = "1l5fRuKFzyNJGQF3AP27AE"`, `clientSecret = "$2a$04$24Vxb0j6X3HK.ZVUA43Wk."` 하드코딩. 실제 네이버 커머스 API 엔드포인트 호출 → HTTP 403 Forbidden 으로 테스트 실패. CI 환경에서 항상 실패하며, 인증정보가 git 히스토리에 노출됨.
- 재현: `cd backend && ./gradlew :api:test --tests "com.sbshop.agent.api.SmartStoreApiExplorerTest"` → `SmartStoreApiExplorerTest > exploreSmartStoreApi() FAILED: org.springframework.web.client.HttpClientErrorException$Forbidden at SmartStoreApiExplorerTest.java:46`
- 원인(확인): 탐색용 테스트가 그대로 잔류. `@SpringBootTest` 없이 실제 외부 API를 직접 호출하는 구조. 인증정보 만료 또는 권한 부족으로 403.
- 수정(2026-07-07): 파일 삭제(`git rm backend/api/src/test/.../SmartStoreApiExplorerTest.java`). 회귀 테스트가 아니라 결함 그 자체이므로 삭제가 정답(@Disabled 금지 규칙의 예외 — 리더 지시). 하드코딩 인증정보(`1l5fRuKFzyNJGQF3AP27AE`, `$2a$04$...`)가 다른 소스·설정에 재사용되지 않음을 전수 검색으로 확인(0건). 삭제 후 `:api:compileTestJava` 정상, `SmartStoreApiExplorer` 참조 0건.
- 파생 발견(범위 외, 후보): 같은 성격의 `CoupangApiExplorationTest`·`CoupangDebugTest`도 `@SpringBootTest` + 실 Coupang API 호출(`coupangOrderApiClient.fetchOrders`) + assertion 없는 `System.out.println` 탐색 코드. 하드코딩 인증정보는 없고 DB(`MarketCredentialRepository`)에서 읽으나, 실 외부 API 호출·라이브 datasource 의존으로 CI에서 항상 실패 → 삭제 권장. 신규 후보 [[D-012]].
- 검증(2026-07-07, qa-verifier): 하드코딩 인증정보 문자열 작업트리 0건, `SmartStoreApiExplorer` 참조 0건, 파일 삭제(staged) 확인, `:api:compileTestJava` BUILD SUCCESSFUL. 동종 Coupang 탐색 테스트 실 API 호출 잔존 → [[D-012]]. **보안 경고: 노출 인증정보가 git 히스토리에 잔존 → 네이버 커머스 콘솔에서 회전(재발급) 필요(수동 조치, 코드 범위 밖)**. 판정서: `_workspace/verify/D-006_verdict.md`.
- 상태: 검증통과
- 이력: 2026-07-07 발견 → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과

---

### D-007: 프론트엔드 lint 59 errors

- 심각도: P3 (품질/부채)
- 리스크 등급: 경량
- 위치: `frontend/src/` 다수 파일
  - `frontend/src/pages/OrderGrid.tsx` — `@typescript-eslint/no-explicit-any` 다수, `@typescript-eslint/no-unused-vars` (line 1164, 1182), `react-hooks/incompatible-library` (line 1144)
  - `frontend/src/pages/Settings.tsx:20` — `react-hooks/set-state-in-effect`
  - `frontend/src/pages/ProductPage.tsx:14` — `@typescript-eslint/no-unused-vars`
  - `frontend/src/pages/ProductRegisterPage.tsx:2` — `@typescript-eslint/no-unused-vars`
- 증상: `npm run lint` 결과 59 errors, 2 warnings. `npm run build`는 통과.
- 재현: `cd frontend && npm run lint` → `✖ 61 problems (59 errors, 2 warnings)`
- 원인(확인): 타입 정의 없이 `any` 사용, 선언 후 미사용 변수, Effect 내 직접 setState 호출.
- 게이트 정정(2026-07-07, fixer-c5): 프론트 `npx tsc --noEmit`(루트 tsconfig.json `files:[]` references-only)은 **아무 소스도 검사 안 하는 헛-그린**, `npm run build`(vite/esbuild)도 타입체크 안 함 → 실제 타입 게이트는 `tsc --noEmit -p tsconfig.app.json`(리더가 하네스 게이트 커맨드 교정). 착수 전부터 tsc-app 8 errors 존재.
- 수정(2026-07-07, 사이클 5 fixer-c5): **행위 불변 우선(타입 계층만 변경 → 런타임 JS 소거 불변)**. 기준선(D-008 삭제 반영 후) 58 problems(56 errors, 2 warnings) → 결과 **6 problems(4 errors, 2 warnings)**, 에러 56→4(−52). 처리 —
  - 미사용 전량 제거: Table.tsx className 6건(전달 호출처 0 확인 후 구조분해 제거), ProductPage `loading`(`[, setLoading]`), ProductRegisterPage `InputNumber` import, OrderGrid 미사용 함수 `MarketFilter` 삭제(내부 any 동반 해소)·`catch (error)`×6/`catch (e)`×3 → bare `catch`.
  - no-explicit-any: orderApi.ts 10건(marketRegistration→unknown, 미소비 반환 Promise<any>→unknown, updateData→Record<string,unknown>) + OrderGrid 다수(불필요 `as any` 캐스트 제거 `row.order?.id`·`row.original.product`, 색상맵 3건·tanstack meta 2건·getCommonLabel `c`·mutation updates·handleUpdate value를 사용형태/DTO 기반 타입으로, axios catch 2건 `unknown`+shape 캐스트, ProcessingModal/selectedLineItem/open*Modal lineItem→`OrderGridDto`). **DTO 정합 보강**: `OrderLineItemDto.sourcingData`에 `sourcingVendor?: string` 추가 — 근거 백엔드 `core/domain/order/vo/SourcingData.java:30` 실재 필드(추측 아님).
  - tsc-app: 착수 8 errors 중 미사용 3건(MarketFilter·loading·InputNumber) 해소, **신규 0건** → 5 errors 잔존(전부 착수 전 기존: ORDER_COLUMNS/PRODUCT_COLUMNS implicit any[] ×4 + [[D-017]] updateOrderLineItem). `npm run build` exit 0. 수정 요지: `_workspace/fixes/D-007_fix.md`.
- 잔여 lint 4 errors + 2 warnings (하위항목 — 로직 재구성 필요분, 리더 지시대로 미강행):
  - `OrderGrid.tsx:18`·`:690` no-explicit-any(`onSubmit`/`handleModalSubmit` data): 모달 제출 페이로드가 소싱/배송 이질 union인데 판별자가 페이로드 밖 `modalMode`라 discriminated-union 내로잉 불성립 → 정밀 타입화는 모달 상태 리팩토링 필요(제출 경로 행위 영향). **권장**: modalMode를 payload에 태그로 편입해 discriminated union 구성 후 두 API 분기 내로잉.
  - `OrderGrid.tsx:38`·`Settings.tsx:20` react-hooks/set-state-in-effect: effect 내 동기 setState(폼 상태 seeding). **권장**: `key`-리마운트+`useState` 이니셜라이저 또는 렌더 파생/ref 가드(폼 리셋 타이밍 행위 민감 → 수동 검증 동반 필요).
  - `OrderGrid.tsx:1096` exhaustive-deps(warning), `:1098` incompatible-library(warning): 각각 memo 의존성/React Compiler-tanstack 호환 이슈 — 행위 민감·라이브러리 이슈로 보류.
- 검증(2026-07-07, verifier-c5): 3게이트 실측 — lint **6 problems(4 errors, 2 warnings)**(에러 56→4), 실 게이트 `tsc --noEmit -p tsconfig.app.json` **5 errors**(전부 착수 기준선 8건의 부분집합, 미사용 3건 해소, **신규 0=무회귀**; tsc 라인 시프트 535→489 등은 상위 삭제 결과·동일 에러), `npm run build` EXIT 0 ✓ built. 백엔드 diff 0건. **행위 불변 혼입 검사(핵심)**: 전 변경 타입 계층 확인 — `as any` 캐스트 제거·`unknown` 대체(stricter가 tsc-app 신규 0=소비처 무파손 실증)·bare catch 9건(error 미참조)·axios catch 캐스트(옵셔널 체이닝 보존, 런타임 동일)·MarketFilter 삭제(참조 0)·Table className(전달 호출처 0, OrderGrid 사용 블록 전수 부재). 경계면 대조: `sourcingVendor?: string` 추가는 백엔드 `SourcingData.java:31 private String sourcingVendor` 실재 + `OrderController`가 도메인 `OrderLineItem` 직접 직렬화(`ResponseEntity<OrderLineItem>`)로 서빙 확정 — 추측 아님, 승인. 잔여 6건 실 lint 출력과 fix.md 기록 정확 일치(침묵 생략 없음). 파생 관측: 배송 모달 `carrier` vs 백엔드 `shippingCarrier` 필드명 불일치 잠복 이슈(범위 밖, 트리아지 권함). 판정서: `_workspace/verify/D-007_verdict.md`.
- 상태: 검증통과 (잔여 lint 6건은 로직 재구성 필요분으로 보류 기록 — 커밋 게이트 판단은 리더)
- 이력: 2026-07-07 발견 → 2026-07-07 수정완료(검증대기, 사이클 5 fixer-c5) → 2026-07-07 검증통과(verifier-c5)

---

### D-008: orderApi.ts 미호출 함수 3건 — 대응 백엔드 엔드포인트 없음

- 심각도: P3 (품질/부채)
- 리스크 등급: 경량
- 위치: `frontend/src/api/orderApi.ts:207` (`purchaseItem`), `frontend/src/api/orderApi.ts:218` (`shipItem`), `frontend/src/api/orderApi.ts:227` (`updateTracking`)
- 증상: 세 함수가 프론트엔드 내 어디서도 import/호출되지 않음. 백엔드 `OrderController`에 대응 엔드포인트(`/purchase`, `/ship` on line-items, `/tracking`)가 존재하지 않음.
- 재현: `grep -rn "purchaseItem\|shipItem\|updateTracking" frontend/src --include="*.tsx" --include="*.ts"` — orderApi.ts 정의 외 0건. 백엔드: `grep -rn "line-items.*purchase\|line-items.*ship\|line-items.*tracking" backend/api/src` — 0건.
- 원인(추정): 미구현 기능 계획을 위한 placeholder로 추가됐으나 기능 구현이 이루어지지 않은 채 잔류. 의도적 보류인지 미완성인지 불분명 — 미확인.
- 수정(2026-07-07, 사이클 5 fixer-c5): 재현 재확인(프론트 참조 0건 + 백엔드 `OrderController` 전체 매핑 대조로 `/line-items/{id}/purchase`·`/line-items/{id}/ship`·`/line-items/{id}/tracking` 부재 확정 — 컨트롤러 `@PostMapping("/ship")`는 배치 `/api/v1/orders/ship`으로 프론트 `shipOrders` 소비, 라인아이템 ship 아님) 후 `orderApi.ts`의 세 함수(주석 포함) 삭제. 미완성 기능 부활은 범위 밖. 성공 기준(프론트 테스트 러너 부재): `npx tsc --noEmit` EXIT 0 + `npm run build` 성공 + 삭제 후 재-grep 0건 — 전부 그린. 다른 export/import 무변경(`updateShippingInfo`·`shipOrders`는 실 엔드포인트·호출처 있어 존치). 수정 요지: `_workspace/fixes/D-008_fix.md`.
- 검증(2026-07-07, verifier-c5): `git diff HEAD`로 세 함수 29줄 정확 삭제 확인(주변 로직 무변경, 행위 불변 혼입 없음). 타입체크 게이트 정정 — 루트 `tsc --noEmit`은 `files: []` references-only라 헛-그린이므로 실 게이트 `tsc --noEmit -p tsconfig.app.json` 실측: 8에러 검출되나 전부 OrderGrid/ProductPage/ProductRegisterPage 소재, orderApi.ts 0건(D-008 삭제가 app-tsc 에러 추가 안 함). `npm run build` ✓ built. 삭제 후 재-grep 0건. 경계면 대조: 삭제 3함수의 호출 URL(`/line-items/{id}/purchase` POST, `/line-items/{id}/ship` POST, `/line-items/{id}/tracking` PUT) 모두 `OrderController` 전체 매핑에 부재 확정(`/ship`은 배치 엔드포인트, `/line-items/{id}/shipping`은 PATCH — 불일치). 존치 `updateShippingInfo`·`shipOrders`는 실 호출처 있어 오삭제 없음. 백엔드 diff 0건(무접촉 준수). 판정서: `_workspace/verify/D-008_verdict.md`.
- 상태: 검증통과
- 이력: 2026-07-07 발견 → 2026-07-07 수정완료(검증대기, 사이클 5 fixer-c5) → 2026-07-07 검증통과(verifier-c5)

---

### D-009: AsyncConfig 중복 빈 이름 충돌 → API 모듈 시작 불가 (D-001에 가려져 있던 2차 P0)

- 심각도: P0 (앱 컨텍스트 시작 불가)
- 리스크 등급: 중대
- 위치:
  - `backend/core/src/main/java/com/sbshop/agent/core/config/AsyncConfig.java`
  - `backend/api/src/main/java/com/sbshop/agent/api/config/AsyncConfig.java`
- 증상: `@SpringBootTest` 컨텍스트 로딩 시 `ConflictingBeanDefinitionException: Annotation-specified bean name 'asyncConfig' for bean class [com.sbshop.agent.core.config.AsyncConfig] conflicts with existing, non-compatible bean definition of same name and class [com.sbshop.agent.api.config.AsyncConfig]`. D-001(ElevenstRestClient) 충돌을 해소하자 스캔이 더 진행되며 드러난 **동일 성격의 2차 빈 이름 충돌**. D-001 수정만으로는 API 컨텍스트가 여전히 시작하지 못함.
- 재현: `cd backend && ./gradlew :api:test --tests "com.sbshop.agent.api.CoupangApiExplorationTest"` → 위 예외로 context load failure. (D-001 수정 이후 상태)
- 원인(확인): `core.config.AsyncConfig`와 `api.config.AsyncConfig`가 둘 다 `@Configuration`이고 클래스 단순명이 같아 기본 빈 이름 `asyncConfig`로 동일 등록됨. D-003(api AsyncConfig의 `productBatchExecutor()` `@Bean` 누락)과 같은 파일을 다루나 **별개의 결함**: D-003은 빈 등록 누락, D-009는 빈 이름 충돌.
- 제안 수정: D-001과 동일 패턴 — 최소 변경으로 한쪽에 명시적 빈 이름 부여(예: `@Configuration("apiAsyncConfig")`). 두 AsyncConfig의 실질적 통합(중복 정의 제거)은 구조 변경으로 별도 취급.
- 수정(2026-07-07, 리더 배치 확장 승인): api `AsyncConfig`에 `@Configuration("apiAsyncConfig")` 부여(core는 기본 `asyncConfig` 유지). D-003과 커밋 단위 분리. 회귀 테스트: `api/src/test/.../config/AsyncConfigBeanNameConflictTest` (두 config 패키지 동시 스캔, refresh 없이 빈 정의 등록 확인 — JPA 초기화 회피). Red=ConflictingBeanDefinitionException → Green. 두 P0 빈 충돌(D-001·D-009) 해소 후 `CoupangApiExplorationTest`의 실패 원인이 `ConflictingBeanDefinitionException` → `entityManagerFactory`/`flyway` `BeanCreationException`(테스트 DB 미설정, 별개 인프라 이슈)으로 바뀜을 확인 = 빈 이름 충돌 완전 해소.
- 검증(2026-07-07, qa-verifier): `AsyncConfigBeanNameConflictTest` Green(tests=1, failures=0), `:core:test` 통과. 전체 `:api:test`에서 `ConflictingBeanDefinitionException` 완전 소멸(0건) — 두 P0 빈 충돌 해소 실측. 경계면 정합: api→`apiAsyncConfig`/core→`asyncConfig`, core 무변경, 문자열 참조 0, 주문 동기화 `@Async("syncTaskExecutor")` 정상. 판정서: `_workspace/verify/D-009_verdict.md`.
- 잔여 관측(신규 후보, 리더 트리아지 필요): 빈 충돌 소멸 후 `CoupangApiExplorationTest`·`CoupangDebugTest`의 `@SpringBootTest`가 `BeanCreationException: Circular depends-on relationship between 'flyway' and 'entityManagerFactory'`(테스트 datasource/Flyway 미구성)로 여전히 로드 실패 → 전체 `:api:test` 그린의 잔여 블로커. D-009 회귀 아님. [[D-005]] 연관 가능성.
- 상태: 검증통과 (범위: asyncConfig 충돌 해소. 전체 `:api:test` 그린은 위 flyway 인프라 이슈 해소 전제 — 커밋 게이트 조건)
- 이력: 2026-07-07 발견 (tdd-fixer, D-001 수정 중 파생 발견) → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과(범위 한정)

---

### D-010: ElevenstRestClient 클래스 단순명 중복 (구조 개선 후보)

- 심각도: P3 (품질/부채)
- 리스크 등급: 경량
- 위치:
  - `backend/infrastructure/.../client/elevenst/ElevenstRestClient.java`
  - `backend/infrastructure/.../client/elevenst/client/ElevenstRestClient.java`
- 증상: 서로 다른 두 클래스가 동일 단순명 `ElevenstRestClient`를 사용 → 빈 이름 충돌(D-001)의 근본 원인이자 코드 가독성 저하. D-001은 명시적 빈 이름으로 충돌만 해소했고, 클래스명 자체의 혼동은 남아있음.
- 제안 수정: 신버전을 용도에 맞게 리네임(예: `ElevenstApiHttpClient`)하고 import 갱신. 구조 변경(Tidy First)이므로 행위 수정과 분리해 별도 커밋.
- 수정(2026-07-07, 사이클 2 구조 배치): 양쪽 다 역할 드러나는 이름으로 리네임 — `elevenst.ElevenstRestClient`→`ElevenstOrderRestClient`(주문 API), `elevenst.client.ElevenstRestClient`→`ElevenstMarketRestClient`(상품 API). 리더 지시가 두 빈 이름 정합을 명시했으므로 한쪽만이 아닌 양쪽 리네임으로 클래스명↔빈이름 일치. 리네임으로 기본 빈 이름이 D-001의 명시 한정자(`elevenstOrderRestClient`/`elevenstMarketRestClient`)와 문자열 동일해져 명시 한정자 제거(평문 `@Component`) — 빈 이름 무변경. 소비처(ElevenstOrderApiClient·ElevenstMarketClient) 주입 타입·import 갱신, D-001 회귀 테스트 FQN 갱신. 구 단순명 코드 참조 grep 0(잔존은 회귀 테스트 Javadoc 역사 설명 1건). 전체 `./gradlew test` BUILD SUCCESSFUL. 수정 요지: `_workspace/fixes/D-010_fix.md`.
- 검증(2026-07-07, qa-verifier): **부분** — 기능·동작불변·경계면 전부 PASS, 단 검증자 유발 포맷 오염 1건 정리 후 커밋 가능. 전체 `./gradlew test` BUILD SUCCESSFUL(회귀 테스트 D-010 반영본 포함). 빈 이름 정합: 두 신규 클래스 평문 `@Component`→기본 빈 이름 `elevenstOrderRestClient`/`elevenstMarketRestClient`로 D-001 명시 한정자와 문자열 동일, `@Qualifier`/문자열 빈 참조 0. 구 단순명 코드 참조 0(Javadoc 역사 문자열 1건만). old 삭제본 vs 신규 diff: 메서드 본문 의미 동일(차이는 Javadoc/포맷) → 행위 불변. **정리 필요(검증자 귀책, fixer 결함 아님)**: `ElevenstMarketClient.java`에 검증자의 모듈 전역 `spotlessApply` 오염(BigDecimal 미사용 import 제거·if 줄분리 등)이 정당 rename 2줄과 혼입 → `git checkout HEAD --` 후 import/필드 타입 2줄만 재적용해 `ElevenstOrderApiClient`처럼 clean rename-only로 정리 필요(tdd-fixer 요청 전송). 판정서: `_workspace/verify/D-010_verdict.md`.
- 정리 완료(2026-07-07): tdd-fixer가 `ElevenstMarketClient.java`를 HEAD 복원 후 rename 2줄만 재적용 → 검증자 유발 오염 제거 확인. 현재 diff는 import+필드 타입(비공백 4줄)로 `ElevenstOrderApiClient`와 동일한 clean rename-only. 재검증 전체 `./gradlew test` **BUILD SUCCESSFUL**. → **PASS 승격**.
- 상태: 검증통과
- 이력: 2026-07-07 발견 (tdd-fixer, D-001 수정 중 기록) → 2026-07-07 수정중(사이클 2) → 2026-07-07 수정완료(검증대기) → 2026-07-07 부분(오염 정리 대기) → 2026-07-07 검증통과(오염 정리 후)

---

### D-011: BatchPriceStockService가 productBatchExecutor를 한정자로 참조하지 않음

- 심각도: P3 (품질/부채 — 전용 스레드풀 미사용)
- 리스크 등급: 경량
- 위치: `backend/core/src/main/java/com/sbshop/agent/core/application/product/BatchPriceStockService.java:38, 81, 123`
- 증상: 세 배치 메서드가 한정자 없는 `@Async`를 사용. D-003으로 `productBatchExecutor` 빈을 등록해도 이름으로 자동 연결되지 않아 `product-batch-` 전용 풀이 실제로 쓰이지 않음. Executor 빈이 복수면 bare `@Async`는 전용 풀을 선택하지 못하고 폴백(경고 후 SimpleAsyncTaskExecutor 등).
- 재현: `grep -rn "@Async" backend/.../BatchPriceStockService.java` → 한정자 없음. `@Async("productBatchExecutor")` 전수 검색 0건.
- 제안 수정: 세 지점을 `@Async("productBatchExecutor")`로 지정. 행위 변경이므로 실제 풀 사용을 검증하는 테스트와 함께 별도 처리.
- 수정(2026-07-07, 사이클 3 행위 배치 fixer-c3): 세 배치 메서드(`crawlAndUpdatePriceStock`·`manualUpdatePriceStock`·`manualUpdateAllFields`) `@Async`→`@Async("productBatchExecutor")`(본문·시그니처 무변경). Red 테스트: `core/src/test/.../product/BatchPriceStockAsyncPoolTest`(신규 의존성 0) — ①행위: 운영과 동일한 executor 복수 공존(`productBatchExecutor`+`syncTaskExecutor`) 재현 컨텍스트에서 배치가 `product-batch-` 스레드에서 실행됨 검증(Red=폴백 풀 접두 불일치→Green), ②리플렉션: 세 메서드 `@Async.value=="productBatchExecutor"` 검증(Red=""→Green). **한계(모듈 경계)**: 실 `productBatchExecutor` 빈은 api 모듈에 있어 core 단독 컨텍스트 로드 불가 → 테스트는 실빈 대신 동일 조건 재현 컨텍스트로 한정자 라우팅을 검증(핵심 결함 정확 재현, api 실빈 배선 자체는 D-013 인프라 확보 후 별도). `:core:test` 전체 BUILD SUCCESSFUL, 변경 2파일 spotless 위반 0. 수정 요지: `_workspace/fixes/D-011_fix.md`.
- 검증(2026-07-07, verifier-c3): **반려(FAIL)**. 한정자 추가·격리 테스트(`BatchPriceStockAsyncPoolTest` tests=2, failures=0)·`:core:test` BUILD SUCCESSFUL·worker 컴파일 정상은 확인되나, **경계면 불일치(설정↔빈, 모듈 경계)로 런타임 회귀 도입**: `productBatchExecutor` 빈은 api 모듈에만 정의(`api/config/AsyncConfig.java:14`). 그러나 이 @Async 메서드 `crawlAndUpdatePriceStock`는 worker `BatchScheduler`(매일 05시 cron, worker `@EnableScheduling`+core `@EnableAsync` 활성)가 호출하는데 **worker는 api 미의존(core+infra만)** → worker 컨텍스트에 빈 부재 → 호출 시점에 `findQualifiedExecutor("productBatchExecutor")`가 `NoSuchBeanDefinitionException` throw(스케줄러 스레드 동기). 수정 전 bare @Async는 유일 TaskExecutor(syncTaskExecutor)로 폴백해 정상 실행 → 이 변경이 worker iHerb 정기 배치를 "동작→런타임 예외"로 회귀. worker 테스트 0건으로 자동 커버리지 없음. fixer 격리 테스트는 빈을 테스트 내부 정의하므로 원리적으로 이 공백 미검출. **수정 지시**: `productBatchExecutor` @Bean을 core.AsyncConfig로 이전하고 api에서 삭제(양쪽 동시 정의 시 api 컨텍스트가 core+api 둘 다 스캔→빈 이름 중복 `ConflictingBeanDefinitionException` 재발, D-001/D-009 동종). 판정서: `_workspace/verify/D-011_verdict.md`.
- 재수정(2026-07-07, 리더 방향 확정 fixer-c3): verifier 지시대로 **`productBatchExecutor` @Bean을 api→core `AsyncConfig`로 이전(승격)** + api에서 제거. api `AsyncConfig`는 껍데기 존치(`@Configuration("apiAsyncConfig") @EnableAsync` — D-009 빈 이름·`AsyncConfigBeanNameConflictTest` 보존, 정리 후보 기록). 중복 정의 회피로 `ConflictingBeanDefinitionException` 미발생 확인(`:api:test` BUILD SUCCESSFUL). D-003 회귀 테스트 `api/.../ProductBatchExecutorBeanTest`→`core/.../config/ProductBatchExecutorBeanTest` 이전(빈 등록+전용풀 검증 의도 유지). 행위 테스트 `BatchPriceStockAsyncPoolTest`를 **실제 core `AsyncConfig`만 로드(=worker 컨텍스트 동일 조건)**하도록 재작성 — worker 경로에서 한정자 해소·`product-batch-` 라우팅 검증(core 빈 이름 일시 변경으로 Red 실측 후 원복해 가드 유효성 입증). `:core:test :api:test :worker:compile*` BUILD SUCCESSFUL(BatchPriceStockAsyncPoolTest 2/0F, core ProductBatchExecutorBeanTest 1/0F, D-009 test 1/0F 무변경), 변경 파일 spotless 위반 0. 수정 요지 갱신: `_workspace/fixes/D-011_fix.md`.
- 범위 외 후보(원장 기록): api `AsyncConfig` 빈-없는 껍데기(`apiAsyncConfig`) — D-009 보존 위해 존치, 향후 `@EnableAsync` 이설 후 클래스 제거 + D-009 회귀 테스트 동반 갱신 검토.
- 재검증(2026-07-07, verifier-c3): **검증통과(PASS)**. 반려 지시(빈 core 이전) 정확 반영으로 1차 FAIL 근본원인 해소 실측. 경계면 재검증: ①`productBatchExecutor` 정의처 core 단 하나(전수 grep, 중복 0) → worker 컨텍스트(core 스캔)에서 한정자 해소·NoSuchBeanDefinitionException 소멸, ②D-009 빈 이름 충돌 재발 없음(core=`asyncConfig`/api=`apiAsyncConfig`, `AsyncConfigBeanNameConflictTest` Green), ③이중 @EnableAsync 무해(Spring imported config 중복제거, 수정 전에도 양쪽 존재). 전체 `./gradlew test --rerun-tasks` **BUILD SUCCESSFUL**(BatchPriceStockAsyncPoolTest 2/0/0, core ProductBatchExecutorBeanTest 1/0/0, D-009·D-001 회귀 테스트 각 1/0/0). 가드 강도: `BatchPriceStockAsyncPoolTest`가 실 core AsyncConfig만 로드(=worker 대표, TestBeans는 executor 미정의)하므로 빈 부재 시 반드시 Red — 결함 본질 실제 포착(설계 검증). 미검증: api 실 @SpringBootTest end-to-end는 D-013 미해소로 별개(범위 밖). 판정서: `_workspace/verify/D-011_verdict.md`.
- 상태: 검증통과 (빈 core 이전으로 api·worker 양 컨텍스트 한정자 해소, D-009/D-001 회귀 없음, 전체 test 그린)
- 이력: 2026-07-07 발견 (tdd-fixer, D-003 수정 중 기록) → 2026-07-07 수정중(사이클 3) → 2026-07-07 수정완료(검증대기, 한정자만) → 2026-07-07 반려(verifier-c3, 경계면 불일치) → 2026-07-07 재수정 수정완료(검증대기, 빈 core 승격) → 2026-07-07 검증통과(verifier-c3, 재검증)

---

### D-012: CoupangApiExplorationTest·CoupangDebugTest — 실 API 호출 탐색용 테스트 잔재

- 심각도: P3 (품질/부채 — CI 상시 실패)
- 리스크 등급: 경량
- 위치:
  - `backend/api/src/test/java/com/sbshop/agent/api/CoupangApiExplorationTest.java`
  - `backend/api/src/test/java/com/sbshop/agent/api/CoupangDebugTest.java`
- 증상: 둘 다 `@SpringBootTest` + 실제 Coupang API 호출(`coupangOrderApiClient.fetchOrders`) + assertion 없는 `System.out.println` 탐색 코드(D-006의 SmartStore 테스트와 동일 성격). 하드코딩 인증정보는 없고 DB(`MarketCredentialRepository`)에서 읽으나, 라이브 datasource·실 외부 API 의존으로 CI에서 항상 실패(현재 테스트 datasource 미설정으로 `entityManagerFactory`/`flyway` BeanCreationException).
- 재현: `cd backend && ./gradlew :api:test --tests "com.sbshop.agent.api.CoupangApiExplorationTest"` → context/bean 생성 실패.
- 제안 수정: 삭제(D-006과 동일 판단). 또는 마켓 API를 MockWebServer로 대체한 진짜 회귀 테스트로 재작성.
- 수정(2026-07-07, 리더 배치 포함 승인): 두 파일 삭제(`git rm`). D-006과 동일 근거·처리(assertion 없는 탐색 잔재 + 실 API/라이브 DB 의존). 하드코딩 인증정보는 없음(DB 조회) — 재사용 grep 확인 대상 없음, 잔여 테스트 디렉터리 자격증명 문자열 0건. 삭제로 묻히는 인프라 이슈는 신규 [[D-013]]으로 별도 기록.
- 검증(2026-07-07, qa-verifier): 두 파일 삭제·참조 잔존 0건 확인. `:api:test --rerun-tasks` **BUILD SUCCESSFUL**(결과 파일 정확히 3개, 각 tests=1/failures=0/errors=0). 전체 모듈 `./gradlew test` **BUILD SUCCESSFUL**(api 3 + core 7, infra/worker NO-SOURCE) — 백엔드 전체 그린, 회귀 없음. 근본 인프라 이슈는 [[D-013]]으로 분리 추적. 판정서: `_workspace/verify/D-012_verdict.md`.
- 상태: 검증통과 (이로써 사이클 1 배치 5건 수정 후 커밋 게이트 `:api:test`·전체 test 그린 실측 성립)
- 이력: 2026-07-07 발견 → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과

---

### D-013: api 모듈 테스트 datasource/Flyway 미구성 → @SpringBootTest 시 flyway↔entityManagerFactory 순환 depends-on

- 심각도: P2 (테스트 인프라 부재 — api 통합 테스트 작성의 선결 과제)
- 리스크 등급: 표준
- 위치: `backend/api` 테스트 구성 (`src/test/resources` 부재 — 테스트용 `application.yml`/datasource 없음), 연관: `backend/api/src/main/resources/application.yml`, `after-migrate.sql`([[D-005]])
- 증상: api 모듈에 테스트 전용 datasource/프로파일이 없어 `@SpringBootTest`가 운영 `application.yml`(PostgreSQL + Flyway)을 그대로 로드. 컨텍스트 기동 시 `BeanCreationException: Error creating bean 'flyway' ... Circular depends-on relationship between 'flyway' and 'entityManagerFactory'`로 실패. 이 때문에 api에서 전체 컨텍스트를 띄우는 통합 테스트를 현재 작성할 수 없음(D-012의 탐색 테스트들이 실패하던 실제 원인이기도 함).
- 재현: api에 `@SpringBootTest` 테스트를 추가하고 `./gradlew :api:test` 실행 → 위 순환 depends-on 예외. (D-012 삭제 전 `CoupangApiExplorationTest`에서 실측됨.)
- 원인(확인): 테스트 classpath에 H2는 있으나(`testImplementation 'com.h2database:h2'`) 테스트용 `spring.datasource`/`spring.flyway`/프로파일 오버라이드가 없어 운영 설정이 적용됨. Flyway와 JPA(entityManagerFactory) 간 depends-on 배선이 이 환경에서 순환으로 해석됨(운영 프로파일·`after-migrate.sql` 구성과 얽힘 — D-005 연관).
- 제안 수정(범위 밖, 별도 배치): api `src/test/resources/application.yml`에 H2(또는 Testcontainers-Postgres) datasource + `spring.flyway.enabled=false`(또는 테스트 마이그레이션) + 필요한 프로파일을 구성해 컨텍스트 기동을 격리. testcontainers 도입은 신규 의존성이므로 리더 승인 필요(tdd-doctrine). 이 선결 과제 해소 전에는 api 통합/@SpringBootTest 테스트를 추가하지 말 것.
- 분석(2026-07-07, 사이클 3 fixer-c3 — 실측 4실험 후 삭제, 코드 변경 0): **수정 보류·리더 승인 요청.** 근거: 이 문제는 datasource 단일 이슈가 아니라 3중 구조 문제. ①**순환의 단독 트리거는 `spring.jpa.defer-datasource-initialization: true` + Flyway**로 격리 확인(defer=false→순환 소멸·Flyway 실행, defer=true→순환 재현). 순환은 **DB 무관**(빈 정의 시점) → 운영 Postgres 기동에도 동일 발생 개연 = 운영 기동 리스크 확인 필요(P0 승격 후보). ②**H2 불가 실측**: 순환 깬 뒤 H2(PG 호환)로 Flyway 실행 시 **V2에서 이미 실패**(V4 jsonb·after-migrate DO$$에 도달조차 못 함). ③H2+ddl-auto 우회조차 그린 아님 — **`R2Config`의 `s3Client`가 기동 즉시 생성되며 `Access key ID cannot be blank`로 실패**(외부 클라이언트 즉시-생성 빈 다수). 판정: H2 경로는 운영 스키마 검증력 상실(리더 금지) + 그린도 안 됨 → 반려. 정도=testcontainers-Postgres(신규 의존성, 승인 필요; 캐시·Docker 준비됨) + `defer` 제거·after-migrate↔Flyway 단일화(=**D-005 처리와 동일 작업, 중대·사용자 승인**) + 외부빈 목/범위축소. 상세: `_workspace/fixes/D-013_fix.md`.
- 승인 요청 항목: (1) testcontainers-Postgres 신규 의존성 도입, (2) D-005와 병합 처리(순환 해소가 defer 제거+after-migrate 단일화를 요구 → 분리 불가), (3) 운영 api 실제 기동 여부 확인(순환 DB 무관 → 운영도 실패 가능성).
- 리더 판정(2026-07-07): (1)(2)는 **중대 등급 — 사용자 승인 대상**(자율 마커로도 생략 불가). 사이클 3 마감 보고에서 사용자에게 승인 요청 예정. 그때까지 분석완료(승인대기) 유지·코드 변경 금지. (3) **운영 기동 차단 개연 — 리더 config 실측 확인**: api `application.yml`에 `defer-datasource-initialization: true` + `flyway.enabled: true` + `sql.init.mode: always` 그대로 존재, 프로파일 오버라이드 없음. D-001/D-009가 기동을 먼저 막고 있어 이 순환이 관측된 적 없었을 뿐일 개연 높음. → **P0 승격 후보**(심각도 P0-후보 표기; 실기동 재현 전이므로 단정 금지).
- 심각도(갱신): P2(테스트 인프라) + **P0 확정(운영 기동 차단 — testcontainers 실 PostgreSQL로 재현 실측)**.
- Red 실측(2026-07-07, 사이클 4 fixer-c4): testcontainers-PostgreSQL `@SpringBootTest`(`ApiContextLoadSmokeTest`)를 현재 설정 그대로 실행 → `BeanCreationException: ... Circular depends-on relationship between 'flyway' and 'entityManagerFactory'`로 컨텍스트 로드 **실패**. DB 접속 이전 빈 와이어링 단계에서 발생(실 Postgres·더미 외부자격 무관) → **운영 기동 차단 P0 확정**(P0-후보 → P0 승격, 반증 아님).
- 수정(2026-07-07, 사이클 4 fixer-c4, 사용자 승인): ①**testcontainers 도입** — api `build.gradle`에 `org.testcontainers:junit-jupiter` + `:postgresql`(testImplementation, 최소 범위). ②**순환 해소** — api `application.yml`에서 `spring.jpa.defer-datasource-initialization: true` 제거(+ `sql.init` 블록 제거, D-005). Green 실측: `ApiContextLoadSmokeTest`가 실 Postgres에서 컨텍스트 로드 성공 + V1~V5 적용 + `market_specific_data` 존재 확인. ③**Flyway 의존성 공백 교정(신규 발견)** — Flyway 10.x는 Postgres 지원에 `flyway-database-postgresql` 모듈이 필요한데 infrastructure엔 `flyway-core`만 있어 실 Postgres에서 `Unsupported Database: PostgreSQL 16.14`로 실패 → infrastructure `build.gradle`에 `runtimeOnly 'org.flywaydb:flyway-database-postgresql'` 추가(운영·worker Flyway도 이걸 없인 못 돌던 상태였음). ④R2 `S3Client` 등 외부 클라이언트 즉시생성 빈은 테스트 프로퍼티 더미 자격증명으로 통과(운영 코드 무변경, 실 자격·실 호출 없음). 수정 요지: `_workspace/fixes/D-013_fix.md`.
- worker 확인(step 5): worker `application.yml`에는 `defer-datasource-initialization`·`sql.init` 없음 → 동일 순환 패턴 없음. worker는 infrastructure 경유로 `flyway-database-postgresql`를 함께 획득(Flyway 정상화 파급 이득).
- 검증(2026-07-07, verifier-c4): **검증통과(PASS)** — 안정 상태 기준. `ApiContextLoadSmokeTest`(@SpringBootTest, 실 Postgres/testcontainers)가 운영 yml+Flyway V1~V5로 컨텍스트 정상 로드 → 순환 depends-on 해소 실증(Red→Green). 트리거 제거가 운영 config 자체 수정(api yml `sql.init`·`defer` 제거)으로 재현 왜곡 없음. 경계면: worker yml은 원래 clean 패턴 → api 수렴 정합; 테스트 더미 자격증명 `@DynamicPropertySource`(test 스코프) 한정, 운영 빈 무변경. `:api:test --rerun-tasks` 4회 결정적 통과. **미검증**: 실 운영 api 기동 여부/프로파일 오버라이드 접근 불가(배포 시 확인 권고); `flyway-database-postgresql` 부재가 운영/worker Flyway를 막고 있었는지 로그 미확인. 판정서: `_workspace/verify/D-013_verdict.md`.
- 상태: 검증통과 (사이클 4 verifier-c4 — 순환 해소 실증, 설정↔기동 경계면 정합) — 이후 사이클 7에서 Flyway 자체 제거됨(사용자 결정). 순환 유발 축(Flyway↔entityManagerFactory)이 소멸했고, `ApiContextLoadSmokeTest`는 Flyway 의존을 걷어낸 뒤에도 실 Postgres 컨텍스트 기동 검증을 유지하도록 재작업(테스트 한정 `ddl-auto: create-drop`, 엔티티 매핑 스키마).
- 이력: 2026-07-07 발견 (tdd-fixer, D-012 수정 중 기록) → 2026-07-07 분석완료·승인대기(fixer-c3, 사이클 3) → 2026-07-07 리더 판정(사용자 승인 대상 확인 + 운영 기동 P0-후보 config 실측) → 2026-07-07 수정중(사이클 4, 사용자 승인) → 2026-07-07 Red 실측(P0 확정) → 2026-07-07 수정완료(검증대기) → 2026-07-07 검증통과(verifier-c4) → 2026-07-07 Flyway 제거로 스모크 재작업(사이클 7 fixer-c7)

---

### D-014: infrastructure 모듈 spotless 포맷 위반 29건 (기존 부채 — 커밋 게이트 리스크)

- 심각도: P3 (품질/부채)
- 리스크 등급: 경량
- 위치: `backend/infrastructure/src/main/java` 다수 (`Cafe24TokenManager.java` 외 28개 파일)
- 증상: `./gradlew :infrastructure:spotlessCheck` 실행 시 `Cafe24TokenManager` 외 28개 파일에서 포맷 위반으로 BUILD FAILED. `./gradlew :infrastructure:spotlessApply`로 일괄 교정 가능하다고 spotless가 안내.
- 재현: `cd backend && ./gradlew :infrastructure:spotlessCheck` → `The following files had format violations ... Violations also present in 28 other files.`
- 원인(확인): 사이클 2와 무관한 리포지토리 전역 포맷 부채. tdd-fixer가 D-004/D-010 수정 중 표면화(내 변경 파일들은 위반 없음 — 별개). 사이클 1 커밋이 통과한 정황상 현재 커밋 게이트는 `test`만 돌리고 `spotlessCheck`는 미포함으로 추정.
- 제안 수정(범위 밖): `spotlessApply` 일괄 교정은 대량 diff를 만들므로 별도 배치로 분리. 커밋 게이트에 `spotlessCheck` 편입 여부는 리더 정책 결정 사항.
- 범위 확장 관측(2026-07-07, fixer-c3 사이클 3): infrastructure뿐 아니라 **core 모듈에도 동종 전역 위반 ~40개 파일** 존재(`:core:spotlessCheck` 실측). 커밋 게이트 `spotlessCheck` 미포함 전제와 정합. D-014 배치 시 core 포함 권고.
- 상태: 발견 (tdd-fixer, 사이클 2 D-004 수정 중 기록만; core 위반 사이클 3 추가 관측)
- 이력: 2026-07-07 발견 (tdd-fixer, 사이클 2 구조 배치 중 파생 기록) → 2026-07-07 core 전역 위반 관측 추가(fixer-c3)

---

### D-015: Flyway 마이그레이션 V1~V4가 빈 DB에서 자족적이지 않음 (pre-Flyway 베이스라인 가정)

- 심각도: P2 (신규 환경 배포/재구축 불가 리스크)
- 리스크 등급: 표준
- 위치: `backend/infrastructure/src/main/resources/db/migration/V1__init_schema.sql`, `V2__move_shipping_fee_to_logistics_cost.sql`
- 증상: 진짜 빈 DB에 Flyway V1부터 적용하면 **V2에서 실패** — `UPDATE sb_order_line_item SET logistics_cost = shipping_fee`가 참조하는 `shipping_fee` 컬럼을 V1이 생성하지 않는다(`sb_order_line_item`에 없음). V1은 `CREATE TABLE IF NOT EXISTS`라 운영의 pre-Flyway 기존 테이블엔 no-op이었고, `shipping_fee`는 Flyway 도입 이전 스키마(ddl-auto 시대)에만 존재했다. 즉 마이그레이션은 "기존 운영 스키마에 대한 델타"로만 작성되어 from-scratch 재현이 불가능.
- 재현(2026-07-07, fixer-c4 실측): testcontainers 빈 Postgres에 V1→V5 순차 적용 시 `ERROR: column "shipping_fee" does not exist` (Script V2 failed). git 전수: `shipping_fee`는 V1의 어느 버전에도 없었음(0건).
- 원인(확인): Flyway가 기존 운영 DB에 사후 도입되며 baseline 개념 없이 델타 스크립트만 축적됨. 운영 정합상 V1~V4 수정은 checksum 위험(운영에 이미 적용됐다는 전제) → 수정 시 `baseline` 전략·V0 재구성 등 별도 설계 필요.
- 임시 대응(테스트): `ApiContextLoadSmokeTest`/`MigrationIdempotencyTest`는 pre-Flyway 베이스라인(`api/src/test/resources/legacy/pre_flyway_baseline.sql`)을 먼저 세팅해 운영 경로를 재현.
- 상태: 무효화 (2026-07-07 사용자 결정: Flyway 제거·스키마 수동 관리) — 마이그레이션 자족성 자체가 무의미해짐. 사이클 7에서 Flyway 런타임·의존성·V1~V5 마이그레이션·`pre_flyway_baseline.sql` 전면 제거. 운영 DB는 데이터가 채워진 살아있는 원본으로 사용자가 직접 스키마 관리. from-scratch 재구축은 엔티티 매핑(테스트 한정 `ddl-auto: create-drop`으로 실증)으로 대체.
- 이력: 2026-07-07 발견 (fixer-c4) → 2026-07-07 무효화 (사이클 7 fixer-c7, 사용자 Flyway 제거 결정)

---

### D-017: OrderGrid `updateOrderLineItem` 미정의 → 라인아이템 인라인 편집 시 ReferenceError

- 심각도: P2 (기능 불능 — 라인아이템 필드 인라인 편집 경로 깨짐)
- 리스크 등급: 표준
- 위치: `frontend/src/pages/OrderGrid.tsx:535` (`lineItemMutation`의 `mutationFn`이 `updateOrderLineItem(id, updates)` 호출)
- 증상: `updateOrderLineItem`가 호출되지만 `orderApi`에서 import되지도(9행 import 목록에 없음), 파일 내 정의되지도 않음. `tsc -p tsconfig.app.json`에서 `error TS2304: Cannot find name 'updateOrderLineItem'`. `vite build`(esbuild)는 타입체크를 하지 않아 빌드는 통과하나, 런타임에 `updateOrderLineItem`은 undefined → 호출 시 ReferenceError.
- 도달 경로(실측): 세 UI 입력이 `handleUpdate(..., 'lineItem.*', ...)`를 호출 → `lineItemMutation.mutate` → `updateOrderLineItem`:
  - `OrderGrid.tsx:1063` 소싱금액(`lineItem.sourcingAmount`, onBlur)
  - `OrderGrid.tsx:1067` 물류비(`lineItem.logisticsCost`, onBlur)
  - `OrderGrid.tsx:1100` 유니패스 완료 체크박스(`lineItem.isUnipassDone`, onChange)
  - → 사용자가 이 세 필드를 편집(blur/toggle)하면 mutation 실행 시점에 예외.
- 재현: `cd frontend && npx tsc --noEmit -p tsconfig.app.json` → `src/pages/OrderGrid.tsx(535,68): error TS2304`. (런타임 재현: 위 세 입력 편집 → 콘솔 ReferenceError·상태 미저장.)
- 원인(추정): 백엔드 라인아이템 수정 엔드포인트는 `PATCH /api/v1/orders/line-items/{lineItemId}`(OrderController:126)로 존재. 프론트에 대응 `updateOrderLineItem` API 래퍼가 있어야 하나 orderApi.ts에 미구현/누락. 인접 래퍼(`updateSourcingInfo`·`updateShippingInfo`)만 있고 범용 라인아이템 patch 래퍼는 누락된 상태. — 미확인(백엔드 엔드포인트 대조까지만 확인).
- 수정(2026-07-07, 사이클 6 fixer-c6): `orderApi.ts`에 `updateOrderLineItem(id, {isUnipassDone?})` 래퍼 신규(`PATCH /line-items/{id}`, `OrderLineItemUpdateRequest`=isUnipassDone only에 정확히 한정), OrderGrid import. **계약 대조 결과 3경로가 서로 다른 DTO 소속**: 백엔드 `OrderLineItemUpdateRequest`는 isUnipassDone만 보유 → sourcingAmount/logisticsCost를 `/line-items/{id}`로 보내면 Jackson이 무성 폐기(데이터 손실). 따라서 `handleUpdate`의 lineItem 분기를 필드별 라우팅으로 교체 — isUnipassDone→`updateOrderLineItem`(`/line-items/{id}`), sourcingAmount·logisticsCost→기존 `updateSourcingInfo`(`/line-items/{id}/sourcing`, `SourcingUpdateRequest`가 두 필드 보유, `toSourcingData` null-보존 부분갱신 확인). 대조표 3경로 전부 DTO 필드 매칭 성립(fix.md). 성공기준: tsc-app TS2304 해소 5→4(신규 0), lint 6 무회귀, build ✓. 수정 요지: `_workspace/fixes/D-017_fix.md`.
- 범위 밖 후보(원장 기록): 인라인 소싱금액/물류비 편집을 `/sourcing`으로 보내면 백엔드 상태전이 로직(PREPARING 시 sourcingOrderNo 필수·PREPARING→PURCHASED 전이) 경유. 뮤테이션에 onError 토스트 부재로 가드 예외가 무성 실패(기존 패턴).
- 검증(2026-07-07, 사이클 6 verifier-c6): **PASS**. 게이트 실측 — tsc-app 5→4(TS2304 소멸, 신규 0), lint 6 무회귀, build ✓, 백엔드 diff 0. 경계면 교차 비교: 3경로 각각 대상 DTO에 필드 존재 확정(sourcingAmount·logisticsCost→`SourcingUpdateRequest`, isUnipassDone→`OrderLineItemUpdateRequest`). null-보존 주장 실코드 검증 — `SourcingUpdateCommand.toSourcingData(existing)`가 `existing.toBuilder()` 기반 non-null만 덮어씀 + 서비스가 기존 SourcingData 전달 확인(부분갱신 정합). 미검증: 런타임 실행(테스트 러너·DB 부재 → fix.md 수동절차로 갈음). 판정서: `_workspace/verify/D-017_verdict.md`.
- 상태: 검증통과 (사이클 6 verifier-c6)
- 이력: 2026-07-07 발견 (fixer-c5) → 2026-07-07 리더 P2 승인·다음 배치 TDD 처리 지시 → 2026-07-07 수정완료(사이클 6 fixer-c6) → 2026-07-07 검증통과(verifier-c6)

---

### D-016: 엔티티 테이블 sb_market_credential·sb_market_registration에 대응 마이그레이션 부재

- 심각도: P2 (신규 환경 앱 기동 불가 — 기존 스키마 의존)
- 리스크 등급: 표준
- 위치: `backend/core/src/main/java/com/sbshop/agent/core/domain/market/MarketCredential.java`, `MarketRegistration.java` (대응 마이그레이션 없음)
- 증상: 두 엔티티(`sb_market_credential`·`sb_market_registration`)에 대한 `CREATE TABLE` 마이그레이션이 V1~V5 어디에도 없다. `ddl-auto: none`이므로 Hibernate도 생성하지 않음 → 이 테이블들은 pre-Flyway 베이스라인에만 존재. 특히 `Cafe24TokenManager` `@PostConstruct`가 기동 시 `sb_market_credential`을 조회하므로, 빈 DB에 Flyway만으로 구축하면 `ERROR: relation "sb_market_credential" does not exist`로 컨텍스트 기동 실패.
- 재현(2026-07-07, fixer-c4 실측): testcontainers 빈 Postgres + Flyway V1~V5 후 `@SpringBootTest` 기동 → 위 오류. 두 테이블을 테스트 베이스라인에 seed하면 해소.
- 원인(확인): D-015와 동일 뿌리 — 마이그레이션이 델타로만 작성되어 엔티티 전체 스키마를 커버하지 않음. `@Table` 9개 중 7개만 마이그레이션이 생성(누락: sb_market_credential, sb_market_registration).
- 제안(범위 밖): 두 테이블의 `CREATE TABLE IF NOT EXISTS` 마이그레이션(예: V6)을 엔티티 매핑에서 도출해 추가. D-015와 묶어 "마이그레이션 자족성 확보" 배치로 처리 권고.
- 상태: 무효화 (2026-07-07 사용자 결정: Flyway 제거·스키마 수동 관리) — 마이그레이션 부재 자체가 무의미해짐. 사이클 7에서 Flyway 전면 제거, 스키마는 사용자가 직접 관리. `sb_market_credential`·`sb_market_registration`은 운영 DB에 이미 존재하며, 테스트는 엔티티 매핑 기반 `ddl-auto: create-drop`으로 생성해 `Cafe24TokenManager @PostConstruct`의 빈 조회 내성을 `ApiContextLoadSmokeTest`가 실증.
- 이력: 2026-07-07 발견 (fixer-c4) → 2026-07-07 무효화 (사이클 7 fixer-c7, 사용자 Flyway 제거 결정)

---

### D-018: 배송 모달 carrier ↔ 백엔드 shippingCarrier 필드명 불일치 → 저장 시 택배사 누락 가능

- 심각도: P2 (오동작 — 배송 정보 부분 저장)
- 리스크 등급: 표준
- 위치: `frontend/src/pages/OrderGrid.tsx` 배송 모달 (carrier 전송) ↔ `PATCH /api/v1/orders/line-items/{id}/shipping` 요청 DTO (`shippingCarrier` 기대)
- 증상: 프론트가 `carrier` 키로 전송하나 백엔드 DTO 필드는 `shippingCarrier` — Jackson이 미매칭 키를 무시하므로 택배사 값이 null로 저장될 개연.
- 재현: 배송 모달에서 택배사 입력 후 저장 → DB shipping_carrier 미반영 확인 (실측 재현은 수정 배치에서 수행).
- 원인(추정): 레거시 병합 시 프론트/백엔드 필드명 리네임 불일치. — 미확인(양측 코드 grep 대조까지 확인, 런타임 재현 전).
- 수정(2026-07-07, 사이클 6 fixer-c6): `OrderGrid.tsx` 단일. **요청 전 필드 전수 대조로 2겹 불일치 확인**: ①키 이름 `carrier`→`shippingCarrier`(`onSubmit` 페이로드 정합). ②**enum 값 불일치(추가 발견)** — 모달 `<select>` 값(`DHL/FedEx/UPS/USPS/EMS/CJ/LOTTE/POST`)이 백엔드 `ShippingCarrier` enum 상수(`CJ_LOGISTICS/HANJIN/KOREA_POST/LOTTE_LOGISTICS/HYUNDAI_LOGISTICS/ROCKET/ETC`)와 불일치. Jackson 기본 enum 역직렬화는 상수명 정확 일치 요구(`fromMarketCode`는 `@JsonCreator` 아님) → 키만 고치면 ETC 외 전부 HTTP 400. select 옵션을 enum 값/라벨로 교체·carrier 초기값 `DHL`→`CJ_LOGISTICS`(2곳). `trackingNo`는 이미 일치. 백엔드 무변경. 성공기준: tsc-app 4(신규 0)·lint 6 무회귀·build ✓. 수정 요지: `_workspace/fixes/D-018_fix.md`.
- 범위 밖 후보(원장 기록): 국제 택배사(DHL/FedEx/UPS/USPS/EMS) 옵션은 백엔드 `ShippingCarrier` enum이 표현 불가하여 제거됨. 국제배송 지원이 실제 요구라면 백엔드 enum 확충 필요(제품 결정+백엔드 변경) — 별개 결함 후보.
- 검증(2026-07-07, 사이클 6 verifier-c6): **PASS**. 게이트 실측 — tsc-app 4(신규 0), lint 6 무회귀, build ✓, 백엔드 diff 0. 경계면 전수 대조: 키 `shippingCarrier`·필드 `trackingNo` 정합, 모달 select 옵션 7개 = `ShippingCarrier` enum 상수 7개 정확 1:1 일치(누락·초과 없음), 기본값 `CJ_LOGISTICS` 유효. 미검증: 런타임 실행(DB 부재 → fix.md 수동절차로 갈음). 국제택배사 소거는 백엔드 enum 표현 불가에 따른 정당한 스코프 사안(반려 아님, 별개 결함 후보). 판정서: `_workspace/verify/D-018_verdict.md`.
- 상태: 검증통과 (사이클 6 verifier-c6)
- 이력: 2026-07-07 발견 → 2026-07-07 리더 등재 (D-017과 함께 경계면 배치 후보) → 2026-07-07 수정완료(사이클 6 fixer-c6) → 2026-07-07 검증통과(verifier-c6)

---

### D-020: R2 자격증명 부재 시 S3Client 빈 즉시생성 실패 → api·worker 컨텍스트 기동 차단

- 심각도: P1 (기능 불능 — 앱 부팅 차단)
- 리스크 등급: 표준
- 위치:
  - `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cloudflare/config/R2Config.java:20` (`s3Client()` 빈)
  - `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cloudflare/R2ImageStorageClient.java:23` (`S3Client` 즉시 주입 — 유일 소비자)
  - 트리거: `api/src/main/resources/application.yml:31-32` (`access-key: ${CLOUDFLARE_R2_ACCESS_KEY:}` 기본값 빈 문자열)
- 증상(운영 실측 — 리더 확인): 운영 서버 `.env`에 `CLOUDFLARE_R2_*` 미설정 상태. 이때 `R2Config.s3Client()`가 기동 시 즉시 `AwsBasicCredentials.create(accessKey, secretKey)`를 호출 → 자격증명이 blank(빈 문자열)이면 `NullPointerException("Access key ID cannot be blank")` → api·worker 모두 스프링 컨텍스트 기동 실패. `ImageStorageClient`를 주입받는 `ProductCreateUseCase`·`ProductManageUseCase`가 eager 싱글턴이므로 `R2ImageStorageClient`→`S3Client`가 기동 시 즉시 생성됨.
- 재현: 운영 yml 그대로(R2 env 미설정) `@SpringBootTest` 기동 → 컨텍스트 로드 실패. 기존 `ApiContextLoadSmokeTest`는 더미 자격증명(`test-access-key` 등)을 주입해 통과하므로 이 blank 케이스를 못 잡음 → blank-creds 변형 테스트 신규 필요.
- 원인(확인): `s3Client()`가 non-lazy 싱글턴이라 Spring이 기동 시 pre-instantiate → 생성자 본문에서 자격증명 검증에 걸림. 업로드 기능은 실제 호출 시에만 자격증명이 필요한데 부팅 시점에 강하게 결합됨.
- 수정(2026-07-07, 사이클 8 fixer-c8): `S3Client` 빈 생성을 사용 시점으로 지연. ①`R2Config.s3Client()`에 `@Lazy` 추가(Spring pre-instantiate 회피). ②유일 소비자 `R2ImageStorageClient`의 `S3Client` 필드 주입을 `ObjectProvider<S3Client>`로 교체, `uploadImages()` 진입 시 `getObject()`로 해석. `@Lazy` 주입 지점 방식은 이 모듈에 `lombok.config`가 없어 `@RequiredArgsConstructor`가 필드 `@Lazy`를 생성자 파라미터로 전파 못 함 → build-wide 설정 신설(파급 큼) 대신 두 파일 국한 `ObjectProvider` 선택. 계약: 부팅은 blank 자격증명에서도 완주(업로드 외 기능 정상), 업로드 실제 호출 시 AWS SDK 검증 예외가 호출자에 전파(조용한 no-op 아님). Red: `ApiContextLoadWithBlankR2CredentialsTest`(수정 전 NPE로 컨텍스트 로드 실패 확인). 계약 고정: `R2ImageStorageClientBlankCredentialsTest`(업로드 호출 시 예외 전파). 검증: `:api:test --tests ApiContextLoad*`·`:infrastructure:test` PASS, 전체 `test`·`spotlessCheck` BUILD SUCCESSFUL. 기존 `ApiContextLoadSmokeTest`(더미-creds 정상 경로)와 공존. worker도 동일 infrastructure 빈 공유로 함께 해소. 수정 요지: `_workspace/fixes/D-020_fix.md`.
- 검증(2026-07-07, 사이클 8 verifier-c8): **PASS**. Red→Green 독립 재현 — 격리 git 워크트리(HEAD, 수정 전 코드)에 신규 blank 테스트만 복사해 실행 시 `UnsatisfiedDependency→BeanInstantiation(s3Client)→NPE "Access key ID cannot be blank"`로 컨텍스트 로드 FAILED(부팅 차단 재현), 수정 후 primary에서 동일 테스트 PASS. 지연 완전성: `S3Client` 프로덕션 주입 지점 전수 grep = `R2ImageStorageClient` 단 1곳 → `ObjectProvider`화로 eager 소비 체인(`ProductCreateUseCase`/`ProductManageUseCase` @Service → R2ImageStorageClient → S3Client) 차단 완결(`@Bean @Lazy`만으로는 불충분한 리스크를 소비자측 ObjectProvider가 정확히 해소). 실패 모드: 계약 테스트로 blank creds 업로드 시 `RuntimeException` 전파(no-op 아님) 확인. 회귀: 기존 `ApiContextLoadSmokeTest`(더미-creds) PASS, 전체 `test`+`spotlessCheck`(읽기전용) BUILD SUCCESSFUL. 미검증: 실 R2 자격증명 업로드 E2E(하네스 범위 밖 — 재배포 후 수동 확인 필요), worker 런타임 부팅(worker 컨텍스트 테스트 부재 — 동일 빈 공유 정적 확인까지). 판정서: `_workspace/verify/D-020_verdict.md`.
- 상태: 검증통과 (사이클 8 verifier-c8)
- 이력: 2026-07-07 발견 (운영 실측, 리더 확인) → 2026-07-07 수정중 (사이클 8 fixer-c8) → 2026-07-07 수정완료(검증대기) (사이클 8 fixer-c8) → 2026-07-07 검증통과 (사이클 8 verifier-c8)

---

### D-021: Product.detailHtml @Lob 매핑 → 실 PostgreSQL에서 주문/상품 조회 전체 500

- 심각도: P1 (기능 불능 — orders/products API 전멸)
- 리스크 등급: 경량 (1파일·가역 — 리더 직접 처리)
- 위치: `backend/core/src/main/java/com/sbshop/agent/core/domain/product/Product.java:83` (@Lob)
- 증상: 운영 실측(2026-07-07 첫 기동 후) — `Could not extract column [6] from JDBC ResultSet [Bad value for type long : <img ...]`. PostgreSQL에서 @Lob String을 Hibernate가 Large Object(OID)로 취급, text 컬럼을 getLong()으로 읽다 실패.
- 재현: ProductDetailHtmlReadTest (testcontainers 실 Postgres, text 컬럼 + HTML 데이터 → 엔티티 로드). H2로는 재현 불가 (@Lob을 CLOB 텍스트로 처리 — H2≠PG 함정 실증 사례).
- 원인(확인): @Lob 제거로 해소 (columnDefinition="text" 유지 — 매핑 동작 동일, OID 해석만 제거).
- 상태: 검증통과 (Red→Green 실측 + 전체 게이트 그린. 내부 QA: 경량 등급 — 리더 직접, 재현 테스트가 게이트)
- 이력: 2026-07-07 운영 E2E 중 발견 → 2026-07-07 리더 직접 TDD 수정 → 검증통과

---

### D-019: 운영 DB 9개 테이블 공통 감사 컬럼 aa_ 접두사 드리프트 (수동 DDL로 해소)

- 심각도: P0 (기동 차단 — 4번째 층)
- 리스크 등급: 중대 (운영 스키마 — 사용자 승인 후 적용)
- 위치: 운영 sbshop DB 전 테이블의 aa_created_at/aa_updated_at/aa_status (경위 불명 이관 잔재 — 사용자는 직접 붙인 적 없음 확인)
- 처리(2026-07-07): 27개 컬럼 리네임 DDL 단일 트랜잭션 적용 (데이터 무손실), 잔여 aa_ 0건 확인. 사용자 사전 승인.
- 상태: 검증통과 (적용 후 기동 성공 실측)
- 이력: 2026-07-07 운영 E2E 중 발견 → 사용자 승인 → 수동 DDL 적용 → 해소

---

## 사이클 9 신규 결함 (운영 가동 이후 사용자 신고 — 3 클러스터 병렬 진단, 2026-07-07)

> 출처: `_workspace/scout_sync.md`(동기화·액션), `_workspace/scout_status.md`(주문상태 매핑), `_workspace/scout_product.md`(상품페이지). 사용자 신고 3대 증상 = D-022(동기화 무반응)·D-027~D-029(취소 오표시)·D-034~D-036(상품페이지 렌더/기능). 리스크등급은 리더 판정 기입.

### D-022: 마켓 동기화 실패 시 finally 블록이 SYNC_COMPLETED(success=true) 재발행 → 에러 은폐

- 심각도: P0 (기능 불능 체감 — 동기화 실패를 사용자가 인지 불가)
- 리스크 등급: 표준 (4 서비스 동일 패턴·행위 수정, 스키마/스케줄러/계약 무변경)
- 위치:
  - `backend/core/.../order/service/SmartStoreOrderSyncService.java:75-82`
  - `backend/core/.../order/service/CoupangOrderSyncService.java:75-82`
  - `backend/core/.../order/service/ElevenstOrderSyncService.java:61-67`
  - `backend/core/.../order/service/EsmplusOrderSyncService.java:61-67`
  - 연계: `SseNotificationController`, `frontend/.../OrderGrid.tsx:510-525`
- 증상: 동기화 버튼 클릭 → "로딩 및 동기화 중..." → 몇 초 뒤 에러·데이터 없이 사라짐 (사용자 신고 직접 원인).
- 재현: 자격증명 미설정 마켓 동기화 버튼 클릭 → catch가 `SyncCompletedEvent(success=false)` 발행 직후 finally가 무조건 `SyncCompletedEvent(success=true)` 재발행 → SSE로 SYNC_FAILED(토스트 깜박) 후 SYNC_COMPLETED(로딩 정상종료·refetch) 순차 도달.
- 원인(확인): 4개 서비스 `finally` 블록이 예외 여부 무관하게 항상 성공 이벤트 발행.
- 제안 수정: 성공 플래그(try 말미 set) 도입 → finally에서 성공 시에만 SYNC_COMPLETED 발행. 또는 성공 경로 말미에서만 발행하고 finally는 isSyncing 리셋만.
- 수정(2026-07-07, 사이클 9 fixer-c9): 4개 서비스에 `boolean success` 플래그 도입, finally에서 `if(success)`일 때만 2-arg `SYNC_COMPLETED(success=true)` 발행. isSyncing.set(false)는 성공/실패 무관 finally 유지. SSE 계약(SseNotificationController isSuccess() 분기) 무변경. Red: `OrderSyncEventEmissionTest`(실패 4 + 성공 회귀 1) 수정 전 4 fail → 후 5 pass.
- 검증(2026-07-07, verifier-c9): **PASS**. `OrderSyncEventEmissionTest` 5/5 Green, `:core:test`·전체 `./gradlew test` BUILD SUCCESSFUL. 4서비스 실패경로 success 이벤트 도달 불가·성공경로 1회 발행·isSyncing 무조건 리셋 코드 확인. 미검증: 실 SSE 스트림 브라우저 E2E(범위 밖). 판정서 `_workspace/verify/D-022_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-023: SSE 연결 실패 시 isSyncing 영구 고착 (로딩 스피너 무한)

- 심각도: P1 (기능 불능 — SSE 불통 시 로딩 오버레이 영구)
- 리스크 등급: 표준
- 위치: `frontend/src/pages/OrderGrid.tsx:543-605`(sync handler success path — setIsSyncing(false) 없음), `:510-525`(EventSource onerror 없음)
- 증상: SSE 단절 상태에서 동기화 클릭 → 로딩 무한. 원인: isSyncing 리셋을 SSE 이벤트에만 의존, HTTP 응답 성공 분기·onerror에 리셋 없음.
- 제안 수정: EventSource `onerror`에서 setIsSyncing(false)+에러 토스트, HTTP 성공 분기에 timeout fallback.
- 수정·검증(2026-07-07, 사이클 9): fixer-c9 — onerror가 `readyState===CLOSED && isSyncingRef.current`일 때만 해제+토스트(트랜지언트/비동기화 스퍼리어스 방지), isSyncingRef(useRef)+useEffect stale 회피, 4핸들러 30초 watchdog. 정상 SSE/catch 해제 보존. verifier-c9 **PASS**(로직 정합·D-022 SYNC_FAILED 연계 확인). 미검증: 브라우저 EventSource CLOSED 전이·watchdog 실동작(러너 부재). 판정서 `_workspace/verify/D-023_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-024: SYNC_FAILED 토스트에 ELEVEN_STREET·GMARKET 한글 라벨 미매핑

- 심각도: P3 (품질) · 리스크 등급: 경량
- 위치: `frontend/src/pages/OrderGrid.tsx:521` — COUPANG/SMART_STORE만 한글 치환, 나머지 enum명 노출.
- 제안 수정: `marketLabels` Record(:413) 재사용.
- 수정·검증(2026-07-07, 사이클 9): fixer-c9 — 인라인 삼항을 `marketLabels[marketType]||marketType`로 교체(11번가·G마켓/옥션 등 한글화). verifier-c9 **PASS**(COUPANG/SMART_STORE 회귀 없음). 판정서 `_workspace/verify/D-024_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-025: 선택 발송(handleShipSelected) 성공 시 toast.success 없음

- 심각도: P3 (UX) · 리스크 등급: 경량
- 위치: `frontend/src/pages/OrderGrid.tsx:1122-1127` — shipOrders 성공 후 조용히 refetch만.
- 제안 수정: `toast.success('N건 발송 처리되었습니다.')`.
- 수정·검증(2026-07-07, 사이클 9): fixer-c9 — handleShipSelected 성공경로(try 내, refetch 뒤) toast.success 추가, 건수=중복제거 orderIds.length. verifier-c9 **PASS**(성공경로만·실패 catch 유지). 판정서 `_workspace/verify/D-025_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-026: 선택 주문 거부(handleCancelOrders) 성공 시 toast.success 없음

- 심각도: P3 (UX) · 리스크 등급: 경량
- 위치: `frontend/src/pages/OrderGrid.tsx:673-680` — cancelOrder 성공 후 조용히 refetch만.
- 제안 수정: `toast.success('N건 취소(거부) 처리되었습니다.')`.
- 참고(scout): 선택 확인/거부/발송 백엔드 계약(`/confirm/batch`·`/{id}/cancel`·`/ship`)·DTO는 전부 프론트와 일치(계약 결함 아님). "선택 확인" 버튼은 선택 행이 모두 NEW일 때만 활성(UX 가이드 부재이나 결함 아님).
- 수정·검증(2026-07-07, 사이클 9): fixer-c9 — handleCancelOrders 성공경로 toast.success 추가, confirm 취소 시 조기 return으로 오발행 없음. verifier-c9 **PASS**. 판정서 `_workspace/verify/D-026_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-027: 쿠팡 detectCancellations — RETURNED·EXCHANGED를 terminal에서 누락 → 반품/교환 주문을 오취소 (사용자 신고 "취소 아닌데 취소됨" 직접 원인)

- 심각도: P1 (오동작 — 실 주문상태 오표시)
- 리스크 등급: 표준 (라이브 주문 상태에 영향 — 회귀 게이트 필수)
- 위치: `backend/core/.../order/adapter/CoupangOrderAdapter.java:481-492`
- 증상: 반품/교환 처리 중 쿠팡 주문이 취소 아닌데 "취소됨" 표시.
- 재현: DB에 RETURNED 주문 A → 동기화(fetchOrders는 ACCEPT/INSTRUCT/DEPARTURE/DELIVERING/FINAL_DELIVERY/NONE_TRACKING만 조회, RETURNED 미포함) → detectCancellations의 hasNonTerminal이 RETURNED를 non-terminal로 판정(terminal=CANCELED,DELIVERED뿐) → API 응답에 없으니 CANCELED로 덮어씀.
- 원인(확인): terminal 판정에 RETURNED·EXCHANGED 누락.
- 제안 수정: hasNonTerminal의 terminal 집합에 RETURNED·EXCHANGED 추가(1줄). Red: RETURNED 주문이 API 미응답 시 CANCELED로 안 바뀜을 재현.
- 수정(2026-07-07, 사이클 9 fixer-c9): non-terminal 판정을 `isNonTerminal` 헬퍼로 추출, terminal 집합에 RETURNED·EXCHANGED 추가. Red: `CoupangDetectCancellationsTest`(RETURNED/EXCHANGED 미취소 2 + NEW 취소·DELIVERED 미취소 회귀 2) 수정 전 2 fail → 후 4 pass.
- 검증(2026-07-07, verifier-c9): **PASS**. `CoupangDetectCancellationsTest` 4/4 Green. RETURNED/EXCHANGED 미취소 확인 + 정당 취소(NEW/PREPARING/SHIPPED 소멸) 회귀 유지, terminal 집합 ↔ ShippingStatus enum 정합. 미검증: 쿠팡 실 API가 반품/교환을 미조회하는 전제(범위 밖). 판정서 `_workspace/verify/D-027_verdict.md`.
- 상태: 검증통과 (사이클 9 — "취소 아닌데 취소됨" 쿠팡 직접원인 해소)

---

### D-028: 11번가 취소/반품/교환 동기화 전무 — postSyncProcess 빈 메서드

- 심각도: P1 (오동작 — 취소 주문이 NEW/PREPARING 영구 잔류)
- 리스크 등급: 표준
- 위치: `backend/core/.../order/service/ElevenstOrderSyncService.java:208`(빈 postSyncProcess), `.../adapter/ElevenstOrderAdapter.java:58-107`(complete/packaging/shipping/dlvcompleted 4상태만 조회)
- 증상: 11번가 주문이 취소/반품돼도 DB에 이전 상태로 영구 잔류. 쿠팡 detectCancellations 대응 로직 부재.
- 제안 수정: 쿠팡 detectCancellations 패턴을 11번가에 적용(terminal에 RETURNED·EXCHANGED 포함). **주의: 라이브 주문을 CANCELED로 마킹 — 오취소 방지 로직 정확성 검증 필수.**
- 수정(2026-07-07, 사이클 9 fixer-c9): 11번가 어댑터가 detectCancellations 훅을 미보유 → `ElevenstOrderSyncService.postSyncProcess`에 쿠팡 정본 `isNonTerminal`(terminal=CANCELED·DELIVERED·RETURNED·EXCHANGED) 이식. API 미응답 기존 주문 중 non-terminal을 CANCELED 처리, 주문일 30일 범위 밖 스킵(쿠팡 정본 동일 가드). Red: `ElevenstDetectCancellationsTest`(NEW 소멸 취소 1 + RETURNED/EXCHANGED/DELIVERED 오취소방지 회귀 3).
- 검증(2026-07-07, verifier-c9): **PASS**(라이브 리스크 심사 통과). isNonTerminal이 쿠팡 정본과 문자 동일 → RETURNED/EXCHANGED 오취소 방지·NEW 소멸 정당취소 유지, 30일 스킵 정당, 상태 갱신만·신규생성 없음. `ElevenstDetectCancellationsTest` 4/4. 미검증: 11번가 실 API가 취소를 미조회하는 전제(진단 근거·범위 밖). 판정서 `_workspace/verify/D-028_verdict.md`.
- 상태: 검증통과 (사이클 9 — 11번가 취소 오표시 해소)

---

### D-029: ESM+ 취소·교환 주문 null 반환 → 기존 DB 주문 상태 미갱신

- 심각도: P1 (오동작 — 취소/교환이 이전 상태로 고착)
- 리스크 등급: 표준
- 위치: `backend/core/.../order/adapter/EsmplusOrderAdapter.java:183-185`, `backend/infrastructure/.../esmplus/EsmplusOrderApiPortImpl.java:677-679`, 연계 `EsmplusOrderSyncService`
- 증상: status가 CANCELED/EXCHANGED면 `return null`로 필터 → processOrders 미전달 → 기존 주문(NEW/PREPARING) 미갱신.
- 원인(확인): 취소/교환을 단순 필터링(detectCancellations 부재). 참고: ESM+ API가 2010~2070 취소코드를 리스트 반환하는지는 API 문서 확인 필요이나, 반환되더라도 현재 코드가 DB 미반영은 확정.
- 제안 수정: 취소/교환도 기존 주문 있으면 상태 업데이트하도록 변경, 또는 detectCancellations 패턴 적용.
- 수정(2026-07-07, 사이클 9 fixer-c9): 실 라이브 경로 = infra `EsmplusOrderApiPortImpl.parseSingleOrder`(Selenium 스크레이프, 상태 필터 없이 취소/교환 in-band 반환) → **case① null 필터 제거**로 커버. `if(CANCELED||EXCHANGED) return null`을 core·infra 양쪽 제거 → processOrders→updateExistingOrder가 기존 주문 상태 갱신. Red: `EsmplusParseSingleOrderTest`(취소 2010/교환 2050 포함 + 정상 회귀). core 파서는 미사용 중복(dead)이나 드리프트 방지로 동일 수정.
- 검증(2026-07-07, verifier-c9): **PASS**. 호출그래프로 라이브 경로(core.fetchOrders→infra parseSingleOrder:646) 확인, infra에도 동일 적용. `EsmplusParseSingleOrderTest` 4/4. **비차단 관찰**: DB에 없던 취소/교환 주문은 processOrders else 분기로 CANCELED/EXCHANGED 신규 레코드 생성 — 데이터 오염 아닌 실 마켓상태 반영(기존 계약). 업무상 원치 않으면 [[D-039]]. 미검증: infra parseSingleOrder는 private+Selenium이라 core 파서와 정적 교차비교로 갈음. 판정서 `_workspace/verify/D-029_verdict.md`.
- 상태: 검증통과 (사이클 9 — ESM+ 취소/교환 미갱신 해소)

---

### D-030: 쿠팡 NONE_TRACKING → UNKNOWN 오매핑 (SHIPPED여야 함)

- 심각도: P2 (오동작) · 리스크 등급: 경량
- 위치: `backend/core/.../order/mapper/CoupangStatusMapper.java:30-44` (case 없음 → default UNKNOWN), `CoupangOrderAdapter.java:66`(조회 대상엔 포함)
- 증상: 운송장 미등록 배송중 쿠팡 주문이 "알수없음" 표시.
- 제안 수정: `case "NONE_TRACKING" -> ShippingStatus.SHIPPED;` 추가.
- 수정·검증(2026-07-07, 사이클 9): fixer-c9가 mapBasicStatus에 NONE_TRACKING→SHIPPED 추가, `CoupangStatusMapperTest` 2/2. verifier-c9 PASS(기존 매핑 회귀 없음, statuses 배열에 NONE_TRACKING 실재 정합). 판정서 `_workspace/verify/D-030_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-031: 11번가 parseOrderDetailElement status SHIPPED 하드코딩

- 심각도: P2 (잠재 오동작 — 현재 호출처 없음) · 리스크 등급: 경량
- 위치: `backend/core/.../order/adapter/ElevenstOrderAdapter.java:264` — `.status(ShippingStatus.SHIPPED)` 고정.
- 증상: fetchOrderDetail 반환 status가 항상 SHIPPED. 현재 미호출이나 향후 통합 시 오매핑.
- 제안 수정: 실제 상태 파싱 또는 메서드 용도 명확화(SHIPPED 전용이면 주석/이름).
- 수정·검증(2026-07-07, 사이클 9): fixer-c9 실측 — fetchOrderDetail(11번가) 호출처 0건(latent), 소스 엔드포인트에 배송상태 필드 없음 → 근거없는 `.status(SHIPPED)` 제거(미설정 null=enrichment no-clobber). parseShippingElement의 배송중 SHIPPED는 무변경. `ElevenstOrderDetailStatusTest` 1/1. verifier-c9 PASS(null이 toShippingData null-guard로 안전). 판정서 `_workspace/verify/D-031_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-032: ESM+ 문자열 폴백 조건 논리 오류 (DEAD CODE)

- 심각도: P2 (오동작 — 미인식 코드 폴백 무력화) · 리스크 등급: 경량
- 위치: `backend/core/.../order/adapter/EsmplusOrderAdapter.java:175-179`, `backend/infrastructure/.../esmplus/EsmplusOrderApiPortImpl.java:668-675`
- 원인(확인): `if (status==NEW && deliveryStatusCode!=1010)` — NEW는 1010에서만 반환되므로 논리 모순 = DEAD CODE. 의도는 `if (status==UNKNOWN)` (미인식 코드 시 문자열 폴백).
- 제안 수정: 조건을 `status==UNKNOWN`으로, 폴백 비교도 `!=UNKNOWN`으로 정정.
- 수정·검증(2026-07-07, 사이클 9): fixer-c9가 core·infra 양쪽 `if(status==NEW && code!=1010)`→`if(status==UNKNOWN)`, 내부 비교 `!=NEW`→`!=UNKNOWN`으로 교정. Red: `EsmplusParseSingleOrderTest`(미인식 9999+"배송중"→SHIPPED 폴백). verifier-c9 PASS(폴백 작동, 정상 1040→SHIPPED 회귀 없음). 판정서 `_workspace/verify/D-032_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-033: 프론트 동기화 상태 dot에서 AUCTION 누락

- 심각도: P3 (표시 누락) · 리스크 등급: 경량
- 위치: `frontend/src/pages/OrderGrid.tsx:413-421`(marketLabels), `:1166`(렌더)
- 제안 수정: `AUCTION` 키 추가 또는 GMARKET에 통합 표시(ESM+ 공유 서비스).
- 판정(2026-07-07, 사이클 9 — **무변경 정당**): fixer-c9·verifier-c9 백엔드 발행 키 직접 실측 — `OrderSyncScheduler` markRunning/Completed/Failed 인자는 EMAIL·COUPANG·GMARKET·SMART_STORE·ELEVEN_STREET·COUPANG_SETTLEMENT·CUSTOMS 7종으로 **AUCTION 미발행**(ESM+가 G마켓·옥션을 GMARKET 단일 키 처리). 프론트 dot은 `Object.entries(marketLabels)`이고 GMARKET='G마켓/옥션'로 이미 포괄. AUCTION 키 추가 시 syncStatuses['AUCTION'] 영구 undefined→회색 오해 dot 발생 → **코드 무변경이 정답**. verifier-c9 실측 일치 확인.
- 상태: 검증통과 (사이클 9 — 무변경 결정, 실측 근거)

---

### D-034: 상품관리 페이지 ag-Grid cellRenderer가 HTML 문자열 반환 → 텍스트로 이스케이프 (사용자 신고 "`<img>` `<button>` 텍스트 노출" 직접 원인)

- 심각도: P1 (기능 불능 — 화면 렌더 깨짐)
- 리스크 등급: 표준 (단일 파일이나 화면 핵심)
- 위치: `frontend/src/pages/ProductPage.tsx:39-41`(이미지 컬럼), `:48-53`(관리 컬럼)
- 증상: ag-Grid v32에서 cellRenderer가 문자열 반환 시 textContent 처리 → `<img>`/`<button>` 태그가 글자로 노출.
- 제안 수정: JSX 반환 React 셀 렌더러로 교체(이미지 `<img>`, 관리 버튼 onClick).
- 수정(2026-07-07, 사이클 9 fixer-c9, D-037 동반): 두 cellRenderer를 JSX 반환으로 교체(이미지 `<img>`, 관리 버튼 `onClick`→openPriceStockModal). `window.__editProduct` 전역등록+onGridReady prop 전면 제거([[D-037]]). 가격/재고 모달 기존 로직 보존. 상세모달(D-035)·이미지변경(D-036)은 범위 밖 미착수.
- 검증(2026-07-07, verifier-c9): **PASS**. `tsc -p tsconfig.app.json` ProductPage 신규 에러 0(잔존 4건 전부 OrderGrid.tsx 기준선 any), `npm run build` EXIT 0, `__editProduct` grep 0건. 미검증: 브라우저 실제 렌더(범위 밖 — 수동 확인 권고). 판정서 `_workspace/verify/D-034_verdict.md`.
- 상태: 검증통과 (사이클 9 — 상품페이지 raw HTML 노출 직접원인 해소)

---

### D-035: 상품 상세 모달 미구현 (상품명 클릭 핸들러·모달 컴포넌트 부재)

- 심각도: P2 (기능 불능 — 상세 조회 불가)
- 리스크 등급: 표준 (신규 UI 구축, 백엔드 계약 완비)
- 위치: `frontend/src/pages/ProductPage.tsx:44`(상품명 컬럼 — onCellClicked 없음)
- 증상: 상품명 클릭 무반응. `fetchProductDetail`(`productApi.ts:42`) 정의됐으나 미호출, 상세 모달 없음.
- 제안 수정: 상품명 클릭 → `fetchProductDetail(id)` → `ProductDetail`(id/코드/가격/물류/스펙/소싱/이미지/detailHtml) 표시 모달 구축.
- 수정(2026-07-07, 사이클 9 fixer-c9, 미배선 UI 구축): 상품명 onCellClicked → fetchProductDetail → antd Descriptions 섹션화(기본/가격/물류/스펙/소싱/메모), 로딩 Spin·실패 토스트. detailHtml은 **sandbox iframe(`sandbox=""` + srcDoc)** 으로 스크립트 차단 렌더(XSS 방지, dangerouslySetInnerHTML 0건).
- 검증(2026-07-07, verifier-c9): **PASS**. 경계면 대조 — 프론트 `ProductDetail` ↔ 백엔드 `ProductDetailResponse` 최상위·중첩 VO 필드명 전부 정합(PriceInfo/LogisticsInfo/ProductSpec/SourcingInfo). detailHtml XSS 안전(빈 sandbox 스크립트 차단), sourceUrl rel="noopener". tsc ProductPage 신규에러 0, build EXIT 0. 미검증: 실 JSON 직렬화·모달 렌더 브라우저 런타임(러너 부재). 판정서 `_workspace/verify/D-035_verdict.md`.
- 상태: 검증통과 (사이클 9 — 상품 상세 조회 기능 배선)

---

### D-036: 상품 이미지 변경 UI 미구현 (API 완비·UI 부재)

- 심각도: P2 (기능 불능) · 리스크 등급: 표준
- 위치: `frontend/src/pages/ProductPage.tsx`(UI 없음), API `productApi.ts:48-55`, 백엔드 `ProductController.java:94-114`(`PUT /images`·`/images/by-url` 구현 존재)
- 증상: 이미지 변경 UI(파일 업로드/URL 입력/변경 버튼) 부재.
- 제안 수정: 상세 모달 내 파일 업로드+URL 입력 섹션 → `uploadImages`/`uploadImagesByUrl` 연결. **주의: R2 자격증명 미설정 시 업로드 실패([[D-020]]) — 코드 결함 아닌 환경 이슈로 구분, 명확한 에러 표시.**
- 수정(2026-07-07, 사이클 9 fixer-c9): 상세 모달 내 이미지 섹션 — hostedImages/sourceImages 썸네일(Image.PreviewGroup) + ①파일 업로드(FormData 'images' 멀티 append → uploadImages), ②URL 등록(TextArea → uploadImagesByUrl), ③crawlSourceImages(자동등록 아닌 TextArea 채움→검토 후 등록). 성공 시 refetch. **D-020 대응**: 업로드 실패 시 "이미지 업로드 실패 — 서버 스토리지(R2) 설정을 확인하세요" 명확 토스트(조용한 실패 금지), uploading 중복클릭 방지.
- 검증(2026-07-07, verifier-c9): **PASS**. 백엔드 파트 키 정합 — ①FormData 'images' ↔ `@RequestPart("images") List<MultipartFile>`, ②URL 배열 ↔ `@RequestBody List<String>`, ③crawl ↔ `GET List<String>`. 크롤 자동등록 아님(검토 후 등록). R2 미설정 시 의도된 에러 토스트 경로 확인(환경이슈 구분). tsc 신규에러 0, build EXIT 0. 미검증: 멀티파트/R2 저장/크롤 실동작 실API 런타임(범위 밖). 판정서 `_workspace/verify/D-036_verdict.md`.
- 상태: 검증통과 (사이클 9 — 이미지 변경 기능 배선)

---

### D-037: window.__editProduct 전역 함수 패턴 (CSP 위험·정리 없음)

- 심각도: P3 (부채) · 리스크 등급: 경량
- 위치: `frontend/src/pages/ProductPage.tsx:52`(onclick 문자열), `:57-59`(window 전역 등록)
- 제안 수정: D-034(JSX 셀 렌더러)와 동시에 전역 함수 패턴 제거 → React 이벤트로.
- 수정·검증(2026-07-07, 사이클 9): [[D-034]]와 함께 처리 — `window.__editProduct` 전역등록·인라인 onclick 전면 제거, React onClick 핸들러로 대체. `__editProduct` grep 0건(verifier-c9).
- 상태: 검증통과 (사이클 9, D-034 동반)

---

### D-038: BatchUpdatePage by-supplier 빈 응답 계약 불일치 → undefined 노출

- 심각도: P2 (오동작 — 빈 결과 시) · 리스크 등급: 경량
- 위치: `frontend/src/pages/BatchUpdatePage.tsx:26`, `backend/api/.../controller/BatchController.java:89`
- 증상: 해당 소싱업체 상품 없을 때 백엔드가 `{message}`만 반환(batchId·count 없음) → 프론트가 "배치 시작: undefined개 상품 (batchId: undefined)" 표시.
- 제안 수정: 프론트에서 batchId 유무 분기, 또는 백엔드 empty 케이스를 명시 응답으로.
- 수정·검증(2026-07-07, 사이클 9): fixer-c9 — 백엔드 무변경, `BatchUpdatePage.tsx`에서 `res.data`를 {batchId?,count?,message?}로 보고 batchId 있으면 성공메시지·없으면 `message.info`로 백엔드 안내 표시. verifier-c9 **PASS**(빈응답 {message} 계약 정합, 정상응답·manual 모드 회귀 없음). 판정서 `_workspace/verify/D-038_verdict.md`.
- 상태: 검증통과 (사이클 9)

---

### D-039: (후보) ESM+ 취소/교환 주문이 DB에 없으면 CANCELED/EXCHANGED 신규 레코드 생성

- 심각도: 미정(업무 결정 필요) · 리스크 등급: 경량
- 위치: `EsmplusOrderApiPortImpl.parseSingleOrder`(취소/교환 in-band 반환) → `processOrders` else 분기 `createNewOrder`
- 관찰(verifier-c9, D-029 검증 중): D-029로 null 필터를 제거하자, 과거 동기화된 적 없는 취소/교환 ESM+ 주문이 조회되면 신규 CANCELED/EXCHANGED 주문으로 생성됨. 데이터 오염은 아니고 실 마켓 상태 반영이며 모든 상태에 동일 적용되는 기존 processOrders 계약. 다만 "취소된 주문을 신규로 만드는" 것이 업무상 불필요하면 skip 조건 추가 검토.
- 제안(범위 밖): 신규 주문 생성 시 terminal 상태(CANCELED/EXCHANGED)면 skip하거나, 취소는 기존 주문 갱신만 허용. 업무 요구 확인 후 결정.
- **사용자 결정(2026-07-08)**: "기존 동기화된 주문이 취소되면 그 행이 취소로 바뀌는 게 자연스럽다. 없던 취소 주문이 신규 레코드로 생성되면 중복 표시" → **absent + CANCELED/EXCHANGED는 신규 생성 건너뜀**(기존 주문만 취소로 갱신).
- 수정(2026-07-08, fixer-d39): `EsmplusOrderSyncService.processOrders` else(미존재) 분기 진입 직후 `dto.getStatus()`가 CANCELED/EXCHANGED면 상세조회·생성 없이 continue(로그). RETURNED·기존 update·비terminal 생성 경로 무변경(D-029 이전 거동 보존). Red: `EsmplusOrderSyncTerminalSkipTest` 4케이스(①absent+CANCELED→save/fetchOrderDetail never ②absent+EXCHANGED→동일 ③existing+CANCELED→save 갱신유지 ④absent+NEW→생성유지) 수정 전 ①② 실패 → 후 4/4.
- 검증(2026-07-08, 리더 직접): diff 정확(1파일, else 진입 즉시 skip), `:core:test` BUILD SUCCESSFUL, 테스트 4케이스가 실제 가드(never/verify)로 결함 포착. 경량-표준 등급 리더 검증.
- 상태: 검증통과 (사이클 9 후속 — 사용자 결정 반영, 중복 레코드 방지)

---

### D-040: (후보/구조) core EsmplusOrderAdapter parseSingleOrder/parseOrdersFromJson 미사용 중복(dead)

- 심각도: P3 (부채) · 리스크 등급: 경량
- 위치: `backend/core/.../order/adapter/EsmplusOrderAdapter.java` (parseSingleOrder/parseOrdersFromJson)
- 관찰(fixer-c9, D-029 수정 중): 실 라이브 경로는 infra `EsmplusOrderApiPortImpl`이고 core 어댑터의 파서는 테스트 외 호출처 없는 중복. D-029에서 드리프트 방지 위해 양쪽 동일 수정했으나, 구조적으로는 중복 제거 대상.
- 제안(범위 밖, 구조 배치): 사용처 재확인 후 core 중복 파서 제거 또는 infra와 단일화. 행위/구조 분리 위해 별도 배치.
- 상태: 후보 (사이클 9 fixer-c9 관찰 — 구조 정리 후보)

---

## 사이클 10 (운영 라이브 진단: 동기화 무반응·액션로그, 2026-07-08)

> 라이브 진단(리더 직접, 실서버 SSH): 동기화 버튼 눌러도 그리드 비어있음 → 실제로는 마켓 API가 실패하는데 **어댑터가 오류를 삼켜 "성공 0건"으로 보고**. 근본은 외부(자격증명/IP 허용목록)이나, 코드가 실패를 은폐. 서버 아웃바운드 IP=168.107.31.154(쿠팡/11번가/스마트스토어 허용목록 등록 대상 — 사용자 조치). 자격증명 실측: COUPANG SET/SET(그러나 403 FORBIDDEN=IP 미허용 유력), SMART_STORE access-key EMPTY, ELEVEN_STREET secret-key EMPTY, GMARKET SET/SET(Selenium).

### D-041: 마켓 주문 동기화 어댑터가 API 오류(403 등)를 삼켜 "성공 0건"으로 보고

- 심각도: P1 (오동작 — 실패를 성공으로 위장, 사용자 무반응 체감)
- 리스크 등급: 표준
- 위치: `backend/core/.../order/adapter/CoupangOrderAdapter.java:72-104`(status별 `catch(Exception)` 후 log만·빈 리스트 반환), 동종 패턴 타 어댑터 점검
- 증상(라이브 실측): 쿠팡 동기화 → API 전 status **403 FORBIDDEN** → 어댑터가 삼켜 `result` 빈 채 반환 → 서비스 `[COUPANG] 동기화 완료: 0건 처리`·success=true → SSE SYNC_COMPLETED → 프론트 에러 없이 빈 그리드. D-022(finally 이중이벤트)는 해소됐으나 그 위층(어댑터 삼킴)이 남아 실패가 서비스 catch에 도달 못 함.
- 원인(확인): fetchOrders가 API 실패를 예외로 전파하지 않고 빈 결과로 흡수. "진짜 0건"과 "오류로 0건"을 구분 못 함.
- 제안 수정: fetchOrders에서 status별 실패를 집계 → 전량 실패(성공 0·오류≥1)면 HTTP 상태 포함 설명적 예외 throw(서비스 catch→SYNC_FAILED→에러 토스트). 부분 성공은 유지. HTTP 403 등 상태코드를 메시지에 노출("403 — IP 허용목록/자격증명 확인"). 타 마켓 어댑터 동일 패턴 점검.
- 상태: 검증통과 (qa-verifier, 2026-07-08 — `_workspace/verify/D-041_verdict.md`). 전량실패→예외전파(403 포함)·진짜0건 무예외·정상/부분실패 정합, 서비스 catch→SYNC_FAILED 경로 정합, CoupangOrderFetchFailureTest 4/4 그린. ⚠커밋 전 트리 동결 후 재게이트 필요(검증 중 D-043 라이브 편집 감지 — 판정서 참조).

### D-042: 진행 현황 사용자 액션 로그 부재 (요청 기능)

- 심각도: 기능 요청 · 리스크 등급: 표준 (신규 테이블·엔드포인트·UI)
- 사용자 요청: "진행 현황에서 사용자가 누른 모든 행동을 로그로 — 쿠팡 동기화 눌렀으면 눌렀다는 것 한 줄 + 성공/실패."
- 현황: `SyncStatusService`는 마켓별 최신 상태만 인메모리 보관(이력 없음·재시작 소실). `ProcessStatus`(sb_process_status)는 상품 배치 전용. 시간순 액션 로그 없음.
- 제안 구축: 영속 `ActionLog` 엔티티(`sb_action_log`: BaseEntity[id/status/created_at/updated_at] + action_type·market_type·action_status(STARTED/SUCCESS/FAILED)·message) + `ActionLogService.record()` + `@EventListener(SyncCompletedEvent)`로 SUCCESS/FAILED 기록 + 컨트롤러에서 STARTED 기록 + `GET /api/v1/action-logs?limit=N` + 프론트 진행현황에 "활동 로그" 섹션(시간·액션·마켓·상태·메시지). 스키마는 수동 DDL(서버). D-041과 결합 시 실패가 로그에 명확히 남음.
- 상태: 검증통과 (qa-verifier, 2026-07-08 — `_workspace/verify/D-042_verdict.md`). **DDL↔엔티티 완전 일치**(id/status/action_type/market_type/action_status/message/created_at/updated_at 컬럼명·타입·nullable 대조 — 서버 DDL 적용 안전), SyncCompletedEvent getter명 정확, GET 최근순·limit clamp, 프론트 DTO 계약 일치, ProcessStatusPage 무회귀. ServiceTest/SyncListenerTest 그린·api:test 빈주입 확인·프론트 tsc0/build0. ⚠서버 `sb_action_log` 수동 DDL 생성 필요(미생성 시 record 예외안전 삼킴).

---

### D-043: 11번가·스마트스토어·ESM+ 어댑터도 동일 오류 삼킴 + 자격증명 빈문자열 미검증

- 심각도: P1 (오동작 — 액션 로그에 거짓 SUCCESS 기록 유발) · 리스크 등급: 표준
- 위치: 각 마켓 클라이언트/포트impl fetch + `{Elevenst,SmartStore,Esmplus}OrderAdapter.fetchOrders`(D-041의 쿠팡과 동일 2층 삼킴), `{...}OrderSyncService.loadAndValidateCredential`(null만 검사·빈문자열 통과)
- 근거(fixer-c10 점검 + 리더 실측): 동일 삼킴 패턴이 11번가·스마트스토어·ESM+ 전부에 존재. 또한 loadAndValidateCredential이 `access_key==null`만 보고 **빈 문자열은 통과** → 스마트스토어(access-key EMPTY)·11번가(secret EMPTY)가 API까지 가서 실패·삼켜짐. 결과적으로 D-042 액션 로그가 이 3마켓에 거짓 SUCCESS(0건) 기록 → 로그 신뢰성 훼손.
- 제안 수정: D-041의 쿠팡 패턴을 3개 마켓 클라이언트+어댑터에 이식(전량 실패 throw). loadAndValidateCredential을 빈문자열도 불완전으로 처리(clear 메시지 "○○ 자격증명 불완전"). 각 마켓 Red 테스트.
- 상태: 검증통과 (qa-verifier, 2026-07-08 — `_workspace/verify/D-043_verdict.md`). 3마켓 어댑터+클라이언트 전량실패 예외전파(삼킴 2층 해소)·**정상0건 오탐 없음**(11번가 result_code≠0 업무분기·스마트스토어 빈배열 try내 반환 보존)·부분실패 result+warn·자격증명 `!hasText` fast-fail(불완전 이벤트, success 없음)·ESM+ masterId/스크래핑 예외전파. 신규 10 케이스 그린. **전체 클린 게이트 `./gradlew test`(전 모듈) BUILD SUCCESSFUL·프론트 tsc0/build0** — 사이클 10(D-041·D-042·D-043) 일괄 커밋 게이트 통과. 쿠팡 서비스 무접촉. 잔여: 11번가 result_code≠0(HTTP200 업무오류) 및 쿠팡 서비스 빈문자열 검증은 후속 권고(판정서 참조).

### D-044: ESM+(G마켓/옥션) Selenium이 컨테이너에 chromedriver/Chrome 부재로 동작 불가

- 심각도: P1 (기능 불능 — ESM+ 동기화 전무) · 리스크 등급: 표준 (Docker 이미지·기동 스크립트)
- 위치: `Dockerfile.backend`(런타임 `eclipse-temurin:21-jre`에 Chrome 미설치), `start.sh`, `infrastructure/.../esmplus/EsmplusScraper.java`(`new ChromeDriver(options)` — Selenium Manager 자동 다운로드 의존)
- 증상(라이브 실측): ESM+ 동기화 → 액션 로그 `FAILED "Unable to obtain: chromedriver, error Command failed"`. 자격증명(masterId/password) 정상, 크롤링 드라이버 자체가 없음. 로컬 PC엔 크롬 있었으나 서버 컨테이너엔 없음.
- 원인(확인): 런타임 이미지에 Chrome/chromedriver 부재 + Selenium 4.16이 Selenium Manager로 런타임 다운로드 시도하나 실패.
- 진단 여정(2026-07-08, 리더 직접): ①서버가 **ARM64**(Oracle Ampere, 커널 6.17) — Google Chrome ARM64 미제공(amd64 .deb 의존성 충돌). ②Debian bookworm `chromium`+`chromium-driver`(arm64, 버전일치 150) 설치는 되나 **Chromium이 SIGTRAP(exit 133)으로 크래시** — 플래그(headless old/new·single-process)·seccomp=unconfined 무관하게 재현(이 Ampere 커널과 비호환). ③해법 전환: **`seleniarm/standalone-chromium`(ARM64 지원 이미지, Chromium 124)는 이 호스트에서 세션 생성 성공 실측** → 별도 Selenium 컨테이너 + 앱이 RemoteWebDriver로 접속.
- 수정(2026-07-08, 리더+fixer-c11): 앱 이미지 chromium 설치 시도 전량 롤백(Dockerfile/start.sh 원복). 신규 `EsmplusDriverFactory.newDriver(options)` — `SELENIUM_REMOTE_URL` 있으면 `RemoteWebDriver(url, options)`, 없으면 `ChromeDriver`(로컬 개발 보존). `EsmplusOrderApiPortImpl`·`EsmplusScraper`의 ChromeDriver 타입을 공통 상위 `RemoteWebDriver`로 넓히고 생성부를 팩토리로 교체. 서버 compose에 `sbshop-selenium`(seleniarm/standalone-chromium, shm 2g) 서비스 + sbshop-api에 `SELENIUM_REMOTE_URL=http://sbshop-selenium:4444/wd/hub` env 추가(백업 `docker-compose.yml.bak-c11`). `:infrastructure:compileJava :api:compileJava :infrastructure:test` BUILD OK.
- 검증(2026-07-08, 리더 라이브): **PASS(드라이버 인프라 한정)**. 재배포 후 ESM+ 동기화 → **원격 Selenium 그리드에서 세션 생성 성공**(chrome 124, Session ID 로그 실측) → 로그인 페이지 접속·로그인 제출·쿠키 6개 획득까지 실행. chromedriver "Unable to obtain"/SIGTRAP 크래시 **완전 소멸**. 이후 `#innerIFrame` 미발견으로 실패하나 이는 드라이버가 아닌 **ESM+ 로그인 성공 여부/페이지 구조** 문제 → [[D-045]]로 분리.
- 상태: 검증통과 (사이클 10 후속 — chromedriver/드라이버 인프라 해소, 원격 그리드 세션 실증)

### D-045: ESM+ 로그인 후 주문 iframe(#innerIFrame) 미발견 → 스크래핑 실패 (근본원인 심화)

- 심각도: P1 (ESM+ 동기화 여전히 불가 — 단 원인이 드라이버가 아님) · 리스크 등급: 표준
- 위치: `EsmplusOrderApiPortImpl.loginAndCreateDriver:439-487`(로그인 후 `:482` `driver.findElement(By.id("innerIFrame"))`), `createLoggedInDetailDriver:292-318`(동일 패턴), `EsmplusOrderSyncService.loadAndValidateCredential:77-86`, `EsmplusScraper.java`(진단 전용, 미사용 실경로), `frontend/src/pages/Settings.tsx:59-60,253-315`
- 증상(라이브): 원격 그리드로 로그인 페이지 접속·제출·쿠키 6개 획득 후 주문 페이지(`/Home/v2/order-integration`)에서 `#innerIFrame` NoSuchElement.
- **(a) 로그인 성공 판별 로직 — 부재(확인)**: `loginAndCreateDriver`(439-487)는 로그인 버튼 클릭(466) 후 `Thread.sleep(5000)`(469)만 하고 **URL도 페이지 요소도 검증하지 않은 채** 곧바로 주문 페이지로 이동(478)한다. 로그인 실패(잘못된 비밀번호 등)여도 ESM+ 로그인 페이지는 보통 같은 화면에 에러 메시지만 띄우고 예외를 던지지 않으므로, 이 코드는 실패를 성공으로 오인하고 계속 진행한다. `createLoggedInDetailDriver`(292-318)도 동일 — try 블록 내 모든 예외를 뭉뚱그려 "[ESM+] 로그인 실패"로만 던져(316) 로그인 자체의 성공/실패와 후속 단계(요소 못 찾음)의 실패를 구분하지 못한다. 대조: 같은 패키지의 **`EsmplusScraper.loginAndScrapeOrders`(디버그 전용, 아래 참조)는 로그인 후 `currentUrl`(63-64)과 주문 페이지 URL(78-79)을 로깅**하고 iframe 목록을 `querySelectorAll('iframe')`로 열거(86-89)한 뒤 `findElement`를 시도한다 — 즉 진단 도구는 이미 "URL/iframe 존재를 먼저 확인해야 한다"는 패턴을 구현해뒀으나, 실제 동기화 경로(`loginAndCreateDriver`)에는 역이식되지 않았다.
- **(b) `#innerIFrame` 미발견의 코드상 원인 후보(우선순위순, 미확정)**:
  1. **로그인 실패(자격증명 무효/불완전) — 유력.** `loadAndValidateCredential`(77-86)이 `access_key`(masterId)의 `hasText`만 검사하고(82-84) **`secret_key`(password)는 전혀 검증하지 않는다.** password가 빈 문자열이어도 그대로 Selenium까지 전달되어 로그인폼에 빈 값이 입력되고, 로그인은 조용히 실패한 채 (a)의 무검증 흐름을 타고 주문 페이지로 진행 → iframe 없음.
  2. **고정 `Thread.sleep` 타이밍 경합.** 로그인 후 5초(469), 주문 페이지 이동 후 5초(479) 고정 대기 — 네트워크 지연·서버 부하 시 페이지 렌더가 늦으면 `findElement`(482, WebDriverWait 미사용 raw 호출)가 요소 생성 전에 실행돼 NoSuchElement. `idInput`/`esmTab` 탐색(449-457)은 `WebDriverWait`로 감쌌으면서 iframe 탐색(482)만 감싸지 않은 비일관성.
  3. **추가 인증 단계(캡차/2FA/기기인증).** ESM+ 로그인이 신규 IP(서버 168.107.31.154, D-041 배포기록)에서 추가 보안 절차를 요구할 가능성 — 코드로 확인 불가, 라이브 스크린샷/HTML 덤프 필요.
  4. **페이지 DOM 변경.** ESM+가 `#innerIFrame` id를 다른 값으로 바꿨을 가능성 — 코드만으로는 배제 불가.
- **(c) 자격증명 출처(확인)**: `EsmplusOrderSyncService.loadAndValidateCredential:78` — `credentialRepository.findByMarketType(MarketType.GMARKET)`로 **`MarketType.GMARKET` 행 단 하나만** 조회. `EsmplusOrderAdapter.getMarketType()`도 `GMARKET`만 반환하고 `credential.getAccessKey()/getSecretKey()`를 `masterId/password`로 그대로 `EsmplusOrderApiPortImpl.fetchOrders`에 전달한다. 검증은 masterId(accessKey)의 `hasText`뿐(82-84) — secretKey(password) 공백/오류는 Selenium 로그인 단계까지 가서야(그것도 무검증으로) 실패한다.
- **(d) "Auction 저장 에러" 및 G마켓/옥션 분리 UI — 구조적 불일치(확인)**: `Settings.tsx`는 GMARKET(59)·AUCTION(60) 탭을 완전히 독립된 마켓 연동처럼 노출한다 — 각 탭이 별도 `formData`(활성탭 기준 `credentials.find`)를 갖고 `PUT /api/v1/market-credentials/{marketType}`로 **서로 다른 DB row**(`sb_market_credential`, marketType unique)에 저장한다(253-315). AUCTION 탭 안내문(289-290) "옥션도 G마켓과 동일한 ESM+ 플랫폼을 사용합니다. G마켓과 동일한 마스터 ID/비밀번호를 입력하세요"는 사용자에게 별도 입력을 요구하는 것처럼 읽히지만, **백엔드 동기화 경로는 AUCTION 행을 영구히 읽지 않는다**((c) 참조 — GMARKET 하드코딩). 즉 사용자가 AUCTION 탭에 자격증명을 입력·저장해도(저장 API 자체는 marketType에 특별한 검증/제약이 없어 성공함 — 명시적 "저장 에러"를 일으킬 코드 경로는 발견 못함, 추정) 그 값은 어디에도 소비되지 않는다. 응답 파싱 단계의 `siteId==1 → AUCTION`(`parseSingleOrder:665`)은 **로그인 자격증명과 무관하게 API 응답 안에서 마켓을 구분**하는 로직일 뿐 — 사용자가 이해하는 "옥션 자격증명"과는 다른 층위다. **"Auction 저장 에러" 신고는 실제 HTTP 실패가 아니라, 저장은 성공하나 효과가 전혀 없는 것(동기화가 AUCTION 행을 참조하지 않음)에 대한 사용자 오인일 가능성이 높다(추정 — 라이브 재현 없이 코드만으로는 확정 불가).**
- 제안 수정(우선순위순): ①`loadAndValidateCredential`에 secretKey `hasText` 검증 추가(masterId와 동일하게 fast-fail). ②`loginAndCreateDriver`에 로그인 성공 판별 단계 추가 — 로그인 후 URL이 `signin.esmplus.com`에 여전히 머물거나 에러 요소가 있으면 명확한 "ESM+ 로그인 실패(자격증명 확인)" 예외로 즉시 중단(현재의 무검증 진행 제거). ③`#innerIFrame` 탐색을 `WebDriverWait`로 감싸 고정 sleep 경합 제거, 타임아웃 시 페이지 HTML 스냅샷을 로그로 남겨 (b)-3/4 후속 진단 가능하게. ④Settings.tsx AUCTION 탭에 "이 값은 저장되지만 동기화에는 사용되지 않습니다(G마켓 계정 통합 사용)" 명시 경고 또는 AUCTION 탭 자체 제거·GMARKET 탭에 흡수 — UX 정정은 백엔드 구조(단일 계정)를 바꿀지, UI를 바꿀지 업무 결정 필요.
- 리스크·미확인 가정(라이브·자격증명 관련 — 운영 실행 없이 코드레벨까지만 확인): 실제 masterId/password 유효성은 라이브 로그인 시도 없이는 확정 불가(추정만). (b)-3(추가 인증)·(b)-4(DOM 변경)도 코드로 배제 불가 — 라이브 재현(HTML 덤프/스크린샷) 필요. 수정 검증 시 실 ESM+ 계정으로 로그인 성공 여부를 반드시 확인해야 함(가짜 그린 위험 — 코드는 "덜 무모하게 실패"하게만 바뀌고 근본 원인은 라이브에서만 확정됨).
- 수정(2026-07-10, 사이클 12 tdd-fixer): 3스코프 — ①`EsmplusOrderSyncService.loadAndValidateCredential:81-89`(core, 태스크 지시문의 infra 경로는 오기)에 secretKey(비밀번호) `hasText` 검증 추가 → 빈/공백 비밀번호로 Selenium 로그인이 조용히 실패("성공 0건" 위장)하던 것을 스크래핑 이전 fast-fail(`"ESM+ 크레덴셜 불완전: 비밀번호(secret-key) 확인"`). **유력 원인.** ②`EsmplusOrderApiPortImpl.loginAndCreateDriver`: 로그인 클릭+sleep 직후 로그인 성공 판별(WebDriverWait로 `signin.esmplus.com` 이탈 확인, 실패 시 `"ESM+ 로그인 실패(자격증명/추가인증 확인)"` 예외) + `#innerIFrame` 탐색을 raw findElement→`WebDriverWait` 교체(미발견 시 htmlSnapshot 로깅 + `"ESM+ 주문 iframe 없음... 페이지 구조 변경 의심"` 구분 예외). 헬퍼 `safeCurrentUrl`/`pageHtmlSnapshot` 추가. `EsmplusDriverFactory`·원격 그리드·드라이버 생성 방식 무접촉. ③`Settings.tsx` AUCTION 탭 제거·GMARKET 탭에 흡수(라벨 "G마켓·옥션 (ESM+ 단일 로그인)")로 무효과 입력 방지(백엔드 GMARKET 행 소비·스키마 무변경). Red: `MarketCredentialValidationTest` esmplus_emptySecret/blankSecret 2건(수정 전 빈 secretKey가 success 이벤트 발행하던 것 실증) Red→Green. 회귀 조정: `EsmplusOrderSyncTerminalSkipTest.stubCredential`에 getSecretKey 스텁 추가. 게이트: `:infrastructure:test :core:test` BUILD SUCCESSFUL, 프론트 `tsc -p tsconfig.app.json`(Settings 신규0)·`npm run build` EXIT0. **근본해결 미확정 — 라이브 검증 필수**(자격증명 유효성·추가 인증·DOM 변경·로그인 성공판별 휴리스틱은 실 ESM+ 계정 라이브에서만 확정). 수정 요지: `_workspace/fixes/recon_D045.md`.
- 상태: 검증통과(코드)·라이브 근본원인 미확정 (재정합 사이클 — qa PASS 2026-07-10 `_workspace/verify/recon_D045_D050_D051.md`: secretKey 검증·로그인 성공판별·iframe WebDriverWait·Settings 단일화 코드 정합, 기존 `EsmplusOrderSyncTerminalSkipTest` 스텁변경은 D-039 보호 무손상 확인. **iframe 미발견 진짜 원인(자격증명 유효성·추가인증·DOM·로그인판별 휴리스틱)은 실 ESM+ 계정 라이브 검증 전까지 미확정 — 사용자 수동 검증 필수**)
- 진단강화(2026-07-10, 사이클 12 리더): 라이브에서 (a) 로그인 성공판별이 실제로 발화(로그인 페이지 잔류 시 예외) 확인됨 → 이제 실패 **원인**(자격증명 오류 vs 캡차/추가인증 vs 셀렉터 드리프트)을 다음 실행에서 확정하기 위해 로그인 실패 catch 블록에 `pageHtmlSnapshot`(앞2000자) + `loginFailureHint(html)`(캡차/보안문자/recaptcha 흔적 감지 → 신규 IP 추가인증 가능성 안내, 미감지 → 자격증명 오류 가능성 안내)를 로그·예외메시지에 포함. 코드만으로 원인 단정 불가 — 다음 라이브 로그의 힌트/HTML로 확정. 헬퍼 `loginFailureHint` 추가, 로그인·iframe 흐름 로직 무변경.
- 이력: 2026-07-10 발견(사이클 11 심화) → 2026-07-10 수정완료 → 2026-07-10 검증통과(코드, 라이브 미확정) → 2026-07-10 진단강화(사이클 12, 실패원인 확정용 HTML/캡차 힌트 로깅)

### D-046: 쿠팡 주문↔상품 매핑 끊김 — 발행 시 sellerProductId 저장 vs 주문 매칭 시 vendorItemId 조회 불일치 (사용자 신고 (1))

- 심각도: P1 (쿠팡 주문 그리드 상품정보 공백) · 리스크 등급: 표준
- 근본원인: 발행 시 `marketIdentifiers`에 `sellerProductId`만 저장되고 `vendorItemId` write-path 부재. 주문 매칭 `resolveProductId`는 vendorItemId LIKE 조회 → 상시 미스매치 → `OrderLineItem.productId=null`. (원격 origin/main base에도 미해소 — 확인.)
- 수정(재정합 재적용): 주문 응답에 이미 있던 sellerProductId를 `MarketOrderDto`에 실어, `resolveProductId`가 vendorItemId 직접매칭 실패 시 sellerProductId로 `MarketRegistration` 역조회 후 `enrichIdentifier()`로 vendorItemId 보강(최초 동기화 자기치유, 추가 API 호출 없음). 변경: `CoupangOrderSyncService`·`MarketRegistration`·`MarketOrderDto`·`CoupangOrderAdapter` + 테스트 `CoupangOrderProductMappingTest`·`MarketRegistrationEnrichIdentifierTest`.
- 상태: 검증통과 (재정합 — `:core:test :infrastructure:test :api:test --rerun-tasks` BUILD SUCCESSFUL 2026-07-10)
- 이력: 2026-07-10 재정합 사이클 — 구 로컬 브랜치(cycle10-local-20260710) D-041 재적용, **원격 D-041(=마켓 오류표면화)과 충돌 방지 위해 D-046으로 번호 재부여**(코드 인라인 주석·커밋 동기화).

### D-047: 상품관리 그리드 — 마켓별 연동코드 컬럼·바로가기 링크 부재 (사용자 신고 (5))

- 심각도: P2 · 리스크 등급: 표준
- 근본원인: 백엔드 `ProductListResponse.marketRegistrations` 데이터는 있으나 프론트 타입·컬럼 미소비. `getMarketMap` N+1.
- 수정(재정합 재적용): `productApi.ts` 타입 추가, `ProductPage.tsx` 마켓 컬럼 6종+URL 링크(폴백값은 '미확인' 배지), `ProductController.getMarketMap` N+1→`findByProductIdIn` 배치조회. 마켓 URL 패턴 best-guess(스토어/카페24 미생성) — 미해결 이관. 변경: `ProductController`·`MarketRegistrationRepository`·`productApi.ts`·`ProductPage.tsx` + `ProductControllerMarketMapTest`.
- 상태: 검증통과 (재정합 — 게이트 BUILD SUCCESSFUL·프론트 tsc0/build0 2026-07-10)
- 이력: 2026-07-10 재정합 — 구 로컬 D-045 재적용·**D-047 재부여**(원격 D-045=ESM+ iframe과 무관).

### D-048: 상품관리 그리드 — ag-Grid 클라이언트사이드 페이징으로 51번째 이후 미노출 (사용자 신고 (6))

- 심각도: P1 · 리스크 등급: 표준
- 근본원인: `pagination={true}` + `rowModelType` 미지정(clientSide)으로 최초 50건만 보유, 백엔드 서버 페이징 미연결.
- 수정(재정합 재적용): ag-Grid 내장 pagination 비활성 + 하단 antd `Pagination`, `onChange→loadData(page-1,size,keyword)` 서버 재호출(total=totalElements, 검색어 유지). 변경: `ProductPage.tsx`.
- 상태: 검증통과 (재정합 — 프론트 tsc0/build0 2026-07-10)
- 이력: 2026-07-10 재정합 — 구 로컬 D-046 재적용·**D-048 재부여**.

### D-049: 상품 상세모달 소스이미지 크롤 버튼 — 비-iHerb 무음실패 + 마켓 재게시 미배선 (사용자 신고 (7))

- 심각도: P2 · 리스크 등급: 표준
- 근본원인: 크롤러 iHerb 전용(비-iHerb 무음 빈응답), `MarketClient.syncImagesAndHtml`(4마켓 구현 완비)이 호출부 0건.
- 수정(재정합 재적용): (사용자 결정) 비-iHerb 벤더 크롤버튼 비활성+안내. 이미지/HTML 수정 후 `ProductManageUseCase.republishToMarkets`로 연동 마켓 `syncImagesAndHtml` 자동 호출(GMARKET/AUCTION은 클라이언트 부재 스킵, 마켓별 try/catch 격리, 자사DB/성공마켓은 마켓실패와 무관 커밋). 부분실패를 `ImageUploadResponse`(synced/skipped/failed)로 표면화(`message.warning`). 변경: `ProductController`·`ProductManageUseCase`·`ImageUploadResponse`(신규)·`productApi.ts`·`ProductPage.tsx` + `ProductManageUseCaseRepublishTest`·`ProductControllerImageUploadTest`.
- 상태: 검증통과 (재정합 — `:core:test :api:test` BUILD SUCCESSFUL·프론트 tsc0/build0 2026-07-10). ⚠라이브 마켓 쓰기 — 첫 실행 시 부분실패 안내 실측 권장.
- 이력: 2026-07-10 재정합 — 구 로컬 D-047(반려→재수정 포함) 재적용·**D-049 재부여**.

### 후보 기록 (사이클 8 운영 정착 중 관찰)

- **Cafe24 refresh token 만료**: 기동 시 invalid_grant (비치명 — 로그만). Cafe24 개발자센터에서 토큰 재발급 후 sb_market_credential 갱신 필요 (사용자 조치).
- **market-credentials API가 accessKey/secretKey 평문 노출**: 응답에 자격증명 원문 포함. 사용자의 "보안 비중요" 방침상 후보 기록만 (공개망 노출 시 마스킹 필요).
- **nginx 컨테이너 IP 캐시**: sbshop 컨테이너 재생성 시 nginx reload 필요 (`docker exec projects-nginx-1 nginx -s reload`) — 배포 절차에 포함 권장.

### 사이클 10 배포 기록 (2026-07-08)

- **sb_action_log 수동 DDL 적용**: 서버 postgres에 `CREATE TABLE sb_action_log`(엔티티 정합 — verifier-c10 대조) + `idx_action_log_created_at`. `docker exec -i`로 적용(-i 없으면 stdin 미전달 함정).
- **재배포**: git pull(D-041/042/043) → compose up --build(api·frontend) → nginx reload. api 기동 안정.
- **라이브 검증(핵심)**: 쿠팡 동기화 트리거 → 액션 로그에 `STARTED "쿠팡 동기화 요청"` → `FAILED "동기화 실패: 쿠팡 API HTTP 오류: 403 FORBIDDEN — IP 허용목록/자격증명 확인"` 기록 실증. **더 이상 "성공 0건" 위장 없음.** 외부 https app·action-logs API 200.
- **사용자 조치 리마인더**: 쿠팡·11번가·스마트스토어 API 허용목록에 서버 아웃바운드 IP **168.107.31.154** 등록(기존 로컬 PC IP 대체) + 스마트스토어 access-key·11번가 secret-key 자격증명 채우기. 그러면 즉시 정상 적재.
- **후속 후보**: 11번가 result_code≠0(HTTP200+업무오류코드)는 정상0건과 구분 위해 미변경 — 오류코드 사전확보 시 코드별 throw. 쿠팡 서비스 loadAndValidateCredential 빈문자열 검증(일관성, 현재 자격증명 SET이라 무영향).

### 사이클 9 배포 기록 (2026-07-08)

- **R2 자격증명 활성화 (D-020/D-036 연계 완료)**: buying-agent `application.yml`의 R2 키를 서버 `~/projects/.env`에 `CLOUDFLARE_R2_*`로 추가. **핵심 발견**: 서버 `~/projects/docker-compose.yml`(레포와 별개 파일)의 sbshop-api 서비스가 **구 변수명 `R2_ACCESS_KEY_ID` 등**을 쓰고 있어 현재 앱이 읽는 `CLOUDFLARE_R2_*`와 불일치 → `.env`만으론 컨테이너 미전달이었음. 서버 compose sbshop-api `environment`에 `CLOUDFLARE_R2_*` 5줄 매핑 추가(백업: `docker-compose.yml.bak-c9`). 재배포 후 컨테이너 env 주입·부팅 무에러(blank creds 예외 소멸) 확인. **주의: 서버 compose는 git 미추적 — 이 변경은 서버에만 존재**(레포 docker-compose.yml엔 이미 CLOUDFLARE_R2 매핑 있음, 서버가 뒤처져 있었음).
- **worker 동거 확인**: sbshop-api 컨테이너가 api(:8080) + worker(:8081) 둘 다 기동 → sbshop-api 재빌드가 스케줄 동기화(worker) 수정도 함께 반영. 별도 worker 서비스 없음.
- **배포 결과**: git pull(D-022~D-039 전체) → compose up --build(api·frontend) → nginx reload. 외부 `/sbshop-agent` 200, orders/products API 200(실데이터), api·worker 부팅 안정.
- **잔존(사용자 조치)**: Cafe24 refresh token 만료(invalid_grant) — 재배포 후에도 로그 에러(비치명). Cafe24 개발자센터 토큰 재발급 후 `sb_market_credential` 갱신 필요.

### 사이클 9 HTTPS 설정 (2026-07-08, 사용자 요청)

- **증상**: 사용자가 `https://168.107.31.154/sbshop-agent` 접속 불가 신고("서버 다운?"). **진단: 서버 정상**(컨테이너 전부 up, http 200) — nginx가 80만 리슨하고 **443/TLS 미설정**이라 https 연결거부(000)였음. docker-compose는 443 매핑하나 nginx conf에 ssl 블록·인증서 없었음.
- **처리(사용자 결정: 자체서명)**: 서버 `~/projects/nginx/conf.d/`에 자체서명 인증서 생성(`selfsigned.crt/.key`, CN·SAN=`IP:168.107.31.154`, 825일). `default.conf`(백업 `default.conf.bak-c9`) server 블록에 `listen 443 ssl` + ssl_certificate 2줄 추가(80·443 동일 location 서빙). `nginx -t` 통과 후 reload. 검증: https 200(루트·products API), http 무회귀 200, can-agent 200.
- **주의**: 자체서명이라 브라우저가 "연결이 비공개가 아닙니다/주의 요함" 경고 → 사용자가 "고급 → 계속 진행" 클릭 필요(기능은 정상). 도메인 확보 시 Let's Encrypt로 정식 인증서 전환 가능. 이 변경도 서버 nginx conf(git 미추적)에만 존재.

---

## 사이클 11 (D-045 근본원인 심화 + 액션로그 UX 잔여요구 진단, 2026-07-10, defect-scout — 코드 무수정)

> 컨텍스트: 원격 origin/main이 2026-07-08 사이클 10(D-041~D-045: 동기화 오류표면화·액션로그·ESM+ Selenium 인프라)을 배포한 위에서, 사용자 잔여 신고 3건 진단. 빌드 베이스라인 확인: `cd backend && ./gradlew compileJava compileTestJava` **BUILD SUCCESSFUL**(경고 0, P0 없음) — 정적 진단으로 진행.

D-045(위 항목)를 근본원인·수정방향으로 심화 갱신함(상태 후보→발견). 아래는 신규 2건.

### D-050: 활동 로그 actionType/marketType가 Enum 원문(영문 코드)으로 노출

- 심각도: P3 (품질/가독성 — 기능 불능 아님, 비개발자 사용자 판독 곤란) · 리스크 등급: 경량
- 위치: `frontend/src/pages/ProcessStatusPage.tsx:83`(`{ title: '액션', dataIndex: 'actionType', width: 180 }` — render 없음, 원문 그대로 렌더), `:84-85`(`marketType`도 `v || '-'`만 적용, 라벨화 없음)
- 증상: 활동 로그 테이블 "액션" 컬럼에 `COUPANG_SYNC`/`SMART_STORE_SYNC`/`ELEVEN_STREET_SYNC`/`GMARKET_SYNC` 같은 내부 코드가 그대로 표시되고, "마켓" 컬럼도 `COUPANG`/`SMART_STORE`/`ELEVEN_STREET`/`GMARKET` 원문이 표시됨.
- 원인(확인): 백엔드 `ActionLog` 엔티티(`backend/core/.../domain/actionlog/ActionLog.java:29-34`)는 `actionType`·`marketType`을 **Java enum이 아닌 순수 `String` 컬럼**으로 저장한다(`action_type varchar(50)`, `market_type varchar(30)`). `actionType` 값은 실제로는 자유문자열이지만 관례상 `{MarketType.name()}_SYNC` 패턴으로 생성됨 — 확인된 발생처: `ActionLogSyncListener.java:24-25`(`String actionType = marketType + "_SYNC"`, 동기화 성공/실패 이벤트) + `OrderSyncController.java:55,79,103,123`(STARTED 기록 시 하드코딩 리터럴 `"COUPANG_SYNC"`/`"SMART_STORE_SYNC"`/`"ELEVEN_STREET_SYNC"`/`"GMARKET_SYNC"`, marketType은 각각 `"COUPANG"`/`"SMART_STORE"`/`"ELEVEN_STREET"`/`"GMARKET"`). `ActionLogResponse.from()`(`backend/api/.../dto/actionlog/ActionLogResponse.java:17-25`)도 문자열을 그대로 통과시킬 뿐 라벨 변환 없음. **백엔드에 CommonCode/라벨 테이블·API 없음**(레포 전체에서 "라벨"/"CommonCode" 관련 인프라 grep 결과 전무) — 이 프로젝트의 기존 관례는 프론트 로컬 맵이다: `frontend/src/pages/OrderGrid.tsx:416-421`의 `marketLabels: Record<string,string>`(`COUPANG: '쿠팡'` 등, `:524-525`에서 토스트 메시지에 사용)가 이미 동일한 목적의 로컬 매핑 선례로 존재.
- 제안 수정 방향: 백엔드 변경 불필요(actionType이 자유문자열 관례라 enum화는 별도 리스크 있는 구조 변경) — `ProcessStatusPage.tsx`에 `OrderGrid.tsx:416` 선례를 따라 로컬 `actionTypeLabels`/`marketTypeLabels: Record<string,string>` 맵 추가 후 `render`로 라벨 치환(미매핑 값은 원문 fallback, 신규 마켓 추가 시 자동 깨지지 않게). actionType은 `{marketLabel}_SYNC` 관례가 안정적이므로 marketTypeLabels를 먼저 두고 `actionType.replace('_SYNC','')`로 마켓코드 추출 → 라벨 조합("쿠팡 동기화") 방식도 가능(단, 향후 SYNC 외 actionType 추가 시 깨짐 — 명시적 actionTypeLabels 맵이 더 안전). 두 맵을 `OrderGrid.tsx`와 공유 모듈(`src/constants/marketLabels.ts` 등)로 추출하면 중복도 해소.
- 재현: 코드 경로 추적(위) — 브라우저 재현은 액션 로그 1건 이상 존재 시 `/process-status` 진행현황 페이지에서 즉시 육안 확인 가능(런타임 실행 없이 코드상 100% 확정, ellipsis/render 부재는 정적 사실).
- 상태: 검증통과 (재정합 사이클 — qa PASS 2026-07-10 `_workspace/verify/recon_D045_D050_D051.md`). `ProcessStatusPage.tsx`에 `marketTypeLabels` 맵(OrderGrid.tsx:416 이식) + `renderMarketType`/`renderActionType`(`_SYNC` 접미 → `{마켓라벨} 동기화` 조합, 미매칭 원문 폴백) 추가, actionType/marketType 컬럼에 render 연결. 게이트: tsc -p tsconfig.app.json 신규 에러 0(OrderGrid 4건 베이스라인) + npm run build EXIT 0. 요약: `_workspace/fixes/recon_D050_D051.md`.

### D-051: 활동 로그 message 컬럼이 ellipsis 잘림 + 전체보기 부재

- 심각도: P3 (품질 — 정보 손실 아님, 확인 곤란) · 리스크 등급: 경량
- 위치: `frontend/src/pages/ProcessStatusPage.tsx:88`(`{ title: '메시지', dataIndex: 'message', ellipsis: true }` — Modal/팝오버 없음, 행 클릭 핸들러 없음)
- 증상: "메시지" 컬럼이 antd `ellipsis: true`로 컬럼 폭(고정폭 미지정 → 나머지 flex 공간)에서 말줄임 처리되고, 전체 텍스트를 볼 방법(모달/팝오버/툴팁 expand)이 없음.
- 원인(확인): 백엔드가 저장하는 message는 **JSON이 아닌 순수 한글/영문 자유 텍스트**다. `ActionLog.java:43`(`@Column(name="message", length=1000)`, 주석 "예: '동기화 요청', '동기화 실패: 403 ...'`) — 실제 값 확인: `ActionLogSyncListener.java:28`(성공 시 `"동기화 성공"` 고정), `:31-32`(실패 시 `"동기화 실패: " + reason`, reason은 `SyncCompletedEvent.getErrorMessage()` — D-041/D-043에서 확인된 예로 `"쿠팡 API HTTP 오류: 403 FORBIDDEN — IP 허용목록/자격증명 확인"` 등 체이닝된 예외 메시지 텍스트, ESM+ 경로는 `EsmplusOrderApiPortImpl.fetchOrders:53`에서 `"ESM+ 주문 조회 실패: " + e.getMessage()`처럼 중첩 예외 메시지가 누적돼 길어질 수 있음). `OrderSyncController.java:55` 등 STARTED 메시지는 짧은 고정 문구("쿠팡 동기화 요청"). 컬럼 자체는 DB에서 최대 1000자까지 저장 가능하므로, 중첩 예외(RuntimeException wrapping)가 쌓이는 실패 케이스는 화면 폭보다 훨씬 길어질 소지가 큼.
- 제안 수정 방향: message가 JSON이 아니므로 별도 JSON 포맷터는 불필요 — 행 클릭(`onRow`) 또는 메시지 셀 클릭 시 antd `Modal.info`/커스텀 모달로 `white-space: pre-wrap` 처리된 전체 텍스트 표시(길이 제한 없이). 실패 메시지가 향후 구조화될 가능성(예: 스택트레이스 포함) 대비, 모달에 `<pre>` 또는 코드블록 스타일 적용 권장(가독성 확보, JSON 파싱 시도는 불필요 — 실패해도 원문 그대로 표시하는 방어적 렌더).
- 재현: 코드 경로 추적(위) — `ellipsis: true` 단독 사용은 antd 기본 동작상 hover 시 title 툴팁만 제공하고 클릭 확장 기능은 없음(정적 사실, antd Table 컬럼 스펙 확인). 브라우저 재현은 message 길이가 컬럼 폭을 넘는 액션 로그(예: D-041/D-043류 403 오류 메시지) 존재 시 즉시 확인 가능.
- 상태: 검증통과 (재정합 사이클 — qa PASS 2026-07-10 `_workspace/verify/recon_D045_D050_D051.md`). `ProcessStatusPage.tsx` message 컬럼에 `ellipsis: true` 유지 + 클릭 가능한 span 진입점(render) 추가, `messageModal` 상태 + antd `Modal`(`<pre>` white-space:pre-wrap/word-break/max-height:60vh)로 전체 텍스트 표시. import에 `Modal` 추가. JSON 파서 불필요(순수 텍스트, 방어적 원문 렌더). 게이트: tsc 신규 에러 0 + npm run build EXIT 0(modal 청크 번들 확인). 요약: `_workspace/fixes/recon_D050_D051.md`.

### D-052: 상품 그리드 마켓별 연동코드가 쿠팡만 표시·나머지 '미확인' (사용자 신고 후속)

- 심각도: P1 (스토어·11번가·카페24·ESM+ 연동코드 상시 '미확인' — 마켓 상품 바로가기 무력화) · 리스크 등급: 표준
- 위치: `ProductController.buildMarketMap:172`(`reg.extractVendorItemId()`), `MarketRegistration.extractVendorItemId:99`(쿠팡 전용 `vendorItemId` 키만 읽음), `frontend/src/pages/ProductPage.tsx:40-71`(`renderMarketCell` — code===productId 폴백 시 '미확인' 배지)
- 근본원인: `buildMarketMap`이 **모든 마켓**의 코드를 쿠팡 전용 `extractVendorItemId()`(marketIdentifiers의 `vendorItemId` 키)로 읽었다. 스마트스토어(`originProductNo`)·11번가(`elevenstId`)·카페24(`product_no`)·ESM+(`goodsNo`)는 해당 키가 없어 항상 null→`productId` 폴백→프론트에서 code===row.id로 '미확인' 배지. 스크린샷 실증(쿠팡 코드 정상, 스토어/11번가/카페24 전부 '미확인'). 각 마켓 클라이언트 저장 키 확인: Coupang `sellerProductId`/`vendorItemId`, Elevenst `elevenstId`, Smartstore `originProductNo`, Cafe24 `product_no`/`product_code`.
- 수정(2026-07-10, 사이클 12 리더): `MarketRegistration.extractMarketCode()` 신설 — marketType별 실제 키 분기(COUPANG=vendorItemId→sellerProductId, SMART_STORE=originProductNo→channelProductNo, ELEVEN_STREET=elevenstId→prdNo, CAFE24=product_no→product_code, GMARKET/AUCTION=goodsNo→itemNo→goodsCode). `buildMarketMap`이 이를 사용(코드 없으면 기존대로 productId 폴백→'미확인'). 프론트 '미확인' 툴팁을 D-046 한정 문구→"해당 마켓 연동정보에 상품코드 키 없음"으로 일반화. Red: `MarketRegistrationExtractMarketCodeTest`(스토어 코드가 vendorItemId 조회로는 null이나 extractMarketCode로는 OP123 반환하는 회귀 재현 포함). 게이트: `:core:test :api:test :infrastructure:test --rerun-tasks` BUILD SUCCESSFUL, 프론트 tsc(신규0, 기존 D-007 OrderGrid 4건만)·build EXIT0.
- 미확정 가정: ESM+(GMARKET/AUCTION)의 실제 저장 키가 `goodsNo`인지는 상품 발행 write-path 미확인(라이브 데이터로 확인 필요) — 후보 키 3종(goodsNo/itemNo/goodsCode) 순차 조회로 방어. 스토어/11번가/카페24는 클라이언트 저장 키 코드상 확인.
- 라이브 발견(2026-07-10, 사용자 협조 DB 조회): `sb_market_registration` 마켓별 건수 = CAFE24 3185·SMART_STORE 3183·ELEVEN_STREET 2286·COUPANG 1261, **GMARKET·AUCTION 0건**. 전체 11개 테이블 중 마켓코드 저장처는 이 테이블뿐(레거시 매핑 테이블 없음), `sb_product`에도 마켓코드 컬럼 없음. 코드상 ESM+ 상품 연동 경로 부재(발행 클라이언트 4개뿐, ESM+는 주문 스크래핑 전용). **결론: 지마켓/옥션은 "매핑 실패"가 아니라 "데이터 부재"** — 사용자 가설(카페24 호스팅 원본 market_registration 테이블 마이그레이션 누락)이 유력, 원본 확인 후 임포트 스크립트 필요. D-052 코드수정은 데이터가 존재하는 4마켓엔 유효.
- 상태: 수정완료(검증대기 — 리더 게이트 통과. 4마켓 라이브 표시 확인 대기 / GMARKET·AUCTION은 데이터 부재로 별도 임포트 트랙)
- 이력: 2026-07-10 발견·수정(사이클 12) → 2026-07-10 라이브 진단(지마켓/옥션 데이터 부재 확정)

### 사이클 11 요약 (defect-scout)

- 신규 결함: 2건(D-050·D-051, 둘 다 P3/경량). 기존 D-045 심화 갱신 1건(상태 후보→발견, 근본원인 4갈래 원인후보·자격증명 경로·GMARKET/AUCTION 구조 불일치 확정).
- 심각도 분포(사이클 11 신규+심화분): P1 1건(D-045, 심화) · P3 2건(D-050·D-051).
- 최우선 수정 권고 3건:
  1. **D-045 (P1)** — ESM+ 동기화 여전히 불가한 상태의 핵심 원인. 우선 `loadAndValidateCredential`에 secretKey 검증 추가(비용 최소, 즉시 가능) + 로그인 성공 판별 단계 추가(라이브 확정 전제). 코드 수정만으로 근본 해결을 단정할 수 없음 — 실 계정 라이브 로그인 검증 필수.
  2. **D-050 (P3)** — 로컬 라벨 맵 추가만으로 해소 가능한 저리스크·저비용 개선(선례 `OrderGrid.tsx:416` 존재), 사용자 체감 개선 크기 대비 수정 비용 낮음.
  3. **D-051 (P3)** — 모달 추가만으로 해소, D-050과 동일 파일(`ProcessStatusPage.tsx`) 동시 처리 시 효율적.
- ESM+(D-045) 관련 미확인 가정 재강조: 실 자격증명 유효성·추가 인증 단계·ESM+ 페이지 DOM 변경 여부는 코드 정적 분석만으로 확정 불가 — 수정 후 반드시 라이브(실 ESM+ 계정) 검증 필요, 가짜 그린 위험 큼.

---

## 사이클 13 (사용자 신고 4건 — 시간대·상품정보·호버성능·상품그리드 UX, 2026-07-11, 리더 직접)

> 컨텍스트: 사용자 UI 신고 4건을 병렬 진단(Explore ×2: 주문-상품 매핑, 시간대) 후 리더가 프론트 전용으로 수정. 전 항목 프론트엔드 변경(백엔드 무변경) → 회귀 위험 최소, 경량 등급. 게이트: `tsc -p tsconfig.app.json` 신규 에러 0(기존 OrderGrid ORDER/PRODUCT_COLUMNS 4건도 함께 해소해 **완전 clean**) + `npm run build` EXIT 0.

### D-053: 진행 현황 시간이 UTC(미국시간처럼)로 표시

- 심각도: P2 (판독 오류 — 기능 정상) · 리스크 등급: 경량
- 위치: `frontend/src/pages/ProcessStatusPage.tsx:117`(활동로그 createdAt), `:112`(배치상태 startedAt, render 부재로 원문 노출)
- 근본원인: 백엔드 시간 필드가 모두 zone 없는 `LocalDateTime`(`BaseEntity.createdAt`, `ProcessStatus.startedAt` 등) + 컨테이너 TZ=UTC → JSON에 zone 정보 없이 `"2026-07-09T04:22:00"` 형태로 직렬화. 프론트 `new Date(v).toLocaleString('ko-KR')`가 이를 (브라우저 로컬 존으로) 파싱하지만 값 자체가 UTC 벽시계라 9시간 이른 시각 표시. **주의**: `spring.jackson.time-zone`은 `LocalDateTime` 직렬화에 영향 없음(존 없는 타입) — 백엔드 설정만으론 미해결.
- 수정: `frontend/src/utils/datetime.ts` 신설 — `toKstDate()`(존 없는 문자열을 UTC로 간주 `+Z`), `formatKst()`(ko-KR·Asia/Seoul 강제). ProcessStatusPage의 createdAt·startedAt 컬럼 render에 `formatKst` 연결. 기존/신규 데이터 모두 일괄 교정, 브라우저 존과 무관.
- 범위 판단: **통합주문관리 주문일시(orderDate)는 의도적으로 미변경** — Coupang 등 한국 마켓 API가 KST를 반환하고 `CoupangOrderAdapter.java:198`에서 `LocalDateTime.parse`로 naive KST 저장하므로 이미 정확. UTC 변환 적용 시 오히려 +9h 어긋남(서버감사시간 UTC와 마켓주문시간 KST의 혼재 의미 구분).
- 상태: 검증통과 (리더 게이트: tsc clean + build EXIT0)

### D-054: 통합주문관리 상품정보(상품명/영문명) 미표시

- 심각도: P1 (상품 식별 정보 상시 공란) · 리스크 등급: 경량
- 위치: `frontend/src/api/orderApi.ts:51-57`(ProductDto), `frontend/src/pages/OrderGrid.tsx:958,962,956`
- 근본원인: 상품코드(`product.sbCode`)는 정상 표시되므로 `product`는 null이 아님(백엔드 조인 정상). 실제 원인은 **프론트 타입/접근 경로 오류** — 백엔드 `Product` 엔티티의 `productName`/`originalName`은 **flat String**인데 `ProductDto`가 `productName: {productName, originalName}` 중첩 객체로 잘못 정의됨. OrderGrid가 `product.productName.productName`으로 접근해 `undefined`. 소싱 URL도 실제 필드는 `SourcingInfo.sourceUrl`인데 `sourcingInfo.url`로 접근.
- 수정: `ProductDto`를 flat(`productName?: string; originalName?: string; sourcingInfo?: {sourceUrl?: string}`)으로 교정. OrderGrid 상품정보 셀 접근 경로를 `product.productName`(등록상품명 행1)·`product.originalName`(영문명 행2)·`sourcingInfo.sourceUrl`(링크)로 수정.
- 참고(별개 트랙): 동기화 시 MarketRegistration 매핑 실패로 `OrderLineItem.productId`가 null이 되는 케이스(`CoupangOrderSyncService.resolveProductId`)는 데이터 레벨 이슈로 별도 — 본 증상(상품코드는 뜨는데 이름만 공란)의 원인은 아님.
- 상태: 검증통과 (리더 게이트: tsc clean + build EXIT0)

### D-055: 통합주문관리 행 호버 음영 지연

- 심각도: P2 (체감 성능 — 마우스 호버 시 회색 음영 지연) · 리스크 등급: 경량
- 위치: `frontend/src/pages/OrderGrid.tsx:387`(hoveredOrderId state), `:1282`(행별 isHovered/bgCol)
- 근본원인: 호버를 React state(`hoveredOrderId`)로 처리 → 매 `onMouseEnter`마다 전체 그리드(주문 500건×최대 3행 = 수백~수천 행) 리렌더·전 셀 `flexRender` 재실행. 이 리렌더 비용이 음영 표시 지연으로 체감됨. 추가로 TableRow 내장 per-row 핸들러가 `background=transparent`로 리셋하는 부작용.
- 수정: `hoveredOrderId` state 제거 → ref + 이벤트 위임(DOM 클래스 토글) 방식. 스크롤 컨테이너에 `onMouseOver`(위임)·`onMouseLeave` 부착, 다른 주문 그룹 진입 시에만 `querySelectorAll('tr[data-order-id="..."]')`로 `.og-row-hover` 클래스 토글(같은 그룹이면 early-return → per-pixel DOM 조회 없음). CSS `.og-row-hover > td { background:#f1f5f9 !important }`로 frozen 셀 인라인 배경까지 즉시 덮음. 각 TableRow에 `data-order-id` 부여, 내장 핸들러는 `onMouseEnter/Leave={undefined}`로 무력화. 리렌더 완전 제거 → 즉시 음영.
- 상태: 검증통과 (리더 게이트: tsc clean + build EXIT0)

### D-056: 상품관리 그리드 — G마켓/옥션 컬럼 제거 + 디자인 개선 + 가격/재고 버튼

- 심각도: P3 (UX/디자인) · 리스크 등급: 경량
- 위치: `frontend/src/pages/ProductPage.tsx`
- 요구/원인: (1) G마켓·옥션은 마켓 상품코드가 없음(D-052 라이브 확정: `sb_market_registration` GMARKET·AUCTION 0건) → 상시 공란 컬럼. (2) ag-grid 오버라이드가 `.ag-theme-alpine`에만 적용(`index.css:191`)돼 실제 사용 테마 `.ag-theme-quartz`엔 미적용 → 기본 스타일로 촌스러움. (3) `가격/재고` 버튼이 무스타일 raw `<button>`.
- 수정: (1) `MARKET_COLUMNS`에서 GMARKET·AUCTION 제거(쿠팡·스토어·11번가·카페24만). (2) `.sb-product-grid`로 스코프한 quartz 토큰 오버라이드(폰트·헤더·행 hover·테두리·라운드·accent) + 헤더/툴바 카드화 + 제목·건수 표시 + 이미지 셀 라운드/플레이스홀더 + 판매가/재고 우측정렬. (3) `가격/재고`를 `.sb-pricestock-btn`(primary 아웃라인→hover 채움)으로 교체. 타 그리드 영향 없도록 클래스 스코프.
- 부수: 기존 `OrderGrid.tsx`의 `ORDER_COLUMNS`/`PRODUCT_COLUMNS` 암묵 any[] 타입 에러 4건(베이스라인)도 `: string[]` 명시로 해소 → 타입 게이트 완전 clean.
- 상태: 검증통과 (리더 게이트: tsc clean + build EXIT0)

### 사이클 13 요약

- 신규 결함 4건(D-053 P2 · D-054 P1 · D-055 P2 · D-056 P3), 전부 프론트 전용·경량. 백엔드 무변경 → 회귀 게이트는 프론트 tsc+build로 충분(전 항목 통과, 타입 게이트 완전 clean 달성).
- 핵심 판단: 시간대 수정 범위를 서버감사시간(UTC)에 국한하고 마켓주문시간(KST)은 제외 — 의미 혼재 방지. 호버는 React state→DOM 위임으로 리렌더 제거.

---

## 사이클 14 (재고현황 새로고침 무반응 신고, 2026-07-11, 리더 직접 — 부분 재실행)

### D-057: 통합주문관리 재고현황 새로고침 버튼 무반응(체감)

- 심각도: P2 (기능 오작동 아님 — 피드백 부재로 무동작 체감) · 리스크 등급: 경량
- 위치: `frontend/src/pages/OrderGrid.tsx:653` `handleSyncProductStock`
- 진단(경계면 전 구간 확인): 클릭 발화 정상(통관정보 새로고침 `:916`과 완전 동일한 SVG onClick 패턴, 통관은 정상 동작). 엔드포인트 정상(`POST /api/v1/products/sync/stock` 라이브 200 `{success:true}`, `ProductSyncController:28` 즉시 성공 반환 후 `new Thread`로 백그라운드 크롤). 대상 선정 정상(`findProductIdsByShippingStatus(NEW/PREPARING)` — 그리드의 "구매준비"=`ShippingStatus.PREPARING`이므로 대상 비어있지 않음).
- 근본원인: 순수 프론트 UX 결함. 재고 동기화는 마켓 동기화(SSE `SYNC_COMPLETED`로 완료 시 갱신)와 달리 **완료 신호가 없는 백그라운드 크롤(수 초~수 분)**인데, 핸들러가 (1) 성공 토스트 없이 (2) 고정 3초 후 refetch만 수행 → 크롤 미완 상태로 refetch돼 재고 값 무변화 + 명시적 피드백 없음 → 사용자에겐 "눌러도 반응 없음"으로 체감.
- 수정(2026-07-11): `handleSyncProductStock`에 ① 시작 즉시 `toast.info`("재고 동기화를 시작했습니다 …") 명시 피드백, ② 3초 후 refetch+오버레이 해제(기존) 유지, ③ 15초 지연 refetch 추가로 느린 크롤 결과 반영. 오버레이 장시간 고착(그리드 블로킹) 회피. 백엔드 무변경.
- 상태: 검증통과 (리더 게이트: tsc -p tsconfig.app.json clean + npm run build EXIT0)
- 미해결(후속): 재고 크롤 완료 이벤트가 없어 갱신 타이밍은 여전히 추정치(15초). 근본 해결은 재고 동기화에도 마켓 동기화처럼 완료 SSE/폴링(배치 진행상태 API 연동)을 붙이는 것 — 별도 개선 트랙(P3).

---

## 사이클 15 (푸시 전 전수 QA — 대시보드/ETC/마켓전파, 2026-07-11, 리더 직접 + 5에이전트 병렬조사)

> 사용자 요청: 모든 메뉴·버튼·액션 전수 조사 후 수정·푸시. 5개 도메인 조사 에이전트(대시보드/OrderGrid/이메일자동추적/마켓전파/기타페이지) 병렬 투입 → 리더가 상충·핵심 재확인. 산출: `docs/normalize/qa-checklist-20260711.md`(전수 체크리스트). 안전 결함 2건 수정(D-058·D-059), 중대 마켓전파 갭 2건 보고(D-060·D-061).

### D-058: 배송정보 미입력 시 택배사가 'ETC'로 표시

- 심각도: P2 (오표기 — 미입력이 '기타'로 보임) · 리스크 등급: 경량
- 위치: `backend/core/.../domain/order/enums/ShippingCarrier.java:27` `fromMarketCode`
- 근본원인: `code == null`이면 null 반환하나 **빈 문자열("")/공백은 switch default 분기→ETC("기타")**로 매핑. 마켓(쿠팡/스마트스토어 어댑터가 `fromMarketCode(deliveryCompanyName/Code)` 호출)이 미배송 주문에 빈 택배사를 주면 ETC가 저장돼 그리드에 "ETC" 표시. 그리드 렌더 자체는 빈 값→'-'로 정상(`OrderGrid.tsx:1061`)이므로, 원인은 저장된 값이 실제 'ETC'라는 것.
- 수정(2026-07-11): `if (code == null || code.isBlank()) return null;` — 빈/공백도 '택배사 없음'(null)으로 처리. 미배송이면 빈칸(`-`). 회귀 테스트 `ShippingCarrierTest`(빈문자열→null, DHL→ETC, 알려진 코드 매핑) 추가.
- 상태: 검증통과 (`:core:test` ShippingCarrierTest PASS, 프론트 무변경)
- 참고: 이미 저장된 기존 'ETC' 데이터는 이 수정으로 자동 교정되지 않음(신규 동기화분부터 적용). 필요 시 일괄 UPDATE 별도.

### D-059: 대시보드 지표가 하드코딩 0 (실데이터 미연동)

- 심각도: P2 (핵심 현황 화면 무기능) · 리스크 등급: 표준
- 위치: `frontend/src/pages/Dashboard.tsx`(전체 하드코딩), 백엔드 통관필터 부재
- 근본원인: Dashboard.tsx가 useState/useEffect/fetch 전무 — 3개 카드 모두 `0` 리터럴. '통관 오류/대기'는 `OrderSearchCondition`에 통관상태 필터가 없어 집계 불가였음.
- 수정(2026-07-11): (백엔드) `OrderSearchCondition`에 `List<CustomsStatus> customsStatuses` 추가(쿼리파라미터 자동 바인딩), `OrderRepositoryImpl`에 `customsStatusIn()`(order.customsData.customsStatus.in) 헬퍼를 content·count 쿼리 where에 추가. (프론트) `orderApi.fetchOrderCount(filters)` 경량 카운트 헬퍼(size=1, totalElements) 추가, Dashboard를 7개 지표(전체·미발주·구매준비·배송진행중·배송완료·통관오류대기·배송처리대기)로 재구성 + 병렬 조회 + 새로고침 버튼.
- 상태: 검증통과 (`:infrastructure:test`·`:api:test` BUILD SUCCESSFUL, 프론트 tsc clean + build EXIT0)

### D-060: 상품 가격/재고 수정이 어느 마켓에도 동기화되지 않음 (호출 경로 부재)

- 심각도: P1 (사용자 기대 기능 미동작 — item 4) · 리스크 등급: **중대(마켓 API 계약·다도메인)** → 요승인
- 위치: `ProductManageUseCase.updatePriceStock:42`(DB만 저장), `BatchPriceStockService`(3개 메서드 모두 DB만), MarketClient.syncPriceAndStock(마켓별 구현 편차)
- 근본원인: 단건/배치 가격재고 수정 경로 어디에서도 `MarketClient.syncPriceAndStock()`를 호출하지 않음. 게다가 마켓별 구현도 편차: 스마트스토어·11번가는 실제 API 호출, **쿠팡·카페24는 로컬 Map만 패치(실 API 호출 없음)**, G마켓/옥션은 MarketClient 자체 부재.
- 제안: (1) `updatePriceStock`/배치 이후 상품의 MarketRegistration을 순회하며 `syncPriceAndStock` 호출하는 경로 신설, (2) 쿠팡·카페24 클라이언트의 실 API 호출 구현, (3) 실패 표면화(부분 실패 마켓 반환). 마켓 API 계약을 실제로 건드리므로 사용자 승인 + 라이브 검증 필수.
- 상태: 발견(보고) — 미수정. 중대 등급이라 별도 배치·승인 후 진행.

### D-061: 배송 마켓 전파 갭 (배송정보 수정 미전파 + ESM+/카페24 미구현)

- 심각도: P1~P2 · 리스크 등급: **중대(마켓 API 계약)** → 요승인
- 위치: `OrderController.updateShippingInfo`(DB만), `EsmplusOrderAdapter.shipOrder:71`(log.warn 스텁), Cafe24 OrderAdapter 부재
- 근본원인: (A) 배송정보 "수정"(updateShippingInfo)은 DB만 갱신하고 마켓 미전파 — 단, 배송"처리"(shipOrders)와 이메일 자동추적 경로는 쿠팡·스토어·11번가 3마켓에 송장 전파함. (B) ESM+(G마켓/옥션)은 shipOrder가 로그만, 카페24는 OrderAdapter 자체가 없어 배송처리 시 예외.
- 제안: ESM+/카페24 배송 어댑터 구현, updateShippingInfo에도 (정책상 필요 시) 마켓 전파 연결. 중대 등급 — 승인·라이브 검증 필수.
- 상태: 발견(보고) — 미수정.

### 사이클 15 요약

- 조사: 5에이전트 병렬 전수 감사, 전수 체크리스트 산출. OrderGrid 22액션·기타 페이지 대부분 계약 정상(✅). iHerb 이메일 자동추적 체인 배선·가동 확인(스케줄러 활성 — 초기 FAIL 오판 정정).
- 수정: D-058(ETC·경량), D-059(대시보드·표준) — 게이트 통과.
- 보고(요승인·중대): D-060(가격재고 마켓 미동기), D-061(배송 마켓 전파 갭). 프론트 죽은코드 인벤토리(P3).
- 다음 배치 권고: D-060 우선(사용자 명시 기대) — 승인 후 마켓별 syncPriceAndStock 호출 경로 신설 + 쿠팡/카페24 실 API 구현 + 라이브 검증.

---

## 사이클 16 (D-060 수정 — 가격/재고 마켓 동기화 배선 + 쿠팡/카페24 실 API, 2026-07-11, 리더 직접, 사용자 승인)

### D-060: 상품 가격/재고 → 마켓 동기화 (수정 완료)

- 상태 전이: 발견(보고·요승인) → **검증통과(수정)** — 사용자 "승인"
- 리스크 등급: 중대(마켓 API 계약·다도메인). 사용자 승인 취득. 게이트: 회귀 통과 + 신규 TDD.
- 수정 내역:
  1. **배선(핵심)**: `ProductManageUseCase.updatePriceStock`가 DB 저장 후 `syncPriceStockToMarkets` 호출 — 상품의 MarketRegistration을 순회하며 `MarketClient.syncPriceAndStock(marketItemId, rawData, price.intValue(), stock)` 실행. 이미지 재게시(`republishToMarkets`, D-049)와 동일 규율: 클라이언트 없는 마켓(GMARKET/AUCTION) 스킵, 마켓별 try로 부분 실패 수집(롤백 없음). marketItemId는 D-052 `extractMarketCode()`(마켓별 코드) 사용. 반환 `MarketRepublishResult`(synced/skipped/failed).
  2. **쿠팡 실 API**: `CoupangMarketClient.syncPriceAndStock`가 로컬 Map만 패치하던 것을 `syncImagesAndHtml`과 동일 패턴(전체 seller-product 페이로드 PUT `/v2/providers/seller_api/apis/api/v1/marketplace/seller-products`)으로 실제 반영. items 없으면 예외 전파(실패 표면화).
  3. **카페24 실 API**: `Cafe24MarketClient.syncPriceAndStock`가 로컬 Map만 패치하던 것을 `publish`의 price/supply_quantity 필드 + `syncImagesAndHtml`의 `PUT /admin/products/{id}` 패턴으로 실제 반영.
  4. **컨트롤러/프론트**: `PUT /products/{id}/price-stock`가 `MarketRepublishResult` 반환. ProductPage가 마켓별 성공/실패를 토스트로 표면화(자사 저장 성공해도 마켓 반영 실패 조용히 삼키지 않음).
  5. **테스트**: `ProductManageUseCasePriceStockSyncTest`(마켓 순회 호출·스킵·부분실패 수집 3케이스). 게이트: `:core:test` 신규 3 PASS(기존 flaky SmartStore 1건 제외), `:infrastructure:test`·`:api:test` BUILD SUCCESSFUL, 전체 compileJava SUCCESSFUL, 프론트 tsc clean + build EXIT0.
- 실동작 마켓: 스마트스토어·11번가는 기존 실 API 구현 → 배선으로 즉시 반영. 쿠팡·카페24는 이번에 실 API 추가. GMARKET/AUCTION은 클라이언트 부재 → 스킵.
- **라이브 미검증(중요)**: 실 마켓 API 응답은 코드 정합으로만 확인, 실제 write 성공은 미검증. 배포는 안전(버튼 클릭 시에만 해당 상품 write, 자동 대량변경 없음) — 첫 상품 1건 클릭이 곧 스모크 테스트. 마켓별 실패는 토스트로 표면화됨.
- 잔여(후속): **배치 경로**(`BatchPriceStockService` 3메서드)는 여전히 DB만 — 대량 라이브 write는 단건 검증 후 별도 배선 권고. 카페24 재고는 product-level `supply_quantity`(publish 관례) 사용 — variant 재고 정확 반영은 라이브 확인 필요. D-061(배송 마켓 전파)은 미착수.

### 사이클 16 요약
- D-060 수정 완료(승인·게이트 통과). 단건 가격/재고 저장이 연동 마켓(스토어·11번가·쿠팡·카페24)에 반영되고 결과가 표면화됨.
- 다음: 단건 라이브 스모크(상품 1건) → 이상 없으면 배치 경로 배선 + D-061(배송 전파) 검토.

### D-060 라이브 검증 (2026-07-11, 리더 직접 — 무인증 라이브 환경, 사용자 허가)

- 방법: 실 서버 `PUT /products/1/price-stock`에 **현재값(40700/500) 그대로** 저장 → 값 변화 없이 마켓 동기화 경로만 실행, 마켓별 결과 관찰(상품1=쿠팡·11번가·스토어·카페24 4마켓).
- 결과: `synced=[SMART_STORE, ELEVEN_STREET]`, `failed={CAFE24, COUPANG}`.
  - ✅ **스토어·11번가: 실 API write 성공** — 배선·클라이언트 end-to-end 정상 확정.
  - 🔴 **쿠팡**: 1차 "원본데이터(items) 없음"(내 rawData 의존 접근 오류) → **vendor-items 전용 엔드포인트(`/vendor-items/{vendorItemId}/prices|quantities`)로 교정**. 2차 "Empty key" → `CoupangProperties.secretKey` 공백 = **서버 `COUPANG_ACCESS_KEY/SECRET_KEY` env var 미설정**(마켓 상품 API는 주문 API의 DB 자격증명과 별개인 env var 사용). 코드 아님·운영 config. 명확한 메시지 가드 추가.
  - 🔴 **카페24**: "401 Invalid access_token" → **기존 미해결 이슈(Cafe24 OAuth 토큰 만료, 사이클 8/9) 확정**. 코드 아님·토큰 재발급 필요.
- 결론: **D-060 코드 완성·검증**(부분 실패 표면화 포함). 스토어·11번가 라이브 동작. 쿠팡·카페24는 **자격증명 설정만 하면 즉시 동작**(코드 준비 완료). 남은 것은 운영 조치:
  1. 서버에 `COUPANG_ACCESS_KEY`, `COUPANG_SECRET_KEY`(쿠팡 마켓 상품 API 키) 설정.
  2. Cafe24 개발자센터에서 토큰 재발급 후 저장소 갱신.

### D-060 라이브 반복검증·자격증명 통합 (2026-07-11, 무인증 라이브 환경 직접 검증)

사용자 허가로 실서버에서 `PUT /products/1/price-stock`(4마켓 상품, 현재값 유지)로 반복 검증하며 실패를 순차 해소:

1. **쿠팡 4단계 관통**: (a) rawData 의존 접근 오류 → vendor-items 전용 엔드포인트, (b) "Empty key"(env 자격증명 공백) → **CoupangRestClient가 DB `sb_market_credential`(COUPANG) 우선 사용, env 폴백**으로 통합(publish·이미지·가격재고 전체가 DB 키로 동작), (c) "411 Length Required"(JDK HttpClient가 무바디 PUT에 Content-Length 미전송) → 빈 JSON `{}` 바디로 강제, (d) "HMAC format is invalid"(제품 RestClient가 KST·T/Z없는 `generateSignature` 사용) → 주문 클라이언트와 동일한 `generateSignatureUtc`(UTC `yyMMdd'T'HHmmss'Z'`)로 교체 + 중복 signed-date 헤더 제거.
2. **최종 결과**: `synced=[SMART_STORE, COUPANG, ELEVEN_STREET]` — **3마켓 실 API write 성공(라이브 확정)**. 현재값 유지로 실판매가 무변경.
3. **카페24만 잔여**: `401 Invalid access_token`. `Cafe24TokenManager`는 DB 리프레시 토큰으로 갱신하는데, 리프레시 토큰이 Cafe24에서 거부(만료/회전소진)돼 access token을 못 얻음 → "Bearer null"→invalid_token. **코드 아님 — 사용자 OAuth 재인증 필요**(Settings UI "정상 연동중"은 토큰 존재만 확인, 유효성 미검증). 재인증 경로: `Cafe24AuthController /api/admin/sync/cafe24/auth/callback` + `generateAuthorizationUrl`.
- **핵심 부산물**: 마켓 자격증명 2원화(주문=DB, 제품=env) 불일치를 쿠팡에 한해 DB 우선으로 통합. 스토어·11번가는 기존 env로 동작 중이라 미변경(향후 동일 통합 여지).
- 상태: **D-060 검증통과(3/4 마켓 라이브 확정)**. 카페24는 토큰 재인증 대기(운영·사용자).

### D-062: Cafe24 가짜 '정상 연동중' 표시 + 재인증 UX 부재 (2026-07-11, 사용자 요청)

- 심각도: P2 (오표시로 장애 은폐 — 실제 토큰 무효인데 정상으로 표기) · 리스크 등급: 표준
- 위치: `frontend/src/pages/Settings.tsx`(hasRefreshToken 기반 녹색), `Cafe24AuthController`(상태 점검·간편 발급 엔드포인트 부재)
- 근본원인: Settings가 `hasRefreshToken`(토큰 **존재** 여부만)으로 "✅ 정상 연동 중"을 표시 → 리프레시 토큰이 실제로 만료/거부돼도 녹색으로 뜸(가짜). 재인증은 콜백 URL을 브라우저 주소창에 수동 입력하는 번거로운 방식뿐.
- 수정: (백엔드) `GET /api/admin/sync/cafe24/status`가 실 Cafe24 API(`/admin/products?limit=1`)를 호출해 토큰 **실유효성**을 검증(401이면 재인증 필요). `POST /api/admin/sync/cafe24/issue-token`이 리다이렉트 code(또는 전체 URL, code 자동추출)로 리프레시 토큰 발급·저장. `Cafe24TokenManager.isRefreshTokenPresent()` 추가. (프론트) 카페24 탭이 실상태를 표시(가짜 녹색 제거)하고, 무효 시 재인증 카드(①인증 열기 →②주소/코드 붙여넣기 →발급) + 상태 새로고침 제공.
- 라이브 검증: 배포 후 `/status` = `{connected:false, "리프레시 토큰이 만료/무효입니다"}` — **기존 '정상 연동중'이 가짜였음이 실검증으로 확정**. 이제 사용자가 UI에서 재인증(OAuth 승인은 Cafe24 로그인 필요) 후 즉시 토큰 발급 가능.
- 상태: 검증통과 (게이트: infra+api test BUILD SUCCESSFUL, 프론트 tsc clean + build EXIT0, 라이브 상태 엔드포인트 실동작 확인)

### D-060 최종 완료 (2026-07-11): 4마켓 전부 라이브 동기화 확정
- Cafe24 재인증(D-062) 후 `PUT /products/1/price-stock` → `synced:[CAFE24,SMART_STORE,COUPANG,ELEVEN_STREET], failed:{}`. **가격/재고 → 4마켓 전 마켓 라이브 반영 확정.** D-060 종결.

---

## 사이클 17 (D-061 배송 마켓 전파 재진단 — 이전 감사 정정, 2026-07-11)

### D-061 정정: 배송정보 수정 → 마켓 전파는 이미 구현됨

- **이전 감사(사이클 15) 오류 정정**: "배송정보 수정(updateShippingInfo)은 DB만 저장·마켓 미전파"는 **틀렸음**. 리더 직접 확인 결과 `OrderService.updateShippingInfo:306,318`이 이미 `marketplaceShippingService.sendTrackingToMarketplace(item)`를 호출(PURCHASED→SHIPPED 및 SHIPPED 이후 수정 양쪽). 전파 경로: OrderController.updateShippingInfo → OrderService → MarketplaceShippingService(DB MarketCredential) → 마켓별 shipOrder/updateTracking.
- **실제 배송 전파 매트릭스**: 쿠팡(shipOrder+updateTracking 완전 구현), 스마트스토어·11번가(shipOrder 구현, updateTracking은 MarketOrderPort 기본값=shipOrder 재호출), ESM+(shipOrder가 log.warn 스텁), 카페24(OrderAdapter 자체 부재 → getPort 시 예외).
- **남은 실제 갭(대형·저가치)**:
  - ESM+(G마켓/옥션) 송장 등록: Selenium 스크래핑으로만 가능(정규 API 없음), 구현·유지보수 비용 중상. **게다가 ESM+는 주문 데이터 0건(D-052)** — 현재 가치 낮음.
  - 카페24 OrderAdapter: 주문 조회/배송 어댑터 전무 → **카페24 주문 동기화 자체가 없음**. 배송 전파 이전에 주문 수집부터 필요. 대형 신규 개발.
- 결론: D-061의 핵심(데이터 있는 3마켓 배송정보 수정→마켓 전파)은 **이미 구현·동작**. ESM+/카페24는 데이터 부재·대형 개발이라 즉시 착수 부적합. 라이브 검증은 실 송장이 필요해(실주문에 실송장 전송) 보류 — 코드 경로는 이메일 자동추적과 동일한 sendTrackingToMarketplace로 확인됨.
- 상태: D-061 핵심 이미구현 확인(정정). ESM+/카페24 배송은 발견(보고) 유지 — 데이터·우선순위상 후순위.

---

## 사이클 18 (사용자 요청 "1,2,3 모두 구현", 2026-07-11)

### 항목1(D-060 배치): 배치 가격/재고도 마켓 자동 반영 — 검증통과
- 구조(Tidy First): 마켓 순회 동기화 로직을 `ProductManageUseCase`→공용 `ProductMarketSyncService`로 추출, `MarketRepublishResult` 독립 레코드화(단건 회귀 없음). `ProductMarketSyncServiceTest`(순회·스킵·부분실패 3케이스).
- 행위: `BatchPriceStockService.crawlAndUpdatePriceStock`(일일 크롤)·`manualUpdatePriceStock`(수동 배치)가 DB 저장 후 마켓 반영, 진행상태 메시지에 성공/스킵/실패 표면화. 일일 가격 갱신이 마켓까지 자동 전파.
- 게이트: compileJava/TestJava 전체 SUCCESSFUL, :core:test(신규3 PASS, 기존 flaky1 제외), :api:test BUILD SUCCESSFUL.

### 항목2: 스토어·11번가 상품 클라이언트 DB 자격증명 통합 — 검증통과(라이브)
- 행위: `SmartstoreRestClient`(clientId/clientSecret)·`ElevenstMarketRestClient`(apiKey)가 쿠팡처럼 DB `sb_market_credential` 우선, env 폴백. 상품-마켓 작업 자격증명을 DB 단일소스로 일원화.
- 라이브 회귀: 배포 후 `PUT /products/1/price-stock` = `synced:[CAFE24,SMART_STORE,COUPANG,ELEVEN_STREET], failed:{}` — **DB 자격증명으로 4마켓 전부 유지(스토어·11번가 무회귀 확인)**.

### 항목3(D-061): 배송 마켓 전파 — 안전개선 + 대형갭 보고
- 행위(안전): `MarketplaceShippingService`가 배송 어댑터 없는 마켓(카페24 등)에서 `getPort` 예외로 배송정보 수정을 깨뜨리던 것을 `findPort`(Optional)로 감지해 스킵(자사 저장 유지, 크래시 방지).
- **대형 갭(미착수·데이터 부재로 검증 불가)**:
  - **ESM+(G마켓/옥션) 송장 등록**: 정규 API 없이 Selenium 스크래핑으로만 가능 → 라이브 UI 리버스엔지니어링 필요. ESM+ 주문 데이터 0건(D-052)이라 검증 불가·가치 낮음.
  - **카페24 배송/주문**: OrderAdapter 자체 부재 + **카페24 주문 동기화(fetchOrders)가 아예 없음** → 배송 전파 이전에 카페24 주문 통합(대형 신규)부터 필요. 카페24 주문 데이터도 시스템에 없음.
  - 판정: 두 건 모두 해당 마켓에 주문 데이터가 없어 무검증 대형 투기 개발 → 라이브 검증 원칙상 별도 스코프(사용자가 실제 해당 마켓 주문 운영 시 착수). 데이터 있는 3마켓(쿠팡·스토어·11번가)은 배송정보 수정→마켓 전파 이미 동작.
- 상태: 항목1·2 검증통과, 항목3 안전개선 완료·대형 어댑터는 보고(후순위).

### 사이클 18 요약
- 사용자 "1,2,3 모두 구현": 1·2 완전 구현·라이브 검증, 3은 안전개선 + 대형 신규(ESM+ Selenium·카페24 주문통합)는 데이터 부재로 별도 스코프 권고.

---

## 사이클 19 (G마켓/옥션 조회를 Cafe24 주문 API로 선회, 2026-07-11, 사용자 요청)

### D-061 후속(방향전환): ESM+ Selenium → Cafe24 주문 API

- 배경(사용자 통찰): G마켓/옥션이 Cafe24에 오픈마켓 연동돼 있어, 불안정한 Selenium 스크래핑 대신 **Cafe24 주문 API로 G마켓/옥션 주문을 안정 조회** 가능.
- 조사(2에이전트: Cafe24 API 문서 + 기존 sync 아키텍처): `GET /api/v2/admin/orders`(scope `mall.read_order`), **`order_place_id`로 마켓 구분**(gmarket/auction/coupang/…), `embed=items,receivers,buyer`, start/end_date(3개월/콜), 페이지 limit≤1000. 송장: `POST /orders/{id}/shipments`(scope `mall.write_order`). 택배사 코드는 `GET /carriers`로 조회.
- 구현: `Cafe24OrderApiPort`(core) + `Cafe24OrderApiClient`(infra, 기존 Cafe24RestClient 재사용) + `Cafe24OrderSyncService`(order_place_id→GMARKET/AUCTION 매핑, 타마켓 스킵, receivers/buyer/items 파싱, product_no로 CAFE24 마켓등록→sb상품 매핑, N/C/R/E 상태매핑). `/api/v1/orders/sync/esmplus` 엔드포인트·OrderSyncScheduler·"G마켓/옥션 동기화" 버튼을 Cafe24 방식으로 선회(ESM+ Selenium 철회). 진단용 `POST /orders/sync/cafe24/preview`(원시 응답). OAuth scope에 `mall.read_order,mall.write_order,mall.read_shipping` 추가.
- 게이트: 전체 compileJava/TestJava SUCCESSFUL, 신규 `Cafe24OrderSyncServiceTest`(gmarket 매핑·타마켓 스킵·필드 파싱) PASS, `:core:test`·`:api:test` BUILD SUCCESSFUL. 부수: 항목2 여파로 깨졌던 `ElevenstRestClientBeanConflictTest`(좁은 스캔에 MarketCredentialRepository 목 미제공)도 수정.
- **재인증 필요(사용자 액션)**: 기존 토큰은 product scope만 → 주문 조회 불가(preview 500 확인). Settings→카페24 재인증 카드(항상 노출로 변경)로 새 scope 재발급 후 라이브 검증 예정.
- 상태: 코드 완성·배포. 재인증 후 라이브 검증(preview로 실구조 확인→sync→그리드 표시) 대기.

### D-061 후속(라이브 검증 완료, 2026-07-11): Cafe24 주문 API로 G마켓/옥션 조회 성공

- 재인증 과정 실드리븐: 403 insufficient_scope(앱에 주문 scope 미등록 → 사용자가 Cafe24 개발자센터에서 전 권한 활성화) → 422 Invalid date format(start_date 시간 포함 → **날짜만 yyyy-MM-dd로 수정**) → 성공.
- **라이브 검증**: `/orders/sync/cafe24/preview` 실주문 파싱 정확(order_place_id=gmarket→GMARKET, buyer/receivers.address_full/items.product_no·product_name·quantity/order_status=N30→SHIPPED, order_date ISO+09:00→KST). status=connected(상품·주문 권한 확인). 동기화 트리거 후 통합주문관리에 **G마켓 주문 3건 실제 표시**(20260708-0000011 등).
- 부수 개선: OAuth scope 전체(app/product/collection/order/shipping RW), status가 주문 권한까지 실검증(오표기 방지), 프리뷰 rootCause 노출.
- 상태: **검증통과** — G마켓/옥션 주문 조회가 Cafe24 API로 안정 동작(Selenium 철회 완료). 송장 역전송(write_order)은 후속 트랙.

### D-061 후속(완료): G마켓/옥션 송장 역전송 Cafe24 shipments API 구현

- 구현: 배송정보 수정 → MarketplaceShippingService.sendTrackingToMarketplace → 포트(GMARKET=EsmplusOrderAdapter shipOrder 스텁 교체, AUCTION=신규 Cafe24AuctionOrderAdapter) → `Cafe24ShipmentService` → `POST /admin/orders/{order_id}/shipments`(tracking_no·shipping_company_code·status=shipping·order_item_code). marketOrderNo=Cafe24 order_id. 택배사 코드는 `GET /admin/carriers`로 몰별 조회→ShippingCarrier 라벨 매칭(미매칭 실패 표면화), order_item_code는 주문상세(embed=items)에서 획득. `MarketplaceShippingService`의 cred==null 조기종료 완화(Cafe24 배송은 마켓 자격증명 불필요→옥션 커버). 진단 `POST /orders/sync/cafe24/carriers`.
- 라이브 검증(읽기): 몰 택배사 조회 성공 — 자체배송(0001)·우체국택배(0012)·CJ대한통운(0006). 코드 매칭 정확(CJ→0006, 우체국→0012). 응답 필드 shipping_carrier_code를 방어적 다중 필드조회로 획득.
- 게이트: `Cafe24ShipmentServiceTest`(코드매칭·order_item_code·바디구성·미매칭예외) PASS, 전체 compileTestJava·:core:test·:api:test BUILD SUCCESSFUL(EsmplusParseSingleOrderTest 생성자 갱신).
- 미검증(쓰기): 실제 shipments POST는 실주문을 '배송중'으로 바꾸는 부작용이라 가짜 데이터 라이브 테스트 회피 — 첫 실 송장(실 트래킹번호로 실 G마켓 주문 배송처리)이 검증. 코드·택배사코드는 준비 완료.
- 상태: 구현 완료(읽기 검증), 실 송장 등록은 실사용 시 검증.

---

## 사이클 20 (사용자 신고 5클러스터 병렬 진단, 2026-07-11)

> 출처: `_workspace/scout_report_inventory.md`(재고), `_workspace/scout_report_customs.md`(통관), `_workspace/scout_report_status_sync.md`(상태·배송). 3 scout 병렬. 리더 코드 직접확인으로 Jackson 직렬화 가설 기각·11번가 실경로(parseShippingElement) 확정·scout의 스마트스토어 상태코드 환각(비표준) 정정.

### D-063: 재고현황 UI 표시규칙 오류 (INV-1)
- 심각도 P2 / 리스크 경량 / 상태 수정완료(검증대기)
- 증상: 재고현황 셀이 IN_STOCK/품절 무관 재입고일 행 항상 표시, 재입고일 없으면 `( - )`. 사용자 요구: 구입가능이면 부가표시 없음, 품절+재입고일 있을 때만 (날짜).
- 근본원인: `frontend/src/pages/OrderGrid.tsx:1015~1023` — 조건 미완성(IN_STOCK도 재입고행 렌더, restockDate 없으면 `( - )`).
- 수정방향: IN_STOCK→뱃지만, OUT_OF_STOCK+restockDate→품절+(날짜), OUT_OF_STOCK+무재입고일→품절만, null→`-`.
- 영향: `frontend/src/pages/OrderGrid.tsx`

### D-064: 재고 갱신시각 미표시 (INV-2)
- 심각도 P2 / 리스크 경량 / 상태 수정완료(검증대기)
- 증상: 재고현황이 언제 갱신된 데이터인지 화면에 표기 없음.
- 근본원인: `Product`(BaseEntity)에 `updatedAt` 존재하나 프론트 `ProductDto`(`frontend/src/api/orderApi.ts:46~62`)에 필드 부재 + 셀 렌더에 표시코드 부재. `timeAgo()`(`OrderGrid.tsx:430`) 재사용 가능.
- 수정방향: 백엔드 응답에 재고확인시각 노출(updatedAt 또는 전용 stockCheckedAt) → ProductDto 추가 → 셀에 상대시각 표시.
- 영향: `frontend/src/api/orderApi.ts`, `frontend/src/pages/OrderGrid.tsx`, (백엔드 DTO 직렬화 확인)

### D-065: 특정 상품 restockDate (-) 버그 (INV-3)
- 심각도 P1 / 리스크 표준 / 상태 **수정완료(검증대기)** — 코어 null-guard(b) 완료. 파싱부(a)는 라이브확인 잔여.
- 수정(b): `ProductSyncService.java:45~51`에 null-guard+IN_STOCK 의미론 적용(IN_STOCK이면 null clear 허용, 그 외 restockDate=null이면 기존값 유지). Red 테스트 `ProductSyncServiceRestockDateTest`(신규, 3건). 요약 `_workspace/fixes/D-065.md`.
- 잔여(a) 라이브확인: DB restock_date 실값 + iHerb 응답 재입고일 필드/포맷(ISO 여부) — 비ISO면 파싱부 후속 수정 필요.
- 증상: "California Gold Nutrition Magnesium Bisglycinate TRAACS 240 Capsules" 재입고일 있으나 화면 (-).
- 근본원인(리더 정정): Jackson은 Spring Boot 기본(JavaTimeModule 등록, write-dates-as-timestamps=false)→LocalDate는 ISO 문자열, **직렬화 가설 기각**. 남은 후보 2: (a) 크롤 파싱 실패(`IherbScraperClient.java:134~156` expectedAvailability/backOrderDate 미획득) (b) null 소거(`ProductSyncService.java:47` restockDate null이면 기존값 덮어씀).
- 수정방향: (b) null-guard(안전, 즉시) + (a) 라이브 확인(DB restock_date 값, iHerb 응답 필드) 필요.
- 라이브확인: `SELECT restock_date,stock_status,updated_at FROM sb_product WHERE original_name LIKE '%Magnesium Bisglycinate%'`
- 영향: `ProductSyncService.java`, `IherbScraperClient.java`

### D-066: 11번가 통관번호(개인통관고유부호) 미조회 — 배송중 경로 (CUS-2, 조숙현)
- 심각도 P2 / 리스크 표준 / 상태 수정완료(검증대기)
- 수정(fixer): `parseShippingElement`·`parseOrderDetailElement`에 psnCscUniqNo 파싱+customsClearanceNo 매핑 추가. **정정: getElementText는 태그 부재 시 null이 아니라 "" 반환** → `emptyToNull()`로 정규화(SyncService null-guard 실효화, 기존 통관번호 빈값 덮어쓰기 회귀 차단). 재현/회귀 테스트 `ElevenstShippingCustomsClearanceTest`. 게이트 `:core:test` 96 tests green. 요약 `_workspace/fixes/D-066.md`. 라이브확인: 배송중 XML에 psnCscUniqNo 태그 실제 포함 여부.
- 증상: 11번가 주문(조숙현) customsClearanceNo null.
- 근본원인(리더 확정): SyncService는 `fetchOrders`만 호출→배송중 주문은 `parseShippingElement`(`ElevenstOrderAdapter.java:387`) 경유하는데 여기 `psnCscUniqNo` 파싱 없음. `parseOrderElement`(line 320/371)엔 이미 존재. 잠복경로 `parseOrderDetailElement`(line 230, 호출 0건)도 누락.
- 수정방향: `parseShippingElement`에 `psnCscUniqNo` 파싱+`.customsClearanceNo(...)` 추가(태그 없으면 null, 안전). parseOrderDetailElement도 동일 보강.
- 영향: `ElevenstOrderAdapter.java`

### D-067: G마켓/옥션 통관번호 미조회 — Cafe24 파싱 부재 (CUS-1)
- 심각도 P2 / 리스크 중대(마켓 API 계약) / 상태 검증통과(코드) — 라이브 PCCC 필드명 확정 필요
- 검증(2026-07-11 배치B): 경계면 정합(CustomsData 빌더·Order.updateCustomsClearanceNo 실존, Order 널가드로 NPE 안전), 4테스트 Green, `:core:test` 107 전량 통과. 코드판정 PASS. **라이브검증 필요**: 후보키 7종은 방어적 추측 — 실제 Cafe24 응답 PCCC 필드명은 preview 로그로 확정. 판정서 `_workspace/verify/batchB_verdict.md`.
- 증상: G마켓/옥션 주문(Cafe24 경유) customsClearanceNo 항상 null.
- 근본원인(확정): `Cafe24OrderSyncService.createOrder/updateOrder`(line 119~170)가 buyer/receivers에서 통관번호 미추출·Order.customsData 미설정. `embed=items,receivers,buyer`로 조회는 함.
- 수정방향: Cafe24 응답의 실제 PCCC 필드명 확정 후 추출·CustomsData 매핑. **라이브확인 필요**(buyer vs receivers, 필드키). 방어적 구현+미발견시 노드키 로깅 권고.
- 영향: `Cafe24OrderSyncService.java`, `CustomsData.java`(구조 적합)

### D-068: 스마트스토어 상태 UNKNOWN 오맵핑 (STAT-1, 이명동)
- 심각도 P2 / 리스크 중대(마켓 계약·배송차단 연계) / 상태 검증통과 — 이명동 실코드 라이브 확인 잔여
- 검증(2026-07-11 배치B): 표준 누락코드만 추가·비표준 미추가 확인, 기존 매핑 7종·default(UNKNOWN+warn) 회귀 보존, 4테스트 Green, `:core:test` 107 전량 통과. 코드판정 PASS. 잔여: 이명동 실 status는 서버로그로 확정(UNKNOWN+warn 노출 유지). 판정서 `_workspace/verify/batchB_verdict.md`.
- 수정(사이클20 배치B): 표준 누락코드 `PAYMENT_WAITING`→NEW, `CANCELED_BY_NOPAYMENT`→CANCELED 추가. 기존 케이스·default(UNKNOWN+log.warn) 유지. 비표준 추측코드 미추가. Red→Green(`SmartStoreStatusMapperTest` 신규, `:core:test` BUILD SUCCESSFUL). 상세 `_workspace/fixes/D-068.md`. 잔여: 이명동 실코드는 서버로그로 라이브 확정 필요.
- 증상: 이명동 주문 "알수없음". 2차피해: `OrderService.java:294~296`이 UNKNOWN이면 배송정보 수정 400 차단.
- 근본원인: `SmartStoreStatusMapper.java:30~50` 스위치에 표준 네이버 productOrderStatus 일부 누락→default UNKNOWN. **리더 정정: scout 제시 코드(PLACE_ORDER_COMPLETED/IN_PROGRESS 등)는 비표준(환각).** 표준 누락 후보: `PAYMENT_WAITING`(→NEW), `CANCELED_BY_NOPAYMENT`(→CANCELED).
- 수정방향: 표준 누락코드 추가 + 기존 log.warn이 이명동 실코드 노출하므로 라이브 로그로 확정. UNKNOWN 해소 시 배송차단도 자동 해소.
- 라이브확인: 서버로그 `"알 수 없는 스마트스토어 주문 상태: {코드}"`
- 영향: `SmartStoreStatusMapper.java`

### D-069: 배송정보 수정 마켓 미동기화 (SYNC-1)
- 심각도 P1 / 리스크 중대 / 상태 검증통과 — (c) 실패 표면화 스코프만. (b) postSyncProcess는 별도 후속.
- 검증(2026-07-11 배치B): 반환형 전환·호출부 6곳(OrderService 4 + worker EmailFetcher 2) 전수 정합(grep 재검증), 성공 시에만 마킹·실패 미마킹 확인, 롤백 제거 의미론 확인, 4테스트 Green. 게이트 `:core:test` 107 통과 + `:worker:clean compileJava` + `:api:test` 8 통과, 회귀 0. 코드판정 PASS. 판정서 `_workspace/verify/batchB_verdict.md`.
- 수정(2026-07-11): `sendTrackingToMarketplace` void→`MarketShippingResult` 반환, port 예외를 catch해 실패결과로 표면화(예외전파·롤백 제거). 배송정보 DB 저장은 마켓 전송과 독립 커밋. 성공 시에만 `markTrackingAsSent`(실패 시 미마킹→재시도 보존). 호출부 6곳(OrderService 4 + worker EmailFetcherService 2) 정합. Red→Green 테스트 `MarketplaceShippingServiceTest`(4). 게이트 `:core:test` 전체 통과 + `:worker:compileJava`. 요약 `_workspace/fixes/D-069.md`.
- 판정: D-061 정정(전파 배선 존재)은 **코드상 맞음**(`OrderService:306,318`→sendTrackingToMarketplace). 사용자 재신고 주원인 = **D-068(UNKNOWN)로 인한 입구 400 차단**(이명동). 부차: (b) `SmartStoreOrderSyncService.postSyncProcess`(line 203) 빈 메서드 → 취소/반품 미갱신, (c) `MarketplaceShippingService`(line 86~95) 마켓 API 실패가 예외전파→트랜잭션 롤백(부분실패 미표면화).
- 수정방향: D-068 우선(이명동 해소). (c) 마켓 실패를 예외전파 대신 부분실패 결과로 표면화(D-060 패턴). (b) postSyncProcess 구현.
- 영향: `MarketplaceShippingService.java`, `SmartStoreOrderSyncService.java`, `OrderService.java`(차단로직은 정상)

### 사이클 20 배치 계획
- **배치 A(코드확정·즉시)**: D-063·D-064(프론트 재고 UI, 동일파일 순차), D-065 null-guard(백엔드), D-066(11번가 통관, 백엔드) — 파일 비겹침 병렬.
- **배치 B(라이브데이터 의존)**: D-067(Cafe24 통관, 필드명 확인), D-068(스마트스토어 상태, 실코드 확인), D-069(배송 전파 표면화). 방어적 구현+라이브검증 관문.

### 사이클 20 검증·커밋 결과 (2026-07-11)
- **배치 A (검증통과·커밋)**: D-063/D-064(재고 UI 규칙+갱신시각, 커밋 cc6e574), D-065(restockDate null-guard, 6559b5b), D-066(11번가 통관 배송중경로, 3b0de64). qa PASS(_workspace/verify/batchA_verdict.md), :core:test 96/0·tsc 0·build ✓.
- **배치 B (검증통과·사용자 승인·커밋)**: D-068(스마트스토어 표준 누락코드, f8d4577), D-067(Cafe24 통관 방어적 구현, adf85d7), D-069(배송 전파 실패 표면화, 968d64b). qa PASS(_workspace/verify/batchB_verdict.md), :core:test 107/0·:api:test 8/0·:worker compile OK. 중대 등급 사용자 승인 획득.
- 결함 상태: D-063·D-064·D-065·D-066·D-067·D-068·D-069 = **검증통과**.

### 사이클 20 라이브 확인 필요(배포 후, 사용자 액션)
1. **D-067 Cafe24 PCCC 실 필드명**: G마켓/옥션 실주문으로 `POST /orders/sync/cafe24/preview` 후 서버 debug 로그 `[CAFE24-ORDER] PCCC 미검출 ... buyerKeys=[..] receiverKeys=[..]`로 실제 통관번호 필드명 확정 → `Cafe24OrderSyncService.PCCC_KEYS`에 반영(후속). 채워지면 확정 완료.
2. **D-068 이명동 실 상태코드**: 스마트스토어 동기화 후 서버 로그 `"알 수 없는 스마트스토어 주문 상태: {코드}"` 확인 → 남은 미지 표준코드 있으면 매퍼 추가(후속).
3. **D-065 iHerb 재입고일**: `SELECT restock_date,stock_status,updated_at FROM sb_product WHERE original_name LIKE '%Magnesium Bisglycinate%'` + iHerb 응답 재입고일 필드/포맷(ISO 여부). 비ISO면 IherbScraperClient 파싱부 후속 결함.
4. **D-066 11번가 배송중 psnCscUniqNo 태그 실재 여부**: 배송중 XML 응답에 태그 포함 확인.

### 사이클 20 후속 결함 후보 (미착수)
- **D-070(후보)**: `ElevenstOrderAdapter.parseOrderElement`(line 325)도 `emptyToNull` 미적용 — complete/packaging/dlvcompleted 경로에서 psnCscUniqNo 태그 부재 시 ""가 null-guard 통과해 기존 통관번호 덮어쓸 잠복 위험(qa-verifier 배치A 발견). D-066과 동일 정규화 적용 권고. 리스크 표준.
- **D-071(후보)**: `SmartStoreOrderSyncService.postSyncProcess`(line 203) 빈 메서드 — 스마트스토어 취소/반품 동기화 전무(쿠팡 detectCancellations 패턴 미적용). 리스크 표준.
- **D-072(후보)**: D-069 배송 전송 실패를 컨트롤러/응답 DTO까지 전달해 UI 토스트로 표면화(현재 서비스 로그+상태까지만). 리스크 경량.

---

## 사이클 21 (동기화가 수기 편집을 덮어쓰는 구조적 결함, 2026-07-11, 사용자 신고)

> 출처: `_workspace/scout_report_overwrite.md`. 사용자 통찰: 통관 검증상태(대기중→정상/불일치)를 수기 처리해도 동기화가 다시 덮어씀 — 통관번호에 국한되지 않고 "우리 DB에서만 관리·외부 마켓 미전송" 필드(통관검증상태·주소·우편번호) 전반의 수기보정 보호 문제. 리더 코드 확정: updateCustomsClearanceNo 무조건 PENDING 리셋.

### D-073: 통관 검증상태 무조건 리셋 (OVW-1/OVW-3/OVW-4)
- 심각도 P1 / 리스크 표준 / 상태 수정완료(검증대기)
- 수정(사이클 21): `Order.updateCustomsClearanceNo`를 번호 실제 변경 시에만 PENDING/NONE 리셋하도록 변경, 불변이면 기존 상태/검증인 유지. Red 테스트 `OrderCustomsClearanceNoTest`(신규) 3케이스. `:core:test` 전체 통과. 요약 `_workspace/fixes/D-073.md`.
- 증상: 사용자가 통관 검증 완료(VALID/INVALID_*) 후 다음 마켓 동기화에서 같은 통관번호가 재하달되면 customsStatus=PENDING·verifiedPerson=NONE으로 리셋 → 수기 검증 소실. VALID→PENDING 되돌림이 통관 스케줄러 재검증 루프도 유발(OVW-4).
- 근본원인(확정): `Order.java:122~128` `updateCustomsClearanceNo`가 번호 변경 여부 무관 항상 PENDING/NONE 세팅. 호출부 5마켓 SyncService(SmartStore:135·Coupang:237·Elevenst:135·Esmplus:153·Cafe24:167) 모두 `if(no!=null)`만 가드 → 번호 불변인데 재호출 시 리셋. 사용자 수기 통관번호 수정 경로(OrderService:214)도 동일(OVW-3).
- 수정방향(최소안): `updateCustomsClearanceNo`를 "번호가 실제 변경된 경우에만 PENDING/NONE 리셋, 불변이면 기존 상태/검증인 유지"로 변경. Order.java 1메서드만. 5 SyncService 불변. 정책: 번호 변경 시 재검증 필요(사용자 사고모델 일치)로 채택.
- 영향: `Order.java`
- 리스크: 낮음(번호 변경 시 기존 무효화 유지).

### D-074: 주소/우편번호 수기보정 동기화 덮어쓰기 (OVW-2)
- 심각도 P2 / 리스크 표준 / 상태 수정완료(검증대기) — 수정요지 `_workspace/fixes/D-074.md`
- 증상: 사용자가 주소를 수기 보정해도 스마트스토어/11번가/ESM+ 동기화가 마켓 값으로 덮어씀. 쿠팡·Cafe24는 progressed(진행) 시 protectAddress 가드 있으나 이 3마켓엔 없음. 또 zipcode는 쿠팡·Cafe24 가드에서도 미보호(주소만 null 치환, 우편번호는 마켓 값 덮음).
- 근본원인(확정): `CoupangOrderSyncService:224`·`Cafe24OrderSyncService:150` protectAddress 존재. `SmartStore/Elevenst/EsmplusOrderSyncService.updateOrderInfoFromDto`는 lineItems 미수신 → 가드 부재, `order.update(...,dto.getAddress(),...)` 무조건. `Order.update`는 non-null이면 덮어씀. zipcode도 세트 보호 필요.
- 수정방향(표준안): 3마켓 updateOrderInfoFromDto에 lineItems 전달 + 쿠팡/Cafe24 패턴(protectAddress=progressed lineitem 존재)의 주소 보호 이식. 추가로 protectAddress 시 zipcode도 함께 null 치환(전 마켓)해 주소·우편번호 세트 보호.
- 영향: `SmartStoreOrderSyncService.java`, `ElevenstOrderSyncService.java`, `EsmplusOrderSyncService.java`, `CoupangOrderSyncService.java`, `Cafe24OrderSyncService.java`(zipcode 세트 보호)
- 리스크: 표준(5 SyncService 다파일).

### 사이클 21 배치
- D-073(Order.java, P1) + D-074(5 SyncService) 파일 비겹침 → 병렬. 둘 다 행위 변경. 커밋 분리.

### 사이클 21 검증·커밋 결과 (2026-07-11)
- **검증통과·커밋**: D-073(통관 검증상태 번호변경시에만 리셋, 커밋 33a1b4e), D-074(주소/우편번호 protectAddress 일관화+세트보호, 3fda643). qa PASS(_workspace/verify/cycle21_verdict.md) + 리더 게이트 :core:test 113/0·:api:test 8/0. 리스크 표준(자율 통과).
- 결함 상태: D-073·D-074 = 검증통과. OVW-3/OVW-4는 D-073로 자동 해소.
- 라이브 확인 권고: (a) 스마트스토어/11번가/ESM+가 통관번호를 실제 하달하는지(하달 안 하면 OVW-1 해당마켓 미발현). (b) "번호 변경 시 재검증(PENDING)" 정책이 업무규칙에 맞는지 사용자 확인.
- 미착수 후속(원장 유지): D-070(parseOrderElement emptyToNull)·D-071(스마트스토어 postSyncProcess 취소/반품)·D-072(배송실패 UI 표면화).

---

## 사이클 22 (Cafe24 동기화 실패 + 전체 액션 활동로그, 2026-07-11, 사용자 신고)

> 출처: `_workspace/scout_report_cafe24fail.md`, `_workspace/scout_report_actionlog.md`. 사용자 신고 (1) "동기화 실패: Cafe24 API 호출 실패" 버그, (2) 모든 메뉴/기능 액션에 활동로그(진행현황 성공/진행/실패) 기록.

### D-075: Cafe24 동기화 실패 원인 은폐 (CF24-1/2/3)
- 심각도 P1 / 리스크 표준 / 상태 수정완료(검증대기) — 수정요지 `_workspace/fixes/D-075.md`
- 증상: 진행현황에 "동기화 실패: Cafe24 API 호출 실패"만 표시, 원인 불명. G마켓/옥션 주문 동기화 중단.
- 근본원인(확정): `Cafe24TokenManager.getValidAccessToken()`(60~68)이 refresh 실패 시에도 예외 없이 **null 반환** → `Cafe24RestClient.get()`이 "Bearer null"로 호출 → 401 → `catch(Exception)` → `RuntimeException("Cafe24 API 호출 실패", e)`(RestClient:34) → `Cafe24OrderSyncService`(62~67) `e.getMessage()`만 SyncCompletedEvent errorMessage로 전달 → root cause(401/403/422) 은폐.
- 구분: (라이브) refresh_token 만료/scope 부족이면 사용자 재인증 필요(`GET /api/admin/sync/cafe24/status`로 판별). (코드 수정) CF24-2 getValidAccessToken null 시 즉시 IllegalStateException("재인증 필요") throw; CF24-3 실패 시 root cause chain을 errorMessage에 포함.
- 수정방향: 코드 수정으로 실패 사유를 명확히 표면화(활동로그·상태에 재인증 필요/scope/HTTP코드 노출). 실제 연결 복구는 사용자 재인증.
- 영향: `Cafe24TokenManager.java`, `Cafe24RestClient.java`, `Cafe24OrderSyncService.java`
- 라이브 확인: `GET /api/admin/sync/cafe24/status`(connected 여부·사유), 서버로그 "Cafe24 GET Error".

### D-076: 전체 사용자 액션 활동로그 커버리지 (기능 구현)
- 심각도 P2(기능 요청) / 리스크 표준 / 상태 발견
- 배경: 활동로그 인프라(ActionLog/ActionLogService.record STARTED·SUCCESS·FAILED/ActionLogSyncListener/ActionLogController/ProcessStatusPage) 존재하나 커버리지가 4개 마켓 동기화(S1~S4)뿐. 미커버 24개 엔드포인트(통관검증·재고새로고침·구매정보수정·배송정보수정·주문확인/취소/발송/삭제·상품 수정/이미지/삭제/등록/게시·소싱·배치·자격증명저장·Cafe24재인증 등).
- 설계 결정(채택): **수동 record() 방식**(surveyor 권고 후보 B) + `ActionLogConstants` 상수 클래스 신설. AOP(후보 A)는 spring-boot-starter-aop 의존성·동적 marketType·기존 SyncCompletedEvent 이중기록 복잡성으로 후순위. 동기 단건수정은 결과만(SUCCESS/FAILED), 장시간/비동기(동기화·배치·크롤)는 STARTED+결과. S1~S4는 현행 유지(STARTED+리스너 완료, 이중기록 금지).
- 인벤토리: `_workspace/scout_report_actionlog.md` §2 전수표 참조.
- 영향: `ActionLogConstants.java`(신규), api 컨트롤러 8종(OrderSyncController[S5·S6], OrderController[O1~O10], ProductController, ProductSyncController, ProductSourcingController, BatchController, MarketCredentialController, Cafe24AuthController), `frontend/src/pages/ProcessStatusPage.tsx`(actionType→한글 라벨).
- 리스크: 표준. 스키마 변경 없음(sb_action_log 기존). marketType nullable 허용.

### 사이클 22 배치
- D-075(Cafe24 client/token/sync)와 D-076(api 컨트롤러+프론트) 파일 비겹침 → 병렬. D-076은 ActionLogConstants 공유 의존으로 단일 fixer 일괄.

### 사이클 22 검증·커밋 결과 (2026-07-11)
- **검증통과·커밋**: D-075(Cafe24 실패 fail-fast+root cause, 커밋 824f24f), D-076(전체 액션 활동로그 24엔드포인트+ActionLogConstants+프론트 라벨, 368d408). 리더 직접 게이트(세션 재시작으로 팀원 소멸→리더 검증): 코드 테스트 :core/:api/:infrastructure 관련 통과, 프론트 tsc 0·build ✓. record 패턴 FAILED후 예외 재throw로 에러응답 보존 스팟체크.
- **환경 제약**: 로컬 Docker 미실행으로 testcontainers 컨텍스트 스모크 3건(ApiContextLoadSmokeTest/ApiContextLoadWithBlankR2CredentialsTest/ProductDetailHtmlReadTest) initializationError — cycle-22 코드 무관(미변경), CI/서버(Docker)에서 검증됨.
- 결함 상태: D-075·D-076 = 검증통과.
- 라이브 확인: D-075 — `GET /api/admin/sync/cafe24/status`로 connected/재인증 필요 판별, 재인증 후 preview 정상 확인. D-076 — 각 액션 클릭 후 진행현황에 STARTED/SUCCESS/FAILED 표시 확인.
- 후속(원장 유지): 배치(B1~B4) 비동기 완료 SUCCESS/FAILED 기록(2차), 활동로그 AOP 리팩토링(선택). 기존 D-070~072 + 사이클20 라이브 잔여.

---

## 사이클 23 (상품관리 액션 상세로그 + 소스이미지 크롤 무반응, 2026-07-11, 사용자 신고)

> 출처: `_workspace/scout_report_product_actions.md`. 신고 (1) 가격/재고 수정 활동로그가 "성공"만 뜸→마켓별 상세 원함, (2) 소스이미지 크롤 버튼 무반응+진행현황 미표시(이미지 교체 중요).

### D-077: 가격/재고·이미지 활동로그 마켓별 상세 미표시 (PA-1)
- 심각도 P3 / 리스크 경량 / 상태 수정완료(검증대기) — `_workspace/fixes/D-077_078.md`
- 증상: `ProductController.updatePriceStock`(:106)이 `MarketRepublishResult`(synced/skipped/failed)를 받고도 "가격/재고 수정 성공 (상품 N)"만 기록. 사용자는 "DB 성공, 쿠팡 7283748383 성공, 스마트스토어 2939395 실패(사유), 카페24 3938 성공" 형태 원함.
- 근본원인: 컨트롤러가 result 내용을 메시지에 미반영. MarketRepublishResult엔 marketItemId 없음(MarketRegistration.extractMarketCode로 조회 가능, ProductController가 이미 MarketRegistrationRepository 주입).
- 수정방향(방법 A, 최소): SUCCESS 블록에서 result.synced/skipped/failed + marketRegistrationRepository.findByProductId(id)로 마켓→상품번호 맵 조립해 "DB 저장 완료 | 쿠팡 {번호} 성공, 스마트스토어 {번호} 실패({사유50자})" 포맷. 실패사유 50자 절단, message 1000자 truncate 기존 유지. 동일 패턴 uploadImages·uploadImagesByUrl에도 적용.
- 영향: `ProductController.java`(주). MarketRepublishResult/프론트 토스트 상품번호는 방법 B(파급 큼)라 후순위.

### D-078: 소스이미지 크롤 버튼 무반응 + 활동로그 미배선 (PA-2)
- 심각도 P2 / 리스크 표준 / 상태 수정완료(검증대기) — `_workspace/fixes/D-077_078.md`
- 증상: 상세모달 "소스 이미지 크롤" 버튼 눌러도 무반응, 진행현황 미기록. 이미지 교체 작업 핵심 경로.
- 근본원인: (a) 프론트 `ProductPage.tsx:535` `disabled={d.vendor!=='IHB'}` — 비-iHerb 버튼 비활성인데 antd disabled 버튼은 Tooltip도 안 떠 "먹통"으로 체감. iHerb라도 크롤 결과 빈응답이면 피드백 없음. (b) 백엔드 `crawlSourceImages`(ProductController:156~)에 activityLog record 미배선(D-076 커버리지 누락), ActionLogConstants에 상수 없음.
- 수정방향: (프론트) 버튼 항상 클릭 가능+handleCrawl에서 비-iHerb warning·크롤 결과 피드백(N개 수집/없음/실패 토스트)·loading 유지. (백엔드) ActionLogConstants.SOURCE_IMAGE_CRAWL 추가 + crawlSourceImages에 record(성공 이미지수/빈결과/실패). 
- 영향: `frontend/src/pages/ProductPage.tsx`, `ProductController.java`, `ActionLogConstants.java`
- 참조: D-049(과거 소스이미지 크롤 비-iHerb 무음실패 수정) 후속.

### 사이클 23 배치
- D-077·D-078 모두 ProductController.java 수정 → 파일 충돌 방지 위해 단일 fixer 일괄. 행위+기능 변경.

### 사이클 23 검증·커밋 결과 (2026-07-11)
- **검증통과·커밋**: D-077(가격재고·이미지 활동로그 마켓별 상세), D-078(소스이미지 크롤 무반응+활동로그 배선). 단일 커밋 d8c7b74(ProductController 공유).
- 리더 직접 게이트: :core:test·:api:test 코드테스트 통과(신규 ProductControllerActionLogDetailTest 5건), 프론트 tsc 0·build ✓. Docker 컨텍스트 스모크 3건은 로컬 환경제약(cycle 무관).
- 리더 보완: ProcessStatusPage에 SOURCE_IMAGE_CRAWL 한글 라벨 추가(fixer 대상 외 갭).
- 결함 상태: D-077·D-078 = 검증통과.
- 라이브 확인: 가격/재고 수정 후 진행현황 마켓별 상세(상품번호 포함) 실측, iHerb 크롤→SOURCE_IMAGE_CRAWL 기록·비-iHerb 클릭→warning 표시.
- 후속: 프론트 토스트/가격재고 상품번호는 방법B(MarketRepublishResult 확장)로 후순위. 배치 비동기 완료기록·AOP·D-070~072·사이클20 라이브 잔여 유지.

### D-079: Cafe24 동기화가 내부 이행상태(PURCHASED 구매완료)를 매 사이클 되돌림 (SP-E 후속 I-1)
- 심각도 P3 / 리스크 표준 / 상태 미착수(후속) — 출처: fix-live-defects-b-c-e 최종 리뷰
- 증상: 운영자가 G마켓/옥션 라인아이템을 수기로 PURCHASED(구매완료, order=2)로 진행시켜도, 30분 주기 Cafe24 동기화가 실제코드(N10/N20)를 매핑해 PREPARING(order=1)으로 덮어 되돌림.
- 근본원인: `Cafe24OrderSyncService.updateOrder`(:191~208)가 shippingStatus를 무조건 덮어씀(단조 가드 없음). main에도 존재한 선행결함이나, d3d40dc가 N10 클로버 대상을 NEW→PREPARING으로 바꿔 체감 노출. `ShippingStatus.PURCHASED`는 Cafe24 코드 매핑이 없음(내부 전용).
- 수정방향: N계열 매핑 적용 시 `if (newStatus.getOrder() > current.getOrder())`로 상향만 허용(내부 진행 downgrade 금지), C*/R*/E*(취소·반품·교환) 전이는 예외적으로 항상 허용.
- 영향: `Cafe24OrderSyncService.java`.

### D-080: marketSpecificData JSON 파서가 순진한 문자열 split (SP-E 후속 I-2)
- 심각도 P4 / 리스크 경량 / 상태 미착수(후속) — 출처: fix-live-defects-b-c-e 최종 리뷰
- 증상: 잠재. 현재는 안전(값에 `,`/`:` 없음). 단 accept/cancel/송장의 cafe24_order_id 조회가 이 파서에 의존하게 되어, 향후 `order_place_name` 등 값에 `,`/`:`가 섞이면 파싱 붕괴→cafe24_order_id 오독→마켓 원본번호로 폴백→Cafe24에 잘못된 id 전송 위험.
- 근본원인: `Order.getMarketSpecificDataMap()`(:160~183)이 `,`·`:` split + 따옴표 strip 방식.
- 수정방향: Jackson(ObjectMapper)로 실제 JSON 파싱 교체. setMarketSpecificDataFromMap도 대칭적으로 직렬화.
- 영향: `Order.java`.

### D-081: 스마트스토어 토큰 발급 timestamp 초/ms 오류 — 가격/재고 배치 스마트스토어 100% 실패 (라이브)
- 심각도 P1 / 리스크 표준 / 상태 수정완료·배포·라이브검증 (main `a92b1b7`)
- 증상: 소싱업체별/가격·재고 배치에서 스마트스토어만 100% 실패. Naver `/oauth2/token` 400 `"timestamp 항목의 유효 시간이 만료되었습니다"` → `[Smartstore] 토큰 발급 실패` → 해당 상품 SMART_STORE 갱신 실패. (Cafe24·11번가·쿠팡은 정상.)
- 근본원인: `SmartstoreRestClient.fetchAccessToken()`(:58)이 서명·전송 timestamp를 `Instant.now().getEpochSecond()`(epoch 초, 10자리)로 생성. Naver Commerce는 **밀리초** 요구 → 초값을 ms로 해석 시 1970년대 → 유효창 밖 "만료". 라이브 로그 `timestamp=[1783909089]`(10자리)가 증거. 형제 클라이언트 `SmartStoreOrderApiClient`는 `System.currentTimeMillis()`(ms) 사용해 정상 — 두 클라이언트 단위 불일치.
- 수정: `SmartstoreRestClient.java:58` `getEpochSecond()`→`toEpochMilli()`. 같은 `timestamp` 변수가 BCrypt 서명(:59)·form 필드(:66) 양쪽에 쓰여 한 줄로 정합 유지. L103 `X-Time-Stamp` 헤더도 동일 초/ms 잠재버그라 ms로 동반 교정. TDD: revert→RED, fix→GREEN(SmartstoreRestClientTest, MockRestServiceServer, ms 13자리/≥1e12 검증).
- 라이브검증(2026-07-13 재배포 후 배치 47059d54): 스마트스토어 46/46 성공, `timestamp 만료`·`토큰 발급 실패` 0건. 이전 100% 실패 완전 해소.
- 영향: `infrastructure/.../smartstore/client/SmartstoreRestClient.java`.
- 잔여(비차단): 테스트가 private final restClient에 리플렉션 주입(RestClient.create() 하드코딩, 생성자 seam 없음) — 향후 JDK 하드닝 시 취약. 근본 개선은 RestClient 생성자 주입화(별도).
- 참고: 쿠팡 배치 부분실패는 코드 무관 외부 상태(쿠팡 판매중지 상품 판매재개 거부·stale vendorItemId not found·일시 504) — 정직 표면화(SP-F), 결함 아님.

## 2026-07-13 라이브 개선 사이클 (통합주문/배치/배송 — 배포·검증 완료)
> superpowers 흐름으로 진행, main 직접 배포. 아래 전부 수정완료·배포됨.

### D-082: 배치 진행추적 키 불일치 — 진행률 영구 PENDING
- 심각도 P2 / 상태 수정완료·배포·라이브검증 (main `43d1171`+요약엔드포인트/프론트)
- 증상: 배치 실행 후 811건 처리해도 `sb_process_status`가 전부 PENDING → 진행률 표시 불가.
- 근본원인: `ProcessStatusService.startBatch`는 productId를 product_code 키로 저장하는데, `BatchPriceStockService`의 markSuccess/markFailed는 `product.getSbCode()`로 갱신 → 키 불일치로 매칭 실패, 상태 미갱신.
- 수정: 3개 배치 메서드의 mark* 호출을 `String.valueOf(productId)`로 통일 + 경량 `GET /api/v1/products/batch/status/{batchId}/summary`(total/success/failed/pending/done/percent) + 프론트 진행바(30초 폴링·localStorage로 새로고침 복원).
- 검증: 라이브 배치 da9a3bdf done 23→31→38→42 증가, 2145/2145 SUCCESS 확인.

### D-083: 배송정보 택배사 맵핑 미흡 — ETC/원본 enum 노출 + carrier 유실
- 심각도 P3 / 상태 수정완료·배포 (main `34af0b2`·프론트 `dffb101`)
- 증상: 배송정보에 "ETC"·"HANJIN" 등 원본 문자열 노출, 쿠팡 일부 주문 carrier 유실(-).
- 근본원인: (a) 프론트 한글맵이 CJ/우체국/롯데 3개만, (b) 백엔드 `ShippingCarrier.fromMarketCode` default→ETC(주석은 null이라면서 모순), (c) `CoupangOrderAdapter` 보정패스가 현재값 ETC일 때만 복구(null 방치).
- 수정: 프론트 전체 택배사 한글맵 + ETC/미매핑/빈값→'-'; fromMarketCode default→null; 쿠팡 보정패스 null carrier도 복구.
- 전수조사(라이브): CJ103·우체국33·롯데24 정상, null 20(송장있는데 carrier null 11=쿠팡 deliveryCompanyName 빈값), ETC 2. GMARKET 중복행 없음.

### D-084: 쿠팡 송장 반영 조용한 성공 위장 + DB/마켓 비동기화
- 심각도 P2 / 상태 수정완료·배포·라이브검증 (main `c07b7cd`·`aa2e9ae`)
- 증상: 송장수정이 DB엔 저장됐는데 쿠팡엔 반영 안 됨, 화면엔 성공 토스트.
- 근본원인: (a) `CoupangInvoiceResponse.isSuccessful()`이 최상위 code만 보고 `data.responseList[].succeed`(항목별 결과)를 무시 → 쿠팡 항목거부를 성공으로 오인. (b) `updateShippingInfo`가 마켓 실패해도 DB 저장 보존(D-069) → 화면 성공.
- 수정: isSuccessful이 항목별 succeed 검사 + `failureReason()` 표면화; `updateShippingInfo`가 `isFailed()` 시 throw→@Transactional 롤백(D-069 반전, DB/마켓 싱크); 프론트 실패 토스트 + orders 재조회.
- 검증: 라이브 백정환 건 "쿠팡 송장업로드 실패: 배송진행상태가 유효하지 않습니다" 사유 표시 + DB 롤백 확인.

### D-085: 송장 초기등록/수정 판단이 우리 전송여부(trackingSentToMarket)에 의존
- 심각도 P2 / 상태 수정완료·배포 (main `3711e32`)
- 증상: 쿠팡 동기화로 유입된 송장(우리가 안 보냄) 편집 시 초기등록 API(shipOrder) 타서 "배송진행상태가 유효하지 않습니다" 거부. 백정환(플래그 false) 실패 vs 김창식(플래그 true) 성공.
- 근본원인: `MarketplaceShippingService.sendTrackingToMarketplace`가 `trackingSentToMarket`(우리 전송 여부)로 shipOrder/updateTracking 결정 → 판매자/쿠팡 직접 등록·수정을 반영 못 함.
- 수정: 시그니처 `(OrderLineItem, boolean invoiceAlreadyExists)`. 판단을 "마켓에 송장 이미 존재(편집 전 trackingNo 존재/동기화 상태)"로 → 있으면 updateTracking(수정), 진짜 최초발송만 shipOrder. 4개 호출부(updateShippingInfo 2·ship·updateTrackingInfo)+worker EmailFetcherService 2곳 관통. **주의: 시그니처 변경이 worker 호출부 누락으로 첫 배포 컴파일 실패 → hotfix `4968d24`. 이후 공유 시그니처 변경은 전 모듈 컴파일 필수.**

### D-086: 이메일 송장 교정 누락 — SHIPPED-다른송장 미처리(가짜→진짜)
- 심각도 P2 / 상태 수정완료·배포 (main `a545497`)
- 증상: 취소 방지용 가짜 송장 선입력(상태 SHIPPED) 후 이메일로 진짜 송장(다른 번호) 도착해도 반영 안 됨.
- 근본원인: `EmailFetcherService.processIherbShipment`가 `SHIPPED+동일송장`(alreadyShipped) 또는 `PURCHASED`만 처리, `SHIPPED+다른송장`은 else로 빠져 무처리.
- 수정: alreadyShipped 다음·PURCHASED 앞에 `SHIPPED이고 이메일송장≠기존` 분기 추가 → 송장/택배사 교정 + 마켓 `updateTracking`(수정, invoiceAlreadyExists=true) 전파. 미지 해외택배사(DHL/FedEx 등 mapCarrier→ETC)는 기존 택배사 유지. worker 테스트 2건.

### D-087: @Async 마켓동기화/정산 트리거의 ActionLog SUCCESS 오기록 (실패 미인지)
- 심각도 P2 / 상태 수정완료·회귀통과(전체 ./gradlew test SUCCESS) — 커밋 대기 (2차 API분석 SYNCA-1/5/9/13 + SYNCB-6 승격)
- 수정: (a) `OrderSyncController` 쿠팡·스토어·11번가·G마켓·정산 5개 엔드포인트의 디스패치 직후 `record(SUCCESS)` 제거(STARTED·catch→FAILED 유지). (b) `CoupangOrderSyncService`에 `ActionLogService` 주입, `syncCoupangSettlement` markCompleted/markFailed 지점에서 `COUPANG_SETTLEMENT_SYNC` SUCCESS/FAILED 기록(recordSettlement 헬퍼). S1~S4 완료는 기존 `SyncCompletedEvent`→`ActionLogSyncListener`에 위임.
- 테스트: `OrderSyncControllerActionLogTest` 새 계약으로 재작성(트리거는 STARTED만·SUCCESS never·동기예외 FAILED), 신규 `CoupangSettlementActionLogTest`(완료 SUCCESS·실패 FAILED·중복스킵 무기록). 기존 수동생성 테스트 2건(OrderSyncEventEmission·OrderAddressProtection) 생성자 인자 보정.
- 증상: 쿠팡·스마트스토어·11번가·G마켓 주문동기화와 쿠팡 정산동기화가 실제 실패해도 활동로그에 SUCCESS로 남거나(정산은 실패가 아예 안 남음), 운영자가 진행현황에서 실패를 인지하지 못한다.
- 근본원인: `OrderSyncController`가 STARTED 기록 후 `@Async("syncTaskExecutor")` 서비스를 호출하고, 비동기 디스패치 직후 동기 실행 흐름에서 `record(..., SUCCESS, ...)`를 남긴다(`OrderSyncController.java:64/94/123/149/204`). @Async라 즉시 반환되므로 try는 항상 성공 → **결과와 무관하게 SUCCESS**. try/catch는 백그라운드 예외를 못 본다.
  - S1~S4(주문동기화): 서비스가 성공/실패 양쪽에 `SyncCompletedEvent`를 발행하고 `ActionLogSyncListener`가 이미 정확한 `{MARKET}_SYNC` SUCCESS/FAILED를 기록한다 → 컨트롤러 SUCCESS는 **중복+가짜** 이중기록.
  - S5(`COUPANG_SETTLEMENT_SYNC`): `syncCoupangSettlement`은 `SyncCompletedEvent`를 발행하지 않아 리스너 기록이 없다 → 유일한 ActionLog가 컨트롤러의 가짜 SUCCESS. **실제 정산 실패가 어디에도 FAILED로 안 남는 진짜 BUG.**
  - 과거 F-SYNC-3(1차 분석 기반)이 "트리거 성공 기록" 의도로 이 SUCCESS를 추가했으나, 리스너 도입(D-042) 이후 중복·오기록이 됨.
- 수정계획: (a) S1~S4 컨트롤러의 즉시 `record(SUCCESS)` 제거 — STARTED와 catch→FAILED(동기 디스패치 실패용)는 유지, 완료는 리스너에 위임. (b) S5 정산은 `CoupangOrderSyncService`에 `ActionLogService` 주입, `syncCoupangSettlement`의 markCompleted/markFailed 지점에서 `COUPANG_SETTLEMENT_SYNC` SUCCESS/FAILED 기록 + 컨트롤러 가짜 SUCCESS 제거. (c) S6 통관은 동기 실행이라 정확 → 불변.
- 영향 범위: `OrderSyncController`(api) + `CoupangOrderSyncService`(core). 다마켓 관측성 계약 → 표준~중대. TDD Red(컨트롤러 SUCCESS 미기록 · 정산 서비스 완료기록) 후 수정.

### UX 개선(비결함, 동반 배포)
- 배치 업데이트 진행바(D-082 동반), 상품관리 가격/재고 stockStatus 모달 시드, 통합주문 그리드 **구매/배송정보 셀 병합(rowSpan, 배송사/송장·계정/공급처/주문#/할인 stack) + 더블클릭 통합 인라인편집(택배사+송장 한 세트 1회 저장→마켓 1회 호출) + placeholder + 수정버튼/모달 제거**, 인라인 성공/실패 토스트.

### D-088: 옥션/G마켓 신규주문(발주확인 전)이 "구매준비"로 오분류 — Cafe24 N10 매핑 오류
- 심각도 P2 (오동작 — 주문상태 오표시, 운영자 발주확인 워크플로우 혼란) / 리스크 등급 표준(마켓 주문상태 계약) / 상태 수정완료·회귀통과(전체 ./gradlew test SUCCESS)·push대기 (2026-07-20 결과서 20260720_1028)
- 라이브 확증(2026-07-20, /cafe24/preview): 주문 2566278285(cafe24_order_id 20260719-0000018, auction)의 실제 Cafe24 `order_status`=**N10**. 대조군 gmarket 4469254653=N20(발주확인 후, PREPARING 정상)·4469438260=N30(배송중, SHIPPED 정상). N10→PREPARING 오분류 100% 확정.
- 신고: 주문 2566278285(서종수, 옥션)이 실제로는 신규주문(주문확인 전)인데 시스템은 "구매준비(PREPARING)"로 표시.
- 위치: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/Cafe24OrderSyncService.java:326`
- 근본원인: `mapStatus()`가 `case "N10", "N20", "N21", "N22" -> PREPARING`으로 **N10(상품준비중)을 발주확인 후 상태에 잘못 포함**. 그러나 같은 코드베이스의 발주확인 액션(`Cafe24OrderApiClient.java:67` `ACCEPT_STATUS="N20"`)이 발주확인의 *결과*를 N20으로 정의하므로 **N10=발주확인 전(신규주문)**이 논리적 필연. 옥션/G마켓은 결제완료 상태로 유입되어 Cafe24가 신규건에 N10을 부여 → NEW여야 할 건이 PREPARING으로 표시됨.
- 모순 근거: `Cafe24OrderSyncService.java:326` + `Cafe24OrderSyncServiceTest.java:167`("N10=발주확인 후" 가정)이 `Cafe24OrderApiClient`의 발주확인 타깃(N20)과 정면충돌. 사용자 신고 증상이 오분류와 정확히 일치.
- 수정계획(TDD): mapStatus에서 N10을 NEW 그룹으로 이동 — `case "N00","N02","N10" -> NEW`(발주확인 전), `case "N20","N21","N22" -> PREPARING`(발주확인 후). `Cafe24OrderSyncServiceTest`의 잘못된 기대값(line 167-168) 정정 + N10→NEW·N20→PREPARING 회귀 테스트 추가.
- 유의: `Cafe24OrderApiClient.java:66` "상태코드 라이브 검증 대상" 주석 — N20 값 잠정. 다만 (신고증상 + 발주확인 타깃) 2독립근거로 진단 신뢰 높음. 라이브 확정은 주문 2566278285의 실제 Cafe24 order_status 조회로 확인 가능(프로덕션 read 승인 필요).
- 영향: Cafe24OrderSyncService 단일 파일(core) + 테스트. 배포 후 기존 N10 상태로 저장된 옥션/G마켓 미확인 주문의 재동기화 시 자동 NEW 교정.

### D-089: 배치 업데이트 진행바가 클라이언트(브라우저)별로 격리 — 동업자 간 상호 미표시
- 심각도 P2 (오동작 — 다중 운영자 협업 시 배치 진행 상호 미가시) / 리스크 등급 표준(프론트 + SSE 이벤트 추가) / 상태 수정완료·검증중 (A안, 2026-07-20)
- 수정(2026-07-20, A안): `BatchStartedEvent`(core) 신설 → `BatchController.startBatchWithLog`(4개 엔드포인트 공통)에서 발행 → `SseNotificationController.onBatchStarted`가 `BATCH_STARTED`(payload=batchId) 전역 방송. `BatchUpdatePage`가 `/notifications/subscribe` 구독 → BATCH_STARTED 수신 시 startTracking(자기 배치는 batchIdRef로 중복 회피), BATCH_COMPLETED/FAILED 수신 시 즉시 요약 갱신. 테스트: `SseNotificationBatchTest`에 batchStartedEventName/Payload 2건 + 기존 BatchController 생성자 4곳 publisher mock 보정. 프론트 tsc/build 통과. (단일 batchId 슬롯: 최근 시작 배치가 표시됨 — 동시 다른 배치는 latest-wins.)
- 신고: "내가 배치업데이트한 것은 동업자 컴퓨터에서 안 보이고, 동업자가 한 것은 내게 안 보인다."
- 위치: `frontend/src/pages/BatchUpdatePage.tsx:5,64,77` (localStorage 단일 구동) · `backend/api/.../SseNotificationController.java`(START 이벤트 부재)
- 근본원인: 진행 카드가 `localStorage['sbshop.activeBatchId']`에 저장된 batchId로만 폴링(`startTracking`). batchId는 배치를 **개시한 브라우저에만** 반환·저장되므로 타 클라이언트/타 기기는 그 batchId를 알 수 없어 진행바 미표시. 배치 상태는 공유 DB(`ProcessStatus`)에 있고 전역 SSE 브로드캐스트(`SseNotificationController.emitters`)도 존재하나, (a) BatchUpdatePage가 SSE를 구독하지 않고, (b) SSE는 `BATCH_COMPLETED/FAILED`(완료)만 방송하고 **배치 시작(batchId 전파) 이벤트가 없어** 타 클라이언트가 진행 중 배치를 발견할 경로가 전무.
- 참고: `ProcessStatusPage`는 SSE(BATCH_COMPLETED) 구독 + DB 조회로 완료건은 공유 가시. 실시간 진행"바"만 개시 브라우저 국한.
- 수정방향(택1, TDD):
  - A(권장·실시간): 배치 시작 시 `BATCH_STARTED` SSE 이벤트(batchId 포함) 방송 + BatchUpdatePage가 `/notifications/subscribe` 구독해 수신 시 startTracking. 기존 전역 emitters 재사용.
  - B(폴링): 진행 중(pending>0) batchId 목록 엔드포인트 신설 + BatchUpdatePage 마운트/주기 폴링으로 활성 배치 자동 추적. SSE 불요, 폴링 지연 존재.
- 영향: 프론트 BatchUpdatePage + (A안) api SseNotificationController/BatchController·이벤트 1종. 백엔드 배치 실행 로직 불변.

### D-090: 옥션/G마켓 발주확인이 "AUCTION credentials not found"로 실패 — Cafe24 기반 마켓 크레덴셜 필수 오요구
- 심각도 P1 (기능 불능 — 옥션/G마켓 발주확인 전건 실패) / 리스크 등급 표준(발주확인 경로 1라인) / 상태 수정완료·검증중 (2026-07-20)
- 수정(2026-07-20): `OrderService.confirmOrder:96` `.orElseThrow(...)` → `.orElse(null)`. 회귀 테스트 `OrderServiceStateGuardTest.ConfirmOrderCafe24NoCredential`(AUCTION 크레덴셜 없이 발주확인 성공·null credential 포트 위임·NEW→PREPARING). 전체 `./gradlew test` 통과. 잔여: Cafe24 acceptOrder PUT 자체(status N20·body)는 `Cafe24OrderApiClient.java:66` "라이브 검증 대상" — 실제 발주확인 성공은 배포 후 라이브 확인 필요(크레덴셜 차단만 해소, PUT 포맷은 별개 미검증).
- 신고: 서종수 옥션 주문(196) 발주확인 시 오류. 로그: `주문 196 접수 확인 실패: AUCTION credentials not found`.
- 위치: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderService.java:96`
- 근본원인: `confirmOrder`가 `credentialRepository.findByMarketType(marketType).orElseThrow(...)`로 마켓 크레덴셜을 **필수**로 요구. 그러나 옥션/G마켓 발주확인은 `Cafe24AuctionOrderAdapter/Cafe24GmarketOrderAdapter.acceptOrders`가 **credential 파라미터를 무시**하고 Cafe24 토큰(Cafe24TokenManager)으로 호출 → AUCTION/GMARKET 크레덴셜은 DB에 없음(설계상 Cafe24가 연동주체). 크레덴셜 조회에서 실제 Cafe24 호출 전에 실패.
- 노출 경위: [[D-088]] 수정 전에는 N10이 PREPARING으로 오분류돼 `hasProgressedOrEnded` 가드("이미 발주확인됨")에서 차단 → 크레덴셜 조회에 도달 못 함. D-088(N10→NEW)로 발주확인이 실행되며 잠재 버그 노출.
- 판정근거: 같은 코드베이스의 송장·취소 경로(`MarketplaceShippingService.java:73,141`, `OrderShipProcessor.java:54`)는 이미 `findByMarketType(...).orElse(null)` + "Cafe24 기반(G마켓/옥션)은 cred 없어도 포트 위임" 주석으로 올바르게 처리. **발주확인 경로만 `.orElseThrow`로 누락.**
- 수정방향(TDD): `OrderService.confirmOrder`의 크레덴셜 조회를 `.orElseThrow(...)` → `.orElse(null)`로 변경(기존 송장/취소 패턴과 일치). Cafe24 어댑터는 null 무시, 타 마켓은 어댑터 내부에서 사용(송장 경로와 동일 계약).
- 영향: `OrderService.java` 1라인 + 회귀 테스트.

### D-091: Cafe24 발주확인 PUT 포맷 오류 — 잘못된 엔드포인트·바디·상태값
- 심각도 P1 (기능 불능 — 옥션/G마켓 발주확인 API 호출 실패) / 리스크 등급 표준(마켓 API 계약·라이브 확증) / 상태 수정완료·검증중 (2026-07-20)
- 신고: 발주확인 재시도 시 "Order 196: 마켓플레이스 주문 접수 실패: Cafe24 API PUT 호출 실패". [[D-090]] 크레덴셜 차단 해소 후 실제 PUT이 노출한 후속 결함. 사용자가 Cafe24 정식 API 스펙 제공.
- 위치: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/client/Cafe24OrderApiClient.java:acceptOrder`
- 근본원인: `acceptOrder`가 `PUT /admin/orders/{id}` + `{shop_no, request:{status:"N20"}}`로 호출했으나, Cafe24 배송상태처리 스펙은 `PUT /admin/orders`(경로에 id 없음) + `{shop_no, requests:[{order_id, process_status:"prepare"}]}`. 3가지 오류: ① 엔드포인트에 id 붙임 ② `request`(객체)→`requests`(배열) ③ `status:"N20"`(읽기 order_status 코드)→`process_status:"prepare"`(쓰기 어휘). 읽기(order_status: N00/N10/N20…)와 쓰기(process_status: prepare/prepareproduct/hold/unhold)가 별개 어휘였음.
- 수정(2026-07-20, 라이브 스펙 기반): `acceptOrder`를 `PUT /admin/orders` + `requests:[{order_id, process_status:"prepare"}]`로 재작성. order_item_code(품주코드)는 sbshop 미보존이라 생략(주문 전체 적용). 회귀 테스트 `Cafe24OrderApiClientStatusTest.acceptOrderSendsPut` 스펙대로 정정. `:infrastructure:test` 통과.
- 잔여(후속 결함 후보): `cancelOrder`(CANCEL_STATUS="C40" + 동일 구 엔드포인트/바디)도 같은 포맷 오류일 가능성 높음. 단 Cafe24 취소는 process_status 어휘 밖(별도 취소/환불 API)이라 정식 스펙 확보 후 수정 필요 — 이번 스코프 제외. 실제 발주확인 성공 여부는 배포 후 라이브 확인.

### D-092: 소스이미지 크롤 재게시 — 대표이미지가 마켓에 반영 안 됨(마켓별 교차 결함)
- 심각도 P2 (오동작 — 상품 이미지/상세 재게시 불완전) / 리스크 등급 중대(마켓 API 계약·다마켓, 라이브 검증 필수) / 상태 진단완료·수정보류(라이브 확증 필요)
- 신고 매트릭스: 쿠팡·스토어=대표이미지·상세 둘 다 ✗ / G마켓=둘 다 ✓ / 옥션·11번가=대표이미지 ✗·상세 ✓.
- 진단(4에이전트 병렬 + 리더 코드확인):
  - 진입: `ProductManageUseCase.updateImagesAndHtml`→`republishToMarkets`→마켓별 `MarketClient.syncImagesAndHtml(marketItemId, currentRawData, hostedImages, newHtml)`. GMARKET/AUCTION은 marketType 클라이언트 부재로 스킵되고, 실제 반영은 CAFE24 등록행(ESM 백필 identifiers) 1개를 Cafe24MarketClient가 처리 → G마켓/옥션 공통 경로.
  - **확정 비대칭(핵심 가설)**: 작동하는 Cafe24(G마켓)는 이미지를 **base64 업로드**(`Cafe24MarketClient` `POST /admin/products/{id}/images`, image_upload_type=B, detail_image/list_image=dataUri). 실패 마켓들은 **우리 외부 hostedImage URL을 대표이미지 필드에 그대로** 전송: 쿠팡 `items[0].images[].vendorPath`(CoupangMarketClient:~269-273), 스토어 `originProduct.images.representativeImage.url`(SmartstoreMarketClient:168-172,314-332), 11번가 `prdImage01~04`(ElevenstMarketClient:117-134,165-181). 마켓이 외부 URL을 대표이미지로 안 받고 조용히 무시 → 대표이미지 ✗. 상세 HTML은 외부 `<img>` URL 렌더되어 ✓.
  - 마켓별 부가:
    - 스토어 상세 ✗: `SmartstoreMarketClient:175` `newDetailHtml.replace("\"","\\\"").replace("\n","")` 수동 이스케이프 후 Jackson이 재이스케이프 → **이중 이스케이프**로 detailContent 손상(코드 확정 버그). currentRawData 미러도 최상위 representativeImage로 스키마 불일치(183-188, 미러 한정 2차).
    - 쿠팡 둘 다 ✗: items[0].images/contents를 rawData PUT + `/approvals`. 외부 vendorPath는 쿠팡이 받을 수도 있어(추정) 승인요청·상태전이 등 별개 원인 가능 — 라이브 응답 확인 필요.
    - 11번가 대표 ✗: prdImage01에 외부 URL. (부차: 대표이미지 PUT은 "동일" 응답을 성공 처리 안 함 124-127 vs 상세 145 — 비대칭, 단 신규 이미지엔 무관.)
    - 옥션 대표 ✗: Cafe24 base64 업로드가 옥션 오픈마켓 연동으로 전파 안 되는 ESM 설정(코드 밖) 가능 — 라이브 확인 필요.
- **publish↔resync 대조 결과(2026-07-20, 리더 확정 — 앞선 가설 반증)**: 3개 실패 마켓 모두 publish()와 syncImagesAndHtml()이 **동일한 외부 호스팅 URL**을 이미지에 사용(스토어·쿠팡은 이미지/이스케이프 코드까지 동일). ∴ "외부 URL 미수용"·"detailContent 이중 이스케이프"는 **근본원인 아님**(등록이 같은 코드로 작동). 진짜 차이는 **CREATE(publish=새 페이로드 POST, 작동) vs UPDATE(resync=GET 전문→필드수정→PUT, 실패)** 라운드트립.
  - 스토어: `publish:43,48`과 `syncImagesAndHtml:172,175`이 `applyImages`·동일 이스케이프 공유 → CREATE만 되고 PUT UPDATE는 안 되는 Naver 의미 차이(추정, 라이브 필요).
  - 쿠팡: `publish:56-60`(POST typed payload) vs `syncImagesAndHtml:265-273`(GET rawData.firstItem.images 교체 후 통째 PUT+/approvals). 이미지 ID 없는 신규 이미지 무시/별도처리 가능(추정).
  - 11번가: `buildProductXml:prdImage01`(POST) vs `applyRepresentativeImages`(productinfo **GET 응답 XML**에 정규식 `<prdImage0N>` 치환 후 PUT). **GET 응답 스키마에 prdImage 태그 없으면 regex no-op**(가장 구체적·검증가능 — productinfo GET 실응답 스키마 확인 필요).
- 다음 단계: 마켓별 UPDATE-이미지 API 정확한 계약 확증 필요(라이브 GET 실응답 스키마 or API 문서). 등록은 되므로 resync를 delete+republish 또는 마켓 전용 이미지수정 엔드포인트로 전환하는 근본 수정 후보. 확증 전 배포 금지(중대·다마켓, 작동중 G마켓 회귀 위험).
- 상태: 진단 refined·미수정. 마켓 1개씩 라이브 확증→TDD 수정 권장(11번가 productinfo 스키마부터).
- **계측 배포(2026-07-20)**: 4개 `syncImagesAndHtml`(쿠팡·스토어·11번가·Cafe24)에 읽기전용 `[D092]` 로깅 추가 — GET 응답 스키마·PUT/POST 요청 바디·응답(현재 다 버려지던 값). 동작 무변경(로깅만), 진단 후 제거 예정.
- **★라이브 확정 근본원인(2026-07-20, [D092] 로그)★**:
  - **쿠팡**: `PUT /v2/providers/seller_api/apis/api/v1/marketplace/seller-products/{id}` → **404 PRECONDITION_FAILED "No matched http method ... did you mean GET,DELETE?"**. 상품수정 PUT을 GET/DELETE 전용 경로(id 포함)에 보냄. 수정: **id 없는 `PUT .../seller-products`**(sellerProductId는 바디)로 변경. `/approvals`는 별개 확인.
  - **스토어**: `PUT /v2/products/origin-products/{id}` → **400 BAD_REQUEST "올바른 이미지 파일이 아닙니다."**. Naver가 외부 R2 URL(representativeImage.url)을 거부 → **Naver 이미지업로드 API로 먼저 업로드 후 Naver URL 사용** 필요. 이미지 거부로 PUT 전체 실패 → 상세도 collateral 실패(이미지 고치면 상세도 통과). GET 스키마·images 스키마·detailContent는 정상.
  - **11번가**: `GET /rest/prodservices/productinfo/{prdNo}` → **`<AuthMessage><resultCode>-997</resultCode>등록된 API 정보가 존재하지 않습니다`**(인증에러, 상품데이터 아님). applyRepresentativeImages가 에러XML에 no-op(prdImage 포함=false)→PUT에 에러XML 전송→11번가 JAXBException. **에러가드(`ERROR`/`resultCode>500`)가 -997·JAXBException 미포착→"재게시 완료" 가짜성공 오기록**. 상세설명 POST(updateProductDetailCont)는 resultCode 000 진짜성공. 수정: (a) productinfo GET 엔드포인트/인증 교정(-997 해소), (b) 에러가드에 AuthMessage/-997/JAXBException 추가(가짜성공 차단).
  - **옥션(Cafe24)**: description PUT resp(len~3900)·이미지 POST resp(len 491) 정상 반환 = Cafe24 API 성공. 옥션 대표이미지 미전파는 Cafe24 상품→ESM 옥션 연동 설정(코드 밖). G마켓은 정상.
- 수정 스코프: 쿠팡(엔드포인트 교정, 확신 높음)·11번가(productinfo GET + 에러가드)·스토어(Naver 이미지업로드 신규, 큼). 옥션은 코드밖. 각 TDD 후 계측 제거.
- **수정 1차(2026-07-20, 쿠팡+11번가 TDD)**:
  - 쿠팡: `syncImagesAndHtml` 상품수정 PUT을 id 없는 base(`.../seller-products`)로, 승인은 `.../seller-products/{id}/approvals` 유지(GET/DELETE는 id경로 유지). `CoupangMarketClientImagesTest` 수정 PUT 어서션을 no-id로 정정.
  - 11번가: GET을 `productinfo`→`product`(‑997 해소 시도), GET/PUT 에러가드에 AuthMessage/‑997/JAXBException/`!<Product>` 추가(가짜성공 차단). `ElevenstMarketClient*Test` mock 경로 product로 정정 + 신규 `throwsWhenGetReturnsAuthError`.
  - 계측 [D092] 유지(라이브 재검증용). 전체 `./gradlew test` 통과. 스토어(Naver 업로드)·옥션(ESM)은 다음 라운드/코드밖.

### D-092 라이브 재검증 (2026-07-20, 쿠팡+11번가 1차 수정 배포 후)
- **쿠팡**: 404 PRECONDITION_FAILED **해소**(id 없는 PUT 성공). 그러나 새 사실 2개 — ① 상품수정 PUT resp `{"code":"SUCCESS", message:"필수 구매 옵션이 존재하지 않습니다/유효하지 않은 구매 옵션..."}` (라운드트립 payload의 구매옵션 검증 경고), ② 승인요청 resp `{"code":"ERROR","message":"'임시저장' 상태의 상품만 승인 요청 가능합니다."}` → **이미 승인/판매중 상품 수정엔 `/approvals`가 부적합**. 쿠팡 승인상품 수정 플로우(부분수정 API or 자동 승인대기 전환) 확인 필요. 이미지 실제 반영 여부 미정(승인대기 지연 가능성).
- **11번가**: 에러가드 수정 성공 — 이제 가짜성공 없이 `failed`로 정직하게 잡힘. 그러나 `/rest/prodservices/product/{prdNo}`도 `/productinfo`와 동일하게 **-997 "등록된 API 정보가 존재하지 않습니다"** → 상품조회 API가 해당 openapikey에 미등록/미허용(계정측). 주문·상세수정 API는 정상. **코드밖(11번가 셀러 API 설정) 가능성 높음** — 셀러오피스에서 상품조회 API 활성화 or 올바른 조회 엔드포인트/인증 확인 필요.
- 스토어: 미착수(Naver 이미지업로드 신규). 옥션: Cafe24 ESM 설정(코드밖).
- 다음: 쿠팡 승인상품 수정·승인 플로우 확정(문서/셀러오피스), 11번가 -997 계정측 해소. 확정 후 2차 수정.

### D-092 2차 확정 (2026-07-20, 사용자 제공 문서 기반)
- **쿠팡 근본원인 확정(문서 `쿠팡상품수정(승인필요).pdf`)**: 상품수정(승인필요) = `PUT .../seller-products`(id無), body에 **`requested` 필드**. `false`(기본)=임시저장, `true`=저장+자동승인요청. 우리 코드는 GET한 rawData 그대로 PUT→requested 부재→**임시저장으로 떨어짐**(11583618874 임시저장 재현). 별도 `/approvals`("상품 승인 요청")는 임시저장 전용이라 승인상품 편집엔 부적합.
  - **수정**: `rawData.put("requested", true)` + `/approvals` 호출 제거. 테스트 정정(requested=true 검증, approvals never). `:infrastructure:test` 통과. 라이브 재검증 대기.
  - **잔여 리스크**: PUT resp에 "유효하지 않은 구매 옵션 값/단위(개당 용량/중량/정)" 경고 동반. 문서 Error Spec의 "Invalid Attribute Value(s)" — 라운드트립 attributes가 카테고리 메타와 불일치 가능. requested=true로 승인요청돼도 쿠팡 심사에서 attribute 반려 가능성 → 라이브 확인 필요.
- **11번가 상품조회 문서(`11번가-상품조회.pdf`)**: 제공된 건 **셀러상품조회**(`GET /rest/prodmarketservice/sellerprodcode/{sellerprdcd}`, 판매자상품코드로 조회, **제한 필드만**: prdNo/selStatCd/selPrc 등). 상품수정(전체XML 덮어쓰기)에 필요한 전체 상품 XML은 **신규상품조회/다중상품조회**(미제공). 현재 `/rest/prodservices/productinfo/`·`/product/` 둘 다 -997 → 폐기 추정. **필요**: 신규상품조회 or 다중상품조회 문서(전체 XML 반환 엔드포인트), 또는 우리 DB에서 전체 XML 재구성 방식. 에러가드 수정으로 현재는 정직하게 failed 처리됨.
- 상태: 쿠팡 2차수정 배포·재검증 대기. 11번가 전체조회 엔드포인트 확정 대기. 스토어(Naver 이미지업로드)·옥션(ESM) 미착수.

### D-092 11번가 조회 문서 분석 (2026-07-20)
- **신규상품조회**(`GET /rest/prodmarketservice/prodmarket/{prdNo}`) 응답에 htmlDetail·selPrc 등은 있으나 **prdImage01·brand·prdTypCd·hsCode·ProductCert 등 상품수정 필수 다수 누락** = 편집용 전체 XML 아님. 셀러상품조회도 제한필드. → **어떤 11번가 조회도 상품수정용 전체 XML을 반환하지 않음.** productinfo(-997)는 폐기.
- **결론**: 11번가 대표이미지는 라운드트립(조회→수정) 불가. `buildProductXml`(publish 등록 시 사용, Product 7필드+하드코딩 기본값으로 완전한 등록XML 생성 → 등록 성공 실증)을 **재사용**해 새 hostedImages로 전체 XML 재구성 후 PUT하는 방식이 정답(상품수정=등록과 동일 XML 포맷).
- **필요 변경**: `MarketClient.syncImagesAndHtml`에 Product 전달(현재 marketItemId/rawData/images/html만) 또는 11번가 전용 경로. ProductManageUseCase는 이미 Product 보유. 크로스컷 인터페이스 변경 → 4개 클라이언트+UseCase+테스트. 사용자 확인 후 진행 예정.
- 쿠팡 requested=true 수정 배포완료(9467fd8) — 라이브 재검증 대기(승인대기 전환·구매옵션 경고).

### D-092 11번가 근본 수정 (2026-07-20, 사용자 승인)
- **인터페이스 변경**: `MarketClient.syncImagesAndHtml`에 `Product` 첫 인자 추가(4개 클라이언트+`ProductManageUseCase.republishToMarkets`). ProductManageUseCase는 재게시 직전 새 이미지·상세HTML로 갱신된 product를 전달. Coupang/SmartStore/Cafe24는 인자만 추가·동작 불변.
- **11번가 재작성**: 조회로 전체 편집 XML을 얻을 수 없으므로(신규/셀러상품조회 필드 누락·productinfo -997), 등록 때 쓰는 `buildProductXml(product)`(Product+기본값으로 완전한 상품 XML 생성, publish 등록 성공으로 완전성 실증)를 재사용해 재구성 → `PUT /rest/prodservices/product/{prdNo}`로 대표이미지+상세HTML을 1회에 반영. 기존 GET(productinfo/regex)·별도 상세POST 제거. 실패가드(ERROR/500/Exception/AuthMessage) 유지. 성공=ClientMessage resultCode 200/210.
- 테스트: Elevenst 2개 테스트 재작성(buildProductXml PUT 전문에 새 prdImage01·htmlDetail 포함·에러 throw·AuthMessage throw), core republish 테스트 2개 mock 시그니처 보정(Product any() 추가), Coupang/SmartStore 테스트 null Product 인자.
- 잔여 리스크: buildProductXml이 상품수정 필수필드 일부 누락 가능(dispCtgrNo·인증 등). 단 publish 등록이 같은 XML로 성공하므로 수용 추정 — 라이브 PUT 응답으로 최종 확인(누락 시 그 필드 보강). 상품수정은 전체 덮어쓰기라 라이브 첫 검증 신중.

### D-092 3차 라이브 검증 (2026-07-20, 인터페이스변경+11번가 재작성 배포 후)
- **쿠팡: 대표+서브이미지 반영 성공 ✓**(사용자 육안 확인). 상세설명은 미반영 — 같은 PUT resp `code:SUCCESS`+"필수 구매 옵션" 경고. requested=true로 승인대기 전환됐을 가능성 → 쿠팡 심사 후 상세 반영 대기 가능성(재확인 필요). 이미지 즉시반영 vs 상세 지연 비대칭은 추가 관찰.
- **11번가: 상품수정 PUT 실패** — resp `<resultCode>500</resultCode> 원재료 유형 코드 필수 입력 대상 카테고리입니다`. buildProductXml이 `rmaterialTypCd` 등 상품수정 필수필드 다수 누락(원재료·dispCtgrNo·인증정보 ProductCertGroup/Cert·상품정보제공고시 ProductNotification·selMthdCd·prdTypCd·hsCode·asDetail·rtngExchDetail). 일부(카테고리·인증·고시)는 우리 DB에 없어 기본값 대체 곤란. → 전체 상품수정 재구성은 whack-a-mole + 인증/고시/카테고리 벽 가능성.
- 판단: 11번가 대표이미지 API 수정은 데이터 완전성 한계로 난이도 높음. 상세설명은 기존 전용 API(updateProductDetailCont, resultCode 000 실증)로 확실히 가능 → **상세는 전용 API 복원, 대표이미지는 buildProductXml 필수필드 보강(신규상품조회로 dispCtgrNo 획득 + 기본값) 반복 or 보류** 결정 필요(사용자).

### D-092 11번가 필수필드 기본값 채우기 (2026-07-20, 사용자 결정)
- 사용자 통찰: 11번가가 등록 후 일부 필드를 필수로 승격 → 홈페이지 수정에서도 동일 에러. 기본값으로 채워 성공시키는 방식 채택.
- buildProductXml에 기본값 추가: selMthdCd=01(고정가), prdTypCd=01(일반배송), rmaterialTypCd=05(원산지 상세설명참조), minorSelCnYn=N, suplDtyfrPrdClfCd=01(과세), dlvClf=02(업체배송), dlvCnAreaCd=01(전국), dlvWyCd=01(택배), dlvEtprsCd=00034(CJ대한통운·사용자지정), asDetail=., rtngExchDetail=.
- **한계/권고**: 전체 덮어쓰기라 기본값이 기존 택배사·배송비·AS안내를 덮어쓸 위험. 견고한 해법은 **상품수정 전문과 동일한 전체 필드를 돌려주는 11번가 상품상세조회 API로 round-trip**(이미지만 교체·나머지 원값 보존). 다중/신규/셀러조회는 요약이라 인증/고시/주소코드 부재 → 11번가 고객센터에 전체 편집전문 조회 API 문의 권고. 그때까지 기본값으로 진행하며 다음 에러(고시/인증/주소코드 등) 시 추가 대응.

### D-092 11번가 라운드트립 불가 원인 분석 (2026-07-20)
- 배송비 기본값 후 다음 에러: "출고지 주소를 확인해주세요"(addrSeqOut 필수) — 예측한 주소코드 벽 도달.
- **신규상품조회(전체조회)로 부족한 이유**: 상품수정=등록과 동일 전체전문(수십필드) 요구. 신규상품조회는 요약이라 상품수정 필수필드 중 미제공: prdImage(우리가새로넣음), brand/prdSelQty(우리DB), 판매방식/유형/원재료/부가세/배송비종류/택배사(기본값가능) — **그러나 addrSeqOut/addrSeqIn(출고지·반품지 주소코드=판매자실제값), ProductCertGroup/Cert(인증), ProductNotification(고시), ProductOption(옵션)은 조회 안 되고 기본값도 위험**(실제 배송지·법적고시·옵션 훼손).
- **결론**: 11번가엔 상품수정 전문과 동일한 전체 편집전문을 돌려주는 조회 API가 없음(신규/다중/셀러조회 모두 요약). 라운드트립 근본 불가.
- **가능 경로**: (a) 주소코드는 판매자레벨 1값 → 출고지/반품지 주소조회 API로 획득 or 셀러 제공(벽 아님). (b) 인증/고시는 카테고리 표준 기본값(건강식품)으로 defaultable하나 규정리스크. (c) 옵션상품이면 옵션 소실 → 옵션 보존 불가면 대표이미지 자동수정 포기. (d) 근본해법=등록시 전체전문을 우리DB에 저장→수정시 재사용(기존상품은 데이터 없음). → 11번가 고객센터에 "상품수정용 전체 편집전문 조회 API 유무" 문의 권고.

### D-092 11번가 buying-agent 라운드트립 이식 (2026-07-20, 결정적 해법)
- **형제 프로젝트 buying-agent에 검증된 완성 구현 발견**(사용자 제보). 핵심: 상품수정 전체XML 조회 엔드포인트는 **`GET /rest/prodmarketservice/prodmarket/{prdNo}`(신규상품조회)** — 이게 현재 전체 전문(옵션·카테고리·인증·고시 포함) 반환. 우리가 쓰던 `/rest/prodservices/productinfo/`(-997)는 폐기 경로였음.
- **이식**: sbshop `ElevenstMarketClient.syncImagesAndHtml`을 buildProductXml 재구성 → **GET 라운드트립 + 누락 필수필드 주입**으로 교체. GET 전문에서 htmlDetail·prdImage01~05 정규식 치환 + 주입: dlvEtprsCd=00034(CJ), rmaterialTypCd=03+ProductRmaterial, selMthdCd=01, 원산지(orgnTypCd=02 미국/1405), 배송·반품(dlvCstInstBasiCd=01·dlvCstPayTypCd=03·bndlDlvCnYn=N·rtngd/exchDlvCst=7000·asDetail·rtngExchDetail), **주소코드 addrSeqOut=5(미국 출고지)·addrSeqIn=3(국내 반품지)·outsideYnOut=Y·outsideYnIn=N**(판매자 실계정값). 메타태그(message/validateMsg/nResult) 제거. 성공=resultCode 200/210.
- 옵션·카테고리·인증·고시는 GET 전문에 있는 원값 보존(재구성 방식의 소실 문제 해결). buildProductXml은 publish(등록) 전용으로 잔존.
- 테스트: Elevenst 2개 재작성(GET 라운드트립·주소코드 주입·메타제거·resultCode 성공판정). 잔여: 인터페이스의 Product 인자는 이제 미사용(GET 방식) — 정리 후보.

### D-092 나머지 마켓 buying-agent 대응 (2026-07-20)
- **쿠팡: 완료**(사용자 확인 — 대표이미지+상세설명 성공). requested=true 수정으로 해결.
- **N스토어(스토어): buying-agent 네이버 이미지 업로드 이식**. "올바른 이미지 파일이 아닙니다"(400)=Naver가 외부 R2 URL 거부 → `SmartstoreRestClient.uploadImages`(멀티파트 POST /v1/product-images/upload) 추가, `SmartstoreMarketClient`에 downloadImage/ensureImageExtension/uploadImagesToNaver + customsTaxType=INCLUDED 주입 + detailContent 이중이스케이프 제거. 네이버 업로드 URL로 representativeImage 등록. 실패 시 외부 URL 폴백.
- **옥션(Cafe24): 코드 이슈 아님**. buying-agent Cafe24 syncImagesAndHtml이 sbshop과 완전 동일(base64 detail/list/tiny/small_image POST). Cafe24 API 호출 성공·G마켓 전파 정상이나 옥션 미전파 = **Cafe24→옥션(ESM+) 연동 설정**(코드 밖). 사용자 Cafe24 마켓연동 이미지동기화 설정 확인 필요.
- **G마켓: 정상**(Cafe24 경로).

---

### D-093: 가격 배치가 사용자 마진/할인/최소마진 정책을 무시 — 하드코딩 15/20/5000이 실가격 지배 (P1)

- 심각도: **P1 (기능 불능 — 사용자가 설정한 가격정책이 실제 판매가에 반영되지 않음)**
- 리스크 등급: **중대** (실 판매가·전 상품 마진에 직접 영향, 다마켓, 스케줄러 행위 변경)
- 조사 계기: 2026-07-21 사용자 신고 — 배한순·안희수·이호엽·강정란 주문건 판매가 이상. "배치로 마진율 10%·할인율 15%·최소마진 3500 설정했는데 반영 안 된 듯".
- 위치:
  - `backend/worker/src/main/java/com/sbshop/agent/worker/scheduler/BatchScheduler.java:34-37` — 매일 05:00 KST 정기 배치가 **`new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5000")` 하드코딩** 파라미터로 iHerb 전 상품 재가격.
  - `backend/api/src/main/java/com/sbshop/agent/api/controller/BatchController.java:80-82, 152-154` — 수동 배치도 요청 미지정 시 기본값 **15/20/5000**.
  - 마진·할인·최소마진 정책을 **영속 저장하는 테이블/설정이 없음** (grep: config/policy/@Value 읽기 흔적 0). 사용자의 수동 배치 파라미터는 일회성.
- 근본원인: (1) 스케줄러가 정책 파라미터를 하드코딩 → 사용자가 수동 배치로 10/15/3500을 넣어도 다음 정기/공급사 배치(하루 다회)가 15/20/5000으로 **덮어씀**. (2) 크롤 기반이라 매 실행 소싱가·환율 변동으로 가격이 요동. 동일 상품 277(210116IHB040)이 4일간 74000~79400 사이 진동(sb_process_status 이력 확인).
- 증거(계산 대조, 현재 cost_price 기준 15/20/5000 역산 = 현재 sale_price 정확히 일치):
  - 277: cost31522·bq2 → (31522×0.8×2+0)/0.665=75842→올림100→**75,900** = 현재가 75,900 ✓
  - 245: cost28383·bq1 → (28383×0.8+6000)/0.665=43168→**43,200** = 현재가 43,200 ✓
  - 3006: cost6587·bq2 → (6587×0.8×2+6000)/0.665=24870→**24,900** = 현재가 24,900 ✓
  - 사용자 의도(10/15/3500) 계산값: 277=75,000 / 245=42,200 / 3006=24,100 — 실제가와 전부 불일치.
- 진단 결론: 사용자 질문의 **가설(2) "가격 반영 안 됨"이 정답**. 단 일회성 반영 실패가 아니라 **구조적**: 정책 저장소 부재 + 스케줄러 하드코딩이 매일 사용자 값을 덮어쓰는 것. 주문 4건은 전부 이익 상태(손실 없음)이나, 사용자가 의도한 마진 10%(더 경쟁적 저가)가 아니라 15%로 상시 판매 중.
- 상태: **발견** (수정 미착수 — 올바른 목표동작이 사업 결정 사항: 스케줄러가 사용자 정책을 읽도록 정책 저장소 신설 vs 스케줄러 상수 교체 vs 정책 UI. 사용자 방향 확인 필요)
- 이력: 2026-07-21 발견·확정(리더 직접 진단, 운영 DB read-only + 코드 대조)

---

### D-094: MarginCalculator가 마켓별 수수료(sb_fee_policy)를 무시하고 채널수수료 18.5% 전 마켓 고정 (P2)

- 심각도: P2 (오동작 — 마켓별 실수수료와 괴리, 단일가 산정의 경제성 왜곡)
- 리스크 등급: 표준
- 위치: `backend/core/src/main/java/com/sbshop/agent/core/domain/product/service/MarginCalculator.java:10` — `CHANNEL_FEE_RATE = 18.5` 하드코딩, 전 마켓 동일 적용.
- 근본원인: `sb_fee_policy` 테이블에 마켓별 수수료율이 존재(COUPANG 11%·SMART_STORE 8%·ELEVEN_STREET 18%·GMARKET 18%·AUCTION 18%·CAFE24 18%)하나 `MarginCalculator`는 이를 읽지 않고 18.5% 고정. 쿠팡(11%)·스토어(8%)는 실제보다 높은 수수료를 가정해 과대 산정(보수적이나 경쟁력 저하), 정책 테이블은 사실상 사장(死藏).
- 증거: `SELECT * FROM sb_fee_policy` 6행 존재·ACTIVE. grep 결과 MarketFeeService는 별도 존재하나 가격 산정 경로(MarginCalculator)와 미연결.
- 상태: **발견** (D-093과 연계 — 가격정책 재설계 시 함께 처리 권장. 단, 다마켓 단일 sale_price 구조에서는 마켓별 수수료 반영이 설계 변경을 수반)
- 이력: 2026-07-21 발견(리더 진단 중 파생)

---

### D-093 스케줄러 비활성화 + 계산식 실측 검증 (2026-07-21, 사용자 결정)
- **BatchScheduler @Scheduled 비활성화**(`BatchScheduler.java:22`, 주석 처리 + 사유 명시). 사용자가 정기 자동재가격 존재를 몰랐고, 향후 고도화(매출 급감 대응 등) 시 정책 저장소 연결 후 복원키로 함. 가역·경량. :worker:compileJava 통과. **미배포(push 대기)**.
- **계산식 실측 대조(16개 최근 주문, sourcing_amount 보유)**: 현재 DB 판매가 = 15/20/5000 계산값과 **14/14 정확히 일치** → 계산식(MarginCalculator) 자체는 버그 없이 설계대로 정상. 문제는 순수 파라미터.
- **D-095 파생(계산모델 한계)**: 판매가는 cost_price(크롤 스냅샷)×(1-쿠폰가정)으로 산정하나, 실제 iHerb 매입가(sourcing_amount)가 상품별로 ±20% 편차. 특히 231108IHB098(실매입이 가정보다 +10~15% 초과)·220130IHB051(+7%)은 실현마진 4~12%로 붕괴(정두호 4%·장용/윤현주 8%). minMargin은 '가정매입' 기준이라 이 원가 과소평가를 보호하지 못함. 함의: 마진 10%로 낮추면(=판매가 700~1300 하락) 이런 저마진 상품은 손실 위험 증가. 조치 후보: 만성 저마진 상품 cost_price 갱신 or 마켓별/상품별 마진 차등.

### D-093 소규모 10/15/3500 반영 테스트 (2026-07-21, 배포 후 라이브)
- 스케줄러 비활성화 배포 확인(새 빌드 기동 2026-07-21T03:51:11Z). 조사 3개 상품(277·245·3006)에 crawl-and-update(10/15/3500) 트리거(batchId=dd7490d8).
- 결과 전건 SUCCESS·4마켓 반영(성공4/스킵0/실패0): 277 75900→**75000**, 245 43200→**42200**, 3006 24900→**24100**. margin_rate 15→10 저장. 크롤 원가 불변으로 예측치 정확 일치.
- 사용자에게 마켓 리스팅 실반영 확인 요청 중. 전체 IHB 적용은 D-095(저마진 상품 손실위험) 사용자 판단 후 결정.

### D-094 마켓별 가격 분리 구현·라이브 검증 (2026-07-21, 사용자 승인·TDD)
- 사용자 결정: 18.5%=순수 마켓수수료여야 함 + 마켓별 가격 분리(근본). 기준가(sb_product.sale_price)=쿠팡 기준.
- 구현(TDD, 커밋 20ebf9f): (1) MarginCalculator 채널수수료 파라미터 오버로드(하드코딩 18.5는 하위호환 기본값). (2) ProductMarketSyncService.syncPriceStockPerMarket — 마켓 순회 시 MarketFeeService.feeRate로 마켓별 실수수료를 divisor에 넣어 가격 따로 산정·전송(단일가 경로 보존). (3) BatchPriceStockService 크롤 경로가 PricingInputs로 재료 전달 + 기준가=쿠팡. (4) PricingInputs record 신설.
- 회귀: core 전체+api test BUILD SUCCESSFUL, 전 모듈 컴파일 OK. 신규 테스트: MarginCalculator 6-arg, per-market sync, 배치 경로.
- **라이브 검증(배치 95f77d61, 3상품 10/15/3500)**: 마켓별 저장 payload에서 상이 가격 확인 — 277: 스토어(8%)=65400·11번가/Cafe24(18%)=74500·쿠팡(11%,기준가)=67900. 4마켓 반영 성공. 마켓 실수수료에 맞춰 가격이 실제로 갈라짐 확정.
- 잔여: BatchController 수동배치 기본값(15/20/5000)은 유지 — 사용자 수동배치는 10/15/3500 명시 전달. 사용자 마켓 리스팅 최종 확인 대기.

### D-096: 스마트스토어 판매자 즉시할인 일괄 제거 (2026-07-21, 사용자 승인·TDD)
- 배경: D-094로 마켓별 가격이 스토어 저수수료(8%)에 맞게 낮게 산정됨. 그런데 상품마다 판매자 즉시할인이 별도로 걸려 있어 겹치면 이중할인 손해(사용자 신고). 사용자 결정: 일회성 일괄 제거, 범위=전체 스토어 상품.
- 구현(TDD, 커밋 3d0787b): MarketClient.removeSellerImmediateDiscount(default no-op)+Smartstore override(GET→customerBenefit.immediateDiscountPolicy만 제거→PUT, 적립 등 보존). SmartstoreSellerDiscountRemovalService(순회·집계, 소규모동기/전체비동기). 내부 엔드포인트 POST /internal/smartstore/remove-seller-discount(productIds, dryRun). core+infra+api 회귀 통과.
- 라이브 검증(3상품 277·245·3006): dryRun→즉시할인 확인(277=9%·245=12%·3006=12%, 구조 immediateDiscountPolicy.discountMethod.value/unitType). 실제 제거(removed=3)→재조회 dryRun(skipped=3=할인없음)로 제거 확정. "키 제거+PUT"이 네이버에서 실제 할인 제거함을 확인.
- 잔여: 전체 스토어 3183건 비동기 실행(productIds 미지정, dryRun=false) — 사용자 최종 승인 후. 스토어 상품 수=3183(ACTIVE).

### D-096 전체 실행 결과 + rate limit 복원력 개선 (2026-07-21)
- 전체 3183건 비동기 실행 완료: removed=2160, skipped=4(할인없음), **failed=1019(429 TOO_MANY_REQUESTS)**. 실패는 네이버 API 분당 한도 초과 — throttle 200ms에 GET+PUT 2콜 + 동시 주문동기화(SyncWorker)까지 경합.
- **개선(TDD)**: SmartstoreSellerDiscountRemovalService에 항목별 재시도(최대3회, 백오프 2s·4s) + throttle 200→500ms. throttle/backoff는 세터로 튜닝·테스트 가능. 멱등이라 재실행 시 이미 제거된 건은 스킵(GET만).
- 잔여: 실패 1019건 mop-up — 개선 배포 후 전체 재실행(자가치유). 스킵 GET 비용은 있으나 재시도로 429 대부분 흡수.

### D-097: 쿠팡 반품완료 전방 감지 부재 — 배송완료 주문의 반품이 RETURNED+정산0으로 전환되지 않음 (2026-07-21, 사용자 신고 "김대섭 반품건이 배송완료로 표시")
- 심각도: P1 (오동작 — 반품완료 주문이 배송완료로 표시·정산액 유지)
- 리스크 등급: 중대 (마켓 API 계약 신설 — 쿠팡 returnRequests)
- 위치: `CoupangOrderSyncService.postSyncProcess` / `CoupangOrderAdapter`(반품 조회 경로 자체 부재)
- 증상: 배송완료(DELIVERED) 후 고객이 반품하면 쿠팡이 그 주문을 ordersheet API에서 제거(단건조회 400 "취소 또는 반품"). 그러나 앱에는 쿠팡 returnRequests(반품) API 호출 경로가 없어 반품완료를 학습 못 함. detectCancellations는 DELIVERED를 terminal로 보호(D-027)하므로 absence로도 안 잡힘 → 영구 DELIVERED 고착, 정산액도 유지.
- 라이브 근거: 주문 2101402034506(김대섭, order_id 33/li 264) — DB=DELIVERED·정산 63,724. 쿠팡 returnRequests=receiptId 1799887551 RETURN/RETURNS_COMPLETED(고객변심, 완료확정 2026-07-13). 쿠팡 단건 ordersheet=400 "취소 또는 반품".
- D-027과의 구분: D-027은 "이미 RETURNED인 주문의 오취소 방지"(역방향 보호, 수정완료). D-097은 "DELIVERED→RETURNED 전방 전환 경로 신설"(별개).
- 수정 설계(사용자 승인): 쿠팡 returnRequests API(searchType=timeFrame, ≤7일 창 분할)로 receiptStatus=RETURNS_COMPLETED 확증 → 해당 orderId의 lineItem을 RETURNED+settlement 0+verified 전환. absence 추론 아님(오취소 없음), 멱등, 정산동기화(DELIVERED만 처리)가 RETURNED 스킵해 재부풀지 않음.
- 수정(2026-07-21, TDD, 커밋 bfe9d2c): queryReturns(port/client, 7일 창 분할·nextToken·URI.create 콜론 원본전송) + detectReturns(adapter, RETURN·RETURNS_COMPLETED만 → RETURNED+정산0+verified, 멱등) + postSyncProcess 배선. Red: `CoupangDetectReturnsTest` 4건(완료반품 전환 / 미완료 미전환 / DB무 no-op / 멱등). core+infra+api 회귀 전체 통과.
- 검증(2026-07-21, 라이브): 배포(Started 08:39:53Z) 후 쿠팡 동기화 수동 트리거 → 로그 "쿠팡 반품완료 반영: 6건 RETURNED+정산0 전환", COUPANG_SYNC SUCCESS. DB 확인: 쿠팡 RETURNED 6건(김대섭 2101402034506 포함) 전부 settlement 0.00·verified=t. 김대섭 li 264: DELIVERED/63724 → RETURNED/0.00/t. absence 아닌 원본 확증이라 오취소 0.
- 상태: 검증통과 (2026-07-21 라이브 — 배송완료 주문 반품 전방 감지·정산0 자동교정, 자가치유·멱등)

### D-098: 취소·반품 종결 lineItem 정산0 미처리 (전 마켓, 쿠팡 제외) — 정산액 부풀림 (2026-07-22)
- 심각도: P1 (오동작 — 취소/반품 주문의 정산액이 부풀린 채 유지, 손익 왜곡)
- 리스크 등급: 표준 (다마켓·정산 데이터, 회귀 게이트 필수)
- 위치: 각 마켓 postSyncProcess(SmartStore line 204 빈 껍데기·Elevenst·Cafe24), 쿠팡만 detectReturns가 RETURNED 0 처리(D-097).
- 라이브 근거(DB 실측 2026-07-22): CANCELED/RETURNED인데 정산액 유지 3건 — GMARKET 곽금희(4460696482) RETURNED 26,611 / SMART_STORE 정가영(2026061471696071) CANCELED 78,140 / 이명동(2026061486764551) CANCELED 47,590. 전부 settlement_verified=f.
- 상태 감지 자체는 Cafe24(R*→RETURNED 라이브작동)·스토어(취소 매핑작동)에서 이미 됨 → 공백은 "종결됐는데 정산0으로 안 내림"뿐. 마켓 API 무관, DB 파생 가능.
- 수정 설계: ShippingStatus.isRefundTerminal()(취소·반품=true, 교환은 결제유지라 제외) + 마켓무관 TerminalSettlementService.zeroSettlementForRefunded(marketType) — 환불성 종결 lineItem 정산0+verified(멱등). 각 마켓 postSyncProcess에 배선. 쿠팡도 CANCELED 커버 위해 추가.
- 수정(2026-07-22, TDD, 커밋 7cf213e): 위 설계대로 구현. `TerminalSettlementServiceTest` 5건(RETURNED/CANCELED 0처리, EXCHANGED·DELIVERED 불변, 이미0 멱등). 생성자 파라미터 추가로 깨진 기존 테스트 7파일 목 주입 보정. core+infra+api 회귀 전체 통과.
- 검증(2026-07-22, 라이브): 배포(재시작 23:55:02Z) 후 스토어·Cafe24 동기화 트리거 → 로그 "[GMARKET] 1건·[SMART_STORE] 2건 정산0 정규화". DB 확인: 곽금희 RETURNED 26611→0.00/verified, 정가영 CANCELED 78140→0.00, 이명동 CANCELED 47590→0.00. DB 전체 스캔이라 30일 창 밖(곽금희 06-23)도 교정됨.
- 상태: 검증통과 (2026-07-22 라이브 — 전 마켓 취소·반품 정산0 정규화, 멱등·자가치유)

### D-099: 11번가 클레임(취소/반품/교환) 감지 정밀화 — 상세조회 ordPrdStatNm 활용 (2026-07-22)
- 심각도: P2 (오동작 — 반품/교환을 CANCELED로 뭉뚱그림·오취소 가능)
- 리스크 등급: 표준 (라이브 주문 상태, 회귀 게이트 필수)
- 배경: 11번가는 클레임 목록 조회 REST가 없음(라이브 확정 2026-07-22: claimservice/ordservices 7개 후보 전부 -997, 정상 엔드포인트 complete·orderlistalladdr는 200으로 대조검증). 기존 detectCancellations(D-028)는 4개 진행상태 목록에서 사라진 주문을 무조건 CANCELED 처리 → 반품/교환 미구분, 정상 aged-out 주문 오취소 위험.
- 발견: `claimservice/orderlistalladdr` 단건 상세조회 응답에 ordPrdStat(901)·ordPrdStatNm(구매확정 등) 실재. D-031의 "상태 필드 없음" 결론이 틀림 — 파서가 안 읽었을 뿐.
- 수정 설계: ElevenstStatusMapper.mapClaimStatus(ordPrdStatNm 부분일치: 취소→CANCELED·반품→RETURNED·교환→EXCHANGED, 그외 null) + ElevenstOrderAdapter.resolveClaimStatus(단건조회·매핑) + detectClaims(사라진 non-terminal 주문을 상세조회로 실상태 판정, 클레임 아니면 상태 불변=오취소 방지). 반품·취소는 D-098이 정산0 처리.
- 검증 한계: 현 DB에 11번가 클레임 주문 0건 → 라이브 E2E 불가, 단위테스트로 검증. 실 클레임 발생 시 라이브 확인.
- 수정(2026-07-22, TDD, 커밋 5658431): 위 설계대로 구현. `ElevenstClaimStatusMapperTest` 5건 + `ElevenstDetectCancellationsTest`에 D-099 4건(취소/반품/교환 판정·클레임아님 오취소방지) 추가. core+infra+api 회귀 전체 통과.
- 검증(2026-07-22, 스모크): 배포(재시작 00:44:34Z) 후 11번가 동기화 트리거 → ELEVEN_STREET_SYNC SUCCESS. 새 상세조회 경로가 sync 무결. 현 DB 클레임 0건이라 상태변경 로그 없음(정상). 라이브 E2E(실 클레임 RETURNED 전환)는 실 클레임 발생 시.
- 상태: 검증통과(단위+스모크) — 라이브 E2E는 실 11번가 클레임 발생 대기

### D-103: Cafe24 리프레시 토큰 자동 갱신 구조 결함 — 선제 갱신 스케줄러 부재 (2026-07-23)
- 심각도: P1 (오동작 — 오랜 미사용/트래픽 공백 시 리프레시 토큰 만료→재인증 외 복구 불가, Cafe24 연동 전면 중단)
- 리스크 등급: 중대 (스케줄러 활성화 — 사용자 승인 필수, 획득)
- 근본 원인: 토큰 갱신이 주문동기화 API 트래픽의 부산물로만 발생. `Cafe24TokenManager.getValidAccessToken()`(:49-78)은 액세스 토큰(2h) 만료 임박 때만 doRefresh→리프레시 토큰 회전. Cafe24 전용 토큰 스케줄러 부재. Cafe24 리프레시 토큰은 유효 2주·refresh 때마다 회전/연장 → API 호출 공백 ≥2주면 만료. refresh_token_expires_at 추적도 전무.
- 수정 설계(사용자 승인 "선제 스케줄러만", 스키마 무변경): core에 `Cafe24TokenRefreshPort.refreshProactively()` 포트 신설 → `Cafe24TokenManager`가 구현(리프레시 토큰 보유 시 access 유효 여부 무관 강제 refresh·회전·시한연장, advisory lock 하, 실패 삼킴) → worker `MarketTokenScheduler` 매일 03:00(KST) 호출. 과거 startup 강제 refresh는 2 JVM 경쟁으로 폐지됐으나 현 단일 JVM+lock 하 안전.
- 수정(2026-07-23, TDD): `Cafe24TokenManagerTest`에 선제갱신 3종(강제회전·토큰없으면건너뜀·실패삼킴). :infrastructure:test PASS, 전 모듈 compile PASS, `./gradlew test` 전체 PASS.
- 미해결: 이미 만료된 토큰은 코드 복구 불가 → UI 재인증 필요(즉시 조치). refresh_token_expires_at 추적/만료임박경고는 범위 제외(선택 후속).
- 상태: 수정완료(라이브 검증대기) — 재인증+배포 후 익일 03:00 로그 "Cafe24 선제 토큰 갱신 완료" 확인 대기

> 참고(번호): 같은 세션 2026-07-23 프론트 UI 배치(커밋 f53563a)가 커밋 메시지에서 D-099~D-102 라벨을 느슨히 사용했으나 원장 미등재. 원장 정본 기준 D-099는 11번가 클레임(상단)이며, 본 D-103이 원장상 다음 번호다. 프론트 배치 상세는 working_history/20260723_1001_결과서.md 참조.

### D-104: 통합 주문 관리 인라인 편집 성능 — FE 전체 그리드 재렌더 병목 (2026-07-24)
- 심각도: P2 (사용성 — 구매상태 셀렉트/인라인 편집 시 화면 굼뜸)
- 리스크 등급: 표준 (다중 FE 리팩토링·행위 보존, 회귀 게이트 필수. BE 무변경)
- 근본원인: BE 단건 엔드포인트는 PK 단건 조회+저장으로 빠름(Explore 조사). 굼뜸의 주범은 FE — ①`columns` useMemo가 매 렌더 재생성(`handleUpdate` deps가 매 렌더 새 mutation 객체 참조) ②`rowData` state+`useEffect([data])`로 편집마다 500건 flatMap+setState 이중 렌더 ③`patchLineItemInCache`가 전 주문 객체 재생성 ④행 메모이제이션 부재로 셀 1개 편집에 수천 셀 재렌더.
- 수정 설계(frontend/src/pages/OrderGrid.tsx, 행위 보존): (1)`handleUpdate` deps→`*.mutateAsync`(RQ v5 안정참조)로 `columns` 안정화 (2)`rowData` 제거·`processedData` 단일 useMemo로 `data`에서 직접 평탄화 (3)`rowCacheRef`로 변경 안 된 주문의 행 객체 참조 재사용 (4)`patchLineItemInCache` 참조 보존(해당 주문만 새 객체) (5)`OrderTableRow=React.memo`(비교자: row.original·isSelected·isOrderBoundary·colCount) → 편집 시 변경된 주문의 3행만 재렌더.
- 수정(2026-07-24): 위 5개. `tsc -p tsconfig.app.json --noEmit` PASS, `npm run build` PASS. FE 테스트 러너 부재로 자동 단위테스트 대신 빌드+독립 QA 리뷰 게이트. 수정요지 `_workspace/fixes/D-104.md`.
- BE 후속(미적용·P3): shippingMutation 성공 시 500건 무효화 유지(상태전이 정확성), OrderLineItem.order_id 인덱스, purchaseStatus EXISTS 서브쿼리, 구매상태 액션로그 부가 2쿼리 — 측정 후 개선 여지.
- 검증(2026-07-24, qa-verifier): **PASS**. 회귀 게이트 2종 독립 재실행 — `tsc -p tsconfig.app.json --noEmit` EXIT 0(에러 0), `npm run build` EXIT 0(에러 0, 청크경고는 정보성·기존). 행위 동등성 A~H 전항 보존 확인: 행 순서·개수(flatMap↔forEach 동일), isFirstLineItem(`id!==currentOrderId&&order` ↔ `liIndex===0` 다중 lineItem 케이스까지 동등), totalRowCount/lineItemCount/rowSpan 동일, 메모스킵 안전(getRowId 미설정=인덱스 rowId, `key=row.id`로 위치기준 비교→데이터불변 시 stale핸들러 정확·변동 시 original바뀌어 재렌더), 선택토글/전체선택(isSelected 프롭이 memo 무효화) 정상, 셀가시성/frozen/경계선(colCount 프롭化만 차이·값 동일) 보존, 호버(data-order-id DOM위임 불변) 보존, 낙관패치/롤백(patchLineItemInCache 비매칭 조기반환=참조보존·매칭 출력 동일) 정확. 미검증: FE 러너 부재로 런타임 실동작(재렌더 카운트·클릭·refetch후 선택정합)은 코드논증 대체 → 브라우저 수동확인 권함. 판정서: `_workspace/verify/D-104_perf_verdict.md`.
- 상태: 검증통과 — 커밋 게이트 통과 가능. 라이브 성능 체감은 배포 후 사용자 확인 권장.

### D-105: 이메일 수집 실패 2종 — IMAP 비ASCII 검색어 크래시 + 긴 트랜잭션 커넥션 실패 (2026-07-24)
- 심각도: P1 (오동작 — 이메일 동기화 빨간불, iHerb 송장 자동반영 파이프라인 부분 중단)
- 리스크 등급: 표준 (worker 서비스 행위 변경, 회귀 게이트 필수)
- 근본원인(라이브 로그 진단): ①`EmailFetcherService.searchAccountForOrderNos`가 사용자 편집 필드 `sourcing_order_no`를 그대로 `SubjectTerm` IMAP SEARCH에 사용 — 한글("재고") 등 비ASCII 값이 charset 미지정으로 서버 `BAD Could not parse command`(`BadCommandException`) 유발, 계정 검색 루프 중단. ②`fetchAndProcessEmails`가 `@Transactional`로 계정별 IMAP 접속(각 30s)·마켓 API 호출 등 느린 I/O 전체를 감싸 DB 커넥션 장기 점유 → 풀이 커넥션 닫아 `Unable to rollback against JDBC Connection`.
- 수정(2026-07-24, TDD, backend/worker/.../EmailFetcherService.java): (1)`isImapSearchable(orderNo)` 정적 가드(비ASCII·blank 제외) 신설 → `buildSearchPlan`·`findIherbConfirmationAmount`에서 비ASCII 주문번호를 검색에서 제외(iHerb 숫자 주문번호는 통과, warn 로그). (2)`fetchAndProcessEmails`의 `@Transactional` 제거 — 읽기는 리포지토리 호출별, 쓰기는 각 `save()`별 자체 트랜잭션(모든 쓰기 경로가 명시적 save 확인).
- 테스트: `EmailFetcherOrderNoGuardTest` 4건(숫자/ASCII 통과·한글 제외·blank 제외). `./gradlew test` 전체 PASS.
- 파생 수습: `MarketType.SMART_STORE` 라벨 스마트스토어→N스토어(앞선 UI 배치) 이후 `ProductControllerActionLogDetailTest` "스마트스토어" 단정 2건이 실패하던 회귀를 "N스토어"로 갱신(라벨 변경 시 전체 test 미실행으로 누락됐던 것). 동일 커밋 d5afd43에 포함.
- 미해결(별건): iHerb 개별 송장 전송 경고(11번가 "존재하지 않는 배송번호", 쿠팡 "이미 송장 있음")는 운영성·다음 사이클 재시도라 본 결함과 무관. Cafe24 송장 실패는 D-103/토큰 무효(재인증 필요) 소관.
- 상태: 수정완료(검증통과) — 회귀 전체 PASS. 배포 후 이메일 동기화 빨간불 해소·`이메일 검색 실패`/`Unable to rollback` 로그 소멸 라이브 확인 권장.
- 검증(2026-07-24, 라이브): 배포 후 `/internal/email/fetch` 수동 트리거 → 로그 `이메일 검색 제외 — 비ASCII 구매주문번호(itemId=428, orderNo='재고')` 정상 스킵 + iHerb 발송/확인 메일 다수 발견, `BadCommand`/`Unable to rollback` 소멸 확인.

### F-CRED-9: 마켓 크레덴셜 시크릿 관리자 인증 게이트 + 평문 표시 (2026-07-24)
- 배경: 사용자 요구 — 설정 및 연동 화면에서 저장된 마켓 API 키(시크릿)를 직접 확인·수정. 기존 F-CRED-1·7은 시크릿을 마스킹했는데, 그 근거는 `/api/v1/**`가 `SecurityConfig`에서 `permitAll`(무인증 공개)이었기 때문. 무인증 상태로 시크릿 평문 노출 시 안전 가드레일이 하드블록(credential exfiltration) → 인증 선행이 필수.
- 심각도: 기능 요청(P2) / 리스크 등급: 표준(보안 경계 변경, 회귀 게이트 필수)
- 수정(2026-07-24, TDD): (BE) `SecurityConfig`에 HTTP Basic + in-memory 관리자 계정(기본 admin/admin, `admin.username`/`admin.password` = env `ADMIN_USERNAME`/`ADMIN_PASSWORD` 재정의 가능), `/api/v1/market-credentials/**`만 `authenticated()`(permitAll 매처보다 앞)·나머지 permitAll 유지(SSE·주문·상품 무영향). `MarketCredentialDto`가 시크릿 평문(access/secret/refresh) 재노출(인증 게이트가 안전성 보장). `MarketCredentialDtoMaskingTest`를 새 정책으로 재작성(시크릿 포함 검증). (FE) axios 요청 인터셉터가 sessionStorage Basic 토큰 첨부, Settings에 관리자 로그인 게이트(로그인/로그아웃)·시크릿 평문 입력칸.
- 검증(2026-07-24): `./gradlew test` 전체 PASS, FE 빌드 PASS. 라이브(배포 후): 무인증 `GET /market-credentials` → **401**, `admin:admin` → **200**, 주문 API 무인증 → **200**(기존 기능 유지) 확인.
- 보안 주의: admin/admin은 약함 + FE가 base64로 전송(브라우저서 열람 가능) — "무인증 접근"은 차단하나 강보안 아님(사용자 선택). `.env ADMIN_PASSWORD`로 코드 변경 없이 강화 가능. 인증 매처 제거 시 F-CRED-1·7 노출 결함 재발하므로 유지 필수.
- 상태: 수정완료(검증통과·라이브 확인) — 커밋 2ca7efa 배포 반영.

### D-106: 상품 상세 모달 필드 편집이 서버에 저장 안 됨 — 실제 PUT 대신 모킹 호출 (2026-07-25)
- 신고: 사용자 — "상품 관리 상세 모달에서 소스 URL을 바꿔도 안 바뀐다."
- 심각도: P1 (오동작 — 소스URL·브랜드·카테고리·소싱·메모 등 판매가 외 모든 편집 필드가 저장 안 됨. 저장 성공 토스트만 뜨고 실제 반영 없음)
- 리스크 등급: 표준 (프론트 배선 변경, FE 게이트 필수)
- 근본원인: `ProductDetailModal.handleSave`가 판매가는 실제 `updatePriceStock`로 저장하지만, **그 외 필드(`sourceUrl` 포함)는 `productMockApi.updateProductFields`(모킹 — 300ms 지연 후 성공만 반환, 서버 미도달)로 처리**. 모킹은 `console.warn`만 남기고 로컬 반영도 안 해 저장 후 모달을 닫으면 값이 사라진다. 백엔드는 정상 — `PUT /api/v1/products/{id}`(`ProductController.updateProduct` → `ProductUpdateCommand` → `Product.updateSourcingInfo`가 `sourceUrl` 반영)가 존재하고 `productApi.updateProduct`(FE 클라이언트)도 이미 있는데 모달만 연결 안 됨(product-grid 리팩토링 때 남긴 "다음 세션 백엔드 TODO" 모킹).
- 수정(2026-07-25): `handleSave`가 판매가 변경 시 `updatePriceStock`(마켓 동기화)를 유지하고, 판매가 포함 전체 편집 필드를 실제 `productApi.updateProduct(id, body)`로 저장하도록 배선. 평탄 폼 `Fields` → `ProductUpdateRequest` 매핑(유일 차이 `productName`→`name`, 나머지 필드명 동일; enum 필드 category/vendor/measureUnit은 상세 응답이 enum명 문자열로 내려주므로 라운드트립). `productMockApi.updateProductFields` 사용 제거(파일은 bulkDelete 때문에 유지).
- 후속 UI 개선(2026-07-25, 사용자 요청 "UI 개선"): ①카테고리·소싱처·단위를 자유텍스트→**드롭다운化**(enum명 value·한글 라벨, 옵션 밖 레거시 값은 보존 표시) — 잘못된 enum 저장 400 리스크 원천 차단. ②저장 UX — baseline 대비 **dirty 추적**(변경 없으면 저장 버튼 비활성 + "변경됨" 배지), 저장 실패 시 `GlobalExceptionHandler`의 `{message}`를 토스트에 표면화. ③시각 — 셀렉트 행을 고스트 인풋 톤에 정렬, 헤더 배지 카테고리 한글 라벨화. `tsc`/`build` PASS.
- 검증(2026-07-25): FE 게이트 `tsc -p tsconfig.app.json --noEmit` EXIT 0, `npm run build` PASS. enum 라운드트립 안전 확인 — ProductCategory/VendorType/MeasureUnit 모두 `@JsonValue`/`@JsonCreator` 부재로 `name()` 대칭 직렬화(상세 응답이 내려준 enum명을 그대로 되돌려 역직렬화). 백엔드 PUT 경로는 기존 테스트로 커버됨. FE 러너 부재로 런타임 실동작은 배포 후 브라우저 수동확인 권장(소스URL 편집→저장→재조회 시 반영).
- 상태: 수정완료(검증통과) — 커밋 대기. push는 사용자 승인 후(자동배포=api 재시작).

### D-107: 11번가 배송중 주문 동기화가 수취인 이름·주소를 지워 "-"로 표시 (2026-07-25)
- 신고: 사용자 — "11번가 주문 동기화 긴급. 이름과 주소가 사라져서 -로 보인다." (스크린샷: 배송중 11번가 주문 20260722086940464·20260721086527055가 이름 "-", 쿠팡·N스토어는 정상)
- 심각도: P1 (오동작 — PII 유실. 배송중 상태의 11번가 주문에서 수취인 이름이 그리드에 "-"로 표시, 주소도 소실 위험)
- 리스크 등급: 표준 (core 1파일 파서 수정, 마켓 API 매핑 계약)
- 근본원인: `ElevenstOrderAdapter.parseShippingElement`(배송중 목록 파서)가 `recipientName`·`address`·`message`·`ordererName` 등을 **빈 문자열("")로 하드코딩**해 DTO를 만들었다. `ElevenstOrderSyncService.updateExistingOrder`→`Order.update`는 `recipientName`을 `!= null`로만 보호하므로 ""가 기존 실이름을 **덮어썼다**. `recipientPhone`은 `isUsablePhone`(blank 거부)로, `address`는 `protectAddress`(D-074, progressed 시 null)로 각각 보호돼 살아남아 — 스크린샷의 "전화·주소는 있고 이름만 '-'" 패턴과 정확히 일치. 주문이 결제완료→배송중으로 넘어가 배송중 목록에만 잡히는 순간 이름이 소실됐다. (D-066에서 통관번호는 이미 `emptyToNull`로 이 함정을 피했으나 이름/주소는 방치됨.)
- 수정(2026-07-25): `parseShippingElement`가 배송중 목록의 `rcvrNm`/`rcvrPrtblNo`/`rcvrMailNo`/`rcvrBaseAddr`/`rcvrDtlsAddr`/`ordDlvReqCont`/`ordNm`/`ordPrtblTel`을 파싱하고, 태그 부재 시 `emptyToNull`로 null 정규화 → `Order.update` null-guard가 기존 값을 보존. 태그가 있으면 파싱해 **복원**(11번가 배송중 목록도 수취인 정보를 담고 있음). `marketProductCode`/`productName`도 ""→null.
- 검증(2026-07-25): Red→Green. 신규 `ElevenstShippingRecipientPreservationTest` 2건(rcvrNm 부재→null 보존 / rcvrNm·주소 존재→복원) PASS. D-066 배송중 통관번호 테스트 회귀 PASS. `:core:test` 전체 PASS. FE·타 모듈 무변경(private 파서 내부 수정, 시그니처 불변).
- 상태: 수정완료(검증통과) — 커밋 대기. push는 사용자 승인 후(자동배포=api 재시작). 이미 ""로 오염된 기존 배송중 주문은 다음 동기화 때 배송중 목록의 rcvrNm으로 자동 복원(목록이 이름을 담고 있을 경우), 아니면 배송완료 전환 시 dlvcompleted 경로가 복원.
- 후속(2026-07-25, 라이브 진단): 배포 후 재동기화에도 이름이 복원 안 돼 서버 로그·DB 확인 → **배송중 목록 API(`/rest/ordservices/shipping/`)가 `rcvrNm`을 아예 주지 않음**(주소는 protectAddress로 이미 DB에 보존, 이름만 `""`)이 확정. 1차 수정은 "추가 손실 방지"만 했고 이미 오염된 이름은 복원 못함. **2차 수정**: `fetchOrders` 배송중 루프에서 이름이 null이면 단건 상세조회(`claimservice/orderlistalladdr`, `rcvrNm` 포함)로 수취인/구매자 정보를 복원(`enrichRecipientFromDetail`, 배송상태·송장은 배송중 값 유지, 상세도 비면 null 유지). 원 주석 "개별 조회로 폴백" 실구현. 신규 테스트 2건(상세조회 복원/상세도 공백 시 null) 추가, `:core:test` 전체 PASS.

### D-108: 전 마켓 동기화 병합이 마스킹/빈 개인정보로 실값을 덮어씀 — 중앙 방어 (2026-07-25)
- 신고: 사용자 — "11번가 외 다른 마켓도 배송중·배송완료 시 개인정보 보호차원에서 기존 필드를 반환하지 않는 경우 유실되지 않도록 전수로 검토·방어해줘."
- 심각도: P1 (PII 유실 — 마켓이 배송중/배송완료/오래된 주문에서 이름·주소를 "" 또는 마스킹("정*영")으로 내려줄 때 기존 실값이 덮여 사라짐)
- 리스크 등급: 표준~중대 (전 마켓 공통 도메인 병합 경로. 단 사용자 명시 요청 + 시그니처 불변 + Red→Green·전 모듈 컴파일·전 마켓 DB 감사로 커버)
- 근본원인: 4개 마켓 sync 서비스(쿠팡·스마트스토어·11번가·Cafe24)가 모두 단일 `Order.update`(9-arg)를 거치는데, 전화번호만 `isUsablePhone`(blank+마스킹 거부)로 보호되고 **이름·주소·우편번호·구매자명은 `!= null`로만 가드** → ""·마스킹값이 실값을 덮었다. (D-107 11번가 배송중 이름 소실의 일반화된 뿌리.)
- 수정(2026-07-25): `Order.update`에 `isMeaningfulPii`(null·blank·'*' 마스킹 거부) 신설, recipientName/zipcode/address/ordererName에 적용. 메시지는 자유텍스트라 blank만 거부. 전화는 기존 `isUsablePhone` 유지. **전 마켓 한 지점 방어.** 수동 편집 클리어("")는 `updateAddress`/`updateCustomsClearanceNo` 별도 경로라 무영향(F-ORD-23 유지).
- 검증(2026-07-25): 신규 `OrderSyncMergeGuardTest` 4건(blank 미덮음/마스킹 미덮음/실값 갱신/null 유지) Red→Green. `:core:test` 전체 PASS, api·worker 컴파일 PASS. **전 마켓 DB 감사**: empty_name·masked_name·empty_addr 전 마켓 0건 → 오염 데이터 없음(순수 예방). 라이브 확정: 11번가 재동기화로 정채영·이선 복원 확인.
- 상태: 수정완료(검증통과) — 커밋·push.

### D-109: 확인메일 실구매가 파서가 첫 매칭을 버리고 두 번째 매칭을 주입 (2026-07-28)
- 신고: 사용자 — "주문 확인 메일에서 실구매비용을 자동 주입하는 기능이 잘 작동하는지 검증해줘"
- 심각도: P1 (데이터 오염 — 실구매가에 무의미 값이 들어가고, 멱등 가드 때문에 영구 고착. 대시보드 순수익 KPI가 과대계상)
- 리스크 등급: 표준 (정규식 추출 로직 2곳, 시그니처 불변)
- 근본원인: `OrderEmailParser.parseIherbConfirmation`이 판정용 `find()`로 매칭 위치를 소비한 뒤 **같은 Matcher로 다시 `find()`를 호출** → 1·2차 패턴의 첫 매칭이 버려지고 본문의 "두 번째" 매칭이 금액으로 채택됐다. 3차 패턴(`합계`)만 우연히 정상. `IherbEmailSearchService.parseAmount`(api)에 동일 결함 복제.
- 실증(운영 로그 vs DB): 343934680 파서값 **48** / 실값 70,743 · 344006073 **32** / 47,609 · 344005516 **31** / 46,923 · 343914885 **30** / 45,254. 7건 중 4건 오파싱.
- 수정(2026-07-28): 패턴을 우선순위 리스트(`List<Pattern>`)로 두고 루프에서 **첫 매칭을 즉시 채택**. 숫자 변환 실패 시 다음 패턴으로 진행. 양쪽 클래스 동일 규약.
- 검증: 신규 `OrderEmailParserConfirmationTest` 3건 Red(2 FAIL: totalAmount=null)→Green, `IherbEmailSearchServiceParseTest` 4건 Green. `:core :infrastructure :worker :api` 전체 test PASS.
- 상태: 수정완료(검증통과).

### D-110: 실구매가 자동 주입이 배송 큐에 종속 — 배송완료 주문 영구 누락 (2026-07-28)
- 신고: D-109 검증 중 발견 (같은 기능의 별개 원인)
- 심각도: P1 (기능 미작동 — iHerb 194건 중 **164건(84.5%)** 실구매가 미주입, 그중 157건은 검색 시도조차 안 됨)
- 리스크 등급: 표준 (조회 조건 신설 + 검색 계획 합집합. 기존 배송 큐 조건 불변)
- 근본원인: `findIherbItemsNeedingEmailProcessing()`의 `shipmentEmailNeeded()`는 **송장 처리가 필요한 상태만** 반환(PREPARING, 또는 `trackingSentToMarket≠true`인 DISPATCHED/SHIPPED). 확인메일은 이 큐에 든 주문번호에 대해서만 곁다리로 처리돼, 배송이 끝나 큐를 벗어난 주문은 확인메일을 영영 검색하지 않았다. 실구매가 수집과 송장 수집은 생애주기가 다른데 한 큐를 공유한 것이 원인. 운영 로그 실측(16h): `주문 확인 파싱` 179회 / **실제 DB 쓰기 0회** — 전부 멱등 스킵(같은 7건만 반복 스캔).
- 수정(2026-07-28): `findIherbItemsNeedingPurchaseAmount()` 신설 — iHerb + 구매주문번호 보유 + `sourcing_amount IS NULL OR <= 0`, **배송상태 미참조**. `EmailFetcherService.fetchAndProcessEmails`가 배송 큐와 실구매가 큐를 조회해 `buildSearchPlan`에서 주문번호 합집합(Set 중복제거)으로 1회 IMAP SEARCH. 매칭 메일의 제목으로 발송/확인을 분기하므로 어느 큐에서 왔든 한 검색으로 양쪽 처리.
- 부가 가드: 한 iHerb 주문번호가 **여러 라인아이템**에 걸리면(운영 2건) 확인메일 총액을 라인별로 배분할 근거가 없고, 총액을 양쪽에 넣으면 `sourcing_amount`가 라인아이템별 합산되는 순수익 계산(`DashboardRepositoryImpl:45`, `DashboardDtos:26`)에서 원가가 중복 계상된다 → 자동 주입 스킵 + 경고 로그(수동 입력).
- 검증: 신규 `EmailFetcherConfirmationQueueTest` 6건 + `OrderLineItemRepositoryImplPurchaseAmountTest` 2건 Red→Green. 4개 모듈 전체 test PASS. `spotlessCheck`는 api 모듈 28개 파일에서 **기존 위반**으로 실패(clean HEAD 동일, 본 변경 무관 — spotlessApply 전면 실행 금지).
- 백필: 별도 마이그레이션 불필요 — 배포 후 새 큐가 미주입 164건을 자동 편입한다. `docker exec projects-sbshop-api-1 curl -s -X POST localhost:8080/internal/email/fetch`로 즉시 1회 실행 가능(스케줄러는 30분 주기).
- 상태: 수정완료(검증통과) — 배포 후 라이브 확인 필요.

### D-111: iHerb 확인메일 달러 표기를 원화로 오인 주입 — D-109 오진의 실제 원인 (2026-07-28)
- 발견 경위: D-109/D-110 배포 후 백필에서 9건 중 2건이 31·35로 주입됨. **D-109의 이중 `find()`는 실재하는 별개 버그였으나(테스트로 입증), 48/32/31/30의 원인은 아니었다** — 진단 로그로 실제 본문을 확보해 원인을 교정 확정.
- 심각도: P1 (원가가 사실상 0이 되어 순수익 폭증. 멱등 가드로 영구 고착)
- 실제 본문(운영 로그, 매칭 주변 60자): `결제 수단: Master Card x2218 총 결제 금액: $48.00 주문 확인 / 관리 주문 상세 정보 Thorne, 커큐민 파이토솜…`
- 근본원인: iHerb는 **계정 설정에 따라 원화(₩45,254)로도 달러($48.00)로도** 표기하는데, 패턴 `[^\d₩]*₩?\s*([\d,]+)`의 `[^\d₩]*`가 `$`를 그대로 삼키고 정수부 48만 캡처했다. 해당 주문의 실 원화 원가는 70,743원.
- 수정(2026-07-28, 2단계):
  1. `c6b5e16` — 1,000원 미만 매칭 거부 + 매칭 주변 60자 WARN(본문 전체는 PII라 미출력). 00:30 스케줄러가 잔여 155건을 오염시키기 전 투입한 **응급 차단**.
  2. `64c6f44` — 통화기호를 group(1)로 캡처(`([₩$]?)`)하고 `$`면 미주입. 소수부(`$1,480.00`)도 파싱해 하한 가드 우회 차단. 원화 청구액은 카드사 환율로 정해져 확인메일에 존재하지 않으므로 수동 입력에 맡긴다.
- 검증: `OrderEmailParserConfirmationTest` 8건 PASS(USD 거부·1000달러 초과 USD 거부·기호 없는 원화 인정 신규 3건). 4개 모듈 전체 PASS. 라이브(00:42 배포 후 2사이클): 확인메일 22건 중 **달러 8건 정상 스킵, 오파싱 주입 0건**.
- 잔존: 오염된 2건(`343280106`=31, `343628939`=35)은 멱등 가드로 자가 교정 불가 → `UPDATE sb_order_line_item SET sourcing_amount=NULL WHERE sourcing_order_no IN ('343280106','343628939') AND sourcing_amount < 1000` 수동 실행 필요(DB 쓰기 권한 차단됨).
- 상태: 수정완료(라이브 검증) — 오염 2건 정리 대기.

### D-112(후보): 확인메일 실구매가 미주입 잔여 155건의 3대 원인 (2026-07-28)
D-110 백필 실행 결과, 자동 주입의 실제 상한이 드러났다. 194건 중 155건이 여전히 미주입이며 원인은 셋으로 갈린다.
1. **달러 표기 8건** — 원화 청구액이 메일에 없어 산출 불가. 정책 결정 필요(수동 입력 유지 vs 환율 근사 주입). `sourcing_amount`가 순수익에 직접 들어가므로 근사값은 이익을 왜곡한다.
2. **패턴 미매칭 8건(전부 `younzara@nate.com`)** — 확인메일 제목은 매칭되나 `총 결제 금액`/`총 금액`/`합계` 3패턴 어디에도 안 걸린다. 다른 템플릿으로 추정. 미매칭은 `log.debug`라 본문 근거 미확보 → 진단 로그 1회 추가 필요.
3. **확인메일 자체 미발견 ~139건** — 유력 원인: `searchAccountForOrderNos`가 **INBOX만** 검색한다. 폐기 예정인 `IherbEmailSearchService`는 전 폴더/라벨을 훑었고 그 주석에 이유가 적혀 있다("POP3로 가져온 이메일은 라벨에 저장됨"). Gmail 보관처리 메일은 INBOX에 없다.
- 우선순위: 3 > 2 > 1 (커버리지 영향 순).

### D-113(후보): 죽은 중복 파서 `IherbEmailSearchService` 삭제 (2026-07-28)
호출자 0건(api, 188줄)인데 확인메일 금액 파싱을 독자 구현해 D-109의 이중 `find()` 결함을 복제하고 있었다. 이번에 같은 버그를 두 곳에서 고쳐야 했고, 통화 인식(D-111)은 이쪽에 미반영이라 되살아나면 즉시 오작동한다. 전 폴더 검색 로직(D-112-3의 해법)만 살려 `EmailFetcherService`로 옮기고 클래스는 삭제 권고.

### D-112 해소: 전체 보관함 검색 + 달러 환율 근사 주입 (2026-07-28)
- 사용자 결정: "과거 백필 결과는 어쩔 수 없다. 대신 INBOX보다 전체 메일 범위에서 찾아야 하고, 달러 주문은 환율 근사값이라도 넣어야 한다." (이메일 계정 세팅이 얼마 안 돼 과거 주문은 메일 자체가 없을 수 있음)
- **D-112-3 (검색 범위)**: `searchAccountForOrderNos`가 INBOX만 열어 보관처리된 메일을 놓쳤다. → `resolveSearchFolders` 신설. Gmail은 전체보관함(IMAP SPECIAL-USE `\All`)이 INBOX·라벨·보관함의 상위집합이라 **그 한 폴더만** 검색(전 폴더 순회 대비 검색 횟수 배수 절감 → 30분 주기 유지). 폴더명이 로케일마다 다르므로(`[Gmail]/All Mail` vs `[Gmail]/전체보관함`) 이름이 아닌 속성으로 판별. 그 외 제공자는 메일 보유 전 폴더 순회, 폴더 목록 조회 실패 시 INBOX 폴백.
- **D-112-1 (달러)**: 파서가 통화를 판별해 액면가+통화를 반환하고(`IherbConfirmationData.currency`), 원화 환산은 `EmailFetcherService.toKrw`가 담당. 기본 환율 **1473**은 달러·원화가 모두 확인된 운영 4건에서 도출($48.00→70,743 · $32.40→47,609 · $31.91→46,923 · $30.61→45,254 → 1469.41~1478.41, 편차 ±0.3%). env `IHERB_USD_KRW_RATE`로 조정, 미설정 시 미주입. 원화 하한(1,000원) 가드는 달러 미적용.
- 검증(라이브 01:25 배포, 01:30 사이클): 확인메일 20건 **전부 금액 확보(amount=null 0건)**, 신규 주입 13건, 오파싱 의심 0건. IMAP 연결 실패 0(9계정 전부 정상). 멱등 가드가 수동 입력값을 보존함을 확인 — 343934680은 환산값 70,704를 계산했으나 기존 70,743 유지, 344006073도 47,609 유지.
- 환산 정확도(수동 입력 실값 대비): 70,704 vs 70,743(−0.06%), 47,725 vs 47,609(+0.24%).
- **커버리지 추이**: 실구매가 보유 30건 → **52건**(미주입 164→142). 잔여 142건은 확인메일이 어느 메일함에도 없다(계정 세팅 이전 주문) — 사용자 수용 결정에 따라 종결. 신규 주문은 이제 정상 포착된다.
- 미해소: `younzara@nate.com` 계정의 확인메일 패턴 미매칭 8건(D-112-2)은 이번 사이클에 재현되지 않아 미확인 상태로 남는다.
- 상태: 해소(라이브 검증) — 잔여는 D-113(죽은 중복 파서 삭제) 뿐.

### D-112-2 해소: HTML 단독 확인메일의 본문이 빈 문자열 (2026-07-28)
- 신고: 사용자 — "younzara@nate.com 왜 안되는지 확인하고 해결해줘"
- 심각도: P1 (확인메일을 찾고도 금액을 못 읽어 실구매가 영구 누락)
- 진단: 미매칭 시 통화 표기 주변만 남기는 WARN을 추가해 라이브 수집 → 출력이 `(통화 표기 없음)`. 금액 라벨이 다른 게 아니라 **본문 자체가 빈 문자열**이었다.
- 근본원인: `EmailFetcherService.getTextFromMessage`가 `text/plain`과 `multipart/*`만 다루고 **`text/html`을 통째로 버려**, HTML 단독 발송 메일에서 본문이 `""`가 됐다. HTML 태그 제거는 파서의 `flattenHtml`이 이미 하고 있었으므로 본문 전달만 하면 되는 문제였다. 아이러니하게도 같은 날 삭제한 `IherbEmailSearchService`(D-113)에는 `htmlToText` 처리가 있었다 — **죽은 코드에만 있던 대응이 실사용 경로의 구멍을 가리고 있었다.**
- 수정: `text/html` 메시지·파트를 본문으로 채택. multipart는 plain을 앞·html을 뒤에 붙인다(multipart/alternative는 같은 내용 두 벌 → 순서가 우선순위, 패턴은 첫 매칭 채택이므로 plain 보유 계정은 기존 동작 유지).
- 검증: 신규 `EmailFetcherBodyExtractionTest` 4건 PASS, 4개 모듈 전체 PASS. 라이브(02:30 사이클): nate 확인메일 **8/8 파싱·주입 성공** — USD 5건 환산(38.72→57,035 · 37.23→54,840 · 76.17→112,198 · 32.64→48,079 · 34.36→50,612), KRW 3건 액면(38,608 · 36,978 · 20,744). `금액 패턴 미매칭` 0건.
- 상태: 해소(라이브 검증).

### D-113 해소: 죽은 중복 파서 삭제 (2026-07-28)
`IherbEmailSearchService`(api, 188줄) + 테스트 제거. 호출자 0건, 잔여 참조 0건, 4개 모듈 전체 PASS. 고유 자산이던 전 폴더 검색은 `resolveSearchFolders`로, HTML 본문 처리는 `getTextFromMessage`로 각각 실사용 경로에 흡수 완료.

### D-114(후보): 확인메일 큐가 영구 잔류해 사이클이 길어짐 (2026-07-28)
- 현황: 실구매가 커버리지 30 → **60건**(미주입 164→134). 잔여 134건은 계정 세팅 이전 주문이라 어느 메일함에도 확인메일이 없다(사용자 수용 결정).
- 문제: `findIherbItemsNeedingPurchaseAmount`에 기간 상한이 없어 **이 134건이 매 사이클 재검색된다.** 전 폴더 검색(D-112-3) 도입으로 비-Gmail 계정이 폴더 8~13곳을 도는 만큼, 1사이클이 02:30:00→02:45:14로 **15분**까지 늘었다(스케줄 주기 30분에 근접).
- 제안: 조회에 기간 상한(예: 주문일 최근 30~60일)을 두어 큐를 상시 소수로 유지. 과거 미주입분은 수동 입력 대상으로 종결.
- 상태: 미착수 — 사용자 결정 필요.

### D-114 해소: 검색 계획 팬아웃으로 사이클이 15분까지 늘어남 (2026-07-28)
- 신고: 사용자 — "찾을 수 없는 134건을 매 사이클 재검색 → 어떻게 해결해야돼?"
- **가설 기각**: 기간 상한은 효과 0. 미주입 134건의 주문일 분포가 최근 30일 99건 / 30~60일 35건 / **60일 초과 0건**이라 어떤 상한을 걸어도 큐가 줄지 않는다. (앞선 "계정 세팅 이전 주문이라 메일이 없다"는 설명도 데이터와 불일치 — 대부분 최근 주문이다.)
- 실제 근본원인: `resolveTargetAccounts`가 소싱 계정을 **주소 문자열 완전일치**로 찾아, 같은 제공자의 별칭 도메인을 놓쳤다. 소싱 `tonyworld@hanmail.net` ↔ 설정 `tonyworld@daum.net`이 매칭 실패 → 전 계정(9곳) 폴백. 미주입 도메인 분포 `gmail 80 · hanmail 27 · naver 13 · apple 5 · daum 5 · skku 2 · nate 1 · 미상 1`에서 **hanmail 27 + 미상 1 = 28건 × 9계정 = 252건**으로, 검색 계획 365건의 약 67%가 이 팬아웃이었다. `DOMAIN_IMAP_HOST`는 hanmail.net·daum.net을 이미 같은 `imap.daum.net`으로 매핑하고 있었는데 라우팅만 문자열 비교였다.
- 수정: (1) local-part가 같고 해석된 IMAP host가 같으면 같은 메일함으로 판정(`isSameMailbox`). local-part만 같고 제공자가 다르면 남남일 수 있으므로 비매칭 후 폴백. 비-Gmail은 중앙 전달 대상이 아니라 직접 수집이라 좁혀도 커버리지 손실 없음(기존에도 폴백으로 결국 같은 메일함을 뒤졌다). (2) 보낸편지함·임시보관함 제외 — IMAP SPECIAL-USE(`\Sent`/`\Drafts`) 우선, 없으면 폴더명 판정. 스팸·휴지통은 진짜 확인메일이 들어갈 수 있어 유지.
- 검증(라이브 04:30 사이클): 검색 계획 **365 → 141건(−61%)**, 대상 계정 9 → 8곳. 계정별 폴더수 감소(naver 9→7, daum 8→6, tonyworld 11→7, oasis 11→8, nate 8→6). **1사이클 15분 14초 → 7분 49초(−49%)**. 커버리지 불변(134건 유지 — 애초에 메일이 없는 건들). `EmailFetcherRoutingTest` 9건 PASS, 4개 모듈 전체 PASS.
- 상태: 해소(라이브 검증).

### D-115 해소: iHerb 확인메일 제목·총액 라벨 변경 미대응 (2026-07-28)
- 신고: 사용자 — "343977053 이메일이 와있어. '결제가 처리되었습니다' 또한 캐치해야 할 것 같아" (받은편지함 스크린샷)
- 심각도: P1 (신규 주문의 실구매가가 계속 누락 — 최근 주차 충전률 50~69%의 주원인)
- 실제 메일함(343977053): `주문이 발송되었습니다 #343977053` / `iHerb 주문 #343977053 결제가 처리되었습니다` / `주문번호 #343977053 결제 대기 중` / `NHN KCP … PAYCO Order Confirmation`. **"주문이 확인되었습니다"는 아예 없다.**
- 근본원인 3중:
  1. 확인메일 판정이 `"확인되었습니다"` 한 문구에만 걸려 있어 `"결제가 처리되었습니다"`를 놓쳤다.
  2. 제목 분기가 `"확인되었습니다 #" + 주문번호`처럼 **문구와 주문번호의 인접·순서를 가정**했는데, 새 제목은 주문번호가 문구 **앞**에 온다(`주문 #343977053 결제가 처리되었습니다`).
  3. 제목을 고쳐 잡은 뒤에도 금액이 null이었다 — 진단 로그가 본문을 드러냈다: `결제 유형: 페이코 총 주문: ₩40,418`. **총액 라벨이 `총 주문`**이라 기존 3패턴(총 결제 금액/총 금액/합계) 어디에도 안 걸렸다.
- 수정: `isConfirmationSubject`/`isShipmentSubject`로 제목 판정 분리(+"결제가 처리되었습니다", "결제 대기 중"은 금액 확정 전이라 제외) · 주문번호 추출을 제목 내 `#(\d+)` 위치 무관으로 변경 · `processMatchedMessage`는 "제목에 #주문번호 포함"만 확인 · 금액 패턴에 `총 주문` 추가(총 결제 금액 다음, 총 금액보다 앞). "총 주문 수량 3" 같은 비금액 매칭은 기존 원화 하한(1,000원) 가드가 걸러낸다.
- 곁가지 정리: 호출자 0건인 `findIherbConfirmationAmount`/`searchConfirmationInAccount` 삭제(75줄). 같은 `"확인되었습니다 #주문번호"` 가정을 품어 되살아나면 즉시 오작동한다(D-113과 동형의 함정).
- 검증: `OrderEmailParserConfirmationTest` 14건 PASS(신규 5건), 4개 모듈 전체 PASS. 라이브: 신규 주입 **8건** — 343977053=17,426(제보 건) · 343976970=39,858 · 343934892=33,565 · 343934970=16,149 · 343932356=41,738 · 343914785=40,418 · 343212013=31,435 · 343286448=43,922. **`금액 패턴 미매칭` 0건.** 미주입 134 → **126건**.
- 교훈: 진단 로그(통화 표기 주변 발췌)가 3중 원인 중 2·3번을 각각 한 사이클 만에 드러냈다. 포맷 의존 파서는 **미매칭 시 실제 입력을 남기는 것**이 수정보다 먼저다.
- 상태: 해소(라이브 검증).

### D-116: 실구매가 인식 실패를 활동 로그에 노출 (2026-07-28)
- 신고: 사용자 — "미매칭 알림 ActionLog에 남겨서 화면에서 보이게 해줘"
- 배경: D-115(제목·총액 라벨 변경)는 **사용자가 메일함을 직접 보고 제보**해서 발견됐다. 포맷 변경은 조용히 누락을 만들고, 서버 로그의 WARN은 아무도 보지 않는다.
- 구현: 확인메일은 찾았으나 금액을 못 읽은 경우 `ActionLog(PURCHASE_AMOUNT_PARSE, marketType=EMAIL, FAILED)`에 주문번호 + 본문 통화 표기 발췌를 기록. 진단 발췌는 파서가 `IherbConfirmationData.amountDiagnostic`으로 전달한다(본문 전체는 주소·연락처 포함이라 통화 표기 주변만). 프론트 `ProcessStatusPage`의 `actionTypeLabels`에 '실구매가 자동인식' 추가 — `EMAIL` 마켓 라벨('이메일')은 기존 존재.
- 중복 억제: 같은 주문이 30분마다 재시도되므로 **주문번호당 1회**만 기록(JVM 인메모리 Set). 배포 시 초기화되어, 수정 후 한 번 더 기록되므로 수정이 실제로 먹혔는지 확인할 수 있다. 컬럼 추가(스키마 수동 DDL) 없이 처리했다.
- 검증: `EmailFetcherConfirmationQueueTest` 12건 PASS(신규 3건 — 실패 기록·주문번호당 1회·성공 시 무기록). 4개 모듈 전체 PASS. 프론트 `tsc -p tsconfig.app.json` + `npm run build` PASS.
- **라이브 미검증**: 배포(06:37) 후 수집을 돌렸으나 현재 파싱 실패가 0건이라 기록이 생기지 않았다(`sb_action_log`에 `PURCHASE_AMOUNT_PARSE` 0행). 오탐이 없다는 것만 확인됐고, 실제 기록 경로는 다음 포맷 변경 때 확인된다.
- 상태: 구현완료(단위검증) — 라이브 발화 대기.

### D-111 잔여 해소: 오염 2건 수동 교정 (2026-07-28)
사용자가 원본 메일에서 액면가를 확인해 전달($31.75 / $35.50). 환율 1473 적용해 교정:
`343280106` 31원 → **46,768원**($31.75) · `343628939` 35원 → **52,292원**($35.50).
`sourcing_amount < 1000` 조건부 UPDATE로 트랜잭션 실행. 소액 오염 잔여 **0건** 확인.
현재 커버리지: iHerb 194건 중 미주입 **126건**(충전 68건).

### D-117: 스마트스토어 "발주확인 해제"를 주문취소로 오매핑 → 살아있는 발송대기 주문 소실 (2026-08-03)
- 심각도: **P1** (기능 불능 — 실 매출 손실·발송기한 초과 발생) · 리스크 등급: 중대(마켓 상태 계약)
- 신고: 사용자 — "2026072260087751 허경덕 네이버 주문이 누락됐다". 해당 번호는 **주문번호(orderId)**이고 상품주문번호는 `2026072251442781`(주문 id=203).
- 증상: 네이버 판매자센터는 `주문상태=발송대기 / 주문세부상태=발주확인해제 / 발송기한 초과`인데, 우리 DB는 `shipping_status=CANCELED` + `settlement_amount=0.00`. 취소건으로 분류되어 발송 작업목록에서 사라졌다.
- 근본원인: `SmartStoreStatusMapper.mapStatus`가 `PAYED`(결제완료=발송대기) 분기에서 `placeOrderStatus=CANCEL`을 `ShippingStatus.CANCELED`로 매핑. `placeOrderStatus`는 취소 여부가 아니라 **발주확인 여부**(OK 확인완료 / NOT_YET 확인전 / CANCEL 확인해제)를 나타내는 **별개의 축**이다. 실제 취소는 `productOrderStatus=CANCELED`/`CANCELED_BY_NOPAYMENT` 분기가 이미 담당하고 있었다.
- 2차 피해: D-098 `TerminalSettlementService.zeroSettlementForRefunded(SMART_STORE)`가 이 오분류 건을 종결건으로 보고 정산액을 0으로 내려 **정산 데이터까지 오염**시켰다. 오분류가 정산 정규화로 증폭되는 경로 확인.
- 진단 근거(로그 대조): 10시간/21런 로그에서 07-22 UTC 청크 성공 7회 ↔ `2026072251442781` 처리 7회로 1:1 대응 확인. 사용자가 제시한 번호는 21런 전체에서 0회 → productOrderId가 아님을 확정.
- 수정: `PAYED`는 `OK`면 PREPARING, 그 외(NOT_YET·CANCEL·누락) 전부 **NEW(발송대기)**. 취소 판정은 productOrderStatus 단독.
- 동반 수정(P2, 잠재 유실): `SmartStoreOrderAdapter.java:155` — `placeOrderStatus`가 응답에 없으면 `asText(null)`→`Map.of` **NPE**→`parseOrderNode` catch가 삼킴→**주문 통째 드롭**. `asText("")`로 정규화.
- 검증: `SmartStoreStatusMapperTest` 9건 PASS(신규 6건 — CANCEL→NEW, OK→PREPARING, NOT_YET→NEW, null 안전, 실제취소/미결제취소 CANCELED 회귀). `SmartStoreOrderParseTest` 신규 1건 PASS(Red 재현 후 Green). 4개 모듈 전체 회귀 PASS.
- 오염 교정: 배포 후 동기화가 203번을 NEW로 자동 복원(`applyShippingData`에 전이 가드 없음). 정산액은 동기화가 갱신하지 않으므로 **수동 복원 필요**.
- 무관 확인: 06-14 취소 2건(이명동=미결제취소, 정가영=취소)은 사용자 확인 결과 **실제 취소** — 현재 CANCELED가 정상, 교정 대상 아님.
- 상태: **수정완료** — 배포·라이브 검증 대기.

### D-118: 스마트스토어 동기화가 매 런 429로 날짜 청크 12~17개 유실 (2026-08-03)
- 심각도: **P1** (주문 수집 신뢰성 붕괴) · 리스크 등급: 표준
- 발견: D-117 진단 중 로그 대조로 발견(사용자 미신고).
- 증상: `스마트스토어 주문 부분 조회: 16 chunk 성공, 15 chunk 실패 (429 TOO_MANY_REQUESTS)`가 매 런 반복. 실패 청크가 런마다 무작위로 바뀌어 **특정 주문이 언제 보일지가 복불복**. 07-26 창은 21런 중 17런 실패.
- 근본원인: (1) 30일 창을 30분마다 **전량 재조회** — 런당 최대 62 API 호출, (2) `SmartStoreOrderAdapter.java:96` `Thread.sleep(300)`이 **성공 경로에만** 있어 실패 시 즉시 다음 호출 → 429 연쇄 폭주(31청크가 4초 만에 완주), (3) 재시도·백오프 없음.
- 은폐: 부분 실패가 WARN 1줄로만 남고 동기화는 `주문 동기화 완료`로 **성공 보고**된다. 사용자에게도 정상으로 보인다.
- 수정(2026-08-03): 청크 간 지연을 **성공·실패 무관하게 항상** 적용(`chunkDelayMillis` 2초 — 31청크면 런당 약 60~90초, 동기화 주기 30분 대비 충분히 여유), 429에 한해 **지수 백오프 재시도**(5초→10초→20초, 최대 3회). 429가 아닌 오류(401 등)는 재시도해도 결과가 같고 호출만 늘리므로 즉시 전파. 조회 창 축소는 하지 않았다 — 사용자가 "매우 천천히 동기화해도 괜찮다"고 명시했고, 창을 줄이면 늦게 도착하는 변경을 놓칠 위험이 새로 생긴다.
- 검증: `SmartStoreOrderThrottleTest` 4건 PASS(429 재시도 회수·재시도 소진 시 예외 전파·비429 미재시도·정상 1회). 기존 `SmartStoreOrderFetchFailureTest`는 지연 0으로 조정.
- 상태: **수정완료** — 배포·라이브 검증 대기(배포 후 "부분 조회" WARN의 실패 청크 수가 0에 수렴하는지 확인).

### D-119: ESM+(G마켓/옥션) 갱신 경로가 마켓 송장을 동기화하지 않음 (2026-08-03)
- 심각도: **P1** (배송정보 유실) · 리스크 등급: 표준
- 신고: 사용자 — "옥션 서종수 주문이 배송완료인데 택배사·송장번호가 비어 있다".
- 대상: 주문 id=196(AUCTION `2566278285`, 서종수, DELIVERED, tracking 없음), id=223(GMARKET `4473983719`, 전동명, SHIPPED, tracking 없음).
- 근본원인: `Cafe24OrderSyncService`의 갱신 경로가 **배송상태만 갱신하고 송장은 의도적으로 제외**했다(구 주석: "트래킹은 마켓 전송 가드가 있는 별도 경로에서 관리"). 그 "별도 경로"는 **우리가 마켓으로 송장을 보낼 때만** 동작하므로, G마켓/옥션 화면에서 직접 입력된 송장은 어느 경로로도 유입되지 못했다. 생성 시점(`buildLineItem`)에만 `tracking_no`를 읽으므로, 주문 수집 후에 마켓에서 입력된 송장은 영구 누락.
- 구조적 함의: `OrderService.failIfNotSent`의 terminal 메시지는 "마켓 송장이 동기화로 반영됩니다"라고 안내하는데, ESM+에 한해 그 약속이 **지켜지지 않는 상태**였다. 수동 입력을 롤백해 놓고 동기화도 안 되니 어떤 경로로도 채울 수 없는 막다른 길이었다.
- 수정: 갱신 경로에 `applyItemShipping` 신설 — 배송상태와 함께 `tracking_no`를 반영하되 **실값일 때만**(빈 값·자리표시자로 기존 송장을 덮지 않음). 택배사는 `shipping_company_code`/`_name`을 `ShippingCarrier.fromMarketCode`로 매핑, 미매핑이면 기존값 유지. 생성 경로에도 같은 가드 적용.
- 부수 발견: 운영 DB에 송장 `00000000`이 저장된 건 2건(id=170 김우영, id=172 곽금희) — 생성 경로가 마켓 자리표시자를 그대로 저장한 결과. 신규 가드가 재유입을 차단하며, 기존 2건은 **수동 정리 대상**.
- 검증: `ShippingDataTrackingGuardTest` 4건 PASS. 4개 모듈 전체 회귀 PASS.
- 상태: **수정완료** — 배포 후 196·223번이 동기화로 채워지는지 확인 필요.

### D-120: 송장 동기화 가드가 "마켓에서 직접 입력한 송장"을 영구 차단 (2026-08-03)
- 심각도: **P2** (잠재 유실 — 현재 실피해는 D-119로 표면화) · 리스크 등급: 표준
- 근본원인: `CoupangOrderSyncService.updateLineItemFromDto`가 `trackingSentToMarket == TRUE`일 때만 마켓 송장을 반영했다. 즉 **우리가 보낸 송장만** 되받고, 판매자가 쿠팡 윙에서 직접 입력한 송장은 영원히 유입되지 않는다. 가드의 실제 목적은 "마켓의 빈 값이 우리 실값을 지우는 것"을 막는 것인데, 전송 여부를 기준으로 삼아 목적과 수단이 어긋나 있었다.
- 수정: 판정 기준을 **전송 여부 → 마켓 값의 유의미성**(`ShippingData.isMeaningfulTracking`)으로 교체(D-107/108 PII 가드와 동형). 11번가·스마트스토어 갱신 경로에도 같은 가드를 적용 — 이 둘은 가드 없이 dto 값을 그대로 반영하고 있어 마켓이 빈 문자열을 주면 기존 송장이 지워질 수 있었다.
- **트레이드오프(의도된 변경)**: 로컬에서 송장을 수정했으나 마켓 전송이 *스킵*된 상태에서 마켓이 다른 실값을 갖고 있으면, 이제 마켓 값이 로컬 값을 덮는다. 사용자가 "각 마켓 사이트의 상태가 진짜"를 우선순위로 명시했으므로 의도한 동작이다.
- 검증 한계: `updateLineItemFromDto`가 private이고 쿠팡 동기화 서비스는 의존성 11개라 배선 자체의 직접 단위테스트는 없다. 판정 로직(`isMeaningfulTracking`)만 단위테스트로 덮었고 배선은 전체 회귀로 확인했다.
- 상태: **수정완료** — 배포 후 라이브 확인 권장.

### D-121: 이메일 송장 반영이 PREPARING 외 상태를 통째로 스킵 (2026-08-03)
- 심각도: **P1** (배송정보 유실) · 리스크 등급: 표준
- 근본원인: `EmailFetcherService.processIherbShipment`가 DISPATCHED/SHIPPED(수정 경로)와 PREPARING(최초 등록)만 처리하고, **그 외 상태(NEW·DELIVERED 등)는 로그만 남기고 스킵**했다. 발주확인 전(NEW)이나 이미 배송완료로 넘어간 뒤 iHerb 발송메일이 도착하면 송장이 영구 누락된다. 서종수 건이 이 경로에도 걸렸다(소싱주문번호 `343911144` 보유).
- 수정: 송장·택배사는 **상태와 무관하게 기록**한다(배송상태는 건드리지 않으므로 마켓 진실 훼손 없음). 마켓 전송은 마켓이 받아주는 상태(PREPARING)에서만 시도하고, NEW·DELIVERED 등은 로컬 기록만 남긴다 — 마켓 송장은 동기화가 가져온다(D-119로 ESM+에서도 성립).
- 검증: `EmailFetcherTrackingFillTest` 2건 PASS(DELIVERED·NEW 모두 송장 기록 + 마켓 전송 미시도 + 배송상태 불변). 구코드에서는 `save()` 자체가 호출되지 않아 Red.
- 상태: **수정완료**.

### 송장 채움 경로 종합 점검 결과 (2026-08-03)

사용자 요청으로 3개 경로 × 5개 마켓을 전수 대조했다. 결손은 ESM+ 2건뿐이고 나머지 마켓은 0건이었다.

| 마켓 | 갱신 시 마켓 송장 반영 | 수정 전 문제 |
|------|----------------------|-------------|
| 쿠팡 | 조건부(`trackingSentToMarket`) | 마켓 직접입력 송장 유입 불가 → D-120 |
| 스마트스토어 | 무조건 반영 | 빈 값이 실값을 덮을 수 있음 → D-120 |
| 11번가 | 무조건 반영 | 동일 → D-120 |
| G마켓·옥션(Cafe24) | **미반영** | 생성 시에만 반영 → D-119 |

**수동 입력 경로 검토(validation 조정 여부)**: `OrderService.updateShippingInfo`는 종료(취소/반품/교환)와 NEW/UNKNOWN만 차단하고 **DELIVERED는 이미 허용**한다. 서종수 건이 수동으로도 안 되던 이유는 상태가드가 아니라 `failIfNotSent`의 롤백 — 마켓이 배송완료 주문의 송장 등록을 거부하면 로컬 저장까지 되돌린다. 이 롤백은 **유지**하기로 했다: 마켓이 진실 원본이라는 원칙에 맞고, D-119로 "동기화가 반영한다"는 안내가 이제 실제로 성립하기 때문이다. 마켓이 전송을 *스킵*하는 경우(어댑터 미지원 등)는 롤백 대상이 아니어서 수동 기록이 그대로 보존된다 — 즉 불필요하게 막힌 케이스는 없다는 결론. (`processShipping`/`updateTrackingInfo`는 상태범위가 더 좁지만 UI 경로가 아니다.)

### D-117 후속: 정산액 복원 완료 (2026-08-03)

배포 후 확인 결과 주문 203(허경덕)은 **오매핑 피해가 자연 해소**돼 있었다 — 08-01 04:15 동기화에서 실제 발송·배송완료로 진행되며 `DELIVERED` + 송장 `365126390452`(CJ대한통운)로 마켓 상태가 정상 경로로 덮어썼다. 발송기한 건은 종결. 다만 오매핑이 남긴 **정산액 0 오염만 잔존**했다.

사용자가 판매자센터 실측치 제공(실결제 56,800원 / 정산예정 53,991원). 추정 대신 실측치를 그대로 반영하고 `settlement_verified=true`로 확정 표시:
`UPDATE sb_order_line_item SET settlement_amount=53991, settlement_verified=true WHERE id=434 AND settlement_amount=0` → 1행. D-117 **완전 종결**.

### D-122: 스마트스토어 수수료율 가정(8%)이 실제(약 4.9%)와 3%p 괴리 (2026-08-03)
- 심각도: **P2** (금액 왜곡 — 정산 추정 + 판매가 산정 양쪽에 전파) · 리스크 등급: 표준
- 발견: D-117 정산액 복원 중 실측치 대조로 발견(사용자 미신고).
- 근거: 실결제 56,800원 → 실제 정산예정 53,991원 = **실효 수수료 4.945%**. `sb_fee_policy`의 SMART_STORE 값은 **8.00%**라 추정치가 52,256원으로 나와 **1건당 1,735원(약 3.2%) 과소평가**된다.
- 파급: `MarketFeeService.settlementAmount`는 정산 추정에만 쓰이는 게 아니라 **마켓별 판매가 산정**(D-094 per-market pricing)에도 쓰인다. 수수료를 과대 가정하면 판매가가 실제 필요보다 높게 잡혀 가격 경쟁력이 깎인다. 정산 화면 숫자만의 문제가 아니다.
- 미확정 사항: 네이버 수수료는 카테고리·채널(스마트스토어/쇼핑 연동)·정산 유형에 따라 달라진다. 관측치가 **1건**뿐이라 4.945%를 전 카테고리 대표값으로 단정할 수 없다. 다른 마켓(쿠팡 11%, 11번가·ESM+ 18%)의 가정도 같은 방식으로 미검증이다.
- 권고: (1) 마켓별 실측 정산액 표본을 모아 실효 수수료율 산출 → `sb_fee_policy` 갱신, (2) `sb_fee_policy`가 카테고리별 행(`category_name`)을 이미 지원하므로 카테고리 편차가 크면 세분화, (3) `settlement_verified=true`인 행을 기준으로 추정 오차를 주기 점검하는 리포트.
- 상태: **발견** — 미수정(수수료 실측값 확보가 선결).

### D-123: 마켓 영구 거부를 일시 실패로 오분류해 30분마다 무한 재시도 (2026-08-03)
- 심각도: **P2** (불필요한 마켓 API 호출 무한 반복 + 로그 오염 + 감사기록 오귀속) · 리스크 등급: 표준
- 발견: D-118/119 배포 검증 중 운영 로그에서 발견(사용자 확인 후 수정 지시).
- 증상(운영 로그, 매 이메일 수집 사이클 반복):
  - `11번가 발송처리 실패: 존재하지 않는 배송번호 입니다` — 소싱주문 343914536·343913856·344006013·344050007
  - `Cafe24 POST 422: "You cannot change to that order state." (order_id=20260630-0000017, status=shipping)` — 소싱주문 344049710
- 근본원인: `MarketplaceShippingService.isNonRetryableMarketState`가 **쿠팡 문구만** 인식했다(`배송진행상태가 유효하지 않습니다` 등, D-E6 당시 쿠팡 사례만 알려져 있었음). 11번가·Cafe24의 영구 거부는 매칭되지 않아 `ofFailed`(일시)로 분류 → `EmailFetcherService.handleMarketResult`가 미마킹 → **30분마다 영원히 재시도**. 종결 처리 기전(`isTerminal` → `markTrackingAsSent`)은 이미 있었고, **분류만 마켓 편향이었다.**
- 동반 결함: 종결 감사 로그가 마켓을 `"COUPANG"`으로 **하드코딩**해, 11번가·Cafe24 종결 건까지 쿠팡으로 기록됐다. 원인 추적이 어긋나는 오귀속.
- 수정: (1) 11번가 `존재하지 않는 배송번호`·Cafe24 `You cannot change to that order state`를 terminal로 인식(5xx 등 일시 오류는 재시도 유지), (2) 종결 로그의 마켓을 `marketTypeOf(item)`로 실제 값 기록.
- **한계(명시)**: 판정이 **문구 매칭**이라 마켓이 메시지를 바꾸면 다시 무한 재시도로 돌아간다. 재시도 반복이 관측되면 이 목록부터 확인할 것. 구조적으로는 재시도 횟수 상한(N회 후 강제 종결)이 문구 의존성을 없애는 방향이나, 이번 범위에서는 다루지 않았다.
- 검증: `MarketplaceShippingTerminalTest` 5건 PASS(신규 3건 — 11번가·Cafe24 terminal 분류, Cafe24 5xx는 재시도 유지). `EmailFetcherTerminalMarketLogTest` 신규 1건 PASS(종결 로그에 실제 마켓 기록). 구코드에서 각각 Red 확인. 4개 모듈 clean 회귀 PASS.
- 상태: **수정완료** — 배포 후 해당 소싱주문들의 재시도 로그가 멈추는지 확인 필요.

### D-119 라이브 검증 결과: ESM+ 송장 미해소 — 원인 재규정 (2026-08-03)

배포 후 사용자가 수동 동기화 실행(02:30:56, `[CAFE24-ORDER] 9건`). **196·223 송장은 여전히 비어 있다.** D-119 수정만으로는 해결되지 않는 건이었다.

관측 근거: Cafe24 응답에 `tracking_no` 필드 자체는 존재한다 — 주문 170·172의 `00000000`이 그 경로로 유입된 값이다. 그런데 196·223은 채워지지 않았다. 가장 정합적인 해석은 **Cafe24가 이 주문들의 실제 송장을 보유하지 않는다**는 것 — 옥션/G마켓 판매자센터에 입력된 송장이 Cafe24 주문 페이로드로 역류하지 않는 구조로 보인다.

D-119 수정 자체는 유효하다(Cafe24가 값을 가진 경우의 경로 + 자리표시자 차단). 다만 없는 데이터를 만들 수는 없다.

**미확정 — 다음 단계의 분기점**: 옥션 판매자센터에 주문 `2566278285`의 송장이 실제로 등록돼 있는가?
- **옥션엔 있고 Cafe24엔 없다** → Cafe24 `/admin/orders/{order_id}/shipments` **하위 리소스 별도 조회**가 필요하다(현재는 주문 목록의 item 필드만 읽는다). Cafe24ShipmentService가 이미 그 엔드포인트에 POST하고 있으므로 GET 경로를 추가하는 형태.
- **어디에도 없다** → 송장 없이 배송완료 처리된 건. iHerb 발송메일(소싱번호 343911144)이 유일한 출처이며, D-121로 경로는 열렸으나 **이미 처리된 메일은 재수집되지 않아** 이 건은 수동 입력이 필요하다.

### D-118 라이브 검증 결과: 429 완전 해소 확인 (2026-08-03)

| 지표 | 배포 전(21런 관측) | 배포 후 |
|------|------------------|--------|
| `TOO_MANY_REQUESTS` | 매 런 12~17건 | **0** |
| `부분 조회` 경고 | 매 런 | **0** |
| 유실 청크 | 12~17 / 31 | **0** |
| 처리 건수 | 2~7건 | **11건** |

31청크 전량 성공. 백오프 재시도는 **발동조차 하지 않았다** — 청크 간 2초 간격만으로 네이버 호출 한도 안에 들어왔다. 조회 창 축소 없이 목표 달성.

### D-124: Cafe24 배송건 목록에서 실송장 탐색 (2026-08-03)
- 심각도: **P1** (ESM+ 배송정보 유실 지속) · 리스크 등급: 표준
- 배경: D-119(주문 item의 `tracking_no` 반영) 배포 후에도 196(서종수)·223(전동명)이 채워지지 않았다. 사용자 확인 결과 **Cafe24에는 두 건 모두 `00000000` CJ대한통운 "자체배송"**으로 배송건이 등록돼 있고, 옥션/G마켓 판매자센터의 실제 송장(`424437596623` CJ · `6079990333504` 우체국)은 Cafe24로 넘어오지 않는다. 사용자 관찰: 마켓플러스 배송관리의 "주문수집"이 주문정보는 가져오나 **송장정보는 가져오지 못한다**.
- `00000000`의 출처: **우리 시스템이 아니다.** 코드 전체에 해당 문자열을 쓰는 곳이 없고(`Cafe24ShipmentService.ship`은 빈 송장 전송을 차단), Cafe24 마켓연동이 주문 상태를 "배송중"으로 맞추려 넣은 자리표시자로 판단된다.
- 이번 조치(사용자 지시 — B/C안 배제, Cafe24 API를 최대한 활용): 주문 목록의 `items[].tracking_no`는 배송건이 여러 개일 때 하나만 비칠 수 있으므로 **`GET /admin/orders/{order_id}/shipments`를 추가 조회**해 실값 송장을 가진 배송건을 찾는다.
  - `Cafe24OrderApiPort.fetchShipments` + 클라이언트 구현 신설
  - `Cafe24ShipmentTrackingLookup` 신설 — 배송건 순회하며 `isMeaningfulTracking` 통과 첫 건 채택, 택배사 코드/명도 해석
  - 호출 억제: **이미 실송장 보유 시 또는 발송 전(NEW/PREPARING) 상태에서는 호출하지 않는다** — 발송 이후(DISPATCHED/SHIPPED/DELIVERED)이고 실송장이 없을 때만 1회.
  - 조회 실패는 삼킨다 — 송장 보강 때문에 주문 동기화 전체가 깨지면 안 된다.
- **진단 로그 내장**: 배송건이 있는데 전부 자리표시자면 `"배송건 N건이 모두 자리표시자 — 마켓 실송장 미연동"`을 남긴다. 이번 조사가 길어진 이유가 "Cafe24가 실제로 무엇을 주는지 볼 수 없었던 것"이므로, 가설을 배포로 검증하는 왕복을 없애기 위해 관측점을 코드에 심었다.
- **미확정(배포 후 판명될 것)**: Cafe24 배송건 목록에 실송장이 아예 없을 가능성이 높다(관리자 화면이 `00000000`을 보여주므로). 그 경우 이 조회는 값을 찾지 못하고 위 진단 로그만 남는데, **그것이 곧 "Cafe24 경로로는 불가능"의 확증**이 된다. 이 시도의 목적은 값 획득과 확증 중 하나를 반드시 얻는 것이다.
- 검증: `Cafe24ShipmentTrackingLookupTest` 6건 PASS(자리표시자 혼재 시 실값 선택·전부 자리표시자면 null·배송건 없음·API 실패 무전파·주문id 없으면 미호출·택배사명 해석). 4개 모듈 clean 회귀 PASS.
- 상태: **수정완료** — 배포 후 로그로 판정.

### 수동 교정 기록 (2026-08-03)
- 주문 196 서종수(AUCTION `2566278285`, Cafe24 `20260719-0000018`): 옥션 실송장 `424437596623`(CJ대한통운) 수동 반영, `tracking_sent_to_market=true`(옥션에 이미 등록됨). 구매자 `한우집위` / 수취인 `서종수`로 저장돼 있어 데이터는 정상.
- 주문 223 전동명(GMARKET `4473983719`, Cafe24 `20260730-0000016`): 옥션에 **가짜 송장** `6079990333504`(우체국) 등록 — 발송지연 회피 목적, 실제 미구매(`NOT_PURCHASED`). 아직 미반영.
  - **주의(백로그 후보)**: 가짜 송장 건이 시스템에 그대로 동기화되면 "배송중이나 실제 미발송" 주문이 데이터에 섞인다. 정산·CS에서 구분할 수단(메모/플래그)이 없다.

### D-125: 마켓 전송 실패가 로컬 송장 기록까지 롤백 — 기록 경로 전면 차단 (2026-08-03)
- 심각도: **P1** (배송정보 기록 불가) · 리스크 등급: **중대**(D-069 계약 반전 — 전 마켓 공통 동작 변경, 사용자 승인 받음)
- 재현(라이브): 통합 주문 관리에서 주문 223(전동명, GMARKET `4473983719`)에 우체국 `6079990333504` 입력 → 전송 → `PATCH /api/v1/orders/line-items/454/shipping` → `IllegalStateException: 마켓(GMARKET) 송장 반영 실패로 저장을 롤백합니다` → DB 미반영.
  - 1차 시도(12:03)는 `invalid_grant / Invalid refresh_token` — **Cafe24 리프레시 토큰 만료**가 겹쳐 있었다. 사용자가 재인증한 뒤 2차 시도(12:06)에서도 `Cafe24 API POST 호출 실패`로 동일 롤백 → 토큰과 무관한 구조 문제임이 분리 확인됐다.
- 근본원인: `OrderService.failIfNotSent`가 마켓 전송 실패 시 예외를 던져 `@Transactional` 롤백을 유발했다. 이 계약(D-069 후속)의 근거는 **"마켓 송장이 동기화로 반영된다"**였는데, D-124 라이브 조사로 ESM+에서는 그 전제가 거짓임이 확증됐다(Cafe24에 `00000000` 더미만 존재).
- 결과적 상태 — **세 경로 전부 차단**:

  | 경로 | 상태 |
  |------|------|
  | 마켓 동기화 유입 | ✗ Cafe24에 실송장 없음(D-124 확증) |
  | 이메일 유입 | ✗ 미구매 건이라 iHerb 발송메일 없음 |
  | 수동 입력 | ✗ 마켓 전송 실패 → 롤백 |

  롤백이 "마켓과의 불일치 방지"가 아니라 **기록 자체를 막는** 상태였다.
- 수정: `failIfNotSent` → `logIfNotSent`. 마켓 전송 실패(일시·영구 모두)에도 **로컬 송장 기록을 보존**하고, 미전송은 `trackingSentToMarket`을 올리지 않는 것으로 표현해 재시도 대상으로 남긴다. 종료상태(취소/반품/교환) 차단과 NEW/UNKNOWN 차단은 **그대로 유지**.
- 원칙 정리: D-120의 "마켓이 진실"은 **읽기 방향**에 적용한다. **쓰기 실패는 로컬 기록을 지우는 근거가 아니다** — 송장은 마켓 API 호출 성공 여부와 무관하게 실재하는 사실이다.
- 적용 범위: 사용자 선택으로 **전 마켓 공통**(대안이었던 ESM+ 한정 예외는 마켓별 규칙 분기를 늘려 기각). 쿠팡·11번가도 전송 실패 시 로컬 보존으로 바뀐다 — 화면에 "저장됨·마켓 미반영"으로 구분돼 보이는 편이 조용히 사라지는 것보다 낫다는 판단.
- 검증: `OrderServiceTrackingPreservedOnSendFailureTest` 신규 4건 PASS(일시실패·영구거부 보존 / 성공 시 마킹 회귀 / 종료상태 차단 회규). 계약을 검증하던 기존 테스트 2건(`OrderServiceShippingGuardTest`·`OrderServiceShippingRollbackTest`)은 **의도된 계약 변경**이므로 새 계약으로 갱신. 4개 모듈 clean 회귀 469건 PASS.
- 상태: **수정완료** — 배포 후 223번 수동 입력 재시도로 라이브 확인 필요.

### 부수 확인: Cafe24 리프레시 토큰 만료 (2026-08-03)
D-125 재현 중 `{"error":"invalid_grant","error_description":"Invalid refresh_token"}` 발견. Cafe24로 나가는 **모든 쓰기 작업**(발주확인·취소·송장전송)이 실패하는 상태였고, 이메일 경로의 반복 실패(소싱주문 344049710 등)도 같은 원인이었다. 사용자가 재인증해 해소. **토큰 만료를 조기에 드러내는 알림이 없다는 점은 백로그 후보** — 이번에도 사용자 신고가 아니라 우연히 발견됐다.

### D-126: 11번가 배송중 목록이 진행상태를 덮어써 결제완료 주문이 "배송중"으로 표시 (2026-08-05)
- 심각도: **P1** (주문상태 오표시 — 발주·발송 판단의 근거가 뒤집힘) · 리스크 등급: 표준
- 신고: 주문 `20260731088778989`(정나영, 11번가)가 판매자센터에서는 **결제완료**인데 그리드에서는 **배송중**.
- 라이브 진단: 같은 동기화 사이클 안에서 이 주문이 **두 번** 처리되고 있었다.
  ```
  00:50:38.848 [ELEVEN_STREET] 처리 중: orderNo=20260731088778989, status=NEW
  00:50:38.853 [ELEVEN_STREET] 처리 중: orderNo=20260731088778989, status=SHIPPED
  ```
  결제완료 목록(NEW)과 배송중 목록(SHIPPED) 양쪽에 나타났고, `MarketOrderUpsertDispatcher`가 목록 순서대로 순회하므로 **뒤에 오는 SHIPPED가 이겼다**.
- 근본원인 두 겹:
  1. **11번가의 4개 목록은 상호배타적이지 않다.** `/rest/ordservices/shipping`(배송중)은 *송장이 등록된 주문*을 돌려주며 진행상태가 결제완료여도 포함된다. 사용자 확인으로 확증 — 발주확인이 11번가에 반영되지 않은 상태에서 송장만 등록돼 "결제완료 + 송장 보유"가 됐다.
  2. `parseShippingElement`가 그 목록의 항목을 **무조건 `ShippingStatus.SHIPPED`로 하드코딩**했고, `fetchOrders`는 네 목록을 **단순 concat**해 충돌 해소 규칙이 없었다.
- 수정: 주문번호를 키로 **병합**하고 상태는 목록별 신뢰 등급으로 판정한다.
  - `RANK_PROGRESS`(결제완료·배송준비중) > `RANK_DELIVERED`(배송완료) > `RANK_SHIPPING`(배송중)
  - 근거 — 결제완료·배송준비중 목록은 11번가의 **진행상태를 직접** 뜻해 확정적이고, 배송중 목록은 **송장 보유 사실**만 뜻해 상태 근거가 되지 못한다. **진행상태 축이 배송 축을 이긴다.**
  - 상태만 우선순위로 고르고 **나머지 필드는 빈 칸을 채우는 방향으로만 병합**한다 — 결제완료가 이겨도 배송중 목록이 준 송장은 보존된다("결제완료인데 송장 있음"이 실제 상태다). 반대로 배송중 목록의 최소 정보가 결제완료 목록의 상품명·수량·`ordPrdSeq`/`dlvNo`를 지우지도 않는다.
  - 주간 chunk를 넘나드는 중복도 함께 해소된다 — 배송중 목록의 날짜 축은 주문일이 아니어서 같은 주문이 다른 chunk에 나타난다(라이브 관찰).
- 검증: `ElevenstStatusAxisPrecedenceTest` 7건 PASS(결제완료 우선·송장 보존 / 배송중 단독은 SHIPPED 유지 / 배송완료 > 배송중 / 배송준비중 > 배송중 / 서로 다른 주문 미병합 / chunk 간 병합 / marketSpecificData 보존).
- 상태: **수정완료** — 배포 후 그리드에서 정나영 주문이 결제완료로 돌아오는지 확인 필요.

### D-127: 11번가 발송처리에 주문번호를 배송번호(dlvNo) 자리로 전달 — 송장 전송이 항상 실패 (2026-08-05)
- 심각도: **P0** (11번가 송장 전송 전면 불능) · 리스크 등급: 표준
- 발견 경로: D-126 조사 중 운영 로그에서.
  ```
  11번가 발송처리 실패: dlvNo=20260801088977098, code=-1, text=존재하지 않는 배송번호 입니다.
  11번가 발송처리 실패: dlvNo=20260801088987161, code=-1, text=존재하지 않는 배송번호 입니다.
  ```
  `dlvNo=` 뒤의 값이 **17자리 주문번호**다. 실제 배송번호는 `marketSpecificData`에 따로 저장돼 있다(주문 228 → `dlvNo: 2716448228`, 10자리).
- 근본원인: `ElevenstOrderAdapter.shipOrder`가 `String dlvNo = order.getMarketOrderNo();`. 같은 클래스의 `acceptOrders`(발주확인)는 `data.getOrDefault("dlvNo", ...)`로 **올바른 출처**를 쓰고 있었다 — 한 어댑터 안에서 같은 값의 출처가 갈린 것이 결함의 형태다.
- 파급: 11번가로 나가는 **모든 송장 전송**(수동 입력·이메일 자동 반영)이 실패했다. D-125가 로컬 기록을 보존하도록 바꿔 둔 덕분에 송장 자체는 DB에 남았으나, 마켓에는 한 건도 반영되지 않았다.
- 수정: `resolveDeliveryNo(order)` 신설 — `marketSpecificData`의 `dlvNo`를 쓰고, **없으면 주문번호로 폴백하지 않고 즉시 실패**한다. 폴백은 "존재하지 않는 배송번호" 실패를 낳을 뿐이고 그 실패가 마켓 거부처럼 보여 원인 추적을 어렵게 만든다(실제로 D-123이 그렇게 오판했다 → D-128).
- 검증: `ElevenstShipOrderDlvNoTest` 2건 PASS(배송번호 전달 / 배송번호 부재 시 fast-fail).
- 상태: **수정완료** — 배포 후 11번가 송장 전송 성공 로그로 확인 필요.

### D-128: D-123의 "존재하지 않는 배송번호" 영구거부 분류가 오분류 — 마켓 미반영을 "반영됨"으로 거짓 마킹 (2026-08-05)
- 심각도: **P1** (데이터 신뢰성 — 전송 안 된 송장이 전송됨으로 기록) · 리스크 등급: 표준
- 배경: D-123(2026-08-03)은 11번가의 `"존재하지 않는 배송번호"`를 **재시도 불가(terminal)**로 분류했다. 당시 근거는 *"해당 배송건 자체가 없다는 응답 — 같은 페이로드 재전송으로는 결코 생기지 않는다"*였다.
- 정정: 그 전제가 거짓이었다. 배송건이 없던 게 아니라 **우리가 잘못된 번호로 물었다**(D-127). 페이로드가 교정되면 재시도로 성공할 수 있다.
- 실제 피해 — 단순 오분류에 그치지 않았다. `EmailFetcherService.applyShippingResult`는 terminal일 때 재시도 루프를 끊으려고 `item.markTrackingAsSent()`를 호출한다. 즉 **마켓에 반영되지 않은 송장이 `tracking_sent_to_market=true`로 기록**됐다.
  - 라이브 확인: 주문 `20260801088977098`(주미숙)·`20260801088987161`(권준혁)이 이 경로로 종결 처리됐고 DB에는 `t`로 남아 있다. 11번가에는 반영되지 않았다.
- 수정: `isNonRetryableMarketState`에서 `"존재하지 않는 배송번호"` 판정을 제거한다. 쿠팡 상태 잠금·Cafe24 422는 실제 마켓 잠금이므로 유지.
- 검증: `MarketplaceShippingTerminalTest`의 해당 계약을 새 계약(terminal 아님 · 재시도 대상)으로 갱신, PASS.
- **미해결(사용자 판단 필요)**: 거짓 마킹된 기존 데이터의 교정. `tracking_sent_to_market`를 되돌려야 재전송 대상이 되는데, 운영 DB 쓰기이므로 승인 없이는 하지 않는다.
- 상태: **수정완료(코드)** · **데이터 교정 보류**

### 통합 검토: 전 마켓 주문상태 매퍼 축 점검 (2026-08-05)
사용자 요청("이 주문건에 한정하지 말고 통합적으로 검토")에 따라 4개 마켓 매퍼를 대조했다. 이는 D-117 사이클이 남긴 *"다른 마켓 상태 매퍼의 축 혼동 점검"* 권고의 이행이기도 하다.

| 마켓 | 상태 판정 근거 | 평가 |
|------|---------------|------|
| **11번가** | **어느 목록에서 왔는지**만으로 결정 (응답에 상태 필드 없음) | ✗ **확정 결함 → D-126** |
| 쿠팡 | **요청 시 넘긴 `status` 파라미터** — 응답의 실제 `status`를 읽고도 쓰지 않음 | △ 잠재 위험(아래) |
| 스마트스토어 | 응답의 `productOrderStatus` + 보조축 `placeOrderStatus` | ✓ D-117에서 축 분리 교정 완료. 단일 조회라 목록 중복 없음 |
| Cafe24(G마켓·옥션) | 응답의 실제 `order_status` 코드 | ✓ 축은 올바름. 폴백은 아래 |

- **쿠팡(백로그)**: `CoupangOrderAdapter.fetchOrders`는 status별로 6회 조회하며 `parseOrderNode(orderNode, status, …)`에 **요청 status**를 넘긴다. 바로 위 88행에서 `orderNode.path("status")`로 **응답의 실제 상태**를 이미 읽어 두고도(스킵 필터용) 매핑에는 쓰지 않는다. 조회 사이에 상태가 전이되면 어긋나고, 11번가와 같은 "목록 concat 후 마지막 승" 구조도 그대로다. 다만 쿠팡 API는 status로 필터하므로 대체로 일치하며 **라이브 오류 사례는 관측되지 않았다** — 그래서 이번 배치에서는 고치지 않고 등재만 한다. 교정 방향: 응답의 실제 status를 매핑에 쓰고, 주문번호 기준 병합을 적용.
- **Cafe24(관찰)**: 미매핑 `order_status` 코드의 폴백이 `NEW`다(다른 매퍼는 모두 `UNKNOWN`). 새 코드가 등장하면 **배송중 주문이 신규로 되돌아가** 재발주 위험이 있다. `UNKNOWN` 폴백이 일관되고 안전하다.
- **구조 관찰**: 이번 결함(11번가)과 D-117(스마트스토어)은 같은 모양이다 — *부가적 사실(송장 보유 / 발주확인 여부)을 주 상태로 오독*. 마켓 응답에서 "상태 축"과 "부가 축"을 구분해 읽는 규율이 매퍼 계층에 명문화돼 있지 않다.

### 미해결: 11번가 발주확인이 마켓에 반영되지 않음 (2026-08-05, 조사 필요)
- 증상: 사용자가 시스템에서 결제완료 건을 모두 발주확인했으나 11번가는 여전히 결제완료. 배송준비중(`packaging`) 목록은 매 사이클 **0건**이다.
- 모순되는 증거: `sb_action_log`의 `ORDER_CONFIRM_BATCH`는 SUCCESS로 기록돼 있고(예: 2026-08-01 07:27 "성공 1건 / 실패 0건"), `confirmOrder`는 `result_code != "0"`이면 예외를 던지므로 **11번가가 0을 반환했다**는 뜻이다.
- 즉 "API는 성공했다는데 마켓 상태는 안 바뀐다". 발주확인 요청/응답 원문을 남기는 진단 로그 없이는 판정 불가 — D-124에서 쓴 방식(관측점을 코드에 심어 배포로 확증)이 적합하다.
- D-127 수정으로 발송처리가 정상화되면 이 문제의 영향 범위가 달라질 수 있으므로, **배포 후 재관측**을 먼저 한다.

### D-129: "저장됨 · 마켓 미반영"이 화면에서 구분되지 않음 — 플래그 의미 정리 + 배지 (2026-08-05)
- 심각도: **P1** (침묵하는 실패 — 마켓 전송 전면 불능이 오래 드러나지 않은 직접 원인) · 리스크 등급: 표준
- 계기: D-127(11번가 송장 전송이 항상 실패)이 **사용자 신고가 아니라 다른 결함을 조사하다 로그에서** 발견됐다. D-125가 전송 실패에도 로컬 송장을 보존하도록 바꾼 뒤로 화면에는 송장이 멀쩡히 보였고, 마켓에 한 건도 안 들어가고 있다는 사실이 드러나지 않았다. D-125 자신이 *"화면에는 저장됨·마켓 미반영으로 구분돼 보인다"*고 적었으나 **그 구분은 실제로 구현돼 있지 않았다.**
- 1차 장애물 — 플래그가 그 질문에 답할 수 없었다. `trackingSentToMarket`은 *"우리 시스템이 전송해 성공했는가"*만 뜻해서, **마켓에서 동기화로 들어온 송장은 마켓이 명백히 보유함에도 null**이었다.
  - 라이브 실측(송장 보유 라인아이템):

    | 마켓 | 상태 | 플래그 null |
    |------|------|------------|
    | 쿠팡 | DELIVERED | **145** |
    | 스마트스토어 | DELIVERED | 14 |
    | 11번가 | SHIPPED/DELIVERED | 5 |
    | G마켓/옥션 | 각 상태 | 7 |

    이대로 배지를 달면 **배송이 끝난 쿠팡 145건이 "미반영"으로 경고**된다 — 경고가 곧 무시된다.
- 수정 1(의미 정리, 사용자 선택): 플래그의 뜻을 **"마켓이 이 송장을 갖고 있는가"**로 바꾼다. 마켓이 실송장을 알려줬다는 것은 곧 마켓이 그것을 보유한다는 뜻이므로, 동기화가 마켓 값을 채택할 때 함께 `true`로 마킹한다.
  - 규칙을 `ShippingData.marketOwnsTracking`에 한 곳으로 모으고 **네 마켓 동기화가 모두 이를 호출**한다(갱신·신규생성 경로 각각). 마켓별로 규칙이 갈리면 이 결함이 그대로 재발한다.
  - 자리표시자·빈값이면 `null`을 돌려준다 — "판단 없음"이라 기존 마킹을 되돌리지 않는다. 그때 저장된 송장은 마켓이 준 것이 아니므로 마킹하면 거짓이 된다.
  - 유일한 소비처(`EmailFetcherService`의 "동일 송장이면 스킵")와도 의미가 맞아떨어진다 — 마켓이 이미 가진 송장을 다시 보낼 이유가 없다. 송장이 다른 경우의 교정 전송은 이 플래그를 보지 않으므로 영향 없다.
- 수정 2(화면): 배송 정보 셀의 송장 아래 **`⚠ 마켓 미반영` 배지**. 판정은 `isMarketUnsynced` 한 함수에 모았다 — 송장이 있고 · 플래그가 true가 아니며 · 종결상태(취소/반품/교환)가 아닐 때.
  - **레거시 보정**: D-129 이전에 동기화된 행은 플래그가 null이므로, 마켓이 배송중/배송완료로 확인해 준 상태면 마켓 보유로 본다. 동기화가 다시 돌면 플래그가 채워지므로 **이 폴백은 시간이 지나며 자연히 무의미해진다**(데이터 마이그레이션 불필요 — 사용자 선택).
  - 검증(판정 대조): 쿠팡 DELIVERED 145건 → 미표시 ✓ / 전동명(GMARKET PREPARING+송장) → 배지 표시 ✓
  - **⚠ 정나영 건에는 배지가 뜨지 않는다 — D-128 거짓 마킹 때문.** 조사 도중(2026-08-05 02:20 동기화) 이 라인아이템의 플래그가 **NULL → `t`로 바뀌는 것을 실시간으로 관측**했다. D-128의 종결 처리가 지금도 거짓 마킹을 만들고 있다는 직접 증거다.
    - 실측: 송장을 가진 11번가 주문 **8건 전부**가 `tracking_sent_to_market = t`인데, D-127로 인해 11번가 발송처리는 한 번도 성공한 적이 없다 → **8건 모두 거짓 마킹**으로 판단된다.
    - 따라서 **데이터 교정 전에는 11번가 주문에 배지가 하나도 뜨지 않는다.** 배지는 "마킹이 없는 것"만 보기 때문이다. D-128 잔여 항목(데이터 교정)이 이 기능의 실효성을 좌우한다.
- 검증: `MarketSourcedTrackingMarksSyncedTest` 3건 + `Cafe24OrderSyncServiceTest` 신규 2건 PASS(마켓 실송장 채택 시 마킹 / 자리표시자는 미마킹). 프론트 `tsc` 무오류 + 빌드 성공.
- 상태: **수정완료** — 배포 후 배지가 실제로 정나영 건에 뜨는지 확인 필요.
- 관찰: D-128의 거짓 마킹(`true`인데 실제 미반영)은 이 배지로 **잡히지 않는다**. 배지는 "마킹이 없는 것"만 본다. 그 데이터 교정은 여전히 별도 승인 대기 항목이다.

### 배포 후 라이브 검증 (2026-08-05 04:20) — D-128 "거짓 마킹" 판단 정정
배포(이미지 `03:55:52`, 기동 `03:56:07`) 후 첫 11번가 동기화(`04:20`) 결과.

**D-126 — 검증 통과 ✓**
```
04:20:14.802 [ELEVEN_STREET] 처리 중: orderNo=20260731088778989, status=NEW
```
정나영 주문이 **한 번만** 처리되고 `NEW`로 확정됐다(이전엔 NEW·SHIPPED 2회 처리 후 SHIPPED가 이겼다). DB `shipping_status=NEW`, 송장 `424079080471`은 설계대로 보존 — "결제완료인데 송장 있음"이 정확히 표현됐다.

**D-129 — 검증 통과 ✓** 동기화가 마켓 실송장 기준으로 플래그를 채우고 있다. 송장 보유 라인아이템 기준 `true` 53건 → **125건**, `null` 170여 → **103건**. 레거시 보정 폴백이 예정대로 자연 소멸 중이다.

**D-128 — "거짓 마킹" 판단을 정정한다 ✗→△**
- 앞선 항목에서 11번가 8건의 `tracking_sent_to_market=t`를 **거짓 마킹**으로 판단하고 데이터 교정을 승인 대기로 올렸다. **이 판단은 과했다.**
- 근거: 그 8건은 모두 11번가 **배송중 목록**에서 유입된 건이고, D-126에서 확립했듯 배송중 목록에 있다는 것은 곧 **11번가가 그 송장을 보유**한다는 뜻이다. 사용자 증언과도 일치한다 — *"가짜송장을 집어넣었어… 이건 또 11번가에 전달되었어"*.
- 즉 D-129가 정리한 새 의미("마켓이 이 송장을 갖고 있는가")로 보면 **`t`는 옳은 값**이다. 잘못된 것은 값이 아니라 **그 값이 붙은 경로**(terminal 오분류로 인한 종결 마킹)였다.
- **결론: 데이터 교정 불필요.** 승인 대기 항목에서 내린다. D-128의 코드 수정(오분류 제거)은 그대로 유효하다 — 앞으로 진짜 일시 실패를 영구 종결시키지 않게 하는 값어치가 있다.
- 남는 교훈: "우연히 옳은 값"이 나오는 경로는 **값만 보고는 결함을 알 수 없다.** 이번에도 값(`t`)이 아니라 경로(로그의 "전송 종결")를 봤기 때문에 발견됐다.

**D-127 — 미검증(보류)**
배포 후 11번가 발송처리가 **한 번도 트리거되지 않아** 확인하지 못했다. 11번가 주문 전부가 `trackingSentToMarket=t`라 이메일 경로의 재시도 대상이 아니기 때문이다(위 정정에 따라 이 마킹은 정당하다). 즉 **이 결함은 자연 발생 트래픽으로는 재현되지 않는다.**
- 확인 방법: 통합 주문 관리에서 11번가 주문에 송장을 수동 입력·전송해 `존재하지 않는 배송번호`가 사라지는지 본다. 배송번호(dlvNo)가 없으면 새 메시지 `"11번가 발송처리 불가 — 배송번호(dlvNo) 없음"`이 뜬다.

**신규 관찰(별건) — G마켓 송장 전송 실패 3건**
```
마켓 배송 전송 실패: order=4473983719, market=GMARKET,
  reason=Cafe24 택배사 코드 매칭 실패: carrier=롯데택배 (몰 등록 택배사=[자체배송, 우체국택배, CJ대한통운])
```
Cafe24 몰에 **롯데택배가 등록돼 있지 않아** 매칭에 실패한다. 코드 문제가 아니라 **Cafe24 쇼핑몰 설정 문제**로 보인다(운영자가 몰 관리에서 택배사를 추가하면 해소). `order=4462952064`는 별개로 `Cafe24 API POST 호출 실패`. 백로그 등재.

**신규 관찰(별건) — 배포 로그가 2026-07-13에서 멈춤**
`~/webhook/deploy-sbshop.log`·`webhook-deploy.log`가 7월 13일 이후 갱신되지 않는다. 배포 자체는 정상 동작하나(repo pull → 이미지 빌드 → 컨테이너 재생성 전부 확인), **배포 실패 시 남는 기록이 없다.** 이번 사이클에서 반복 확인된 "침묵하는 실패"와 같은 부류.

### D-130: 전 마켓이 다품목·묶음배송 주문을 표현하지 못함 — 1단계(공통 기반) 완료 (2026-08-05)
- 심각도: **P1** (주문 데이터 유실 — 다품목 주문의 2번째 상품부터 사라짐) · 리스크 등급: 중대(도메인 모델 변경, 사용자 승인 받음)
- 발단: D-126 수정 배포 후 사용자 확인 — 11번가 `20260731088778989`(정나영)은 **상품주문 2건**(순번1 Calcium Magnesium 57,700 결제완료 / 순번2 베이직 뉴트리언트 52,800 발송완료)인데 우리 DB에는 라인아이템 1건뿐. 상품·정산액은 순번1, 송장은 순번2가 섞여 있었고 **순번2는 시스템에 존재하지 않았다**.
- 라이브 실측: **전 마켓이 주문당 라인아이템 정확히 1건**(쿠팡 191/191, 11번가 14/14, N스토어 22/22, G마켓·옥션 12/12). 쿠팡은 `orderItems.get(0)`만 쓴다.
- API 문서 조사(4마켓): 넷 다 **"주문 — 묶음/배송 — 상품주문" 3계층**을 갖는다 — 11번가 `dlvNo`(+`bndlDlvSeq`), 쿠팡 `shipmentBoxId`, N스토어 `packageNumber`, Cafe24 `shipments` 리소스. 쓰기 단위만 N스토어가 상품주문이고 나머지는 배송.
- **D-126 정정**: "배송중 목록은 송장 보유 주문을 돌려준다"는 가설은 틀렸다. 실제로는 두 목록이 각각 **다른 상품주문**을 돌려주고 있었다. D-126의 병합·등급 로직은 2단계에서 제거하고, `claimservice/orderlistall`(상품주문별 상태 직접 제공)로 대체한다.
- 설계: `docs/superpowers/specs/2026-08-05-bundle-shipment-order-model-design.md` — `Order 1:N Shipment 1:N LineItem`. 상태는 라인아이템에 둔다(같은 주문에서 상품마다 갈리므로).
- **1단계 완료(main `318ea52`)**: `Shipment` 엔티티·리포지토리, 라인아이템 마켓키 2컬럼, 3계층 DTO, 정규화기, 배송 upsert 서비스, 운영 DDL 적용. **어디에도 배선하지 않아 런타임 동작 불변.** 828테스트/실패0, 기존 테스트 수정 0건.
- 리뷰 루프가 잡은 실제 결함 3건: ① `OrderLineItemResponse` 미러 파손(엔티티 필드 추가로 JSON 계약 깨짐 — 계약 테스트가 잡음) ② `linkToShipment`가 배송의 null 송장으로 로컬 실송장 소거(**D-125 재발 부류**) ③ 정규화기가 "원본 불변"을 표방하며 가변 Map 공유.
- **2단계 착수 전 필수 3건 → 2026-08-06 전부 해소**: 레거시 행 백필 경로 부재 → **D-132** ·
  `uk_line_item_order_market_no`와 정규화기 대체값 충돌 → **D-131** · 미러 쓰기 단일 원본 미확립 → **D-133**.
- 상태: **1~4단계 완료**(11번가 D-134/136 · 쿠팡 D-137 · 골격추출 D-138 · Cafe24 D-139). 다음은 5단계(N스토어 — 주문번호 전환 이전 계획 필요, 설계 §9.2)

### D-131: 정규화기가 상품주문 식별자를 위조 — 유니크 제약과 충돌 예정 (2026-08-06)
- 심각도: P1 (동기화 전면 실패 유발) · 리스크 등급: 표준 · 상태: **검증통과**
- 발단: D-130 1단계 완료 후 2단계 착수 전 점검. `MarketOrderNormalizer`가 평면 DTO를 감쌀 때
  `marketLineItemNo`에 배송 식별자(주문번호 또는 `shipmentBoxId`)를 그대로 넣었다.
- 결함: `market_line_item_no`는 **마켓 상품주문번호**를 뜻하므로 주문번호를 넣는 것은 거짓이고,
  1단계에서 적용한 `uk_line_item_order_market_no (order_id, market_line_item_no)` 하에서
  **주문당 라인아이템이 2건이 되는 순간 유니크 위반으로 동기화가 통째로 실패**한다.
  Cafe24는 이미 `items[]` 순회로 주문당 N건을 만들고(`Cafe24OrderSyncService:171`), 쿠팡도 3단계에서 그렇게 된다.
- 수정: 상품주문 식별자를 **비운다**(null = 아직 모름). PostgreSQL은 UNIQUE 인덱스에서 NULL끼리
  충돌로 보지 않으므로 레거시·미전환 행이 공존한다. 배송 계층 폴백(`marketShipmentNo = marketOrderNo`)은
  설계 §3.3이 명시적으로 허용하고 주문당 배송 1건이라 충돌하지 않으므로 그대로 유지.
- 원칙: 모르는 값에 그럴듯한 대체값을 넣는 것이 D-127과 같은 부류의 실수다 — 폴백은 실패를 없애지 않고
  **실패의 원인을 감춘다**. 여기서는 감추는 것을 넘어 새 실패를 만들 수 있었다.
- 검증: 신규 계약 테스트 2건(수정 전 실패 확인), 전체 850 테스트 실패 0.

### D-132: 레거시 라인아이템 백필 경로 부재 — 어댑터 전환 시 중복 생성 (2026-08-06)
- 심각도: **P1** (주문 데이터 이중화 + 우리 고유 정보 고아화) · 리스크 등급: 표준 · 상태: **검증통과**
- 라이브 실측(2026-08-06): 라인아이템 **240행 전부** `market_line_item_no`가 NULL
  (쿠팡 192 · N스토어 22 · 11번가 14 · G마켓 10 · 옥션 2), `sb_shipment` 0행.
- 결함: 어댑터가 상품주문 식별자를 채우기 시작하면 이 행들은 정확키 매칭에 걸리지 않아 **같은 상품주문에
  라인아이템이 두 벌 생기고**, 소싱처·실구매가·구매상태 같은 우리 고유 정보는 아무도 보지 않는 옛 행에 남는다.
- 수정: `OrderLineItemMatcher` 신설. **정확키 → 상품 ID → 카디널리티** 순으로 매칭하고, 채택 시 키를 부여한다
  (채택이 곧 백필 — 별도 배치 불필요, 동기화가 30일 창을 매 사이클 다시 훑으므로).
  기존 행은 **최대 1회만 소비**한다 — 두 상품주문이 한 행을 함께 채택하면 D-130이 그대로 재발한다.
- 핵심 판단: 카디널리티 규칙을 "남은 것이 1:1일 때"가 아니라 **"주문 전체가 1:1일 때"**로 좁혔다.
  남은 것으로 판정하면 상품이 명백히 어긋나는 두 건을 마지막에 억지로 짝짓는다 — 상품 정보가 양쪽에
  있는데 일치하지 않으면 그것은 "모르겠다"가 아니라 "다르다"다. 현재 240행 전부가 주문 전체 1:1이다.
- 설계 §5.4-3의 "섞인 흔적" 경고 포함: 쪼개짐이 일어났고 채택한 행이 이미 송장을 갖고 있으면
  다른 상품주문의 송장일 수 있으므로 **지우지 않고** ⚠ 확인 필요로 남긴다(정나영 건의 실제 형태).
- 검증: 신규 테스트 12건(정나영 재현 포함), 전체 850 테스트 실패 0.
- 미배선: 2단계(11번가 어댑터)가 첫 소비자다. 지금 배선해도 부여할 키가 없어(D-131) 무의미하다.

### D-133: 송장 쓰기 단일 원본이 미확립 — 미러가 다른 필자의 쓰기와 갈린다 (2026-08-06)
- 심각도: **P1** (이메일 송장 자동반영 파이프라인 정지 위험) · 리스크 등급: **중대**(다도메인·라이브 경로) · 상태: **검증통과**
- 배경: 설계 §4.4는 *"쓰기는 배송이 단일 원본이고, 라인아이템 컬럼에 직접 쓰는 코드는 남기지 않는다"*고
  정했다. **그 전제가 거짓인 채로 1단계 미러링이 들어갔다** — 실제로는 이메일 파이프라인·수동 송장 입력·
  발송 처리기가 각자 라인아이템에 직접 쓰고 있었다.
- 파급(2단계 이후): 발송처리 호출 단위가 **배송**으로 바뀌므로(설계 §6.1) 배송이 모르는 송장은 마켓에
  나가지 않는다. 즉 iHerb 발송메일 → 쿠팡·11번가 송장 자동반영이라는 **지금 라이브인 파이프라인이
  조용히 멈춘다.** D-129의 "마켓 미반영" 배지도 배송 플래그를 보게 되므로 영구히 미반영으로 남는다.
- 수정: `LineItemShippingWriter` 신설 — 송장 쓰기의 단 하나의 통로. `shipment_id`가 있으면 배송에 쓰고
  라인아이템과 **같은 배송의 형제 라인아이템에 미러**한다(한 배송 = 한 송장, 설계 §3.1;
  11번가는 묶음배송번호가 같으면 한 번의 발송처리로 나머지 주문번호까지 발송된다 — `-3308` 문서).
  **진행상태는 올리지 않는다** — 같은 배송에서도 상품마다 갈린다(설계 §3.2, 정나영 건).
  배송을 못 찾으면 경고만 남기고 라인아이템 기록은 유지한다(D-125 — 미러 실패가 사실을 지울 근거가 못 된다).
- 이관한 필자: `OrderService`(수동 송장 입력·수정·전송성공 마킹 3+1지점) · `OrderShipProcessor`(1) ·
  `EmailFetcherService`(송장 교정·최초 기록·전송성공·종결 마킹 4지점).
- **남긴 필자와 그 이유**: `OrderService:120,174`는 상태 전용(NEW→PREPARING, →CANCELED)이라 라인아이템이 맞다.
  4개 마켓 동기화 서비스와 `CoupangOrderAdapter`는 각 마켓 단계(3~5)에서 자기 배송 upsert로 전환된다 —
  그 마켓 라인아이템은 해당 단계까지 `shipment_id`가 NULL이라 갈릴 수 없다.
- 동작 불변 근거: 운영 240행 전부 `shipment_id` NULL → 통로가 배송을 **조회조차 하지 않는다**.
  기존 16개 테스트 파일에 목이 아닌 **진짜 통로**를 끼워 이 사실을 회귀로 고정했다(목으로 대체하면
  라인아이템 쓰기 자체가 사라져 검증이 통과해도 아무것도 증명하지 못한다).
- 검증: 신규 테스트 8건, 전체 850 테스트 실패 0(core 534 · api 152 · worker 60 · infrastructure 104).

### D-134: 11번가 다품목·묶음배송 2단계 — 어댑터·동기화 3계층 전환 (2026-08-06)
- 심각도: **P1**(D-130 본체 해소) · 리스크 등급: **중대**(마켓 API 계약·동기화 매칭 규칙·다도메인) · 상태: **검증통과**
- 범위: 설계 §8 2단계 전부. `orderlistall` 상태조회 도입 · `ordPrdSeq` 단위 라인아이템 분리 ·
  `dlvNo` 기반 Shipment · 부분발송처리 · **D-126 병합·등급 로직 제거** · 다품목 발주확인.
- **상태 판정 구조를 바꿨다**: 종전에는 4개 목록 중 "어디서 왔는가"로 상태를 정했고 그것이 D-126의
  구조적 원인이었다. 지금은 `claimservice/orderlistall`의 `ordPrdStatNm`을 상품주문마다 직접 읽는다.
  목록의 역할은 **주문 발견과 상세 정보 수집**으로 좁아졌다. D-126의 RANK_*·`mergeInto`·`fillBlanks`는 제거.
- 계층별 출처를 분리했다(`OrderAccumulator`): 주문 공통은 아무 목록 행에서 빈 칸 채우기 · 상품주문
  로스터·상태는 orderlistall이 권위 · 상품·금액·판매자상품코드는 전체 정보 목록에서 `ordPrdSeq`로 짝지어 ·
  **송장·택배사는 배송번호(dlvNo)에** 붙인다(송장은 상품주문의 것이 아니라 배송의 것).
- 발주확인을 **모든 `ordPrdSeq`**에 대해 호출한다. 종전엔 대표 순번 하나만 불렀다 — 다품목 주문에서
  순번 2가 남으면 11번가는 그 주문을 결제완료 목록에 계속 두므로, 이것이 오래된 미해결 항목
  *"API는 성공(result_code=0)인데 배송준비중 목록은 매 사이클 0건"*의 유력한 원인이다. **라이브 확인 필요.**
- 발송처리는 상품주문 식별자를 알면 **부분발송**(`partDlvYn=Y`)을 쓴다. 전체 발송처리는 묶음배송번호가
  같은 주문번호를 모두 발송 처리하므로(`-3308` 설명), 다품목에서 준비되지 않은 상품까지 발송된 것으로
  기록될 수 있었다. 식별자를 모르는 레거시 라인아이템은 종전 경로(주문당 1건이라 결과 동일).
- **조사 중 발견한 실제 회귀 2건**(구현 중 자체 발견·수정):
  1. **배송중 목록에만 있는 주문이 통째로 사라졌다.** 이 목록은 `ordPrdSeq`를 주지 않아 로스터가 비었다.
     → 식별자 없는 라인아이템 1건으로 낸다(키를 위조하지 않으므로 D-131/D-132 매칭이 그대로 동작).
  2. **D-107 수취인 상세조회 복원이 배선에서 빠졌다.** 재작성 과정에서 호출부가 사라졌다.
     → `enrichMissingRecipients`로 복원. 어느 목록에서도 이름을 못 얻은 주문만 단건 조회한다.
- **알려진 한계(사람 확인 필요)**: 배송중 목록에만 있으면서 상품주문이 2건 이상인 주문은 양쪽에 상품
  정보가 없어(판매자상품코드 부재) 자동 매칭이 불가능하다. 이때 신규 2건이 생기고 옛 행이 고아로 남는다.
  삭제는 하지 않으며 `⚠ 확인 필요` 경고에 라인아이템 id를 함께 남긴다. 드문 조합으로 판단해 허용했다.
- 검증: 신규 테스트 32건(정나영 건 개별배송·묶음배송 양쪽, 상태 폴백, 부분발송, 분할 시 소싱정보 보존),
  전체 **879 테스트 실패 0**(core 563 · api 152 · worker 60 · infrastructure 104). 스키마·프론트 변경 없음.

### D-135: Order.marketSpecificData 인코딩이 값 안의 콤마를 조용히 잘라먹는다 (2026-08-06)
- 심각도: P2 (데이터 손상, 조용함) · 리스크 등급: 표준 · 상태: **발견**(회피만 적용)
- 발견 경로: D-134에서 `ordPrdSeqs="1,2"`를 주문에 저장했더니 읽을 때 `"1"`이 됐다.
- 원인: `Order.setMarketSpecificDataFromMap`/`getMarketSpecificDataMap`이 자체 구현 유사 JSON이고,
  읽을 때 `split(",")` → `split(":", 2)` → `replace("\"", "")`를 한다. 따라서 값에 `,` `:` `"` `}`가
  들어가면 그 값이 잘리거나 깨진다. **예외도 로그도 없다.**
- 회피: D-134는 구분자를 `|`로 바꿨다(읽을 때 `[|,]` 둘 다 허용). 근본 수정은 하지 않았다 —
  이 인코딩은 전 마켓이 공유하고(`cafe24_order_id`·`dlvNo` 등) 이미 저장된 행을 읽어야 하므로,
  포맷 교체는 별도 배치로 다뤄야 한다.
- 조치 후보: 실제 JSON 직렬화로 교체 + 기존 행 읽기 호환(현 포맷도 파싱). 값에 특수문자를 넣는
  호출부가 더 있는지 먼저 조사할 것.

### D-136: 2단계 라이브 검증에서 드러난 4건 — 전제 정정 + 빈 껍데기 행 (2026-08-06)
- 심각도: **P1**(빈 껍데기 라인아이템 + 구매정보 고아화) · 리스크 등급: 중대 · 상태: **검증통과**
- 발단: D-134 배포 후 11번가 동기화를 트리거해 관측. 정나영 건이 **2행으로 갈리긴 했지만**
  둘 다 `product_id` NULL·정산 0의 **빈 껍데기**였고, 소싱정보를 가진 기존 행(id=459)이 **고아**가 됐다.
  주문 228이 3행이 됐다(459 + 신규 472·473). `⚠ 확인 필요` 경고는 예정대로 떴다.
- 조사 방법: 추측을 멈추고 **라이브 API 응답 3개를 직접 떴다** — `claimservice/orderlistall`,
  `claimservice/orderlistalladdr`, `ordservices/shipping`. (`orderlistalladdr`는 https 전용 — http는 `-400`.)

**정정된 전제 4건**

| # | 종전 전제 | 라이브 사실 |
|---|---|---|
| 1 | 배송중 목록은 `ordPrdSeq`를 주지 않는다 | **준다.** 대신 `dlvNo`를 주지 않는다. 필드는 `ordNo`·`ordPrdSeq`·`invcNo`·`dlvEtprsCd`·`sndEndDt`뿐 |
| 2 | 정산액은 요율로 추정해야 한다 | `orderlistall`이 **`stlPlnAmt`(정산예정금액)·`selFee`(판매수수료)·`tmallApplyDscAmt`**를 상품주문별로 준다 |
| 3 | 상세조회로 판매자상품코드를 얻을 수 있다 | **어느 API도 `sellerPrdCd`를 주지 않는다.** `sellerStockCd`는 빈 값. 상품 매핑은 전체 정보 목록에서만 가능 |
| 4 | `orderlistalladdr`는 주문 단위 응답 | **상품주문마다 한 행이다.** `details.get(0)`만 읽던 `resolveClaimStatus`는 D-130과 같은 키메라 오류 |

- 수정 4건:
  1. **배송중 목록의 `ordPrdSeq` 사용** — 송장을 그 상품주문이 속한 배송에 붙인다(`trackingBySeq`).
     "안 준다"는 전제로 스스로 길을 막고 있었다.
  2. **정산액을 마켓 실측값으로** — `stlPlnAmt`를 그대로 싣고, 없을 때만 요율 추정. 주문금액도
     `stlPlnAmt + selFee + tmallApplyDscAmt`로 산출한다. **추측이 아니라 산술이며 라이브에서 검증**했다:
     49887+6253+1560 = 57700 · 45648+5712+1440 = 52800 (판매자센터 표시액과 일치). 설계 §9.1 · D-122의 근본 해법.
  3. **분할 차단 가드** — 상품을 식별할 수 없는 상품주문이 있는데 짝짓지 못한 기존 행이 남으면
     **분할을 미룬다.** 빈 껍데기 행과 고아 행을 동시에 만드는 조합을 막는다. 새로 들어오는 주문은
     결제완료 목록을 반드시 거치므로 이 가드는 **기능 배포 전에 이미 날짜 창을 지나 있던 주문**에만 걸린다.
  4. **`resolveClaimStatuses`로 교체** — `ordPrdSeq → 클레임 상태` 맵을 돌려주고 라인아이템별로 적용한다.
     종전에는 첫 행의 상태를 주문 전체에 씌워, 한 상품만 반품된 주문의 나머지 상품까지 반품으로 만들 수 있었다.
  5. (부수) **택배사 위조 제거** — `parseCarrierCode`가 코드 부재 시 CJ를 기본값으로 주는데,
     `orderlistall`은 `dlvEtprsCd`를 주지 않으므로 그대로 두면 전 주문이 CJ로 찍혔다(D-131과 같은 부류).
     코드가 없으면 null로 둔다.
- 검증: 신규 테스트 11건(라이브 응답을 픽스처로 그대로 옮김), 전체 **890 테스트 실패 0**.
- **남은 데이터 교정(승인 대기)**: 주문 228의 빈 껍데기 행 472·473을 삭제하고 459에
  `market_line_item_no='1'`을 지정해야 한다. 그러면 정확키 매칭으로 459가 순번1이 되고 순번2가
  실측 정산액과 함께 새로 생긴다. **운영 DB 쓰기라 승인 없이 하지 않는다.**
- 교훈: *"목록 행의 단위"를 잘못 알고 있었던 것이 D-126·D-130의 뿌리였는데, 2단계에서도 같은 실수를
  또 했다* — 이번엔 "배송중 목록은 최소 정보뿐"이라는 문장을 검증 없이 물려받았다.
  **마켓 API의 응답 필드는 문서나 기존 주석이 아니라 실물로 확인할 것.** 라이브 응답 한 번이
  네 가지 전제를 동시에 정정했다.

### 라이브 검증 완료 (2026-08-06 03:58) — D-134·D-136 정나영 건 정상 분할 확인

교정(`backend/docs/ddl/2026-08-06-order228-cleanup.sql`, 사용자 승인 후 실행) → 동기화 1회 → 실측:

```
 id  | seq | ship | prod | purchase_status | sourcing  | tracking_no  | status  | settlement
 459 |  1  |   6  | 312  | PURCHASED       | 344142016 | 315399495342 | SHIPPED | 47314.00
 474 |  2  |   6  | NULL | NOT_PURCHASED   |           | 315399495342 | SHIPPED | 45648.00
```

**D-130의 발단 사례가 해소됐다.** 확인된 것:
- 상품주문 2건이 **정확히 2행**으로 존재한다. 순번2(52,800원)는 D-130 조사 시점에 시스템에 아예
  없었고, 이제 구매·발주·정산 대상에 들어온다.
- 레거시 행 459가 **순번1로 채택**되고 소싱주문번호·구매상태를 그대로 유지했다(D-132 채택 경로 동작).
- 순번2의 정산액이 **45,648 — 마켓 실측값(`stlPlnAmt`)**이다. 요율 추정이 아니다(D-136 동작).
- 두 상품주문이 **같은 배송(id 6)**에 묶였고 송장을 공유한다 — `dlvNo` 2716448228이 같다(묶음배송).
- `⚠ 확인 필요` 경고가 예정대로 떴다(459가 이미 송장을 갖고 쪼개짐 — 실제로는 묶음배송이라 옳은 송장).
- 다른 13개 주문은 라인아이템이 늘지 않았다(회귀 없음).

**남은 정확도 격차 2건 (백로그)**
1. **459의 정산액이 여전히 추정값 47,314다.** 마켓 실측은 49,887(`stlPlnAmt`). 갱신 경로가
   정산액을 다시 계산하지 않기 때문이다(종전 동작 보존 — 생성 시점에만 계산). 실측값이 있을 때
   기존 행의 정산액을 실측으로 교정하는 것은 D-122의 근본 해법이지만, **이미 대조·정산한 과거
   수치를 바꾸는 일**이라 사용자 판단이 필요하다. 11번가 전 주문에 영향.
2. **474의 `product_id`가 NULL이다.** 11번가가 어느 API에서도 `sellerPrdCd`를 주지 않고, 이 주문은
   이미 배송중이라 전체 정보 목록에 다시 나오지 않는다 → 자동 매핑 경로가 없다. 화면에 상품명이
   비어 보인다. 수동 지정이나 `prdNo`(11번가 상품번호) 기반 매핑 도입이 후보.

**진단 방법에 대한 기록**: 배포 확인 중 `docker exec ... unzip`으로 jar 심볼을 검사했는데
**컨테이너에 `unzip`이 없어 모든 검사가 거짓 0을 돌려줬다.** 그것을 근거로 "D-136 미배포"라고
잘못 판단했다(실제로는 이미 배포됨). 대조군 심볼로 검사 자체를 검증하지 않은 탓이다.
**배포 확인은 서버 저장소의 커밋(`git -C <compose working_dir> log`)으로 하는 것이 확실하다** —
compose working_dir은 `docker inspect <container> --format '{{json .Config.Labels}}'`로 찾는다.

### D-137: 쿠팡 다품목·분할배송 3단계 — 어댑터·동기화 3계층 전환 (2026-08-06)
- 심각도: **P1**(다품목 주문의 2번째 상품부터 완전 유실) · 리스크 등급: **중대**(마켓 API 계약·동기화 매칭·다도메인) · 상태: **검증통과**
- **실물 확인부터 했다** — 2단계에서 전제를 검증 없이 물려받아 두 번 데었기 때문이다(D-136).
  쿠팡 HMAC 서명을 파이썬으로 재현해 `ordersheets`를 8개월 6개 상태로 훑었다(407행).

**라이브로 확정한 응답 구조**
- **행 하나 = 배송박스 하나.** 행 레벨: `orderId`·`shipmentBoxId`·`status`·`invoiceNumber`·
  `deliveryCompanyName`·`splitShipping`·`ableSplitShipping`·`receiver`·`orderer`·`overseaShippingInfoDto`.
- **상품 레벨(`orderItems[]`)**: `vendorItemId`·`sellerProductId`·`externalVendorSkuCode`·
  `vendorItemName`·`shippingCount`·`orderPrice`·`salesPrice`·`canceled`·`cancelCount`.
- **11번가와 결정적 차이 — 진행상태가 배송 레벨이다.** 11번가는 상품주문마다 상태가 갈리지만
  쿠팡은 박스 하나에 `status` 하나다. 대신 상품별 `canceled` 플래그로 **부분취소가 상품 단위**로 표현된다.
- **설계 §12-1은 실측으로 확정하지 못했다**: 407행 전부가 주문당 1행·1상품이었다 —
  **우리 상점에 다품목·분할배송 쿠팡 주문 사례가 8개월간 없다.** 그래서 단정하지 않고
  <b>두 해석 모두에서 옳게</b> 동작하도록 만들었다(같은 `orderId`의 행이 여럿이면 배송 여럿,
  한 행에 상품이 여럿이면 라인아이템 여럿).
- 수정:
  - `orderItems[]` **전량 파싱**. 종전 `orderItems.get(0)`은 2번째부터 완전 유실이었다(D-130 실측 근거).
  - `orderId`로 DTO **집계**. 행마다 DTO를 내보내면 `MarketOrderUpsertDispatcher`가 같은 주문을
    여러 번 찾아 `onExisting`을 반복 호출하고 서로 덮어쓴다 — 11번가에서 겪은 함정을 미리 막았다.
  - 상품주문 식별자를 **`배송박스:vendorItemId`**로 만들었다. 분할배송은 한 상품의 수량을 여러 박스로
    쪼갤 수 있고, 그러면 `vendorItemId`만으로는 한 주문 안에서 중복돼 `uk_line_item_order_market_no`를
    위반한다(D-131과 같은 부류의 전면 실패). 쿠팡의 실제 상품주문 단위는 (배송박스, 상품)이다.
  - `canceled` 상품만 CANCELED로. 형제 상품은 그대로 둔다.
  - 쓰기 경로 3곳이 **라인아이템이 속한 배송**에서 `shipmentBoxId`를 얻는다(`resolveShipmentBoxId`).
    `sb_order.shipment_box_id`는 주문당 하나라 분할배송을 표현하지 못한다.
  - **발주확인이 주문의 모든 배송박스**를 확인한다. 종전엔 주문 컬럼 하나만 넘겨, 분할배송이면
    나머지 박스가 미확인으로 남는다 — 11번가 D-134와 같은 형태의 결함이다.
  - `fixCarriers`가 평면 필드 대신 **배송**에서 읽는다. 라인아이템의 배송식별자로 짝짓고, 배송이
    아직 없는 레거시 행은 배송이 하나뿐일 때만 적용한다(여러 박스 중 아무거나 붙이면 오염된다).
- **구현 중 자체 발견해 고친 결함 1건**: `resolveProductId`를 상품주문당 **두 번** 호출하고 있었다
  (`Incoming` 생성 시 + 라인아이템 빌드 시). 쿠팡의 `vendorItemId` 보강은 `marketRegistrationRepository.save`
  **부수효과가 있는 경로**여서 저장이 두 번 일어났다(기존 테스트가 잡았다). 해석 결과를 한 번만 구해
  재사용하도록 고치고 11번가에도 같이 적용했다.
- **설계에서 의도적으로 벗어난 것**: 설계 §4.3/§8은 3단계에서 `sb_order.shipment_box_id`를 제거하라고
  했으나 **유지했다.** 지금 지워도 얻는 것이 없고(값을 계속 쓰는 폴백 경로가 남아 있다) 파괴적
  스키마 변경이라, 다른 미러 컬럼과 함께 6단계(정리)에서 지운다.
- 검증: 신규 테스트 16건(라이브 응답 구조를 픽스처로), 전체 **906 테스트 실패 0**
  (core 590 · api 152 · worker 60 · infrastructure 104). 스키마·프론트 변경 없음.
- 라이브 검증 한계(정직하게): 우리 데이터에 다품목·분할배송 사례가 없어 **그 경로는 실물로 확인할 수
  없다.** 단일 상품 경로(407행 전부의 형태)만 실측 확인 가능하다.

**진단 방법 기록 — 쿠팡 API 직접 조회**
`CoupangHmacUtil`과 동형의 서명을 파이썬으로 재현해 서버에서 호출했다:
`message = yyMMdd'T'HHmmss'Z' + METHOD + path + query`, HMAC-SHA256(secretKey), 헤더
`Authorization: CEA algorithm=HmacSHA256, access-key=…, signed-date=…, signature=…` + `X-Requested-By: vendorId`.
`ordersheets`는 `searchType=timeframe`으로 31일 이내만 조회되며 `maxPerPage=50`·`nextToken` 페이징이다.

### 라이브 검증 완료 (2026-08-06 04:36) — D-137 쿠팡 3단계 회귀 없음

배포(컨테이너 04:35:17, 커밋 `60c8ef2`보다 나중) → 쿠팡 동기화 1회 → 실측:

```
  market_type  | orders | items | keyed | linked        shipments = 92
 COUPANG       |    192 |   192 |    83 |     83        (11번가 9 + 쿠팡 83)
 ELEVEN_STREET |     14 |    15 |    10 |     10
 (라인아이템이 2건인 주문은 228 하나 — 11번가 정나영 건)

 id  |     market_line_item_no     | shipment_id | product_id | tracking_no  | status
 232 | 706831525380148:72408791123 |          22 |         75 | 424087182013 | DELIVERED
 233 | 706843097448465:87712479648 |          23 |       2617 | 424436769455 | DELIVERED
```

확인된 것:
- **회귀 없음 — 쿠팡 192주문 : 192라인아이템 그대로다.** 3계층 전환이 단일 상품 주문
  (8개월 407행 전부의 형태)에서 동작 불변임이 실측 확인됐다.
- 상품주문 식별자가 **`배송박스:vendorItemId`** 형태로 채워졌다. 30일 창 안의 83건이
  채택되고 배송에 연결됐다(D-132 채택 경로 동작). 나머지 109건은 창 밖이라 NULL로 남는다 — 의도된 동작.
- `product_id`가 정상 매핑됐다(75·2617·277) — `resolveProductId` 단일 호출로 바꾼 뒤에도 매칭이 유지된다.
- 송장·배송상태가 배송에서 라인아이템으로 미러됐다.
- **경고 로그가 하나도 없다**: `⚠ 분할 보류` 0 · `orderItems가 비어 있다` 0 · `⚠ 확인 필요` 0.
  즉 쿠팡 상품 매핑이 온전하고, 어느 행도 상품주문 식별자를 잃지 않았다.

**진단 방법 기록 — 배포 확인을 두 번 틀렸다**
1. `docker exec ... unzip`으로 jar 심볼 검사 → **컨테이너에 `unzip`이 없어 전부 거짓 0**(D-136에 기록).
2. `docker ps --format '{{.Status}}' | grep -E 'Up (Less than a minute|[0-9]+ (second|minute))'`
   → **"Up 33 minutes"에도 매칭**돼 옛 컨테이너를 새 배포로 오판했다.

확실한 방법: **서버 저장소 커밋 + 컨테이너 생성 시각이 커밋 시각보다 나중인지**를 함께 본다.
```
git -C /home/ubuntu/projects/sbshop-agent rev-parse --short HEAD          # = push한 커밋인가
date -d "$(docker inspect projects-sbshop-api-1 --format '{{.Created}}')" +%s   # > 커밋 epoch 인가
```
compose working_dir은 `docker inspect <container> --format '{{json .Config.Labels}}'`로 찾는다.
**대조군 없는 검사는 신뢰하지 않는다** — 검사가 항상 실패하는 것과 조건이 거짓인 것을 구분할 수 없다.

### D-138: 3계층 동기화 골격이 마켓마다 복제됨 — 공통 골격 추출 (2026-08-06)
- 심각도: P2(유지보수 — 같은 버그를 마켓 수만큼 고쳐야 함) · 리스크 등급: 표준(행위 불변 구조 변경) · 상태: **검증통과**
- 발단: 3단계 결과서의 구조 관찰. 11번가(D-134)·쿠팡(D-137)을 전환하며 `syncShipmentsAndLineItems`가
  거의 그대로 두 번 복제됐고, 차이는 **로그 태그·상품 해석·정산액 산출** 셋뿐이었다.
  Cafe24·N스토어가 남아 있어 그대로 두면 복제가 넷이 된다. 사용자가 4단계 전에 추출을 지시했다.
- 수정: `MarketLineItemSyncDispatcher`(골격) + `MarketLineItemSyncPolicy`(마켓별 3가지) 신설.
  두 서비스에서 **232줄 제거 / 52줄 추가**.
- **골격이 소유하게 된 규율** — 마켓과 무관하게 같아야 하므로 한 곳에 모았다:
  - 매칭은 주문 전체에서 한 번(배송별로 나누면 같은 기존 행을 두 배송이 채택해 중복)
  - 상품 해석은 상품주문당 한 번(쿠팡 식별자 보강처럼 부수효과 있는 경로의 중복 실행 방지)
  - 송장은 직접 쓰지 않고 배송 미러가 내려쓴다(D-133)
  - `UNKNOWN` 상태는 덮지 않는다
  - 분할 보류(D-136) · 기존 행은 지우지 않는다
- **정책에 남긴 것**: 로그 태그 · `resolveProductId`(11번가 `findBySbCode` vs 쿠팡 `market_registration`
  + 역조회·보강) · `createLineItem`(정산액 산출 — 11번가는 마켓 실측값 우선).
- 부수 개선(로그 한정): 쿠팡도 11번가의 richer `⚠ 확인 필요` 경고를 쓰게 됐다(고아 행 id 포함).
- 검증: **915 테스트 실패 0**. 리팩토링 전 906과 비교해 **기존 테스트가 하나도 바뀌지 않고 통과** —
  그것이 행위 불변의 증거다. 신규는 골격 계약 테스트 9건뿐이다.
- 앞으로: 마켓별 테스트는 **정책만** 검증하면 된다. 공통 규율의 정본은
  `MarketLineItemSyncDispatcherTest`다 — 4·5단계에서 같은 규율을 다시 테스트하지 않는다.

### 4단계(Cafe24) 착수 전 실물 확인 (2026-08-06) — 설계 §12-2 확정 + 오판 정정 1건

**Cafe24 주문 API를 읽기 전용으로 조회해 확인했다** (`younzara.cafe24api.com/api/v2`,
저장된 access_token 사용 — refresh를 유발하지 않도록 GET만).

**설계 §12-2 확정 — 배송↔상품 매핑이 응답에 명시돼 있다**

주문 `items[]` 원소가 **배송 식별자를 직접 갖는다**:
```
order_item_code  = 20260805-0000011-01        ← 상품주문 식별자
shipping_code    = D-20260805-0000011-00      ← 배송 식별자 (item에 들어 있다!)
order_status     = N20                        ← 상품별 진행상태
status_code/text = N1 / 배송준비중
tracking_no · shipping_company_code           ← 상품별 송장·택배사
custom_product_code = 220622IHB002            ← sbCode
quantity · product_price · payment_amount · claim_quantity · cancel_date
```
`shipments` 리소스도 매핑을 명시한다:
```
shipping_code = D-20260805-0000011-00
items = [{"status":"shipready","order_item_code":"20260805-0000011-01"}]
tracking_no · shipping_company_code · tracking_no_updated_date
```

- **Cafe24가 네 마켓 중 가장 깔끔하다.** 배열 인덱스 짝짓기(`applyItemShipping`)는
  **원래 필요가 없었다** — `order_item_code`가 처음부터 응답에 있었고 우리가 저장하지 않았을 뿐이다
  (기존 코드 주석도 *"sbshop 라인아이템은 order_item_code 미보존"*이라 인정하고 있었다).
- 매핑: 배송 = `shipping_code` · 상품주문 = `order_item_code`. 그룹핑에 `shipments` API 호출이
  필요하지 않다(item이 `shipping_code`를 갖는다). `Cafe24ShipmentTrackingLookup`은 D-124의
  실송장 탐색 용도로 계속 필요하다.
- 상태가 **상품 레벨**이다(11번가와 같은 형태, 쿠팡과 다름).

**오판 정정 — `Cafe24Gmarket/AuctionOrderAdapter`는 죽은 코드가 아니다**

D-137 결과서에 *"소비처 없는 죽은 코드"*라고 적었는데 **틀렸다.**
`MarketplaceShippingService`가 `List<MarketOrderPort>`를 주입받아 `getMarketType()`으로 찾으므로
Spring이 **타입으로** 해석한다 — 클래스명 직접 참조가 없어 grep에 걸리지 않았을 뿐이다.
실제로는 **G마켓·옥션의 발주확인·취소·송장 전송을 담당하는 살아 있는 쓰기 경로**다
(`acceptOrder`/`cancelOrder`/`Cafe24ShipmentService.ship`). 지우면 그 세 기능이 죽는다.

**교훈**: DI 컨테이너가 타입으로 해석하는 빈은 **클래스명 grep으로 사용처를 판정할 수 없다.**
인터페이스 구현체는 `implements <Port>` + 그 포트의 주입 지점을 함께 봐야 한다.
D-136에서 "죽은 코드가 기능 상실을 가린다"고 적었는데, 이번엔 반대 방향으로 틀렸다 —
**살아 있는 코드를 죽었다고 판정**했다. 지웠다면 기능 세 개를 잃었을 것이다.

### D-139: Cafe24(G마켓·옥션) 4단계 — 인덱스 짝짓기 제거 + 3계층 전환 (2026-08-06)
- 심각도: **P1**(엉뚱한 상품에 송장·상태가 붙는 오배정) · 리스크 등급: **중대**(동기화 매칭 규칙) · 상태: **검증통과**
- 본 과제였던 결함: `Cafe24OrderSyncService.applyItemShipping`이 `items.size() == lineItems.size()`일 때
  **배열 인덱스로 짝지었고**, 개수가 다르면 **첫 아이템 상태를 전체 라인아이템에 씌웠다.**
  마켓이 순서를 바꾸면 엉뚱한 상품에 송장·상태가 붙는다.
- **실물 확인 결과 인덱스 짝짓기는 원래 필요가 없었다**: 주문 `items[]` 원소가
  `order_item_code`(상품주문)와 `shipping_code`(배송)를 **직접** 갖는다. 기존 코드 주석도
  *"sbshop 라인아이템은 order_item_code 미보존"*이라 인정하고 있었다 — 응답에 있는 식별자를
  저장하지 않고 인덱스로 추측하고 있었던 것이다.
- 수정:
  - `Cafe24LineItemMapper` 신설 — `items[]`를 `shipping_code`로 묶어 (배송 / 상품주문) 2계층으로 변환.
    상품주문 식별자 = `order_item_code`, 배송 식별자 = `shipping_code`(없으면 주문번호로 대체).
  - 송장·택배사는 **배송**에 붙인다. 실값을 준 첫 아이템을 채택한다(D-119 — 자리표시자로 덮지 않음).
  - `Cafe24OrderSyncService`가 3계층 DTO를 만들어 **공통 골격(D-138)에 위임**한다.
    매칭·채택·배송 upsert·미러·분할 보류·경고가 전부 골격 것이므로 서비스에서 사라졌다.
  - **미매핑 `order_status` 폴백을 `NEW` → `UNKNOWN`으로 바꿨다.** 종전 폴백은 새 코드가 등장하면
    배송중 주문을 신규로 되돌린다 — 가장 나쁜 실패다. 11번가·쿠팡은 이미 정리했고 Cafe24만 남아 있었다.
    골격이 `UNKNOWN`으로 기존 상태를 덮지 않으므로 안전하게 보존된다.
  - D-124 실송장 탐색은 유지 — 단, **배송이 하나뿐일 때만** 적용한다(여러 배송 중 아무 곳에나
    붙이면 엉뚱한 송장이 들어간다).
- 제거된 죽은 코드: `applyItemShipping`·`buildLineItem`·`mapStatus`·`needsTrackingLookup`(매퍼·골격으로 이동).
- 검증: 신규 테스트 13건(라이브 응답 형태를 픽스처로, 순서 뒤집기 회귀 포함),
  전체 **927 테스트 실패 0**(core 611 · api 152 · worker 60 · infrastructure 104).
- 기존 `Cafe24OrderSyncServiceTest` 10건이 처음에 실패했다 — **그 테스트들이 검증하는 것이 곧 골격이
  하는 일**이어서 목으로는 아무것도 증명하지 못했다. 진짜 골격을 끼워 통과시켰다(D-133에서 세운 규율).

**작업 중 사고 — 정규식 수술로 파일을 손상시켰다**
`mapStatus` 등을 정규식으로 제거하려다 `createOrder`·`updateOrder`·`persistOrder`·`fetchAndPersist`가
함께 지워졌다. **컴파일은 통과했다**(호출부도 같이 사라졌으므로) — 즉 컴파일러가 잡아 주지 않는
기능 상실이었다. 파일 단위로 되돌리고 `Edit`로 정확히 다시 했다.
**교훈: 여러 메서드를 지우는 편집은 정규식으로 하지 말 것.** 중괄호 균형을 세는 스크립트도
javadoc·주석 경계에서 어긋난다. 지운 뒤에는 **핵심 메서드 존재를 명시적으로 확인**해야 한다
(`grep -c 'createOrder\|updateOrder\|persistOrder'` 같은 대조군).

### 라이브 검증 완료 (2026-08-06 05:20) — D-139 Cafe24 회귀 없음 + 신규 결함 1건 발견

배포(저장소 `2474f79`, 컨테이너 05:19:45) → Cafe24 동기화 1회 → 실측:

```
  market_type  | orders | items | keyed | linked      shipments = 102
 GMARKET       |     10 |    10 |     8 |      8      (11번가 9 + 쿠팡 83 + Cafe24 10)
 AUCTION       |      2 |     2 |     2 |      2
 (라인아이템 2건인 주문은 228 하나 — 11번가 정나영 건)

 id  |         seq         | ship | prod | tracking_no  | status
 470 | 20260805-0000011-01 |   93 | 1819 | 315399523924 | PREPARING
 454 | 20260730-0000016-01 |   96 |  325 | 315399491750 | SHIPPED
```

확인된 것:
- **회귀 없음 — G마켓 10:10, 옥션 2:2 그대로다.** 인덱스 짝짓기를 걷어내도 행이 늘지 않았다.
- 상품주문 식별자가 **`order_item_code` 그대로** 채워졌다(`20260805-0000011-01`). 30일 창 안 10건 전부
  채택되고 배송에 연결됐다.
- `product_id` 매핑 정상(1819·312·257·325), 송장·상태가 배송에서 미러됐다.
- `⚠ 분할 보류` 0 · `items가 비어 있다` 0 · `미매핑 order_status` 0.

### D-140: 미매핑 택배사 코드가 매핑 가능한 이름을 가려 택배사 유실 (2026-08-06)
- 심각도: P2(표시 정보 유실) · 리스크 등급: 경량 · 상태: **검증통과**
- 발견 경로: D-139 라이브 검증 로그 — `알 수 없는 택배사 코드: '0006' → 미매핑(null) 처리`.
- 라이브 확인: Cafe24가 `shipping_company_code='0006'`과 `shipping_company_name='CJ대한통운'`을
  **함께** 준다. 그런데 호출부가 `fromMarketCode(firstNonBlank(code, name))`로 **코드만** 넘겨,
  코드가 매핑표에 없으면 **매핑 가능한 이름이 쓰이지 못하고** 택배사가 null이 됐다.
  → G마켓·옥션 주문의 택배사가 화면에 뜨지 않았다.
- 수정: `ShippingCarrier.resolve(code, name)` 신설 — 코드를 우선하고(코드가 더 권위 있다),
  **미매핑이면 이름으로 폴백**한다. 둘 다 미매핑이면 null(ETC로 위조하지 않는다).
  호출부 2곳 교체: `Cafe24LineItemMapper` · `Cafe24ShipmentTrackingLookup`.
- 원칙: **부분 신호가 더 나은 신호를 가리지 않게 한다.** `firstNonBlank`는 "값이 있으면 쓴다"인데,
  여기서 필요한 것은 "해석되면 쓴다"였다. D-131(대체값 위조)·D-136(배송중 목록 전제)과 같은 부류 —
  <b>있는 정보를 쓰지 않고 있었다.</b>
- 검증: 신규 테스트 3건, 전체 **930 테스트 실패 0**.
- 남은 것: Cafe24 코드표(`0006` 등)를 `fromMarketCode`에 직접 넣지 않았다 — 다른 코드값을 실물로
  확인하지 못했고, 이름 폴백으로 실효가 확보됐다. 코드표를 확보하면 코드 매핑을 채우는 편이 낫다
  (이름 표기는 흔들릴 수 있다).

### D-141: N스토어 5단계 — 주문 키 전환(productOrderId → orderId) + 3계층 전환 (2026-08-06)
- 심각도: P1(주문 1건이 DB에서 여러 행) · 리스크 등급: **중대**(마켓 API 계약 + 주문 키 이전) · 상태: **검증통과**
- 선행 실측(라이브 22건 전수, `product-orders/query`): `order.orderId` 22/22 · `packageNumber` 22/22 ·
  `expectedSettlementAmount` 22/22 · **orderId 공유 0건** · 새 키↔기존 `market_order_no` 충돌 0 ·
  마켓 주문번호를 문자열로 참조하는 다른 테이블 없음.
  → 이전은 **행 병합이 아니라 키 재작성**으로 확정됐다(별건 계획 불필요, 5단계에 흡수).
- 결함: 네이버 응답은 **원소 하나가 상품주문 1건**이고 각자 `order` 노드를 갖는다. 우리는 그 원소를
  그대로 주문 1건으로 냈고 `productOrderId`를 주문번호로 썼다 — 한 주문에 상품주문이 2건이면
  **주문 하나가 우리 DB에서 두 행**이 된다(설계 §9.2). `orderId`는 읽지도 않고 있었다.
- 수정:
  - 어댑터가 `orderId`로 묶어 3계층 DTO를 낸다. 배송은 `packageNumber`로 가르고(없으면 상품주문번호
    폴백), 상품주문 키는 `productOrderId`, 정산액은 `expectedSettlementAmount` 실측값.
  - **청크 경계를 넘어 누적한다.** 30일 창을 1일 단위로 훑으므로 같은 주문의 상품주문이 다른 날
    바뀌면 다른 청크에 온다. 같은 상품주문이 두 청크에 나오면 **나중 것이 이긴다**(최신이 진실) —
    그대로 두면 라인아이템 키가 중복돼 `uk_line_item_order_market_no` 위반으로 동기화가 통째로 실패한다.
  - 동기화는 공통 골격(D-138)에 위임. 기존/신규 판정만 공통 헬퍼에서 뺐다 — **새 키로 못 찾으면
    옛 키(상품주문번호)로 찾아 키를 갈아 끼우는** 마켓 고유 규칙이 생겼기 때문이다(`Order.rekeyMarketOrderNo`).
    **이전이 곧 동기화다** — 배포 시점과 데이터 이전 시점이 어긋나도 중복 행이 생기지 않는다.
  - 발송처리·발주확인·취소는 전부 **상품주문 단위**다. 주문 키가 `orderId`가 됐으므로 라인아이템의
    `market_line_item_no`(발송) / 주문의 `productOrderIds`(발주확인·취소)를 쓴다. 특정 불가 시
    주문번호로 추측하지 않고 **즉시 실패**한다(D-127에서 배운 것 — 잘못된 식별자의 마켓 거부는
    마켓 상태 잠금처럼 보인다). 전환 전 저장분만 주문번호로 폴백한다(그때는 그 값이 상품주문번호였다).
- 검증: 신규 테스트 23건(어댑터 그룹핑 8 · 발송키 7 · 3계층 동기화 8), 전체 **953 테스트 실패 0**.
- 설계 판단 하나 정정: 처음에는 "재키잉 선(先)패스 + 공통 헬퍼 재조회"로 짰는데, 테스트가
  **재조회가 방금 바꾼 키를 못 보는 경우**를 잡았다. 조회 자체를 마켓 규칙으로 끌어올려 해결했다.

### 라이브 검증 완료 (2026-08-06 07:15Z) — D-141 회귀 없음
배포(저장소 `c5ae62b`, 컨테이너 07:09:39Z, 기동 07:09:52Z) → 동기화 1회 → 실측:
```
 market_type | orders | items | keyed | linked      shipments = 11
 SMART_STORE |     22 |    22 |    11 |     11
```
- **11건이 `주문 키 이전: <옛> → <새>`로 남고 전부 기존 행을 찾았다** — 신규 생성 0, 주문 수 22 그대로.
- 라인아이템에 상품주문번호가 붙고 배송(`packageNumber`)에 연결됐다. 송장은 배송에서 미러.
- 경고 0건(`⚠ 분할 보류`·`확인 필요`·파싱 실패 전부 0).
- 남은 것: 30일 창 밖 11건(5~6월)은 마켓이 변경으로 내려주지 않아 동기화가 만나지 못한다 →
  서버 `/tmp/ss_migrate.sql` 수동 실행 필요(미실행이어도 폴백 경로로 동작에 지장 없음).
- 기존 행 정산액은 추정값 유지(실측값은 신규 생성 경로에만) — 11번가와 같은 백로그.

### D-142: 배송에 속하지 않은 라인아이템 127건 — 레거시 배송 백필 (2026-08-07)
- 심각도: P2(6단계 전제 미충족) · 리스크 등급: 표준 · 상태: **검증통과**
- 문제: 3계층 전환은 각 마켓의 조회 창(30일) 안에서만 일어난다. **창 밖의 옛 주문은 마켓이 더는
  변경으로 내려주지 않아 동기화가 영원히 만나지 못한다** — 그 라인아이템은 `shipment_id`가 비어 있다.
  실측 127건(쿠팡 109 · N스토어 11 · 11번가 5 · G마켓 2). 전부 "주문 1건 = 미연결 라인아이템 1건"이고
  다품목 0 · 송장 충돌 0이라 백필이 단순하다.
- 왜 6단계의 전제인가: 미러 컬럼과 `sb_order.shipment_box_id`를 제거하려면 **모든 라인아이템이 배송을
  가져야** 한다. 그 전에 제거하면 옛 행의 송장이 갈 곳을 잃고, 쿠팡 송장 수정은 박스 식별자를 잃는다.
- 수정: `LegacyShipmentBackfillService`(멱등) + 내부 트리거 `/internal/backfill/legacy-shipments`.
  배송 식별자는 쿠팡 `shipment_box_id`, 없으면 주문번호(정규화기와 같은 규칙, 설계 §3.3).
  **라인아이템의 송장·택배사·마켓보유플래그를 배송으로 승격**한다 — 원본이 뒤집히는 지점이다.
  미러 컬럼은 그대로 둔다(소비처 이관 전).
- 검증: 신규 테스트 5건.

### D-143: `sb_order.shipment_box_id` 제거 — 배송박스번호의 두 번째 원본 제거 (2026-08-07)
- 심각도: P2(중복 원본) · 리스크 등급: 표준 · 상태: **검증통과**
- 배경: 3단계(쿠팡)에서 "의도적으로 유보"한 정리다. 배송박스번호는 배송(`sb_shipment.market_shipment_no`)이
  갖는데 주문에도 같은 값이 남아 있었다. **주문당 한 개뿐이라 분할배송을 표현하지 못한다** —
  "대표 박스"가 나머지 박스를 가린다.
- 전제: D-142 백필로 미연결 라인아이템 0건이 된 뒤에만 안전하다(라이브 확인 후 착수).
- 수정:
  - `Order.shipmentBoxId` 필드 제거 → `Order.update(...)`가 9인자에서 8인자로. 네 마켓 동기화가
    모두 이 메서드를 거치므로 호출부 4곳을 함께 수정했다(PII 가드의 정본 지점이라 시그니처만 바뀜).
  - `CoupangOrderAdapter`: 발송·송장수정·발주확인의 **주문 컬럼 폴백 제거**. 배송을 못 찾으면
    추측하지 않고 즉시 실패한다("주문 동기화로 배송을 먼저 확보해야 합니다").
  - `MarketOrderDto.shipmentBoxId`·정규화기의 박스 분기·`OrderResponse.shipmentBoxId` 제거.
    프론트는 이 필드를 쓰지 않는다(사용처 0 확인).
  - `LegacyShipmentBackfillService`: 쿠팡은 이제 **배송 식별자를 지어내지 않고 건너뛴다**.
    박스번호는 마켓 API가 요구하는 실제 값이라 주문번호로 대체하면 거부가 상태 잠금처럼 보인다(D-127).
- DB: `ddl-auto=update`라 컬럼은 DB에 고아로 남는다(기동 영향 없음). `ALTER TABLE sb_order DROP
  COLUMN shipment_box_id`는 선택적 정리로 남긴다.
- 검증: 전체 **958 테스트 실패 0**.

### 라이브 검증 완료 (2026-08-07 10:33Z) — D-142·D-143 회귀 없음
- D-142 백필 실행: `{"unlinked":127,"created":127,"linked":127,"skipped":0}` → **전 마켓 미연결 0건**,
  배송 242건(송장 보유 227). 쿠팡 배송 식별자는 당시 `shipment_box_id` 값 그대로.
- D-143 배포(`a7ec6ee`, 기동 10:32:34Z) 후 쿠팡 동기화 1회: **78건 처리, 오류·경고 0**.
  라인아이템 193:193 연결 유지, 배송 242건 그대로(중복 생성 없음) — 주문 컬럼 폴백을 떼도 회귀 없음.

### D-141 후속: 창 밖 11건 키 이전 완료 (2026-08-07, 사용자 실행)
`/tmp/ss_migrate.sql` 실행 — 이미 동기화가 이전한 11건은 안전장치가 대상에서 제외(`DELETE 11`)하고
나머지 11건만 이전(`UPDATE 11` ×3: 라인아이템 키 · `productOrderIds` · 주문 키).
최종 상태: **N스토어 주문 22건 전부 `orderId` 키 · `productOrderIds` 보유 22/22 ·
라인아이템 22건 전부 상품주문번호 부여·배송 연결.** 22건이 한 규칙으로 정렬됐다.

### D-144: 마켓발 송장이 이메일 큐 게이트를 닫아 진짜 송장이 영영 반영되지 않음 (2026-08-07)
- 심각도: **P1**(송장 오기재 — 고객에게 남의 송장이 안내됨) · 리스크 등급: 표준 · 상태: **발견**
- 발견 경로: 사용자 신고 — 쿠팡 `23102043923068`(홍경희)에 8/6 02:27 발송메일이 왔는데 송장 미반영.
- 위치: `OrderLineItemRepositoryImpl.shipmentEmailNeeded()` (배송 큐 조건) ·
  `EmailFetcherService.fetchAndProcess()` 1단계
- 증상·실측:
  - 이 주문의 DB 송장은 `363092185283`인데, **중앙 메일함 전체에 그 번호가 없다**(SUBJECT·BODY 0건).
  - 같은 번호가 **무관한 스토어 주문**(`2026073124339271`, 안규걸, iHerb 344143953)에도 붙어 있다.
    두 건은 고객·iHerb 계정·상품이 전부 다르다 → 실제 송장일 수 없다(마켓 쪽 가송장으로 추정).
  - 진짜 송장은 메일에 있다: iHerb `344163905` 발송메일(8/5 17:27Z = 8/6 02:27 KST) → **`315399527822`**.
  - 이메일 파이프라인 자체는 정상이다 — 같은 스케줄러가 8/7에도 다른 건들을 정상 처리했다.
- 원인(확인): **큐 게이트가 "마켓이 송장을 갖고 있다"를 "우리가 진짜 송장을 확보했다"로 착각한다.**
  1. 쿠팡 동기화가 마켓에서 `363092185283`을 받아옴 → D-129 규칙대로 `trackingSentToMarket=true`.
  2. 배송 큐 조건은 `PREPARING ∪ (DISPATCHED|SHIPPED ∧ trackingSentToMarket ≠ true)`.
     이 건은 `DISPATCHED ∧ true` → **큐에서 제외**.
  3. 큐에 없으면 그 주문번호로 메일을 **검색조차 하지 않는다**(검색은 주문번호 기반 SubjectTerm).
  4. `processIherbShipment`에는 "송장이 다르면 교정 후 마켓 수정 반영" 분기가 이미 있다 —
     **큐에만 들어왔으면 자동 교정됐을 것이다.** 스킵 지점은 처리 로직이 아니라 큐다.
- 범위(실측, 2026-08-07): 큐에서 빠진 활성 건(DISPATCHED/SHIPPED ∧ sent=true) 16건 중
  **DB 송장이 그 주문의 발송메일에 없는 건 12건**(일치 3 · 발송메일 없음 1).
  종결(DELIVERED) 190건은 상태로 이미 제외되므로 별도 판단 필요.
- 재현: 위 대조 스크립트(`/tmp/mail_compare.py`, 읽기 전용) — DB 송장 vs 발송메일 송장 대조.
- 수정: 큐 조건에서 `trackingSentToMarket`를 **게이트에서 제거**했다. iHerb 주문번호가 있고 종결 전
  (PREPARING·DISPATCHED·SHIPPED)이면 상태·동기화 여부와 무관하게 큐에 넣는다. 동일 송장이면 기존
  분기가 스킵하므로 재처리 비용이 없고, 검색 비용은 활성 20건 규모라 무시할 만하다.
  종결(DELIVERED 등)은 계속 제외 — 마켓이 받아주지 않고 과거 기록을 바꾸는 일이라 사람 판단이 필요하다.
  (대안이던 "송장 출처 컬럼 신설"은 스키마 변경이라 채택하지 않았다.)
- 검증: 신규 테스트 2건(회귀 정본: 큐 조건이 `trackingSentToMarket`을 참조하지 않는다),
  전체 **960 테스트 실패 0**.

### 라이브 검증 (2026-08-07 11:33Z) — D-144 수정 후 이메일 수집 1회
배포(`478f889`, 기동 11:32:38Z) → `/internal/email/fetch` → **큐가 열려 12건이 교정됐다**.
사용자 지목 건: `iHerb 344163905 송장 변경 감지(기존=363092185283 → 신규=315399527822, 상태=DISPATCHED)`.

**마켓 반영 결과 — 마켓마다 갈렸다(이 추적에서 신규 결함 3건이 드러났다):**

| 마켓 | 건수 | 마켓 반영 | 근거 |
|------|-----|----------|------|
| 쿠팡 | 1 | **성공** | 전송 후 재동기화에도 `315399527822` 유지 = 마켓이 새 송장 보유 |
| N스토어 | 5 | **실패(거짓 성공)** | 응답에 `failProductOrderInfos`, 그러나 성공 처리 → 11:38 동기화가 전부 원복 |
| 11번가 | 6 | 거부 | `해당 배송번호의 주문상태가 이미 변경 되었습니다. 변경된 상태 : 배송중` — 11:50 동기화가 전부 원복 |
| G마켓 | 5 | 실패 | `Cafe24 택배사 코드 매칭 실패: carrier=롯데택배 (몰 등록=[자체배송, 우체국택배, CJ대한통운])` |

**실질 반영은 12건 중 1건(쿠팡)뿐이다.** 마켓 반영에 실패한 교정은 다음 동기화가 마켓 값으로 되돌린다
(N스토어 11:38 · 11번가 11:50 확인) — **마켓 반영에 성공하지 못하면 우리 DB의 교정도 남지 않는다.**
동기화의 "마켓이 진실 원본" 규율과 이메일의 "iHerb 송장이 진실" 규율이 충돌하는 지점이다.

### D-145: 스마트스토어 송장 전송이 실패해도 성공으로 기록된다 (거짓 성공) (2026-08-07)
- 심각도: **P1**(마켓 미반영을 반영됨으로 기록 — 상태 오염) · 리스크 등급: 표준 · 상태: **발견**
- 위치: `SmartStoreOrderApiClient.shipOrder` — 최상위 `code`만 검사한다.
- 증상(라이브 응답): HTTP 200 본문이
  `{"data":{"successProductOrderIds":[],"failProductOrderInfos":[{"productOrderId":"...","code":"104119","message":"택배사코드 확인"}]}}`
  인데 예외 없이 통과 → `마켓 배송 전송 완료` 로그 + `trackingSentToMarket=true` 마킹.
- 결과: D-144 교정 5건이 마켓에 반영되지 않은 채 성공 처리됐고, **11:38 동기화가 마켓 값으로 전부 원복**했다.
  교정 → 거짓 성공 → 원복이 매 사이클 반복되는 왕복이 된다.
- 드러난 하위 원인 2가지(둘 다 실물로 확정):
  ① `104119 택배사코드 확인` — **네이버의 롯데 코드는 `HYUNDAI`다.** 우리는 `LOTTE`를 보내고 있었다.
     근거: 네이버가 우리 주문에 돌려주는 `delivery.deliveryCompany` 실측 — 롯데 송장(3153·3159로 시작) 2건이
     `HYUNDAI`로 등록돼 있다(분포: CJGLS 14 · EPOST 4 · HYUNDAI 2, `LOTTE`는 없음). 쿠팡과 같은 관례(D-E5).
  ② `9999 주문상태 및 클레임상태를 확인하세요` — **배송중 주문은 `dispatch`로 송장을 바꿀 수 없다.**
     - 라이브 직접 시험: 배송중 주문 `2026073137353041`에 <b>올바른 코드(HYUNDAI)+진짜 송장</b>으로 재호출 →
       여전히 `9999`, 마켓 값 불변(CJGLS/363092185283 그대로). 즉 택배사 코드와 상태 거부는 별개 문제다.
     - 나머지 4건은 애초에 유효 코드(CJGLS)로 보냈는데도 전부 `9999`였다.
     - 게이트웨이 경로 탐색: `dispatch`만 존재하고 `shipping-invoice`·`tracking-number`·`dispatch/modify`·
       `{id}/dispatch`·`v2/dispatch` 등은 전부 `GW.NOT_FOUND`.
     - **커머스API 공식 답변 2건**(저장소 메인테이너): "송장 정보를 수정하는 기능을 제공하고 있지 않습니다.
       이미 발송처리한 주문건을 다시 발송처리 API 하시면 오류를 반환합니다"(#3192) ·
       "송장번호 수정 기능은 커머스API로 제공하지 않으며 가까운 시일 내 제공 계획이 없습니다"(#2450).
     - **수동 경로**: 스마트스토어센터 > 판매관리 > 발주(주문)확인/발송관리 에서 상품주문번호별로 송장을 입력하고
       하단 [송장수정] 버튼으로 여러 건 일괄 수정(공식 안내, FAQ 3756).
- 수정(2026-08-07):
  ① **거짓 성공 제거** — `SmartStoreDispatchResult.verifyAccepted`가 `data.failProductOrderInfos`를
     실패로 판정한다. 모르는 형태는 실패로 위조하지 않는다(판정 근거가 없으면 통과).
  ② **택배사 코드 교정** — `LOTTE_LOGISTICS`·`HYUNDAI_LOGISTICS` → `"HYUNDAI"`. 아울러
     `default -> "CJGLS"` 폴백을 제거하고 매핑 불가 시 즉시 실패시킨다 — 모르는 택배사를 CJ로
     위조해 보내면 마켓에 엉뚱한 택배사가 등록되고 고객은 조회되지 않는 송장을 받는다(D-140과 같은 부류).
  ③ **`9999`를 영구 거부로 분류** — `isNonRetryableMarketState`에 추가해 30분 재시도 루프를 끊는다.
     `104119`(택배사코드)는 넣지 않는다 — 우리 요청 오류라 고치면 재시도로 성공한다(D-128과 같은 구분).
  ③의 "송장 수정 API 신설"은 **불가능으로 판명**됐다(위 근거) — 사람의 수동 수정으로 넘긴다.
- 검증: 신규 테스트 9건(응답 판정 5 · 택배사 코드 2 · 영구 거부 분류 2), 전체 **969 테스트 실패 0**.

### D-146: 11번가 "이미 배송중" 거부가 일시 실패로 분류돼 무한 재시도 (2026-08-07)
- 심각도: P2 · 리스크 등급: 경량 · 상태: **검증통과** (2026-08-08 라이브 확인)
- 0단계 결론(코드 수정 전 필수 확인): **11번가에 송장 수정 API는 없다** — 3중 확증으로 확정.
  ① 게이트웨이가 키 없이 등록 경로를 알려준다(`-100` 등록 / `-997` 미등록) → 12개 서비스 그룹 ×
  1,150여 경로 패턴 전수 조회, 수정 계열 **0건**.
  ② 외부 연동 5개 저장소(PHP·Ruby·Python·Laravel)가 쓰는 11번가 경로 25개에도 없음.
  ③ 라이브: 발송처리 재호출은 5·8-파라미터 모두 `-3313`. `reqdelivery`에 미지의 **9-파라미터 변형**이
  등록돼 있었으나 6번 자리가 **"추가 송장번호"(분할발송용)** — 대체가 아니라 덧붙이기라 수정 경로가 아니다.
  라이브 시험 중 마켓 데이터 변경 없음(li231 `invcNo` 363082001112·상태 401 그대로, 읽기 조회로 확인).
- 수정: `isNonRetryableMarketState`에 `"주문상태가 이미 변경 되었습니다"` 추가(상태 값 비의존).
  D-128 구분 유지 — `"존재하지 않는 배송번호"`는 우리 요청 오류라 재시도 대상으로 남긴다(회귀 테스트 존치).
- 테스트: `MarketplaceShippingTerminalTest` 3건 추가(배송중·구매확정·원인 체인 래핑).
- 위치: `MarketplaceShippingService.isNonRetryableMarketState` — 11번가 문구가 목록에 없다.
- 증상: `해당 배송번호의 주문상태가 이미 변경 되었습니다. 변경된 상태 : 배송중`(6건)이 일시 실패로 분류돼
  미마킹 → 30분마다 같은 요청이 반복된다. D-123에서 쿠팡·Cafe24 문구만 넣고 이 문구는 빠져 있었다.
- 주의: D-128 정정과 혼동하지 말 것 — "존재하지 않는 배송번호"는 우리 요청 오류(재시도 가치 있음)지만,
  이 문구는 마켓 상태 잠금(재시도 무의미)이다.

### D-147: 마켓 전송 실패 시 `trackingSentToMarket`이 true로 남아 미반영 배지가 뜨지 않는다 (2026-08-07)
- 심각도: P2(표시 오류 — 사람이 미반영을 못 본다) · 리스크 등급: 경량 · 상태: **발견**
- 위치: `EmailFetcherService.handleMarketResult` 일시 실패 분기 — 미마킹만 하고 **기존 true를 내리지 않는다.**
- 증상: 마켓발 송장으로 이미 true였던 건을 이메일이 교정하고 전송에 실패하면, 플래그는 true로 남는다.
  화면의 `⚠ 마켓 미반영` 배지(D-129)가 뜨지 않아 **사람이 미반영 상태를 볼 수 없다.**
- 수정(2026-08-08) — 플래그를 고치는 대신 **플래그를 믿지 않게** 했다:
  - `LineItemShippingWriter.marketHasTracking(item, tracking)` — "마켓이 이 송장을 정말 갖고 있는가"를
    배송의 `market_tracking_no`로 판정한다. 마켓 보유값을 아직 모르면(동기화 전) 종전 플래그로 폴백해
    방금 보낸 송장을 매 사이클 다시 보내지 않는다.
  - `EmailFetcherService`의 "이미 동기화됨" 판정을 이 메서드로 교체. 라이브에서 이 판정이 거짓 성공이
    남긴 플래그를 믿고 **전송을 아예 시도하지 않아**, 영구 거부를 만나지 못하고 화면이 "곧 자동 반영됨"
    이라고 잘못 안내했다(2026-08-07 실측: 스토어 5건 발송 API 호출 0회).
  - `isAwaitingManualFix` — 사람이 고치기를 기다리는 중이면 재전송하지 않는다. 30분마다 같은 거부를
    받아낼 뿐이다. 사람이 고치면 동기화가 표시를 스스로 끈다(D-148).
  - 화면 배지는 이미 값 비교로 판정하므로(D-149) 플래그 자체는 손대지 않았다.
- 검증: 신규 테스트 4건, 전체 **978 테스트 실패 0**.

### D-148: 마켓의 가송장이 우리가 아는 실제 송장을 덮어 교정이 매 동기화마다 원복됨 (2026-08-07)
- 심각도: **P1**(잘못된 송장을 고객에게 안내) · 리스크 등급: 표준 · 상태: **검증통과(배포 대기)**
- 위치: `OrderShipmentUpsertService.upsertShipment` · `Shipment`
- 증상(라이브 실측): 이메일이 진짜 송장으로 교정(11:34) → 스토어 동기화가 마켓의 가송장으로 원복(11:38)
  → 11번가도 원복(11:50). **12건 교정 중 마켓 반영에 성공한 1건(쿠팡)만 살아남았다.**
- 왜 P1인가: 되돌아가면 우리 값 = 마켓 값이 되어 `⚠ 마켓 미반영` 배지도 꺼진다. **고쳐야 할 일이
  있다는 사실이 화면에서 사라지고**, 화면·엑셀·고객 응대가 가송장을 진짜처럼 안내한다.
- 규율: **송장의 진실은 발송처(iHerb 발송메일)다. 마켓 값은 "마켓이 알고 있는 값"일 뿐이다.**
  마켓 값도 버리지 않는다 — 그래야 배지가 정확해지고 스스로 꺼진다.
- 수정:
  - `sb_shipment.market_tracking_no` 신설 — 마켓이 아는 값을 매 동기화 기록. 실제 송장과의
    **불일치가 곧 "마켓 미반영"**이다(`trackingSentToMarket` 플래그에 의존하지 않는다 — D-147).
  - `sb_shipment.manual_fix_required` 신설 — 마켓이 영구 거부하면 세운다. 사람이 판매자센터에서
    고쳐 마켓 값이 우리 송장을 따라오면 `applyMarketTracking`이 **스스로 끈다**(완료 체크 버튼 불필요).
  - 동기화는 **우리가 송장을 모를 때만** 마켓 값을 채택한다(마켓이 유일한 출처인 정상 경로는 그대로).
  - 택배사도 함께 지킨다 — 송장과 택배사가 어긋나면 조회 자체가 안 된다.
- 스키마: **`ddl-auto=update`는 이 DB에서 동작하지 않는다** — 앱 역할(`sbshop`)이 테이블 소유자
  (`canagent`)가 아니라 `ERROR: must be owner of table sb_shipment`로 거부된다. Hibernate는 이 실패를
  **WARN으로만 남기고 기동을 계속**하므로, 앱은 정상 기동한 채 엔티티에만 있는 컬럼을 SELECT해
  **주문 조회가 전부 500**이 됐다(2026-08-07 라이브 장애 4분). 소유자 역할로 직접 적용해 복구:
  ```sql
  ALTER TABLE sb_shipment ADD COLUMN IF NOT EXISTS market_tracking_no varchar(100);
  ALTER TABLE sb_shipment ADD COLUMN IF NOT EXISTS manual_fix_required boolean;
  ```
  **규율: 엔티티에 컬럼을 더하는 배포는 운영 DDL을 먼저 적용하고 배포한다.** 그리고 스키마가 바뀐
  배포의 확인은 기동 로그로 끝내지 않고 **실제 조회 1회까지** 해야 한다 — 기동 성공이 곧 정상은 아니다.
- 검증: 신규 테스트 5건(원복 회귀 · 마켓 유일 출처 채택 · 자동 해소 · 유지 · 자리표시자 무시),
  전체 **974 테스트 실패 0**.

### D-149: 마켓 반영 상태를 화면이 구분하지 못함 — 배지 3종 + 필터 (2026-08-08)
- 심각도: P2(사람이 조치할 건을 볼 수 없음) · 리스크 등급: 표준 · 상태: **검증통과(배포 대기)**
- 문제: 배지가 `⚠ 마켓 미반영` 하나뿐이라 **기다리면 되는 건**(자동 재시도)과 **사람이 마켓에서
  직접 고쳐야 하는 건**(영구 거부)이 같은 모습으로 보였다. 후자는 방치하면 영원히 반영되지 않는다.
- 수정:
  - `OrderDetailDto.OrderLineItemDetailDto.shipment` 신설 — 그리드 응답이 배송 계층을 싣는다
    (`shipmentId` 모아 한 번에 조회, N+1 없음). 6단계 미러 컬럼 제거의 전제이기도 하다.
  - 판정을 `marketSyncState(lineItem, shipment)`로 이관: **두 송장 값의 비교**가 근거다.
    `trackingSentToMarket` 플래그는 전송 실패에도 참으로 남아 미반영을 가린다(D-147) — 폴백으로만 쓴다.
  - 배지 3종: `🔒 마켓 수동수정`(붉은색) · `⚠ 마켓 전송 대기`(앰버) · `✓ 마켓 반영됨`(초록).
    **전송 버튼과 한 줄**에 둬 셀 높이를 늘리지 않는다. 수동수정일 때만 마켓 보유값을 회색 취소선
    한 줄로 덧붙인다 — 입력칸이 "바꿀 값", 그 줄이 "지금 마켓에 잘못 들어가 있는 값".
  - 상단 필터 칩 `마켓 수동수정 필요 N` · `마켓 전송 대기 N` — 라인아이템 단위로 걸러 표가 깨지지 않는다.
  - 복사·딥링크 버튼과 완료 체크는 두지 않는다(2026-08-07 사용자 판정). 완료는 마켓이 따라오면
    `applyMarketTracking`이 스스로 끈다(D-148).
- 검증: 백엔드 **974 테스트 실패 0**(강제 전량 재실행) · 프론트 `tsc -p tsconfig.app.json` 통과 ·
  `npm run build` 통과 · `npm run lint` 지표 변경 전과 동일(기존 문제만).

### D-150: 예외 래핑이 실패 사유를 지워 영구 거부 분류가 무력화됨 (2026-08-08)
- 심각도: P2(무한 재시도 + 수동조치 표시 누락) · 리스크 등급: 경량 · 상태: **검증통과**
- 발견 경로: D-147 배포 후 라이브 확인 — 스토어 5건이 전송을 시도했고 마켓이 `9999`로 거부했는데
  `manual_fix_required`가 서지 않았다. 로그의 실패 사유가 `스마트스토어 주문 발송(shipOrder) 실패`뿐이었다.
- 원인: `SmartStoreOrderApiClient.shipOrder`의 catch가 예외를 래핑하며 **원인 메시지를 버렸다.**
  `MarketplaceShippingService.isNonRetryableMarketState`는 최상위 메시지만 검사하므로 문구를 찾지 못했다.
- 수정: ① 클라이언트가 사유를 메시지에 남긴다 ② 분류기가 `RootCauseExtractor.rootMessage`로
  **원인 체인까지** 검사한다 — 어느 마켓이든 래핑에 사유가 가려지는 같은 함정을 함께 막는다.
- 교훈: D-139에서 같은 형태를 겪었다(Cafe24 동기화 실패 사유 은폐 → `failureReason`으로 결합).
  **예외를 래핑할 때 원인 메시지를 버리지 말 것** — 상위에서 문구로 판정하는 코드가 있으면 조용히 무력화된다.
- 검증: 신규 테스트 1건, 전체 **979 테스트 실패 0**.

### D-151: G마켓/옥션 송장 역전송이 항상 신규 등록(POST)이라 이미 배송중인 주문에서 422 (2026-08-08)
- 심각도: P1 · 리스크 등급: **중대**(마켓 API 계약 변경 — 호출 동사가 POST→PUT으로 바뀐다)
  · 상태: **검증통과** (2026-08-08 라이브)
- 수정: `Cafe24ShipmentService.ship`이 먼저 `fetchShipments`로 기존 배송건을 확인해
  있으면 `updateShipment`(PUT), 없으면 종전 `registerShipment`(POST). 여러 건이면 추측 없이 실패.
  `status`는 싣지 않는다(이미 배송중인 주문에 상태 재전송은 같은 422를 부른다).
  포트에 `updateShipment(orderId, shippingCode, body)` 신설 — 어댑터는 무변경
  (`updateTracking` 기본 폴백이 `ship()`을 타므로 전송·교정 두 경로가 함께 고쳐진다).
- **배포 직후 교정(같은 날)**: "배송건이 있으면 수정"은 너무 넓었다 — Cafe24는 **발송 전에도 배송건
  `D-...-00`을 미리 만들어 둔다**(라이브 확인: 아직 우리가 송장을 보내지 않은 20260807-0000011에도 존재).
  그대로 두면 **최초 전송까지 수정 경로로 새고**, 마켓플레이스 주문은 수정이 거부되므로(D-154)
  송장이 영영 마켓에 못 들어갔을 것이다. 판정 기준을 "배송건 존재"에서
  **"마켓이 실송장을 들고 있는가"(`ShippingData.isMeaningfulTracking`)**로 좁혔다.
  모호 실패도 *수정 대상이 여럿일 때*로 한정했다(다건 주문의 첫 전송을 막지 않기 위해).
- 테스트: `Cafe24ShipmentUpdateTest` 6건(실송장 보유→PUT · 실송장 없음/자리표시자/배송건 없음→POST ·
  실송장 배송건 여럿→실패 · 실송장 없는 배송건 여럿→POST).
- 위치: `Cafe24ShipmentService.ship` → `Cafe24OrderApiClient.registerShipment`
  (`POST /admin/orders/{id}/shipments`). `MarketOrderPort.updateTracking` 기본 구현이 `shipOrder`로
  폴백해 **수정 경로가 신규 등록을 다시 호출**한다.
- 실체(2026-08-08 라이브 재현): `422 You cannot change to that order state`
  `more_info={status: shipping, order_id: 20260630-0000017, tracking_no: 424438293101, ...}`
- 원인: 두 주문 모두 Cafe24에서 **이미 배송중(status_code N1)**이고 배송건이 하나 등록돼 있다
  (`shipping_code=D-...-00`, `tracking_no=00000000` — 가송장). 배송중 주문에 배송건을 **새로** 만들 수 없다.
- **11번가·네이버와 정반대다 — Cafe24에는 수정 API가 있는데 우리가 안 쓰고 있었다.**
  `PUT /admin/orders/{order_no}/shipments/{shipping_code}` 라우트 존재를 라이브로 확인
  (없는 shipping_code로 PUT → `404 Input value in [Shipping code] is invalid (parameter.shipping_code)`,
  대조군인 없는 경로는 `404 No API found.`). 실주문 미변경.
- 수정 방향: Cafe24 어댑터에 `updateTracking` 구현 — 배송건 조회로 `shipping_code`를 얻어 PUT.
  `sb_shipment.market_shipment_no`에 이미 `D-20260730-0000016-00` 형태로 보관 중(구주문은 주문번호라 폴백 필요).
- 대상: li 402(주문 4462952064) · li 454(주문 4473983719). 현재 `manual_fix_required=t`로 종결돼 있어
  수정 후 플래그 해제 경로도 함께 봐야 한다.

### D-152: Cafe24 클라이언트의 POST/PUT/DELETE가 응답 본문을 버려 원인 추적이 막힌다 (2026-08-08)
- 심각도: P2(진단 차단) · 리스크 등급: 경량 · 상태: **검증통과** (2026-08-08 라이브 — 이 수정 덕에 D-154를 찾았다)
- 수정: `enrich()`를 post/put/delete에도 적용(get과 동일 규율).
- 테스트: `Cafe24RestClientErrorMessageTest` 4건 — JDK 내장 HTTP 서버로 실제 422를 왕복시켜
  네 동사 모두 상태코드·본문이 최상위 메시지에 실리는지 검증(모킹이 아니라 실제 경로).
- 위치: `Cafe24RestClient` — `get()`만 `enrich()`로 상태코드·본문을 메시지에 싣고,
  `post()`·`put()`·`delete()`는 `"Cafe24 API POST 호출 실패"`만 던진다.
- 증상: 원장(`sb_action_log`)에 사유가 `Cafe24 API POST 호출 실패`로만 남아 **D-151의 실제 원인(422 상태 잠금)이
  한 달간 불명이었다.** 스택트레이스의 원인 체인에는 남지만 액션로그·화면에는 도달하지 못한다.
- 수정 방향: `enrich()`를 post/put/delete에도 적용(get과 동일 규율).

### D-153: (오등재 — 무효) Cafe24 동기화가 마켓 가송장을 안 읽는다 (2026-08-08)
- 상태: **무효(오등재)** — 등재 당일 코드 확인으로 철회.
- 철회 사유: `00000000`은 **D-124에서 이미 "ESM+ 자체배송이 Cafe24에 남기는 더미"로 판정**해
  `ShippingData.isMeaningfulTracking`이 의도적으로 배제하는 값이다(전부 0이면 자리표시자).
  마켓이 실송장을 갖지 않은 상태이므로 `market_tracking_no`가 비는 것이 정상이고,
  D-149 배지도 그 전제 위에서 "미반영"으로 바르게 표시된다. 고칠 것이 없다.
- 교훈: 마켓 값이 비어 보이면 "수집 누락"부터 의심하기 전에 **자리표시자 판정 규칙**을 먼저 확인할 것.

### D-154: Cafe24 마켓플레이스 주문은 송장 수정 자체가 불가 — 새 거부 문구가 재시도 루프로 샌다 (2026-08-08)
- 심각도: P1 · 리스크 등급: 경량 · 상태: **검증통과** (2026-08-08 라이브)
- 실체(D-151 배포 직후 라이브): PUT 경로는 정상 동작했으나 Cafe24가 다른 사유로 거부한다 —
  `422 Shipping information (tracking number, shipping carrier code) cannot be edited for marketplace orders.`
  **G마켓·옥션처럼 마켓플레이스에서 연동된 주문은 Cafe24 API로 송장을 고칠 수 없다**(자체몰 주문과 다름).
- 부작용: 문구가 바뀌면서 `isNonRetryableMarketState`의 Cafe24 조건
  (`"You cannot change to that order state"`)에 더는 걸리지 않아 **영구 거부가 재시도 대상으로 샜다.**
  D-151이 의도치 않게 만든 구멍이다 — 동사를 바꾸면 거부 문구도 바뀐다는 걸 놓쳤다.
- 수정 방향: `"cannot be edited for marketplace orders"`를 영구 거부 문구에 추가.
- 사람의 조치 경로: Cafe24 관리자가 아니라 **G마켓·옥션 판매자센터(ESM+)** 에서 고쳐야 한다
  (Cafe24는 마켓 원본을 미러링할 뿐이라 송장의 진실이 그쪽에 있다).
- D-151 평가: 폐기하지 않는다. PUT은 배송건이 있을 때 올바른 동사이고, 무엇보다 **거부 사유가
  "상태 잠금"이 아니라 "마켓플레이스 주문은 수정 불가"라는 진짜 이유로 드러났다**(D-152와 함께).

### D-155: 통관번호 유실 — 동기화 가드가 어댑터 의존이라 얇다 (예방 강화) (2026-08-08)
- 심각도: P2(복구 불가한 PII 유실 위험) · 리스크 등급: 경량 · 상태: **수정완료(검증대기)**
- 계기: 사용자 신고 — 11번가 `20260709083393133`(이영한) 통관번호가 비어 있음.
  가설은 "배송완료 전환 시 마켓이 통관번호를 제공하지 않아 동기화가 지웠다"였다.
- **가설 검증 결과: 동기화가 지운 것이 아니다.** 근거:
  ① 저장값이 `NULL`이 아니라 **빈 문자열 `''`**(length 0) — 누군가 명시적으로 빈 값을 썼다는 뜻.
  ② 11번가 어댑터 2곳 모두 `emptyToNull`, 4개 마켓 동기화 모두 `!= null` 가드, 통관 배치는 번호 미변경,
     `CustomsData` 기본값도 `null` → **동기화가 `''`를 쓸 수 있는 경로가 없다.**
  ③ 라이브: 11번가 배송중 목록엔 `psnCscUniqNo` 태그 **자체가 없다**(5건 전부) → `null` → 가드가 막는다.
  ④ `''`를 쓸 수 있는 유일한 경로는 수동 수정 API(빈 값=클리어, F-ORD-23)이고,
     `sb_action_log`에 `2026-07-17 05:50:10`·`05:50:14` **ORDER_UPDATE 2회**(주문 154)가 있다.
- 수정(예방): 도메인에 정본 가드 `Order.applyCustomsClearanceNoFromMarket` 신설 — blank·마스킹이면
  기존 실값 보존. 4개 마켓 동기화를 모두 이 경로로 배선했다. 종전 방어는 **어댑터가 `emptyToNull`을
  기억해 주는 것에 의존**했고, 어댑터 하나만 바뀌면 뚫린다(이름·주소가 그렇게 사라진 D-107의 재발 방지).
  통관번호는 **한 번 지워지면 마켓에서 되받을 수 없어 복구 불가**라 방어 가치가 특히 크다.
- 테스트: `OrderCustomsSyncGuardTest` 7건(빈값·null·마스킹 보존 · 검증상태 유지 · 실값 반영 ·
  빈 주문 채움 · **수동 클리어 회귀**).
- 미결(사용자 판단 대기): 그리드 인라인 통관번호 셀의 **빈 값 커밋이 즉시 삭제**되는 UX가 이번 유실의
  실제 원인이다. ㉮ 그대로 ㉯ 확인 후 삭제 ㉰ 인라인 삭제 금지 중 선택 필요.
- 참고: 해당 주문의 통관번호는 **복구 불가**(액션로그·백업에 값 없음, 11번가도 미제공).

### D-156: 마켓 송장을 모를 때 배지가 "✓ 마켓 반영됨"으로 거짓 표시 (2026-08-08)
- 심각도: **P1**(사람이 조치해야 할 건을 정상으로 오인) · 리스크 등급: 경량 · 상태: **수정완료(검증대기)**
- 신고: 11번가 `20260720086485068`(엄수현) — 우리는 CJ `424438293101`, 마켓은 우체국 `6079990333504`인데
  화면은 `✓ 마켓 반영됨`.
- 위치: `OrderGrid.tsx` `marketSyncState` 폴백 분기.
  ```
  if (marketTracking) return marketTracking === tracking ? 'synced' : (manualFixRequired ? 'manual' : 'waiting');
  if (shipping?.trackingSentToMarket === true) return 'synced';   // ← manualFixRequired를 보지 않는다
  ```
- 원인: 마켓 송장을 모르면(=`marketTrackingNo` 비어 있음) `trackingSentToMarket` 폴백으로 내려가는데,
  **영구 거부 종결 처리가 바로 그 플래그를 true로 세운다**(D-146/D-154의 `markTrackingAsSent`).
  즉 **영구 거부 건일수록 "반영됨"으로 보인다** — D-147이 경고한 바로 그 함정을 폴백이 되살렸다.
- 수정: **폴백을 없앴다**(사용자 결정 — "폴백은 위험하다, 정직한 게 좋다"). `trackingSentToMarket`으로
  반영됨을 단정하던 줄과 배송상태로 추정하던 `MARKET_CONFIRMED_STATUSES`를 함께 제거했다.
  마켓 값을 모르면 `manualFixRequired`면 `manual`, 미전송이면 `waiting`, 전송했지만 확인 못 했으면
  **신규 상태 `unknown`(`· 마켓 값 미확인`)**으로 드러낸다. `waiting`으로 뭉뚱그리지 않은 이유는
  그것이 "기다리면 자동으로 된다"는 또 다른 거짓이기 때문이다(종결·전송완료 건은 재시도하지 않는다).
  필터 칩에도 추가. 값 비교가 가능한 경우의 판정은 그대로 둔다(사람이 고치면 배지가 스스로 꺼지는 성질 유지).

### D-157: 구매확정·배송완료로 목록에서 사라진 11번가 주문의 상태가 갱신되지 않는다 (2026-08-08)
- 심각도: P2 · 리스크 등급: 표준 · 상태: **수정완료(검증대기)**
- 신고: 위 주문은 마켓에서 **구매확정(`ordPrdStat=901`, `ordPrdStatNm=구매확정`)**인데 시스템은 `SHIPPED`.
- 원인: `ElevenstOrderAdapter.applyProductOrderStatuses`는 **4개 목록에서 수집된 주문만** 대상이라
  목록을 벗어난 주문에 닿지 못한다. 이를 보완하는 `ElevenstOrderSyncService.detectClaims`는
  사라진 주문을 조회하긴 하지만 **클레임(취소·반품·교환)만 반영**하고, 구매확정 같은 정상 종결은
  `mapClaimStatus`가 `null`을 돌려줘 버려진다(오취소 방지 규율의 부작용).
- 근거: `mapProductOrderStatus`는 이미 `구매확정 → DELIVERED`를 안다 — 매핑이 없는 게 아니라 **경로가 없다.**
- 수정 방향: `detectClaims`를 "사라진 주문의 실제 상태 재확인"으로 일반화 —
  클레임이면 클레임 상태, 정상 종결(구매확정·배송완료)이면 `DELIVERED`를 반영한다.
  **오취소 방지 규율은 유지**: 모르는 상태명은 여전히 상태를 바꾸지 않는다(UNKNOWN이면 무변경).

### D-158: 사라진 주문의 마켓 보유 송장을 수집하지 않아 배지 판정 근거가 없다 (2026-08-08)
- 심각도: P2 · 리스크 등급: 경량 · 상태: **수정완료(검증대기)**
- 증상: 위 주문의 마켓 송장(`invcNo=6079990333504`, `dlvEtprsCd=00007` 우체국)은
  `claimservice/orderlistall`·`orderlistalladdr` 응답에 **이미 들어 있는데** 수집하지 않아
  `sb_shipment.market_tracking_no`가 비어 있다. 그 결과 D-149 배지가 값 비교를 못 하고
  D-156의 거짓 폴백으로 떨어진다 — **두 결함이 맞물려 "반영됨" 오표시를 만든다.**
- 수정 방향: D-157과 같은 경로에서 `invcNo`를 함께 읽어 `market_tracking_no`에 반영(D-148 규율 —
  우리 송장은 덮지 않고 "마켓이 아는 값"으로만 보관).

**D-156~158 공통 수정 요약 (2026-08-08)**
- `ElevenstOrderAdapter.resolveClaimStatuses` → `resolveMissingOrderState`로 일반화.
  반환은 `MissingOrderState(statuses, trackingNos)` — 종결 상태(클레임 + 구매확정·배송완료)와 마켓 보유 송장.
  종결이 아닌 상태·미매핑은 담지 않는다 → **오취소·상태 역주행 방지 규율 유지**.
- `ElevenstOrderSyncService`에 `ShipmentRepository` 주입, 사라진 주문의 마켓 송장을 `applyMarketTracking`으로 기록.
  순번별 값이 엇갈리면 기록하지 않는다(어느 배송의 것인지 근거가 없다).
- 테스트: `ElevenstMissingOrderStateTest` 5건 신규 + 기존 7곳 계약 이관
  (그중 `"클레임이 없으면 빈 결과"`는 **구매확정 → DELIVERED 검증**으로 의미가 바뀌었다 — 그것이 이번 수정 자체다).
- 회귀 1006건 실패 0 · 프론트 `tsc -p tsconfig.app.json` + `npm run build` 통과.

**D-156 배포 후 보정 (2026-08-08, 같은 날)**
- 폴백 제거 직후 실측하니 **쿠팡 81건(6~7월 배송완료·미전송)이 `⚠ 전송 대기`로 표시**됐다.
  종전 `MARKET_CONFIRMED_STATUSES` 폴백이 `✓ 반영됨`으로 덮고 있던 건들이다.
  그런데 `waiting`은 "기다리면 자동으로 반영된다"는 약속이고, 그 약속은 여기서 거짓이다 —
  재시도 큐는 **종결 전 주문만** 담고(D-144) 쿠팡도 배송완료면 송장 수정을 거부한다.
- 보정: 미전송이라도 **배송완료면 `unknown`**(마켓 값 미확인)으로 둔다. `waiting`은 약속이 참인 경우에만.
- 배포 후 분포(라이브): 쿠팡 반영됨 75 · 값미확인 28+81 · 11번가 반영됨 3 · 수동수정 8 · 전송대기 1 ·
  G마켓 반영됨 4 · 수동수정 1 · 스토어 반영됨 11.
- **다음 후보**: 쿠팡·스마트스토어에도 D-158처럼 마켓 보유 송장 수집을 넓히면 `값미확인`이 실제 판정으로 바뀐다.

### D-159: 마켓 보유 송장이 과거 주문에 비어 있다 — 신규 컬럼의 소급 공백 (2026-08-08)
- 심각도: P2(판정 근거 부재 → 화면이 `· 마켓 값 미확인`으로 남음) · 리스크 등급: 표준 · 상태: **수정완료(검증대기)**
- 계기: 사용자 요청 — "쿠팡·스마트스토어도 마켓 송장 수집을 확장해달라(모든 마켓이 일관돼야 한다)".
- **조사 결과: 수집 경로는 이미 전 마켓 일관이었다.** 30일 조회 창 <b>안</b>의 주문은
  쿠팡 56/56 · 스토어 7/7 · G마켓 4/4로 **100% 수집**된다(공용 경로 `OrderShipmentUpsertService`).
  비어 있는 건 창 <b>밖</b>(쿠팡 131중 19만, 스토어 13중 4만) — `market_tracking_no`가 D-148(2026-08-07)에
  신설돼, 그전에 창을 벗어난 주문은 값을 가질 기회가 없었다. **기능 부재가 아니라 소급 공백이다.**
- 수정: 마켓별 단건 조회 API를 새로 붙이지 않고(새 경로 = 새 결함), 네 마켓 동기화에 **조회 기간 파라미터**를
  추가해 검증된 경로를 넓은 기간으로 재실행한다. 무인자 호출은 기존 30일 그대로라 스케줄러는 영향 없다.
- 트리거: `POST /internal/backfill/market-tracking?days=120` (내부 토큰 가드 · 기간 상한 365일 ·
  한 마켓 실패가 나머지를 막지 않음). 테스트 4건으로 이 안전장치를 고정.
- 잔여: 백필 후에도 마켓이 송장을 주지 않는 주문(구매확정 등 옛 건)은 `· 마켓 값 미확인`으로 남을 수 있다 —
  그건 사실을 사실대로 표시하는 것이므로 결함이 아니다.

**D-159 보정 — 마켓 API 제약은 실행해 봐야 드러났다 (2026-08-08)**
- 1차 백필(120일 단일 창) 실측 결과: 스토어 성공(수집 11→34) · 11번가 성공 ·
  **G마켓 실패**(`422 The date range for Search End Date should be within 3 months`) ·
  **쿠팡 실패**(`429 TOO_MANY_REQUESTS`). "한 번에 넓은 창"이 통하지 않는 마켓이 있다.
- 보정: 동기화에 **조회 구간(from~to) 오버로드**를 추가하고, `MarketTrackingBackfillService`가
  **오래된 쪽부터 순차로** 구간을 걷는다 — 쿠팡·스토어·11번가 30일, Cafe24 60일(3개월 상한 여유),
  구간 사이 5초 대기. 구간 하나가 실패해도 같은 마켓의 다음 구간을 계속 시도한다.
- 테스트: `MarketTrackingBackfillServiceTest` 5건(구간 크기 상한 · 오늘까지 전 구간 덮기 ·
  마켓 단위 실패 격리 · 구간 단위 실패 격리). 페이싱은 필드로 낮출 수 있게 해 회귀 시간을 잡아먹지 않는다.
- 교훈: **마켓 API의 범위 상한·레이트리밋은 문서보다 실행이 먼저 알려준다.** 백필처럼 평소 경로보다
  넓게 부르는 기능은 첫 실행을 관측 대상으로 삼아야 한다.

**D-159 최종 결과 (2026-08-08 라이브)**
- 백필 완료: 구간 성공 `{COUPANG=4, SMART_STORE=4, ELEVEN_STREET=4, GMARKET_AUCTION=2}`.
- 배지 분포: 쿠팡 반영됨 452 · 전송대기 4 · **값미확인 0**(109→0) · 스토어 반영됨 34 · 값미확인 9 ·
  11번가 반영됨 3 · 수동수정 8 · 전송대기 1 · 값미확인 2 · G마켓 반영됨 4 · 수동수정 1 · **값미확인 0**.
- 잔여 `값미확인`(스토어 9 · 11번가 2)은 마켓이 그 주문의 송장을 주지 않는 건이다 — 사실 그대로의 표시다.
- **부작용(예상 못 함)**: 동기화 경로를 그대로 쓰므로 그 기간의 **과거 주문이 새로 유입**됐다 —
  쿠팡 272 · 스토어 16(4월까지). 전부 `DELIVERED`/`CANCELED` 종결 상태라 이메일 큐·발주확인 등
  자동화는 건드리지 않는다(큐는 종결 전 주문만 담는다). 그리드 건수가 늘어난 것은 사용자에게 보고함.

**D-159 후속 — 백필 유입분 정리 (2026-08-08, 사용자 요청)**
- 백필이 끌어온 과거 주문 **288건**(쿠팡 272 · 4/10~6/6, 스토어 16 · 4/3~5/30)을 제거했다.
  대상 조건: *백필 시각 이후 생성 + 주문일이 30일 창 밖 + 라인아이템이 전부 종결*
  → 오늘 들어온 신규 주문(11번가 박효진 `NEW`)은 두 조건 모두에서 제외됐다.
- 단일 트랜잭션으로 **백업 후 제거**: `_bak_backfill_orders_20260808` ·
  `_bak_backfill_line_items_20260808` · `_bak_backfill_shipments_20260808` (각 288행).
  복원은 세 테이블에서 `INSERT ... SELECT`. 작업 후 **고아 행 0** 확인.
- 정리 후에도 백필 성과는 유지: 쿠팡 반영됨 180 · **값미확인 0** · G마켓 **값미확인 0** ·
  스토어 반영됨 20 · 값미확인 9 · 11번가 반영됨 3 · 수동수정 8 · 전송대기 1 · 값미확인 2.
- **남은 위험 → 해소(같은 날 구현)**: 백필에 **갱신 전용 모드**를 넣었다.
  `createMissing=false`면 없는 주문을 만들지 않고 기존 주문의 마켓 값만 갱신한다.
  적용 지점 — 쿠팡·11번가는 공용 `MarketOrderUpsertDispatcher`, 스마트스토어는 자체 upsert 루프
  (옛 키 폴백 규칙 때문에 디스패처 미사용), G마켓·옥션은 `persistOrder`(→`fetchAndPersist`까지 인자 연결).
  **기본값은 종전대로 생성(true)** 이라 정기 동기화 동작은 불변이고, 백필만 `false`로 부른다.
  테스트 3건으로 고정: 갱신 전용이면 생성 안 함(갱신은 그대로) · 기본은 생성 · 백필은 네 마켓 모두 전용 모드.
- **라이브 검증(2026-08-08)**: 갱신 전용 배포 후 백필 재실행 → 구간 전 마켓 성공
  (쿠팡4·스토어4·11번가4·G마켓2)이면서 **주문 수 243 → 243(유입 0건)**. 목적대로 동작한다.
- 부수 관측: 같은 시점 11번가 수동수정이 8 → 1로 줄었다. 사용자가 셀러오피스에서 7건을 고쳤고
  **동기화가 두 송장 값의 일치를 확인해 표시를 스스로 껐다**(D-148 설계대로 — 완료 체크 버튼 없이
  마켓 실제 상태로 판정). 남은 1건(197 엄수현)은 구매확정이라 마켓이 수정을 거부하는 건이다.

### D-160: 백필이 최근 주문을 거짓 취소 → 정산액 0으로 굳는다 (2026-08-09, 사용자 신고 "홍경희·김유동 정산 0")

- 심각도: **P1**(손익 수치 왜곡 — 화면·대시보드·엑셀 전부) · 리스크 등급: 표준 · 상태: **검증통과(라이브)** — `8e8147b`·`a8b4615`
- 신고: 홍경희·김유동 건의 정산금액이 0으로 표시된다.

**실측 (운영 DB)**

```
 id  | 수취인 | 정산액 | verified | 배송상태  | updated_at
 467 | 홍경희 |   0.00 |    t     | SHIPPED   | 2026-08-08 13:32:06
 468 | 이재근 |   0.00 |    t     | NEW       | 2026-08-08 13:32:06
 469 | Cross… |   0.00 |    t     | NEW       | 2026-08-08 13:32:06
 475 | 김성욱 |   0.00 |    t     | PREPARING | 2026-08-08 13:32:06
 471 | 김유동 |   0.00 |    t     | DELIVERED | 2026-08-09 02:05:52
 767 | 조규전 | 48772  |    f     | PREPARING | 2026-08-09 01:06:23   ← 사건 이후 생성, 정상
```

신고된 2건만의 문제가 아니다. **사건 시점에 종결 전이던 쿠팡 라인아이템 5건 전부**가 0이고,
전부 `settlement_verified=t`다. 반면 그 창의 다른 쿠팡 주문(약 40건)은 전부 `DELIVERED`(종결)라
멀쩡하다 — 즉 **"종결 전"이 정확한 피해 조건**이다.

**인과 사슬**

1. `MarketTrackingBackfillService`가 D-159 백필을 **30일 구간으로 나눠 오래된 쪽부터** 걷고,
   구간마다 `syncCoupangOrders(from, to, false)`를 부른다.
2. 그런데 `CoupangOrderSyncService.postSyncProcess`는 **조회 구간을 무시하고 항상 `now-30d ~ now`**로
   취소 감지를 돌린다:
   ```java
   private void postSyncProcess(List<MarketOrderDto> orders, MarketCredential credential) {
       LocalDate fromDate = LocalDate.now().minusDays(30);   // ← 인자 fromDate/toDate와 무관
       coupangOrderAdapter.detectCancellations(orders, fromDate, toDate);
   ```
3. 그래서 과거 구간(예: 4월)을 조회한 `orders`에 최근 주문이 없는 것을 **"마켓에서 사라졌다"**로 읽고
   최근 30일의 **종결 전 주문을 전부 `CANCELED`**로 바꾼다. `detectCancellations`는 11번가 `detectClaims`와
   달리 **단건 상세조회로 확증하지 않는다** — 부재(absence)만으로 단정한다.
4. 바로 뒤 `terminalSettlementService.zeroSettlementForRefunded(COUPANG)`가 그 `CANCELED` 건들의
   정산액을 **0 + verified**로 내린다(D-098 설계대로 — 입력이 거짓이었을 뿐).
5. 마지막 구간(최근 30일)이 같은 주문을 다시 만나 **배송상태는 원래대로 복원**된다.
   그러나 **정산액을 되돌리는 경로가 없다** — `MarketLineItemSyncDispatcher.applyLineItem`은
   상품·상태만 반영하고 정산액은 **생성 시점에만** 계산된다. 그래서 0이 영구히 남는다.

**증거**: `sb_action_log`의 2026-08-08 13:30:32 / 13:31:06 / 13:31:45 / 13:32:07 `COUPANG_SYNC` 4연속
(이어서 스토어 4 · 11번가 4 · G마켓 2)은 원장의 *"D-159 라이브 검증 — 구간 전 마켓 성공
(쿠팡4·스토어4·11번가4·G마켓2)"* 기록과 정확히 일치한다. 피해 행의 `updated_at`(13:32:06)은
**상태를 복원한 마지막 구간**의 커밋 시각이다. `COUPANG_SETTLEMENT_SYNC`는 08-08 17:05에 `0건 업데이트`라
정산 동기화는 무관하다.

**핵심 비대칭**: 배송상태는 매 동기화가 되살리지만 **정산액은 되살리는 주체가 없다.**
거짓 취소가 한 번만 스쳐도 손익 수치가 영구 손상된다.

- 수정 방향 (셋 다 필요):
  1. `postSyncProcess`가 **실제 조회 구간**을 받아 그 구간에만 취소 감지를 적용한다
     (구간 밖 주문은 판단 대상이 아니다). 백필(`createMissing=false`)에서는 아예 끄는 것도 후보 —
     백필은 "갱신 전용"이 취지이므로 상태 판정 권한이 없어야 한다.
  2. `detectCancellations`를 11번가 `detectClaims`(D-099) 수준으로 올린다 — 부재만으로 단정하지 않고
     단건 조회로 확증. 부분 조회 실패(`failureCount > 0`)일 때도 취소 감지를 건너뛴다.
  3. 갱신 경로가 정산액을 복구할 수 있게 한다 — 마켓 실측/총액이 있고 현재 값이 0인데 상태가 종결이
     아니면 재계산. (D-136 잔여 #1 "갱신 경로가 정산액을 재계산하지 않는다"와 같은 뿌리다.)
- 데이터 교정: 위 5건. `settlement_amount = 주문총액 × 0.89`로 되돌려야 하나 **주문총액을 DB에 보관하지
  않는다** — 쿠팡 재조회가 필요하다. 3의 수정 후 해당 구간을 재동기화하는 것이 정공법.

### D-161: 11번가가 준 `prdNo`를 버려서 순번2의 상품이 비어 있다 (2026-08-09, 사용자 신고 "정나영 첫번째 상품번호")

- 심각도: P2(상품명·상품번호 공백 → 소싱·구매 대상에서 사람이 놓친다) · 리스크 등급: 경량 · 상태: **검증통과(라이브)** — `e914ef4`
- 신고: 정나영 건(11번가 `20260731088778989`)이 2행으로 갈린 것은 맞는데 위 행의 상품번호가 비었다.
  실제로는 `230806IHB154`여야 한다.
- 실측: `sb_order_line_item` 474(순번2) `product_id = NULL`. 459(순번1)는 312(`210121IHB011`) 정상.
- **D-136 잔여 #2로 이미 등재돼 있던 건이다**(2026-08-06). 당시 결론은
  *"어느 API도 `sellerPrdCd`를 주지 않으므로 자동 매핑 경로가 없다"* 였다. 그 문장은 맞지만
  **한 걸음 못 갔다.**

**실제 원인**: `claimservice/orderlistall` 응답에 **`prdNo`(11번가 상품번호)가 들어 있고**,
`sb_market_registration`(11번가 2,286건)이 **이미 `prdNo`를 보관한다.** 우리가 읽지 않을 뿐이다.

- 라이브 픽스처(`ElevenstLiveFindingsTest`, 2026-08-06 실물 응답) 순번2:
  `<prdNm>쏜리서치 베이직 뉴트리언트 투퍼데이 60캡슐</prdNm><prdNo>6124097725</prdNo>`
- 운영 DB: `sb_market_registration` id 9439 → `{"sellerPrdCd":"230806IHB154","prdNo":"6124097725"}`
  → `sb_product_id = 2500`. **사용자가 말한 값과 정확히 일치한다.**
- 그런데 `ElevenstOrderAdapter.OrderAccumulator.StatusRow`는 `prdNo`도 `prdNm`도 읽지 않는다:
  ```java
  private record StatusRow(String statusName, String dlvNo, Integer quantity,
      BigDecimal settlementAmount, BigDecimal sellerFee, BigDecimal marketDiscount) {}
  ```
  그리고 `ElevenstOrderSyncService.elevenstResolveProductId`는 `sellerPrdCd → findBySbCode` 한 경로뿐이다.

**왜 순번1은 멀쩡한가**: 459는 07-31(결제완료·배송준비중 단계)에 만들어졌다. 그때는 **전체 정보 목록**이
`sellerPrdCd`를 줬다. 순번2는 3계층 전환(08-06) 시점에 처음 로스터에 올랐는데 그 주문은 이미 **배송중**이라
전체 정보 목록에 없었다 — 배송중 목록도 `orderlistall`도 `sellerPrdCd`를 주지 않는다.
즉 **"기능 배포 전에 이미 조회 창을 지나 있던 주문"의 신규 순번**이 정확한 발생 조건이다.

- 수정 방향: `StatusRow`에 `prdNo`·`prdNm`을 싣고, `elevenstResolveProductId`에
  **`prdNo → sb_market_registration → sb_product_id` 폴백**을 추가한다(쿠팡의 `vendorItemId` 경로와 동형).
  `prdNm`은 상품 미매핑 시에도 화면에 이름이 뜨게 해 준다.
- 부수 효과: 이 폴백이 있으면 `MarketLineItemSyncDispatcher.applyLineItem`이 다음 동기화에서
  474의 `product_id`를 **스스로 채운다** — 수동 DB 교정이 필요 없다.

**D-160·D-161 수정 완료 (2026-08-09)**
- `e914ef4` D-161 — `StatusRow`가 `prdNo`·`prdNm`을 싣고, `elevenstResolveProductId`에
  `prdNo → sb_market_registration` 폴백. 테스트 5건(어댑터 2 · 동기화 3).
- `8e8147b` D-160 — 취소 감지 사정거리(실제 조회 구간) · 갱신 전용 호출은 상태 판정 금지 ·
  부분 조회 시 감지 스킵(`FetchOutcome`) · 종결 전 정산0 복구(`recoverSettlement`). 테스트 9건.
  정산액 산출을 `MarketLineItemSyncPolicy.settlementAmount`로 분리(네 마켓) — D-136 잔여 #1과 같은 뿌리.
- 회귀 **1040건 실패 0**(착수 전 1026 → 신규 14). 프론트 변경 없음(내부 DTO·서비스만).
- **데이터 교정은 배포 후 동기화 1회로 자동**이다 — 손상 5건은 쿠팡 동기화가, 정나영 474는
  11번가 동기화가 스스로 채운다. 라이브 실측으로 확인할 것.
**D-160 확장 — 11번가에도 같은 가드 (2026-08-09, 사용자 요청)**
- `d61d93e`(구조) `FetchOutcome` → 공용 `MarketFetchOutcome`. 마켓마다 복제하면 같은 버그를 마켓 수만큼
  고쳐야 한다(`MarketLineItemSyncDispatcher` 도입 사유와 동일). 행위 변경 없음.
- `a8b4615`(행위) 11번가 `postSyncProcess`에 같은 3조건 가드 + 어댑터 `fetchOrdersWithOutcome`(7일 chunk
  부분 실패 신호). 테스트 5건, Red 4/5.
- **11번가는 피해가 없었다** — `detectClaims`가 단건 상세조회로 확증한다(D-099). 고친 것은
  *근거 없는 판정의 비용*이다: 백필 구간마다 구간 밖 최근 주문 전부에 상세조회를 때렸고, 11번가는
  레이트리밋 때문에 백필이 구간 사이에 쉬어야 하는 마켓이다. 확증이 언젠가 느슨해지면 그때는
  쿠팡과 같은 일이 난다.
- **스마트스토어·Cafe24는 해당 없음** — 부재 추론(absence inference) 자체가 없다. 확인했다.
- 정산액 복구는 공용 골격에 있어 네 마켓 모두 적용된다.
- 회귀 **1045건 실패 0**.

**D-160·D-161 라이브 검증 통과 (2026-08-09 11:37~11:39 UTC)**
- 배포 `7153abf` → 컨테이너 재생성 `11:23:46Z` → 기동 성공.
- **손상 5건 + 정나영 474가 동기화 1회로 자동 복구**(쿠팡 11:37:15 · 11번가 11:38:35). 수동 DB 쓰기 없음.
  467 27,056 · 468/469 24,920 · 471 25,988 · 475 50,997 (모두 `verified=f` — 재산출이지 실측이 아니다).
  474 `product_id` NULL → **2500**(`230806IHB154`, 사용자가 말한 값과 일치).
- 산술 확인: 복구값 ÷ 0.89가 모두 정수 총액(28,000 · 57,300 · 30,400 · 29,200) — 요율 산출이 그대로 적용됐다.
- 부작용 범위: 종결 전 정산0 잔여 **0건**, 배포 후 변경 라인아이템 **정확히 6건**(손상 5 + 474).
- 미해결 이월: **459의 정산액은 추정값 47,314**(실측 49,887). 0이 아니므로 이번 복구 대상이 아니다 —
  D-136 잔여 #1 그대로. 기존 수치를 실측으로 교정하는 것은 사용자 판단 사항(11번가 전 주문 영향).

---

### D-162: 상품 미매핑 라인아이템이 조용히 NULL로 남는다 — G마켓 유령 리스팅 (2026-08-12, 사용자 신고 "상품코드가 5ffd2a8e27776로 되어있다")

| 항목 | 내용 |
|---|---|
| 심각도 | P1 (매출 누수 + 파이프라인 누락) |
| 리스크 등급 | 표준 (다파일·행위 수정, 스키마 변경 없음) |
| 상태 | 수정완료 · 회귀 통과 |

**신고**: 주문 `4478251768`의 상품코드가 `5ffd2a8e27776`(난수)인데 실제로는 `201203IHB008`이다.

**진단이 바꾼 것**: 신고는 "코드가 이상하다"였지만 실제 문제는 **매출 누수**였다.

`5ffd2a8e27776`은 카페24 상품코드가 아니라 **G마켓 리스팅의 판매자 관리코드**다. 그 리스팅은
카페24 몰 상품과 연동이 끊겨 있어 주문이 `product_no=-99999`, `custom_product_code=null`로 온다.
`cafe24ResolveProductId`는 `product_no → product_code`만 보므로 `product_id`가 NULL로 남았다 —
**경고 한 줄 없이.**

ESM+ 전수 스캔(3,353건, `POST /api/ea/goods/search`)에서 난수 코드 **13건**을 찾았다. 12건은 정상
코드 리스팅이 따로 있는 **중복(유령)** 이고, 연동이 없어 재가격 배치가 닿지 않아 **등록 시점 가격에
얼어붙어 있었다**. 그래서 대부분 정상 리스팅보다 싸고, 구매자는 싼 쪽을 산다.

- 이번 건: 기버터 2개가 유령에서 128,800원(정상가 171,800원) — **한 건에 43,000원 손실**
- 13건 중 **11건이 재고 99999(무한)** — 실재고 0이어도 계속 주문을 받는다(미발송 페널티)
- 상세 명세와 조치 내역: `docs/normalize/esm-ghost-listings-20260812.md`

**핵심은 침묵이었다** — 매핑 실패는 값 하나가 비는 것으로 끝나지 않는다. 재고·정산·소싱이 전부
상품에 매달려 있어 **그 주문은 파이프라인 전체에서 빠진다.** 그런데 신호가 주문 화면의 `-` 하나뿐이라
2026-08-11 주문을 하루 뒤 사람이 눈으로 발견할 때까지 아무도 몰랐다.

**수정**

| 층 | 내용 | 이유 |
|---|---|---|
| 표면화 | `MarketLineItemSyncDispatcher.warnUnmapped` — 상품 못 붙인 라인아이템을 WARN | 조용한 실패가 결함의 본체다 |
| 단서 | 경고에 마켓 식별자·상품명·라인아이템 id를 함께 싣는다 | 경고만으로는 조치할 수 없다 — 어느 리스팅이 범인인지 알아야 마켓을 고친다 |
| 수집 | `Cafe24LineItemMapper`가 `market_custom_variant_code` 수집 | 미연동 주문에 남는 **유일한** 단서인데 버리고 있었다 |
| 화면 | `OrderGrid` 상품코드 셀 `-` → 빨간 **미매핑** 배지 | `-`는 "값이 없다"로 읽히지 "고장났다"로 읽히지 않는다 |

**침묵을 지키는 경계**: 정책이 상품을 못 줘도 **채택한 기존 행이 이미 알고 있으면 경고하지 않는다.**
값이 멀쩡한데 경고를 내면 사람이 경고를 무시하는 법을 배운다.

**폴백을 넓히지 않은 이유**: `custom_product_code`/`market_custom_variant_code`로 `sb_code`를 직접
조회하는 폴백도 검토했으나 **이 사건은 못 풀었다** — 유령 리스팅의 코드는 난수라 우리 DB 어디에도
없다. 진짜 해결은 마켓 쪽 정리였고, 코드가 할 일은 **다음번엔 사람이 즉시 알게 하는 것**이다.

**TDD**: Red 확인 — 경고 테스트는 `[]`(경고 자체가 없음), 매퍼 테스트는 `market_custom_variant_code`
키 부재. 부정 테스트 2건(매핑 성공 시 침묵 · 채택 행이 아는 경우 침묵)은 양쪽에서 통과하는 것이 정상.

**마켓 조치 (2026-08-12, 사용자 승인)**: 유령 12건 판매중지 완료(12/12 성공, 재조회로 검증).
크랜베리 1건은 짝이 없어 코드 교정으로 처리(`62b4280580fb7` → `220623IHB016`) — 중지하면 그 상품이
G마켓에서 아예 안 팔린다. 다만 그 리스팅은 **반품/교환 정보 미설정**이라 어떤 수정도 저장되지 않는
상태였다(반품지·반품배송비를 먼저 채워야 했다). 삭제는 기버터 주문의 배송완료·정산 이후로 보류.

**부수 발견**: `99999`는 무한재고가 아니라 **재고 미설정**의 표시값이다(ESM 재고 변경 다이얼로그
고지: *"재고 미설정 시 99,999로 입력됩니다"*). 원장 본문의 "재고 99999" 서술은 그렇게 읽어야 한다.

**주문 교정**: `sb_order_line_item` id 771 → `product_id=186`. `applyLineItem`이 `productId != null`
일 때만 덮으므로 재동기화에 살아남는다.

**매핑 별칭 등록**: 미연동 리스팅의 주문은 `product_code`에 **G마켓 상품번호**를 싣는다(실측).
그래서 크랜베리 등록(`sb_market_registration` 1842)에 `gmarket_goodsNo=2484492709` ·
`gmarket_masterNo=5711573815`를 넣었다 — 카페24 연동 여부와 **무관하게** 우리 쪽 매핑이 선다.
충돌 0(전 마켓), `extractMarketCode`는 `product_no`/`product_code`만 읽어 가격·재고 경로 무영향,
`enrichIdentifier`가 병합이라 백필이 지우지 않는다. 덤으로 상품 그리드 G마켓 배지 링크가 살아난다.
**나머지 12건은 별칭을 넣지 않았다** — 판매중지라 새 주문이 나지 않고 곧 삭제 대상이다.

---

### D-163: 수동 이메일 트리거가 재진입 스킵 시에도 동기화 상태를 COMPLETED로 기록 (2026-08-21, 리팩토링 캠페인 발견)

- 심각도: P2 / 리스크: 경량 | 위치: `backend/worker/.../controller/EmailFetchController.java` + `backend/worker/.../scheduler/OrderSyncScheduler.syncOrders` (검증 중 동일 결함 확인 — 30분 주기 상시 경로라 오히려 주 발생 지점)
- 증상: 스케줄러 실행 중 `/internal/email/fetch` 호출 시 재진입 가드가 본처리를 스킵(`executed=false`)하는데도 `markCompleted(EMAIL)`을 무조건 호출 — 진행 중인 동기화가 화면상 COMPLETED로 표시되는 구간 발생. 최종 상태는 자가 교정됨(표시 오류만).
- 상세: `docs/normalize/refactor-20260821/bugs-core-rest-worker.md` B-1
- 수정(2026-08-21, fixer-d163): 두 호출부에 `if (executed)` 가드 — 재진입 스킵(false) 시 markCompleted 미호출. markRunning 선행·markFailed·응답 JSON·403 경로·타 스케줄 메서드 불변. 스케줄러 스킵 로그 분기 추가. Red: `EmailFetchControllerSyncStatusTest` 확장 + `OrderSyncSchedulerSyncStatusTest` 신규.
- 검증(2026-08-21, qa-verifier PASS): Red를 수정 전 코드(git archive 전개)에 실측 — 스킵 2케이스가 정확히 NeverWantedButInvoked로 실패, 나머지 5건 통과. `:worker:test --rerun` 72 tests 그린, 리더 전 모듈 회귀 1,102 tests 그린. 경계면 — markRunning은 lastSyncAt 미변경(거짓 "방금" 시각 소멸이 의도 결과), 프론트 RUNNING 1급 렌더 상태·60초 폴링 수렴, 가드 해제가 서비스 내부 finally라 스킵 관측 시 실제 실행자의 완료/실패 기록이 반드시 뒤따름(RUNNING 고착 없음). spotlessCheck 통과. 판정서: `_workspace/verify/D-163_verdict.md`
- 상태: 검증통과

### D-164: `markSentIfSucceeded`의 실패 분기가 거짓 "롤백 예정" 로그를 남기고, 3개 호출 경로 중 2개는 실패 로깅 계약(D-125) 미적용 (2026-08-21)

- 심각도: P2 / 리스크: 표준 | 위치: `backend/core/.../application/order/service/OrderService.java` (`markSentIfSucceeded`·`dispatchLineItem`·`updateTracking`)
- 증상: D-125 계약 반전(실패해도 로컬 보존) 이후 갱신되지 않은 "도달 불가" 분기가 실제로 도달하며 "롤백 예정" 로그를 남김 — 롤백은 일어나지 않아 운영 오진단 유발. `dispatchLineItem`·`updateTracking` 경로는 `logIfNotSent` 미호출로 terminal/재시도 구분 없이 기록됨.
- 상세: `docs/normalize/refactor-20260821/bugs-core-order.md` B-1 (수정 방향 포함)
- 상태: 발견

### D-165: `Order.marketSpecificData` 수제 JSON 파서가 콤마·따옴표 포함 값을 조용히 훼손 (2026-08-21)

- 심각도: P2 / 리스크: 중대(스키마·마이그레이션 영향) | 위치: `backend/core/.../domain/order/Order.java` (`getMarketSpecificDataMap`/`setMarketSpecificDataFromMap`)
- 증상: split 기반 파서 + 무이스케이프 직렬화 + 실패 전부 삼키는 catch. D-135의 `|` 구분자 규약은 특정 2필드만 피해간 봉합이며, 콤마 포함 값을 넣는 순간 재발. 파싱 실패와 "데이터 없음"이 구분 안 됨.
- 상세: `docs/normalize/refactor-20260821/bugs-core-order.md` B-3 (Jackson/jsonb 전환 제안)
- 상태: 발견

### D-166: 신규 상품 등록가가 쿠폰 미반영분만큼 상향 편향 — 재가격 배치 비활성(D-093)로 영구화 (2026-08-21)

- 심각도: P2 / 리스크: 표준 | 위치: `backend/core/.../application/product/MarketSalePriceResolver.resolveForProduct`
- 증상: 쿠폰율·최소마진이 배치 파라미터라 상품에 없음 → 오버라이드 없는 등록 경로는 판매가 과대 산정(실측 51,400 vs 62,200). "재가격 배치가 나중에 교정" 전제가 D-093으로 무효화되어 편향이 영구 잔존. 호출부 전수의 오버라이드 전달 여부 확인 필요.
- 상세: `docs/normalize/refactor-20260821/bugs-core-prodsrc.md` B-4
- 수정(2026-08-21, fixer-d166-be/fe): 하드코딩 대신 정책 저장소 신설 — `PricePolicy`(`sb_price_policy`)·`PricePolicyRepository`·`PricePolicyService`(ACTIVE 단일 행, 부재 시 null). `MarketSalePriceResolver.resolveForProduct`가 오버라이드 누락 필드를 정책으로 보충(marginRate: 오버라이드>상품>정책, couponRate·minMarginPrice: 오버라이드>정책, 세 값 모두 오버라이드면 정책 미조회). `PricePolicyController` GET/PUT `/api/v1/price-policy`(SecurityConfig 무수정 — `/api/v1/**` permitAll). FE: `pricePolicyApi.ts`, Settings 가격 정책 섹션, `MarketBadgeCell` 다이얼로그 초기값을 정책 API로(실패 시 상수 폴백). 배포 후 리더 수동 조치: 시드 `INSERT INTO sb_price_policy ...` (행 부재 시 결함 미해소·무해).
- 검증(2026-08-21, qa-verifier **부분**): `:core:test --rerun` 747 tests·`:api:test` 170 tests 실패 0(신규 8+4+3 케이스 실행 확인), spotlessCheck 통과, 프론트 `tsc -p tsconfig.app.json`·`npm run build` 그린, eslint 5 error 기준선 유지(전부 기존 set-state-in-effect). Red 실측 재현 — `git archive HEAD` 전개본에 신규 테스트 이식 시 8 tests 중 6 fail(`expected 19000 but was 21700` 등, 수정자 보고와 일치), 기대값은 `MarginCalculator` 손계산으로 독립 검증. 행위 보존 확인 — 정책 부재 시 결과 동일, 기존 4테스트는 목 인자 추가뿐 단언 약화 없음, 배치 경로 `resolve(PricingInputs,..)` 무변경, 배지 등록은 3값 전달로 정책 무관. 계약 일치(경로·필드·null 200·래퍼 없음·`Map.of` 미사용), Testcontainers Postgres 컨텍스트 로드로 빈·엔티티 실증. **잔여 2건** — ①정책 `minMarginPrice`가 실제 결속되는 테스트 케이스 부재(현재 6케이스 모두 보정 미발동 → `return null`로 바꿔도 그린) ②정책 시드 후 "원가 있고 마진율 없는 상품"이 기준가→계산가로 전환되는 범위 밖 행위 변화(영향 규모는 운영 DB 조회 필요). 판정서: `_workspace/verify/D-166_verdict.md`.
- 잔여 ② 종결(2026-08-21, 리더 운영 DB 실측): `cost_price>0 AND margin_rate IS NULL` 상품 **0건**(원가 보유 2,467 / 전체 3,195) — 기준가→계산가 전환 대상이 현재 데이터에 없음. **설계 유지 결정**(정책 = 3파라미터 공통 기본값, 향후 마진율 없는 상품은 정책 마진으로 계산되는 것이 의도된 동작).
- 잔여 ① 종결 + **최종 PASS**(2026-08-22, qa-verifier 재검증): 테스트 파일 1개만 보강(프로덕션 무변경 — `MarketSalePriceResolver.java` diff 37/4 및 전문 대조로 복원 확인). 신규 `resolveForProduct_policyMinMarginRaisesPrice`(정책 최소마진 8000 → 22000) + 기존 `일부만 오버라이드…` 강화(최소마진 8000, 기대 23000 — 정상 23000/쿠폰 오염 22000/수정 전 20300으로 세 값이 갈려 두 축 동시 판별). 검증자 독립 뮤테이션(`resolveMinMarginPrice` → `return null;`) 재현으로 두 케이스가 실제로 깨짐 확인(23000→20300, 22000→19000; 20300은 수정 전 실측값과 동일). 재게이트 `:core:test --rerun` **748**/0/0 · `:api:test --rerun` 170/0/0 · spotlessCheck · `tsc -p tsconfig.app.json` 통과. 배포 후 리더 수동 조치(시드 INSERT + `\d sb_price_policy` 확인)는 유효. 환경 flake 1건 기록 — 재검증 1회차 `:core:test`가 단언 실패 0건인데 BUILD FAILED(테스트 실행기 JVM 사망 추정, 원문 미확보), 동일 커맨드 2·3회차는 클린. 이번 수정 귀속 근거 없음, 재발 시 원인 규명 필요.
- 라이브 검증(2026-08-22): ① `sb_price_policy` 테이블 자동 생성 확인(ddl-auto), **PUT→GET→DB 왕복 실측 일치**(15/20/5000). ② 배지 등록 다이얼로그가 정책값을 실제로 읽음 — 정책 쿠폰율을 21로 바꾸자 다이얼로그 초기값이 21로 표시(하드코딩 폴백 20과 구별되는 결정적 증거), 확인 후 20 복원. ③ Settings 정책 섹션은 관리자 로그인 뒤라 사용자 육안 확인 위임. ④ 실등록 1건은 **선행 결함으로 불가** — 3마켓 신규 등록 파이프라인이 각기 다른 이유로 고장 상태임을 발견(D-185 쿠팡 카테고리 메타 404 · D-186 스토어 주소록 404 · D-187 카페24 분류 조회 실패). 무오버라이드 정책 보충 경로는 단위테스트 Red 실측으로 검증됨 — 실등록 실측은 D-185~187 해소 후로 이월.
- 상태: 검증통과 (실등록 실측만 D-185~187 이후로 이월)

### D-167: Cafe24 이미지·상세HTML 동기화 실패가 조용히 성공으로 기록 (2026-08-21)

- 심각도: P1 / 리스크: 표준 | 위치: `backend/infrastructure/.../cafe24/adapter/Cafe24MarketClient.syncImagesAndHtml`
- 증상: 상세설명 PUT 실패·이미지 업로드 실패를 log.error만 하고 삼킨 뒤 정상 반환 — 호출자는 동기화 성공으로 기록(거짓 성공, D-145/D-150 계열). 타 3마켓 어댑터·같은 클래스 publish는 전부 예외 전파.
- 상세: `docs/normalize/refactor-20260821/bugs-infra.md` B-INF-1
- 수정(2026-08-21, fixer-d167): 실패 삼킴 3지점을 예외 전파로 교체 — 상세설명 PUT 실패·이미지 POST 실패·외부 이미지 바이트 null → RuntimeException(원인 포함). 기존 이미지 삭제 실패의 log.warn 삼킴(멱등 정리)만 의도적 유지. 사용자 승인 범위(바이트 null 포함). Red 테스트: `infrastructure/.../cafe24/Cafe24MarketClientImagesTest` 5케이스(전파 3 + 회귀 가드 2).
- 검증(2026-08-21, qa-verifier PASS): `test --rerun-tasks` 전 모듈 강제 재실행 1,088 tests 실패 0. Red 재현성 논증 성립(수정 전 코드에서 전파 3케이스 실패). 경계면 — `MarketClient` 시그니처 불변, 호출부 재게시 부분실패 계약(`updateImagesAndHtml_partialFailureDoesNotBlockOthers`)이 예외를 failed 수집·markSynced 미도달로 수용. 4마켓 실패 전파 일관성 달성. spotlessCheck 통과. 판정서: `_workspace/verify/D-167_verdict.md`. 잔여: 카페24 실 API 라운드트립은 목 기반 미확인 — 라이브 재게시 1건 육안 확인 권고.
- 상태: 검증통과

### D-168: Cafe24 주문취소가 검증된 발주확인과 다른 미검증 API 형태 사용 (2026-08-21)

- 심각도: P2 / 리스크: 표준(마켓 API 계약) | 위치: `backend/infrastructure/.../cafe24/client/Cafe24OrderApiClient.cancelOrder`
- 증상: 검증 스펙(D-091, `PUT /admin/orders` + `requests[].process_status`) 대신 `PUT /admin/orders/{id}` + `request.status="C40"` 사용. 원주석이 "별도 API 필요(D-091 후속 미검증)" 명시(salvage 보존). 라이브 취소 시 422 가능성 높음.
- 상세: `docs/normalize/refactor-20260821/bugs-infra.md` B-INF-2
- 상태: 발견

### D-169: 11번가 XML 중복 태그 제거가 3회 이상 반복·값 불일치에 미방어 (2026-08-21)

- 심각도: P3 | 위치: `backend/infrastructure/.../elevenst/ElevenstOrderRestClient.removeDuplicateTags`
- 증상: 비중첩 replaceAll이라 3회 반복 시 잔여, 두 번째 값이 달라도 검사 없이 폐기. 현재 라이브 미관측 — 잠재 결함.
- 상세: `docs/normalize/refactor-20260821/bugs-infra.md` B-INF-3 | 상태: 발견

### D-170: R2 이미지 업로드가 InputStream을 닫지 않음 (2026-08-21)

- 심각도: P3 | 위치: `backend/infrastructure/.../cloudflare/R2ImageStorageClient.uploadImages`
- 증상: try-with-resources 부재로 성공/실패 모두 미닫음 — 대량 배치에서 핸들 누적 가능. `ImageUploadFile.inputStream()` 실체 확인 필요.
- 상세: `docs/normalize/refactor-20260821/bugs-infra.md` B-INF-4 | 상태: 발견

### D-171: 예외 핸들러의 `Map.of`가 null 메시지에서 NPE — 400/404가 500으로 둔갑 (2026-08-21)

- 심각도: P2 / 리스크: 경량 | 위치: `backend/api/.../exception/GlobalExceptionHandler.java` (`handleNotFound`/`handleIllegalState`/`handleIllegalArgument`)
- 증상: 메시지 없는 예외에서 `Map.of("message", null)` NPE → 의도한 상태코드 대신 컨테이너 기본 500. 같은 파일 내 안전 패턴(문자열 연결)과 혼재.
- 상세: `docs/normalize/refactor-20260821/bugs-api.md` B-API-1
- 수정(2026-08-21, fixer-d171): 3개 핸들러의 `Map.of` 값에 `Objects.requireNonNullElse(e.getMessage(), 핸들러별 기본 문구)` 적용. `handleTypeMismatch`/`handleGeneral`은 무변경. Red: `GlobalExceptionHandlerTest` 신규 5케이스(null 메시지 3핸들러 — 수정 전 NPE 실패 확인 + 보존 가드 2).
- 검증(2026-08-21, qa-verifier PASS): `:api:test --rerun` 167 tests 그린, 메시지 있는 예외의 상태코드·원문 보존 단언 통과, spotlessCheck 통과. 리더 전 모듈 회귀 1,098 tests 그린. 판정서: `_workspace/verify/D-171_D-172_verdict.md`
- 상태: 검증통과

### D-172: 재고 동기화 트리거 실패 시 FAILED 활동로그 누락 — STARTED 영구 미완결 (2026-08-21)

- 심각도: P2 / 리스크: 경량 | 위치: `backend/api/.../controller/ProductSyncController.syncAllProductStock`
- 증상: catch가 500만 반환하고 FAILED 미기록. `OrderSyncController` 5개 트리거는 전부 기록(F-SYNC-3) — 규율 누락.
- 상세: `docs/normalize/refactor-20260821/bugs-api.md` B-API-2
- 수정(2026-08-21, fixer-d172): catch에 `ActionStatus.FAILED` 활동로그 기록 추가(F-SYNC-3 패턴) + 응답 메시지 null 방어(`failureMessage`). STARTED·성공·403 경로 불변. Red: `ProductSyncControllerActionLogTest` 신규 5케이스(FAILED 미기록·null NPE — 수정 전 실패 확인).
- 검증(2026-08-21, qa-verifier PASS): 기존 계약 테스트가 200/403/500 본문 JSON 완전일치로 불변 고정, STOCK_SYNC·FAILED·null 마켓 전부 프론트 매핑 실존, `@Async` 본문 내 SUCCESS/FAILED 기록과 중복 없음(컨트롤러 catch는 동기 디스패치 실패만 포착). 리더 전 모듈 회귀 1,098 tests 그린. 개선 여지(비차단): 컨트롤러에 로거 부재로 앱 로그 미기록, null 메시지 시 활동로그 문구 "실패: null"은 코드베이스 공통 관례. 판정서: `_workspace/verify/D-171_D-172_verdict.md`
- 상태: 검증통과

### D-173: `/api/admin/**` permitAll — Cafe24 리프레시 토큰 발급이 무인증 (2026-08-21)

- 심각도: P2(보안 — 사용자 우선순위상 비중요 분류) | 위치: `backend/api/.../security/SecurityConfig.java`
- 증상: `POST /api/admin/sync/cafe24/issue-token`이 무인증 호출 가능 — 저장 토큰 교란·상태 무인증 조회 가능. 같은 파일의 크레덴셜 보호 원칙과 불일치. 의도/누락 판별 필요.
- 상세: `docs/normalize/refactor-20260821/bugs-api.md` B-API-3 | 상태: 발견

### D-174: `VendorType.valueOf(toUpperCase())` 로케일 의존 (2026-08-21)

- 심각도: P3 | 위치: `backend/api/.../controller/BatchController.java` | 증상: 터키어 등 로케일에서 정상 코드 거부. `Locale.ROOT` 명시가 정석.
- 상세: `docs/normalize/refactor-20260821/bugs-api.md` B-API-4 | 상태: 발견

### D-175: 상품 500건 초과 시 목록·필터·카운트가 조용히 부정확 (2026-08-21)

- 심각도: P1(데이터 규모 도달 시 기능 불능) | 위치: `frontend/src/pages/product/ProductGrid.tsx` (500건 하드코딩 + 클라이언트 필터/페이지네이션/카테고리 역산)
- 증상: 501번째 상품부터 무경고 누락. 근본 해소는 목록 API 서버 필터·페이지네이션·category 필드(백엔드 4작업, bugs-frontend.md T1) 선행 필요.
- 상세: `docs/normalize/refactor-20260821/bugs-frontend.md` B1·T1
- 수정 — 백엔드(2026-08-22, fixer-d175-be): 목록 API에 서버 필터 신설. 컨트롤러가 `keyword`·`marketFilter` 유무로 유스케이스 3메서드→포트 3메서드→JPQL 4종으로 갈라지던 2×2 폭발 구조를 **단일 통합 검색 경로**로 대체 — 조건 값객체 `ProductSearchCondition`(core) + `ProductSpecifications`(JPA Criteria) 하나. 신규 파라미터 5종(`categories`·`vendors`·`stockStatuses`·`markets`·`inStockOnly`) 전부 optional·자유 조합, 없으면 전건. `stockStatuses`는 컬럼값 우선·null이면 `stock>0` 폴백의 **유효 재고상태**로 판정(프론트 기존 식과 동의). `markets`는 ANY. `GET /api/v1/products/categories` 신설(실존 비null category enum명 distinct·정렬). 응답 계약 `Page<ProductListResponse>`·스키마·엔티티 무변경. 통합으로 참조 0이 된 8심볼(JPQL 4종 + 포트 2 + 유스케이스 2) 동시 삭제, `grep -ra` 전부 0 확인. 요지: `_workspace/fixes/D-175_be_fix.md`
- Red 실측(수정 전 트리): ① 행위 — 카테고리 3종 상품에 `categories=[SUPPLEMENT]` 기대 1건이 `expected: 1L but was: 3L`로 실패(필터 무시·전건 반환 실증) ② 계약 — `GET /api/v1/products/categories`가 200이 아니라 **400**. 404가 아닌 이유는 전용 매핑 부재로 요청이 `@GetMapping("/{id}")`에 흡수돼 `"categories"`→`Long` 변환에 실패했기 때문 — 미구현이 아니라 상품 상세로 오라우팅되던 상태였다. ③ 최종 테스트 3종은 수정 전 컴파일 불가(신규 타입·시그니처 부재).
- Green(최종, 2차 반영): `:core: :infrastructure: :api:test --rerun-tasks` **1,166 tests / failures 0**(core 766·infra 225·api 175, BUILD SUCCESSFUL 4m30s). 신규 `ProductSearchSpecificationTest`(@DataJpaTest·H2) **16** + `ProductSearchUseCaseCategoryNamesTest` 2 + `ProductControllerFilterParamTest`(MockMvc) 5, 기존 `ProductControllerR6QueryTest` 5·`ProductControllerMarketMapTest` 5는 신규 경로로 이식하되 검증 의미 보존. 4모듈 컴파일·3모듈 spotlessCheck 통과(spotlessApply 미실행, 위반 수동 교정). 1차(서버 필터 통합 단계) 수치는 1,160 tests/failures 0이었다.
- 경계면 미결이었던 GMARKET·AUCTION 비대칭 → **리더 판정 선택지 2로 해소(2차 작업, 2026-08-22)**: `markets`에 GMARKET을 주면 CAFE24 등록행 + identifiers에 `gmarket_goodsNo` 값 보유 상품으로, AUCTION은 `auction_goodsNo`로 해석한다(배지 파생 조건 `buildGmarketUrl`/`buildAuctionUrl`과 동일 원천 — 키 문자열을 `MarketRegistration` 상수로 승격해 두 경로가 공유). 그 외 마켓·CAFE24 자체는 기존대로 등록행 존재, ANY 의미 불변. 서브쿼리 하나 안에서 OR 분기로 조립하고, 등록행 기반 목록이 비면 그 분기를 생략한다(빈 `IN ()` 방언 파손 회피). **프론트 무변경**(칩 유지). 선택지 1을 기각한 근거는 수정 전 클라 필터가 배지 맵을 봤으므로 기존엔 두 마켓 필터가 실제로 걸렸고, 칩 제외는 회귀이기 때문.
- **2차 결정적 실측 — 운영 JSON은 컴팩트가 아니다**: `gmarket_goodsNo`를 쓰는 코드 경로가 저장소에 없어(백필 SPECS는 COUPANG·SMART_STORE뿐, G마켓/옥션은 외부 ESM 엑셀 백필 유입) 운영 DB를 읽어 형태를 확인했다. 실제 저장은 `{"gmarket_goodsNo": "3315930979"}` — **콜론 뒤 공백**. 컴팩트(`"key":"value"`) 가정 술어였다면 테스트는 전부 green인데 **운영에서 0건**이 나왔을 함정(`compact_gm=0` vs `ws_tolerant_gm=1985` 실측). 그래서 키 존재 검사는 공백 무관 LIKE로 두고 빈 값 배제만 두 스페이싱(`:""`, `: ""`)으로 좁혔다. 키 분포: CAFE24 3,186행 중 gmarket 1,985·auction 1,328, 타 MarketType 행엔 두 키 0건, 빈 값·null 값·다중 공백 0건.
- **두 엔진 의미 동일성 실증**: H2 테스트가 공백/컴팩트 두 직렬화 형태를 모두 고정하고, 운영 Postgres에서 LIKE 술어 vs 배지 진실값(`market_identifiers::json ->> 'key'`)을 CAFE24 3,186행 전수 대조 — gmarket 1985=1985·auction 1328=1328, **불일치 0**.
- 2차 Red 실측: 신규 6케이스 중 **5건 실패**(전부 빈 결과 `Expecting actual: [] to contain exactly in any order: ["SB001"]` 형). 6번째 `markets=[CAFE24]` 등록행 기반 유지는 구현 전에도 green — 기존 동작 보존 가드라 가짜 Red 아님을 명시.
- 관측(비차단): core 테스트에서 H2가 `jsonb`를 모름(`ImageInfo.columnDefinition="jsonb"`) — 테스트 데이터소스에 `INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON`으로 우회. Product를 다루는 후속 JPA 테스트도 같은 벽에 부딪힌다. / `getProducts` 파라미터가 8개 — 축이 더 늘면 `@ModelAttribute` 요청 객체로 묶는 편이 낫다.
- 경계면 확인(fixer-d175-fe 실측 교차): axios v1 기본 `indexes:false`는 `categories[]=A`로 직렬화돼 `@RequestParam List<T>`에 바인딩되지 않는다 — FE가 `paramsSerializer:{indexes:null}`로 반복 키(`categories=A&categories=B`) 고정, BE의 MockMvc 테스트가 같은 와이어 형태를 검증하므로 일치. 신규 5축 enum은 Spring `StringToEnumConverterFactory`(트림 후 `valueOf`)라 **대소문자 정확 일치 필요**(불일치 시 400); 기존 `marketFilter`만 컨트롤러가 `toUpperCase()`해서 예외.
- 검증(2026-08-22, qa-verifier): **PASS**. 판정서 `_workspace/verify/D-175_verdict.md`. ① Red 독립 재현 — `git archive HEAD`로 스크래치에 수정 전 트리를 전개해 자작 프로브 실행: 행위 Red 2건 실패(`categories=[SUPPLEMENT]` expected 1L but was 3L / CAFE24 식별자 좁히기 불가 expected 1L but was 2L), **보존 가드 2건은 수정 전에도 green**(keyword·marketFilter) — 행위 변경분과 보존분이 정확히 갈린다. 계약 Red는 동일 프로브 파일을 두 트리에 넣어 대조: redtree `Status expected:<200> but was:<400>` → greentree 200 PASS. ② 경계면 1순위(다중값 직렬화) — axios 1.17.0으로 실제 URL을 뽑아 실측: 기본값은 `categories[]=A`(바인딩 실패), `paramsSerializer:{indexes:null}` 적용 시 `categories=A&categories=B`. BE MockMvc 테스트가 같은 반복 키 형태를 검증하므로 **양쪽이 같은 와이어를 본다**. enum 리터럴 4축 전수 일치(운영 API 실응답 표본으로 재확인), `stockStatuses` 폴백·`markets` 매핑 조건·0개 선택 no-op 모두 수정 전 클라 필터와 동치, Page 봉투(content/totalElements/totalPages/number)·`ProductFilterPanel` prop 계약 불변. ③ 회귀 — `--rerun-tasks` 1,166 tests/failures 0(core 766·infra 225·api 175, 250클래스) **fixer 주장과 완전 일치**, spotlessCheck `--rerun-tasks` 7작업 실행 통과, worker 컴파일 통과, `tsc -p tsconfig.app.json`·`npm run build` exit 0. ④ 고아 8심볼 `grep -ra` 전부 0(worker 포함, 확장자 무제한 스캔도 0). ⑤ 신규 주석 0·FQN 0·상호 불가침 준수, **검증자 트리 무변경**(착수=종료 git status 동일, spotlessApply 미실행). ⑥ 운영 LIKE↔배지 동일성 read-only 재실측 — gmarket 1985=1985·auction 1328=1328·**불일치 0**/CAFE24 3,186행, 타 MarketType 두 키 0건으로 CAFE24 한정도 안전.
- 검증자 추가 발견(공백 보완, 비차단): 납품 테스트가 전부 `PageRequest.of(0,50)`이라 Spring Data가 카운트 질의를 생략해 **서브쿼리 count 경로가 미실행**이었다. 별도 트리에서 `pageSize=2`로 강제 실행해 markets 서브쿼리·marketFilter NOT IN·stockStatuses 폴백 OR 세 경로 모두 `totalElements`/`totalPages` 정확함을 확인(19 tests/0 failures) — 문제 없음. 본 트리 테스트에 작은 pageSize 케이스 1건 추가 권장.
- 관측(비차단, 후속 후보): ① `vendors` 전체 선택 시 vendor null 행이 이제 포함된다(수정 전엔 제외) — 리더 계약이 명시 요구한 동작이며 운영 영향 **1행**(3,195 중 `vendor IS NULL`=1). ② 운영 `sb_product` 3,195행 중 `category IS NULL`이 **2,858행**이라 `/categories`는 `["FOOD","UNKNOWN"]`만 반환하고, 패널 "전체 카테고리"를 켜면 2,858행이 빠져 337행만 남는다 — 수정 전 클라 필터도 동일 의미라 회귀는 아니나 이제 전건에 걸려 체감이 크다(카테고리 백필 또는 "미분류" 옵션이 별도 결함 후보). → **D-201로 해소 진행**(사용자 선택: "미분류" 필터 옵션 신설). ③ `identifierPresent`는 LIKE 휴리스틱이라 콜론 **앞** 공백·다중 공백·JSON null 값에서 배지 파싱과 갈릴 수 있다 — 현 운영 데이터 0건이고 이 키를 쓰는 writer가 저장소에 없어 지금은 안전.
- 미검증(명시): 라이브 검증 없음(미배포 — 배포 후 `/categories` 200·브라우저 서버 필터 재확인 필요) / FE 런타임 테스트 없음(인프라 부재, 단 직렬화는 실제 axios로 실측) / 성능 미측정(`markets`는 선행 와일드카드 LIKE로 인덱스 미사용).
- 라이브 검증(2026-08-22 16:45 KST, 배포 커밋 c6fe9a6 · 리더 직접, api·frontend 컨테이너 16:31 재생성 확인): ① API 스모크 — `/categories` 200 `["FOOD","UNKNOWN"]`, `totalElements=3195`·`totalPages=64`, `markets=GMARKET` 단독 **1,985건**(운영 DB 전수 실측치와 정확히 일치 — LIKE 술어 운영 작동 실증). ② Chrome 그리드 — 헤더 "전체 3,195건", **64/64 페이지 도달**(3,151번째 이후 상품 표시 — 수정 전 불가), 카테고리 옵션 DB 기준 2종, 500건 안내 문구 제거 확인. ③ 필터 실측 — G마켓 단독 1,985건(전 행 G마켓 배지 활성), 키워드 "비타민" 131건 → +재고만 81건(전 행 "있음") → +FOOD 80건(카테고리 null 행 1건 제외, 전 행 FOOD) — 조합·축소 방향 전부 정확. ④ 와이어 실측 — `?page=0&size=50&keyword=비타민&categories=FOOD&inStockOnly=true` 반복 키 형태 200.
- 상태: **검증통과 (라이브 검증 완료)**
- 이력: 2026-08-21 발견 → 2026-08-22 BE 1차 수정 → 2026-08-22 BE 2차(markets 식별자 해석, 리더 선택지 2) + FE 전환 → 2026-08-22 검증통과(qa-verifier) → 2026-08-22 배포·라이브 검증 완료

### D-176: 날짜 필터 기본값이 UTC 계산 — KST 00~09시에 전날로 밀림 (2026-08-21)

- 심각도: P3 | 위치: `frontend/src/pages/OrderGrid.tsx` 기본 날짜 범위, `pages/dashboard/AttentionPanel.tsx` `todayMinus`
- 증상: `toISOString().split('T')[0]`은 UTC 날짜. `utils/datetime.ts`의 Asia/Seoul 규칙과 불일치.
- 수정(2026-08-22, fixer-d176-177): `utils/datetime.ts`에 Asia/Seoul 기준 헬퍼 2종 신설 — `kstDateString(base?)`(KST 오늘 YYYY-MM-DD), `kstDateStringOffset({days?,months?}, base?)`(KST 오늘 기준 상대 날짜). `Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Seoul'}).formatToParts`로 KST 연·월·일을 뽑아 `Date.UTC`로 재구성 후 `setUTCMonth/setUTCDate`로 연산 — 로컬 타임존 오염과 UTC 환산을 모두 배제하고, JS 월말 오버플로(3/31 -1M → 3/3) 의미는 기존과 동일 보존. 치환 5지점 — `OrderGrid.tsx` 필터패널 기본값(구 `fmt`/`today`/`oneMonthAgo` 제거), `handlePeriod` 프리셋(1주·1개월·3개월 — **원 보고에 없던 4번째 UTC 지점, 타입게이트가 검출**), 그리드 `defaultStart`·`defaultEnd`, `AttentionPanel.todayMinus`. `toKstDate`/`formatKst`는 무변경.
- 게이트(2026-08-22 실측): `npx tsc -p tsconfig.app.json --noEmit` exit 0, `npm run build` exit 0(error 0건). Red 실증 — KST 2026-08-22 07:00(=UTC 08-21 22:00)에서 현행 `defaultEnd`가 `2026-08-21`을 반환(당일 주문이 기본 필터에서 통째 누락)함을 node로 재현. Green — 실소스 모듈을 `node --experimental-strip-types`로 import해 KST 00:30·07:00·09:00·23:59 4경계 전부 `2026-08-22` 반환 확인. 요지: `_workspace/fixes/D-176_177_fix.md`
- 라이브 검증(2026-08-22 17:22 KST, 배포 7b25758): 주문그리드 날짜 기본 범위 07.22~08.22(KST 오늘 종료) 표시 확인. 00~09시 구간 행위는 검증자 경계 19케이스 실행으로 갈음.
- 상세: `docs/normalize/refactor-20260821/bugs-frontend.md` B2 | 상태: **검증통과 (라이브 검증 완료)**

### D-177: 마켓 라벨 맵 3벌의 키 불일치 — CAFE24·AUCTION 라벨 누락, 화면/엑셀 표기 상이 (2026-08-21)

- 심각도: P3 | 위치: `OrderGrid.tsx` `marketLabels`(6키) vs `ProcessStatusPage.tsx`(8키) vs `orderExcelExport.ts`(6키)
- 증상: 해당 마켓에서 원문 코드 노출 가능, 같은 주문이 화면/엑셀에서 다른 이름. G마켓/옥션 통합 여부는 사용자 판정 필요.
- 사용자 판정: **G마켓·옥션 각각 표기**(통합 표기 금지). 정본은 백엔드 `MarketType` enum 라벨과 일치.
- 수정(2026-08-22, fixer-d176-177): 공용 모듈 `frontend/src/utils/marketLabels.ts` 신설 — `MARKET_LABELS`(정본: COUPANG 쿠팡 / SMART_STORE N스토어 / ELEVEN_STREET 11번가 / **GMARKET G마켓** / **AUCTION 옥션** / CAFE24 카페24 / UNKNOWN 알 수 없음 + 소스계열 EMAIL 이메일 · COUPANG_SETTLEMENT 쿠팡 정산), `marketLabel(code)`(미정의 키는 원문 코드 폴백 — 기존 각 파일 동작 보존), `ORDER_MARKET_CODES`. 로컬 맵 3벌 전부 삭제하고 소비처 치환 — `OrderGrid.tsx`(SSE 실패 토스트·마켓채널 체크박스 목록), `ProcessStatusPage.tsx`(`renderMarketType`), `orderExcelExport.ts`(엑셀 '마켓' 컬럼). 그리드 마켓 셀은 원래부터 서버 공통코드(`getCommonLabel('marketType', …)`) 경유라 이미 정본과 일치 — 이제 엑셀·화면 표기가 동일.
- 설계 판단(계약 보강): `OrderGrid.tsx`의 구 `marketLabels`는 **라벨 맵이 아니라 동기화 상태 점(dot) 행의 키 목록**을 겸하고 있었다(`Object.entries(marketLabels)`로 순회하며 `syncStatuses[key]` 조회). ESM+ 동기화는 백엔드가 `GMARKET` 한 키로 G마켓·옥션 양쪽을 기록하므로(`OrderSyncController` `GMARKET_SYNC`), 이 지점만 정본 라벨을 쓰면 옥션 동기화 상태가 표기에서 사라진다. 따라서 공용 모듈에 **동기화 채널 전용** `SYNC_SOURCE_KEYS`(6키)·`SYNC_SOURCE_LABELS`(GMARKET만 'G마켓/옥션'으로 오버라이드)를 분리 신설하고, 점 행·SSE 토스트·`ProcessStatusPage.renderActionType`의 `{코드}_SYNC` 파생 라벨 3곳에만 적용 — **주문의 마켓 라벨(정본)과 동기화 채널 라벨(통합)을 개념 분리**. 점 행 키·순서·문구는 수정 전과 완전 동일(행위 무변경).
- 게이트(2026-08-22 실측): `npx tsc -p tsconfig.app.json --noEmit` exit 0, `npm run build` exit 0(error 0건). Red 상당물 — 수정 전 3벌 맵의 키 불일치를 `_workspace/fixes/D-176_177_baseline.txt`에 원문 채증. Green — 로컬 맵 잔존 0건·소비처 3곳 전부 공용 모듈 import를 grep 실증, 실소스 import로 `marketLabel('GMARKET')='G마켓'`·`('AUCTION')='옥션'`·미정의 키 원문 폴백 확인. eslint 잔여 5건(exhaustive-deps 3·incompatible-library 1·set-state-in-effect 1)은 전부 미접촉 영역의 선존 항목. 요지: `_workspace/fixes/D-176_177_fix.md`
- 후속 후보(미수정, 담당 외 영역 — 2026-08-22 verifier-p3a 지적으로 교정): 주문 도메인 4파일 밖에 마켓 라벨 중복처가 **2곳** 더 있다.
  - `frontend/src/pages/product/productGridShared.tsx:13-19`(`MARKET_FILTER_OPTIONS`)·`:29-34`(`MARKET_BADGES`) — 4번째 중복처. 값은 정본과 일치(N스토어·카페24·G마켓·옥션 분리)라 **표기 불일치는 없음**. 순수 중복 제거 대상.
  - `frontend/src/pages/sourcing/DraftReviewPage.tsx:19-24` — 5번째 중복처이며 **값이 정본과 불일치**: `SMART_STORE: '스마트스토어'`(정본 'N스토어'), `CAFE24: 'Cafe24'`(정본 '카페24'). GMARKET·AUCTION·UNKNOWN 3키가 아예 없어 소비처 `:158`·`:295`의 `?? o.marketType` 폴백으로 **원문 코드가 그대로 노출**된다. 즉 D-177이 잡으려던 표기 불일치가 초안 검수 화면에 실제로 남아 있음. 해당 파일은 fixer-p3-drafts 담당 영역이라 이번 사이클에서 불가침 준수로 미수정. → **처리 완료(2026-08-22, fixer-d178 소사이클)**: 로컬 `MARKET_LABELS` 삭제, 소비처 2곳을 정본 `marketLabel()`로 치환(폴백 동작 동일 — 정본 함수가 `MARKET_LABELS[code] ?? code`). 이제 초안 검수 화면도 N스토어·카페24 표기, GMARKET·AUCTION 원문 코드 노출 해소. 게이트 tsc exit 0·build 성공 실측. 상세: `_workspace/fixes/D-178_fix.md` 부록.
- 비차단 관찰(2026-08-22 verifier-p3a → **리더 판정: 현행 유지**): `ProcessStatusPage.renderMarketType`(액션로그 marketType 컬럼)에 정본을 적용했는데, 이 컬럼에는 `OrderSyncController:108/118`의 `record("GMARKET_SYNC","GMARKET",…)`가 남긴 **동기화 채널 값도 섞여 들어온다**. 그 결과 ESM 동기화 로그 한 행에서 actionType은 'G마켓/옥션 동기화', marketType은 'G마켓'으로 갈린다(수정 전엔 둘 다 'G마켓/옥션'). 기능 영향 없음 — actionType이 채널 통합 표기를 이미 전달하므로 marketType 컬럼은 정본 유지가 맞다고 판정, 4번째 동기화 적용점으로 확장하지 않음.
- 후속: 점 행 CUSTOMS 미렌더는 [[D-200]]으로 등재.
- 라이브 검증(2026-08-22 17:22 KST, 배포 7b25758): 주문그리드 행 배지 '옥션' 개별 표기, 헤더·동기화 버튼 'G마켓/옥션'(채널 통합) 유지, 진행 현황 활동 로그 한 행에서 액션 'G마켓/옥션 동기화'+마켓 'G마켓' — 리더 판정 그대로 렌더. DraftReviewPage 5번째 중복 맵도 정본 치환 배포됨(7b25758).
- 상세: `docs/normalize/refactor-20260821/bugs-frontend.md` B3·B4 | 상태: **검증통과 (라이브 검증 완료)**

### D-178: 소싱 초안 목록 화면 부재 — 2건 이상 생성 시 나머지 초안 접근 불가 (2026-08-21)

- 심각도: P2(기능 공백) | 위치: `frontend/src/api/sourcingDiscoveryApi.ts` `drafts`(호출부 0) / `DiscoveryPage.handleCreateDrafts`(첫 건만 navigate)
- 증상: 이전 세션의 미처리 초안에 UI로 도달 불가. 백엔드 목록 API는 존재.
- 수정(2026-08-22, fixer-d178): 초안 목록 화면 `frontend/src/pages/sourcing/DraftListPage.tsx` 신설(기존 sourcing 페이지의 antd Table 결 준수) — `drafts(status?)` 첫 호출부, `DraftStatus` 5종 다중 상태 필터, id 내림차순(DTO에 생성일 없음), 컬럼은 상품·상태(enrichNote Tooltip)·마켓 준비(enabled 대비 valid)·매입가·마진율·등록 결과(productId), 행 클릭 → `/sourcing/drafts/{id}`. 라우트 `sourcing/drafts` 추가. 진입 경로 — DiscoveryPage 헤더 "등록 초안" 버튼, DraftReviewPage 상단 "← 초안 목록"·등록 완료 Result "초안 목록으로". `handleCreateDrafts`는 1건이면 직행·2건 이상이면 목록으로 분기. 부수 교정 — `sourcingDiscoveryApi.drafts`가 axios 기본 직렬화로 `status[]=`를 보내 Spring `@RequestParam List<String>`에 바인딩되지 않던 것을 `paramsSerializer: { indexes: null }`로 교정(`productApi.ts:119` 선례). 백엔드 무변경.
- 게이트(2026-08-22 실측): `npx tsc -p tsconfig.app.json --noEmit` exit 0, `npm run build` 성공(DraftListPage 청크 3.99 kB). Red 상당물 — 수정 전 `sourcingDiscoveryApi.drafts(` 호출부 0건 grep 실증. 잔여: 신규 파일의 `react-hooks/set-state-in-effect` 1건은 sibling 페이지와 동일 패턴으로 D-180 묶음 편입 후보. 요지: `_workspace/fixes/D-178_fix.md`
- 라이브 검증(2026-08-22 17:22 KST, 배포 7b25758): /sourcing/drafts 라우트 렌더 정상(상태 필터·컬럼·빈 상태 문구). 운영 sb_product_draft 0건·API 200 `[]` 교차 확인 — 빈 상태가 올바른 동작. 실초안 있는 흐름은 다음 초안 생성 시 자연 검증.
- 상세: `docs/normalize/refactor-20260821/bugs-frontend.md` B5·§C | 상태: **검증통과 (라이브 검증 완료)**

### D-179: 죽은 추상화·오도성 코드 정리 후보 묶음 (2026-08-21, P3 일괄)

- `CoupangRestClient.delete(String)` — D-181 수정으로 호출부 0 (2026-08-21 편입) / `SourcingAgentFactory`+`SourcingAgent` 구현체 0의 고아 쌍(빈 리스트 주입, 오도성 예외 메시지) / `worker/config/EmailAccount` enum(참조 0 + 실계정 하드코딩이 현행 구성과 불일치 — 오독 위험) / `MarketRegistrationDefaults.unconfigured()` 미배선 점검 기능 / `CoupangHmacUtil.generateSignature·generateDatetime`(구 KST 서명, "HMAC format is invalid" 유발 이력) / `CoupangDataMapper.extractMergedHtmlDescription·getPrice` 미참조 / `api/config/AsyncConfig` 껍데기(D-011 후속 — `@EnableAsync` 담당 여부 검증 후 판단) / 프론트 §C "백엔드 엔드포인트와 짝인 미호출 API 클라이언트 7건"(양쪽 동반 판정 필요)
- **수정완료(검증대기)** (2026-08-22, fixer-d179): 구조 변경 전용 — 삭제 전 항목마다 `grep -ra`(NUL 함정 회피) 참조 0 실증. **백엔드 7항목 전량 삭제**: ① `CoupangRestClient.delete(String)`(쿠팡 DELETE는 D-181이 `requestWithBody("DELETE")`로 전환 — 쿠팡 범위 `restClient.delete` 0건) ② `SourcingAgentFactory`+`SourcingAgent` 쌍(`implements` 0·주입처 0·호출부 0. `@Component`였지만 단일 생성자 폴백으로 빈 리스트가 주입되던 무동작 상태였고, 반환 타입 `ScrapedProductDto`는 살아 있는 `ProductInfoCrawlerPort` 경로가 계속 써서 존치) ③ `worker/config/EmailAccount`(참조 0 — 현행은 `core.config.EmailAccountProperties` 체제, 하드코딩 계정 7종은 오독 유발 잔재. 디렉터리 빈 상태로 남음) ④ `MarketRegistrationDefaults.unconfigured()`+전용 헬퍼 `putIfBlank()`(`@Value` 필드 18개·`@Getter`는 전부 존치) ⑤ `CoupangHmacUtil.generateSignature`·`generateDatetime`(살아 있는 서명 경로는 `generateSignatureUtc` 단 하나, `buildAuth`·시간 import는 UTC 판이 계속 사용) ⑥ `CoupangDataMapper.extractMergedHtmlDescription`·`getPrice`(`buildIdentifiers`·`buildRawData`·`getStock` 3개는 참조 있어 존치) ⑦ `api/config/AsyncConfig` 껍데기 — **삭제 전 전수 검증 완료**: `@EnableAsync`는 컨텍스트당 전역·멱등(프록시 모드 BPP 1개)이고, `ApiApplication`이 `scanBasePackages="com.sbshop.agent"`라 core 판을 반드시 스캔하며, `@Async("syncTaskExecutor"/"productBatchExecutor")`가 요구하는 실행기 빈 2개를 정의하는 곳이 **core 판뿐**이라 운영에서 core 판 로드가 확정적, api 판은 빈 0개 껍데기(`{}`), api 유일 `@Async` 사용처(`SourcingDiscoveryRunner:28`)는 core 판 BPP가 계속 프록시 → **담당 아님, 삭제**. D-011 잔재 테스트 `AsyncConfigBeanNameConflictTest`(`apiAsyncConfig` 존재 단언)는 삭제 대신 **역방향 구조 가드 `AsyncConfigStructureTest`로 교체**(파일명도 사실에 맞게 개명 — 껍데기 부재 단언 + core 판 단독으로 `ASYNC_ANNOTATION_PROCESSOR` 빈 등록 확인) — api 테스트 181→182 순증. **프론트 §C 6쌍은 BE 전량 존치·FE 5건만 삭제**: 수동 curl 관례가 실 운영 수단인 프로젝트라 GET/진단 엔드포인트 삭제는 행위 변경으로 보고 HTTP 표면 무변경(컨트롤러 테스트 정리 0건). FE 삭제 5 — `crawlSourceImages`(FE 실사용은 `crawlAndUpload`), `getMarketRegistrations`(**대체 경로 실증** — 그리드는 상품 목록 DTO에 실려 오는 `marketRegistrations` 필드를 읽는다: `productApi.ts:17`→`MarketBadgeCell.tsx:28`·`productGridShared.tsx:59`), `getLocalMarketData`, `syncMarketLive`, `sourcingDiscoveryApi.candidate`. FE 존치 1 — `batchApi.manualUpdate`는 짝인 `manual-update-price-stock`이 D-180a가 방금 키셋 정비한 트리거 4종 중 하나라 FE 배선 전제 투자 직후 제거는 역행으로 판단. 같은 컨트롤러의 `…/{marketType}/handoff`는 FE 실사용 중(마켓플러스 딥링크) 무변경. 게이트: 4모듈 컴파일 · `test --rerun-tasks` **1,245 tests/0 failures**(core 766·infra 225·api 182·worker 72) · 4모듈 `spotlessCheck` 통과(spotlessApply 미사용, 유휴 import는 수동 제거) · `tsc -p tsconfig.app.json --noEmit` exit 0 · `npm run build` 성공. 삭제 후 잔여 참조 재확인 전부 0건. `_workspace/fixes/D-179_180b_fix.md`
- 검증(2026-08-22, verifier-p3b PASS): 삭제 심볼 전수 독립 grep -ra 참조 0 재확인(worker·리소스 포함), AsyncConfig는 core 실담당 확정 후 삭제 안전성 인정, 회귀 1,245 tests 그린.
- 상세: 각 `docs/normalize/refactor-20260821/bugs-*.md` 데드 의심 절 | 상태: **검증통과** (구조 변경 — 라이브 검증은 배포 후 스모크로 갈음, 커밋 413b97d)

### D-180: 계약 일관성·프론트 품질 잔부채 묶음 (2026-08-21, P3 일괄)

- 배치 트리거 4종 응답 키셋 비대칭(`count` 유무) / `requireNonNegative` 헬퍼 2중 + 인라인 반복 / eslint `react-hooks/set-state-in-effect` 5건(useEffect 수동 페칭 6파일이 근본 원인) / 스타일 4방식·페칭 3패턴·toast 이중화·라벨/색상 다중 정의 등 구조 부채 목록 / `OrderGrid.tsx` 2,065줄 분해(독립 사이클 권장) / `productMockApi.ts` 파일명 정정(rename → productBulkApi)
- **D-180a(배치 트리거 응답 키셋 비대칭) 수정완료(검증대기)** (2026-08-22, fixer-d180a): 가산 변경 — `crawl-and-update`·`manual-update-price-stock`·`manual-update-all` 세 트리거 응답에 `count`(문자열, 대상 건수) 추가로 4종 전부 `{batchId, count, message}` 공통 보장. 기존 키 제거·개명·값 변경 0, `by-supplier` 무변경. count는 각 트리거가 이미 액션로그 "N건"·`BatchStartedEvent(total)`에 쓰던 `productCodes.size()` 재사용(신규 계산 없음) — 제외 트리거 없음. Red: 신규 `BatchControllerTriggerKeysetContractTest` 6케이스를 수정 전 코드에 실행 → **4 failed**(전부 `count` 부재), by-supplier 2건은 보존 가드로 통과. Green: `:api:test --rerun-tasks` **181 tests/0 failures**, 4모듈 컴파일·`:api:spotlessCheck` 통과. 특성화 테스트는 단언 무충돌(`containsEntry` 기반)이라 `@DisplayName` 문구만 신규 키셋으로 갱신. FE 소비처 3곳(`BatchUpdatePage.tsx:134·147`, `ProductGrid.tsx:166`) 읽기 조사 결과 전부 `batchId`/`count`만 구조적으로 읽어 무영향, `manual-update-*` FE 소비처는 0건. `_workspace/fixes/D-180a_fix.md`
- D-180a(배치 응답 키셋) 라이브: 실배치 트리거는 운영 비용 문제로 미실행 — MockMvc 계약 테스트 6건·verifier Red 독립 재현으로 갈음, 배포됨(400b6b1).
- **D-180c(프론트 구조 잔부채 — set-state-in-effect 전량 해소 + productMockApi 개명) 수정완료(검증대기)** (2026-08-22, fixer-d180c): 구조 변경 전용(행위 불변 원칙). ① eslint 실측 전 **11 problems(6 errors/5 warnings)** → 후 **5 problems(0 errors/5 warnings)** — `react-hooks/set-state-in-effect` **6건 전량 해소, 신규 위반 0**. 잔여 5 warnings는 전부 기존값 그대로(`OrderGrid` exhaustive-deps 3·incompatible-library 1, `ProductGrid` incompatible-library 1 — 범위 밖). 원장 기재 5건이 아니라 6건인 이유는 D-178로 신설된 `DraftListPage`가 sibling 패턴을 물려받아 1건 추가된 것(D-178 결과서 예고대로 본 묶음에 편입). ② 전환 5파일 `useQuery`화: `sourcing/DraftListPage`(목록·statusFilter 키)·`sourcing/DiscoveryPage`(후보+발굴상태 2쿼리, 폴링 setInterval은 존치하고 콜백만 refetch로 교체)·`ProcessStatusPage`(활동로그, SSE 핸들러→refetch)·`sourcing/DraftReviewPage`(초안 로드)·`BatchUpdatePage`(배치 요약 폴링→`refetchInterval` 함수형). 폼 시드 2파일(`Settings`·`DraftReviewPage`)은 페칭이 아니라 **파생 상태**가 원인이라 React 공식 "렌더 중 상태 조정"(직전 소스 식별자 비교 후 setState) 패턴으로 전환 — effect 제거로 커밋 후 추가 렌더 1회도 사라짐. ③ 동등성 보정: 모든 신규 쿼리 `retry: false`(원본 수동 페칭은 1회 시도 후 즉시 토스트), 목록은 `placeholderData: keepPreviousData`(필터 변경 시 기존 행 유지 = 원본 거동), 폼·요약은 `gcTime: 0`(재방문 시 캐시된 낡은 폼값 노출 차단 = 원본의 매 마운트 재조회), 배치 요약은 `refetchIntervalInBackground: true`(원본 setInterval은 백그라운드 탭에서도 폴링). 전역 `refetchOnWindowFocus:false`·staleTime 0 기본이라 마운트마다 재요청하는 신선도는 원본과 동일. ④ `pages/product/productMockApi.ts` → `productBulkApi.ts` 파일시스템 `mv`(내용 무변경, 실체는 실 API 순차 호출), import 1곳(`ProductGrid.tsx:16`) 갱신 — 타 참조처 grep 0건. ⑤ 범위 밖 존치: `OrderGrid.tsx` 2,065줄 분해(별도 사이클 지시), `api/`·`backend/`(병렬 fixer 작업 중 불가침), 잔여 warning 5건, `ProductDetailModal.tsx:136`의 억제 지시자(수치에 안 잡히는 7번째 동일 패턴 — 전환법은 `DraftReviewPage`와 같으나 직전 사이클이 의도적으로 유지한 건이고 상품 페이지가 병렬 작업 중이라 다음 사이클 후보로 남김). 게이트: `tsc -p tsconfig.app.json --noEmit` exit 0 · `npm run build` 성공 · eslint 전후 대조. `_workspace/fixes/D-180c_fix.md`
- D-180c 관측(라이브 검증 시 확인 필요, 비차단): ⓐ `DraftReviewPage`는 URL id가 숫자가 아닐 때 원본이 무한 스피너였고 이번에도 `loading = !Number.isFinite(draftId) || isLoading`로 **동일 거동을 보존**함 — 404 안내로 바꾸는 것은 행위 변경이라 별도 판단 대상. ⓑ `DiscoveryPage` 폴링 중 상태조회가 네트워크 오류일 때 원본이 "발굴 완료"로 오판(토스트+목록 갱신)하던 것을 `polled.isError || !polled.data?.running`로 **그대로 보존**함 — 교정은 행위 변경이라 후보로만 기록. ⓒ 목록 3화면은 재방문 시 캐시된 이전 행이 잠깐 보인 뒤 갱신됨(원본은 빈 표+스피너) — 데이터 신선도는 동일하며 참조 결인 `ProductGrid`와 같은 거동.
- **D-180c 반려 1건 → 교정 완료** (2026-08-22, verifier-p3b 지적 · fixer-d180c 재수정): `Settings.tsx`의 렌더 중 상태 조정이 **웜 캐시 재마운트에서 폼을 시드하지 못하는 회귀**를 만들었다. 원인은 `seedSource` 초깃값을 `{ credentials, tab }` — 즉 **첫 렌더의 credentials 자기 자신**으로 잡은 것. `market-credentials` 쿼리는 `gcTime` 기본 5분이라 5분 내 재방문 시 첫 렌더에 캐시가 동기 반환되고, 그러면 비교식 양변이 같아져 시드 분기가 그 마운트 동안 한 번도 실행되지 않는다(폼이 빈 칸). staleTime 0의 배경 refetch도 **structural sharing이 deep-equal일 때 동일 참조를 유지**하므로 구제하지 못한다 — 자격증명이 안 바뀐 통상 경우 탭을 옮겼다 돌아오기 전까지 빈 칸이 유지된다. 수정 전 `useEffect(…, [credentials, activeTab])`는 마운트 시 dep 값과 무관하게 1회 실행돼 언제나 시드했으므로 명백한 행위 변경. 교정: 초깃값을 센티넬 `{ credentials: undefined, tab: activeTab }`로 — 같은 사이클 `DraftReviewPage`가 이미 쓰던 패턴과 통일. 실측(react-dom/server + 실물 react 19.2.7 / react-query 5.101.0 프로브, fixer 독립 재현): 교정 전 `WARM: credentialsPresentOnRender=true | formData={}`, 교정 후 `WARM: … formData={"marketType":"COUPANG","clientId":"cid-1",…}`, COLD 경로는 전후 동일. 게이트 재실행 — tsc exit 0 · build 성공 · eslint `✖ 5 problems (0 errors, 5 warnings)` 유지. 나머지 5파일 전환·rename은 verifier PASS(전후 diff·eslint 수치 독립 재실측·rename md5 동일).
- **D-180b(`requireNonNegative` 중복 통합 + `paramsSerializer` 전역화) 수정완료(검증대기)** (2026-08-22, fixer-d179): 구조 변경 전용. ① 신규 `api/validation/NonNegativeValidator`(private 생성자 유틸, static `(String,BigDecimal)`·`(String,Integer)` 2벌)로 **private 헬퍼 2곳만** 흡수 — `ProductController` 2벌·`OrderController` 1벌. 메시지 생성식(`label + "는 0 이상이어야 합니다: " + value`)이 구 헬퍼와 바이트 동일해 호출 라벨 11개(`ProductController` 9·`OrderController` 2)의 메시지는 불변. **미흡수 2건(문구가 달라 접으면 행위 변경)** — `ProductSourcingController:83`의 `costPrice`(`"costPrice는 음수일 수 없습니다: "`), `ProductController.updatePriceStock`의 `가격`(아래 반려 대응). **반려 1건 대응 완료** (verifier-p3b FAIL 1, 2026-08-22): 초안이 `updatePriceStock` 인라인까지 `requireNonNegative("가격", …)`으로 접었는데 인라인 문구는 조사 `은`(`"가격은 …"`)이고 공용 헬퍼는 `label + "는 …"`이라 `"가격는 …"`으로 바뀌었다 — `GlobalExceptionHandler.handleIllegalArgument`가 `getMessage()`를 HTTP 400 본문 `message`에 그대로 실으므로 `PUT /products/{id}/price-stock`에 음수 가격을 보내는 클라이언트의 수신 문자열이 바뀌는 **행위 변경**. 독립 재현 `OLD=가격은 0 이상이어야 합니다: -1` / `NEW=가격는 …` / `IDENTICAL=false`. 조치는 권장안 (a) — 해당 인라인을 수정 전 `if/throw`로 **원복**하고 통합 범위에서 제외(`git diff` 상 이 메서드 본문 hunk 0 = HEAD와 바이트 동일), `NonNegativeValidator`와 라벨 11개 흡수는 유지. **게이트 구멍 봉합** — 이 건이 1,245 그린을 통과한 원인은 기존 테스트가 예외 **타입만** 단언하고 저장소 전체에 `"0 이상이어야"` 문자열 단언이 0건이었던 것이라, 기존 6개 테스트에 `.hasMessage(…)` 단언을 추가했다(테스트 전용, 프로덕션 무변경): `updatePriceStock`(`"가격은 0 이상이어야 합니다: -1"` ← 이번 회귀의 직접 가드)·`salePrice`·`stock`·`costPrice`·`sourcingAmount`·`logisticsCost`. 재게이트 `:api:test --rerun-tasks` **182 tests/0 failures** · `:api:spotlessCheck` 통과(core·infra·worker 소스 무변경이라 766·225·72 결과 유효). ② `axios.ts` `apiClient` 생성에 `paramsSerializer:{indexes:null}` 전역 적용, `productApi.ts:119`·`sourcingDiscoveryApi.ts:195` 개별 지정 제거. 전제 확인 — axios 인스턴스는 이 하나뿐, `params:` 넘기는 호출부는 전체 7곳이고 배열은 2곳(products·drafts)·나머지 5곳은 스칼라 전용(`indexes`는 배열 직렬화에만 관여). **와이어 동일성 실증**(D-175·D-178 채증 방식 재사용 — 어댑터 가로채기로 `axios.getUri` 최종 URI 캡처, 실제 호출 형태 8케이스를 개별 판/전역 판으로 각각 태워 대조): 전부 동일, 배열은 양쪽 `k=a&k=b`(Spring `@RequestParam List<T>` 바인딩 형태), 스칼라·undefined 처리도 동일 — `IDENTICAL: true`. 게이트는 D-179와 공통(1,245 tests/0 failures·spotlessCheck·tsc exit 0·build 성공). `_workspace/fixes/D-179_180b_fix.md`
- D-180b·D-180c 검증(2026-08-22, verifier-p3b — 반려 각 1회 후 재검 PASS): b는 인라인 검증 메시지 1글자 변경을 원복(바이트 동일 재확인)·serializer 전역화 와이어 동일 실증, c는 Settings 웜 캐시 시드 회귀 교정 후 useQuery 전환 6파일 행위 보존·eslint 에러 6→0·rename md5 동일.
- **D-180e(알림 라이브러리 이중화 해소) 검증통과** (2026-08-22 수정 / 2026-08-23 verifier-p3c PASS, fixer-d180e): 구조 변경 전용 — 알림이 뜨는 시점·조건·문구 0건 변경, 바뀐 것은 표시 위치·스타일뿐(antd `message` 상단중앙 → react-toastify 우하단, 지속시간은 **공칭 3초 동일**이나 toastify 11.1.0은 `pauseOnHover`·`pauseOnFocusLoss` 기본값이 둘 다 true라 호버·포커스 상실 시 일시정지하는 차이가 있다 — antd message는 멈추지 않음. 읽는 중 사라지지 않는 방향이라 개선에 가깝지만 "완전 동일"은 아님, verifier 지적 반영). 방향은 리더 판정대로 **react-toastify 통일**(antd는 `Modal`·`Pagination`·`Drawer` 컴포넌트로 존치, 알림 API만 통합) — 사용 빈도가 높은 주문·상품 화면이 이미 toastify라 체감 변화 최소화가 근거. 신설 `frontend/src/utils/notify.ts`(toastify 얇은 래퍼, `success`/`error`/`warning`/`info` 4종, 2번째 인자로 `ToastOptions` 통과). **치환 66건 / 10파일** — antd `message.*` 54건(`ProductDetailModal` 12·`ProductRegisterPage` 11·`DiscoveryPage` 10·`DraftReviewPage` 6·`SourcingSettingsPage` 6·`BatchUpdatePage` 5·`ProcessStatusPage` 3·`DraftListPage` 1) + 허용 영역 내 직접 `toast.*` 12건(`MarketBadgeCell` 6·`ProductGrid` 6)을 전부 `notify.*` 경유로. **문구 동일성 기계 증명** — `git archive HEAD | tar -x`로 뽑은 pristine 사본(병렬 fixer 편집 혼입 차단)과 현재 트리를 호출 접두어 정규화(`message.`/`toast.`/`notify.` → 공통 토큰, `warn`→`warning`) + import 행 제외 후 `diff`, **10파일 전부 바이트 동일** — 문구·조건·분기·인자 표현식 무변경 확정(직전 사이클 조사 1글자 회귀 재발 방지 절차). 1:1 대응 없던 케이스 3: ① `toast.warn`(`ProductGrid:155` 1건)은 toastify에서 `toast.warning`의 별칭(`dist/index.d.ts:390-391` 동일 시그니처)이라 별칭 축약이며 의미 변경 아님 ② `message.loading` 사용처 0건이라 loading 계열 미구현(필요 시 `toast.loading`+`toast.update`로 확장) ③ antd `message`의 duration 2번째 인자·반환 close 핸들 사용처 각 0건이라 옮길 의미 없음. `Modal.confirm` 등 다이얼로그는 대상 외 무변경, `App.tsx` ToastContainer 설정 유지, 계약대로 `react-toastify` 의존성 제거 판단 안 함. **2차분(리더 승인 후)** — `ProductRegisterPage.tsx` 11건은 최초 허용 목록 밖이라 1차에서 보류했다가(`pages/` 직속으로 허용된 `BatchUpdatePage`·`ProcessStatusPage`와 같은 층인데 목록 누락) 리더가 D-180d 담당 범위와 무충돌임을 확인·승인 후 처리. 착수 전 `git diff --stat` 0줄로 HEAD 동일(병렬 편집 없음) 실증. **이중화 해소 판정: antd `message` 사용처가 저장소 전체 0건** — 1차 문서에서 "OrderGrid 37건이 끝나야 단일화 완료"라 적은 것은 오판이었고, `pages/order/OrderGrid.tsx`의 37건은 antd가 아니라 **이미 react-toastify 직접 호출**이라 이중화의 구성원이 아니다(같은 `ToastContainer`를 써서 표시 위치·거동이 통일 후와 동일). 따라서 **D-180 잔여 항목 '알림 이중화'는 본 작업으로 해소 완료**이고, 남은 것은 그 37건이 `notify` 래퍼를 경유하지 않는 스타일 일관성 잔부채뿐 — D-180d 분해가 검증대기라 트리 동결 존중해 손대지 않았고 그 게이트 통과 후 별도 처리한다. 허용 영역(`utils/**`·`sourcing/**`·`Settings.tsx`·`BatchUpdatePage`·`ProcessStatusPage`·`dashboard/**`·`product/**`·`App.tsx`) + 승인 확장분 내 **라이브러리 알림 호출** 잔존 **0건**(`dashboard/**`는 알림 호출 자체 0건). **사실 정정(verifier 지적)** — 요지 1차판의 "`Settings.tsx`도 알림 호출 0건"은 **틀렸다**: 네이티브 `window.alert()`가 6건 있다(`Settings.tsx:62·67·107·110·137·140` — 토큰 발급 결과/오류, 설정 저장 성공/오류, 가격 정책 저장 성공/오류). 최초 조사가 `message.*`/`toast.*` 패턴만 훑어 네이티브 경로를 놓친 것. `alert()`는 라이브러리가 아니라 계약 대상 밖이고 판정에도 무영향이나, **전환은 행위 변경**(확인 눌러야 진행 → 안 눌러도 진행)이라 구조 전용 사이클 대상이 될 수 없다 — 후속 후보로만 등재. 저장소 전체 네이티브 경로는 이 6건 + `pages/order/OrderGrid.tsx:350` `window.confirm` 1건(다이얼로그라 대상 외)이 전부. 게이트: `tsc -p tsconfig.app.json --noEmit` exit 0 · `npm run build` 성공 · `npx eslint .` **✖ 5 problems (0 errors, 5 warnings)** — 신규 0 실증은 저장소 전체 실행에 병렬 fixer 미완 편집이 섞이므로 **만진 파일만 pristine 대 현재 트리로 개별 lint 대조**(1차 9파일은 양쪽 `✖ 1 problem (0 errors, 1 warning)`로 동일 — `ProductGrid.tsx:134 react-hooks/incompatible-library`, 2차 `ProductRegisterPage`는 전후 모두 지적 0건. 전체 5건 중 나머지 4건은 D-180d 담당 `OrderGrid` 기존분). 주석 0줄, git 조작 0. `_workspace/fixes/D-180e_fix.md`
- D-180e 검증(2026-08-23, verifier-p3c — **반려 0회 PASS**): 문구 무변경을 **TypeScript 컴파일러 API로 AST 파싱**해 알림 호출의 레벨·인자 개수·인자 소스 텍스트 원문을 소스 순서대로 대조(66건 전건 일치·불일치 0, 인자 개수 분포 `{1:66}` 단일로 duration 미사용도 독립 확인), 나아가 각 파일 unified diff의 모든 변경행을 AST가 계산한 `ImportDeclaration` 줄 범위로 분류해 **import 내부 28행 + 알림 접두어만 다른 66행, 실질 변경 0** 확정 — fixer의 텍스트 정규화 diff보다 강한 증명. 불가침 준수 확인(`pages/order/**` notify 출현 0, toast 37건이 HEAD와 동수, backend 0행). **게이트 기법 개선안 채택** — fixer의 파일별 개별 lint는 파일 간 상호작용을 놓칠 수 있어, `git archive HEAD` pristine에 대상 파일만 덮어쓴 **격리 트리**로 저장소 전체 게이트를 이 작업에 100% 귀속시킴(head·iso 양쪽 `tsc 0 / build 0 / eslint ✖ 5 (0 errors, 5 warnings)` 규칙·파일·라인까지 동일 → 신규 경고 0 확정). **병렬 편집 중 게이트가 필요한 이후 작업은 이 기법을 표준으로 쓸 것.** 요지 정정 3건 반영 완료(Settings `alert()` 6건 사실 정정·지속시간 일시정지 차이·인자 수치 55→66). 프로세스 지적 1건 — fixer가 동결 선언 후 검증 진행 중에 승인받은 2차분을 얹어 범위가 이동했고(verifier가 1차 결과 폐기·전 항목 재실행으로 무결성은 확보), **범위 확장 시 검증 요청 철회 후 재요청**이 규율. 미검증: 런타임·시각 검증 없음(FE 테스트 러너 부재), 라이브 미배포 — 배포 후 소싱 설정 저장·배치 실행·상품 상세 저장·상품 등록 크롤링 4화면 우하단 토스트 표출 확인 필요. 판정서 `_workspace/verify/D-180e_verdict.md`
- **D-180d(`OrderGrid.tsx` 분해) 수정완료(검증대기)** (2026-08-23, fixer-d180d): 구조 변경 전용 — 화면 동작 불변, 추출은 텍스트 그대로 이동(이름·시그니처·로직 변경 0). **1,793줄 단일 파일 → `frontend/src/pages/order/` 8파일 1,835줄**, 본체는 **700줄(-61%)**: `types.ts` 24(타입 6종)·`constants.ts` 95(상수·스타일 토큰 19)·`helpers.ts` 93(순수 헬퍼 9종, 캐시 패처 포함)·`cells.tsx` 244(인라인 편집 셀 5종)·`OrderTableRow.tsx` 66(행 렌더링 — rowSpan 병합·프리즈·주문 경계선)·`OrderFilterPanel.tsx` 240·`orderColumns.tsx` 373(컬럼 정의 15개+`columnHelper`)·`OrderGrid.tsx` 700(페칭·낙관적 뮤테이션·SSE·동기화/배치 핸들러·테이블 조립). 파일 위치는 `pages/product/ProductGrid.tsx` 관례를 따라 디렉터리 안으로 이동, `App.tsx` 임포트 1줄만 갱신(라우트 `/orders` 불변). **동등성 실증** — ① 7개 조각 전부 원본 줄 범위와 `diff` **0**(`export` 키워드만 정규화 후 비교), 본체도 원본 764–1213↔신규 22–471은 차이 1줄(파일 이동으로 불가피한 동적 임포트 경로 `../utils/orderExcelExport`→`../../…`, tsc TS2307이 잡아준 지점)·1568–1793↔475–700은 차이 0. ② 순수 헬퍼는 `node --experimental-strip-types`(v26)로 **추출 전 코드(원본 78행+140–227행 슬라이스)와 배포되는 `helpers.ts` 바이트 복사본을 각각 로드**해 대조: `stockCellInfo` 6·`shortAccountLabel` 11·`statusBorder` 6·`formatThousands` 9·`parseThousands` 6·`marketSyncState` 10×9=90·캐시 패처 7 = **135케이스 0 mismatch**, 캐시 패처는 손대지 않은 항목의 **참조 유지**까지 양쪽 동일(이 성질이 깨지면 `OrderTableRow`의 `React.memo`가 전 행을 재렌더). `blurLeftToPage`만 `document.hasFocus()` 의존이라 노드 실행 제외(diff 0으로 갈음). ③ 유일한 구조적 변형인 컬럼 정의는 `buildOrderColumns(deps)`로 빼되 **`useMemo` deps 배열을 그대로 유지**(`[handleUpdate, commonCodes, getCommonLabel]`)해 배열 재생성 시점이 전과 동일. **본체 존치와 사유**: 동기화 핸들러 6종·배치 액션 4종은 `setIsSyncing`/`refetch`/`rowSelection`/`processedData`에 직접 붙어 있어 빼내려면 커스텀 훅이라는 **새 추상화 도입**이 필요 → 계약 밖, 다음 사이클 후보. 툴바·동기화 칩은 콜백 8개 왕복이 되레 읽기를 해쳐 존치. `processedData`/`visibleData`/`syncCounts`는 `rowCacheRef`와 얽혀 순수 추출 불가. 게이트: `tsc -p tsconfig.app.json --noEmit` exit 0 · `npm run build` 성공 · `npx eslint .` **✖ 5 problems (0 errors, 5 warnings)** — 전후 동수·동일 규칙, 파일 경로만 이동(OrderGrid exhaustive-deps 3·incompatible-library 1, ProductGrid incompatible-library 1). 주석 추가 0줄. **리더 지시 전제 1건 정정**: "OrderGrid 청크"는 원래 없었다 — `App.tsx`가 `lazy()` 8개와 달리 OrderGrid만 **정적 임포트**해 `index-*.js`에 실린다. 분해 후에도 동일하며 `통합 주문 관리`·`마켓 수동수정 필요`·`sbshop.orderFilter.open` 3문자열이 `index-*.js`에서 검출됨으로 번들 포함을 확인, 동적 임포트인 `orderExcelExport-*.js` 청크 분리도 유지. 후속 참고: D-180e가 남긴 `OrderGrid` toast 37건은 전부 `frontend/src/pages/order/OrderGrid.tsx`로 경로가 바뀌었다(react-toastify 호출 그대로). `_workspace/fixes/D-180d_fix.md`
- 상세: `docs/normalize/refactor-20260821/bugs-api.md` B-API-5·BL-API-3, `bugs-frontend.md` B8·D | 상태: 발견 (묶음 — **D-180a·b·c 검증통과**(커밋 400b6b1·413b97d·2b36dcf), 잔여: 스타일 4방식·라벨/색상 다중 정의·**`OrderGrid` 분해는 D-180d로 처리 완료(검증대기)** — 1,793줄→`pages/order/` 8파일·본체 700줄, **toast 이중화는 D-180e로 66건 치환 해소 완료·검증통과**(2026-08-23 verifier-p3c PASS) — antd `message` 사용처 저장소 전체 **0건**. 잔여는 ⓐ `pages/order/OrderGrid.tsx` 37건이 `notify` 래퍼를 경유하지 않는 스타일 일관성(이미 toastify라 이중화 아님·표시 거동 동일, D-180d 검증 통과 후 처리) ⓑ `Settings.tsx` 네이티브 `alert()` 6건 → **D-202로 정식 등재**(행위 변경이라 별도 사이클, 선행 조사 완료 — 블로킹 의존은 `:62` 1곳뿐), 음수 검증 인라인 2곳(`ProductSourcingController:83` costPrice·`ProductController.updatePriceStock` 가격)의 문구 통일은 행위 변경 사이클 후보)

- **사이클 D 완주(2026-08-23, verifier-p3d 양건 PASS · 배포 커밋 ee4741e · 라이브 검증 완료)**
  - **D-180d `OrderGrid.tsx` 분해**: 1,793줄 1파일 → `pages/order/` 8파일(types 24·constants 95·helpers 93·cells 244·OrderTableRow 66·OrderFilterPanel 240·orderColumns 373·본체 **700**). 추출은 텍스트 그대로 이동. 검증 — 멀티셋 라인 diff 차이 3종(동적 import 경로·`buildOrderColumns` 래퍼·export 1행)뿐이고 로직/이름/시그니처/JSX 변경 0행, memo 비교자 4조건 바이트 동일, 컬럼 15개 id·size·accessor 토큰 30개 순서 일치, 훅 30개 호출 순서·deps 15개 동일·조건부 훅 0, 헬퍼 216케이스 실행 대조 미일치 0, `orderColumns` 모듈 스코프 13개와 본체 지역 선언 충돌 0(조용한 리바인딩 없음). 라우트 `/orders` 불변, `App.tsx` 임포트 1줄.
  - **D-180e 알림 이중화 해소**: `utils/notify.ts` 신설(react-toastify 래퍼 4종), 10파일 **66건** 치환 → antd `message` 저장소 전체 **0건**. 검증 — AST 파싱으로 인자 문자열 66/66 일치·종류 변경 0, `toast.warn===toast.warning` 동일 함수 객체 실증, `Modal.confirm`/`<Modal>` pristine 2=현재 2(과잉 제거 아님). **OrderGrid의 toastify 직접 호출 37건은 이중화 구성원이 아니었음**(원래부터 toastify·같은 컨테이너) — 래퍼 미경유 스타일 일관성 항목으로 성격 분리, 잔여 ⓐ.
  - 부수 변화: 알림 표시가 소싱·설정·배치 화면에서 상단 중앙 → **우하단**으로 통일(의도된 변화). 지속시간은 3초이나 toastify는 `pauseOnHover`·`pauseOnFocusLoss` 기본 true라 읽는 중엔 유지된다(antd는 미정지) — "동일"이 아니라 "읽는 중 유지"가 정확한 서술. 번들 청크 레이아웃 변화(`message-*` 소멸·`notify-*` 신설 207kB·index 832,038→798,238B)는 라이브러리 교체에 따른 배치 변화이며 분해로 인한 청크 변화는 0.
  - 라이브 검증(2026-08-23 15:5x KST, 리더 직접): 분해 후 주문 화면 완전 렌더(필터 패널·프리셋 5개·동기화 점 7개·툴바·인라인 편집 셀·그리드 행) 및 검색 동작 정상, 콘솔 오류 0. 소싱 자동화 설정에서 저장 실행 → **"설정을 저장했습니다" 토스트가 우하단 표출**(문구 동일·위치 통일 확인).
  - 검증 기법(표준 채택): **격리 트리 게이트** — `git archive HEAD`로 pristine을 뽑고 대상 파일만 덮어쓴 트리에서 게이트를 돌려, 병렬 fixer 편집이 섞이지 않은 상태로 "신규 경고 0"을 그 작업에 100% 귀속시킨다.
  - 잔여: ⓐ OrderGrid 37건 `notify` 경유 전환(+ 전환 후 `no-restricted-imports`로 재발 방지 검토) ⓑ [[D-202]] 네이티브 `alert` 6건 ⓒ 스타일 4방식·페칭 3패턴·라벨/색상 다중 정의.

### D-207: 상품 1592가 스마트스토어에서 판매중지 상태이며 판매가도 로컬과 다르다 (2026-08-24, D-206 라이브 검증 중 발견 — 운영 확인 항목)

- 심각도: P2(추정 — 파급 범위 미측정) | 위치: 운영 데이터(코드 결함 아님)
- 증상: 상품 1592(220227IHB052)의 로컬 DB는 `sale_price=70600`·`stock_status=IN_STOCK`인데, 스마트스토어 실제 등록값은 `salePrice=68200`·`statusType=SUSPENSION`(판매중지). 즉 **우리는 판매중으로 알고 있으나 스토어에서는 팔리지 않는 상태**이고 가격도 2,400원 차이가 난다.
- 발견 경위: [[D-206]] 수정 후 마켓 실시간 조회가 처음으로 정상 동작하면서 드러남 — 이 UI를 만든 목적(로컬 기록 vs 마켓 실물 대조)이 첫 사용에서 실증된 사례.
- **미측정**: 같은 불일치가 몇 개 상품에 있는지 전수 조사하지 않았다. 스토어 API를 상품마다 호출해야 해 비용이 있으므로 사용자 판단 필요. 판매중지 상품이 다수면 매출 직결 사안.
- 수정 방향(미착수): ① 전수 대조 배치(스토어 등록 상품의 statusType·salePrice를 로컬과 비교해 리포트) ② 불일치 발견 시 교정 경로 정의(마켓 값을 정본으로 볼지, 로컬을 정본으로 볼지는 항목별 판단)
- 상태: 발견 (사용자 확인 대기)

### D-208: 11번가 상품조회 API가 인증을 거부한다 — `-997 등록된 API 정보가 존재하지 않습니다` (2026-08-24, D-206 라이브 검증 중 발견)

- 심각도: P3(외부 요인 — 우리 코드 결함 아님) | 위치: `ElevenstMarketClient.extractMarketItem` → `/rest/prodservices/productinfo/{prdNo}`
- 증상: [[D-206]]으로 식별자 해석을 `prdNo`로 정정한 뒤에도 11번가가 `<resultCode>-997</resultCode><resultMessage>등록된 API 정보가 존재하지 않습니다.</resultMessage>`로 거부한다. 상품 1592의 `prdNo=4193852605`는 DB에 실재하며 주문 동기화 등 다른 11번가 API는 정상 동작하므로, **이 엔드포인트(상품조회 서비스)에 대해 API 키가 등록·승인되지 않은 것**으로 판단된다.
- 부수 발견(코드 결함): `ElevenstMarketRestClient.sendRequest:59-66`이 **HTTP 상태를 통째로 버린다** — `responseCode >= 400`이면 에러 본문을 그대로 문자열로 반환하고 `IOException`도 합성 XML로 삼켜 **어떤 경우에도 예외를 던지지 않는다**. 그래서 인증 거부가 "성공했으나 값이 비어 있음"으로 둔갑한다(D-206 전에는 이것이 전 항목 '마켓 미제공'으로 표시돼 조용한 오답을 만들었다). 다른 11번가 경로도 같은 클라이언트를 쓰므로 **거짓 성공 위험이 11번가 전반에 잠재**한다 — 쿠팡 봉투 검사([[D-181]])와 같은 계열의 문제.
- 수정 방향(미착수): ① 11번가 셀러오피스에서 상품조회 API 사용 신청·승인 확인(사용자 조치 필요) ② `ElevenstMarketRestClient`에 `resultCode` 검사와 HTTP 상태 검사를 추가해 거짓 성공을 차단(D-181 방식 준용 — 핫패스 방어적/콜드패스 엄격)
- 상태: 발견 (①은 사용자 조치, ②는 코드 수정 후보)

### D-209: 쿠팡 678건이 마켓 반영 불가 — 쿠팡이 판매중지시킨 상품 + 존재하지 않는 옵션ID (2026-08-24, 가격 배치 중 발견)

- 심각도: P1(매출 직결 — IHB 쿠팡 등록 994건 중 **678건이 반영 불가**) | 위치: 운영/외부 요인 + 일부 데이터 결함
- 발견 경위: 가격 정책 배치(`6cd60ded` 15/20/5000 → `7f158465` 5/0/1500, 각 2,146건 전량 성공) 후 마켓별 `is_synced` 집계에서 쿠팡만 316/678로 비정상. 배치 자체는 실패 0건이라 **집계를 보지 않았으면 드러나지 않았을 문제**.
- **원인 ① 쿠팡의 판매중지 (60분 로그에 945회, 압도적 다수)**: `400 Bad Request {"code":"ERROR","message":"판매재개에 실패했습니다. [옵션ID(87763025801)은 쿠팡에 의해 '판매중지'된 상품입니다. 자세한 중지 사유와 재개 방법은 판매자님의 대표 메일로 전달되었으니 확인 부탁드립니다. 판매 재개 요청을 위해 수신하신 쿠팡 메일 계정으로 회신 부탁드립니다.]"}`. 가격/재고 동기화가 판매중 상태로 되돌리려 할 때마다 거부된다. **사용자 조치 필요** — 쿠팡이 대표 메일로 보낸 중지 사유 확인 후 회신(스팸함 확인, WING>온라인 문의 경로).
- **원인 ② 존재하지 않는 옵션ID (123회)**: `가격변경에 실패했습니다. [vendoritemid 89379330764 not found]` — 로컬 `market_identifiers.vendorItemId`가 쿠팡에 없음(상품 삭제 또는 옵션ID 변경). **코드/데이터로 해소 가능** — 쿠팡 seller-products 재조회로 식별자 재수집하는 백필 필요([[product-grid-market-links]]의 백필 트리거 확장 후보).
- 연관: 스마트스토어도 상품 1592가 `SUSPENSION`([[D-207]]) — **복수 마켓 동시 판매중지 정황**이나 전수 파악 미실시.
- 수정 방향(미착수): ① 사용자의 쿠팡 메일 확인·재개 요청 ② 옵션ID 재수집 백필 ③ **마켓별 판매중지/식별자 유효성 전수 리포트 배치** — 지금은 `is_synced` 집계를 사람이 눈으로 봐야만 드러난다. 배치 완료 시 마켓별 실패 사유 요약을 남기는 것이 근본책.
- 상태: 발견 (①은 사용자 조치 대기, ②③은 코드 후보)

### D-213: 마켓 판매 상태 대량 이상 — 스토어 판매중지 2,229건 · 쿠팡 승인완료 24건 (2026-08-25, D-210 리포트 첫 실행으로 확정)

- 심각도: **P1(매출 직결)** | 위치: 운영 데이터(코드 결함 아님)
- 근거: [[D-210]] 대조 리포트 라이브 첫 실행(배포 `3e43a0bc`, 2026-08-25 22:5x KST). 마켓 원문 상태 분포:

| 마켓 | 판매 가능 | 판매 불가 |
|---|---|---|
| 카페24 (3,184) | `selling=T` **2,635** | `selling=F` **549** |
| 스마트스토어 (3,237) | `SALE` **936** | **`SUSPENSION` 2,229** · `PROHIBITION` 10 · `OUTOFSTOCK` 62 |
| 쿠팡 (1,167) | `승인완료` **24** | **`임시저장` 1,029** · `승인반려` 100 · `심사중` 14 |

- **스마트스토어**: 3,237건 중 **2,229건(69%)이 SUSPENSION**. 표본 1건([[D-207]] 상품 1592)으로만 알던 것이 전수로 확정됐다. `PROHIBITION` 10건은 별도 사유(판매금지).
- **쿠팡**: `승인완료`가 **24건뿐**. [[D-209]]의 "판매재개 실패 945회"와 정합한다. **단 해석 주의** — 이 상태값이 "최근 수정본의 심사 상태"일 가능성이 있어, 과거 승인된 리스팅이 노출 유지 중일 수 있다. 반증: 같은 날 가격 배치에서 쿠팡 315건이 정상 반영됨(is_synced=t). **WING 실물 확인으로 확정 필요.**
- **카페24**: 549건 `selling=F`. 세 마켓 중 가장 양호하나 무시할 수치는 아니다.
- 수정 방향(미착수): ① 마켓별 판매중지 사유 확보(쿠팡은 대표 메일, 스토어는 판매중지 사유 API/센터) ② 재개 가능분 일괄 재개 ③ 정기 상태 감시 — 지금은 리포트를 사람이 돌려야만 드러난다
- 상태: 발견 (사용자 확인·판단 대기)

### D-214: 마켓↔로컬 식별자 불일치 실측 (2026-08-25, D-210 리포트 첫 실행)

- 심각도: P2 | 위치: `sb_market_registration.market_identifiers`
- 사용자 요구("우리 3천개면 카페24도 3천개여야 한다 / 마켓에만 있는 것과 우리에만 있는 잘못된 식별자를 파악하라")에 대한 **실측 답**:

| 마켓 | 로컬 | 마켓 | MATCHED | IDENTIFIER_MISMATCH | MISSING_LOCAL(마켓에만) | STALE_LOCAL(우리에만) | DUPLICATE_MARKET |
|---|---|---|---|---|---|---|---|
| 카페24 | 3,186 | 3,184 | 3,184 | 0 | 0 | **2** | 0 |
| 스마트스토어 | 3,186 | 3,237 | 3,141 | **43** | **10** | **2** | **43** |
| 쿠팡 | 1,262 | 1,167 | 1,156 | 5 | 6 | **101** | 0 |

- **카페24는 사실상 완전 동기** — 사용자 가설("3천개=3천개")이 카페24에서는 성립한다.
- **쿠팡 STALE_LOCAL 101건**이 최대 교정 대상. [[D-209]]의 `vendoritemid not found` 123건과 겹칠 개연성이 높다.
- **스토어 DUPLICATE_MARKET 43건**: 한 로컬 행에 마켓 리스팅 2개 이상. 중복 등록 실물.
- **11번가는 대상에서 제외됨**(2,286행) — [[D-208]] 인증 거부 + 카탈로그 요청 형식 미검증으로 [[D-210]]에서 의도적 비활성화. **"0건"이 아니라 "미판정"이다.**
- **리포트가 볼 수 없는 영역(블라인드 스팟)**: 쿠팡 `externalVendorSku`가 우리 sb_code와 다른 **31건**은 이 리포트에 뜨지 않는다(목록 응답에 그 필드가 없어 diff 대상 밖 + `sellerProductId` 조인이 성립해 MATCHED로 떨어짐). 별도 DB 질의가 올바른 도구다.
- **교정 시 안전 규칙(검증자 제시 — 반드시 준수)**: ① note에 `미확정` 포함 행은 삭제 후보 제외(조회 실패지 부재 아님) ② `FAILED` 마켓은 "비었음"이 아니라 "조회 불신" — 통째 제외 ③ 11번가 2,286행에 판단 금지 ④ `IDENTIFIER_MISMATCH` 중 로컬값이 null이면 삭제가 아니라 **백필** 신호 ⑤ `deep`은 현재 쿠팡에서만 유효
- 상태: 발견 (교정 범위 사용자 판단 대기)

### D-215: 쿠팡 임시저장 1,029건 — D-092 잔재, 팔리는 중이나 최신 수정본 미제출 (2026-08-26 조사 확정)

- 심각도: P2(매출 중단 아님 — 아래 ② 참조) | 위치: 운영 데이터(현행 코드 결함 아님)
- **① 원인 확정**: 쿠팡 상품수정(승인필요) PUT의 `requested`가 빠지면 쿠팡 기본값 `false`("작성 내용만 저장")가 적용돼 **판매 중이던 상품이 미제출 초안(임시저장)으로 떨어진다.** 이미 [[D-092]](2026-07-20)에 sellerProductId까지 특정해 재현·기록된 사실이다. **현행 코드는 해소됨** — 등록 `CoupangProductPayload:182` `.requested(true)`, 재게시 `CoupangMarketClient:249` `rawData.put("requested", true)`, 회귀 가드 `CoupangMarketClientImagesTest:91`.
- **1,029건의 출처(추론, 타임스탬프 정합)**: 쿠팡 등록행 `created_at`이 **2026-02-28 하루에 1,261건**, 임시저장 표본의 쿠팡 `saleStartedAt`이 전부 **2026-02-28 20:57~23:29**로 리셋(승인완료 표본 2591·3133은 2023-04-24 보존). 저장소 최초 커밋이 2026-06-15라 당시 코드는 없다 — 레거시 대량 PUT에서 `requested` 누락으로 코호트 전체가 초안화된 것으로 판단. **직접 증명 불가**(확정 항목 아님). 방증: D-092 수정 후 재게시된 3058·3095·186은 승인완료로 이동.
- **② 결정적 정정 — 임시저장은 "안 팔림"이 아니다**: `statusName`은 **등록상품(마스터)의 심사 상태**이고 노출상품(`productId`)·옵션(`vendorItemId`)의 판매 상태와 별개 객체다. 쿠팡 문서는 "임시저장이면 `vendorItemId`는 null"이라 하는데 **운영 임시저장 15건 전부 노출상품ID·옵션ID·실판매가·재고를 보유**(표본 361건 100% 노출상품ID 보유, 예외는 2026-08-22 신규 3194 하나). 즉 **과거 승인분이 계속 팔리는 위에 미제출 초안이 얹힌 상태**. 방증: [[D-209]] 거부 메시지의 옵션ID(87763025801)가 임시저장 표본 대역과 일치 / 2026-08-24 가격 배치가 `vendor-items`로 315건 정상 반영.
- **→ [[D-213]]의 "쿠팡 승인완료 24건뿐" 해석을 이 절로 교정한다.** 사용자 증언("전부 비노출은 아니고 노출중은 맞다")과 일치.
- **③ 해소 경로**: `PUT .../seller-products/{sellerProductId}/approvals`(본문 없음). 적용 대상이 **임시저장·승인반려·부분승인완료**라 지금 1,029건이 정확히 대상이다. 우리 코드에 호출 없음 — D-092 당시엔 대상이 승인완료라 `"'임시저장' 상태의 상품만 승인 요청 가능합니다"`로 거부됐고(`ledger:1350`) 테스트가 미호출을 고정(`CoupangMarketClientImagesTest:78`). **당시 부적합했던 API가 지금은 적합하다.** 400 "등록/수정 중" 시 10분 후 재시도 규칙. `/partial`(승인불필요)은 "임시저장·승인대기중 상품 수정 불가"라 우회로 아님.
- 사람 몫: MD 심사 자체 / 승인반려 100건의 반려 사유 확인(WING 또는 `GET .../seller-products/{id}/histories`) — 사유 없이 재제출하면 반복 반려. [[D-209]] 판매중지(쿠팡 메일 회신)는 **별개 축이니 섞지 말 것**.
- **④ 승인 요청 파일럿 도구 신설(2026-08-27, tdd-fixer)**: 표본 몇 건으로 "임시저장 → 승인완료"가 실제로 되는지 먼저 확인하기 위한 **명시 대상 전용** 도구. **라이브 실호출 미수행**(목 테스트만) — 배포 후 리더 표본 실행이 첫 검증.
  - API: `POST /api/v1/products/coupang/approval-requests` 본문 `{"sellerProductIds":[...], "throttleMs":900}`. **벌크 스윕 경로 없음** — 대상은 본문 ID 배열로만 지정되고 **상한 20건**(초과·빈 배열·본문 누락은 400, 서비스·마켓 호출 0회로 고정). 중복 ID는 합치고 요청 순서 유지.
  - 상태 게이트: 대상마다 `GET seller-products/{id}` 로 `statusName` 을 먼저 읽어 **임시저장·승인반려·부분승인완료일 때만** 호출한다. 그 밖의 상태·`statusName` 미상은 **호출하지 않고** `SKIPPED`(사유 기록), 404 도 `SKIPPED`, 조회 실패는 `FAILED`(호출 안 함). D-092 의 `"'임시저장' 상태의 상품만 승인 요청 가능합니다"` 거부가 이 게이트로 차단된다.
  - 봉투 화이트리스트: `code` 가 `SUCCESS`(대소문자 무시)·문자열 `200` 일 때만 성공. `code=ERROR`·code 부재·JSON 아님·본문 null 은 전부 `FAILED`. 근거는 [[D-181]] 라이브 실측(성공 응답에 `code:"SUCCESS"` 실존 / HTTP 200 + `code:"ERROR"` 실패 실존) — "예외 안 났으니 성공"이 성립하지 않는 마켓이다([[D-208]]·[[D-212]] 계열).
  - 400 `등록/수정 중` 은 `RETRYABLE` 로 분리(note 에 10분 규칙). **자동 재시도 없음** — 사람이 판단. HTTP 400 예외 체인과 200+`code=ERROR` 봉투 **양쪽 전송 형태**를 다 잡는다.
  - 건별 결과 반환: 대상 ID·사전 상태·호출 여부·`code`·`message`·판정(`REQUESTED`/`SKIPPED`/`RETRYABLE`/`FAILED`)·사유. 요약 카운트는 items 에서 파생한다.
  - 쓰로틀: 상품당 2콜(GET+PUT)이라 **기본·하한 600ms**(3.3콜/초, 쿠팡 5콜/초 한도 아래). **낮출 수 없고 올릴 수만 있다.** 실패는 그 건만 기록하고 계속. 활동로그 `PRODUCT_APPROVAL_REQUEST` 는 **실제로 쏜 건만** 기록(건너뜀은 마켓 쓰기가 아님).
  - 포트: `MarketClient.supportsApprovalRequest()`(default false)·`requestApproval()`(default 미지원 예외), 쿠팡만 구현. **[[D-092]] 회귀 가드 보존·강화** — `CoupangMarketClientImagesTest` 의 "재게시는 `/approvals` 미호출" 3건에 `requestWithBody("PUT", …)` never 를 덧붙였다(승인 요청이 `put()` 이 아닌 `requestWithBody()` 로 나가므로 덧붙이지 않으면 기존 단언이 자동 참이 된다).
  - 게이트(격리 빌드): core **873**(862→+11) · infra **306**(284→+22) · api **202**(198→+4) · worker **72**, 실패·에러 0, 4모듈 `compileJava`·`spotlessCheck` 통과(`spotlessApply` 미실행, 위반 2건 수동 교정).
  - 요지·표본 실행 예시: `_workspace/fixes/D-215_approval_fix.md`
- **⑤ 라이브 1차 실행과 411 전건 실패 → 전송 결함 수정(2026-08-27, 리더 실행 + tdd-fixer 수정)**
  - 리더 실측(배포 `4293edf4`): 임시저장으로 저장된 6건 중 **라이브 실상태는 심사중 3·승인완료 3**이라 도구가 전부 정확히 건너뛰었고, 임시저장 확인된 4건(`14813282340`·`14813282123`·`14813282110`·`14813282095`)은 사전 상태 조회 4/4 정상 후 호출 4회 전부 `FAILED | code=null | 411 Length Required: "<HTML><HEAD>...`. **쿠팡이 거부한 게 아니라 우리 요청이 게이트웨이 앞단에서 끊긴 것.**
  - **부수 확인**: 저장된 `statusName`은 낡았다 — **표본 선정은 저장값이 아니라 라이브 카탈로그 기준**으로 해야 한다. 그리고 ④의 사전 상태 게이트·봉투 검사는 설계대로 동작했다(대상 아닌 상태 호출 0회 차단, 411을 성공으로 삼키지 않고 FAILED 보고).
  - **원인 정정 — `body == null`이라 Content-Length가 안 붙는 것이 아니다.** 실측 반증: 빈 본문(`new byte[0]`·`+application/json`·`""`) **세 형태 전부 411 그대로**. 진짜 실패 조건은 **HTTP/2 + 본문 길이 0인 PUT** — JDK `HttpClient`가 HTTP/2로는 길이 0 본문에 `content-length`를 싣지 않고 Akamai edge가 411로 끊는다. 평문 HTTP/1.1 로컬 서버·MockRestServiceServer로는 **재현 불가**(HTTP/1.1에서는 수정 전에도 `Content-Length: 0`이 붙는다).
  - **영향 범위 실측(미인증 호출로 판별 — 자격증명 없어 쓰기 불성립)**: GET 본문없음 통과 / **DELETE 본문없음 통과**([[D-181]] 전환분 무사) / 본문 있는 POST·PUT 통과(라이브 등록 성공 이력과 일치) / **본문 없는 PUT만 411**. 대상 호출부는 승인 요청 `CoupangMarketClient:417` 하나뿐.
  - 수정: `CoupangRestClient`에 **HTTP/1.1 고정 `RestClient`(`bodilessWriteClient`)** 를 두고 `body == null && method ∈ {POST,PUT,PATCH}`일 때만 사용. 그 외 경로는 전부 무변경. **전역 HTTP/1.1 전환은 하지 않았다** — 본문 있는 요청은 HTTP/1.1에서 chunked로 나가 잘 도는 등록 경로의 전송 형태가 바뀌기 때문. `{}` 같은 비어있지 않은 본문을 넣는 우회도 411은 뚫지만, 쿠팡이 "본문 없음"으로 문서화한 엔드포인트에 문서 밖 본문을 넣는 것이라 채택하지 않았다.
  - **HMAC 무영향**: `generateSignatureUtc(method, url, accessKey, secretKey)`는 본문 인자가 없고 서명 문자열이 `datetime+method+path+query`뿐(`CoupangHmacUtil:31`). 미인증 실측이 `Specified key is not registered.`(키 미등록)로 답한 것 = 서명 파싱 정상.
  - **Red/Green 라이브 실측(동일 클래스, 미인증, 존재하지 않는 id `0`)**: 수정 전 `PUT null → 411 Length Required`, 수정 후 `PUT null → 401 Specified key is not registered.` = edge 통과. 단위 Red는 `Upgrade: h2c` 유무로 고정(`expected: null but was: "h2c"`, 7건 중 2건 실패 → 수정 후 7/7).
  - 신규 회귀 `CoupangRestClientWireTest`(7건) — 로컬 HTTP 서버로 **실제 나간 요청 헤더·본문**을 검사한다(목 호출 횟수 세기 아님). DELETE·본문 있는 POST/PUT·GET의 전송 형태 무변경을 함께 고정.
  - 게이트(격리 빌드): core **873** · infra **306→313**(+7) · api **202** · worker **72**, 실패·에러 0, 4모듈 `compileJava`·`spotlessCheck` 통과(`spotlessApply` 미실행, 위반 2건 수동 교정).
  - 요지: `_workspace/fixes/D-215_411_fix.md`
- 상태: **④ 도구 수정완료 + ⑤ 411 전송 결함 수정완료(검증대기)** — 해소 경로 확정. **배포 후 표본 재시도가 첫 실검증**(411이 아니라 쿠팡 봉투 `code`가 돌아와야 한다). 실제 1,029건 해소는 그 결과를 보고 판단하며, [[D-216]] ②(PUT 바디를 현재 정책값으로 덮어쓰기)는 여전히 미착수다.

### D-216: 쿠팡 승인필요 PUT이 레거시 초안 전문을 되돌려 가격 롤백 위험 (2026-08-26, 바코드 계획 검토 중 발견)

- 심각도: **P1(실행 시 매출 직접 훼손)** | 위치: `CoupangMarketClient.syncImagesAndHtml:207-256`
- 증상: 이 경로는 **GET한 전문을 통째로 되돌려 PUT**한다. [[D-215]] 임시저장 상품의 GET은 **2026-02-28 레거시 초안**을 반환하므로, 승인이 나면 바코드만이 아니라 **초안 전체가 현재 라이브를 덮어쓴다.**
- **실측(로컬 정책가 vs 쿠팡 초안 salePrice, 15건 중 13건이 초안이 높음)**: 200908WA007 50,100→61,400 / 210125IHB040 46,800→50,500 / 231108IHB012 30,700→32,400 / 231114IHB059 40,400→43,800 / 231114IHB096 33,400→36,500. 원인은 [[D-209]] 가격 배치가 `vendor-items`(옵션)만 갱신하고 등록상품 초안은 안 건드려 초안이 배치 이전 값에 머문 것.
- 문서 근거: 승인필요 PUT은 "판매가격 수정은 **상품 승인 요청 전에만 가능**" — 임시저장은 승인요청 전 상태라 초안 `salePrice`가 승인 시 적용될 수 있다. `maximumBuyCount`(재고)도 동일 위험.
- **되돌릴 수 없다**: 승인불필요 API가 `400 승인 대기중 상품은 수정할 수 없습니다`를 반환 — 한 번 제출하면 심사 종료까지 추가 수정 불가. **1,029건 일괄 실행 = 최악의 경우 가격 인상 롤백 1,029건.**
- 수정 방향(미착수): ① **선행** — `GET .../vendor-items/{vendorItemId}/inventories`로 현재 실판매가·재고 조회(우리 코드에 이 GET 없음, [[D-210]] 리포트에 붙이는 것이 자연스러움. 그러면 1,029건이 "실판매중 vs 진짜 미노출"로 전수 판별되는 부수효과) ② PUT 바디의 `salePrice`·`maximumBuyCount`를 **초안 값이 아니라 현재 정책값·현재 재고로 덮어쓴 뒤** 전송 → 승인돼도 가격이 튀지 않고 임시저장 해소+바코드 반영이 동시 달성 ③ **5~10건 파일럿 후 확대**(10분 재시도·쓰로틀). 같은 코호트 승인반려 100건(sellerProductId 대역 14.3~14.8B 중첩)은 초안 자체 문제 개연성 — [[D-182]]·[[D-183]] "유효하지 않은 구매 옵션 값 혹은 단위" 계열 유력.
- **선행 ① 완료(2026-08-26, tdd-fixer)**: 쿠팡 현재 실판매가·재고 조회를 신설하고 [[D-210]] 리포트에 편입했다. **읽기 전용** — 쓰기 동사 미추가.
  - `GET .../vendor-items/{vendorItemId}/inventories` → `data.salePrice`(실판매가) · `data.amountInStock`(재고) · `data.onSale`(판매중) · `data.sellerItemId`. 필드는 쿠팡 공식 문서로 확정, **라이브 실호출은 미수행**(이 세션에서 운영 SSH·DB 접근이 차단됨) — 배포 후 `liveLimit=5` 표본 1회가 첫 검증 항목.
  - **지시·D-216 본문 정정**: 초안가·`vendorItemId`는 **카탈로그 목록에서 얻을 수 없다.** 쿠팡 "상품 목록 페이징 조회" 응답에는 `salePrice`도 `vendorItemId`도 아이템 레벨 정보도 없다(`sellerProductId·productId·statusName·saleStartedAt·brand·createdAt` 등뿐). 그래서 옵션ID는 **로컬 `market_identifiers.vendorItemId`**, 초안가는 **`GET seller-products/{sellerProductId}` 신규 호출**(= 이 절의 PUT이 직전에 수행하는 바로 그 GET)로 조달한다. 결과적으로 **상품당 2콜**.
  - 리포트: `GET /api/v1/products/market-sync/report?liveInventory=true&liveLimit=N`. **옵션을 끄면 새 코드가 한 줄도 실행되지 않고 호출 0회**(테스트 고정), `liveLimit` 기본 50(표본이 기본)·최대 5000. 상품별 **① 로컬 정책가(`sb_product.sale_price`) ② 쿠팡 초안가 ③ 옵션 실판매가**를 나란히 싣고 `priceDiverged`·`localVsLiveDiverged`·`draftVsLiveDiverged`·**`draftAboveLive`(=승인 시 가격 인상 롤백 위험 건수)**를 집계한다.
  - 판별 3분류: `ON_SALE`(조회 성공+판매중) / `NOT_ON_SALE`(조회 성공+판매중지) / `UNDETERMINED`(옵션ID 없음·조회 실패·옵션 부재). 세 번째는 내부에서 `noOptionId`/`lookupFailed`/`optionAbsent`로 따로 세고 note는 전부 `미판정 —`으로 시작한다 — **조회 실패를 "미노출"로 단정하지 않는다**([[D-210]] 반복 오탐 차단).
  - 봉투: `verifyEnvelopeStrict`. `code != SUCCESS` · `SUCCESS`인데 `data` 없음 · 문서화되지 않은 4xx/5xx는 **전부 예외**. `Optional.empty()`(=옵션 부재)는 404와 **문서화된 400 두 메시지**(`유효한 옵션이 없습니다`/`유효하지 않은 ID가 입력되었습니다`)에서만 나온다.
  - 쓰로틀: 호출마다 `throttleMs`. 상품당 2콜이라 쿠팡 vendorId당 초당 5회 제한 기준 **`throttleMs=250` 이상 권장**(기본 200은 한도에 붙는다). 50건마다 진행 로그, 실패는 그 건만 기록하고 계속.
  - 게이트(격리 빌드 — 공유 트리는 바코드 fixer의 진행 중 Red로 `:core:test` 컴파일 불가): core **817**(806→+11) · infra **281**(266→+15) · api **193**(191→+2) · worker **72**, 실패·에러 0, `spotlessCheck` 4모듈 통과(`spotlessApply` 미실행).
  - 요지: `_workspace/fixes/D-216_live_fix.md`
- **검증 후속 조치 완료(2026-08-26, 검증 PASS 조건부 이후)**: 초안가 미상이 `draftAboveLive=0`으로 나타나 **"롤백 위험 없음"으로 오독**될 수 있는 거짓 안심 경로를 막았다([[D-181]]·[[D-208]]·[[D-212]] 계열).
  - **미상 사유 구분**: 포트 반환 타입을 `Optional<Integer>` → `MarketDraftPrice(salePrice, miss)`로 바꿔 사유를 실어 나른다 — `NO_ITEMS_FIELD`(구조 불일치) / `EMPTY_ITEMS` / `NO_PRICE_FIELD` / `PRODUCT_ABSENT`(404) / `NO_SELLER_PRODUCT_ID` / `LOOKUP_FAILED` / `UNSUPPORTED`. 쿠팡 구현은 앞 3건에 `log.warn`(이전엔 무성), 리포트는 `draftMissReasons` 맵 + `warnings`에 사유별 건수 노출.
  - **판정 규칙 2단**: `draftAboveLiveUnderstated`(=`draftUnknown>0`, 임계 없음) → "draftAboveLive 는 하한값, 0이어도 위험 없음이 아니다". `draftMeasurementUnreliable`(=`priceComparable==0` 또는 **미상률 ≥ 10%**) → **"초안가 측정 신뢰 불가 — draftAboveLive 를 '위험 없음'으로 읽지 말 것"**.
  - **임계 10% 근거**: 이 절의 실측 위험 발생률 **86.7%**(15건 중 13건). 미상률 10%면 놓친 위험 기대값 ≈ examined의 8.7% → **1,029건 코호트에서 약 89건**이 측정 밖에 남아 파일럿 규모(5~10건)보다 한 자릿수 크다. 이 측정으로는 일괄 실행을 정당화할 수 없다는 판단.
  - **초안가 비교 경계 회귀 고정**: `draft<live`(`draftBelowLive=1`·`draftAboveLive=0`) · `draft==live`(둘 다 0·`draftVsLiveDiverged=0`) · `draft>live`(기존).
  - **`DEFAULT_THROTTLE_MS` 200 → 250**: 상품당 2콜 × 200ms = 정확히 5콜/초로 쿠팡 vendorId 한도에 여유 0. 기본값을 안전한 쪽에 두고 필요 시 파라미터로 낮춘다.
  - 게이트(격리 빌드): 내 증분만 core **827**(+21) · infra **284**(+18) · api **194**(+3) · worker 72 / 합본 **core 862 · infra 284 · api 198 · worker 72**, 실패·에러 0, `spotlessCheck` 4모듈 통과.
- 상태: **선행 ① 수정완료 + 검증 후속 반영 완료(재동결, 배포 가능)** — ②(PUT 바디의 `salePrice`·`maximumBuyCount`를 현재 정책값·현재 재고로 덮어쓰기)·③(파일럿) 미착수. **바코드 소급·승인요청은 여전히 착수 전**

### D-217: 바코드가 전 상품 미보유 — 수집은 되나 상품 생성에서 버려짐 + 쿠팡 규약 미준수 (2026-08-26, 쿠팡 공지 대응)

- 심각도: P2(쿠팡 공지 대응 필요) | 위치: 다층
- 실측: `sb_product.barcode` **3,195건 전부 공란**(IHB 2,146·OCD 407·FTN 393·VTB 102·TES 98·COK 48). 쿠팡 `market_identifiers`의 `"barcode"` 키는 **1,261건 전부 빈 문자열** — 우리가 보낸 게 아니라 `CoupangDataMapper:21`이 쿠팡 GET 응답을 되받아 적은 것.
- **① 수집은 이미 동작한다**: `scraper/scrapers/iherb.py:419` `upc=spec.get("UPC 코드")` → `models.py:83` → `ScraplingIherbClient:103` → `ProductDetailDto.upc` → `DraftEnrichmentUseCase:93` `.barcode(detail.upc())` → `sb_product_draft.barcode`까지 저장.
- **② 버리는 지점**: `DraftPublishUseCase:103` `toCreateCommand(draft)`가 barcode를 넘기지 않고, **`ProductCreateCommand`에 barcode 필드 자체가 없다**. 그리고 `Product.java:366` `createProductSpec()`이 `.barcode("")` 하드코딩 → **신규 상품은 예외 없이 빈 바코드로 태어난다.** 덧: 운영 `sb_product_draft` **0행**(소싱 파이프라인 미사용) — 실제 수집된 바코드는 지금까지 0건.
- **③ 저장 자리는 완비**: `sb_product.barcode varchar(100)`·`ProductSpec:23`·`ProductUpdateCommand:25`·`Product.updateProductSpec:277-291`·`ProductUpdateRequest:24`·프론트 `ProductDetailModal:341` 편집 필드. **DDL 변경 불필요**, 지금도 수동 입력 시 저장된다. 없는 것은 자동 채움 경로·형식 검증(UPC-A 12/EAN-13 13 + 체크디짓)·중복 감지.
- **④ 쿠팡 규약 미준수**: `CoupangProductPayload.Item`에 barcode 필드가 **없다**(전 필드 확인). 쿠팡 스펙은 `barcode` / `emptyBarcode`(없으면 true) / `emptyBarcodeReason`(최대 100자) **3필드 세트**이며 "없으면 그냥 비움"이 아니라 **`emptyBarcode=true`+사유 명시가 정규 경로**다. 바코드 변경은 **승인필요 PUT에서만** 가능(승인불필요 문서에 barcode 없음) → [[D-216]] 위험과 직결.
- **⑤ 수급 실측**: iHerb 운영 스크래퍼 실호출 **2/2 성공**(`068958016375`·`695927121213`, UPC-A 12자리). 단 **건당 71.2초**(`iherb.py:143` `DynamicFetcher network_idle=True` 대기) → 2,146건 직렬 **약 42시간**. 빠른 `catalog.app.iherb.com`(0.2초)에는 UPC 없음, 하위 엔드포인트 4종 404/500, plain curl은 **CF 403** → 브라우저 렌더링이 유일 경로. 병렬 4~6 추정 시 7~11시간, 대기 최적화 성공 시 3~6시간(실험 1회 선행 권장).
- **⑥ 타 소싱처**: **VTB 102건 즉시 가능**(Shopify variant JSON에 EAN-13 노출, 브라우저 불필요 — `5021265246335` 등 실측). FTN 393·COK 48은 페이지에 바코드 패턴 0건, OCD 407(봇 챌린지 202)·TES 98(403)은 확인 불가. → 이들은 쿠팡 `emptyBarcode=true`+사유가 현실적.
- 미확인: 쿠팡 공지 원문(필수 강제 시점·조건) / iHerb 2,146건의 UPC 실보유 비율(백필 전 50~100건 표본 권장) / 스크래퍼 동시 실행 상한 / 스토어·11번가·카페24의 바코드 요구 여부(우리가 안 보내는 것만 확인됨)
- 수정 A (2026-08-26, fixer): **신규 상품 구멍 메움** — `ProductCreateCommand`에 `barcode` 컴포넌트 추가, `DraftPublishUseCase.toCreateCommand`가 `draft.getBarcode()` 전달, `ProductCreateUseCase.enrichWithHostedImages` 재조립 시 보존, `Product.createProductSpec`의 `.barcode("")` 하드코딩을 커맨드 값(검증 통과 시)으로 교체. 위치인자 생성 호출부 **27곳** 전수 조정(main 3 · core 테스트 16 · infra 테스트 8).
- 수정 B (2026-08-26, fixer): **형식 검증** — `core/domain/product/service/BarcodeValidator`(정적 유틸) 신설. 공백·하이픈 제거 → 숫자만 → **자릿수 8/12/13** → GS1 mod-10 체크디짓. 근거: iHerb=UPC-A 12·VTB=EAN-13 실측, EAN-8은 동일 산식의 정규 소매 코드라 허용, **GTIN-14는 물류 박스 코드라 체크디짓이 맞아도 거부**(`10068958016372`로 테스트 고정). 세 저장 경로 공통 적용 — 생성=저장 안 함+warn, 수정=**기존 값 유지**+warn(빈 문자열은 수동 삭제로 보고 비움), 백필=저장 안 함+ProcessStatus 사유. UPC-E는 산식이 달라 사실상 거부됨(두 소스가 안 내보내는 것이 실측 — 알려진 한계).
- 수정 C (2026-08-26, fixer): **백필 배치** — `ProductBarcodeBackfillService`(`findTargets(vendor, limit)` + `@Async backfillBarcodes`), `ProductRepository.findBarcodeBackfillTargetIds()`/`(vendor)`(공란 바코드 + 소싱 URL 보유만), `JobType.BACKFILL_BARCODE`·`ActionLogConstants.BATCH_BACKFILL_BARCODE`, `POST /api/v1/products/batch/backfill-barcode {supplierCode, limit}` → `{batchId, count, message}`(D-180a 키셋). 크롤은 지시대로 `ProductDetailCrawlerPort.fetchDetail`(=`ScraplingIherbClient`) 경유. 멱등(보유분 스킵)·실패 격리·완료 이벤트로 JobType 가드 해제. 스로틀 VTB 1,500ms / 그 외 300ms.
- **속도 실험 (③⑤ 해소)**: `network_idle=True` → `wait_selector="#product-specs-list"` 교체로 **68.4s → 5.8s (11.8배)**. 동일 상품 3건×3설정 실측이며 UPC 3/3 동일. 전체 `fetch_detail` 출력 13개 필드(성분·이미지수·specs키·설명/사용법/주의 길이 포함) **전 필드 IDENTICAL**(2상품). `disable_resources=True` 추가는 0.4s 이득뿐이라 미채택. 셀렉터 미출현 페이지에서 Scrapling은 **예외를 던지지 않고 페이지를 그대로 반환**(404 URL 16.5s→status 404 보존, 목록 페이지 21.5s→200) → 기존 차단 감지·404 분기 무영향, 셀렉터 타임아웃은 **40s**(아래 후속 ① 참조). **적용은 `fetch_detail`에만** — 목록 크롤·가격재고 크롤(`IherbScraper.scrape`)은 무변경(후속 후보).
- **검증 후속 ① (2026-08-26, qa-verifier 조건부 PASS 반영)**: `iherb.py` `SELECTOR_TIMEOUT_MS` **20000 → 40000**. 근거 — ① 타임아웃이 짧으면 Scrapling이 예외 없이 specs 미부착 페이지를 반환해 **"UPC 없음"으로 조용히 오탐**되고, 이는 진짜 미보유와 구별되지 않아 배치의 보유율 수치 자체를 오염시킨다(비대칭 비용) ② 40s는 관측 최대 9.9s의 4.0배·중앙값 6.3s의 6.3배로 부하 4배 악화까지 견딤(30s는 3.0배로 경계) ③ 타임아웃을 무는 건 어차피 실패할 비정상 페이지뿐이라 표본 비정상률 상한 6%(0/50, rule of three) 최악 가정에서도 30s 대비 **차이 21분**(4시간 작업 기준) ④ Java `DETAIL_TIMEOUT=120s` 천장의 34%만 사용하고, 종전 운영값(`network_idle timeout=60000`)보다 여전히 엄격. **재실측**: 정상 페이지 3건 7.9/5.7/6.7s(평균 6.8s)로 UPC·이미지수·specs키수 전부 동일 — 상향이 정상 경로 비용에 무영향. 비정상(404) 페이지는 **40.9s에 `status=not_found`**로 정상 종료(느린 성공 아님).
- **iHerb 표본 실측 (미확인 항목 해소)**: 2,146건 모집단에서 **무작위 50건 → UPC 보유 50/50 (100%)**, 체크디짓 위반 0, 오류 0, 평균 6.4s·중앙값 6.3s. → **전량 2,146건 직렬 약 4.0시간**(42시간에서 단축). 병렬화 없이도 야간 1회로 완주 가능.
- **VTB 경로 신설**: `scraper/scrapers/vitabiotics.py` — Shopify `/products/{handle}.js`의 variant `barcode`(EAN-13), **브라우저 불필요**. `app.py`의 `/scrape/product-detail`을 URL로 분기해 **Java 어댑터 무변경**으로 같은 경로가 두 소싱처를 태운다. 원칙: **추측 금지** — 한 handle에 variant가 여럿이고 URL에 `?variant=`가 없으면(규격별 바코드 상이) 사유를 남기고 건너뛴다. 404 handle은 사이트 리네임 패턴(`-tablets` 접미)만 시도하되 **제목 토큰이 handle 토큰을 전부 포함할 때만** 채택(`ultra-iron-sustained-release`→"Ultra Iron"은 `sustained`·`release` 토큰이 없어 거부 — 서방형/일반형 혼동 방지 가드가 실제로 작동). 429는 백오프 재시도(3/6/9s)+1.5s 스로틀.
- **VTB 전수 102건 실측**: **EAN 보유 71건(70%)**, 형식위반 0, 404(복구불가) 14, 다중variant 17. 리네임 복구 도입 전 58건(57%)에서 상승(404 34→14). 남은 31건은 **17=규격 특정 불가(`sourcing_url`에 `?variant=`만 채우면 자동 해소)·14=상품 소멸**이라 의도적 공란. 소요는 102건 약 4분.
- 미확인(잔여): 쿠팡 공지 원문(필수 강제 시점·조건) / 스토어·11번가·카페24의 바코드 요구 여부 / 스크래퍼 동시 실행 상한(병렬화 검토 시)
- **실행 결과(2026-08-27, 리더)**: iHerb 전량 배치 완료 — **2,146건 중 2,132건 확보(99.3%)**, 실패 14건(상품 페이지 소멸 등). 중간에 D-218 긴급 배포로 1회 중단됐으나 멱등 설계대로 재개(`e42c0a0d` 689건 → `ddf45949` 1,423건)해 손실 없음. VTB 배치 — **102건 중 71건(70%)**, 조사 예측치와 일치(잔여 31 = 다중variant 17 + 상품소멸 14). **전체 상품 3,195건 중 2,203건(69%) 바코드 보유** (착수 시점 0건).
- **독립 검증(리더)**: 저장값을 iHerb에서 재조회해 대조 — `220621IHB021` `753950002647`·`220621IHB020` `753950001572` 양쪽 정확 일치. 전 표본 12자리 UPC-A, 브랜드별 제조사 접두 일관(닥터베스트 `753950`·컨트리라이프 `015794`·캘리포니아골드 `898220`), 동일 제품군도 용량·수량별로 값 상이 — 상품↔바코드 1:1 정합 확인.
- **잔여 946건**: OCD 407·FTN 393·TES 98·COK 48 전부 0건. 조사 결론은 "바코드 부재"가 아니라 **"수집 경로 미발견"** — FTN·COK는 페이지에 패턴 0건, OCD(봇 챌린지 202)·TES(403)는 접근 불가. iHerb도 빠른 API엔 UPC가 없고 브라우저 렌더링으로만 얻은 선례가 있으므로 벤더별 재조사 가치 있음. 대안은 쿠팡 정규 경로인 `emptyBarcode=true`+사유.

- 상태: **검증 조건부 PASS → 후속 ① 반영 완료(재검증 대기)** — 산출물 `_workspace/fixes/D-217_barcode_fix.md`. 게이트(후속 ① 반영 후 재측정, BUILD SUCCESSFUL 4m30s): core 862 · infra 284 · api 197 · worker 72 전부 failures 0, 4모듈 compileJava·spotlessCheck 통과(공유 트리에 타 fixer 변경 혼재 — 증분 내역은 산출물 참조). **④ 쿠팡 페이로드(barcode/emptyBarcode/emptyBarcodeReason)는 이번 범위 밖** — [[D-216]] 해소 후 별건.

### D-218: 주문 상품매칭이 부분 문자열로 엉뚱한 상품을 찾아 실제 오배송 발생 (2026-08-27, 사용자 신고 → P0)

- 심각도: **P0(고객 오배송 실발생)** | 위치: `MarketRegistrationRepository`의 LIKE 질의 + 주문 동기화 3경로
- **실제 사고**: G마켓 주문 `4477134670`(2026-08-07). ESM 실판매는 `230806IHB130`(닥터베스트 멀티비타민, 카페24 `product_no=20082`)인데 우리는 `200828TE001`(사클라 칠리 페스토)로 매칭·발송 → `EXCHANGED` 처리됨.
- **원인**: `findByMarketTypeAndIdentifiersContaining`가 `market_identifiers` **JSON 덩어리 전체**를 `LIKE '%값%'`으로 훑고, 호출부 3곳이 **`ORDER BY` 없이 `regs.get(0)`**을 채택. 카페24 `product_no`가 4~5자리라 날짜로 시작하는 SB코드(`200828TE001`) 안에 파묻힌다 — `20082` 조회 시 7건이 걸리고 정답은 4번째.
- **피해 범위(실측)**: 이 경로 주문은 총 16건(G마켓 13·옥션 3). 순서 무관 판정법(매칭된 상품의 식별자 안에 남의 `product_no`가 들어 있는지)으로 7건은 **안전 증명**, 9건이 확인 대상이었고 사용자 ESM 대조 결과 **오배송은 1건 확정**. 쿠팡·11번가는 식별자가 10~11자리라 운영 충돌 0건이었으나 같은 코드 경로라 잠재 위험은 동일했다.
- **수정(2026-08-27, 커밋 `f462ff89`)**: ① SQL LIKE는 **후보 전치 필터**로만 쓰고 **정확 일치 판정을 Java JSON 파싱(Jackson)으로** 이관 — 운영 카페24 3,186행 중 **2,428행이 공백 포함 JSON**(`{"product_no": "20082", …}`), 758행이 압축이라 **SQL 문자열 패턴 해법은 76%를 조용히 놓친다**(D-206과 동일 계열 함정). ② **후보 다건이면 첫 건 채택 금지** → 매칭 실패로 두고 경고(0건 `찾을 수 없음` / 다건 `매칭 중단` 문구 분리). ③ 카페24(`product_no`·`product_code`)·쿠팡(`marketProductCode`→`vendorItemId`, `sellerProductId`→`sellerProductId`)·11번가(`sellerProductId`→`prdNo`) 3경로 전환. 구 메서드 → `findIdentifierCandidates`(ORDER BY id 추가), 잔존 호출부 0.
- **검증(2026-08-27, verifier-match **PASS**, 롤백 불필요)**: Red를 `git archive HEAD~1` + 운영 7행 시드로 재현(`expected "230806IHB130" but was "200828TE001"`). **회귀 위험 데이터로 0 확인** — 5개 (마켓×키) 전부 고유값수=행수, 다건 0건이라 "전 주문 미매칭" 시나리오가 운영 데이터상 불가능. 공백/압축 양쪽 고정, 식별자 11,178개 값에 LIKE 와일드카드·이스케이프·앞뒤공백 0건. 조회 키 5행 독립 확인(어댑터 실독 + DB 커버리지). 안전장치 발견: `MarketLineItemSyncDispatcher.applyLineItem:95`가 productId=null이면 **기존 값을 지우지 않아** 미매칭 증가가 기존 매핑을 소거하지 않는다. 게이트 core 885·infra 313·api 202·worker 72 전부 그린, `@Test` +12/-0.
- **잔여(별건)**: ① **사고 주문 `4477134670`이 지금도 틀린 상품(2320)을 가리킨다** — 카페24 조회 창 안 주문은 다음 동기화에서 `applyLineItem`이 자동 교정하나 창 밖(08-07)은 안 된다. 교정 필요. ② `custom_product_code`(SB코드)가 `product_no`보다 안정적 조인 키인데 매칭 경로가 미사용 — 폴백 후보. ③ 라이브 동기화 후 `상품 매칭 중단` WARN 0건 확인이 남음. ④ 부수 확인: `210121IHB002`는 구 코드에서 후보 2건이라 **잠재 오배송 후보였고** 이번 수정으로 1건 확정됐다.
- 상태: **검증통과** (배포·사후검증 완료)

### D-219: 잔여 946건 바코드 수집 경로 조사 — 26건만 수집 가능, 920건 경로 없음 (2026-08-28)

- 심각도: P3(조사 결과·계획) | 위치: `scraper/` 벤더별 수집 경로
- 배경: [[D-217]]로 iHerb 2,132·VTB 71건을 확보(전체 69%). 잔여 946건(OCD 407·FTN 393·TES 98·COK 48)의 수집 경로를 **브라우저 렌더링까지 동원해** 재조사했다(1차는 plain curl만 써서 막혔던 것).

| 벤더 | 건수 | 브라우저 도달 | 바코드 존재 | 수집 가능 | 판정 |
|---|---|---|---|---|---|
| COK | 48 | O | **O** | **26건(54%)** | **수집 실행** |
| FTN | 393 | O | **X(확정)** | 0 | emptyBarcode |
| OCD | 407 | O | **X(확정)** | 0 | emptyBarcode |
| TES | 98 | **X(403)** | **확인 불가** | 0 | emptyBarcode |

- **COK 성공 경로**: Shopify 아님 — **SAP Commerce(Hybris) OCC REST가 무인증 공개**. `GET https://www.costco.co.uk/rest/v2/uk/products/{code}?fields=FULL` → **`.ean`**. `code`는 상품 URL `/p/` 뒤 마지막 세그먼트, 404 시 `{code}_BD` 폴백(5건 추가 생존). 브라우저 불필요, **건당 약 2초·전량 2~3분**. **1초 이상 페이싱 필수**(무페이싱 연타 시 비-JSON 응답으로 죽음, 실측).
- **⚠ 1차 오판의 원인(재발 방지)**: 응답에 `upc` 키가 있으나 **값이 전부 `'0'`**이라 "바코드 없음"으로 오판했다. 실제 값은 별도 **`ean`** 키에 있고 **값이 없으면 키 자체가 응답에서 사라진다** — 키 목록만 훑으면 존재를 놓친다. JSON-LD의 `gtin8:"0"`도 동일한 쓰레기값.
- **COK 배제 대상 22건**: 404 단종 10 / **플레이스홀더 6**(`1000000666433`·`1000000120296`·`1000000890181`×2·`1000000513001`·`1000001001368` — `1000000`+사내품번 패턴, **필터 없으면 가짜 바코드가 마켓에 올라간다**) / 체크디지트 실패 5 / **12자리 보류 1**(`512454063291`는 UPC-A로도 절단 EAN-13 `5012454063291`로도 체크디지트 통과 — 0 패딩 관례 적용 시 틀린 값이 된다).
- **FTN 부재 근거(확정)**: Shopify 아님 — **Magento 2**. `/graphql` GET 공개. `customAttributeMetadata` 조회에서 `ean`·`barcode`·`gtin`·`upc`가 전부 `attribute_type:"AnyType"`(=속성 부재), 대조군 `commodity_code`는 `"String"`(=실재). 상품 15건 `custom_attributesV2` 전량 덤프(상품당 145~171 속성)에서 8~14자리 숫자 **0건**. 이 API는 빈 값도 반환하므로 "값이 없어 안 보인 것"이 아니다.
- **OCD 부재 근거(확정)**: 상품 데이터는 `PUT /api/webproductpagews/v6/products`(본문=UUID 배열)로 오며 WAF 쿠키 때문에 브라우저 세션 내에서만 호출 가능. 그 객체 30필드에 바코드 없음. SSR 상태 전체 키 센서스 — 상품 3건 고유 키 490~545개 중 ean/gtin/upc/barcode **0건**. JSON-LD `sku`는 Ocado 자체 ID. ⚠ 샘플 4건 중 **2건이 404 단종** — 407건 중 상당수가 죽은 링크일 수 있어 emptyBarcode 처리 전 생존 확인 권장.
- **TES 확인 불가**: **Akamai Bot Manager**로 `tesco.com` **도메인 전체 403**. 7가지 수단 전부 실패(plain curl 2 IP · DynamicFetcher · StealthyFetcher 3변형 · 생 Playwright 웜업). **페이지를 한 번도 못 열었으므로 "바코드가 없다"가 아니라 "확인 불가"**다. 영국 주거용 프록시 확보 시 재조사 여지.
- **부수 발견(별건, 가치 큼)**: FTN GraphQL 한 번으로 **원가(`cost`)·중량(`weight`)·HS 관세코드(`commodity_code`)·성분·알레르겐·원산지**를 전부 얻는다. 현행 `scraper/scrapers/fortnum.py`는 브라우저 렌더링으로 상품당 **20초**인데 GraphQL은 **0.5초** — **40배**. 재고·가격 크롤 경로 교체 후보.
- 미확인: TES의 EAN 게시 여부(도달 불가) / OCD 407건 실제 생존율(3건만 심층 확인) / COK 체크디지트 실패 5건의 자릿수 보정 여지 / 12자리 UPC-A vs 절단 EAN-13 판별 규칙
- 상태: 발견 (COK 26건 수집 착수 대기, 920건은 emptyBarcode 대상)

### D-181: 쿠팡 어댑터가 이미지·상세 수정 PUT 응답의 마켓 레벨 오류코드를 검사하지 않음 — 거짓 성공 여지 (2026-08-21, D-167 검증 중 발견)

- 심각도: P2(추정 — 응답 포맷 확인 필요) | 위치: `backend/infrastructure/.../coupang/adapter/CoupangMarketClient.syncImagesAndHtml` 계열
- 증상: HTTP 예외는 자연 전파되지만, 200 응답 본문에 담기는 쿠팡 봉투(`{code, message}`) 실패를 검사하지 않아 D-167 계열의 거짓 성공 가능. qa-verifier가 D-167 검증 중 4마켓 대조에서 지적. 조사 결과 `syncPriceAndStock`(PUT 3발)·`deleteFromMarket`도 동일하게 본문 미검사 — 사용자 결정으로 3경로 일괄 수정.
- 라이브 실측(2026-08-21, 리더 SSH 읽기): vendor-items PUT 실패는 HTTP 400 + `{"code":"ERROR","message":...}`로 도착(당일 배치 로그 실증 — 판매중지 재개 거부·삭제상품 가격변경 거부) → 기존에도 예외 전파됨. seller-products PUT 성공 봉투는 로그 부재로 미실측.
- 수정(2026-08-21, fixer-d181): 실측 근거의 경로별 규칙 — 상품수정 PUT(콜드 패스)은 **엄격**(code≠SUCCESS/200·파싱 불가·code 부재 전부 throw), vendor-items PUT 3발·DELETE(핫 패스)는 **방어적**(봉투가 파싱되고 code가 명시적 비성공일 때만 throw — 성공 봉투 미실측이라 거짓 실패 방지). delete는 본문 수신 위해 `requestWithBody("DELETE")` 전환(HMAC·헤더 동일 확인). 단일 헬퍼 `verifyEnvelope(strict)`.
- 검증(2026-08-21, qa-verifier PASS): Red를 수정 전 코드(git archive)에 실측 — **거짓 성공 실증 8케이스**(전부 "예외가 던져지지 않음"으로 실패). `:infrastructure:test --rerun` 140 tests 그린, 리더 전 모듈 회귀 1,116 tests 그린. 경계면 — 3개 호출부 전부 기존 부분실패 계약으로 수용(재게시는 오염 rawData 미저장, 가격재고는 markSyncFailed 교정, 삭제는 best-effort 유지), publish POST는 기존 data 검사로 등가 가드 존재(미보호 경로 없음). spotlessCheck 통과. 판정서: `_workspace/verify/D-181_verdict.md`
- 라이브 검증(2026-08-21 17:58 KST, 배포 커밋 85937c5 · 상품 3110 재게시 실측): ① 쿠팡 seller-products GET 성공 응답이 `{"code":"SUCCESS",...}` — **성공 봉투에 code 실존 확정, 엄격 규칙 잔여 리스크 해소.** ② 상품수정 PUT이 실제로 **HTTP 200 + `{"code":"ERROR","message":"유효하지 않은 구매 옵션 값 혹은 단위가 존재합니다."}`** 를 반환 — 신규 검사 로직이 포착·전파해 `failed=[COUPANG]` 집계, DB 확인 결과 COUPANG is_synced=false 유지·rawData 미오염(CAFE24·SMART_STORE는 정상 갱신). **구 코드였다면 이 호출이 "성공"으로 기록되고 로컬이 오염됐을 실제 사례.** PUT 거부 자체는 신규 결함 [[D-182]]로 분리 등재.
- 잔여: `CoupangRestClient.delete(String)` 호출부 0으로 데드코드화 — D-179 묶음에 편입.
- 상태: 검증통과 (라이브 검증 완료)

### D-182: 쿠팡 재게시 왕복 페이로드가 옵션 값·단위 검증에 거부된다 (2026-08-21, D-181 라이브 검증 중 발견)

- 심각도: P2 (쿠팡 재게시 기능 불능 — 최소 해당 상품, 왕복 패턴 공유하는 상품 전반 가능성) | 위치: `backend/infrastructure/.../coupang/adapter/CoupangMarketClient.syncImagesAndHtml` 페이로드 구성부
- 증상: 상품 3110(NU00069) 재게시에서 seller-products GET(SUCCESS) → 이미지/contents만 교체 → 동일 base PUT이 `code=ERROR, "유효하지 않은 구매 옵션 값 혹은 단위가 존재합니다."`로 거부됨. GET이 돌려준 전체 페이로드를 그대로 되돌려도 PUT 검증을 통과하지 못함 — GET 응답의 옵션(items) 속성/단위 표현이 PUT 스키마 요구와 다르거나, 과거 등록 시점의 값이 현행 검증 규칙에 안 맞는 것으로 추정. 상품 3110은 용량 200 밀리리터·묶음 3개 구성.
- 영향: D-181 수정 전에는 이 실패가 조용히 성공으로 기록돼 왔으므로, **쿠팡 재게시가 실제로 언제부터 안 되고 있었는지 알 수 없음** — 재게시 시도 이력이 있는 상품의 쿠팡 실물 리스팅과 로컬 기록 대조 필요.
- 재현: 상품 3110 상세 모달 → 이미지 URL로 등록 → 재게시 → failed=[COUPANG], 위 메시지.
- 수정 방향(미착수): GET 페이로드를 PUT에 되돌릴 때 items의 attributes/notices 등에서 PUT이 거부하는 필드·값 정규화 필요(쿠팡 문서의 수정 API 요구 스키마와 대조). 관련 salvage: `refactor-20260821/salvage-infra.md` CoupangMarketClient.syncImagesAndHtml 절(D-092 경로 함정).
- 수정(2026-08-21, tdd-fixer): `syncImagesAndHtml`가 PUT 직전 `sanitizeItemAttributes` — 빈/null 값 엔트리 드롭, 자리표시 값(값==타입명 또는 {수량,용량,중량,정,개,캡슐})은 상품 데이터로 재충전(수량류→bundleQuantity+"개", 용량/중량/함량/캡슐/정류→capacity+단위 접미사), 의미값·부가 필드·타입명 불변.
- 검증(2026-08-21, qa-verifier **PASS**): Red를 수정 전 코드(`git archive HEAD` 전개 + 테스트 이식)에 실측 — 행위 변경 2건만 FAILED, 보존 가드 2건은 전후 모두 Green(가짜 Red 아님). `:infrastructure:test --rerun` **144 tests / failures 0**, XML에 신규 4 testcase 실행 기록 확인. 호출부 1곳(`ProductManageUseCase:170`)·인터페이스·타 마켓 어댑터 무변경, D-181 봉투 검사 무영향, `:api:/:worker:compileJava`·`spotlessCheck` 통과, 신규 주석 0·FQN 0. 반환 rawData가 DB에 저장되므로 2회차 재게시는 정화값 그대로 통과(멱등). 판정서: `_workspace/verify/D-182_verdict.md`
- 잔여 리스크(라이브): ① 단위 접미사가 카테고리 `usableUnits` 미대조 고정 매핑 — 다른 카테고리에서 재거부 가능 ② 자리표시 문구 집합이 실측 2종+유추 4종 ③ `포장단위="개"`처럼 단일 토큰이 정상값인 속성은 드롭될 수 있음(실데이터 미확인). 오탐 파급은 "이미 거부되던 엔트리" 한정이라 신규 회귀는 아님.
- 후속 후보(분리 등재 제안): `CoupangMetaService.extractMandatoryAttributes`(최초 등록 경로)와 신규 `refillAttributeValue`(재게시 경로)의 **산식이 상이** — 수량 계열 `capacity*bundleQuantity`(600) vs `bundleQuantity+"개"`(3개), `캡슐`·`정` 타입의 수량/용량 분기가 반대, 단위 선택이 `findProperUnit(usableUnits)` vs 고정 switch. 라이브 수락된 쪽은 신규 sanitize라 D-182 반려 사유는 아니나 두 경로 통일 필요. 덧: `CoupangMetaService.java:51` Integer 언박싱 NPE 여지(기존 코드).
- 라이브 검증(2026-08-21 19:55 KST, 배포 커밋 57694b6 · 상품 3133 NU00111 재게시 실측): 수정 전 100% 자리표시 속성(36/36)이던 상품에서 쿠팡 PUT **`code=SUCCESS, data=10621199335`** — `synced=[CAFE24, COUPANG, SMART_STORE], failed=[]` 완전 성공, DB 3마켓 모두 is_synced=t·rawData 정상 갱신. 잔여 경고("'개당 용량/중량/정' 유효하지 않은 구매 옵션" — 수락은 되나 병합 라벨이 비정규명)는 [[D-183]] 산식·정규명 통일에서 해소 예정.
- 상태: **검증통과 (라이브 검증 완료)**

### D-183: 쿠팡 최초 등록과 재게시의 속성값 산식이 서로 다르다 + 등록 경로 언박싱 NPE 여지 (2026-08-21, D-182 검증 중 발견)

- 심각도: P2 | 위치: `backend/infrastructure/.../coupang/component/CoupangMetaService.extractMandatoryAttributes` vs `.../adapter/CoupangMarketClient.refillAttributeValue`
- 증상: 같은 필수 구매옵션에 두 경로가 다른 값을 만든다 — 수량 계열이 등록은 `capacity*bundleQuantity`(상품 3110이면 600), 재게시는 `bundleQuantity+"개"`(3개, 라이브 수락 실증). 캡슐·정 타입의 수량/용량 분기가 서로 반대. 단위 선택도 등록은 `findProperUnit(usableUnits)` 동적, 재게시는 고정 switch. 등록 산식(600개)이 오히려 의심스러움 — 등록 직후 상품의 옵션 표기가 틀릴 가능성.
- 부수: `CoupangMetaService.java:51`이 `getBundleQuantity()`(Integer)를 null 가드 없이 언박싱 — NPE 여지.
- 수정(2026-08-21, fixer-d183, 반려 1회 재수정 포함): 공용 `CoupangAttributeValueResolver` 신설 — "총"=capacity×bundleQty / 순수 "수량"=bundleQty / "개당"·용량류=capacity. 단위는 usableUnits 3상태(null=미상→고정 매핑 / 빈 목록=단위 없음 확정→미부착 / 목록→완전일치→부분일치→첫 단위 폴백). 등록(`CoupangMetaService`)·재게시(`CoupangMarketClient`) 두 경로 모두 리졸버 사용, 재게시는 displayCategoryCode로 메타 1회 지연 조회+실패 폴백. NPE 가드·0 이하 방어 포함. 타입명 불변.
- 검증(2026-08-21, qa-verifier — 1차 조건부(F-1 빈 목록 단위 강제부착·F-2 "개" 하드코딩) → 재수정 → 재검증 **PASS**): 검증자 자체 프로브 테스트로 3상태 전 분기 실측, 등록 "수량" 600→"3개" 교정·NPE Red 재현 확인, 소비자 경계 분석(등록은 null 불가·재게시는 빈 목록 불가 → D-182 라이브 경로 무영향 실측). `:infrastructure:test --rerun` 167 그린, 전 모듈 1,143 tests 그린, spotlessCheck 통과. 판정서: `_workspace/verify/D-183_verdict.md`
- 범위 명시: **산식·단위 통일 완료 / 정규 타입명 전환은 미완** — 재게시 잔여 경고("'개당 용량/중량/정' 비정규명")는 이번 수정으로 사라지지 않으며 라이브 정규명 실험 후 별도 판단. `getUsableUnits` 미캐시(재게시당 메타 1회)는 소소한 낭비로 기록만.
- 2차 보완(2026-08-21, 라이브 반려 사례 반영): 상품 2334 재게시가 PUT SUCCESS 후 **승인반려** — 필수옵션이 자리표시가 아닌 **빈 값 EXPOSED**(`개당 용량`·`개당 중량`='')여서 "빈 값=드롭" 규칙이 필수옵션을 제거한 것. 보완: 빈 값도 EXPOSED면 **단위 계열 게이트**(`supportsUnitFamily`: 중량↔무게단위, 용량↔부피단위, 캡슐·정↔정제단위, 병합 라벨은 하나라도 일치, 미상 단위는 보수적 통과) 통과 시 재충전 — 2334형은 [수량=4개, 개당 중량=28g] 생성·계열 불일치 `개당 용량`은 드롭. 게이트가 자리표시 경로에도 적용되어 오데이터 방지 강화.
- 2차 재검증(qa-verifier PASS): 검증자 프로브로 실제 PUT 바디 전/후 대조 — 2334형 Red 성립, 3110/2591 라이브 실증 형상 완전 불변, NONE 드롭 불변, 메타 호출 계약 강화(계열 탈락 시 0회). `:infrastructure:test --rerun` 173 그린, 전 모듈 1,134 그린(42/42 api 클래스 실행 확인). `exposed` 대문자 고정은 실측 GET 3건(3110·2334·2591)으로 확인 — 판정 유효.
- 신규 관측(별도 후보): 비계측 단위(EA 등) 상품이 부피 전용 허용단위 카테고리에 걸리면 첫 단위 폴백으로 "28ml"류 오데이터 가능(3중 조건 필요, 대안은 확정 반려라 트레이드오프 수용) / 단일 계열 라벨 자리표시가 계열 불일치로 드롭되면 반려 가능성(현 데이터는 병합 라벨뿐) / 등록 경로는 게이트 없음(MANDATORY 드롭 불가로 의도적 비대칭).
- 3차 보완(2026-08-21): 라이브 재실측에서 **자기무력화 경로** 발견 — 1차 재게시가 정화(빈 엔트리 드롭)한 rawData를 호출부가 DB에 저장 → 후속 재게시 입력에서 빈 필수옵션 엔트리 소실 → 재충전 불능. 보완: 재게시는 저장 rawData의 items 유무와 무관하게 **항상 seller-products GET**으로 신선 페이로드 사용(이미 오염된 저장본 자가 치유, 재게시당 쿠팡 API 최대 3회). 재검증 PASS(§11 — 조건부 GET 복원 시 26중 23테스트 파손으로 계약 강제 실측, rawData 소비자 전수 조사 충돌 없음).
- **라이브 검증 완료(2026-08-21 21:15 KST, 배포 커밋 c401504 · 상품 2334 재재게시 실측)**: PUT 응답 **`{"code":"SUCCESS","message":"[]"}` — 경고 없는 클린 성공**, 쿠팡 저장 속성 **수량=4개 + 개당 중량=28g**(신선 GET→빈 EXPOSED 재충전→계열 일치 확인). 2591 실측(수량=3개·개당 30정)과 합쳐 정제/식품·병합/정규 라벨 전 조합 검증. `exposed` 표기 대문자 고정 실측 확정(§10.5② 해소). 상품 상태는 직전 반려 이력으로 "승인반려" 표시 유지 — requested=true로 재승인 요청됐으므로 쿠팡 심사 결과는 비동기 확인 필요.
- 정규명 전환 실험은 **중단 결정**: 병합 라벨 PUT이 수락됨을 3상품에서 실증했고, 대상 상품들이 전부 심사중이라 추가 PUT 차단 + 이전 정규명 시도 실패 이력 — 경고는 표시성으로 판정. 필요 시 심사 완료 후 별도 사이클.
- 상태: **검증통과 (라이브 검증 완료)**

### D-184: 스마트스토어 이미지/HTML 재게시가 가격표시제 품목에서 unitPriceYn 미설정으로 거부 (2026-08-21, D-183 라이브 검증 중 발견)

- 심각도: P2 | 위치: `backend/infrastructure/.../smartstore/adapter/SmartstoreMarketClient.syncImagesAndHtml` (origin-products PUT 페이로드)
- 증상: 상품 2334(식품 — 가격표시제 대상 품목) 재게시가 HTTP 400 `invalidInputs: originProduct.detailAttribute.unitCapacity.unitPriceYn — "단위가격 사용여부를 선택해주세요"`로 거부. D-092 계열(관부가세 customsTaxType 기본 채움과 동형) — 가격표시제 품목은 `unitPriceYn` 필드가 필수인데 왕복 페이로드에 미포함/미설정.
- 재현: `PUT /api/v1/products/2334/images/by-url` → failed=[SMART_STORE]. 3회 연속 동일 실패 실측.
- 수정(2026-08-21, fixer-d184): `syncImagesAndHtml`에 `applyUnitPrice(originProduct, product)` 1행 추가 — 가격/재고 경로(`syncPriceAndStock`)가 이미 쓰는 검증된 헬퍼 재사용. 헬퍼·타 경로 불변. Red: `SmartstoreMarketClientImagesTest` 신규 3케이스(수정 전 unitCapacity 미포함 실측 실패).
- 검증(2026-08-21, qa-verifier — 기능 4항 PASS + 포맷 반려 1회 교정 후 spotlessCheck 그린): Red 실측(git archive 이식), 헬퍼 3종 diff 0·syncPriceAndStock 무영향·detailAttribute 타 키 보존·D-092 함정 저촉 없음. `:infrastructure:test --rerun-tasks` 178 그린, 리더 전 모듈 회귀 1,154 그린. 주의(비차단): `applyUnitPrice`는 라이브 unitCapacity를 상품 스펙으로 **전면 교체**(가격/재고 경로와 동일 거동 — 신규 위험 아님). 2334는 스펙 보유(28·G·b4) DB 실측 → unitPriceYn=true 경로. 판정서: `_workspace/verify/D-184_verdict.md`
- 라이브 검증 완료(2026-08-21 21:37 KST, 배포 커밋 9ee9056 · 상품 2334 재게시 실측): 스마트스토어 PUT 200 정상 응답(originProductNo 4824226277) — 3회 연속 400이던 경로 해소. **`synced=[CAFE24, COUPANG, SMART_STORE], failed=[]` 3마켓 완전 성공**, DB 3마켓 is_synced=t. 잔여 참고: `applyUnitPrice`는 라이브 unitCapacity를 스펙으로 전면 교체(가격/재고 경로와 동일 거동) — 수동 설정 사용 시 유의.
- 상태: **검증통과 (라이브 검증 완료)**

### D-185: 쿠팡 신규 등록이 카테고리 메타 조회 404로 불가 (2026-08-22, D-166 라이브 검증 중 발견)

- 심각도: P1(신규 등록 기능 불능) | 위치: `backend/infrastructure/.../coupang/component/CoupangMetaService` 경유 `GET /v2/providers/openapi/apis/api/v2/products/category-meta/{code}`
- 증상: 상품 3194 등록 시도 → 404 `PRECONDITION_FAILED "No exactly matching API specification ... found"` — **엔드포인트 자체가 쿠팡 측에서 사라진 것으로 보임**(스펙 변경/폐기 추정). 카테고리 예측 결과 73134으로 메타 조회 실패 → publish 중단.
- 참고: 2026-08-14 배지 등록 라이브 검증 이후 어느 시점의 외부 스펙 변경 추정. 쿠팡 개발자 문서에서 현행 카테고리 메타 API 경로 확인 필요.
- 상태: 발견

### D-186: 스마트스토어 신규 등록이 주소록 자동조회 404로 불가 (2026-08-22, D-166 라이브 검증 중 발견)

- 심각도: P1(신규 등록 기능 불능 — 출고지 수동 설정 없는 상품 전부) | 위치: `backend/infrastructure/.../smartstore/component/SmartstoreAddressBookResolver` 경유 `GET /v1/seller/addressbooks`
- 증상: 3상품(3194·2270·1803) 등록 시도 전부 → 주소록 조회 404 `GW.NOT_FOUND` → 출고지/반품지 자동 해석 실패 → `SmartstoreProductPayloadBuilder`가 "출고지 주소ID 없음"으로 거부. 네이버 커머스API 주소록 엔드포인트 경로 확인 필요(스펙 변경 추정).
- 원인(확정): 1순위 경로 `GET /v1/seller/addressbooks-for-page?page=1&size=100`은 **HTTP 200 정상**이며 실응답 최상위 키가 **`addressBooks`**(리더 라이브 실측). `SmartstoreAddressBookResolver.fetchFromApi`가 `contents`/`content`/배열루트만 인식해 정상 응답을 "빈 목록"으로 오인, **로그 한 줄 없이 continue** → 2순위 폐기 경로 `/v1/seller/addressbooks`(404 GW.NOT_FOUND)로 넘어가 전체 실패. 로그의 404는 증상이지 원인이 아니었다.
- 수정: `LIST_KEYS = [addressBooks, contents, content]` + 배열루트 순으로 `extractList` 일원화, 미인식/빈 목록 시 `log.warn`에 `path`와 응답 최상위 키 기록(침묵 continue 제거). 헬퍼·경로목록·캐시·설정 오버라이드 불변. `_workspace/fixes/D-186_fix.md`
- 검증(2026-08-22, `_workspace/verify/D-186_verdict.md`): **PASS**. Red 실측 = 수정 전 소스 스크래치 재구성(`git archive HEAD`)에 신규 테스트 이식 → **7 tests, 5 failed**(통과 2건은 보존가드). Green = `:infrastructure:test --rerun` **185/0**, `:core:test`+`:api:test --rerun-tasks` **748/0·170/0** (합계 1,103 tests 0 failures). 행위 보존은 검증자 독립 프로브 7케이스를 수정 전/후 사본에 교차 실행 — 배열루트·`content`키·첫원소폴백·2순위폴백·실패비캐시·`invalidate` **6종 pre/post 동일**, 차이는 `addressBooks` 경로 1건뿐. 경계면(`MarketAccountResourcePort`·`SmartstoreProductPayloadBuilder.asLong`·`MarketRequiredFieldValidator`) 계약 불변. 클린 사본 `spotlessCheck` 통과, 신규 주석 0·FQN 0.
- 상태: 검증통과 (단위·통합 범위) — **라이브 실등록 1건 미검증**(리더 몫). 관찰: 주소록 100건 초과 시 1페이지만 조회하는 페이지네이션 한계는 기존 동작으로 잔존.

### D-187: 카페24 신규 등록이 진열 분류 목록 조회 실패로 불가 (2026-08-22, D-166 라이브 검증 중 발견)

- 심각도: P1(신규 등록 기능 불능) | 위치: cafe24 publish 경로의 쇼핑몰 분류 자동 매칭
- 증상: 상품 766 등록 시도 → "쇼핑몰 분류 목록 조회 실패(분류 0개 또는 API 오류)로 자동 매칭도 폴백도 불가" 거부. 오류 메시지가 안내하는 `market.cafe24.default-category-no` 설정도 미구성 상태.
- 종합: **D-185~187로 배지 클릭 신규 등록이 3마켓 전부 현재 불능** — 기존 상품 재게시·가격재고 동기화는 정상(금일 라이브 확인)이며 신규 등록 파이프라인만 고장. 마켓 측 API 스펙 변경이 공통 원인 후보.
- 원인 확정(2026-08-22, 라이브 실측): `GET /admin/categories` **403 insufficient_scope** — 인가 스코프 목록에 `mall.read_category`/`mall.write_category` 누락(application·product·collection·order·shipping만 요청).
- 수정(fixer-d187): `Cafe24TokenManager.generateAuthorizationUrl` scope에 category 2종 추가(1행). Red — scope 포함 단언(수정 전 실패). 전 모듈 회귀 그린. 초경량(상수 2토큰)이라 리더 직접 검토로 게이트 — 실질 검증은 재인증 후 라이브.
- 재인증(2026-08-22 라이브): 개발자센터에 상품분류(Category) 권한 자체가 미등록이라 invalid_scope → 사용자가 권한 추가(읽기+쓰기) → 수분 전파 후 전체 스코프 동의·인가 코드 발급 → issue-token 성공. 우회 지식: 상태 점검이 "정상"이면 UI에 재인증 버튼이 안 뜨므로 authorize URL 직접 진입으로 재인증 가능(브라우저 세션 프로브로 스코프 유효성 판별 기법 확립).
- **라이브 검증 완료**: `[카페24분류] 5개 분류를 조회해 캐시` — 403 소멸, 토큰 scopes에 read/write_category 실림 실측. 실등록은 몰 분류가 템플릿 기본값(ALL/Jackets/Tops/Shoes/새 상품분류)뿐이라 자동 매칭 안전장치(루트 폴백 거부 — 의도된 가드)에서 정상 정지 — 몰에 실분류 생성 후 완주 예정(사용자 선택 대기).
- 상태: **검증통과 (라이브 검증 완료 — 실등록은 몰 분류 데이터 준비 후)**

### D-188: 스토어 상품고시 페이로드가 구세대 스키마 — enum·하위 블록 전면 불일치 (2026-08-22, D-186 라이브 검증 중 발견)

- 심각도: P1(스토어 신규 등록 불능의 2단계 원인 — D-186 주소록 해소 후 드러남) | 위치: `backend/infrastructure/.../smartstore/component/SmartstoreProductPayloadBuilder` 고시 블록
- 증상: POST /v2/products 400 `productInfoProvidedNoticeType NotValidEnum`. 라이브 오라클 실측(필수 결핍 미니 페이로드로 enum 유효성만 판별): `HEALTH_FUNCTIONAL_FOOD`·`PROCESSED_FOOD` **무효**, `DIET_FOOD`(건강기능식품)·`FOOD`(일반식품)·`ETC` 유효. 하위 블록도 키·필드명 전면 상이 — dietFood{weight, ingredients, nutritionFacts, cautionAndSideEffect, storageMethod, intakeMethod, consumptionDateText, customerServicePhoneNumber, geneticallyModified, noMedicinePhrase(bool), importDeclarationCheck(bool), ...} 필드셋으로 실측 통과, food{amount, producer, weight, keep, adCaution, foodItem, productComposition, size, customerServicePhoneNumber} 필수 확정. boolean 필드에 문자열 넣으면 역직렬화 거부(커뮤니티 사례 일치).
- ⚠️ **위 증상 절의 필드 계약은 1차 실측본으로 폐기됨.** 라이브 실등록에서 역직렬화 거부가 재발해 리더가 오라클 재실측(dietFood-full/food-full, notice 오류 0)으로 계약을 확정했다. 확정본: dietFood 문자열 14(productName·ingredients·specification·weight·amount·producer·location·customerServicePhoneNumber·cautionAndSideEffect·consumerSafetyCaution·storageMethod·nutritionFacts·intakeMethod·consumptionDateText) + Boolean 3(nonMedicinalUsesMessage=true·importDeclarationCheck=true·geneticallyModified=false), food 문자열 9(foodItem·amount·producer·weight·keep·adCaution·productComposition·size·customerServicePhoneNumber), **공통 footer 5종(returnCostReason·noRefundReason·qualityAssuranceStandard·compensationProcedure·troubleShootingContents)은 문자열이 아니라 Boolean** 이며 각 블록 내부에 들어간다. 무효 키: `manufacturer`·`noMedicinePhrase`·`funtionalInfo`.
- 상태: 검증통과 (2026-08-22 2차 보완까지, `_workspace/verify/D-188_verdict.md`) — 1차 Red 4건·2차 Red 3건을 각각 실측 재현 후 Green, 전 모듈 1179테스트 0실패, 라우팅 3경로(빈 notice/건기식/grocery) 직렬화 실측으로 오라우팅 없음, 고시 외 페이로드는 1차 커밋 대비 정규화 비교 완전 동일. **라이브 POST /v2/products 실호출은 미검증 — 최종 확인은 실제 등록 1건 필요.**

### D-189: 스토어 신규 등록이 이미지를 네이버 서버에 업로드하지 않고 외부 URL을 직접 전송 (2026-08-22, D-188 라이브 검증 중 발견)

- 심각도: P1(스토어 신규 등록 불능의 3단계 원인 — D-186 주소록·D-188 고시 해소 후 드러남) | 위치: `SmartstoreMarketClient.publish` → `SmartstoreProductPayloadBuilder.build` 이미지 경로
- 증상: POST /v2/products 400 `"올바른 이미지 파일이 아닙니다."` — D-092 salvage에 기록된 함정("네이버는 외부 URL 대표이미지 거부 → 네이버 이미지 서버 업로드 후 반환 URL 사용, 무확장자면 .jpg 힌트")이 재게시(`syncImagesAndHtml`→`uploadImagesToNaver`)에는 구현돼 있으나 **신규 등록 경로에는 미적용**.
- 상태: 검증통과 (2026-08-22, `_workspace/verify/D-188_verdict.md` D-189 절) — `publish()`에 `applyUploadedImages` 1호출 추가(19줄 순수 추가, 헬퍼·재게시·빌더 무변경). Red 5건 실측 재현(핵심 2건은 `file:///` 원 URL 전송 증명, 3건은 `UnnecessaryStubbingException`으로 업로드 미호출 증명) 후 Green, 전 모듈 1184테스트 0실패. 업로드 상한 10장은 빌더 상한(대표1+추가9)과 정합하며 `applyImages`의 무제한 펼침을 막는 필수 장치로 확인. **라이브 POST /v2/products 실호출은 미검증 — D-186·D-188·D-189가 신규 등록 불능의 1·2·3단계 원인이므로 실제 등록 1건으로 함께 닫아야 한다.**
- 후속 후보(검증 중 발견): 재게시 경로 `syncImagesAndHtml`은 `uploadImagesToNaver(hostedImages)`를 **상한 없이** 호출한다 — 신규 등록만 10장 캡이 걸려 두 경로의 이미지 상한이 불일치. hostedImages가 10장 초과인 상품에서 재게시 쪽 초과 위험.

### D-190: 스토어 등록가가 10원 단위 미준수로 거부 (2026-08-22, D-189 라이브 검증 중 발견)

- 심각도: P2(스토어 신규 등록 4단계 원인) | 위치: `SmartstoreProductPayloadBuilder`의 salePrice (D-166 정책 계산가가 1원 단위로 산출됨)
- 증상: POST /v2/products 400 `originProduct.salePrice NumberUnit "판매가 항목은 10원 단위로 입력해 주세요."` — 네이버는 10원 단위 강제. 같은 응답에서 정책 계산가가 실제 페이로드에 실림이 간접 확인됨(D-166 라이브 방증).
- 병행 발견(코드 외 제약): `statusType limitOver "상품 등록 한도를 초과했습니다"` — **네이버 계정 상품 등록 한도 초과. 코드로 해결 불가 — 판매자센터에서 한도 확인/기존 상품 정리 필요(사용자 조치).** 해소 전까지 스토어 실등록 검증 불가.
- 상태: 검증통과 (2026-08-22, `_workspace/verify/D-188_verdict.md` D-190 절) — 빌더 내 `floorToTenWon` 단일 지점 라운딩(컨텍스트·폴백 공통). Red 2건 실측 재현(51912→51910, 23912→23910) 후 Green, 전 모듈 1187테스트 0실패. 라운딩은 스토어 빌더 파일 밖으로 새지 않음(전역 grep + `git diff --stat` 확인, 타 마켓·계산기·재게시 무변경). null→0은 구 코드와 완전 동치이며 `0 % 10 == 0`이라 단위 위반 아님. **라이브 미검증 — 위 등록 한도 초과 해소 후 D-186·188·189·190을 실등록 1건으로 함께 닫아야 한다.**
- 후속 후보(검증 중 발견) ①: **가격 동기화(PUT) 경로에는 10원 단위 라운딩이 없다** — `SmartstoreMarketClient.syncPriceAndStock:145`가 `priceResolver`가 준 Integer를 그대로 `salePrice`에 넣고, core 전역에 10원 라운딩 코드가 없다. 등록(POST)만 흡수되고 수정(PUT)은 1원 단위로 나갈 수 있다. PUT에도 `NumberUnit`이 적용되는지는 라이브 확인 필요.
- 후속 후보(검증 중 발견) ②: **판매가 fail-fast 부재** — 빌더 `require()`와 core `MarketRequiredFieldValidator` 어디에도 salePrice 검증이 없어, 가격 없는 상품이 `salePrice=0`으로 조립돼 네이버 400으로만 드러난다(구 코드부터 동일). 카테고리·주소ID·A/S전화가 한국어 메시지로 즉시 실패하는 것과 대비.

### D-191: 스토어 등록 payload의 KC 면제대상(kcExemptionType) 설정이 카테고리 조건부 거부 (2026-08-22, D-190 라이브 검증 중 발견)

- 심각도: P2(스토어 신규 등록 5단계 원인) | 위치: `SmartstoreProductPayloadBuilder` certificationTargetExcludeContent 블록의 `kcExemptionType: "OVERSEAS"`
- 증상: 실등록에서 `kcExemptionType Exist "KC 면제대상 항목은 설정하실 수 없습니다."` 거부. 오라클 실측 — 독립 미니 페이로드에선 현행 블록 통과(무조건 거부 아님, **카테고리/맥락 조건부**), `kcExemptionType` 제거형·블록 생략형 모두 통과, `kcCertifiedProductExclusionYn`은 문자열 "TRUE"가 정답(bool은 역직렬화 거부).
- 상태: 검증통과 (2026-08-22, `_workspace/verify/D-188_verdict.md` D-191 절) — Map 엔트리 1개 제거(빌더 -1줄). Red 1건 실측 재현(전송 블록에 `kcExemptionType="OVERSEAS"` 포함 확인) 후 Green, 전 모듈 1188테스트 0실패. `kcExemptionType`은 프로덕션 코드 전역에서 소멸(재게시 PUT은 네이버 저장값 왕복이라 무관). 잔여 2필드는 타입 단언으로 잠김 — Boolean `true` / 문자열 `"TRUE"` 비대칭이 오타로 보여 "정리" 리팩토링에 당하기 쉬운 지점을 보호한다. **라이브 미검증이며 거부가 카테고리 조건부라 단위 테스트는 "그 키를 안 보낸다"만 보장한다 — 등록 한도 해소 후 D-186·188·189·190·191을 실등록 1건으로 함께 닫아야 한다.**

> **2026-08-22 스토어 신규 등록 체인 종합(리더)**: D-186(주소록 응답 키)→D-188(고시 enum·필드 스키마 2라운드)→D-189(이미지 네이버 업로드)→D-190(판매가 10원 단위)→D-191(KC 면제 항목) 5결함 6차 라이브 시도로 순차 해소. **최종 시도에서 네이버 검증 오류가 `limitOver`(계정 판매중/판매대기/품절 상품수 1,000 초과) 단 1건만 잔존 — 코드 경로는 완전 복구.** 실등록 완주는 사용자가 판매자센터에서 상품수를 1,000 이하로 정리한 뒤 재시도만 남음. D-166의 이월된 실등록 실측도 동일 시점에 완성 예정. 잔여 마켓: D-187(카페24 분류)·D-185(쿠팡 카테고리 메타).

### D-192: 카페24 신규 등록이 supply_price 부재/형식으로 422 거부 (2026-08-22, D-187 라이브 검증 중 발견)

- 심각도: P2(카페24 신규 등록 후속 계층 — 분류 가드·토큰 해소 후 드러남) | 위치: `Cafe24MarketClient.publish` 페이로드 (supply_price 미전송)
- 증상: POST /admin/products 422 `"[Product supply price] can only contain integers and should be in strings. (parameter.supply_price)"` — 현행 API에서 supply_price(공급가)가 정수 문자열로 필수화된 것으로 보임(우리는 미전송).
- 수정(2026-08-22, fixer-d187): productData에 `supply_price` 정수 문자열 추가 — costPrice 내림(공급가=원가), null/0 이하 시 salePrice 폴백. Red 실측 후 Green, 전 모듈 회귀 그린.
- 라이브 검증(2026-08-22): 재시도에서 supply_price 422 소멸 — 등록이 다음 계층(이미지)으로 진행. 상태: **검증통과 (라이브 검증 완료)**

### D-193: 카페24 신규 등록이 외부 이미지 URL 직접 전송으로 422 거부 (2026-08-22, D-192 라이브 검증 중 발견)

- 심각도: P2(카페24 신규 등록 후속 계층) | 위치: `Cafe24MarketClient.publish` — `list_image`/`detail_image`에 외부 URL(esmplus/R2) 직접 전송
- 증상: POST /admin/products 422 `"[Product image] Wrong image path"`. 재게시 경로(`syncImagesAndHtml`)는 이미 **외부 이미지 다운로드→base64→POST /admin/products/{no}/images** 방식으로 검증돼 있음(D-092) — 신규 등록만 미적용. 스토어 D-189와 동일 구조의 누락.
- 수정(2026-08-22, fixer-d187): 재게시의 base64 업로드를 `uploadMainImage` 헬퍼로 추출해 등록 성공 직후 호출(양 경로 공유·복제 없음), 등록 페이로드에서 외부 URL 제거, 이미지 실패 시 product_no 포함 전파. 전 모듈 1,199 tests 그린.
- 라이브 검증(2026-08-22): "Wrong image path" 422 소멸, **등록 POST 성공 — product_no=22019(P000BGOX) 실발급**. 이미지 단계는 소스 URL(esmplus) 자체가 404라 의도된 실패 전파로 정지 — 코드 정상, 상품 766의 이미지 데이터 부패가 원인. 부산물: 카페24에 이미지 없는 고아 상품 22019 잔존(등록행 미저장 — markPublished 미도달), 정리 필요.
- 상태: **검증통과 (등록 파이프라인 라이브 실증 — 이미지까지의 완주는 살아있는 이미지 보유 상품으로 잔여)**

> **2026-08-22 카페24 신규 등록 체인 종합(리더)**: D-187(분류 권한 스코프+개발자센터 등록+재인증)→분류 가드(default-category-no=23, 기존 관행 실측 일치)→토큰 401(리더 프로브의 RT 회전 부작용 — 만료 강제로 복구, 이후 프로브는 RT 되쓰기 패턴)→D-192(supply_price 정수 문자열)→D-193(이미지 base64 업로드 공유화) 순차 해소. **등록 POST 성공(product_no 22019 발급)으로 파이프라인 라이브 실증** — 잔여는 상품 데이터(소스 이미지 404) 문제로, 카페24 미등록+생존 이미지 상품이 사실상 없어(전수 조회) 완주 대상 부재. 고아 22019 정리 대기. 관찰(후보): 카페24 클라이언트가 401에서 토큰 자동 재갱신 안 함.

### D-194: 쿠팡 신규 등록가가 10원 단위 미준수로 거부 (2026-08-22, D-185 라이브 검증 중 발견)

- 심각도: P2(쿠팡 신규 등록 후속 계층 — D-190의 쿠팡판) | 위치: 쿠팡 등록 페이로드의 정상가/판매가 (D-166 정책 계산가 1원 단위)
- 증상: 등록 실패 `"옵션(1개) : 정상가는 최소 10원 단위로 입력가능합니다. (1원단위 입력 불가)"` (라이브 실측).
- 수정·검증(2026-08-22, fixer-d185 연속 3라운드): 전 모듈 회귀 그린.
- **라이브 검증 완료**: 상품 3194 쿠팡 등록 성공(sellerProductId=16355149484, 심사중) — salePrice 47,200·originalPrice 62,770 — 둘 다 10원 단위 실측.
- 상태: **검증통과 (라이브 검증 완료)**

### D-195: 쿠팡 등록 속성의 exposed를 NONE으로 하드코딩 — 필수 구매옵션 미인식 (2026-08-22, D-194 라이브 검증 중 발견)

- 심각도: P2(쿠팡 신규 등록 후속 계층) | 위치: `CoupangMetaService.extractMandatoryAttributes`의 `.exposed("NONE")` 하드코딩
- 증상: 등록 거부 `"필수 구매 옵션 (미입력시 등록/노출 제한) 존재하지 않습니다."` — 메타 실측상 필수 구매옵션(개당 캡슐/정·중량·용량 그룹, 수량)은 `exposed=EXPOSED`인데 전부 NONE으로 전송되어 구매옵션 부재로 판정.
- 수정·검증(2026-08-22, fixer-d185 연속 3라운드): 전 모듈 회귀 그린.
- **라이브 검증 완료**: 상품 3194 쿠팡 등록 성공(sellerProductId=16355149484, 심사중) — EXPOSED 속성 2종이 구매옵션으로 정상 인식.
- 상태: **검증통과 (라이브 검증 완료)**

### D-196: 쿠팡 등록이 같은 그룹의 필수 구매옵션을 전부 전송 — 옵션 3개 한도 초과 (2026-08-22, D-195 라이브 검증 중 발견)

- 심각도: P2(쿠팡 신규 등록 후속 계층) | 위치: `CoupangMetaService.extractMandatoryAttributes` — groupNumber 무시
- 증상: 등록 거부 `"카테고리는 최대 3개까지 옵션생성이 가능합니다."` — 메타의 그룹1(개당 캡슐/정·중량·용량, 전부 MANDATORY+EXPOSED)은 **택1** 의미인데 전부 EXPOSED로 전송(+수량)해 한도 초과.
- 수정·검증(2026-08-22, fixer-d185 연속 3라운드): 전 모듈 회귀 그린.
- **라이브 검증 완료**: 상품 3194 쿠팡 등록 성공(sellerProductId=16355149484, 심사중) — 그룹1에서 개당 캡슐/정=60정 단독 선택(TABLET 계열 게이트 일치), 수량=1개.
- 상태: **검증통과 (라이브 검증 완료)**

> **2026-08-22 쿠팡 신규 등록 체인 종합(리더)**: D-185(카테고리 메타 현행 경로+required 필드)→D-194(가격 10원 내림)→D-195(exposed 메타값)→D-196(그룹당 택1 — supportsUnitFamily 재사용) 4계층 순차 해소, **상품 3194 실등록 성공(sellerProductId 16355149484)**. 등록 상품 실측: 판매가 47,200(D-166 정책 계산가 — 쿠폰 20% 반영, 기준가 50,400 대비 인하 = **D-166 이월 실등록 실측 완성**), 속성 수량=1개·개당 캡슐/정=60정(D-183 등록 산식 라이브 실증), 이미지 3장, 심사중. **이로써 3마켓 신규 등록 파이프라인 전부 복구**: 쿠팡 완주 / 카페24 등록 POST 실증(이미지는 데이터 문제) / 스토어 코드 완주(계정 limitOver만 잔여). 배지 클릭 등록 기능 정상화.

### D-197: 스토어 신규 등록 빌더에 관부가세(customsTaxType) 누락 (2026-08-22, limitOver 해소 후 발견)

- 심각도: P2(스토어 신규 등록 최종 계층 — limitOver는 사용자 정리로 소멸) | 위치: `SmartstoreProductPayloadBuilder` detailAttribute (재게시 경로 `applyCustomsTaxType`=INCLUDED는 존재, 빌더만 누락 — D-184·189와 동일 패턴)
- 증상: 400 `customsTaxType.required.overseas "출고지가 해외 주소인 경우 ... 관부가세 입력이 필수"` (라이브 실측). D-092 salvage 기록 재확인 사례.
- 수정(2026-08-22, fixer-d188): 빌더 detailAttribute에 customsTaxType=INCLUDED 1행(재게시와 동일 값). Red 실측 후 Green, 전 모듈 회귀 그린.
- 라이브 검증 완료(2026-08-22): 상품 2270 스토어 실등록 **SYNCED** — channelProductNo 13730401497. 상태: **검증통과 (라이브 검증 완료)**

### D-198: 스토어 신규 등록 빌더에 가격표시제 단위용량(unitCapacity) 누락 (2026-08-22, fixer 선제 발견)

- 심각도: P2(D-184의 등록측 누락 — "재게시엔 있고 빌더엔 없는" 4번째 패턴) | 위치: `SmartstoreProductPayloadBuilder` detailAttribute (재게시 `applyUnitPrice`는 존재)
- 증상(예상): 가격표시제 대상 상품 신규 등록 시 unitPriceYn 400 또는 단위가격 미표기 등록. 등록/재게시 불일치.
- 수정(2026-08-22, fixer-d188): 산출 규칙을 `SmartstoreUnitCapacity.of(Product)` 정적 유틸로 추출해 어댑터·빌더 공유(재게시 산출 불변은 기존 테스트로 고정). Red 실측 후 Green, 전 모듈 회귀 그린.
- 라이브 검증 완료(2026-08-22): 상품 2270 스토어 실등록 **SYNCED** — channelProductNo 13730401497. 상태: **검증통과 (라이브 검증 완료)**

### D-199: KST 날짜 헬퍼의 월말 오버플로 — 대상 월이 짧으면 시작일이 당월로 밀린다 (2026-08-22, verifier-p3a 등재 권고)

- 심각도: P4(선존 부채, D-176 회귀 아님 — 수정 전 `setMonth`와 동작 동일) | 위치: `frontend/src/utils/datetime.ts` `kstDateStringOffset`의 `setUTCMonth`
- 증상: JS `setMonth` 시맨틱상 존재하지 않는 날짜가 다음 달로 넘어간다. 그 결과 `{months:-n}` 시작일이 대상 월이 아니라 **당월 1~3일**로 밀린다. 트리거는 "31일"이 아니라 **대상 월 말일 < 원래 일자** — 2월이 대상이 되는 3/29·3/30도 포함된다.
- 실측(2026-08-22, 실소스 import — **2026년 전 캘린더일 스윕**, fixer·verifier 독립 2회 일치): 손실일 = 실제 시작일 − 의도 시작일(대상 월 말일로 클램프한 값).
  - `-1M` **연 7일**: `03-29→03-01`(1일) · `03-30→03-02`(2일) · `03-31→03-03`(**3일, 최대**) · `05-31→05-01`(1일) · `07-31→07-01`(1일) · `10-31→10-01`(1일) · `12-31→12-01`(1일)
  - `-3M` **연 5일**: `05-29→03-01`(1일) · `05-30→03-02`(2일) · `05-31→03-03`(3일) · `07-31→05-01`(1일) · `12-31→10-01`(1일)
  - 윤년은 2월이 하루 길어 3월 트리거가 3일→2일로 줄고 최대 손실도 2일(2028: `03-30→03-01` 1일 · `03-31→03-02` 2일)
  - `{days:-n}`(1주 프리셋·`todayMinus`)은 월 연산이 아니라 **해당 없음**. 그 외 날짜 정상(`2026-08-22 → 2026-07-22`).
- 영향 범위(정정 2회): ① 판정서 초안의 "며칠짜리로 좁아짐"은 과대 표현 — 시작일이 당월 1~3일로 밀릴 뿐 범위는 29~31일로 유지되므로 실제 손실은 **1~3일**(verifier 재측정으로 수용). ② fixer의 "연 5일, 31일에만"은 과소 집계 — 트리거가 일자가 아니라 대상 월 길이라 **`-1M` 연 7일 · `-3M` 연 5일**이 맞다(verifier 지적으로 수용). 손실 1~3일 규모와 P4 판정은 양쪽 정정 후에도 유지 — 기본 조회 범위가 하루~사흘 짧아지는 수준으로 주문 누락 같은 체감 증상은 없다.
- 수정(2026-08-23, fixer-p3-period): **① 기간 프리셋 재설계(사용자 요구)** — 취지는 "한 달 넘는 조회는 옆 날짜 입력으로 하고, 프리셋은 최근 월 단위로 곧장 짚는다". 버튼을 `오늘/1주일/1개월/3개월`에서 **`오늘 / 이번주 / {당월}월 / {전월}월 / {전전월}월`** 5개로 교체하고 라벨을 KST 오늘로부터 동적 생성(9월엔 `9월·8월·7월`, 1월엔 `1월·12월·11월`). 이로써 프리셋에서 `{months}` 상대 오프셋 경로가 사라졌다. **② 헬퍼 자체 수정** — `{months}` 경로는 초기 기본 범위(최근 1개월, `OrderGrid:543`·`:825`) 2곳이 남아 삭제 대신 **월말 클램프**(`min(원래 일, 대상 월 말일)`)로 해소. 월 이동은 `setUTCMonth` 대신 `y*12+(m-1)+months` 산술로 계산하고 말일은 `Date.UTC(y, m, 0)`로 구해 윤년까지 정확. 새 헬퍼 `kstMonthRange(monthsAgo)`(월 1일~말일)·`kstWeekStartString()`(그 주 월요일)을 `datetime.ts`에 추가했고 전부 KST 기준·`toISOString` 미사용(D-176 규칙 준수).
- Red 실측(2026-08-23): `2026-03-31 -1M → 2026-03-03`(want 02-28) · `2026-07-31 -1M → 2026-07-01`(want 06-30) · `2026-05-31 -3M → 2026-03-03` · `2028-03-31 -1M → 2028-03-02`(윤년, want 02-29) 재현. Green 후 동일 케이스 전부 통과 + 경계 스윕(월말 클램프 12케이스 · `kstMonthRange` 10케이스(1월·3월·윤년 2월·12월·연말 래핑) · 월요일 시작 6케이스(월/토/일·월경계·연경계)) 전부 PASS.
- 부수 정리: 활성 프리셋 상태의 매직 인덱스(`activePeriod === 2`, `-1`)를 의미 식별자(`PERIOD_DEFAULT`/`PERIOD_CUSTOM`/프리셋 id)로 교체 — 칩 활성 조건이 "기본 범위에서 벗어남"이라는 의미를 그대로 유지한다. 초기 기본 범위(최근 1개월)는 사용자 요구가 없어 현행 유지.
- 보정(2026-08-23, verifier-p3c 비차단 관측 ① 즉시 반영): 1차 수정의 `periodPresets = useMemo(..., [])`가 **패널 마운트 시각의 KST 오늘로 라벨·범위를 고정**하는 회귀를 만들었다(구 코드는 클릭 시점 계산이었으므로 이번 변경이 새로 들여온 것). 주문 화면은 종일 띄워두는 화면이라 자정을 넘기면 `오늘` 버튼이 어제 범위를 넣고 날짜 입력도 어제로 보여 사용자가 알아채기 어려운 조용한 오답이 된다. 조치: 범위 계산을 순수 함수 `kstPeriodRanges(today = kstDateString())`로 `datetime.ts`에 추출(인자는 KST ymd 문자열 — 결과가 인자 하루에만 의존해 node 실소스 검증 가능, 라벨은 UI 관심사라 `OrderGrid`가 id로 부여)하고, ① `handlePeriod(id)`가 인자 없이 재호출해 **클릭 순간의 KST로 범위를 다시 계산**(최소 요구 충족), ② `today` 상태 + 60초 티커(값이 바뀔 때만 `setToday` → 동일 값이면 React가 리렌더 생략)로 `useMemo(..., [today])`를 갱신해 **표시 라벨도 자정에 자동 전환**(권장 요구 충족, 매 렌더 재계산 없음). 검증: 자정 전후 2시각(월 넘김 `2026-08-31 23:59`→`09-01 00:01`, 연 넘김 `2026-12-31 23:59`→`2027-01-01 00:01`, 주 넘김 `08-23 23:59`→`08-24 00:01`) 실소스 실행으로 값 전환 확인 + 기존 계약 8케이스 회귀 PASS. 재게이트 그린(신규 린트 위반 0).
- 게이트(2026-08-23): `tsc -p tsconfig.app.json --noEmit` 0 · `npm run build` 성공 · `eslint .` **에러 0 / 신규 경고 0**(잔여 5경고는 HEAD 기준선과 동일 — OrderGrid 4건은 라인만 +14 시프트, ProductGrid 1건은 타 fixer 영역).
- 라이브 검증(2026-08-23 15:1x KST, 배포 커밋 100aaba · 리더 직접): 프리셋 5개 라이브 렌더 확인 — `7월` 클릭 시 2026.07.01~07.31(과거월=1일~말일), `8월`(당월) 08.01~08.23(오늘), `이번주` 08.17~08.23(월요일 시작) 전부 계약대로. 옆 날짜 입력 병존 확인. 마운트 고정 회귀 보정분 포함 배포.
- 상태: **검증통과 (verifier-p3c PASS · 라이브 검증 완료, 커밋 8d55aa3·100aaba)**
### D-200: 동기화 상태 점 행에 CUSTOMS 채널이 렌더되지 않는다 (2026-08-22, verifier-p3a 등재 권고)

- 심각도: P4(선존 공백, D-177 회귀 아님 — 구 `marketLabels`도 6키였음) | 위치: `frontend/src/utils/marketLabels.ts` `SYNC_SOURCE_KEYS`(6키) vs `backend/core/.../application/sync/SyncMarketKeys`(7키)
- 증상: 백엔드 `SyncMarketKeys`는 EMAIL·COUPANG·SMART_STORE·ELEVEN_STREET·GMARKET·COUPANG_SETTLEMENT·**CUSTOMS** 7키이고 `CustomsOrderSyncService:26`이 `markRunning(SyncMarketKeys.CUSTOMS)`로 상태를 기록한다(`CustomsSyncTransactionBoundaryTest:72-73`이 검증). 그런데 프론트 점 행은 6키만 순회해 **통관 동기화 상태가 화면에 표시되지 않는다**.
- 수정(2026-08-23, fixer-p3-period): `SYNC_SOURCE_KEYS`에 `CUSTOMS`를 **마지막**에 추가(기존 6키 순서 보존, 백엔드 `SyncMarketKeys` 선언 순서와 일치 — 마켓 4종 뒤에 비마켓 소스 EMAIL·COUPANG_SETTLEMENT·CUSTOMS가 오는 화면 흐름 유지)하고 라벨 `CUSTOMS: '통관'`을 `MARKET_LABELS`에 정의(비마켓 소스 EMAIL·COUPANG_SETTLEMENT가 이미 그곳에 있는 선례를 따름). 점 행은 7칸이 되며 `flexWrap: wrap` 컨테이너라 폭 초과 시 자연 줄바꿈.
- 근거: 사이클 9의 AUCTION(응답에 영구 부재라 추가 금지)과 달리 **라이브 `GET /api/v1/orders/sync/status`가 CUSTOMS를 실제 반환**(status COMPLETED, lastSyncAt 존재)함이 리더 실측으로 확인됨.
- Red 실측(2026-08-23): 실서버 응답 7키를 `SYNC_SOURCE_KEYS`로 순회 시 렌더 6개 · dropped `CUSTOMS`, `includes('CUSTOMS') === false`. Green 후 dropped 0 · 라벨 `통관` · 기존 6키 순서 불변 확인.
- 게이트: D-199와 동일 실행(타입·빌드·린트 그린, 신규 위반 0).
- 라이브 검증(2026-08-23 15:1x KST, 배포 커밋 100aaba · 리더 직접): 주문 화면 상단 동기화 점 행에 **`통관 26분 전`** 렌더 확인 — 7개 채널(쿠팡·N스토어·11번가·G마켓/옥션·이메일·쿠팡 정산·통관) 전부 표시, 기존 6개 문구·순서 불변.
- 상태: **검증통과 (verifier-p3c PASS · 라이브 검증 완료, 커밋 8d55aa3·100aaba)**

### D-201: 카테고리 필터에 "미분류" 축이 없어 전체 카테고리 선택 시 2,858행이 조용히 빠진다 (2026-08-23, D-175 비차단 관측 ② 승격)

- 심각도: P3(데이터 누락 아님 — 조회 결과만 축소, 사용자가 알아채기 어려움) | 위치: `backend/core/.../domain/product/ProductSpecifications.addCategories` + `dto/ProductSearchCondition`, `backend/api/.../controller/ProductController.getProducts`, `frontend/src/pages/product/ProductFilterPanel.tsx`·`ProductGrid.tsx`·`api/productApi.ts`
- 증상: `GET /api/v1/products/categories`는 DB에 실존하는 비null enum만 distinct로 주므로 `["FOOD","UNKNOWN"]`뿐이다. 패널의 "전체 카테고리"를 켜면 그 2개가 그대로 `categories=FOOD&categories=UNKNOWN`으로 나가고, `IN (...)`은 SQL 3값 논리상 NULL을 절대 매치하지 않으므로 `category IS NULL`인 상품이 전부 탈락한다. **"전체"를 골랐는데 결과가 줄어드는** 역직관적 동작이며, 미분류 상품만 보는 수단도 없었다.
- 운영 실측(2026-08-23, `docker exec projects-postgres-1 psql -U canagent -d sbshop` read-only): `sb_product` 전체 **3,195행** = `category IS NULL` **2,858** + FOOD **336** + UNKNOWN **1**. `WHERE category IN ('FOOD','UNKNOWN')` → **337행**(= 현 "전체 카테고리" 결과), `WHERE category IN ('FOOD','UNKNOWN') OR category IS NULL` → **3,195행**(= 수정 후 기대), `WHERE category IS NULL` → **2,858행**(= 미분류 단독).
- 수정(2026-08-23, fixer-p3-uncategorized): D-175가 만든 단일 경로를 확장 — `ProductSearchCondition`에 `boolean includeUncategorized` 컴포넌트를 추가하고 `addCategories`를 3분기로 재작성했다. **파라미터 의미**: 둘 다 없음 → 카테고리 술어 미생성(전건, 종전과 동일) / `categories`만 → `category IN (...)`(종전과 동일, NULL 불포함) / `includeUncategorized`만 → `category IS NULL`(신규) / 둘 다 → `category IN (...) OR category IS NULL`(**OR 결합**, 신규). 다른 필터축과는 기존대로 AND. 컨트롤러는 `@RequestParam(defaultValue = "false") boolean includeUncategorized` 1개를 추가해 조건 객체로 그대로 전달 — 응답 계약 `Page<ProductListResponse>`·스키마·엔티티·`/categories` 엔드포인트 **전부 무변경**(미분류는 DB distinct에 나올 수 없는 값이므로 서버 옵션이 아니라 프론트 상수 옵션으로 제공). 프론트는 `ProductFilters`에 `includeUncategorized: boolean`을 추가하고 패널 카테고리 줄 끝에 **"미분류" 체크박스를 서버 옵션 유무와 무관하게 항상 렌더**, "전체"는 서버 카테고리 전부 + 미분류를 함께 켜고/끈다(`isAllCategories = categories.length === categoryOptions.length && includeUncategorized`). `toQuery`는 켜졌을 때만 `includeUncategorized=true`를 싣고 기존 `categories` 전송 규칙은 그대로다. 그리드 카테고리 열의 빈 값 표시('-')도 현행 유지.
- Red 실측(2026-08-23, 2단):
  - **① 런타임 Red(기존 API만으로 증상 재현)** — `ProductSearchSpecificationTest`에 FOOD·UNKNOWN·null 3행을 심고 "distinct 카테고리 전부 선택"이 3건을 반환해야 한다고 단언: `AssertionError: Expecting actual: ["SB001","SB002"] to contain exactly in any order: ["SB001","SB002","SB003"] but could not find the following elements: ["SB003"]` — 운영의 337/3,195와 같은 형태(NULL 행만 탈락).
  - **② 계약 Red(신규 파라미터 미존재)** — 계약대로 4케이스를 작성하니 `:core:compileTestJava FAILED` / `ProductSearchSpecificationTest.java:285,289,301,331: error: cannot find symbol — symbol: method includeUncategorized(boolean)`.
- Green(2026-08-23): core 4테스트(OR 결합 2단언·미분류 단독·플래그 off 시 기존 동작 2단언·keyword와 AND 결합) + api 2테스트(파라미터 전달·미전송 시 기본 false) 전부 통과. `:core:test` **770** · `:infrastructure:test` **225** · `:api:test` **184** = **1,179 tests / failures 0 / errors 0**, `spotlessCheck` 통과, 4모듈 컴파일 통과. 프론트 `tsc -p tsconfig.app.json --noEmit` exit 0 · `npm run build` 성공 · `eslint .` **에러 0**(잔여 5경고는 TanStack `useReactTable` 관련 기존 경고, 신규 0).
- 기존 테스트 조정: 컨트롤러 메서드를 직접 호출하던 2개 파일(`ProductControllerMarketMapTest`·`ProductControllerR6QueryTest`, 4개 호출부)에 신규 인자 `false`를 추가 — 기대 단언은 손대지 않았고 기존 동작 불변을 그대로 지킨다.
- 부수 변경 1건(명시): 패널에서 `categoryOptions.length === 0`일 때 뜨던 `(로드된 상품 없음)` 안내 문구를 제거했다. 그 줄은 이제 "미분류"가 항상 있어 비지 않으며, 서버 카테고리가 0종이어도 미분류로 조회가 가능하므로 문구가 사실과 어긋난다.
- 미검증(명시): 라이브 검증 없음(미배포 — 배포 후 "전체 카테고리" 체크 시 3,195건 / "미분류" 단독 2,858건 브라우저 재확인 필요). FE 런타임 테스트 인프라 부재는 D-175와 동일.
- 요지: `_workspace/fixes/D-201_fix.md`
- 라이브 검증(2026-08-23 15:1x KST, 배포 커밋 100aaba · 리더 직접): **핵심 증상 해소 실증** — 상품관리 '전체 카테고리' 체크 후 검색 시 **3,195건 유지**(수정 전이면 337건), '미분류' 단독 시 **2,858건**·표시 행 카테고리 전부 '-'. API 스모크: `includeUncategorized=true` 단독 2,858 · `categories=FOOD,UNKNOWN`+미분류 3,195 · **`keyword=비타민`+미분류 1건**(괄호 우선순위 정확 — 잘못 묶였다면 2,858건이 딸려나왔을 것).
- 상태: **검증통과 (verifier-p3c PASS · 라이브 검증 완료, 커밋 8d55aa3·100aaba)**

### D-202: `Settings.tsx`의 네이티브 `alert()` 6건 — 알림 경험이 화면마다 다르고 실행을 블로킹한다 (2026-08-23, D-180e 검증 중 verifier-p3c 발견 · 리더 등재 판정)

- 심각도: P3(기능 오류 아님 — 일관성·UX) | 위치: `frontend/src/pages/Settings.tsx:62·67·107·110·137·140`
- 증상: D-180e로 앱 전역 알림이 `notify`(react-toastify 우하단)로 통일됐으나 설정 화면만 네이티브 `window.alert()`를 쓴다. ① **알림 경험 불일치** — 같은 앱에서 저장 성공이 어떤 화면은 우하단 토스트, 설정 화면은 화면 중앙 모달로 뜬다. ② **실행 블로킹** — `alert()`는 사용자가 확인을 누를 때까지 JS 스레드를 동기적으로 멈추므로, 뒤따르는 상태 갱신·재조회가 클릭 전까지 시작조차 하지 않는다. 6건 내역은 토큰 발급 결과(`:62`)·오류(`:67`), 설정 저장 성공(`:107`)·오류(`:110`), 가격 정책 저장 성공(`:137`)·오류(`:140`).
- **발견 경위**: D-180e(알림 이중화 해소)의 최초 조사가 `message.*`/`toast.*` 패턴만 grep 해 네이티브 경로를 통째로 놓쳤고, fixer가 "`Settings.tsx`는 알림 호출 0건"이라 오기재한 것을 verifier-p3c가 잡아냈다. `Settings.tsx`는 D-180e의 수정 허용 목록 **안**에 있던 파일이다. **교훈: 알림 인벤토리는 라이브러리 API뿐 아니라 `alert`/`confirm`/`prompt` 네이티브 경로까지 훑어야 한다.**
- **D-180e에서 처리하지 않은 이유(리더 확인)**: 토스트 전환은 **행위 변경**이다 — "확인을 눌러야 다음으로 진행"이 "눌러도 안 눌러도 진행"으로 바뀐다. D-180e는 구조 변경 전용 사이클이라 섞었으면 반려 사유였다. 다음 행위 변경 사이클에서 처리한다.
- **선행 과제 완료(2026-08-23, fixer-d180e 조사)** — 리더가 지목한 "호출부마다 `alert` 이후 코드 유무 판정"을 전수 조사했다. **블로킹 의존이 있는 곳은 6건 중 1건뿐이다.**
  - **`:62` `alert(res.message)` — 유일한 위험 지점.** `issueTokenMutation.onSuccess` 안에서 alert **뒤에 3개 문장이 이어진다**: `if (res.connected) setAuthCode('')` · `refetchCafe24Status()` · `queryClient.invalidateQueries({queryKey:['market-credentials']})`. 현재는 사용자가 확인을 눌러야 인증코드 입력칸이 비워지고 Cafe24 상태 재조회가 시작된다. 토스트로 바꾸면 셋 다 즉시 실행돼, 사용자가 토스트를 읽는 동안 화면이 갱신된다. 수용 가능한 변화로 보이나 **명시적 판단이 필요한 지점**이며, 필요하면 알림을 마지막으로 옮기거나 토스트 콜백에서 후속을 돌리는 선택지가 있다.
  - **나머지 5건은 alert 뒤에 코드가 없다 — 기계적 치환으로 안전하다.** `:67`·`:110`·`:140`은 `onError` 본문의 유일한 문장이고, `:107`·`:137`은 `onSuccess`의 **마지막** 문장이다(`invalidateQueries`·`setPolicyDraft(null)`가 alert보다 **먼저** 호출되므로 재조회는 이미 출발한 상태다).
- 재현: 설정 화면에서 마켓 자격증명 저장 또는 가격 정책 저장 → 우하단 토스트가 아니라 브라우저 기본 모달이 뜨고, 확인을 누르기 전까지 화면이 멈춘다.
- 수정 방향(제안): 6건을 `notify.success`/`notify.error`로 치환. `:62`만 위 판단을 먼저 내리고, 나머지 5건은 D-180e와 같은 정규화 diff 절차로 문구 동일성을 증명하면 된다. 저장소 전체 네이티브 경로는 이 6건 + `pages/order/OrderGrid.tsx:350`의 `window.confirm` 1건이 전부이며, 후자는 다이얼로그라 대상이 아니다(`product/ProductGrid.tsx:148`·`product/MarketBadgeCell.tsx:83`의 `confirm`은 `AntModal.confirm`이라 네이티브가 아니다 — verifier-p3c 독립 검증).
- 상태: 발견

> **2026-08-22 스토어 실등록 완주(리더)**: limitOver 사용자 정리 후 D-197(관부가세)·D-198(단위용량 — 빌더/재게시 공유화) 해소로 **상품 2270 실등록 성공(https://smartstore.naver.com/shouldbe_shop/products/13730401497)**. 스토어 체인 총 7계층(D-186·188·189·190·191·197·198) 소진 — "재게시엔 있고 등록 빌더엔 없는" 패턴 4종 전부 공유화로 해소. **3마켓 실등록 완주 달성**(쿠팡 3194·스토어 2270·카페24 POST 실증). 쿠팡 심사: 4건 심사중, 2334만 승인반려(사유는 API 미노출 — WING 확인 필요). 다음 대기: P3 묶음(D-176~180)·D-175.

### D-203: 마켓 실시간 조회 API가 살아 있는데 화면에서 도달할 수 없다 (2026-08-23, 리더 지시 신규 등재 · 사용자 승인)

- 심각도: P3(기능 손실 아님 — 진단 수단 부재) | 위치: `frontend/src/api/productApi.ts`, `frontend/src/pages/product/ProductDetailModal.tsx`, (신규) `frontend/src/pages/product/MarketLiveCompare.tsx` / 백엔드 `api/.../controller/MarketRegistrationController:29·39·49`
- 증상: `GET /api/v1/products/{id}/markets`·`GET .../markets/{marketType}/local`·`POST .../markets/{marketType}/sync` 3종은 백엔드에 온전히 살아 있고 `MarketRegistrationService.syncMarketLive`가 `MarketClient.extractMarketItem`으로 **마켓 API를 실시간 호출해 실제 등록 상태를 반환**한다. 그런데 프론트 클라이언트 함수가 사이클 B(죽은 코드 정리)에서 미호출 판정으로 삭제돼 **화면에서 이 진단 경로에 도달할 방법이 없었다**. 이 프로젝트가 반복해서 겪은 "로컬 DB에는 성공으로 기록됐는데 마켓 실물은 다르다"(D-181 쿠팡 봉투 거짓 성공 계열) 문제를 즉석에서 대조할 수단이 없다는 뜻이다.
- Red 실측(2026-08-23, 수정 전): `grep -rn "syncMarketLive\|getMarketRegistrations\|getLocalMarketData\|markets/.*sync" frontend/src` → **히트 0건**. 백엔드 엔드포인트 3종은 존재(위 컨트롤러 라인).
- 수정(2026-08-23, fixer-p3-marketlive): **백엔드 무변경.** ① `productApi.ts`에 백엔드 DTO를 그대로 옮긴 타입 `MarketItemInfo`(core `.../market/client/dto/MarketItemInfo` 14필드)·`MarketRegistrationRecord`(api `.../dto/market/MarketRegistrationResponse` 12필드, `@JsonRawValue` 2필드는 엔티티 게터가 비유효 JSON을 `{}`로 치환하므로 객체 타입)와 함수 3종(`getMarketRegistrations`·`getLocalMarketData`·`syncMarketLive`) 복원. ② 상품 상세 모달에 접이식 섹션 **"마켓 실제값 대조 (로컬 DB ↔ 마켓 API)"** 신설(신규 컴포넌트 `MarketLiveCompare.tsx`, 이미지 블록과 상세HTML 블록 사이). 등록된 마켓만 `GET /markets` 결과로 나열하고 마켓별 "마켓 실제값 확인" 버튼이 `local`+`sync`를 병렬 호출해 **로컬 DB 값과 마켓 실값을 3열(항목·로컬 DB·마켓 실제값)로 나란히** 표시, 다른 항목은 앰버 배경+굵은 글씨로 강조하고 상단에 `다른 항목 N개`를 요약한다.
- 오탐 방지 설계: 마켓별로 `extractMarketItem`이 채우는 필드가 다르다(11번가=상품명만 / 스토어=상품명·가격·재고 / 쿠팡=상품명·브랜드·제조사·바코드·식별자 / 카페24=상품명·상세HTML·이미지). 마켓이 값을 주지 않은 항목은 "다름"이 아니라 **`마켓 미제공`(회색)**으로 분류한다. 수치 항목(판매가·재고·이미지 수·상세HTML 길이)은 숫자 비교(BigDecimal `12900.00` vs `12900` 오탐 방지), 식별자는 키 정렬 후 문자열화해 순서 차이로 인한 오탐을 막는다.
- 미지원 마켓 처리: `MarketClientRouter`에 클라이언트가 있는 마켓은 COUPANG·SMART_STORE·ELEVEN_STREET·CAFE24 4종뿐이라 G마켓·옥션은 호출 시 확정적으로 `IllegalArgumentException("지원하지 않는 마켓입니다")`가 난다. 해당 마켓 버튼은 **비활성 + 툴팁("실시간 조회 API가 없습니다")**으로 처리해 확정 실패를 사용자에게 떠넘기지 않는다.
- 로딩·실패 처리: 외부 마켓 호출이라 수 초가 걸릴 수 있으므로 버튼이 `조회 중…`으로 바뀌며 마켓별로 잠기고(중복 클릭 방지) 재진입도 가드한다. 실패 시 `GlobalExceptionHandler`의 `{success,message}` 본문에서 메시지를 뽑아 **토스트 + 카드 내 인라인 빨간 박스** 양쪽에 남긴다(조용한 실패 없음). 알림은 전부 `utils/notify` 경유(D-180e 규율).
- 게이트(2026-08-23, 격리 트리 — `git archive HEAD` + 본 결함 3파일만 덮어쓰기, 병렬 fixer 편집 차단): `npx tsc -p tsconfig.app.json --noEmit` exit **0** · `npm run build` exit **0** · `npx eslint .` exit **0**, 경고 **5개(기준선 5개와 동일 — 전부 기존 TanStack `useReactTable` 관련, 본 결함 3파일 경고 0)**.
- 미검증(명시): FE 런타임 테스트 인프라 부재로 자동 테스트 없음. 라이브 검증 미실시 — 배포 후 실제 등록 상품(예: 쿠팡 3194 / 스토어 2270)에서 대조 실행 필요.
- 요지: `_workspace/fixes/D-203_fix.md`
- 라이브 검증(2026-08-24, 배포 7c85409d·1ac6ce9a · 리더 직접): 상세 모달의 접이식 '마켓 실제값 대조' 섹션이 펼치기 전 호출 0으로 동작, 등록 4마켓을 동기화 시각과 함께 나열(쿠팡 '미동기화' 구분 표시). 조회 실행 시 로컬 DB 10항목 표 렌더 확인. **이 UI가 배선 직후 실제 결함 [[D-206]]을 발견**(스토어 404·11번가 빈 응답) — 진단 도구로서 목적 달성.
- 상태: **검증통과 (라이브 검증 완료)**

### D-204: 값 직접 지정 배치와 소싱 후보 상세가 백엔드에만 있고 화면에서 도달할 수 없다 (2026-08-23, 리더 지시 신규 등재)

- 심각도: P3(기능 손실 — 대체 경로 없음) | 위치: `frontend/src/pages/BatchUpdatePage.tsx`, `frontend/src/api/sourcingDiscoveryApi.ts`, `frontend/src/pages/sourcing/DiscoveryPage.tsx`, (신규) `frontend/src/pages/sourcing/CandidateDetailDrawer.tsx` / 백엔드 `api/.../controller/BatchController:73`, `api/.../controller/SourcingDiscoveryController:96`
- 증상 ①: `POST /api/v1/products/batch/manual-update-price-stock`(+`batchApi.manualUpdate`)은 살아 있으나 호출부 0. 배치 화면에는 소싱처 크롤 기반 **자동 산정** 경로 2종만 있어, 판매가·판매상태를 **값으로 직접 지정해 여러 상품에 적용할 방법이 UI에 없었다**. 현재 운영가가 긴급 세일 상태라 정상가 복귀 같은 수동 지정 작업이 실제로 필요한 상황.
- 증상 ②: `GET /api/v1/sourcing/candidates/{id}`는 살아 있으나 프론트에 클라이언트 함수 자체가 부재(`sourcingDiscoveryApi.candidate` 없음). 추천 목록에서 후보를 눌러 상세를 볼 수 없어, 목록 컬럼에 없는 필드(정가·할인율·베스트셀러 순위·수요 키워드·예상 마진율·후보 상태·발견/최근확인 시각·성분 원문)를 확인할 수단이 없었다.
- Red 실측(2026-08-23, 수정 전): `grep -rn "manualUpdate" frontend/src` → 정의부(`api/batchApi.ts:9`) 1건뿐, **호출부 0건**. `grep -rn "sourcingDiscoveryApi" frontend/src` → `candidate(` 호출 **0건**이며 API 모듈에 함수 자체가 없음(`candidates`/`customsBlocked`/`reject`만 존재).
- 백엔드 계약 대조(추측 금지 — 소스 실독): `ManualUpdateRequest(List<PriceStockItem> items)` / `PriceStockItem(Long productId, BigDecimal price, Integer stock)`. `BatchPriceStockService.manualUpdatePriceStock:138` 실측 — **price가 null이면 판매가 미변경, stock이 null이면 판매상태 미변경, stock<=0이면 OUT_OF_STOCK·1 이상이면 IN_STOCK**(재고 수량 자체는 저장하지 않고 판매상태만 파생), 변경점이 없으면 "변경사항 없음"으로 성공 처리, 반영 후 `productMarketSyncService.syncPriceStock`으로 **마켓에 즉시 전파**. 응답은 `{batchId, count, message}`(전부 문자열)로 기존 `crawl-and-update`·`by-supplier`와 동일 키셋(D-180a 정비분). `CandidateResponse` 31필드는 프론트 `Candidate` 인터페이스와 필드·순서 일치 — 목록/상세가 같은 DTO다.
- 수정(2026-08-23, fixer-e-batch-sourcing): **백엔드 무변경.** ① 배치 화면 모드를 3종으로 분리 — 기존 2종은 `소싱업체별 자동 산정`·`상품ID 지정 자동 산정`(내부 키 `supplier`/`crawl`), 신규 `값 직접 지정`(`direct`). 모드 전환 시 상단 Alert가 **info(자동 산정: 크롤 매입가로 판매가 계산) ↔ warning(직접 지정: 입력값이 그대로 판매가가 되고 마켓에 즉시 반영)**으로 바뀌고, 직접 지정 모드에서는 자동 산정 전용 입력(마진율·쿠폰율·최소 마진가)이 **화면에서 사라져** 어느 쪽을 실행 중인지 혼동할 여지를 없앤다. 실행 버튼도 초록(`배치 실행`) ↔ 빨강(`입력값 적용`)으로 구분. ② 입력은 한 줄에 한 상품 `상품ID, 판매가, 재고`(탭 구분도 허용). 빈칸 또는 `-`는 "그 값은 유지"로 백엔드 null 의미에 그대로 대응. ③ 소싱 API 모듈에 `candidate(id)` 추가, 신규 `CandidateDetailDrawer`(720px, DiscoveryPage의 기존 Drawer 관례)가 상세를 **서버에서 다시 조회**해 목록에 없는 필드 중심으로 표시(소싱처·외부ID / 정가 / 매입가+할인율 / 베스트셀러 순위 / 예상 판매가·마진율 / 국내 최저가·중앙값 / 수요 키워드 / 월 검색량·경쟁수 / 발견·최근확인 시각 / 제외 사유 / 통관 판정 사유 / 점수 근거(`ScoreBreakdownPanel` 재사용) / 성분 원문 전문). 목록 행 클릭과 행 우측 `상세` 버튼 두 경로로 열리며, 이미 배선돼 있던 거절을 Drawer 헤더에서도 이어서 실행할 수 있게 연결(거절 성공 시 목록 캐시에서 제거 — `removeCandidate`로 공통화).
- 오실행 가드(배치는 비동기·실행 중 배포 금지 작업이라 되돌리기 어렵다): 빈 입력 → 실행 안 함(경고 토스트). 파싱 실패는 **부분 실행 없이 전량 중단**하고 `N번째 줄 — 사유: "원문"` 형식으로 어디가 틀렸는지 지목한다(상품ID 비정수/0 이하, 같은 상품ID 중복, 3열 초과, 판매가 0 이하·비숫자, 재고 음수·비정수, 판매가·재고 둘 다 빈 줄). 통과 후에는 **확인 모달 필수** — `입력한 값을 N개 상품에 적용` 제목에 판매가 변경 건수·판매상태 변경 건수, 상품별 적용 결과(`#1024 판매가 32,900원 · 재고 12 → 판매중`) 최대 20건 미리보기, 마켓 즉시 반영 경고를 담고 확인 버튼은 danger. 확인 후에만 POST하며 응답의 `batchId`·`count`를 토스트로 알리고 기존 `startTracking`으로 진행현황 카드에 물린다(기존 SSE·폴링 관례 그대로 재사용).
- 게이트(2026-08-23, 격리 트리 — `git archive HEAD` + 본 결함 4파일만 덮어쓰기, 병렬 fixer 편집 차단): `npx tsc -p tsconfig.app.json --noEmit` exit **0** · `npm run build` exit **0** · `npx eslint .` exit **0**, 경고 **5개(기준선 5개와 동일 — 전부 기존 `OrderGrid`/`ProductGrid`의 TanStack `useReactTable` 관련, 본 결함 4파일 경고 0)**. Green 실측: `grep -rn "batchApi.manualUpdate\|sourcingDiscoveryApi.candidate("` → `BatchUpdatePage.tsx:196`, `CandidateDetailDrawer.tsx:43`.
- 미검증(명시): FE 런타임 테스트 인프라 부재로 자동 테스트 없음. 실제 배치 실행은 운영 판매가를 바꾸므로 미실시 — **배포 후 상품 1건으로 소액 검증 권장**(판매가만 지정 → 진행현황 성공 확인 → 마켓 반영 확인). 소싱 상세도 후보 데이터가 있는 환경에서의 라이브 확인 필요.
- 반려 수정 1건(2026-08-23, verifier-p3e 지적 — `BatchUpdatePage.tsx:80` 판매가 상한 부재): 파서에 하한만 있어 `1024, 999999999999999999999`가 통과, `price: 1e+21`이 전송되면 `BatchPriceStockService:171`의 `price.intValue()`가 int 오버플로로 **-559,939,584**가 되어 **사용자에게 한 번도 표시되지 않은 음수 가격이 마켓에 전파**된다(DB엔 1E+21 저장). 백엔드에 수치 검증이 없어 프론트가 유일한 방어선. `MAX_PRICE = 100_000_000` 상한 추가 — 오버플로와 함께 `1e9`(10억) 같은 오타도 차단한다. 같은 파서의 재고에도 `MAX_STOCK = 1_000_000` 상한을 추가했다(Integer 범위 초과 시 Jackson 역직렬화 400이 나던 것을 줄 단위 메시지로 앞단 차단 — 데이터 오염은 없던 경로라 verifier 지적 대상은 아니었음). 재검 실측(현행 소스에서 파서 추출 후 node 실행): 재현 입력 `1024, 999999999999999999999` → **REJECT**("판매가는 0보다 크고 100,000,000원 이하여야 합니다"), `1024, 100000001` → REJECT, `1024, 100000000` → ACCEPT(`intValue()` 안전), `1024, 1e9` → REJECT, `1024, , 99999999999` → REJECT, 정상 입력 3종(`32900,12` / `,0` / `18500,`)은 ACCEPT 유지. 수정 후 게이트 재실행: tsc 0 / build 0 / eslint 0 errors·경고 5(기준선 동일, 본 결함 4파일 0).
- 부수 정리(2026-08-23, 리더 지시): 도달 불가하던 `priceCell.replace(/,/g, '')` 제거(라인이 이미 `/[,\t]/`로 분할된 뒤라 셀에 콤마가 남을 수 없음). 확인 모달 미리보기 20건 제한은 유지(건수는 전량 표시).
- **전제 정정 + 추가 결함 차단(2026-08-23, 죽은 코드 제거 채증 중 fixer 발견)**: verifier-p3e와 리더가 공유한 "천 단위 콤마 입력은 '항목이 3개를 넘습니다'로 안전 거부된다"는 전제가 **거짓**이었다. 실측 — `1024, 32,900`은 정확히 3셀(`1024`/`32`/`900`)로 쪼개져 **거부되지 않고 판매가 32원·재고 900으로 통과**했다(`1,024,900`은 상품ID 1·판매가 24·재고 900). 콤마가 4셀을 만들 때만 거부되므로(`1024, 32,900, 12`) 2콤마 형태가 구멍이었다. 스프레드시트 복사·붙여넣기에서 매우 흔한 입력이고, 정가 32,900원 상품이 **32원**이 되어 마켓에 전파되는 경로다(확인 모달에는 `판매가 32원`으로 표시되므로 "표시값=전송값" 약속 자체는 유지 — 조용한 오염이 아니라 사람이 스킵하기 쉬운 오독). 라인 분할 **전에** `THOUSANDS_COMMA = /\d,\d{3}(?!\d)/`로 검사해 거부하도록 수정하고, 입력 힌트에 "판매가에 천 단위 콤마는 넣지 마세요(32900)"를 추가했다. 권장 표기(`1024, 32900, 12`)·탭 구분·`-` 유지 표기는 전부 영향 없음. 공백 없는 압축 표기(`1024,900`)는 천 단위 콤마와 구별이 불가능하므로 **보수적으로 거부**하고 띄어쓰기를 안내한다(적용 오류보다 재입력이 안전한 방향).
- 후속 후보 관찰 ④(이번 사이클 산물 아님 — 기존 백엔드 동작, verifier-p3e 제기 · fixer 소스 확인): `ProductMarketSyncService:41-44`가 `quantity = soldOut ? 1 : Product.DEFAULT_IN_STOCK_QUANTITY`(=`Product.java:37`, **999**)로 계산해 `client.syncPriceAndStock(...)`에 넘기므로, 가격/재고 동기화가 **마켓의 재고 수량을 항상 1 또는 999로 무조건 덮어쓴다**. `syncPriceStock`(수동 지정)과 `syncPriceStockPerMarket`(`by-supplier`·`crawl-and-update`)이 같은 계산을 공유한다. 신규 UI가 이 경로를 처음으로 사람 손에 직접 노출시키므로 기록해 둔다.
- 요지: `_workspace/fixes/D-204_fix.md`
- 3차 재검(2026-08-23, verifier-p3e): fixer가 제기한 천 단위 콤마 반례를 검증자가 PASS를 준 트리에서 직접 재현해 **1차 판정서의 B-5-2("콤마 입력은 안전 거부됨")를 오판으로 무효 처리**했다. 원인은 검증자 케이스 설계 결함 — 콤마 케이스를 3필드 형태(`1024, 32,900, 12`, 콤마 3개→4셀→거부) 하나만 시험해 "콤마는 거부된다"는 일반 명제를 끌어냈고, **필드 개수 축을 콤마 축과 교차시키지 않아** 2필드 형태(3셀→통과)를 놓쳤다. 검증자 독립 실측: `1024, 32,900`→price 32·stock 900 / `1,024,900`→id 1·price 24·stock 900, `1,234,567`→id 1·price 234·stock 567 전부 수정 전 ACCEPT였음이 확인됐다. 수정 후 5형태 전부 차단·정상 표기 회귀 0 확인. 추가 확인 2건 — ① `THOUSANDS_COMMA`에 `/g` 플래그가 없어 `.test()`가 무상태(있었다면 `lastIndex` 때문에 행마다 검사가 번갈아 스킵됐을 자리, 다중 행으로 실증해 행 번호 정확) ② 오탐 경계는 **콤마 뒤 공백이 없고 마지막 필드가 정확히 3자리**일 때로 한정(`1024,32900,100`·`1024,123`), 공백 있는 입력은 전량 통과하며 권장·플레이스홀더 표기가 모두 공백을 쓰므로 보수적 거부 트레이드오프가 타당하다고 판정. 잔여 우회 3종(`1024, 32, 900`/`32,9000`/`32,90`)은 천 단위 콤마 표기가 아니고(천 단위 그룹은 정의상 3자리, `toLocaleString`·스프레드시트는 콤마 뒤 공백을 넣지 않음) 모달 불변식도 유지되므로 조치 불요 — 이 클래스의 최종 방어선은 정규식이 아니라 확인 모달의 항목별 미리보기다.
- 라이브 검증(2026-08-24, 리더 직접): 모드 3종 탭 분리·직접 지정 모드의 경고색 안내와 빨간 버튼으로 자동 산정과 명확히 구분됨 확인. **천 단위 콤마 차단 실동작**(`1024, 32,900, 12` → '1번째 줄 — 천 단위 콤마로 읽힐 수 있는 숫자가 있습니다' + 올바른 표기 안내). 정상 입력 시 확인 모달이 '#3194 판매가 32,900원 · 재고 12 → 판매중'과 즉시 마켓 반영 경고를 표시 — 취소로 종료(실제 적용 안 함). 소싱 후보 상세 Drawer도 목록에 없는 정보(정가·매입가·예상 마진율·국내 시세·통관 판정 사유·점수 기여도·성분 원문) 정상 렌더.
- 상태: **PASS(verifier-p3e 재검 통과, 2026-08-23)** — 1차 판정 `부분`의 반려 1건(판매가 상한) 해소 확인. 검증자 독립 재추출 24케이스 실행, 경계 우회 없음(`Math.round`가 상한 검사 뒤라 `99999999.6`→`100000000`에서 멈추고 `100000000.4`는 거부), 종단 간 `intValue()=100000000` 원값 일치(Integer.MAX_VALUE 대비 21배 여유). `MAX_STOCK` 추가분도 별도 판정 **수용**(Jackson 400을 줄 단위 메시지로 앞당긴 UX 개선, 기능 손실 없음 — 재고는 부호만 의미를 갖고 100만은 도달 불가값). 변경 격리 확인: 1차 검증본 대비 `BatchUpdatePage.tsx` 1파일만 변경, 소싱 3파일 바이트 무변경으로 1차 판정 유효. 백엔드 `git status` 0건이라 1차 1,251테스트 통과 승계.

### D-205: antd 정적 `Modal.confirm`이 React 19에서 조용히 무동작 — 확인창을 쓰는 기능 3건이 7주간 죽어 있었다 (2026-08-23, 리더 라이브 실증 · 리더 지시 신규 등재)

- 심각도: **P1**(기능 전면 불능 3건 · 7주 잠복) | 위치: `frontend/src/App.tsx`, `frontend/src/pages/product/ProductGrid.tsx:148`, `frontend/src/pages/product/MarketBadgeCell.tsx:83`, `frontend/src/pages/BatchUpdatePage.tsx:224`
- 증상: antd v5의 **정적 메서드** `Modal.confirm`이 React 19에서 다이얼로그를 마운트하지 못한다. 예외도 콘솔 출력도 없이 no-op이라 `onOk` 콜백이 영원히 호출되지 않는다. 버튼을 눌러도 확인창이 뜨지 않고 화면이 완전히 무반응이다.
- **라이브 실증(2026-08-23, 리더 · 배포 `24c7639`)**: 상품관리에서 상품 1건 체크 → `선택 삭제 (1)` 클릭 → 확인창 없음, 상품 수 3,195 유지, 콘솔 오류 없음.
- **원인(설치본 소스로 확정한 4단 체인)**: ① `antd/lib/modal/confirm.js:96`이 `unstableSetRender()`로 얻은 렌더 함수로 다이얼로그를 별도 컨테이너에 렌더 → ② `antd/lib/config-provider/UnstableContext.js:14`의 `defaultReactRender`가 `rc-util/lib/React/render`에 위임 → ③ `rc-util@5.44.4/lib/React/render.js:21`이 `{...ReactDOM}`에서 `createRoot`/`render`/`unmountComponentAtNode`를 꺼내는데 **React 19가 이 셋을 `react-dom` 최상위에서 제거**해 전부 `undefined` → ④ `render()`가 `if (createRoot)` 실패 후 `legacyRender`로 내려가 `reactRender?.(node, container)` **옵셔널 호출로 아무 일도 하지 않고 반환**. antd에 호환 경고가 있긴 하나(`UnstableContext.js:20`, "antd v5 support React is 16 ~ 18") `NODE_ENV !== 'production'` 가드 안이라 **운영 빌드에서 통째로 제거된다** — 7주간 아무 신호도 없던 이유.
- **발생 시점**: `95561cda`(2026-07-06 "Phase 0 기반 환경 업그레이드")의 React 19 전환. React 18에서는 `ReactDOM.createRoot`가 최상위에 있어 정상 동작했다.
- Red 실증(2026-08-23, 설치된 실 의존성을 Node로 직접 실행): `react-dom 19.2.7`에서 `typeof ReactDOM.createRoot|render|unmountComponentAtNode` **전부 `undefined`**, `rc-util` `render(node, container)` 호출 결과 **예외 없음 · 컨테이너에 `__rc_react_root__` 미생성 · 컨테이너 무변경** — 라이브 증상(무반응 + 콘솔 무출력)과 정확히 일치.
- **전수 조사 결과 — 정적 호출은 이 3건이 전부**: ⓐ `ProductGrid.tsx:148` 상품 일괄 삭제 → `bulkDeleteProducts` 미호출로 **선택 삭제 전면 불능** ⓑ `MarketBadgeCell.tsx:83` G마켓·옥션 마켓플러스 핸드오프 → `window.open`+클립보드 복사 미실행으로 **핸드오프 불능**(선행 `getMarketPlusHandoff` API는 이미 성공한 뒤라 서버 왕복만 낭비) ⓒ `BatchUpdatePage.tsx:224` 값 직접 지정 배치(D-204 신설) → `runManualUpdate` 미호출로 **배치 실행 불능**. 셋 다 "확인을 받아야 실행"이라 **안전 실패**(데이터 손상 없음)지만 기능은 통째로 죽어 있다. **대상 아님(구분 확인)**: `ProcessStatusPage.tsx:214`·`ProductGrid.tsx:271`·`MarketBadgeCell.tsx:196`·`ProductDetailModal.tsx`의 `<Modal open=…>`/`Popconfirm`은 JSX 컴포넌트라 앱 트리에서 React가 직접 렌더 → rc-util 경로를 타지 않는다. `message.*`/`notification.*`은 **저장소 전체 0건**([[D-180e]]가 `utils/notify`로 통일해 둔 덕에 사정권 밖 — 남아 있었다면 토스트도 전부 죽었을 것). `from 'antd/...'` 딥 임포트 0건. `Settings.tsx` `alert()` 6건·`OrderGrid.tsx:350` `window.confirm`은 브라우저 네이티브라 정상 동작([[D-202]] 소관).
- **놓친 경위**: D-202 기록이 "`ProductGrid.tsx:148`·`MarketBadgeCell.tsx:83`의 `confirm`은 `AntModal.confirm`이라 네이티브가 아니다"라고 두 호출부를 정확히 짚었지만, **antd 쪽이 죽어 있다는 사실 자체가 미지**여서 그대로 지나갔다. **교훈: 라이브러리 메이저 업그레이드 시 "빌드가 통과했다"는 런타임 무동작을 전혀 배제하지 못한다 — 특히 React 트리 밖에 렌더하는 명령형 API(정적 메서드·portal 계열)는 타입도 빌드도 잡지 못한다.**
- 수정(2026-08-23, fixer-antd-confirm): **백엔드 무변경 · 의존성 무변경**(`package.json`·`package-lock.json` 손대지 않음, `npm install` 불필요). 리더 제시 2안 중 **(b) 훅 전환** 채택 — (a) `@ant-design/v5-patch-for-react-19`는 의존성 추가 + React 내부 몽키패치인데 호출부가 3곳뿐이라 근본 해소가 더 싸다. (b) 안에서도 `Modal.useModal()` 대신 **`App.useApp()` + 앱 루트 단일 프로바이더**를 골랐다 — **`MarketBadgeCell`이 그리드 행마다 렌더되는 셀 컴포넌트라 `useModal`의 `contextHolder`가 페이지당 50개(기본 페이지 크기) 생긴다**는 점이 결정적. ① `App.tsx`의 `<ConfigProvider>` 바로 안쪽을 `<AntApp component={false}>`로 감쌈 ② 3개 호출부에 `const { modal } = AntApp.useApp();` 추가하고 `AntModal.confirm` → `modal.confirm`으로 수신자만 교체. `BatchUpdatePage`는 JSX 모달이 없어 `Modal as AntModal` 임포트가 완전히 불필요해져 `App as AntApp`로 교체, 나머지 두 파일은 JSX 모달을 계속 쓰므로 `AntModal` 임포트 유지.
- **DOM 무변경 확보**: `<AntApp>` 기본값이 `component='div'`라 `<div class="ant-app">` 래퍼가 생기는데, `ProductGrid`가 `height:'100%'`로 부모 높이에 의존해 레이아웃이 깨질 수 있다. **`component={false}`로 `React.Fragment` 렌더**시켰다(`antd/lib/app/App.js:54`). SSR 문자열 대조로 실증 — 래퍼 유무 두 트리의 출력이 **바이트 동일**. `component === false` dev 경고는 `cssVar` 사용 시에만 발생하는데(`App.js:52`) 이 저장소 `ConfigProvider`는 `theme.token.colorPrimary`만 있어 `cssVar`가 `undefined`(`config-provider/index.js:419`) — 해당 없음, 게이트의 신규 경고 0으로도 확인.
- **확인창 계약 불변 증명**: diff는 수신자만 바뀐다 — `title`·`content`·`okText`·`okType:'danger'`·`cancelText`·`width`·`onOk` 본문 한 글자도 미변경. `useModal`은 `withConfirm` 등 **설정 변환기만** 공유하고(`antd/lib/modal/useModal/index.js:100`) 다이얼로그는 `ElementsHolder`(`:17`) 안의 `HookModal`로 React 트리에서 직접 렌더하므로(`:102`), 옵션 해석은 동일하고 렌더 경로만 정상화된다. `useModal/index.js`의 `require` 목록에 `rc-util/lib/React/render`가 **없다** — 깨진 3·4단계를 구조적으로 통과하지 않는다.
- Green 실증(런타임 테스트 인프라 부재 → `react-dom/server`로 호출부 트리를 직접 렌더): `App.useApp()`의 `typeof modal.confirm`이 **프로바이더 없는 옛 트리에서 `undefined` → `<AntApp component={false}>` 하위 새 트리에서 `function`**. 3개 호출부의 실제 중첩 형태를 각각 재현해 전부 `function` 확인 — 특히 **`BatchUpdatePage`는 자기 `<ConfigProvider theme={{colorPrimary:'#166534'}}>`를 return 안에 중첩**하고 있어 프로바이더 중첩이 배선을 끊지 않는지 별도 확인이 필요했다.
- 게이트(2026-08-23): `npx tsc -p tsconfig.app.json --noEmit` exit **0** · `npm run build` exit **0** · `npx eslint .` exit **0, errors 0**. 경고는 `git archive HEAD` 격리 트리에서 뽑은 기준선과 **규칙·파일 단위로 완전 동일**(5건→5건, 신규 0): `OrderGrid.tsx:195·437·474 exhaustive-deps`, `OrderGrid.tsx:475 incompatible-library`, `ProductGrid.tsx:134→135 incompatible-library`(훅 1줄 추가만큼 밀린 것, 기존 TanStack `useReactTable` 관련으로 본 수정과 무관).
- **남긴 위험(명시)**: `App.useApp()`을 `<App>` 프로바이더 **밖**에서 호출하면 `AppContext` 기본값이 `{message:{},notification:{},modal:{}}`이라(`antd/lib/app/context.js:12`) `modal.confirm`이 `undefined` → **TypeError로 터진다**. 지금의 조용한 무동작과 달리 시끄럽게 실패하므로 발견은 쉽지만 실패 성격은 바뀌었다. 현 3개 호출부는 전부 `App.tsx` 라우트 트리 하위임을 위 Green 실증으로 확인 — **향후 `App.useApp()` 사용처 추가 시 프로바이더 하위인지 확인 필요.**
- 미검증(명시): FE 런타임 테스트 인프라 부재로 자동 테스트 없음. 라이브 검증 미실시 — 배포 후 ⓐ 상품 일괄 삭제(취소로 미실행 확인 후 삭제 실행 → 3,195→3,194) ⓑ G마켓/옥션 배지 클릭 핸드오프 ⓒ 값 직접 지정 배치(확인창까지, 실행은 D-204 미검증 항목과 동일하게 소액 판단 후) 3건 필요. 부수로 기존 JSX 모달 2종(상품 상세, 마켓 등록)도 눈으로 확인 권장.
- 요지: `_workspace/fixes/D-205_fix.md`
- 라이브 검증(2026-08-24, 배포 7c85409d · 리더 직접): 상품관리에서 상품 1건 선택 후 '선택 삭제' → **확인창 정상 표출**('상품 1개 삭제 / 되돌릴 수 없습니다. 진행할까요?') — 취소로 종료. 값 직접 지정 배치의 확인 모달도 정상 표출 확인. 7주간 불능이던 확인창 의존 기능 복구 실증.
- 상태: **검증통과 (라이브 검증 완료)**

### D-206: 마켓 실시간 조회가 마켓 상품번호 대신 로컬 상품 PK로 조회한다 — 스토어는 404, 11번가는 조용한 오답 (2026-08-24, 리더 라이브 실증 · 리더 지시 신규 등재)

- 심각도: **P2**(기능 오동작 — 4마켓 중 3마켓 전면 오조회, 그중 1마켓은 조용한 오답) | 위치: `backend/core/src/main/java/com/sbshop/agent/core/application/market/MarketRegistrationService.java:46-49`, `backend/core/src/main/java/com/sbshop/agent/core/domain/market/MarketRegistration.java:95-107`, `frontend/src/pages/product/MarketLiveCompare.tsx:238`
- 증상: [[D-203]]이 화면에 노출시킨 "마켓 실제값 확인"이 `syncMarketLive`를 타는데, 이 메서드가 `reg.extractVendorItemId()`로 식별자를 구하고 **비면 `String.valueOf(reg.getProductId())` 즉 로컬 PK로 폴백**한다. `extractVendorItemId()`는 identifiers JSON에서 **`vendorItemId` 키 하나만** 읽고 그 키는 **쿠팡에만 존재**하므로, 쿠팡 외 3마켓은 전부 로컬 PK로 조회한다.
- **운영 실측(`sb_market_registration`, 상품 1592)** — 마켓별 identifiers에 `vendorItemId`가 없음이 데이터로 확인됨:
  - `ELEVEN_STREET | {"sellerPrdCd":"220227IHB052","prdNo":"4193852605"}`
  - `COUPANG | {"externalVendorSku":"…","sellerProductId":"14813281569","vendorItemId":"89379432362","productId":"70535073"}`
  - `CAFE24 | {"product_no":"17624","product_code":"P000BABW","gmarket_goodsNo":"…","custom_product_code":"…"}`
  - `SMART_STORE | {"originProductNo":"6321468668","sellerManagementCode":"…","channelProductNo":"6351684748"}`
- **라이브 실증(2026-08-24, 리더)**: 스마트스토어 → `[Smartstore GET Error] path: /v2/products/origin-products/1592, msg: 404 Not Found: {"code":"NOT_FOUND","message":"존재하지 않는 상품입니다."}` → 화면 "조회 실패"(요란한 실패). 11번가 → 호출 200이나 **응답 필드 전부 null** → 전 항목 "마켓 미제공" + 초록색 "대조 가능한 항목 모두 일치"(**조용한 오답 — 더 위험**).
- **11번가가 200을 주는 이유(어댑터 소스로 확정 — 마켓이 잘못된 ID를 수용한 것이 아니다)**: `ElevenstMarketRestClient.sendRequest:59-66`이 `responseCode >= 400`이면 `getErrorStream()` 본문을 **그대로 String으로 반환**하고, IOException도 `<resultCode>ERROR</resultCode>` 합성 XML로 삼켜 **어떤 경우에도 예외를 던지지 않는다**. 그 위에서 `ElevenstMarketClient.extractMarketItem:62`이 `extractXmlValue`로 태그를 뽑는데 이 헬퍼는 태그 부재 시 **빈 문자열**을 돌려준다(`:348-355`). 결과적으로 `name=""`·`mappingKey=""`이고 price/stock/brand는 애초에 매핑하지 않으므로 DTO 전 필드가 null → 화면 전 항목 "마켓 미제공". 즉 HTTP 상태는 **어댑터가 통째로 버려서** 보이지 않을 뿐이다. 올바른 `prdNo`로는 같은 경로가 `prdNm`/`prdNo`를 담은 정상 XML을 반환하므로 상품명·마켓 식별자 행이 채워진다(단 11번가 `extractMarketItem`은 name·mappingKey 2필드만 매핑하므로 **나머지 행이 "마켓 미제공"으로 남는 것은 정상**이며, 이것이 아래 ③ 문구 교정이 필요한 이유다).
- **어댑터 API 경로 대조표(추측 금지 — `extractMarketItem` 소스 실독으로 확정)**: 리더 계약이 제시한 쿠팡=`vendorItemId`는 **코드와 불일치**하여 정정했다.

  | 마켓 | `extractMarketItem` 경로 | 필요한 식별자 | 근거 |
  |---|---|---|---|
  | COUPANG | `…/marketplace/seller-products/{id}?vendorId=` (`CoupangMarketClient:110`) | **`sellerProductId`** (계약의 `vendorItemId` 아님) | 같은 메서드가 응답을 `dataMapper.buildIdentifiers(marketItemId, …)`에 넘기는데 이 메서드의 **파라미터명이 `sellerProductId`**(`CoupangDataMapper:17`)이고 본문이 `ids.put("sellerProductId", <그 인자>)`. `vendorItemId`는 응답 item에서 따로 뽑는다. `extractDeleteCode()`도 쿠팡에 `sellerProductId`를 쓴다 |
  | SMART_STORE | `/v2/products/origin-products/{id}` (`SmartstoreMarketClient:94`) | `originProductNo` | `publish()`가 이 경로 응답의 `originProductNo`를 저장하고, `syncPriceAndStock`이 같은 경로 GET/PUT에 쓴다 |
  | ELEVEN_STREET | `/rest/prodservices/productinfo/{id}` (`ElevenstMarketClient:63`) | `prdNo` (없으면 `elevenstId` 폴백) | 응답에서 `prdNo`를 되읽고, `deleteFromMarket`이 같은 값을 `prdNo`로 로깅. **폴백이 필요한 이유**: `publish()`가 prdNo 값을 `elevenstId` **키로** 저장하는데(`:48`) 운영 1592행은 `prdNo` 키를 쓴다 — 두 키가 같은 값을 담고 있어 둘 다 읽어야 전건이 해소된다 |
  | CAFE24 | `/admin/products/{id}?embed=variants` (`Cafe24MarketClient:130`) | `product_no` | `publish()`가 응답 `product.product_no`를 저장. `product_code`(`P000BABW`)는 숫자 경로에 넣을 수 없다 |
  | GMARKET·AUCTION | 없음 | — | `MarketClientRouter`에 클라이언트 자체가 없음([[D-203]] 기록) |
- **`extractVendorItemId()` 타 호출부 전수 조사(`grep -ra`)**: 프로덕션 호출부는 `CoupangOrderAdapter:80·114·175` **3곳뿐이며 전부 쿠팡 전용 경로에서 의미대로 올바르게 사용**한다(주문 항목의 vendorItemId 대조). 따라서 **이 메서드는 손대지 않고** 신규 해석기를 별도로 두었다 — 기존 호출부의 의미를 바꿨다면 그것이 회귀다.
- Red 실측(2026-08-24, 수정 전 서비스 코드 · Mockito strict stub 인자 불일치로 실제 전달값이 그대로 노출됨):
  - 스마트스토어: `marketClient.extractMarketItem("1592");` ← **로컬 PK** (기대 `"6321468668"`), `at MarketRegistrationService.syncMarketLive(MarketRegistrationService.java:52)`
  - 쿠팡: `marketClient.extractMarketItem("89379432362");` ← **vendorItemId** (기대 `sellerProductId` `"14813281569"`) — 쿠팡도 잘못된 식별자를 쓰고 있었음이 여기서 드러났다
  - 11번가·카페24 동일하게 `"1592"`, 식별자 부재 케이스와 미지원 마켓 케이스는 예외가 나지 않아 `AssertionError`
  - 6개 테스트 전부 FAILED (`17 tests completed, 6 failed`)
- 수정(2026-08-24, fixer-d206): ① `MarketRegistration`에 **신규** `liveLookupKeys(MarketType)` + `extractLiveLookupId()` 추가 — 키는 `COUPANG_LOOKUP_KEY`/`SMART_STORE_LOOKUP_KEY`/`ELEVEN_STREET_LOOKUP_KEY`/`ELEVEN_STREET_LOOKUP_FALLBACK_KEY`/`CAFE24_LOOKUP_KEY` 상수로 승격(기존 `GMARKET_IDENTIFIER_KEY` 선례 따름). 값 추출은 기존 `identifier(key)` 재사용(빈 문자열→null, 파싱 실패 내성 포함). ② `syncMarketLive`의 **로컬 PK 폴백 제거** — 미지원 마켓은 `"<라벨> 실시간 조회를 지원하지 않는 마켓입니다"`, 식별자 부재는 `"<라벨> 상품번호(originProductNo)가 등록 정보에 없습니다 — 등록 정보를 먼저 동기화하세요: productId=…"`로 `IllegalStateException`. `GlobalExceptionHandler:35`가 이를 400 + `{success,message}`로 내려주고 `MarketLiveCompare.extractErrorMessage`가 그 message를 그대로 표시하므로 사용자에게 원인과 조치가 함께 도달한다. ③ 프론트 문구 교정 — 대조된 항목 수(`state !== 'na'`)를 세어 **0건이면 `마켓이 값을 주지 않아 대조할 항목이 없습니다`(주황)**, 차이 있으면 기존 `다른 항목 N개`, 그 외에만 `대조한 N개 항목 모두 일치`(초록). 이제 초록불은 **실제로 비교된 항목이 1개 이상일 때만** 뜬다.
- **기존 `extractMarketCode()`와 구분(왜 재사용하지 않았나)**: `extractMarketCode()`는 **쓰기 경로(`syncPriceAndStock`)용**이라 쿠팡에서 `vendorItemId`를 먼저 돌려주는데, 쿠팡 가격/재고 API는 `…/vendor-items/{vendorItemId}` 경로라 그쪽이 맞다. 두 경로가 요구하는 식별자가 마켓별로 다르므로(쿠팡 읽기=sellerProductId / 쓰기=vendorItemId) 별도 해석기가 필요하다.
- 게이트(2026-08-24): `./gradlew :core:test :infrastructure:test :api:test :worker:test spotlessCheck` **BUILD SUCCESSFUL** — core **780**(기준선 770 +10: 신규 도메인 6 + 서비스 6, 로컬 PK 폴백을 고정하던 옛 테스트 2개 제거) · infrastructure **225** · api **184** · worker **72**, 실패·에러 **0**, spotless 전 모듈 통과(`spotlessApply` 미실행). 프론트: `npx tsc -p tsconfig.app.json --noEmit` exit **0** · `npm run build` exit **0** · `npx eslint .` **errors 0**, 경고 5(기준선 5와 동일 — 전부 기존 TanStack `useReactTable` 관련, 본 결함 파일 경고 0).
- 미검증(명시): 라이브 미실시 — 배포 후 상품 1592에서 4마켓 각각 "마켓 실제값 확인" 필요. 특히 ⓐ 스마트스토어가 `origin-products/6321468668`로 200을 받고 상품명·판매가·재고가 채워지는지 ⓑ 11번가가 `productinfo/4193852605`로 `prdNm`을 반환하는지(나머지 행 "마켓 미제공"은 매핑 부재라 정상) ⓒ 쿠팡이 `seller-products/14813281569`로 정상 조회되는지(**식별자가 바뀌었으므로 기존 성공 경로의 회귀 여부를 반드시 확인**).
- 후속 후보(이번 사이클 대상 아님 — 고치지 않고 기록만): ① `ElevenstMarketRestClient.sendRequest`가 HTTP 4xx/5xx와 IOException을 전부 정상 문자열로 반환해 **11번가 전 경로에서 실패가 조용히 성공으로 둔갑**한다(이번 결함의 "200처럼 보임"의 실제 원인). ② `extractMarketCode()`의 폴백 키가 읽기 경로엔 부적합 — SMART_STORE는 `channelProductNo`, CAFE24는 `product_code`로 폴백하는데 둘 다 해당 마켓의 origin-products/admin-products 경로에 넣을 수 없는 값이다. 현재 쓰기 경로에서만 쓰이나 같은 계열의 잠복 오조회. ③ 11번가 `extractMarketItem`이 name·mappingKey 2필드만 매핑해 대조 가치가 낮다(가격·재고·브랜드 미매핑).
- 요지: `_workspace/fixes/D-206_fix.md`
- 라이브 검증(2026-08-24 17:1x KST, 배포 1ac6ce9a · 리더 직접, 상품 1592): **스마트스토어 복구 실증** — 수정 전 `404 존재하지 않는 상품`이던 호출이 이제 실데이터 반환(`salePrice=68200`, `stock=999`, rawData에 상품명·`statusType=SUSPENSION`). **배선 즉시 실제 불일치 발견**: 로컬 DB 70,600원·IN_STOCK vs 스토어 실제 68,200원·**판매중지(SUSPENSION)** — 이 기능의 존재 목적이 실증된 사례로 [[D-207]] 운영 확인 항목으로 분리. 11번가는 식별자 해석이 정정됐음에도 마켓이 인증을 거부(`-997 등록된 API 정보가 존재하지 않습니다`) — 우리 결함이 아니라 11번가 API 등록 문제로 [[D-208]] 분리 등재.
- 상태: **검증통과 (라이브 검증 완료 — 스토어 복구 실증, 11번가는 외부 요인으로 분리)**

### D-210: 마켓↔DB 식별자 정합성을 전량 대조할 수단이 없다 — 누락·유령 식별자·판매중지 규모 미측정 (2026-08-25, 사용자 요구)

- 심각도: P1(매출 직결 — [[D-207]]·[[D-209]]의 전수 파악이 이 기능 없이는 불가) | 위치: 기능 부재(신규 구축)
- 요구 원문 취지: "우리 상품이 3천개면 카페24 상품도 3천개여야 하고, 우리 시스템의 쿠팡 식별자가 2천개면 실제 쿠팡 상품도 2천개여야 한다. 다르다면 **마켓엔 있는데 우리가 누락한 것**과 **우리에만 있고 마켓엔 없는 잘못된 식별자**를 각각 파악해달라. 전 마켓을 둘러 100% 싱크를 맞추고 싶다."
- 운영 실측 기준선(2026-08-25): 상품 3,195건. 등록행 카페24 3,186·스토어 3,186·11번가 2,286·쿠팡 1,262. SB코드 보유율 카페24 3,184/3,186 · 쿠팡 1,261/1,262 · 11번가 2,286/2,286 · 스토어 3,183/3,186.
- **이번 사이클은 읽기 전용 리포트까지다. 교정(식별자 재수집·삭제·재등록)은 사용자가 리포트를 본 뒤 별도 사이클.**

#### D-210-a: 마켓 클라이언트 카탈로그 조회 (담당: fixer-catalog-clients)

- 계약(확정·구현완료): core에 `MarketCatalogEntry(String sellerCode, Map<String,String> identifiers, String status)` 신설, `MarketClient`에 `fetchCatalog(long throttleMs)`(기본값 null = 미지원)와 `fetchBySellerCode(String)` 추가. 4마켓 전부 `fetchCatalog` 구현, `fetchBySellerCode`는 쿠팡만 구현(나머지는 인터페이스 기본값).
- **계약 이탈 1건(리더 확인 요망)**: `fetchBySellerCode`의 기본 구현을 지시된 `null` 대신 `Optional.empty()`로 두었다. `Optional`을 null로 돌려주면 core가 `.orElse()`·`.isPresent()`를 부르는 순간 NPE가 나고, 이 코드베이스의 미지원 Optional 관례도 전부 `Optional.empty()`다(`fetchLinkIdentifier`·`removeSellerImmediateDiscount`). 시그니처는 계약 그대로다. **미지원과 미발견을 구분할 수 없다는 손실은 남는다** — 다만 존재 여부의 authority는 `fetchCatalog`이고 이 메서드는 SB코드 보충용이라 실사용 영향은 없다.
- **전 마켓 공통 설계 — 실패 시 부분 결과 금지**: 페이지 순회 도중 실패하면 그때까지 모은 것을 반환하지 않고 **예외를 던진다**(누적 건수를 메시지에 포함). 기존 `fetchAllChannelProductNos`는 실패 시 `break`로 부분 맵을 돌려주는데, 대조 용도에서 부분 카탈로그는 **"마켓에 없는 상품"으로 오독**되어 D-210이 막으려는 바로 그 오답을 만든다. [[D-210-b]] 관측 ③과 같은 판단.
- 마켓별 구현 실측표:

  | 마켓 | API 경로 | 페이징 | 종료조건 | 페이지당 | sellerCode | identifiers | status |
  |---|---|---|---|---|---|---|---|
  | SMART_STORE | `POST /v1/products/search` (body `page`/`size`) | `page` 증가 | `last=true` 또는 `contents` 빈 배열 | **500** | `channelProducts[].sellerManagementCode` | `originProductNo`(contents 레벨) · `channelProductNo` | `channelProducts[].statusType` |
  | CAFE24 | `GET /admin/products?limit&offset&fields` | `offset` +100, 5000 도달 시 `since_product_no` 커서 전환 | 반환 건수 < `limit` | 100 | `custom_product_code` | `product_no` · `product_code` | `display=T,selling=T` (두 원문 플래그 합성) |
  | COUPANG | `GET .../marketplace/seller-products?vendorId&maxPerPage&nextToken` | `nextToken` 승계 | `nextToken` 빈 문자열 | 100 | **null**(목록에 `externalVendorSku` 없음 — item 레벨 필드) | `sellerProductId` (+응답에 있으면 `productId`·`vendorItemId`) | `statusName` |
  | ELEVEN_STREET | `POST /rest/prodmarketservice/prodmarket` (EUC-KR XML) | `start`/`end` 순번 +100 | 파싱된 레코드 수 < `limit` | 100 | `sellerPrdCd` | `prdNo` | `selStatNm`(없으면 `selStatCd`) |

- 스마트스토어 — **추가 호출 0회**: 기존 `fetchAllChannelProductNos`가 이미 순회하던 것과 같은 응답에서 `sellerManagementCode`·`statusType`을 함께 꺼낸다. 기존 메서드는 다른 호출부가 있어 **미변경**하고, 채널상품 선택 로직만 `pickChannelProduct`로 추출해 공유했다(STOREFARM 우선·없으면 첫 채널 — 순수 추출, 행위 동일). 이 추출은 구조 변경이라 커밋 분리 판단은 오케스트레이터 몫.
- 카페24 — **rate limit 존중**: `throttleMs`를 페이지 사이에 실제로 대기(테스트로 경과시간 실측). 429(또는 `Too many`)를 만나면 1s·2s·3s로 **최대 3회 백오프 재시도**하고, 그래도 계속되면 예외. 429가 아닌 실패는 재시도 없이 즉시 예외.
- 쿠팡 — `vendorId`는 `CoupangRestClient.resolveVendorId()`를 신설해 얻는다(기존 private `resolveCredentials()[2]` 노출). `properties.getVendorId()`를 직접 읽으면 **DB 자격증명 우선 규칙을 건너뛴다**. 봉투 검사는 기존 `verifyEnvelopeStrict` 재사용 — `code`가 `SUCCESS`/`200`이 아니면 예외([[D-181]] 계열). `fetchBySellerCode`는 `GET .../seller-products/external-vendor-sku-codes/{SB코드}`로 `data`가 배열/객체 모두 수용, **빈 배열이면 미등록으로 `Optional.empty()`, 404/`NOT_FOUND`도 empty, 그 외 실패는 예외**(호출 실패를 "마켓에 없음"으로 둔갑시키지 않는다). 마켓 제약상 등록 후 1분 경과해야 조회되는 것은 코드로 못 막으니 호출부 유의.
- 11번가 — **[[D-208]] 조용한 오답 차단**: `ElevenstMarketRestClient.sendRequest:59-66`이 HTTP 상태를 통째로 버려 4xx/5xx 본문과 IOException을 정상 문자열로 반환하므로, `fetchCatalog`는 응답의 `resultCode`를 **직접 검사**해 `200`/`0`/부재가 아니면 예외를 던진다. `-997 등록된 API 정보가 존재하지 않습니다`·REST 클라이언트 합성 `<resultCode>ERROR</resultCode>`·빈 응답 3종 모두 예외로 승격(각각 테스트 보유). REST 클라이언트 자체는 11번가 전 경로 공유라 이번 범위 밖으로 두었다(아래 후속 후보).
- **미확정·문서 근거 없는 항목(명시)**:
  - 11번가 요청 XML의 **루트 엘리먼트명(`SearchProduct`)과 `start`/`end` base(1-origin)는 가정**이다. D-208로 이 엔드포인트가 인증 거부 중이라 실호출로 확정할 수 없었다. 종료 판정을 "반환 건수 < limit"으로 둔 것도 총건수 필드의 문서 근거가 없어서다. **11번가 API 등록이 풀리는 즉시 1페이지 실호출로 루트명·base·필드명을 확정해야 한다.**
  - 11번가 응답의 반복 레코드 엘리먼트명도 미확정 — 파서는 `<Product>`/`<product>` 양쪽을 수용한다.
  - 카페24 `custom_product_code`가 **목록 기본 응답에 포함되는지 미확정**. 대신 `fields=`로 **명시 요청**해 기본 필드셋 의존을 없앴다(Cafe24 공식 문서에서 `fields` 파라미터 지원 확인). 그래도 빠지면 `sellerCode=null`로 떨어지고 [[D-210-b]]의 식별자 조인이 대체한다.
  - 카페24 `offset` 상한이 리더 조사 5000 / 공식 문서 8000으로 엇갈린다 — **보수적으로 5000에서 커서 전환**(빨리 전환해도 손해 없음).
- **실호출 검증: 미실시(차단)** — 운영 SSH가 이 세션의 권한 분류기에 거부됐다(`ssh -i ssh-key-2026-06-25.key ubuntu@168.107.31.154`의 `docker ps`·`docker exec ... psql` 모두 `Blocked by classifier`). **4마켓 어느 것도 실호출 확인을 하지 못했다.** 다른 에이전트에게 대신 실행을 요청하는 것은 권한 우회라 하지 않았다. 리더가 직접 1페이지씩 실호출해 ⓐ 카페24 `custom_product_code` 반환 여부 ⓑ 11번가 루트/필드명 ⓒ 쿠팡 목록 `statusName` ⓓ 스토어 `sellerManagementCode` 반환을 확정해야 한다.
- Red 실측(2026-08-25): 1차 신규 30테스트 작성 후 실행 → **`30 tests completed, 29 failed`**. 유일 통과는 쿠팡 `fetchBySellerCode` blank 입력 케이스(인터페이스 기본값이 이미 만족) — 가짜 Red가 아님을 확인한 지점. 2차(아래 동료 검토 반영) 신규 7테스트 → **`37 tests completed, 7 failed`**.
- **동료 검토로 발견한 자체 결함 2건 (D-210-b fixer 지적 → 코드 실독으로 확인 → TDD 수정)**: D-210-b가 "카탈로그는 전 페이지 전량이어야 한다 / 식별자 값은 문자열 정규화여야 한다"를 재확인 요청했고, 주장 검증차 내 코드를 다시 읽어 **둘 다 실제로 위반하고 있었음**을 확인했다.
  - ① **페이지 상한 소진 시 조용한 절단** — 4마켓 전부 루프가 `MAX_PAGES`로 빠져나가면 그때까지 모은 것을 **말없이 반환**했다. 내가 이 절에 적어둔 "실패 시 부분 결과 금지" 설계를 정작 상한 경로에서 어기고 있었던 것. 잘린 카탈로그는 잔여 전량을 `STALE_LOCAL`("마켓에 없는 상품")로 만들어 **D-210이 막으려는 바로 그 오답을 대규모로 생산**한다. → 자연 종료 도달 여부를 `reachedLastPage`로 추적해 상한 소진 시 **예외**를 던지도록 수정(4마켓 각각 테스트 보유).
  - ② **문자열 앞뒤 공백 미제거** — `sellerCode`·`identifiers`·`status`를 `asText()` 그대로 담아 마켓이 `"  220227IHB052  "`처럼 주면 SB코드 조인이 통째로 깨지고, 식별자는 전건 `IDENTIFIER_MISMATCH` 오탐이 된다. 쿠팡은 더 나빴다 — `productId`를 `asLong()`으로 읽어 값이 `" 70535073 "`처럼 문자열이면 **0으로 떨어져 식별자가 조용히 누락**됐다. → 전 마켓에 `text()` 헬퍼(경로 조회 + trim) 도입, 쿠팡은 `putIfPresent`로 trim 후 숫자 판단. 11번가는 `extractXmlValue`가 이미 trim하고 있어 무변경.
  - 교훈: 동료가 계약 재확인을 요청하면 "지켰다"고 답하기 전에 **코드를 다시 읽을 것**. 이번엔 그래서 라이브 전에 잡혔다.
- Green/게이트(2026-08-25): `:core:test` **801** · `:infrastructure:test` **262**(기준선 225 → +37, 전부 이번 신규) · `:api:test` **191** · `:worker:test` **72**, 실패·에러 **0**, `spotlessCheck` 4모듈 통과(`spotlessApply` 미실행 — 위반 1건은 수동 교정). **이 게이트는 격리 빌드 디렉터리에서 측정했다**(`--init-script`로 `buildDirectory` 이설) — 공유 워킹트리에서 다른 fixer와 gradle이 동시에 돌면 `Could not write XML test results`·`NoClassDefFoundError`로 무더기 거짓 실패가 난다([[D-210-b]] 관측 ②와 같은 현상이며, 재현·회피법이 이것).
- 후속 후보(이번 사이클 대상 아님 — 고치지 않고 기록만): `ElevenstMarketRestClient.sendRequest`가 HTTP 4xx/5xx와 IOException을 전부 정상 문자열로 반환하는 문제는 **여전히 미수정**이다. 이번엔 `fetchCatalog`가 `resultCode`를 직접 검사해 회피했을 뿐이므로, 11번가의 **다른 모든 경로에는 거짓 성공 위험이 그대로 남아 있다**([[D-206]] 후속 후보 ①과 동일 항목).
- 요지: `_workspace/fixes/D-210_clients_fix.md`
- 상태: 수정완료(검증대기)

#### D-210-b: 대조 서비스·API (담당: fixer-recon-service)

- 수정(2026-08-25): `MarketCatalogReconciliationService`(core application) 신설 — 마켓별로 카탈로그 1회 조회 후 맵 대조. 조인은 **SB코드 1순위 / 마켓별 식별자 2순위 하이브리드**라 쿠팡처럼 목록에 SB코드가 없는 마켓도 `sellerProductId`로 자동 대체 조인된다. 버킷은 계약 4종(`MATCHED`·`IDENTIFIER_MISMATCH`·`MISSING_LOCAL`·`STALE_LOCAL`)에 2종 추가 — `UNJOINABLE_LOCAL`(SB코드·식별자 둘 다 없어 조인 불가. STALE로 넣으면 "마켓에 없다"는 근거 없는 단정이 됨), `DUPLICATE_MARKET`(한 로컬 행에 마켓 리스팅 2개 이상). 마켓 원문 `status`는 해석 없이 분포 집계 → **D-207 스토어 `SUSPENSION`·D-209 쿠팡 판매중지 전수가 이 필드로 닫힌다.**
- **쿠팡 2단 전략**: 1단(기본)은 목록의 `sellerProductId`↔로컬 대조로 STALE_LOCAL과 상태 분포를 확정(상품당 추가 호출 0). 2단(`deep=true`)은 **1단에서 남은 STALE_LOCAL 후보에만** `fetchBySellerCode`(쿠팡 `/external-vendor-sku-codes/{SB코드}`) 단건을 걸어 부재를 확정하고, 실재하면 재분류해 카탈로그 페이지 누락 오탐도 걸러낸다. `deepLimit`(기본 200·상한 1000)로 상한. **3,195콜 전량 상세조회는 어떤 모드에서도 하지 않는다.**
- API: `GET /api/v1/products/market-sync/report?markets=&limit=&deep=&deepLimit=&throttleMs=` — 읽기 전용(활동로그도 안 남긴다). 마켓 미지정 시 전 마켓. 마켓 하나가 죽어도 나머지는 계속(`FAILED`+사유 / `UNSUPPORTED`), 그 경우에도 로컬 집계는 보고한다. **동기 결정** — 기본 모드는 수십 콜, deep도 상한 때문에 최악 ~40초. 비동기(`batchId`)는 `JobType` enum과 `ProcessStatus` 쓰기가 필요해 "읽기 전용" 제약과 충돌하므로 채택하지 않고 `elapsedMs`·`warnings`(절단 고지)로 대체.
- 성능: 마켓당 카탈로그 1회 + DB 질의 1회. `MarketRegistrationRepository.findSyncRowsByMarketType` projection으로 3컬럼만 읽어 `market_detailed_info`·`detail_html` blob 적재를 피한다. `LEFT JOIN`이라 상품이 사라진 고아 등록행도 `sbCode=null`로 함께 잡힌다.
- 범위 이탈 1건(리더 확인 요망): 위 성능 근거로 `core/domain/market/repository/`에 읽기 전용 메서드 1개 추가 + projection 인터페이스 1개 신설. 이 저장소는 repository 17/17이 전부 domain에 있어 application에 두면 관례·layering이 깨진다. 불가침 지정 `core/domain/market/client/**`·`infrastructure/**`는 무편집.
- 검증(2026-08-25, fixer 자체): Red 실측 — 서비스 골격(빈 리포트)에서 **18/18 실패**(계약 8케이스 전부 포함), 컨트롤러 골격(파라미터 무시)에서 7 중 4 실패(나머지 3은 골격도 만족 — 가짜 Red 아님). Green — `:core:test` 801/실패 0(기준선 780 → +21), `:api:test` 191/실패 0(기준선 184 → +7), 4모듈 컴파일·`:core:`/`:api:` spotlessCheck 통과, 주석 0줄·FQN 0. JPQL 엔티티 조인은 @DataJpaTest 실 SQL로 실증.
- 관측(비차단): ① `:infrastructure:spotlessCheck`가 `Cafe24MarketClient.java` 포맷 위반으로 실패 — 병렬 fixer 작업 파일이라 미편집·메시지 전달(이후 그쪽에서 수동 교정 완료). ② 여러 에이전트가 같은 워킹트리에서 gradle 동시 실행 시 `build/classes`·`test-results` 레이스로 **전 테스트가 `NoClassDefFoundError`로 무더기 실패**하거나 결과 XML이 0바이트로 남을 수 있다(재실행하면 그린) — **대량 실패를 결함으로 판정하기 전에 1회 재실행으로 판별할 것**. 격리 측정이 필요하면 `--init-script`로 buildDirectory를 스크래치패드에 이설하는 방법이 있다(fixer-catalog-clients 제보). ③ 프론트는 이번 범위 밖.
- **계약 오탐 조건 3건 — 제기 후 실제 위반 2건 확인·해소(2026-08-25)**: ⓐ 문자열 정규화 위반 실재(4마켓 trim 누락, **쿠팡은 `productId`를 `asLong()`으로 읽어 공백 섞이면 `0`으로 떨어져 식별자가 조용히 누락**) ⓑ 전량 보장 위반 실재(4마켓 전부 `MAX_PAGES` 상한 소진 시 **말없이 부분 반환** — 잔여가 전부 STALE_LOCAL로 잡히는 경로가 실재했다) ⓒ 인증 거부는 예외 처리 확인. ⓐⓑ는 fixer-catalog-clients가 TDD로 수정(`text()` 헬퍼 + `reachedLastPage` 상한 예외). **부분 카탈로그 리턴 경로는 이제 없다.** — 서비스 계층이 클라이언트에 요구사항을 명시 전달한 것이 실제 결함 2건을 끌어낸 사례.
- 카페24 `status`만 합성 문자열이다(`display=T,selling=F`) — 노출/판매가 별도 플래그 2개라 손실 없이 담으려는 의도. **카페24 판매중지 = `selling=F` 포함 키의 합**으로 읽어야 한다(다른 마켓은 `SALE`·`SUSPENSION`·`APPROVED`·`DELETED` 단일 문자열).
- **신규 결함 후보(고치지 않고 기록만 — 리더 번호 배정 요망): 11번가에만 우리 SB코드가 심겨 있지 않다.** 마켓별 sellerCode 출처를 우리 등록 경로와 교차 확인한 결과 — 카페24 `custom_product_code`←`Cafe24MarketClient:65`, 스토어 `sellerManagementCode`←`SmartstoreProductPayloadBuilder:124`, 쿠팡 `externalVendorSku`←`CoupangProductPayload:145`는 전부 `product.getSbCode()`로 일치하는데, **11번가 등록 XML에는 `sellerPrdCd` 태그 자체가 없다**(`getSbCode()`가 로그 `:44`와 에러 메시지 `:309`에만 등장). 오탐은 없다(sellerCode=null이면 `prdNo` 식별자 조인으로 폴백하고 sellerCode 차이를 기록하지 않는다). 다만 **11번가만 SB코드 축 대조가 구조적으로 불가능**해져 `marketWithSellerCode=0`·`matchedBySbCode=0`이 정상 출력이 되고, 마켓에만 있는 11번가 상품의 SB코드를 영영 알 수 없다(prdNo만 남음) — 사용자 요구인 "전 마켓 100% 싱크"가 11번가만 반쪽이 된다. 교정 방향: 등록 XML에 `sellerPrdCd` 추가 + 기존 2,286건 상품수정 API 백필. 연관 [[D-208]](11번가 인증 거부는 별개 엔드포인트 문제).
- 요지: `_workspace/fixes/D-210_recon_fix.md`
- 상태: 수정완료(검증대기) — 라이브 실행으로 실제 버킷 건수를 뽑는 것이 이 기능의 진짜 검증

#### D-210-c: 검증 반려 4건 수정 (담당: tdd-fixer, 2026-08-25)

판정서 `_workspace/verify/D-210_verdict.md`가 D-210-a·b를 **부분 판정·배포 보류**로 반려했다(A.1·A.4·B.7 FAIL). 반려 4건을 TDD로 해소했다. 전 항목이 겨냥한 것은 하나 — **STALE_LOCAL 오탐 차단**. 다음 사이클이 이 버킷을 근거로 식별자를 교정하므로 오탐 1건은 곧 멀쩡한 식별자 삭제 1건이다.

- **① 11번가 봉투 검사를 거부 목록 → 허용 목록으로 뒤집음.** `verifyCatalogEnvelope`가 `resultCode`가 **아예 없는 본문**(게이트웨이 HTML 오류 페이지 등)을 정상 통과시켰다 → 레코드 0건 → `records.size() < CATALOG_LIMIT`로 자연 종료 → 빈 카탈로그가 `COMPLETED` → 11번가 로컬 **2,286행 전량 STALE_LOCAL**. `CATALOG_SUCCESS_CODES = Set.of("200","0")` 화이트리스트로 전환해 **부재도 실패**로 처리한다(같은 파일 `syncImagesAndHtml`의 PUT 200/210 화이트리스트와 같은 패턴). 이 저장소가 반복한 계열 사고다 — [[D-181]] 쿠팡 봉투, [[D-212]] 11번가 삭제 블랙리스트 구멍.
- **② 서비스 빈 카탈로그 2차 방어(4마켓 공통).** `marketTotal == 0 && localTotal > 0`이면 분류하지 않고 `FAILED`로 종결한다(사유를 `failureReason`·`warnings` 양쪽에). ①이 막는 구멍뿐 아니라 **아직 발견되지 않은 동류 구멍까지 마켓 무관하게** 막는다. 로컬 0 + 마켓 0은 정상이므로 `COMPLETED` — 과발동하지 않음을 별도 테스트로 고정했다.
- **③ deep 모드의 미지원·실패·부재 3상태 분리.** `fetchSingle`이 모든 예외를 `Optional.empty()`로 삼켜 실패와 부재가 같은 버킷·같은 문구로 붕괴했고, `fetchBySellerCode` 구현체가 **쿠팡뿐**이라 나머지 3마켓에 `deep=true`를 걸면 **마켓 호출 0회인데** 전건에 "단건 조회에도 없습니다"가 붙고 `deepLookups`가 헛증가하고 후보당 `throttleMs`씩 잠들고 경고가 한 줄도 안 남았다. → `MarketClient.supportsSingleLookup()`(default false, 쿠팡만 true) 명시 신호를 신설해 미지원이면 **루프를 아예 돌지 않고** 경고 1회. `SingleLookup(entry, failed)` 3상태로 note를 갈랐다 — 미지원/deep=false는 `마켓 카탈로그에 없습니다`, 실패는 `… — 단건 확인이 실패해 미확정입니다`(+경고에 사유), **성공·부재일 때만** `마켓 카탈로그에도 단건 조회에도 없습니다`. 반환값만으로는 미지원과 미발견을 구분할 수 없다는 것이 명시 신호를 택한 이유다.
- **④ 11번가 카탈로그 비활성화(코드는 보존).** `fetchCatalog`가 컬렉션 URL에 **POST**했다 — 같은 클래스가 이 리소스의 읽기에는 GET을 쓰고(`syncImagesAndHtml`), 요청 XML 루트명(`SearchProduct`)·`start`/`end` base도 문서 근거 없는 가정이며, D-208로 실호출 확인이 불가능하다. 즉 **읽기 전용 리포트가 검증되지 않은 쓰기 후보를 라이브 판매자 API에 발사**한다. → `CATALOG_ENABLED = false` 스위치로 `fetchCatalog`가 `null`(UNSUPPORTED)을 반환하고 `restClient`를 **전혀 건드리지 않는다**(`verifyNoInteractions`로 고정). 순회 구현 전체는 `scanCatalog(long)`으로 **보존**했고 기존 테스트 8건이 그대로 검증한다(삭제 0). 비활성 사유는 코드가 아니라 리포트로 — `MarketClient.catalogUnsupportedReason()`(default null, 11번가만 오버라이드)을 서비스가 `failureReason`·`warnings`에 싣는다. 11번가는 D-208로 어차피 조회가 거부되므로 **비활성화로 잃는 것이 없다.**
- **재활성화 조건(`CATALOG_ENABLED = true` 전에 전부 확정):** ⓐ D-208 해소 ⓑ 전체 상품 조회의 정식 엔드포인트·HTTP 동사 문서 확인(컬렉션 POST가 조회가 아니면 경로 교체 — **미확인 상태에서 POST 재개 금지**) ⓒ 요청 본문 루트 엘리먼트명·`limit`/`start`/`end` base ⓓ **성공 봉투의 `resultCode` 실측값을 `CATALOG_SUCCESS_CODES`에 추가** — ①로 인해 성공 코드가 화이트리스트에 없으면 전 페이지가 예외로 떨어진다(조용한 오답보다 요란한 실패가 낫다는 선택이지만 재활성화 필수 항목) ⓔ 응답 레코드 엘리먼트명 ⓕ 소량 1회 실호출 후 **마켓에 변경이 남지 않았음**을 관리자 화면으로 확인. 절차 전문: `_workspace/fixes/D-210_reject_fix.md`.
- **기존 테스트 조정 4건(약화 아님)**: ②로 인해 "빈 카탈로그 + 로컬행"으로 STALE/UNJOINABLE을 세팅하던 3건(`staleLocal_whenLocalOnly`·`unjoinableLocal_whenNoSbCodeAndNoIdentifier`·`fetchesCatalogOnlyOnce`)에 **조인되지 않는 마켓 엔트리 1건**을 넣어 카탈로그를 비우지 않도록 바꿨다(단언 의도 불변). `deepModeConfirmsStaleLocalOnly`에는 `supportsSingleLookup()=true` 스텁을 추가 — Mockito 목은 default 메서드도 목킹해 false를 돌려주므로 명시가 필요하다.
- Red 실측: 계약 표면만 먼저 추가해 컴파일을 통과시킨 뒤 **행위 미구현 상태에서** 측정(컴파일 에러가 아니라 단언 실패로 Red를 확인). core 3 실패 + infrastructure 4 실패 = **신규 9건 중 7건 Red**. 나머지 2건(`completed_whenBothSidesEmpty`, `deepLookupMissRecordsConfirmedAbsence`)은 기존 행위가 이미 만족하는 **회귀 고정용 가드**이며 가짜 Red로 세지 않았다.
- Green/게이트(2026-08-25, 격리 빌드): `:core:test` **806**(801 → +5) · `:infrastructure:test` **266**(262 → +4) · `:api:test` **191** · `:worker:test` **72**, 실패·에러 **0**, `spotlessCheck` 4모듈 통과(`spotlessApply` 미실행 — 위반 2건 수동 교정). 증가분 9 = 신규 테스트 9와 정확히 일치.
- 미수정(의도적): 판정서 **요구 5**(쿠팡 `UNJOINABLE_LOCAL` 판정이 `sbCode != null`에 의존 — 쿠팡 카탈로그는 `sellerCode`를 주지 않으므로 sbCode는 조인 키가 아니다)는 비차단·운영 노출 **0행**이고 리더 지시 4건에 없어 손대지 않았다. 스토어 `asBoolean(true)`·카페24 offset→cursor 정렬 가정·`ElevenstMarketRestClient.sendRequest`의 4xx/5xx 정상 반환도 그대로다.
- 요지: `_workspace/fixes/D-210_reject_fix.md`
- 상태: 수정완료(검증대기) — 반려 4건 해소. 11번가는 리포트에서 `UNSUPPORTED`로 정직하게 표시되며, 라이브 실행 검증은 여전히 남아 있다.

### D-218: 마켓 주문 상품매칭이 식별자 JSON 부분 문자열로 조회해 엉뚱한 상품 배송 (2026-08-27, 실제 오배송 발생)

- 심각도: **P0(실제 고객 오배송 발생)** | 위치: `MarketRegistrationRepository:23` + 3개 마켓 sync 서비스
- 실증(운영): G마켓 주문 `4477134670`(2026-08-07). ESM 기준 실판매 상품은 `230806IHB130`(카페24 `product_no=20082`)인데 시스템은 `200828TE001`(사클라 칠리 페스토)로 매칭해 그대로 발송(현재 `EXCHANGED`).
- 원인: `findByMarketTypeAndIdentifiersContaining`이 `market_identifiers` **JSON 덩어리 전체를 `LIKE '%값%'`으로 부분 검색**하고 호출부가 `ORDER BY` 없이 `regs.get(0)`을 집는다. 카페24 `product_no`는 4~5자리 숫자라 날짜로 시작하는 SB코드(`200828TE001`) 안에 파묻힌다.
- **운영 실측 blast radius**(2026-08-27, 읽기 전용 조회): `product_no` 3,185건 중 **696건이 부분 문자열 충돌**(최악 후보 104건), 그중 **413건은 id 순 첫 후보가 오답**. 즉 카페24 상품의 **13%가 오배송 후보**였다.
- **결정적 관측**: 카페24 3,186행 중 **2,428행이 공백 포함 JSON**(`{"product_no": "20082", ...}`), 758행이 압축 JSON. → `LIKE '%"product_no":"20082"%'` 같은 **SQL 문자열 패턴 해법은 76%를 조용히 놓친다.** 정확 일치는 SQL이 아니라 JSON 파싱으로 판정해야 한다.
- 수정(2026-08-27): 정확 일치 판정을 **SQL이 아니라 Java JSON 파싱으로** 옮겼다. `MarketRegistrationLookup`(core/application/market) 신설 — 기존 `LIKE '%값%'`은 **후보 전치 필터**로만 남기고(+`ORDER BY r.id`), 후보 각각의 `identifier(key)`를 파싱해 **지정한 키의 값이 정확히 같은 것만** 남긴다. 구 메서드 `findByMarketTypeAndIdentifiersContaining`은 참조 0이 되어 삭제, `findIdentifierCandidates`로 대체.
- **왜 SQL 패턴이 아니라 Java 파싱인가**: 위 관측대로 카페24 76%가 공백 포함 JSON이라 `LIKE '%"product_no":"20082"%'`는 절반 이상을 놓친다. Java 파싱은 압축·공백·숫자값 표기를 모두 동일하게 읽고, **SQL에는 방언 의존 구문이 하나도 남지 않아** Postgres/H2 의미 차이가 원천적으로 생기지 않는다([[D-206]] 계열 사고 예방). 실증 — Postgres `LIKE '%20082%'` 7행 = H2 동일 시드 `findIdentifierCandidates` 7건, 그중 정확 일치 1건.
- **다건이면 매칭하지 않는다**: 정확 일치 2건 이상이면 첫 건을 집지 않고 `Optional.empty()` + WARN(`상품 매칭 중단 — {키}={값} 인 등록행이 N건이라 … 잘못 배송하지 않도록 매칭하지 않는다: sbProductId 후보=[…]`). 0건은 호출부의 기존/신설 WARN(`productId를 찾을 수 없음`)으로 문구가 완전히 갈린다.
- **마켓별 조회 키 확정(코드 실독)**: 카페24 `product_no`·`product_code`(`Cafe24LineItemMapper:104-105`가 저장 키와 동일명으로 담는다) / 쿠팡 `marketProductCode`→**`vendorItemId`**·`sellerProductId`→`sellerProductId`(`CoupangOrderAdapter:603-604`) / 11번가 `sellerProductId`→**`prdNo`**(`ElevenstOrderAdapter:673`). 11번가 `marketProductCode`는 SB코드라 `findBySbCode` 경로 — 무변경.
- Red 실측: 신규 `MarketRegistrationLookupTest` 10건을 **구 의미 골격**(부분 문자열+첫 건)으로 측정 → `10 tests completed, 5 failed`. 대표 실패가 곧 이번 사고다 — **`expected: "230806IHB130" but was: "200828TE001"`**. 통과 5건은 기존 행위가 이미 만족하는 회귀 가드(가짜 Red로 세지 않음). 주문 경로 전체를 도는 `Cafe24OrderSyncServiceTest` 신규 2건도 원본 의미 복원 후 `18 tests completed, 2 failed` 확인 후 Green 복원.
- **운영 전수 시뮬레이션(읽기 전용 덤프 9,920행)**: 새 알고리즘으로 카페24 `product_no` 3,185 / `product_code` 3,184 / 쿠팡 `vendorItemId` 1,261 / `sellerProductId` 1,262 / 11번가 `prdNo` 2,286 **전부 자기 자신으로 정확 해석, 모호 0 · 미해석 0**. 즉 **회귀 0건이면서 413건의 오배송 경로만 닫힌다.**
- 게이트(격리 빌드): `:core:test` **885**(기준선 873 → +12 = 신규 12) · `:infrastructure:test` **313** · `:api:test` **202** · `:worker:test` **72**, 실패·에러 **0**. 4모듈 `compileJava`·`compileTestJava`·`spotlessCheck` 통과(`spotlessApply` 미실행 — 미사용 import 3건 수동 교정). 주석 0줄·FQN 0·DB/마켓 쓰기 0.
- 후속 후보(고치지 않고 기록만): ① 카페24 라인아이템에는 `custom_product_code`(우리 SB코드)도 온다(`Cafe24LineItemMapper:106`) — `product_no`보다 안정적인 조인 키인데 매칭 경로가 안 쓴다. ② **이미 잘못 매칭돼 저장된 과거 주문 라인아이템의 교정 배치는 이번 범위 밖**(운영 쓰기 금지 상태라 미착수). 별건 필요.
- 요지: `_workspace/fixes/D-218_match_fix.md`
- 상태: 수정완료(검증대기)

### D-220: 마켓 주문동기화가 처리 0건도 "성공"으로만 기록해 유입 단절이 8일간 무증상 (2026-08-28)

- 심각도: **표준(운영 관측성)** | 위치: `Cafe24OrderSyncService:84-89` 외 4개 마켓 동기화 + `ActionLogSyncListener` + `MarketSyncStatus`
- 실증(운영, 2026-08-28 브라우저·DB 실측): G마켓/옥션 주문이 **08-20 이후 8일간 0건 유입**인데 `sb_action_log`에는 30분 간격 `GMARKET_SYNC / 동기화 성공`만 쌓였다. ESM+ 주문통합검색에는 같은 창에 실주문 10건이 있었고 그중 **`4483756643`(G마켓 08-27 16:46)·`2569176234`(옥션 08-24 18:15) 2건이 DB에 없었다**(count=0 확인). 단절 지점은 우리 코드가 아니라 **Cafe24 마켓플러스**였다(마켓플러스 주문수집이력 810회 스캔 → 실수집 마지막 `2026-08-20 15:46:13`, 이후 전 회차 "0건 성공/0건 실패", 수집실패 사유 0건).
- 원인: `markCompleted`·`SyncCompletedEvent`·`ActionLogSyncListener` 어디에도 **건수가 실리지 않는다.** 성공/실패 두 값만 남으므로 "정상인데 안 팔림"과 "유입이 끊김"이 로그상 완전히 같다. 5개 마켓 동기화 전부 동일 패턴. **이 결함 때문에 실제 사고를 8일 늦게 발견했다.**
- 수정(2026-08-28):
  - `MarketSyncStatus` + `processed_count`·`new_count`·`last_new_at`. `markCompleted(at, processed, newCount)`는 **신규 0건 회차에서 `lastNewAt`을 갱신하지 않는다** — 공백 측정의 기준선이라 덮으면 경고가 영원히 안 뜬다. 건수 없는 기존 1-arg `markCompleted`는 기존 건수를 **지우지 않는다**(쿠팡 정산 경로가 이 오버로드를 쓴다).
  - 처리/신규 분리: `SyncCounts(processed, created)` 신설. `MarketOrderUpsertDispatcher.dispatch`가 반환(쿠팡·11번가 공용), 스토어는 자체 루프에서 누적, 카페24는 `persistOrder`의 boolean을 `PersistOutcome{CREATED,UPDATED,SKIPPED}` 3상태로 갈라 누적. **갱신 전용 모드에서 건너뛴 주문은 처리 건수에도 신규 건수에도 세지 않는다**(false 성공 방지).
  - `SyncCompletedEvent` + `processedCount`·`newCount`, 미측정은 `UNMEASURED=-1`로 명시(기존 2-arg 생성자 보존 → 호출부 무변경).
  - `ActionLogSyncListener` 성공 메시지가 `동기화 성공 — 처리 N건, 신규 M건`으로 바뀐다. **신규 0건일 때만** `SyncStatusService.lastNewAt`을 조회해 임계 초과 시 `{MARKET}_SYNC_STALE` + `ActionStatus.WARNING` 레코드와 `log.warn`을 남긴다.
  - `SyncFreshnessPolicy` — 마켓별 임계: G마켓/옥션 **10일**(저volume, 역대 최대 공백 8일보다 넉넉히), 쿠팡·스토어 2일, 11번가 7일, 기본 7일. **`lastNewAt`이 null이면 경고하지 않는다** — 신규 유입 이력이 없으면 측정 근거가 없다.
- **왜 임계를 마켓별로 갈랐나**: G마켓/옥션은 전체 16건뿐인 저volume 채널이라 단일 임계(예 2일)로는 정상 무판매에도 상시 경고가 뜬다. 경고가 상시면 아무도 안 본다 — 이번 사고의 재발 방지가 목적이므로 **오경보를 내지 않는 선에서 가장 짧은 임계**를 마켓별로 잡았다.
- 알려진 한계(기록만): G마켓과 옥션은 카페24 한 경로로 들어와 `SyncMarketKeys.GMARKET` 한 키를 공유하므로 **옥션만 끊긴 경우는 이 경고로 잡히지 않는다.** 이번 사고는 두 마켓이 함께 끊겨 무관했다. 마켓별 분리가 필요하면 별건.
- Red 실측: 구현을 넣은 뒤 **행위만 되돌려**(성공 메시지 상수화 + stale 조기 return + created 카운트를 false로) 측정 → `11 tests completed, 3 failed` (`처리 건수와 신규 건수가 남는다`·`임계 넘긴 경고`·`dispatch 건수 분리`). 컴파일 에러가 아니라 단언 실패로 확인했다.
- 스키마(수동 DDL 필요 — Flyway 제거 체제):
  ```sql
  ALTER TABLE sb_market_sync_status ADD COLUMN IF NOT EXISTS processed_count integer;
  ALTER TABLE sb_market_sync_status ADD COLUMN IF NOT EXISTS new_count integer;
  ALTER TABLE sb_market_sync_status ADD COLUMN IF NOT EXISTS last_new_at timestamp;
  ```
  `sb_action_log.action_status`에 `WARNING` 값이 새로 들어간다(varchar라 DDL 불필요).
- 상태: 수정완료(검증대기) — 배포 전 위 DDL 선행 필수.

### D-221: ESM 난수 관리코드 미매칭 — 카페24 주문의 productId 해석 실패 (2026-08-28, 미착수)

- 심각도: **경량** | 위치: `Cafe24OrderSyncService.cafe24ResolveProductId`
- 실증: 운영 로그 `[CAFE24] sb_market_registration에서 productId를 찾을 수 없음: product_no=-99999, product_code=2005125893`
- 원인: 조회 키로 `product_no`·`product_code`만 쓰고 **`market_custom_variant_code`(ESM+ 판매자 관리코드)를 쓰지 않는다.** ESM 난수 관리코드로 등록된 건은 영영 해석되지 않는다.
- 근거 문서: `docs/normalize/esm-ghost-listings-20260812.md`의 미완 조치.
- 상태: 발견

### D-222: 마켓 등록 배지가 "삭제된 상품"과 "등록 대기"를 구분하지 못한다 (2026-08-29, 바코드 확대 중 발견)

- 심각도: **표준(운영 오판)** | 위치: `sb_market_registration`(스키마) + `MarketBadgeState.of` + `frontend/src/pages/product/productGridShared.tsx:badgeVisual`
- 증상: 상품 44(200907WA006)는 쿠팡에서 **삭제된 상품**인데 상품 그리드의 쿠팡 배지가 켜져 보인다. 사용자가 "없는 상품인지 파악이 안 되고 재등록도 어렵다"고 지적.
- 실측: `sb_market_registration` 쿠팡 행은 `is_synced=false`, `last_synced_at=2026-03-01`, `sellerProductId=11583638672` 보유. 바코드 PUT 시 쿠팡이 `code=ERROR, "해당 상품은 이미 삭제된 상품입니다"` 반환.
- 원인 2단:
  - **데이터는 맞다.** 백엔드 `MarketBadgeState.of(synced, url)`가 `synced ? SYNCED : PENDING`으로 정확히 내려보낸다.
  - **화면이 구분을 못 보여준다.** `badgeVisual`이 `PENDING`에 `'pending'`을 주지만 시각 스타일이 `registered`와 거의 같아 눈으로 갈리지 않는다. 등록 행이 아예 없는 G마켓만 `'missing'`으로 흐리게 표시돼 대비된다.
  - **근본은 정보 부족이다.** `is_synced=false` 하나에 **① 마켓에서 삭제됨 ② 전송 실패로 미반영 ③ 한 번도 성공 못 함**이 뭉뚱그려져 있어, 화면을 고쳐도 "삭제됨"과 "등록 중"을 가를 근거가 없다.
- 수정 방향(미착수): ① `sb_market_registration`에 **미동기 사유 컬럼** 신설(`DELETED_ON_MARKET` / `SYNC_FAILED` / `NEVER_SYNCED`, nullable). 마켓 응답에서 삭제 판정 시 기록한다 — 쿠팡은 `"이미 삭제된 상품입니다"`가 확정 신호. ② `MarketBadgeState`에 사유를 실어 프론트가 배지를 갈라 그리게 한다(삭제=빨강+재등록 유도, 실패=주황+사유 툴팁, 대기=회색). ③ 스키마 변경이므로 수동 DDL 선행 필요.
- 참고: 식별자(`sellerProductId` 등)는 **지우지 않는다.** 과거 주문 추적([[D-218]] 오배송 사고 계열)·[[D-210]] 정합성 대조·재등록 이력의 근거다. 지우면 문제가 해결되는 게 아니라 안 보이게 된다.
- 규모: 바코드 확대 340건 표본에서 "이미 삭제된 상품" **10건**. [[D-210]] 리포트의 "쿠팡 우리에만 101건"과 같은 뿌리로 보인다.
- **원인 정정(2026-08-29)**: 딥다이브 §6.1 의 "백엔드는 제대로 내려보낸다"는 **틀렸다.** `ProductController.buildMarketMap` 이 `boolean synced = reg.hasIdentifiers()` 로 판정한다 — `is_synced` 를 **보지 않는다.** 그래서 마켓에서 삭제됐어도 식별자만 남아 있으면 SYNCED 로 내려간다. 상품 44 증상의 직접 원인이다.
- 수정: `MarketBadgeState` 에 `reason` 필드 추가 + `of(hasIdentifiers, isSynced, reason, url)` 신설. `DELETED_ON_MARKET` → `DELETED`, `VALIDATION_FAILED`/`TRANSIENT_ERROR` → `FAILED`, **사유가 없으면 기존 판정 유지**. 프론트 `badgeVisual` 에 `deleted`(빨강·클릭 시 재등록)·`failed`(주황·사유 툴팁·링크 유지) 추가.
- 판단 근거: 사유 없는 미동기 행을 경고로 물들이지 않았다. 기존 테스트가 "식별자가 있으면 isSynced=false 여도 SYNCED — 레거시 임포트 행을 거짓 미완료로 경고하지 않는다"는 **의도적 결정**을 박아 두었고, 미분류 2,021건이 여기 해당한다. 그 결정을 존중하고 **사유가 확정된 행만** 갈라 그린다.
- **동반 발견 — 죽은 등록은 스스로 분류될 수 없다**: [[D-225]] 가 `is_synced=false` 를 전송에서 건너뛰므로, 죽은 등록은 마켓 응답을 받을 기회가 영영 없다. 즉 배지를 갈라 그려도 **가를 재료가 생기지 않는다**(작업 시점 `DELETED_ON_MARKET` 보유 행 0건). 상품 44 도 사유가 NULL 이라 여전히 SYNCED 로 그려졌다.
- 그래서 `MarketRegistrationProbeService` 를 함께 만들었다 — 미분류 미동기 행을 마켓 단건 조회로 확인해 **삭제가 확정된 것만** `DELETED_ON_MARKET` 으로 기록한다. 살아 있으면 아무것도 쓰지 않고, 삭제인지 알 수 없는 실패(타임아웃 등)는 `INCONCLUSIVE` 로 남긴다 — **한 방향으로만 쓴다.** 트리거: `POST /internal/registrations/probe?market=COUPANG&limit=&throttleMs=&dryRun=`. 이것은 [[D-227]] 의 축소판(카탈로그 전수 대조가 아니라 등록행 단건 프로브)이다.
- **라이브 프로브에서 분류기 버그 발견·수정(2026-08-29)**: 쿠팡 20건 프로브 결과가 `DELETED 1 / INCONCLUSIVE 14`로 갈렸는데 **15건의 응답 메시지가 완전히 동일**했다(`Product(...) data not found.`). 원인은 `DELETED_MARKERS` 의 `"404"` — 상품번호 `14813281**404**` 에 우연히 404 가 들어 있어 삭제로 판정됐다. **숫자 상태코드를 문자열로 매칭했는데 메시지가 상품번호를 품고 있었다.**
- 피해 가능성: 오판 시 [[D-223]] 가드가 통과시켜 **중복 리스팅**을 만든다 — 정확히 [[D-224]] 에서 "틀린 사유는 사유 없음보다 나쁘다"고 적은 그 경로다.
- 수정: 맨 숫자 마커(`"400"`·`"404"`·`"NOT_FOUND"`·`"BAD_REQUEST"`) 제거, 이유구까지 포함한 `"400 Bad Request"`·`"404 Not Found"` 로 교체. 쿠팡의 실제 삭제 신호인 `"data not found"` 를 DELETED 마커에 추가(쿠팡 API 직접 조회로 5/5 실재 부재 확인).
- 회귀 3건 추가: 상품번호에 404 포함 시 오판 금지, `data not found` → DELETED, 일반 400 → VALIDATION.
- 테스트: `MarketBadgeStateTest` 5건 + `MarketRegistrationProbeServiceTest` 4건 + 분류기 회귀 3건
- **프로브 실측 — 삭제는 쿠팡에만 몰려 있다(2026-08-29)**: 스토어·11번가·카페24 각 30건 표본이 **전부 ALIVE**(90/90). 즉 이 세 마켓의 미동기 행은 **삭제된 게 아니라 "마지막 작업만 실패한" 레거시**다. [[D-224]] 에서 미분류 행을 경고로 물들이지 않기로 한 판단이 실측으로 뒷받침됐다 — 다 칠했다면 1,734건이 거짓 경고였을 것이다.
- 운영 판단: 세 마켓 전량 프로브(1,734건)는 **API 예산 대비 수확이 없어 보류**한다. 삭제율이 유의미해지는 신호(배지 문의·주문 실패)가 나오면 그때 돌린다. 쿠팡만 전량 실행.
- **쿠팡 전량 프로브 완료(287건)**: `DELETED_ON_MARKET 50` / `ALIVE 237` / `INCONCLUSIVE 0`. 삭제 50건은 전부 `148132xxxxx`·`148132xxxxx` 연번대로 [[D-215]] 임시저장 코호트와 겹친다. **미동기 287건 중 실제로 사라진 건 17%뿐**이고 나머지 83%는 마켓에 멀쩡히 있다.
- **남은 판단거리 — 살아있음이 확인된 237건을 어떻게 할까**: 프로브는 한 방향으로만 써서 ALIVE 에 아무것도 기록하지 않는다. 그 결과 ① 다음 프로브가 237건을 매번 다시 조회한다(낭비) ② [[D-225]] 가 계속 건너뛰어 **바코드 등 전송 대상에서 영구 제외**된다 ③ [[D-223]] 가드가 통과시켜 직접 API 호출 시 중복 등록이 가능하다(프론트는 링크로 그리므로 화면 경로로는 안 걸린다). `is_synced=true` 로 승격하면 셋 다 해결되지만 **`is_synced` 의 의미를 "마지막 작업 성공"에서 "마켓에 존재"로 바꾸는 결정**이라 별도 판단이 필요하다. 사용자 확인 대기.
- **프로브의 사각**: `is_synced=true` 인데 실제로는 삭제된 행은 대상에 들어오지 않는다. 그 방향은 [[D-227]] 카탈로그 전수 대조가 필요하다.
- 상태: **수정완료(라이브 검증)** 2026-08-29

### D-231: is_synced 의 의미가 확정되지 않아 두 경로가 다른 규칙으로 돌았다 (2026-08-29, 아키텍처 검토 중 발견)

- 심각도: **P1(모든 후속 설계의 전제)** | 위치: `MarketRegistration` + `ProductMarketSyncService` + `ProductBarcodeSyncUseCase`
- 증상: 쓰기 실패 시 `is_synced` 를 뒤집을지가 경로마다 달랐다 — 가격·재고는 무조건 뒤집고, 바코드는 `DELETED_ON_MARKET` 일 때만 뒤집었다. [[D-224]] 자책 결함을 한쪽만 고친 결과다.
- 실측 근거: 프로브가 미동기 행의 실제 상태를 측정하니 **쿠팡 287건 중 237건(83%)·나머지 3마켓 표본 90/90(100%)이 마켓에 살아 있었다.** 즉 `is_synced=false` 는 "마켓에 없음"이 아니라 "마지막 쓰기 실패"로 쓰이고 있었고, 목표 아키텍처 문서는 전자를 전제하고 있었다.
- 결정(**A안**, 사용자 확정 2026-08-29): **`is_synced` 는 "마켓에 존재"다.** 존재를 바꾸는 것은 존재 확인뿐이고, 쓰기 실패는 존재를 건드리지 않는다.
- 그 귀결로 **질문 두 개를 두 컬럼으로 분리**: `unsync_reason`(존재 — `NEVER_SYNCED`/`DELETED_ON_MARKET`) + **`last_sync_error` 신설**(쓰기 결과 — `TRANSIENT_ERROR`/`VALIDATION_FAILED`/`BLOCKED_BY_MARKET`). `is_synced=true` 이면서 `last_sync_error=BLOCKED_BY_MARKET` 인 상태("마켓엔 있는데 심사중이라 수정이 안 들어간다")가 실재하는데 이전 모델에는 그 칸이 없었다.
- 신설 `BLOCKED_BY_MARKET`: 쿠팡 "심사가 진행중입니다"가 그동안 `TRANSIENT_ERROR`(=재시도하면 됨)로 분류됐다. **재시도해도 심사가 끝나기 전엔 안 풀린다.**
- 엔티티 API 도 의미별로 갈랐다 — `markSynced`(쓰기 성공=존재 확인, 둘 다 지움) / `confirmPresentOnMarket`(존재만 확인, 쓰기오류 유지) / `markAbsentFromMarket`(부재, 사유 필수) / `recordSyncError`(쓰기 실패, 존재 불변).
- 스키마: `ALTER TABLE sb_market_registration ADD COLUMN IF NOT EXISTS last_sync_error VARCHAR(32);` 적용 완료. 기존 `unsync_reason` 값은 전부 존재-사유(DELETED 50·NEVER_SYNCED 3)라 **데이터 이전 불필요**.
- 테스트: `MarketFailureClassifierTest` 10건 + `MarketBadgeStateTest` 7건
- 문서: `product-lifecycle-target.md` 개정(§1.2·2.2·2.3·2.4·2.5·2.6·4.2·8·9). 초판이 틀렸던 네 곳을 본문에 표시했다.
- **라이브 승격 완료(2026-08-29)**: 미분류 미동기 1,971건에 `promoteAlive=true` 프로브 실행 → **1,970건 승격, 신규 삭제 1건 발견(카페24)**. 결과 **미분류 미동기 = 0건**. 목표 문서의 불변식("`is_synced=false` 면 반드시 사유가 있다")이 개정판에서 "신규 데이터에만 성립"으로 물러섰다가, 승격으로 **전체 데이터에서 전면 달성**됐다.
- 마켓별: 쿠팡 237 · 11번가 334 · 스토어 723 · 카페24 676 승격. 최종 상태 = 정상 9,866 / `DELETED_ON_MARKET` 51(쿠팡 50·카페24 1) / `NEVER_SYNCED` 3.
- **네이버 429 사건 — 안전한 오분류 방향이 실제로 작동했다**: 스토어 723건 중 92건이 `INCONCLUSIVE`(전부 `429 Too Many Requests`). 스로틀 350ms 가 짧았던 것이지 삭제가 아니다. 분류기가 판정 불가로 처리해 **아무것도 쓰지 않았고**, 1,200ms 로 재실행하니 92건 전부 승격됐다. 429 를 삭제로 오판했다면 살아있는 네이버 상품 92개가 삭제로 표시되고 [[D-223]] 가드를 통과해 **중복 리스팅 92개**가 생길 수 있었다. §4.2 의 "분류 실패는 안전한 쪽으로 기운다"가 실증된 사례다.
- 라이브 배지 검증: 상품 `220130IHB006` 쿠팡 배지가 빨강(bg `rgb(254,226,226)` / border `rgb(220,38,38)`), 툴팁 "쿠팡 — 마켓에서 삭제된 상품입니다. 클릭하면 다시 등록합니다.". 같은 행의 나머지 5개 마켓은 각자 정상 색으로 링크 유지 — **한 줄에서 어디가 살아있고 어디가 죽었는지 읽힌다.**
- 잔여: `last_sync_error` 는 아직 전부 NULL 이다. 앞으로 쓰기가 실패할 때 채워지므로, 다음 동기화에서 `BLOCKED_BY_MARKET` 이 처음 기록되는 것을 확인하면 완전 검증된다.
- 상태: **수정완료(라이브 검증)** 2026-08-29

### D-232: 프로브가 "조회 성공"을 "존재"로 단정했다 — 쿠팡은 삭제된 상품도 200으로 돌려준다 (2026-08-29, D-227 교차대조 중 발견)

- 심각도: **표준(오판이 중복 등록으로 이어짐)** | 위치: `MarketRegistrationProbeService.probeOne` + `CoupangMarketClient`
- 증상: 프로브가 `extractMarketItem` 이 예외를 안 던지면 ALIVE 로 판정하고 `is_synced=true` 로 승격했다. 그런데 **쿠팡은 삭제된 상품도 200 으로 응답한다** — 본문의 `statusName` 이 `"상품삭제"` 일 뿐이다. 껍데기만 보고 내용을 안 읽었다.
- 발견 경로: [[D-227]] 카탈로그 대조가 쿠팡 `STALE_LOCAL` **101건**(카탈로그에도 `fetchBySellerCode` 에도 없음)을 보고했는데, 프로브는 같은 행들을 방금 ALIVE 로 승격한 상태였다. **두 방법이 충돌**해서 실측했더니 `200815TE116`(sellerProductId=11002709448)이 ID 조회는 성공하는데 `statusName="상품삭제"` 였다.
- 규모: `is_synced=true` 쿠팡 60건 표본에 **상품삭제 1건**(나머지 59는 심사중 — 오늘 바코드 PUT 결과). 대조 기준으로는 101건 중 기존 기록 50건을 뺀 **약 51건**이 잘못 승격됐다.
- 계열: [[D-181]]·[[D-208]]·[[D-212]] 의 **거짓 성공** 계열이다. "봉투가 아니라 내용으로 판정하라"를 문서에 적어두고 프로브에서 스스로 어겼다.
- 수정: `MarketPresence`(PRESENT/ABSENT/UNKNOWN) 신설 + `MarketClient.checkPresence` 로 존재 판정을 클라이언트에 위임. 쿠팡은 `statusName` 을 읽어 `MarketFailureClassifier.indicatesDeletedStatus`("삭제" 포함)로 판정한다. 프로브는 **PRESENT 일 때만 승격**하고 UNKNOWN 은 아무것도 쓰지 않는다.
- 교훈: **두 독립적 방법을 교차 대조하지 않았으면 못 잡았다.** 프로브(ID 조회)와 카탈로그 대조(목록+sellerCode)가 서로를 검증했다. 단일 판정 경로만 있으면 이런 오판은 조용히 남는다.
- 테스트: `MarketPresenceCheckTest` 4건 (Red 3건 확인 후 구현)
- 상태: **수정완료(라이브 검증)** 2026-08-29 — 대조 `persist=true` 실행으로 잘못 승격된 51건 교정 확인

### D-233: 바코드 재전송이 멱등하지 않다 — 이미 같은 값인데 PUT 해서 승인 상품을 심사중으로 되돌린다 (2026-08-29)

- 심각도: **표준(재실행이 해를 끼침)** | 위치: `CoupangMarketClient.syncBarcode` · `SmartstoreMarketClient.syncBarcode`
- 증상: `syncBarcode` 는 마켓의 현재 바코드를 보지 않고 무조건 PUT 했다. **쿠팡은 PUT 성공이 곧 심사중 전환**이므로([[coupang-republish-attributes]]), 이미 바코드가 들어간 상품에 재전송하면 **멀쩡한 승인 상품을 심사중으로 되돌린다.**
- 재집계를 막고 있던 문제: `market_identifiers.barcode` 는 [[D-228]] 이후에만 채워지므로, 그 이전에 성공한 것들도 전부 공백으로 보여 "미전송"으로 오인된다. 그 상태로 전량 재실행하면 오늘 성공한 866건이 전부 심사중으로 돌아간다.
- 실측: `is_synced=true` 쿠팡 60건 표본 중 **59건이 심사중** — 오늘 바코드 PUT 의 결과다.
- 수정: `syncBarcode` 가 `boolean` 을 반환한다(전송했나). 신선 GET 후 **마켓 값이 이미 같으면 PUT 없이 `false` 반환**. 유스케이스는 `SENT` 와 `ALREADY` 를 구분해 보고하고, 두 경우 모두 [[D-228]] 식별자 기록과 `markSynced()` 를 수행한다(GET 성공 = 존재 확인).
- 효과: 재실행이 **안전하고 싸진다**. 이미 반영된 것은 GET 한 번으로 끝나고 마켓 상태를 건드리지 않는다.
- 테스트: `CoupangBarcodeIdempotenceTest` 5건(멱등·차이시전송·공백시전송 + [[D-232]] 존재판정 2건), `ProductBarcodeSyncIdentifierTest` 에 `ALREADY` 1건 추가
- 상태: **수정완료(라이브 검증)** 2026-08-29 — 재집계에서 `ALREADY` 확인, PUT 0건

### D-237: 상품 삭제가 best-effort 라 마켓에 리스팅을 남긴 채 우리만 잊는다 (2026-08-30, 소프트 삭제 8a)

- 심각도: **표준(유령 리스팅 생성)** | 위치: `ProductManageUseCase.deleteProduct`
- 증상: 마켓 삭제가 실패해도 DB 삭제를 강행했다. 코드 주석에 `(best-effort로 DB 삭제는 진행)` 이라고 명시되어 있었다. **마켓엔 상품이 남고 우리는 잊는다** — [[D-223]] 이 게시 경로에서 막으려던 유령 리스팅이 삭제 경로로 생긴다.
- 조용한 스킵도 같은 병: 마켓 클라이언트가 없거나(G마켓·옥션) 삭제 API 가 없으면 `skipped` 로 보고하고 **그대로 DB 를 지웠다.** 사용자는 다 지워진 줄 안다.
- 실측으로 드러난 추가 결함: 마켓 상품코드가 `null` 인데도 `deleteFromMarket(null)` 을 호출하고 **성공으로 집계**했다. 무엇을 지울지 모르는데 지웠다고 센 것이다.
- 착수 시점 판단 근거: `PRODUCT_DELETE` 활동로그 **0건** — 이 기능은 운영에서 **한 번도 쓰인 적이 없다.** 목표 문서가 "위험 높음"으로 본 이유는 기존 동작 변경인데, **바꿀 기존 동작이 실제로는 없었다.** 지금이 가장 싼 시점이다.
- 수정(8a): 규칙을 뒤집었다.

  | 상황 | 이전 | 이후 |
  |---|---|---|
  | 마켓 삭제 실패 | DB 삭제 강행 | **폐기 보류** + `last_sync_error` 기록 |
  | 삭제 API 없음 | 스킵 → DB 삭제 | **수동 처리 대상**, 폐기 보류 |
  | 마켓 클라이언트 없음 | 스킵 → DB 삭제 | **수동 처리 대상**, 폐기 보류 |
  | 마켓 상품코드 없음 | `null` 로 호출 후 성공 집계 | **수동 처리 대상**, 폐기 보류 |
  | 전 마켓 성공 | DB 삭제 | DB 삭제 + 등록행 `DELETED_ON_MARKET` |
  | API 응답 | 항상 200 | 폐기 보류 시 **409** |

- 409 를 택한 이유: 프론트 일괄삭제가 **예외로 실패를 센다**(`productBulkApi.ts`). 409 면 기존 화면이 **수정 없이** 올바르게 동작한다.
- 원칙: **되돌리기 어려운 쪽을 나중에 한다.** 마켓 삭제는 우리가 되돌릴 수 없고(재등록은 새 리스팅), DB 삭제는 우리가 통제한다.
- **8b 완료(2026-08-30)**: `deleted_at` 소프트 삭제. 조회 경로를 전수 조사한 결과 **설계가 바뀌었다** — 아래 참조.
  - 조사 결과: 코드베이스에 **네이티브 SQL 이 하나도 없고**(`nativeQuery = true` 0건), `@Query` 6개가 전부 JPQL, 상품 조회가 전부 `ProductReader` → `ProductRepository` 를 거친다. **QueryDSL 은 대시보드 2곳에서 상품을 조인**한다.
  - **`@SQLRestriction`(엔티티 전역 필터)을 버렸다.** 한 줄로 끝나지만, 대시보드가 `leftJoin(p)`·`join(p)` 로 주문↔상품을 조인하므로 **폐기 상품이 주문 조회에서도 사라진다.** 소프트 삭제를 하는 이유가 바로 주문 추적 보존인데 **가장 쉬운 구현이 목적을 스스로 무너뜨린다.** 내부 조인 쪽(`countOutOfStock`)은 주문 행 자체가 집계에서 빠진다.
  - 대신 **상품 관리 경로에만 명시적으로** 걸었다: `ProductSpecifications.matching`(그리드) · `findDistinctCategories` · `findBarcodeBackfillTargetIds` 2종. **주문·대시보드 조인은 건드리지 않았다** — 폐기 상품도 과거 주문에서 이름이 나온다.
  - 쓰기 가드: 폐기 상품에 **게시는 거부**(`IllegalStateException`), **바코드 전송은 SKIPPED**.
  - `ProductDeleteTxService.deleteWithRegistrations` → `markDeleted()` + save. **등록행은 보존**([[D-222]] 원칙 ②). 하드 삭제는 `purge(...)` 로 분리해 남겼다.
  - 스키마: `ALTER TABLE sb_product ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;` 적용(활성 3,195 / 폐기 0).
  - 테스트: `ProductSoftDeleteTest` 5건 (Red 확인 후 구현)
- 미착수(8c): purge. **보존기간을 지금 정하지 않기로 했다** — 상품 3,186건·주문품목 272건이라 보존 비용이 사실상 0이고, "언제 지워도 안전한가"는 정산 대사 주기를 알아야 답할 수 있다. 근거 없는 숫자를 규칙으로 굳히지 않는다.
- 테스트: `ProductDeleteDisposalGuardTest` 4건(Red 3건 확인 후 구현) + 기존 특성화 2건을 새 결정으로 갱신
- 상태: **수정완료(8a)** 2026-08-30

### D-238: 필터 실행 뒤 Gradle up-to-date 때문에 회귀 통과를 오보했다 (2026-08-30, 프로세스 결함)

- 심각도: **표준(검증 신뢰성)** | 위치: 작업 절차
- 증상: `./gradlew :core:test --tests '*XxxTest'` 로 특정 테스트만 돌린 뒤 `./gradlew test` 를 실행하면 `:core:test` 가 **up-to-date 로 건너뛰어진다.** 그 상태로 "회귀 217건 통과" 라고 보고했고, `--rerun-tasks` 로 강제하니 **숨어 있던 실패 3건**이 드러났다.
- 놓친 실패: `ProductMarketSyncServiceTest`(D-231 A안이 바꾼 동작) · `MarketRegistrationProbeServiceTest` 2건(D-232 `checkPresence` 전환). 셋 다 **의도적으로 바꾼 동작을 붙들고 있던 옛 특성화 테스트**라 깨지는 게 정상이었다. 문제는 그걸 모른 채 통과로 보고한 것이다.
- 영향 커밋: `a68cba72`(D-231) · `ea2b4542`(D-232) · `453d3a08`(D-233) 의 "회귀 통과" 기술은 부정확하다. 실제로는 이 커밋들이 3건을 깨뜨린 상태로 배포됐다(테스트만 깨졌고 운영 동작은 정상).
- 재발 방지: **게이트는 `--rerun-tasks` 로 돌린다.** 필터 실행 뒤의 통과는 신뢰하지 않는다.
- 상태: **수정완료(절차)** 2026-08-30

### D-245: 소싱처별 가격 정책이 없어 배치가 상품 마진율을 일괄로 덮어쓴다 (2026-08-30, D-239 재크롤 준비 중 발견)

- 심각도: **표준(가격 오류 = 매출 직결)** | 위치: `BatchPriceStockService.crawlAndUpdatePriceStock` + `sb_price_policy`
- 증상: 배치가 `marginRate`·`couponRate` 를 **파라미터로 받아 전 상품에 일괄 적용하고, 상품의 `margin_rate` 를 덮어쓴다.** 상품마다 저장된 자기 마진율이 있는데 무시된다.

  ```java
  ProductUpdateCommand.builder().marginRate(marginRate)  // 넘긴 값으로 상품 마진율을 덮어쓴다
  ```

- **소싱처마다 조건이 다르다(사용자 확인 2026-08-30)**:

  | 소싱처 | 마진율 | 쿠폰율 | 비고 |
  |---|---|---|---|
  | IHB 아이허브 | 4~15% | **20%** | 쿠폰 할인가로 구매 |
  | FTN·OCD·VTB·COK·TES | **25%** | **0%** | **정가 구매 + 배송비 부담** |

  `sb_price_policy` 에는 **단 한 줄(margin 15 / coupon 20 / min 5000)** 뿐이고 그건 **아이허브 전용**이다. 나머지 소싱처 정책은 시스템 어디에도 없다.
- **쿠폰율이 특히 위험하다**: `MarginCalculator.applyCoupon` 이 **구매가를 쿠폰율만큼 깎는다.** 비-아이허브 상품에 기본값 20을 쓰면 **원가를 20% 싸게 잡아 판매가가 그만큼 낮게 산정된다** — 팔수록 손해다.
- 현재 위험: 배치 기본값이 `15/20/5000` 이라 **버튼 한 번으로 557건의 마진율이 25%→15%, 원가가 20% 축소**된다. IHB(4%)와 OCD(25%)를 한 배치에 섞으면 **한쪽이 반드시 망가진다.**
- 임시 대응: 재크롤 시 소싱처별로 나눠 파라미터를 손으로 지정한다(OCD·VTB·COK = `marginRate=25, couponRate=0`).
- 수정 방향(미착수): ① `sb_price_policy` 를 **소싱처별 다건**으로 확장(vendor 컬럼) ② 배치가 파라미터 대신 **소싱처 정책 + 상품 저장값**을 쓰고, 파라미터는 "명시적 덮어쓰기"일 때만 적용 ③ 화면 기본값도 소싱처에 따라 달라져야 한다.
- 상태: 발견

### D-244: 쿠팡 상품 삭제가 "없거나 삭제 불가"를 한 덩어리로 처리해 실제로 지울 수 있는 것도 못 지웠다 (2026-08-30, TES 폐기 실측 중 발견)

- 심각도: **표준(폐기 불가)** | 위치: `CoupangMarketClient.deleteFromMarket`
- 증상: 쿠팡 DELETE 가 이 응답으로 실패한다.

  ```
  400 {"code":"ERROR","message":"업체상품[14292103442]이 없거나 삭제가 불가능한 상태입니다.
       삭제는 '저장중', '임시저장' 상태에서만 가능합니다."}
  ```

  **"없거나" — 또 '또는'이다.** 네이버 `statusType`("입력하지 않았거나 허용되지 않은")에서 두 번 틀렸던 것과 같은 함정. 두 가지가 한 문장에 있다: ① 이미 삭제돼 없음 ② 있는데 상태 때문에 못 지움. **처리가 정반대인데 코드는 둘 다 실패로 봤다.**
- 실측(TES 상품 22건의 쿠팡 실제 상태): 승인완료 11 · 임시저장 6 · **상품삭제 4**(이미 없음) · 승인반려 1. **4건은 이미 지워졌는데 우리는 삭제 실패로 처리하고 있었다.**
- **삭제 API 는 있었다 — 순서가 필요했을 뿐이다.** 사용자 지적("쿠팡이 삭제가 안될리가 없어")대로 실측했다:

  ```
  1) 승인완료 상태
  2) 판매중지  OK "판매 중지 처리되었습니다."
  3) DELETE    OK "14292091136가 삭제되었습니다."
  ```

  승인된 상품은 바로 못 지우지만, **판매중지하면 삭제 가능한 상태로 내려간다.** 2289·2296 두 건으로 재현 확인.
- 수정: DELETE 실패가 "삭제가 불가능한 상태" 면 **'또는'을 가른다** — `checkPresence`([[D-232]])로 존재를 확인해 ABSENT 면 **이미 삭제됨으로 성공 처리**, PRESENT 면 **판매중지 후 DELETE 재시도**. 재시도도 실패하면 실패로 남긴다.
- **판매중지를 삭제로 인정하지 않는다(사용자 결정 B안)**: "판매중지는 단순히 재고가 없을 때에도 되는 상태" — 폐기의 증거가 못 된다. 판매중지는 **삭제를 가능하게 하는 수단**일 뿐이고, 실제 DELETE 성공만 삭제로 친다.
- **자책 정정**: 처음에 DELETE 검증을 `verifyEnvelopeLenient` → `Strict` 로 바꿨다가 기존 특성화 테스트 `D-181: DELETE 응답 본문이 없거나 봉투가 아니면 기존대로 통과` 가 깨졌다. **쿠팡 DELETE 는 성공 시 본문이 비어 온다** — strict 면 정상 삭제를 실패로 보고 판매중지 폴백까지 헛돈다. 되돌렸고, 봉투가 실제로 오는 판매중지 호출에만 strict 를 남겼다. **옛 결정을 뒤집을 땐 왜 그렇게 되어 있었는지부터 봐야 한다.**
- 테스트: `CoupangDeleteFlowTest` 3건(판매중지 후 재삭제 · 이미 삭제됨 성공 처리 · 재시도 실패는 실패)
- 상태: **수정완료(검증대기 — 배포 후 TES 폐기 배치)** 2026-08-30

### D-239: 크롤러 없는 소싱처가 iHerb 크롤러로 폴백돼 655건이 "품절"로 단정됐다 (2026-08-30, D-060 감사 중 발견)

- 심각도: **P0(매출 직결 — 팔 수 있는 상품이 전 마켓에서 품절 처리됨)** | 위치: `StockCrawlerRouter` + `IherbScraperClient.checkStockWithDetails`
- 증상: 소싱처별 재고 크롤러는 **IHB·FTN 둘뿐**인데, `StockCrawlerRouter` 가 미등록 소싱처를 `defaultCrawler`(= iHerb 크롤러)로 폴백한다. iHerb 크롤러는 URL 에서 상품 ID 를 못 뽑으면 **"모른다"가 아니라 `OUT_OF_STOCK` 을 반환**한다.

  ```java
  String productId = extractProductId(sourceUrl);
  if (productId == null) {
      log.error("아이허브 상품 ID 추출 실패. sourceUrl={}", sourceUrl);
      return new StockCheckResult(StockStatus.OUT_OF_STOCK, null, 0, null);  // ← 품절로 단정
  }
  ```

  예: COK 상품의 `sourcing_url` 은 `https://www.costco.co.uk/...` 다. iHerb 파서로는 절대 파싱되지 않는다.
- **실측 — 우연이 아니다**:

  | 소싱처 | 전체 | 품절 | 품절률 | 크롤러 |
  |---|---|---|---|---|
  | IHB | 2,146 | 437 | 20.4% | 있음 |
  | FTN | 393 | 146 | 37.2% | 있음 |
  | **OCD** | **407** | **407** | **100%** | **없음** |
  | **VTB** | **102** | **102** | **100%** | **없음** |
  | **TES** | **98** | **98** | **100%** | **없음** |
  | **COK** | **48** | **48** | **100%** | **없음** |

  크롤러 없는 4개 소싱처가 **정확히 100%**. 크롤러 있는 곳은 20~37%. 코드 경로가 이 분포를 정확히 설명한다.
- 피해: **655건이 실제 재고와 무관하게 전 마켓에서 품절**이다. 그리고 [[D-060]] 배선 때문에 이 품절 상태가 **마켓까지 전파**됐다. 팔 수 있는 상품이 안 팔리고 있다.
- 원칙 위반: 목표 문서 §0 원칙 ④ "**모르면 모른다고 기록한다**". 조회 실패를 "없음"으로 단정하지 않는다는 규칙을 재고 크롤이 정면으로 어겼다.
- **수정 ①②(2026-08-30, 코드)**: 더 나빠지지 않게 먼저 막았다.
  - `StockCrawlerRouter` **defaultCrawler 폴백 제거** — 크롤러 없는 소싱처는 `IllegalStateException`. 다른 소싱처 크롤러로 대체하지 않는다.
  - `IherbScraperClient` 의 **세 실패 경로를 각각 올바르게** 고쳤다:

    | 경로 | 이전 | 이후 |
    |---|---|---|
    | URL 에서 ID 추출 실패 | 품절로 단정 | **판정 불가 예외** |
    | HTTP 404 | 품절(플래그 없음) | 품절 + **`sourceGone=true`**(원본 소멸) |
    | 재시도 소진(403 차단·순단) | 품절로 단정 | **판정 불가 예외** |

  - 세 번째가 특히 중요하다 — **iHerb 가 403 으로 차단하면 IHB 2,146건도 품절로 덮였다.** IHB 품절률 20.4% 중 일부가 이것일 수 있다.
  - `BatchPriceStockService` 는 상품 단위로 예외를 잡아 `markFailed` 하므로, **판정 불가는 이제 재고를 건드리지 않고 실패로 남는다.**
  - `StockCrawlerRouter.supportedVendors()`·`supports()` 신설 — 스케줄 대상 선정의 근거.
- **미착수 ③ 655건 복구(핵심 잔여)**: 코드는 앞으로를 막았을 뿐 이미 잘못 품절된 655건은 그대로다. **일괄 IN_STOCK 전환은 위험**하다(진짜 품절이 섞여 있다). 크롤러가 없어 실제 재고 확인 수단도 없다 — 그게 애초 원인이다. 선택지: (a) OCD·VTB·TES·COK 크롤러 신규 구현(근본, [[scrapling-scraper-poc]] 방식 재사용 가능) (b) 일괄 IN_STOCK 후 주문 시 대응(빠르나 과판매) (c) 소싱처 성격별 판단(상시재고 여부는 사용자 지식 필요). **사용자 판단 대기.**
- **수정 ④(2026-08-30, 크롤러 신규 구현)**: 4곳 중 **3곳 완성**.

  | 소싱처 | 건수 | 방식 | 상태 |
  |---|---|---|---|
  | VTB Vitabiotics | 102 | Shopify JSON(`/products/{handle}.js`) | **완료** |
  | OCD Ocado | 407 | JSON-LD + Scrapling 브라우저(평문은 202 차단) | **완료** |
  | COK Costco UK | 48 | JSON-LD + 평문 HTTP(리다이렉트 추적) | **완료** |
  | TES Tesco | 98 | — | **불가 — 운영서버 IP 가 도메인 전체 차단(홈페이지도 403)** |

  - 세 사이트가 **모두 schema.org JSON-LD** 를 쓴다는 걸 발견해, 사이트별 셀렉터를 추측하는 대신 **표준 구조를 읽는 공통 스크래퍼**(`scrapers/jsonld.py`) 하나로 처리했다. 하위 클래스는 도메인과 렌더링 방식만 정한다.
  - 실측: COK £6.99 Marmite(무게 600g 파싱) · £16.89 Lavazza / OCD £4.50 Basmati · £9.50 Honey / VTB £9.85 · £22.95 · £10.35 — **전부 실제 재고 있음.** DB 에는 655건 전부 품절로 기록돼 있었으니 가짜 품절이 실물로 확인됐다.
  - 디버깅에서 Costco 가 `@type` 을 **소문자 `"product"`** 로 쓰는 것을 발견해 대소문자 무시로 교정.
  - Tesco 는 스크래퍼를 등록해 두되 결과가 항상 `blocked` 로 온다 — **재고를 건드리지 않고 실패로 남는다.** 프록시 없이는 불가.
  - **배송비 체계는 손댈 게 없었다** — 스크래퍼가 `price`(GBP)·`weightGrams` 를 내면 `pricing.py` 가 배대지 배송비(£10.5 + 0.5kg당 £2)·환율을 얹고, 배치가 `원가 + 배송비/묶음수량` 으로 계산한다. FTN 과 동일 경로.
- **동반 신설 — 폐기 후보 상태(사용자 지적)**: 링크 소멸·단종은 품절과 다르다(되돌아오지 않는다). 기존 `sourceGone` 은 품절로만 바꾸고 **아무 기록도 남기지 않았다**([[D-224]] 와 같은 병 — 상태만 있고 사유가 없다).
  - `SourceGoneReason` 신설: `LINK_DEAD`(404 — URL 변경일 수도) / `DISCONTINUED`(판매종료 표기). **사람이 할 일이 다르므로** 나눴다.
  - `sb_product.source_gone_at`(최초 감지 시각 — 언제부터 사라졌는지가 폐기 판단 근거, 재감지해도 보존) + `source_gone_reason` 신설. 원본이 다시 잡히면 **표시를 지운다**(URL 변경이었을 수 있다). 사유 없이 표시하려 하면 거부.
  - 스크래퍼가 단종을 품절과 갈라 별도 상태(`discontinued`)로 올린다 — 이전엔 `_OUT_OF_STOCK` 목록에 섞여 묻혔다.
  - `ProductListResponse.sourceGoneReason` 으로 화면에 노출.
  - 테스트: `SourceGoneTrackingTest` 4건
- **복구 실측(2026-08-30, 재크롤 배치 `83d9a6be` 외 3회)**: 크롤러 보유 3개 소싱처 555건을 전량 재크롤했다.

  | 소싱처 | 전체 | 재고 회복 | 폐기 후보(LINK_DEAD) | 실측 품절 | 미해결 |
  |--------|------|----------|---------------------|----------|--------|
  | OCD | 407 | **271** | 136 | 0 | 0 |
  | COK | 48 | 7 | 14 | 25 | 2 |
  | VTB | 100 | 55 | 34 | 11 | 0 |
  | 합계 | 555 | **333** | 184 | 36 | 2 |

  - **재크롤 전에는 555건 전부가 품절**이었다. 그중 **333건(60%)이 실제로는 재고 보유** — 가짜 품절의 규모가 실물로 확정됐다.
  - 폐기 후보 184건은 전부 404 실측이다. 미해결 2건은 Costco JSON-LD 부재(레이아웃 예외)로 **품절로 단정하지 않고 원상 유지** — 의도한 동작이다([[D-247]]).
  - TES 98건은 크롤 불가 상태에서 사용자 판단으로 전량 폐기([[D-244]]), VTB 2건(3179·3189)은 규격 미지정으로 폐기.
- 상태: **수정완료(라이브 검증)** 2026-08-30 — 재발 차단 + 655건 전량 처리(555 재크롤 · 98 폐기 · 2 폐기)

### D-240: 정기 가격/재고 크롤이 아예 실행되지 않는다 (2026-08-30, D-060 감사 중 발견)

- 심각도: **표준(자동 갱신 부재)** | 위치: `BatchScheduler.scheduleDailyIherbPriceUpdate`
- 증상: 이 메서드에 **`@Scheduled` 애노테이션이 없고, 어디서도 호출되지 않는다.** 사실상 죽은 코드다. 운영 로그에 시작 메시지(`iHerb 소싱업체 정기...`)가 **0건**.
- 확인: `grep` 으로 호출부 0건, 클래스에 스케줄 애노테이션 없음, 외부 설정(quartz/xml/yml) 참조 0건. 활동로그 `BATCH_CRAWL_UPDATE` 마지막이 **07-25**(수동 실행).
- 즉 **가격/재고 갱신은 사람이 화면에서 배치를 돌릴 때만 일어난다.** `OrderSyncScheduler` 는 `@Scheduled` 가 제대로 붙어 있어 대조된다.
- 게다가 설령 살아 있었어도 **대상이 `VendorType.IHB` 하나뿐**이다(메서드 이름부터 `scheduleDailyIherb...`). 나머지 5개 소싱처 1,048건은 정기 갱신 대상이 아니다.
- **부분 수정(2026-08-30)**: `scheduleDailyIherbPriceUpdate` → `scheduleDailyPriceUpdate` 로 바꾸고 **대상을 `stockCrawlerRouter.supportedVendors()` 전체**로 확대했다. 크롤러가 생기면 자동으로 대상에 들어오고, 없으면 애초에 돌지 않는다.
- **`@Scheduled` 는 아직 붙이지 않았다** — [[D-239]] 의 655건 복구가 끝나기 전에 켜면 안 된다. 지금 켜도 크롤러 보유 소싱처(IHB·FTN)만 도니 해는 없지만, 복구 판단과 함께 켜는 게 맞다.
- 상태: **부분 수정(대상 확대 완료 · 스케줄 활성화 대기)** 2026-08-30

### D-241: 11번가는 재고 수량을 마켓에 보내지 않는다 (2026-08-30, D-060 감사 중 발견)

- 심각도: 경량(느리게 오는 위험) | 위치: `ElevenstMarketClient.syncPriceAndStock`
- 증상: 시그니처로 `int quantity` 를 받지만 **한 번도 사용하지 않는다.** 가격은 전송하고, 품절은 `stopdisplay`/`restartdisplay`(전시 on/off)로 처리한다.
- 우리 모델이 이진(재고유무)이라 **품절 자체는 반영된다** — 기능이 깨진 건 아니다. 다만 11번가에 등록된 재고 숫자가 우리 손을 떠나 있어, 주문이 쌓여 0 이 되면 **우리에겐 재고가 있어도 11번가에서 안 팔린다.**
- 수정 방향(미착수): 11번가 재고 수정 API 존재 여부 확인 후 배선. 없으면 문서화하고 닫는다.
- 상태: 발견

### D-242: 쿠팡 가격/재고 경로가 봉투를 못 읽으면 조용히 성공 처리한다 (2026-08-30, D-060 감사 중 발견)

- 심각도: 경량(현재 미발현) | 위치: `CoupangMarketClient.verifyEnvelopeLenient`
- 증상: `code` 가 비어 있으면(응답 봉투를 못 읽으면) **예외 없이 반환** = 성공 처리. 가격·재고·판매상태 3개 호출 전부 lenient 를 쓴다.
- 현재 미발현: 라이브 실측에서 쿠팡이 정상 에러 봉투를 반환했다([[D-209]] 판매중지 메시지). 즉 봉투는 읽힌다.
- 그래도 위험한 이유: **가장 중요한 기능**에 "못 읽으면 성공" 규칙이 걸려 있다. 쿠팡이 응답 형태를 바꾸면 조용히 거짓 성공이 된다 — [[D-181]]·[[D-208]]·[[D-212]] 계열.
- 수정 방향(미착수): strict 로 전환하되, 무바디 200 응답이 정상인 엔드포인트가 있는지 먼저 확인한다(그래서 lenient 였을 수 있다).
- 상태: 발견

### D-243: 카페24 품절 미반영 — D-060 이전 동기화분 676건 (2026-08-30)

- 심각도: 표준(과판매 위험) | 위치: 데이터(코드 아님)
- 증상: 로컬 품절 상품의 카페24 리스팅이 여전히 `selling=T` 인 경우가 있다. 표본 400건 교차: 품절 94건 중 **9건 불일치**(품절이고 중지 85).
- 원인: **카페24 실 API 호출은 [[D-060]] 수정(2026-07-11) 때 처음 붙었다.** 그 전에는 로컬 Map 만 패치했다. 품절 상품 1,232건 중 **676건이 7/11 이전 마지막 동기화** — 카페24 에 품절이 전달된 적이 없다.
- 주의: 이 676건 중 상당수가 [[D-239]] 로 **잘못 품절 처리된 것**이다(OCD·COK 등). **D-239 를 먼저 정리하지 않고 카페24에 품절을 밀어넣으면 팔리는 상품을 더 많이 감춘다.**
- 수정 방향(미착수): D-239 해소 후, 재고 상태가 확정된 상품만 재동기화한다.
- 상태: 발견

### D-235: 스마트스토어에 중복 리스팅이 이미 43건 존재한다 (2026-08-29, D-227 대조로 발견)

- 심각도: **표준(마켓 데이터 오염, 되돌리기 어려움)** | 위치: 마켓 실물(코드 아님)
- 증상: 카탈로그 대조에서 스마트스토어 `DUPLICATE_MARKET` **43건** — 우리 등록행 하나에 마켓 리스팅이 **둘 이상** 매칭된다. [[D-223]] 가드가 막으려던 유령 리스팅이 **가드 도입 이전에 이미 만들어져 있었다**는 증거다.
- 함께 나온 것: `IDENTIFIER_MISMATCH` 43건(저장된 식별자가 마켓과 다름 — 덮어쓰기의 흔적), `MISSING_LOCAL` 10건(마켓에만 존재). 쿠팡은 `IDENTIFIER_MISMATCH` 122건 · `MISSING_LOCAL` 6건.
- 대조 전체 결과(2026-08-29): 스토어 MATCHED 3,141 / 카페24 MATCHED 3,184 / 쿠팡 MATCHED 1,039. 11번가는 카탈로그 전량 조회 미지원([[D-208]] 계열)이라 `UNSUPPORTED`.
- 수정 방향(미착수): **코드로 자동 정리할 수 없다.** 어느 리스팅을 남길지는 판매 이력·리뷰·순위가 걸린 판단이라 사람이 정해야 한다. 우선 43건의 목록과 각 후보 리스팅의 판매량·상태를 뽑아 검토 자료를 만든다. [[D-223]] 가드가 있으므로 **더 늘어나지는 않는다.**
- 상태: 발견

### D-236: 스토어·카페24는 단건 조회를 지원하지 않아 부재를 확정할 수 없다 (2026-08-29)

- 심각도: 경량(감지 사각) | 위치: `SmartstoreMarketClient`·`Cafe24MarketClient` `supportsSingleLookup()`
- 증상: [[D-227]] 대조에서 스토어·카페24의 `deepLookups=0` — `supportsSingleLookup()` 이 false 라 단건 확인을 건너뛴다. 그래서 `STALE_LOCAL` 이 카탈로그 기준으로만 판정되고, **`persist=true` 여도 아무것도 기록하지 못한다**(각 2건씩 미확정으로 남음).
- 이것은 **안전장치가 의도대로 작동한 것**이다 — 증거가 부족하면 쓰지 않는다. 다만 두 마켓에서는 삭제 자동 감지가 사실상 불가능하다는 뜻이기도 하다.
- 대안: 두 마켓은 등록행 프로브([[is-synced-means-present-on-market]])가 `sellerCode` 가 아니라 **마켓 상품번호로 직접 조회**하므로 그쪽이 감지 경로다. 실제로 스토어 723건·카페24 677건을 프로브로 확인했다.
- 수정 방향(미착수): `fetchBySellerCode` 를 구현하거나, 대조가 단건 확인 수단으로 **프로브의 `checkPresence`** 를 쓰도록 잇는다. 후자가 중복이 적다.
- 상태: 발견

### D-234: 바코드 일괄 전송에 스로틀이 없어 네이버 요청 한도를 때린다 (2026-08-29, 재집계 중 실측)

- 심각도: **표준(마켓 API 한도 잠식)** | 위치: `ProductBarcodeSyncUseCase.sync` · `ProductBarcodeSyncController`
- 증상: `sync(productIds, dryRun)` 이 상품 사이에 **아무 지연 없이** 순회한다. 상품당 마켓 수만큼 GET 이 나가므로 400건 배치면 순식간에 수백~수천 요청이 된다.
- 실측: 재집계 400건 배치에서 **12분간 429 가 2,050건**. `last_sync_error=TRANSIENT_ERROR` 가 818건까지 치솟았다. 실패의 대부분이 실제 문제가 아니라 **우리가 너무 빨리 때린 결과**다.
- **동시 실행이 겹친 것도 원인**: 같은 시간에 스마트스토어 카탈로그 대조(`persist=true`)도 돌고 있었다. 네이버를 때리는 작업 둘을 병렬로 돌린 운영 판단 착오다.
- 피해가 제한된 이유: [[D-231]] A안 덕분에 429 는 `last_sync_error` 에만 기록되고 **`is_synced` 를 뒤집지 않았다.** 이전 모델이었다면 818건이 "마켓에 없음"으로 뒤집혀 배지가 전부 오염됐을 것이다.
- 수정: `sync(productIds, dryRun, throttleMs)` 오버로드 + 컨트롤러 `throttleMs` 파라미터(기본 0 — 기존 소량 호출은 그대로 빠르게). 상품 사이에만 지연하고 첫 건은 지연하지 않는다.
- 운영 규칙: 네이버가 섞인 일괄 실행은 **`throttleMs` 600 이상**, 그리고 **네이버를 쓰는 작업은 동시에 하나만**. [[is-synced-means-present-on-market]] 의 프로브 스로틀 1200ms 와 같은 계열의 제약이다.
- 테스트: `ProductBarcodeSyncThrottleTest` 2건
- 상태: **수정완료(검증대기)** 2026-08-29

### D-229: 쿠팡 상품 조회 경로의 vendorId 가 항상 비어 있다 (2026-08-29, 프로브 로그에서 발견)

- 심각도: 경량(현재 무해) | 위치: `CoupangMarketClient.extractMarketItem`
- 증상: 요청 경로가 `...seller-products/{id}?vendorId=` 로 **값 없이** 나간다. `properties.getVendorId()`(env)를 쓰는데 비어 있고, 인증 헤더(`X-Requested-By`)는 `CoupangRestClient.resolveCredentials()` 가 **DB 값**을 쓴다 — 같은 요청에서 출처가 갈린다.
- 현재 영향 없음: 이 엔드포인트는 vendorId 없이도 동작한다(프로브에서 ALIVE 5건, 쿠팡 API 직접 조회 6건 모두 성공).
- 수정 방향(미착수): `restClient.resolveVendorId()` 로 통일한다. 다만 경로가 바뀌면 **HMAC 서명 대상 문자열도 바뀌므로** 서명 생성과 실제 요청 경로가 같은지 확인 후 반영.
- 상태: 발견

### D-230: 프론트 배포가 사용자에게 도달하지 않는다 — index.html 에 Cache-Control 이 없다 (2026-08-29, D-222 라이브 검증 중 발견)

- 심각도: **표준(모든 프론트 배포에 영향)** | 위치: 운영서버 nginx 설정 (repo 밖)
- 증상: [[D-222]] 배지 수정을 배포하고 화면을 봤는데 **옛 화면 그대로**였다. 서버 번들에는 새 코드가 있는데 브라우저는 낡은 청크를 로드하고 있었다.
- 실측: 브라우저가 로드한 청크 `index-DaQUVVX_.js` / `ProductGrid-CrHr8p82.js`, 서버가 가진 청크 `index-DFHcJhpE.js` / `ProductGrid-BMleJyAz.js` — **완전히 다른 해시**. `curl -I` 결과 `index.html` 응답에 `Cache-Control` 헤더가 **아예 없다**(`Last-Modified`·`ETag` 만 있음).
- 원인: 헤더가 없으면 브라우저가 `Last-Modified` 기반 **휴리스틱 캐싱**으로 index.html 을 재사용한다. Vite 빌드는 청크 파일명에 해시를 박으므로 index.html 만 갱신되면 되는데, 그 index.html 이 캐시되어 **옛 해시를 계속 가리킨다.**
- 피해: 프론트를 배포해도 기존 사용자에게 **강제 새로고침 전까지 반영되지 않는다.** 이번처럼 "배포했는데 화면이 안 바뀐다"를 코드 문제로 오진하게 만든다.
- 수정(2026-08-29, 사용자 승인 후 서버 직접 반영): `infra/nginx/conf.d/default.conf` 에 `/sbshop-agent/assets/` 전용 location 을 신설하고, 나머지 `/sbshop-agent/` 에는 `Cache-Control: no-cache` 를 준다. 백업 `default.conf.bak-cache-20260829-111902`.
- **조건부 캐시가 필요했다**: 프론트 nginx 가 SPA 폴백(`try_files ... /index.html`)을 하므로 **없는 자산 요청에 HTML 이 200 으로 돌아온다.** 여기에 `immutable` 을 붙이면 **HTML 이 1년 캐시**된다 — 배포 후 옛 청크를 요청하는 탭에서 정확히 이 일이 생긴다. 그래서 `map $sent_http_content_type` 으로 갈랐다: `text/html` 이면 `no-cache`, 아니면 `immutable`.
- 검증: 실제 JS/CSS → `immutable` · 없는 자산(HTML 폴백) → `no-cache` · `index.html` → `no-cache` · API 401(로그인 게이트 유지) · HTTPS 200. 브라우저 하드 리로드 후 새 청크(`index-DFHcJhpE.js`/`ProductGrid-BMleJyAz.js`) 수신 확인.
- **주의**: 수정 이전에 캐시된 항목은 재검증 없이 쓰인다. 기존 사용자는 **한 번의 강제 새로고침**이 필요하고, 그 이후로는 자동 반영된다. 캐시 항목은 URL 단위라 `/products`·`/orders` 등 SPA 경로가 각각 별도 항목이다.
- 상태: **수정완료(라이브 검증)** 2026-08-29

### D-223: 재등록에 중복 가드가 없어 마켓에 유령 리스팅을 만든다 (2026-08-29, 상품 생명주기 딥다이브 중 발견)

- 심각도: **P1(마켓 데이터 훼손·되돌리기 어려움)** | 위치: `ProductPublishUseCase.publishToMarket`
- 증상: 재등록은 최초 등록과 **같은 함수를 다시 부르는 것**인데 `publishToMarket` 어디에도 `isSynced` 검사나 "이미 마켓에 있는가" 확인이 없다(전체 파일에 `isSynced` 참조 0건).
- 흐름: `savePending`이 기존 행을 재사용(`MarketRegistrationTxService:20-23`) → `client.publish()`가 마켓에 **새 상품 생성** → `markPublished`가 `market_identifiers`를 **새 식별자로 덮어씀**. 기존 리스팅(A)은 마켓에 남지만 우리는 새 식별자(B)만 알게 되어 **A가 추적 불가능한 유령이 된다.**
- 계열 가능성: [[D-215]] 쿠팡 임시저장 1,029건, [[D-210]] "쿠팡 우리에만 101건"과 같은 뿌리일 수 있다.
- 수정(2026-08-29): `ProductPublishUseCase.guardAgainstDuplicatePublish` 신설. `is_synced=true` + 조회용 식별자 보유 → `DuplicatePublishException`(HTTP 409) 거부. 통과 조건 둘 — `unsync_reason=DELETED_ON_MARKET`(정상 재등록) 또는 `force=true`(`POST /products/{id}/markets/{market}?force=true`). 더불어 `markPublished` 가 `replaceIdentifiersArchivingPrevious` 로 이전 식별자를 `previousIdentifiers` 배열에 **보존**한다.
- 판단 근거: 사유 미분류 미동기 행은 **통과시키되 경고 로그**로 두었다. 막으면 당시 미분류 2,021건의 재등록이 전부 불가능해진다. 실피해가 큰 `is_synced=true` 는 확실히 막히므로 목적은 달성된다.
- 라이브 검증(2026-08-29): 상품 30 / 11번가에 게시 요청 → **409 거부**, 식별자 `6589496920` 이 문구에 포함. 등록행 무변경(`updated_at` 07-25 그대로), 마켓 부작용 없음.
- 실물 증거: [[D-235]] 스마트스토어 중복 리스팅 43건 — **가드 도입 이전에 이미 만들어져 있던 것들**이다. 가드가 있으므로 더 늘지 않는다.
- 테스트: `ProductPublishDuplicateGuardTest` 5건
- 상태: **수정완료(라이브 검증)** 2026-08-29

### D-224: is_synced 가 "마켓 존재 여부"가 아니라 "마지막 작업 성공 여부"다 — 실패 사유가 유실된다 (2026-08-29)

- 심각도: **표준(다수 결함의 공통 원인)** | 위치: `MarketRegistration.markSyncFailed` + `ProductMarketSyncService:101`
- 증상: `markSyncFailed()`는 **가격·재고 동기화 실패 한 곳에서만** 호출되고, 그 catch 가 **모든 예외를 같게 취급**한다. 네트워크 순단·429·검증 400·"이미 삭제된 상품"이 전부 같은 `false`가 된다.
- `is_synced=false`에 섞여 있는 실제 상황: ① 마켓에서 삭제됨(→재등록) ② 검증 실패로 미반영(→데이터 수정 후 재시도) ③ 일시 오류(→그냥 재시도) ④ 한 번도 성공 못 함(→최초 등록) ⑤ 게시 직후 PENDING. **대응이 전부 다른데 표현이 하나다.**
- 파급: [[D-222]] 배지 구분 불가의 근본 원인. 재등록 판단 자체가 불가능해진다.
- 수정: `unsync_reason VARCHAR(32)` 컬럼 신설(운영 DB 수동 DDL 완료) + `UnsyncReason` enum + `UnsyncReasonClassifier`. 실패를 잡는 두 경로(`ProductMarketSyncService`·`ProductBarcodeSyncUseCase`)가 예외의 **원인 사슬 전체**를 훑어 분류·기록한다. `markSynced()` 가 성공 시 사유를 소거한다. `MarketRegistrationResponse.unsyncReason` 으로 API 에도 노출 — [[D-222]] 배지 분리의 재료가 준비됐다.
- 분류 기울기: 매칭 실패 시 `TRANSIENT_ERROR`(재시도해도 안전)로 떨어진다. 마켓이 문구를 바꾸면 삭제 감지를 놓치는 방향이지, 살아있는 상품을 삭제로 오판하는 방향이 아니다.
- 백필: `is_synced=false` 2,024건 중 **확실히 판별 가능한 3건만** `NEVER_SYNCED` 로 채웠다(식별자 없음 + 동기화 이력 없음). 나머지 **2,021건은 NULL 유지** — 틀린 사유는 사유 없음보다 나쁘다(오판 시 [[D-223]] 가드가 중복 등록을 허용해 버린다). 채우는 경로는 [[D-227]].
- **라이브 검증 중 자책 결함 발견·수정(2026-08-29)**: 최초 구현이 바코드 전송 실패 시 사유와 무관하게 `is_synced=false` 로 뒤집었다. 쿠팡 `"심사가 진행중입니다"` 는 상품이 **마켓에 멀쩡히 있다는** 뜻인데도 8건이 미동기로 전환됐다. 파급이 컸다 — ① 배지 미등록 표시 ② [[D-225]] 스킵 로직이 영구 제외 ③ **[[D-223]] 가드 무력화**(미동기는 통과하므로 유령 리스팅 생성 가능). 즉, 방금 만든 가드를 스스로 뚫었다.
- 수정: 바코드 경로에서 `is_synced` 를 뒤집는 것은 `DELETED_ON_MARKET`(마켓에 없다는 증거가 있을 때) **뿐**이다. 검증 실패·일시 오류는 응답·로그로만 보고한다. 훼손된 8건은 `is_synced=true`, `unsync_reason=NULL` 로 복구 완료.
- 교훈: **사유 기록과 상태 전환은 별개다.** "왜 실패했나" 를 남기는 것과 "마켓에 없다고 판정하는 것" 을 같이 묶으면 판정이 오염된다.
- 테스트: `UnsyncReasonClassifierTest` 6건 + `ProductBarcodeSyncIdentifierTest` 의 비전환 회귀 2건(심사중·검증실패)
- 상태: **수정완료(라이브 검증)** 2026-08-29

### D-225: 죽은 등록(is_synced=false)에도 마켓 전송을 시도한다 (2026-08-29, 바코드 확대 중 실측)

- 심각도: 경량 | 위치: `ProductBarcodeSyncUseCase.syncOne` (같은 패턴 여부를 `ProductMarketSyncService`·`ProductManageUseCase`에서도 점검 필요)
- 증상: `findByProductId`로 **모든 등록행**을 꺼내 `is_synced`를 보지 않고 전송한다. 대상 선정 SQL 은 "상품에 동기화된 마켓이 하나라도 있으면" 통과시키므로(`exists`), 스토어만 살아있는 상품의 **죽은 쿠팡 등록까지** 건드린다.
- 실측: 바코드 확대 670건 표본에서 쿠팡 FAILED 64건. 사유 분류 상위 = "이미 삭제된 상품입니다"·"심사가 진행중입니다"·"유효하지 않은 구매 옵션 값". 첫 번째가 이 결함에 해당.
- 피해: 거짓 실패로 집계됨(실은 **대상 아님**) · 죽은 등록에 API 호출 낭비(네이버·쿠팡 요청 한도 잠식).
- 수정: 전송 루프에서 `is_synced=false`는 `SKIPPED("미동기 등록 — 마켓에 없거나 반영 안 됨")`로 건너뛴다.
- 실측 효과: 재처리 24건 중 **15건이 SKIPPED** 로 걸러졌다 — 이전에는 전부 "실패"로 집계되던 것들.
- 상태: **수정완료(라이브 검증)** 2026-08-29

### D-226: 마켓 게시 성공 후 DB 갱신 실패의 자동 복구가 없다 (2026-08-29)

- 심각도: 경량(빈도 낮음·피해 큼) | 위치: `ProductPublishUseCase:69-73`
- 증상: `client.publish()` 성공 후 `markPublished` 가 실패하면 로그(`[게시-복구필요]`)만 남기고 예외를 던진다. **마켓에는 상품이 생겼는데 우리는 식별자를 모르는 상태**가 되고 PENDING 행만 남는다. 자동 복구 경로가 없다.
- 수정(2026-08-29): [[D-227]] 카탈로그 대조에 **복구 경로**를 붙였다. 식별자가 빈 등록행이 카탈로그에서 SB코드로 매칭되면 **마켓의 식별자를 되찾아** `enrichIdentifier` + `markSynced()` 한다. 리포트에 `recoveredIdentifiers` 카운트 추가. `persist=true` 에서만 동작한다.
- **부재 사유도 정확해졌다**: 식별자가 없는 행의 부재는 `DELETED_ON_MARKET` 이 아니라 **`NEVER_SYNCED`** 로 기록한다 — 있었던 적이 없으니 삭제가 아니다. 이전 구현은 둘을 구분하지 않았다.
- 별도 적재(2단 커밋) 대신 화해 배치를 택한 이유: 게시 경로에 단계를 더하면 실패 지점이 늘어난다. 대조는 **이미 주기적으로 도는 읽기 경로**라 여기에 얹는 게 새 실패를 만들지 않는다.
- 현황: 실측 복구 대상 **3건**(스토어 2·카페24 1, 전부 2026-08-21 생성). 대조 결과 셋 다 마켓에도 없어 `NEVER_SYNCED` 가 이미 정확했다 — **지금은 복구할 게 없고, 값은 예방적이다.**
- 테스트: `MarketCatalogRecoveryTest` 4건 (Red 2건 확인 후 구현)
- 상태: **수정완료(검증대기)** 2026-08-29

### D-227: 카탈로그 대조가 "마켓에 없음"을 알고도 기록하지 않는다 (2026-08-29)

- 심각도: 경량 | 위치: `MarketCatalogReconciliationService`
- 증상: [[D-210]] 리포트가 마켓 카탈로그와 우리 DB를 대조해 "우리에만 있음"(쿠팡 101건)을 세지만 **읽기 전용이라 DB 에 남기지 않는다**(`save(` 0건). 매번 다시 조회해야 알 수 있고, 그 사이 배지·전송 로직은 계속 죽은 등록을 살아있는 것으로 취급한다.
- 수정 방향(미착수): [[D-224]]의 `unsync_reason` 컬럼에 `DELETED_ON_MARKET` 을 기록하도록 쓰기 경로를 붙인다. 읽기 전용 원칙을 깨므로 **명시 옵션(`persist=true`)에서만** 동작하게 한다.
- 우선순위 상승(2026-08-29): [[D-224]] 컬럼이 생겼지만 과거 **2,021건이 미분류**로 남았고, 이를 능동적으로 채울 수 있는 유일한 경로가 이 대조다.
- 수정(2026-08-29): `MarketSyncReportRequest.persist` + `MarketCatalogReconciliationService.persistAbsent` 신설. `STALE_LOCAL` 중 **부재가 확정된 것만** `DELETED_ON_MARKET` 으로 기록한다. 리포트에 `persistedAbsent` 카운트 추가. 트리거: `GET /api/v1/products/market-sync?deep=true&persist=true`.
- **핵심은 증거 강도를 가른 것**이다. 기존 코드가 이미 `STALE_LOCAL` 을 세 등급으로 구분하고 있었다 — ① 카탈로그에만 없음 ② 카탈로그에도 단건 조회에도 없음(확정) ③ 단건 확인 실패(미확정). **② 만 기록한다.** 카탈로그 목록은 페이지 누락·필터로 빠질 수 있어 그것만으로는 삭제 증거가 못 된다.
- 안전장치: `persist=true` 인데 `deep=false` 면 **아무것도 쓰지 않고 경고만** 남긴다(부재를 확정할 수단이 없으므로). 이미 `DELETED_ON_MARKET` 인 행은 다시 쓰지 않는다(멱등). 기본값은 `persist=false` — 읽기 전용 리포트 동작을 깨지 않는다.
- 테스트: `MarketCatalogPersistAbsenceTest` 6건. **변이 검사로 판별력 확인** — 증거 강도 검사를 제거하니 3건이 정확히 실패했다(구현을 먼저 써서 Red 를 못 봤기에 대체 검증).
- 상태: **수정완료(라이브 검증)** 2026-08-29 — 쿠팡 `persistedAbsent=51`, 스토어·카페24는 단건조회 미지원이라 0건([[D-236]])

### D-228: 마켓에 보낸 값을 우리 등록행에 남기지 않는다 (2026-08-29, 쿠팡 경고 6건 실측 중 발견)

- 심각도: 경량(추적성) | 위치: `ProductBarcodeSyncUseCase.push`
- 증상: 바코드를 마켓에 성공적으로 PUT 했는데 `market_identifiers` 의 `barcode` 는 **빈 문자열 그대로**였다. 쿠팡 경고 화면의 6건을 실측한 결과 6/6 모두 마켓에는 값이 있고 우리 식별자는 비어 있었다.
- 실측: `{"sellerProductId":"11898889204","vendorItemId":"74409528805","barcode":"", ...}` — 마켓 API 조회 시 `barcode=737870163961`, DB `sb_product.barcode` 와 일치.
- 피해: "이 상품 바코드를 마켓에 반영했는가"를 DB 만으로 판정 불가 → 매번 마켓 API 재조회 필요, 요청 한도 잠식.
- 계열: [[D-224]]와 같은 병이다 — **로컬 상태가 마켓 상태를 반영하지 않는다.**
- 수정: 전송 성공 직후 `reg.enrichIdentifier("barcode", …)` 로 실제 보낸 값을 기록한다. `dryRun` 은 건드리지 않는다.
- 테스트: `ProductBarcodeSyncIdentifierTest` 3건 (기록·dryRun 무변경·실패 시 사유 기록)
- 상태: **수정완료(검증통과)** 2026-08-29

### D-246: 쿠팡 판매가 변경 폭 제한(−50%/+100%)을 몰라 재가격이 조용히 거부된다 (2026-08-30, D-239 재크롤 로그에서 발견)

- 심각도: **P1(매출 직결 — 쿠팡만 가격이 어긋난 채 방치)** | 위치: `CoupangMarketClient` 가격 갱신 경로 + `ProductMarketSyncService`
- 증상: 재가격 배치가 쿠팡에서 반복 거부됐다. 재크롤 배치 로그에서 **84회**, 마켓 동기화 실패는 **전량 쿠팡(28/28)**.

  ```
  400 Bad Request: {"code":"ERROR","message":"가격변경에 실패했습니다.
  [옵션ID[78260151147] : 판매가 변경이 불가능합니다.
   변경전 판매가의 최대 50% 인하/최대 100%인상까지 변경가능합니다.]"}
  ```

- 원인: 쿠팡은 **1회 변경 한도가 기존가 대비 −50% ~ +100%** 다. 우리는 목표가를 한 번에 밀어넣는다. 마진 25% + 실배송비로 재산정하니 변동폭이 이 한도를 넘는 상품이 다수 생겼다.
- 피해: 스토어·카페24는 한도가 없어 새 가격이 반영됐고 **쿠팡만 옛 가격으로 남았다** → 같은 상품이 마켓별로 다른 가격. 역마진 위험.
- **다행인 점**: [[D-244]] 의 분류기가 이 400 을 `VALIDATION_FAILED` 로 잡아 `is_synced` 를 건드리지 않았다. [[D-231]] A안(존재/쓰기결과 분리)이 없었다면 정상 상품 수십 건이 "마켓에서 사라짐"으로 오판됐을 것이다.
- 해결 방향: **다단계 인하** — 목표가가 한도 밖이면 한도까지(기존가×0.5) 내려 PUT 하고, 신선 GET 으로 갱신된 기준가를 다시 읽어 반복한다. 수렴 실패 시 수동 대상으로 기록. 한도는 마켓별로 다르므로 클라이언트 내부에 둔다.
- 수정(2026-08-30): `CoupangMarketClient.changePriceStepwise` — 목표가가 한도 밖이면 **한도까지만 옮기고 다시 시도**한다(최대 8단계). 인하는 절반씩, 인상은 두 배씩 수렴한다.
  - **현재가 출처가 핵심이었다.** 처음엔 저장된 `marketDetailedInfo` 에서 `salePrice` 를 읽으려 했는데, 운영 실측 결과 **1,262건 중 20건에만** 들어 있었다. 그대로 뒀으면 1,242건이 단발 전송으로 떨어져 아무것도 안 고쳐졌다. → **신선 GET**(`GET /vendor-items/{id}`)으로 현재가를 읽는다(프로젝트 표준 원칙과 동일).
  - 현재가를 끝내 못 읽으면 **1회만 시도한다.** 추측으로 여러 번 밀어넣으면 엉뚱한 가격이 마켓에 남는다.
  - 8단계 안에 수렴하지 못하면 예외 — 조용히 중간 가격으로 두지 않는다.
- 테스트: `CoupangSteppedPriceChangeTest` 5건. 쿠팡의 거부 규칙을 흉내내는 스텁(한도 밖이면 400, 통과하면 현재가 갱신)으로 실제 왕복을 재현했다. Red 확인(한도 초과 2건 실패, 한도 내 1건은 통과 — 기존 동작 보존). **변이 검사**: 한도 무시 변이 → 2건 실패, 현재가 미상 시 추측 허용 변이 → 1건 실패.
- **1차 수정 실패 (2026-08-30, FTN 배치 `1a32728a` 실측)**: 현재가를 `GET /vendor-items/{id}` 로 읽게 했는데 **그런 엔드포인트가 없다.**

  ```
  404 PRECONDITION_FAILED: No exactly matching API specification for
  /api/v1/marketplace/vendor-items/78506056296 found
  ```

  PUT 경로(`/vendor-items/{id}/prices/{price}`)와 대칭일 것이라 **짐작하고 검증 없이 배포**했다. 운영에서 **86회 전부 실패**했고, 단계 조정은 0회, 한도 거부 3건이 그대로 남았다. 테스트는 통과했는데 — 스텁이 내 잘못된 가정을 그대로 흉내냈기 때문이다. **가짜 계약을 검증한 테스트였다.**
- **2차 수정**: 실제 조회 경로는 `GET /seller-products/{sellerProductId}` 이고, 가격은 `data.items[]` 안에 **옵션별로** 들어 있다. `vendorItemId` 가 일치하는 항목을 골라야 한다(첫 항목을 집으면 다른 옵션 가격을 기준으로 삼는다).
  - `sellerProductId` 는 `marketDetailedInfo` 에 **1,262건 중 20건**뿐이지만 `marketIdentifiers` 에는 **1,262건 전부** 있다 → `ProductMarketSyncService.mergeRawDataWithIdentifiers` 로 둘을 합쳐 클라이언트에 넘긴다(상세정보가 우선 — 마켓에서 읽어온 최신값을 식별자로 덮지 않는다).
  - 테스트 스텁을 **실제 경로만 응답하고 나머지는 404 를 던지도록** 고쳤다. 그러자 3건이 Red 로 떨어졌다 — 운영에서 벌어진 일이 테스트에 재현됐다.
- 교훈: **외부 API 경로를 대칭으로 짐작하지 않는다.** 이미 운영에서 성공하는 호출을 찾아 그 경로를 쓰거나, 배포 전에 한 건 실호출로 확인한다. 스텁은 내 가정이 아니라 **관측된 응답**을 흉내내야 한다.
- 테스트: `CoupangSteppedPriceChangeTest` 5건 + `SyncPassesIdentifiersTest` 4건.
- **라이브 검증 완료 (2026-08-31, 상품 13 단건 배치)**: 단계마다 서로 다른 경고를 심어두고 **하나도 찍히지 않음**을 확인했다.

  | 단계 | 실패 시 경고 | 결과 |
  |------|-------------|------|
  | 저장 JSON 의 `salePrice` | — | **없음** → 마켓 조회 경로를 탐 |
  | 식별자의 `sellerProductId` | "sellerProductId 가 없어" | 미발생 |
  | `GET /seller-products/{id}` | "현재가 조회 실패" / `Coupang GET Error` | 미발생 |
  | `items[]` vendorItemId 매칭 | "items 에서 vendorItemId 를 찾지 못했다" | 미발생 |
  | 가격 PUT | 동기화 실패 | 성공 |

  **대조군**: 같은 코드 경로에서 FTN 배치(`1a32728a`)는 `현재가 조회 실패` 를 **86회** 냈다. 지금은 **0회** — 검사가 항상 침묵하는 게 아니라 실제로 고쳐졌다.
- **미검증 잔여**: 한도를 실제로 넘는 변경에서 단계 조정이 도는지는 아직 못 봤다(이번 건은 동일 조건이라 가격 변동이 없었다). OCD 재크롤 때 원가가 크게 올라 자연히 검증된다.
- 상태: **수정완료(조회 경로 라이브 검증 · 단계 동작은 OCD 재크롤 시 확인)** 2026-08-31

### D-247: Costco 일부 페이지에 JSON-LD Product 가 없다 (2026-08-30, 재크롤 중 2건)

- 심각도: 경량(2건) | 위치: `scraper/scrapers/jsonld.py` `CostcoUkScraper`
- 증상: `p/102730`(Natco 코코넛밀크), `p/327998`(Jamieson 츄어블 비타민C) 두 건에서 `JSON-LD 에 Product 가 없다 — 레이아웃 변경 의심` 예외. 404 가 아니므로 폐기 후보도 아니고, 재고도 갱신되지 않아 **옛 상태(품절)로 남는다.**
- **동작 자체는 옳다** — 모를 때 품절로 단정하지 않는 것이 [[D-239]] 의 교훈이다. 다만 이 2건은 영원히 갱신되지 않는다.
- 해결 방향: JSON-LD 부재 시 DOM 폴백(가격·재고 셀렉터) 또는 수동 확인 큐. 2건뿐이라 우선순위 낮음.
- 상태: **발견** 2026-08-30

### D-248: 쿠폰율·최소마진이 전역 단일값이라 비-아이허브 상품이 "20% 할인 매입"으로 잘못 계산된다 (2026-08-30, D-245 후속 감사 중 발견)

- 심각도: **P1(매출 직결 — 전 소싱처 상시 저가 등록)** | 위치: `MarketSalePriceResolver.resolveCouponRate/resolveMinMarginPrice` + `PricePolicy`(전역 1행)
- 증상: `sb_product.margin_rate` 는 상품별인데 **`couponRate`·`minMarginPrice` 는 `sb_price_policy` 전역 1행에만 있다.** 배치가 오버라이드를 넘길 때만 소싱처별 값을 쓰고, **마켓 등록·배지 클릭 경로(`ProductPublishUseCase.resolveForProduct(product, marketType)`)는 오버라이드가 없어 전역값이 그대로 적용된다.**
- 실측 전역 정책: `marginRate=15, couponRate=20, minMarginPrice=5000` — **이것은 아이허브 정책이다**(사용자 확인). 그런데 OCD·VTB·COK·FTN 에도 그대로 걸린다.
- 결정적 지점 — `MarginCalculator.applyCoupon` 은 **매입가를 깎는다**:

  ```java
  private BigDecimal applyCoupon(BigDecimal buyPrice, BigDecimal couponRate) {
      BigDecimal factor = ONE.subtract(couponRate.divide(100));
      return buyPrice.multiply(factor);   // 원가를 20% 낮게 잡는다
  }
  ```

  즉 `couponRate=20` 은 "고객 할인"이 아니라 **"우리가 20% 싸게 산다"** 는 뜻이다. 아이허브는 실제로 쿠폰으로 싸게 사지만, **코스트코·오카도·비타바이오틱스는 정가로 사고 배송비까지 낸다.** 없는 할인을 가정해 원가를 20% 낮게 잡고 판매가를 매긴다.
- 피해 실측(COK Colman's 머스타드, 원가 37,348원 · 마진 25% · 수수료 18.5%):

  | | 판매가 | 실제 마진 |
  |---|---|---|
  | 현재(쿠폰 20% 오적용) | 63,600원 | 14,486원 (22.8%) |
  | 올바름(쿠폰 0%) | 76,800원 | 25,244원 (32.9%) |

  건당 **13,200원 저가 등록 · 마진 10,758원 증발.** `minMarginPrice=5000` 바닥이 역마진만 겨우 막고 있다.
- [[D-245]] 와 같은 뿌리다 — 가격 정책이 **소싱처 차원을 갖지 않는다.** D-245 는 배치가 마진율을 일괄로 덮는 문제였고, 이쪽은 **오버라이드 없는 모든 경로가 아이허브 가정으로 도는** 문제다.
- 해결 방향: 소싱처별 가격 정책(`vendor` 키를 가진 정책 행) 도입 후, 상품의 소싱처로 조회해 마진·쿠폰·최소마진을 함께 해석한다. 전역 행은 소싱처 미지정 시 폴백으로만 남긴다.
- 미확인 사항: `sb_product.margin_rate` 가 IHB 2,145건에서 **4%** 인데 사용자가 말한 아이허브 정책은 **15%** 다. 상품별 값이 정책값을 이기므로 현재 아이허브는 4% 로 계산된다. 의도인지 확인 필요.
- 상태: **발견** 2026-08-30

### D-249: ~~margin_rate 가 레거시 이관값에서 멈춰 있다~~ → **오진, 결함 아님** (2026-08-30)

- 심각도: **P0(잠재 — 배치 1회로 매출 직결 손실)** | 위치: `sb_product.margin_rate` 데이터 + `MarketSalePriceResolver.resolveMarginRate`
- 발견 경로: [[D-248]] 조사 중 "아이허브 마진이 4% 로 저장돼 있다"는 것이 이상해 출처를 추적했다.
- **출처**: 레거시 이관값이다. IHB 2,145건 중 **2,144건이 정확히 `4.00`, 1건만 `5.00`, 생성일이 전부 2026-02-22.** 코드에 4 를 기본값으로 쓰는 곳이 없고 임포트 스크립트도 저장소에 없다 — 레거시 DB 컬럼을 그대로 옮겨왔다. 다른 소싱처(FTN 20/25, OCD·VTB·COK 25)도 동일하게 2026-02 이관값이다.
- **결정적 증거 — 실제 판매가는 이 값으로 만들어지지 않았다.** 현재 `sale_price` 에서 마진율을 역산했다(쿠폰 20%·수수료 18.5%, n=2,109):

  | 지표 | 값 |
  |------|-----|
  | 중앙값 | **10.7%** |
  | 1사분위 | 9.1% |
  | 3사분위 | 13.3% |

  4% 가 아니고 **단일값도 아니다.** 이 분산이 실제 운영 방식이다 — 사용자 확인: "주문 수에 따라 판매량이 떨어지면 마진을 확 낮추는 식으로 운영한다." 즉 **진짜 마진 정책은 `sale_price` 안에만 있고 `margin_rate` 컬럼은 그것을 한 번도 따라간 적이 없다.** [[D-224]]·[[D-228]] 과 같은 계열 — 상태 컬럼이 현실을 반영하지 않는다.
- **피해(잠재)**: `resolveMarginRate` 는 **상품별 값을 정책값보다 먼저** 집는다. IHB 재가격 배치를 오버라이드 없이 돌리면 4% 가 적용된다:

  ```
  마진 4% 재계산 시 판매가 변동
    중앙값  -8.6%   하위25% -12.0%
    1,639건 / 2,109건 가격 하락
  ```

  이번 세션에서 "IHB 2,146건 재크롤" 을 다음 작업으로 제안한 상태였다. **오버라이드 없이 실행했으면 배치 1회로 1,639건이 인하됐다.**
- 부수 확인: 쿠폰 0% 로 역산하면 2,090/2,109 가 역마진 → 현재 아이허브 가격은 **20% 쿠폰 매입 전제로 산정된 것이 확실하다.** [[D-248]] 의 반대편 증거다.
- **정정 — 결함이 아니었다 (2026-08-30, 사용자 지시로 배치 이력 확인 중 드러남)**

  위 진단의 전제가 틀렸다. "IHB 배치가 한 번도 돌지 않았다"고 판단했으나, `sb_process_status` 실측 결과
  **08-24 · 08-26 · 08-27 에 2,146건씩 전량 성공**으로 돌았다(`job_type=CRAWL_AND_UPDATE_PRICE_STOCK`).
  배치 로그의 가격이 현재 판매가와 **정확히 일치**한다:

  ```
  [231114IHB087] 가격:29900   → 현재 sale_price 29,900
  [220712IHB010] 가격:198800  → 현재 sale_price 198,800
  ```

  즉 `margin_rate=4.00` 은 2026-02 레거시 잔재가 아니라 **관리자가 08-27 배치에서 실제로 쓴 값**이다. 백필 대상이 아니다.
- **역산으로 복원한 마지막 배치 설정**: 마진 4%(배치가 직접 저장 — 추정 아님) · 쿠폰 **0%** · 최소마진 **0**.
  이 조합으로 재계산하면 2,109건 중 **2,030건이 현재가와 ±1% 이내**다. 초기 역산에서 **쿠폰 20% 를 가정한 것이 오판의 원인**이었다.
- **드러난 진짜 문제는 반대편이었다**: [[D-248]] Phase 1 에서 소싱처 정책 IHB 를 사용자 설명대로 `마진 15 · 쿠폰 20` 으로
  시드했는데 **실제 운영값은 4/0** 이었다. 그대로 배치를 돌렸으면 1,617건이 내려갔다.
  사용자 확인 후 운영 DB 정책을 **`마진 4 · 쿠폰 0 · 최소마진 0`** 으로 정정했다.
  (최소마진은 0 일 때 2,030건이 ±1% 이내, 5,000 일 때 1,907건 — 0 이 현재가를 더 정확히 재현한다.)
- 교훈: **"이 값이 이상하다"는 직감으로 결함을 세우기 전에, 그 값을 쓴 실행 이력부터 찾는다.**
  코드와 데이터만 보고 "쓰인 적 없다"를 단정했다가 이력 테이블 한 번 조회로 뒤집혔다.
- 상태: **결함 아님(오진) · 부수 조치 완료** 2026-08-30 — 소싱처 정책을 실제 운영값으로 정정

### D-250: 배치가 쓴 세 가격 파라미터 중 마진율만 저장돼 판매가를 재현할 수 없다 (2026-08-30, 사용자 지적)

- 심각도: **표준(구조 결함 — [[D-248]]·[[D-249]] 의 공통 뿌리)** | 위치: `PriceInfo` · `ProductUpdateCommand` · `BatchPriceStockService` · `MarketSalePriceResolver`
- 사용자 지적: "마진율, 쿠폰율, 최소마진가가 상품에 어떤 건 저장되고 어떤 건 안 된다는 게 이상하다."
- 증상: 배치 화면은 **마진율·쿠폰율·최소마진가 셋을 함께 받아** 판매가를 만드는데, 상품에 남는 것은 **마진율뿐**이었다.

  ```java
  ProductUpdateCommand.builder()
      .costPrice(buyPrice)
      .marginRate(marginRate)   // ← 이것만 저장
      .salePrice(salePrice)     // 쿠폰율·최소마진가는 계산에만 쓰이고 사라짐
  ```

  쿠폰율·최소마진가는 전역 `sb_price_policy` 한 줄에만 존재했다.
- 피해: **그 판매가가 어떤 조건에서 나왔는지 사후에 알 수 없다.** 그리고 배치가 아닌 경로(배지 클릭 등록 등)로 재계산하면 마진율은 상품값, 쿠폰율·최소마진가는 전역값이 되어 **서로 다른 시점·출처의 값이 한 계산식에 섞인다.** [[D-248]] 이 바로 이 혼합의 결과다.
- 수정:
  - `PriceInfo` 에 `coupon_rate`·`min_margin_price` 추가(둘 다 nullable), `ProductUpdateCommand` 에 동일 파라미터 추가.
  - `BatchPriceStockService` 가 세 값을 함께 저장한다.
  - `MarketSalePriceResolver` 의 쿠폰율·최소마진가 해석을 **마진율과 같은 우선순위**로 통일: `오버라이드 → 상품값 → 전역 정책`.
  - **기존 행은 NULL 이므로 전역 정책으로 폴백한다 — 현재 동작이 그대로 유지된다.** 운영 DB 3,095건 전부 NULL 확인.
- 구조 정리(Tidy First): `ProductUpdateCommand` 를 위치 인자 26개로 호출하던 `ProductSanitizer` 를 빌더로 바꿨다. 필드가 늘 때마다 무관한 호출부가 깨지던 구조였다.
- 테스트: `PricingParamsSymmetryTest` 5건. **Red 를 단언 실패로 확인**(2건 실패 → 표면만 추가한 상태에서 값이 저장되지 않음·정책이 상품값을 이김). 폴백·오버라이드 우선 2건은 대조군으로 처음부터 통과해 기존 동작 보존을 확인했다.
- 스키마: `ALTER TABLE sb_product ADD COLUMN IF NOT EXISTS coupon_rate numeric(5,2), min_margin_price numeric(15,2)` — 운영 DB 수동 반영 완료(가산적·되돌리기 가능).
- **남은 위험(후속 필요)**: 배치 화면의 초기값이 `marginRate: 15, couponRate: 20, minMarginPrice: 5000` 으로 **하드코딩**돼 있다. 아이허브 정책 그대로다. 이제 값이 **영속되므로**, 다른 소싱처 상품을 기본값으로 돌리면 잘못된 쿠폰율이 상품에 박힌다 — 수정 전보다 위험이 커졌다. [[D-248]](소싱처별 가격 정책)에서 함께 해소해야 한다.
- 상태: **수정완료(검증통과)** 2026-08-30

### D-248 진행: 소싱처별 가격 정책 (2026-08-30, Phase 1·2-1 완료)

- 계획 문서: `docs/normalize/plans/D-248_소싱처별-가격정책.md` (라이브 검수 결과·단계별 상태 포함)
- **라이브 검수 확정**: 배치 업데이트 화면에서 소싱업체를 `IHB` → `VTB` 로 바꿔도 `마진 15 / 쿠폰 20 / 최소마진 5,000` 이 그대로다. 비타바이오틱스는 쿠폰 없이 정가 매입인데 화면은 20% 할인 매입을 가정한다.
- **설정 화면은 중복이 아니라 오배치였다**: 가격 정책 카드는 `Settings.tsx:449` 로 **마켓 탭 조건문 바깥**에 있다(전역 1개). 마켓 탭 아래 놓여 마켓별 설정처럼 보였을 뿐이다. 수수료는 이미 `sb_fee_policy` 로 마켓별 분리가 끝나 있고, 빠져 있던 축은 소싱처다.
- **Phase 1(완료)**: `sb_vendor_price_policy` 신설 — 마진·쿠폰·최소마진에 더해 `ship_currency`·기초/추가 배송비(금액+무게 쌍)·국내 배송비(요금+무료기준)를 담는다. 기초·추가 두 쌍 구조라 현재 규칙(0.5kg까지 £10.5, 이후 0.5kg마다 +£2)이 그대로 표현되고 달러 소싱처는 통화만 바꾸면 된다. 5개 소싱처 시드 완료(IHB 15/20/5000 KRW · FTN·OCD·COK·VTB 25/0/5000 GBP).
- **Phase 2-1(완료)**: 해석 순서를 `오버라이드 → 상품값 → 소싱처 정책 → 전역 정책` 으로 확장. `VendorPricePolicy` 엔티티·JPA 리포지토리(core 배치 — 기존 `PricePolicyRepository` 관례)·`VendorPricePolicyService` 신설.
- 사용자 결정: 다섯 파라미터 전부 소싱처별로 다름 · 국내 배송비도 소싱처별 설정값으로 · 상품 0건인 `AMZ`·`TES` 는 목록에서 제외(TES 는 소프트 삭제라 복구 가능).
- 테스트: `VendorPricePolicyResolutionTest` 3건. Red 를 단언 실패로 확인.
- **부수 발견 — `Product.create` 가 마진율을 0 으로 채운다**: `createPriceInfo` 의 `defaultIfNull(command.marginRate(), BigDecimal.ZERO)`. 상품값이 소싱처 정책을 이기므로 **마진 없이 생성된 상품은 0% 마진이 고정된다.** Red 를 잡다가 드러났다. 별도 결함으로 다룬다.
- **다음 위험**: Phase 1 시드가 들어가면서 기존 3,095건(쿠폰·최소마진 NULL)의 폴백 대상이 전역 정책 → 소싱처 정책으로 바뀐다. 가격 영향 확인이 필요하다.
- 상태: **진행중(Phase 2-2·2-3·3·4 대기)** 2026-08-30

### D-251: Product.create 가 마진율을 0 으로 채워, 마진 없이 만든 상품은 0% 가 고정된다 (2026-08-30, D-248 Red 확인 중 발견)

- 심각도: **표준(잠재 — 신규 상품 저마진 고정)** | 위치: `Product.createPriceInfo`
- 증상: 생성 명령에 마진율이 없으면 **`null` 이 아니라 `ZERO` 로 채운다.**

  ```java
  BigDecimal margin = defaultIfNull(command.marginRate(), BigDecimal.ZERO);
  ```

- 피해: [[D-248]] 이후 해석 순서는 `오버라이드 → 상품값 → 소싱처 정책 → 전역 정책` 인데, **상품값 0 이 소싱처 정책을 이긴다.** 즉 마진 없이 생성된 상품은 소싱처 정책을 아무리 잘 채워도 **영원히 0% 마진으로 계산된다.** 폴백이 작동하려면 "값 없음"이 `null` 로 남아야 한다.
- 발견 경로: `VendorPricePolicyResolutionTest` 에서 소싱처 정책이 이기지 않아 원인을 추적하다 드러났다. 테스트가 아니었으면 조용히 묻혔을 결함이다.
- 계열: 0 과 null 을 구분하지 않는 문제. [[D-250]] 에서 쿠폰율 0(정가 매입)과 null(미지정)을 구분해야 했던 것과 같은 병이다 — 그쪽은 테스트로 명시해 뒀다.
- 수정(2026-08-30, 사용자가 ②안 선택): `ProductCreateUseCase.withResolvedMargin` — 마진율이 없으면 **소싱처 정책값으로 채운다.** 정책도 없으면 `IllegalStateException` 으로 생성을 거부한다(0% 마진 상품이 조용히 생기는 것보다 생성 실패가 낫다). `Product.create` 의 `defaultIfNull(..., ZERO)` 는 그대로 두되, 여기까지 오면 항상 값이 채워져 있다.
  - **이건 폴백이 아니다.** 마진율 미지정은 "정책을 따르겠다"는 정상 경로다. 폴백은 실패했을 때 다른 값으로 때우는 것이고, 여기서는 정책이 없으면 던진다.
- 실측: 마진율 0/NULL 상품은 vendor 없는 고아 1건뿐 — **아직 피해 없음, 예방 조치다.**
- 테스트: `ProductCreateMarginFromPolicyTest` 3건. 구현 후 통과라 **변이 검사로 판별력 확인** — 정책 해석을 건너뛰는 변이에서 2건이 정확히 실패했다.
- 상태: **수정완료(검증통과)** 2026-08-30

### D-252: F&M 단종 상품은 404 가 아니라 200 + "This Product is not available." 라 폐기 후보로 안 잡힌다 (2026-08-30, FTN 재크롤 실패 28건 추적)

- 심각도: 표준(28건이 영구 실패로 적체) | 위치: `scraper/scrapers/fortnum.py`
- 증상: FTN 재크롤 배치(`1a32728a`)에서 28건이 전부 같은 이유로 실패했다.

  ```
  FTN 스크랩 실패(error): price not found (200) url=.../balsamic-jelly-100g
  ```

- 실측 응답:

  ```json
  {"httpStatus":200, "name":"This Product is not available.",
   "price":null, "inStock":true, "availabilityText":"In stock"}
  ```

  **HTTP 200 이고 "재고 있음"으로 읽힌다.** 스크래퍼는 `404` 또는 `"page not found"` 만 링크 소멸로 보는데, F&M 은 단종 상품에 **소프트 404**(200 + "This Product is not available.")를 준다.
- 피해: 폐기 후보로 기록되지 않아 **매 배치마다 같은 28건이 실패로 쌓인다.** 사람이 보기 전까지 영원히 갱신되지 않는다. [[D-247]](Costco JSON-LD 부재 2건)과 같은 계열 — "실패로 남기는 것"이 안전하긴 해도 해소되지는 않는다.
- **가격이 `null` 인 것이 유일한 방어였다.** 가격만 파싱됐다면 `inStock=true` 로 읽혀 **단종 상품이 판매중으로 유지**됐을 것이다. 재고 판정이 소프트 404 를 못 걸러낸다는 뜻이다.
- 수정: `gone_markers` 에 `"this product is not available"` 추가 — 가격 파싱 전에 `status="not_found"` 로 반환한다. 자바가 `sourceGone=LINK_DEAD` 로 기록해 폐기 후보가 된다.
- **라이브 검증 완료 (2026-08-31, 대조군 포함)**:

  | 대상 | 이전 | 이후 |
  |------|------|------|
  | `balsamic-jelly-100g` | `error` · inStock **true** | **`not_found`** · inStock false |
  | `dried-mango-slices-90g-pack` | `error` · inStock **true** | **`not_found`** · inStock false |
  | **정상 상품(대조군)** `28-piece-ginger-napolitains-182g` | — | **`ok` · £22.0** — 정상 경로 안 깨짐 |
- 상태: **수정완료(라이브 검증)** 2026-08-31

### D-253: 원본 소멸 상품의 마켓 가격이 원가 0 으로 재계산돼 쓰레기 값으로 덮인다 (2026-08-31, OCD 배치 라이브 사고)

- 심각도: **P0(라이브 사고 — 37건이 실제로 덮였다)** | 위치: `BatchPriceStockService` 원본소멸 분기 + `ProductMarketSyncService.priceForMarket`
- 증상: OCD 재크롤(`00009907`) 중 쿠팡 단계 조정 로그에서 발견했다.

  ```
  [쿠팡] 변경 폭 제한으로 단계 인상/인하: 182300 → 91150 (목표 11000)
  [쿠팡] 변경 폭 제한으로 단계 인상/인하: 99000 → 49500 (목표 11000)
  ```

  **서로 다른 상품이 전부 목표 11,000원.** 추적하니 원본 소멸(`LINK_DEAD`) 상품들이었다.
- 원인:

  ```java
  BigDecimal existingCost = product.getPriceInfo().getCostPrice();   // = 0
  new PricingInputs(existingCost != null ? existingCost : BigDecimal.ZERO, ...)
  ```

  의도는 "기존 원가로 다시 계산해 같은 가격을 보내자"였으나 **두 가지가 틀렸다.**
  ① 원가가 **0** 이라 마진·수수료만 얹힌 쓰레기 값이 나온다. ② 애초에 **원가에서 재계산하는 것 자체가 "미변경"이 아니다** — 마진율이 바뀌면 가격도 바뀐다.
  배치 메시지의 `가격 미변경` 은 **DB 얘기일 뿐** 마켓에는 나갔다.
- 피해(실측): **37건**이 마켓에서 덮였다. 원래 판매가 47,500~182,300원. 마켓별 CAFE24 37 · SMART_STORE 37 · ELEVEN_STREET 16 · COUPANG 5.
  **DB `sale_price` 는 온전하다** — 복구 기준값이 있다. 해당 상품은 전부 품절 처리라 즉시 판매 위험은 없지만, 재고가 복구되면 그 가격에 팔린다.
- 사용자 지적: "원가가 0이라는 건 품절이 되었거나 링크가 사라졌다는 뜻이다. 그런 건 업데이트하지 않고 별도 기록을 해둘 필요가 있다."
- 수정:
  - `ProductMarketSyncService.hasUsableCost` 신설 — **원가가 없거나 0 이하면 가격을 계산하지 않고 `null` 을 돌려준다.** 마켓 클라이언트는 `price == null` 이면 가격 갱신을 건너뛰고 재고만 반영한다.
  - 원본소멸 분기가 `PricingInputs(null, ...)` 을 넘긴다 — 의도를 코드로 드러낸다.
  - 배치 메시지를 `가격 미전송` 으로 정정. 기존 문구는 사실과 달랐다.
  - **별도 기록은 이미 있다**: `source_gone_at` · `source_gone_reason`([[D-239]] 에서 신설). 이번 건들은 전부 `LINK_DEAD` 로 기록돼 있어 복구 대상을 그대로 뽑을 수 있었다.
- **동반 발견 — 쿠팡 10원 단위**: 단계 조정이 반으로 나누며 1원 단위를 만들어 거부당했다(`91,150 → 45,575`).

  ```
  400: 판매가는 최소 10원 단위로 입력가능합니다. (1원단위 입력 불가)
  ```

  인하는 올림, 인상은 내림으로 10원에 맞춘다 — 반대로 하면 한도를 벗어나 다시 거부당한다.
  **이 거부가 우연히 쿠팡 오염을 5건으로 막아줬다**(다른 마켓은 37건).
- 테스트: `NoPriceWithoutCostTest` 4건 · `CoupangSteppedPriceChangeTest` 에 10원 단위 케이스 추가(총 6건). 쿠팡 스텁에 실제 거부 규칙을 넣자 Red 로 떨어졌다.
- 교훈: **배치 메시지가 실제 동작을 반영하는지 확인해야 한다.** "가격 미변경"이라는 문구를 믿고 이 경로를 검토 대상에서 빼고 있었다.
- 상태: **수정완료(배포 대기) · 오염 37건 복구 필요** 2026-08-31

### D-254: 폐기 후보 191건이 기록만 되고 관리자에게 보이지 않는다 (2026-08-31, 사용자 지적)

- 심각도: 표준(운영 공백) | 위치: `ProductSearchCondition` · `ProductSpecifications` · `ProductListResponse` · 프론트 상품 관리
- 사용자 지적: "품절인지 링크소멸인지 단종인지 파악하고 상태를 기록할 필요가 있다. 비정상 상품은 품절 처리해 두고, 상태를 파악해 두면 배치가 끝나고 관리자가 삭제할 수 있어야 한다."
- **판별·기록은 이미 되고 있었다**([[D-239]] 에서 신설). 배치가 스크래퍼 status 에 따라 갈라 처리한다:

  | 스크래퍼 status | 자바 처리 | DB 기록 |
  |----------------|----------|---------|
  | `ok` + inStock | 재고·가격 정상 갱신 | `IN_STOCK` |
  | `ok` + !inStock | 품절(가격은 갱신) | `OUT_OF_STOCK` |
  | `not_found` | 품절 + 폐기 후보 | `source_gone_reason=LINK_DEAD` |
  | `discontinued` | 품절 + 폐기 후보 | `source_gone_reason=DISCONTINUED` |
  | `blocked`·`error` | **아무것도 안 함** | 변경 없음 |

- **끊긴 곳 ①(고침)**: API 는 `sourceGoneReason` 을 내려주는데 **프론트가 쓰지 않았다** — `frontend/src` 에 `sourceGone` 참조 0건. 목록 필터도 소싱처·재고·마켓뿐이라 **폐기 후보 191건이 3,000건 속에 묻혀 있었다.**
  - `SourceGoneFilter`(ALL / GONE_ONLY / ALIVE_ONLY) 신설 → 검색 조건·Specification·컨트롤러 파라미터 연결.
  - `ProductListResponse` 에 `sourceGoneAt` 추가 — **언제부터 사라졌는지가 삭제 판단 근거**다.
  - 프론트: 필터 패널에 `원본 상태` 라디오, 그리드에 `원본 상태` 컬럼(사유 배지 + "YYYY-MM-DD부터").
  - 일괄 삭제는 이미 있었다(`productBulkApi.bulkDeleteProducts`) — 필터로 골라 체크 후 삭제하면 [[D-237]]·[[D-244]] 의 마켓 삭제 경로를 탄다.
- **끊긴 곳 ②(미해결)**: 단종을 판별하는 스크래퍼가 절반뿐이다 — iHerb·jsonld(Ocado/Costco/Tesco)만 있고 **F&M·Vitabiotics 는 없다.** 그래서 191건이 **전부 `LINK_DEAD`** 이고 `DISCONTINUED` 는 0건이다.
  - 다만 **실익을 먼저 봐야 한다** — 둘 다 결국 폐기 대상이라 사람이 할 일이 같다면 나눌 필요가 없다. 화면을 쓰면서 판단한다.
- 현재 폐기 후보: OCD 136 · VTB 34 · COK 14 · FTN 7 = **191건**
- 상태: **수정완료(①) · 단종 판별 확대는 보류(②)** 2026-08-31

### D-255: 크롤 실패가 상품에 남지 않아 그 상품이 조용히 옛 가격으로 굳는다 (2026-08-31, 사용자 지적)

- 심각도: 표준(무증상 정체) | 위치: `Product` · `BatchPriceStockService`
- 사용자 지적: "봇차단·오류도 **기록은 해줘야** 하는 것 아닐까? 처리는 아무것도 안 하는 게 맞을 것 같고(일시적인 오류일 수 있으니)."
- 증상: `blocked`·`error` 는 재고·가격을 건드리지 않는 것이 옳다([[D-239]] 의 교훈). 그런데 **기록도 배치 이력(`sb_process_status`)에만 남고 상품에는 남지 않았다.** 배치가 끝나면 그 상품은 겉보기에 멀쩡한데 실제로는 갱신이 멈춘 상태다.
  - 실제 사례: 2026-08-31 FTN 배치에서 F&M 31건이 실패했는데, **상품만 봐서는 알 수 없었다.** 배치 로그를 뒤져서야 원인([[D-252]] 소프트 404)을 찾았다.
- **폐기 후보와 다른 축이다**: `sourceGone` 은 "원본이 없다"(확정), `lastCrawlError` 는 "확인하지 못했다"(미확정). 섞으면 일시 차단이 폐기로 둔갑한다.
- 수정:
  - `sb_product.last_crawl_at` · `last_crawl_error` 신설(운영 DB 수동 DDL).
  - `Product.recordCrawlFailure(reason)` — **재고·가격을 건드리지 않는다.** 사유 없으면 거부, 500자 초과는 잘라 담는다(컬럼 초과로 저장이 통째로 실패하면 안 된다).
  - `Product.recordCrawlSuccess()` — 이전 실패 기록을 지운다. 복구된 상품이 계속 실패로 보이면 안 된다.
  - `BatchPriceStockService`: 성공·원본소멸 경로는 `recordCrawlSuccess`, catch 블록은 `recordCrawlFailure`. **기록 자체가 실패해도 배치는 계속한다** — 부수 기록 때문에 본 작업을 멈추지 않는다.
  - 화면: `원본 상태` 컬럼이 `정상` / `링크 소멸·단종`(폐기 후보) / **`확인 실패`**(크롤 실패, 사유는 툴팁) 세 갈래를 보여준다.
- 테스트: `CrawlOutcomeRecordTest` 5건(재고·가격 불변 · 성공 시 초기화 · 사유 필수 · 길이 절단 · 폐기 표시와 독립).
- 상태: **수정완료(배포 대기)** 2026-08-31

### D-256: 삭제 직후 확인 모달이 다시 뜨고 검색 입력이 먹지 않는다 (2026-09-01, 마켓 삭제 시험 중 발견)

- 심각도: 경량(운영 불편) | 위치: `ProductGrid.handleBulkDelete` (antd `Modal.confirm`)
- 증상: 「선택 삭제」로 삭제가 완료된 뒤에도 **확인 모달이 다시 나타난다.** 그 상태에서 통합 검색창에 입력하면 값이 반영되지 않아, **매번 「취소」를 눌러야** 다음 작업이 가능하다.
  - 마켓 삭제 시험(951 → 957 → 1051)에서 매 건마다 재현됐다. 검색어를 바꾸려 `triple_click`+타이핑을 해도 이전 검색어가 그대로 남았다.
- 원인 추정: `Modal.confirm` 이 명령형으로 호출되는데 삭제 성공 후 닫히지 않거나, 목록 갱신으로 컴포넌트가 다시 그려지면서 모달이 재생성된다. 모달이 살아 있는 동안 뒤쪽 입력이 포커스를 못 받는다.
- 피해: **일괄 삭제 한 번에는 문제가 없다**(모달을 한 번만 연다). 건별 반복 작업에서만 걸린다. 데이터에는 영향 없음.
- **위험 요소**: 남아 있는 모달의 「삭제」를 잘못 누르면 **의도치 않은 선택분이 삭제될 수 있다.** 시험 중 실제로 그 상황을 만나 취소로 빠져나왔다.
- **원인 정정(2026-09-01)**: 모달이 스스로 다시 뜨는 게 아니었다. **버튼이 반응 없어 보여 두 번 눌러 모달이 두 개 열린 것**이다. 삭제는 건별 마켓 API 호출이라 50건이면 수십 초가 걸리는데 **진행 표시가 전혀 없었다.** 즉 UI 결함은 "모달 중복"이 아니라 **"장시간 작업에 피드백이 없다"** 였다.
- 수정: 삭제 진행 중에는 버튼을 잠그고 `삭제 중… 12/50` 처럼 진행률을 표시한다. 재진입 가드(`if (deleting) return`)로 모달 중복 자체를 막고, 취소·완료 시 상태를 되돌린다.
- 상태: **수정완료** 2026-09-01

### D-257: 일괄 삭제에 스로틀이 없어 네이버 요청 한도에 걸린다 (2026-09-01, 폐기 후보 50건 삭제 실측)

- 심각도: 표준(재시도로 회복 가능하나 매번 실패분이 남음) | 위치: `productBulkApi.bulkDeleteProducts`
- 증상: 폐기 후보 50건 일괄 삭제에서 **7건 실패**. 그중 **6건이 네이버 429**다.

  ```
  GW.RATE_LIMIT — 요청이 많아 서비스를 일시적으로 사용할 수 없습니다.
  ```

  `for` 루프가 쉬지 않고 건별 삭제 API를 호출한다. [[D-234]](바코드 전송 스로틀 부재)와 같은 문제가 삭제 경로에도 있었다.
- 수정: 건별 400ms 간격 + 진행 콜백. 실패해도 다음 건으로 계속한다(기존 동작 유지).
- 상태: **수정완료** 2026-09-01

### D-258: 쿠팡은 심사중 상품 삭제를 거부하는데 일시 오류와 구분되지 않았다 (2026-09-01, 사용자 실측)

- 심각도: 표준(재시도 낭비·조치 불가) | 위치: `MarketFailureClassifier` · `MarketRegistration`
- **사용자 실측**: 삭제 실패한 쿠팡 상품을 마켓에서 직접 확인하니 **'검토중'** 상태였고, 마켓 화면에서 삭제를 시도하면 **"승인대기중/심사중인 상품은 삭제할 수 없습니다."** 가 뜬다.
- 증상: API 응답은 이렇게 온다.

  ```
  409 CONFLICT: 업체상품[10621199335]이 없거나 삭제가 불가능한 상태입니다.
  ```

  이 문구가 `BLOCKED_MARKERS` 에 없어 **분류가 안 됐다.** 429(일시 오류)와 같은 통에 담기면 **재시도해도 영원히 실패**하는 건이 매번 섞인다.
- 또한 실패 **분류만 저장되고 원문이 남지 않아**, `BLOCKED_BY_MARKET` 이 "심사중이라 못 지운다"(심사 끝나면 풀림)인지 "권한이 없다"(계정 문제)인지 구분할 수 없었다.
- 수정:
  - `BLOCKED_MARKERS` 에 `"삭제가 불가능한 상태"`·`"삭제할 수 없습니다"`·`"승인대기"` 추가.
  - `MarketRegistration.recordSyncError(type, message)` 오버로드 — **원문 사유를 500자까지 함께 저장**(`last_sync_error_message`, 운영 DB 수동 DDL). 성공 시 함께 지운다.
  - `MarketRegistrationResponse` 에도 필드 추가 — 엔티티와 바이트 동일 계약(`ResponseDtoContractTest`)이 이를 강제했고, 그 테스트가 누락을 잡아냈다.
- 테스트: `DeleteBlockedByReviewTest` 4건(심사중 차단·마켓 문구·429는 일시·차단은 소멸 아님) · `SyncErrorMessageTest` 4건. Red 를 단언 실패로 확인.
- 상태: **수정완료** 2026-09-01

### D-259: 카페24에 전송 실패·종료 임박 상품이 쌓여 있다 (2026-09-01, 사용자 관찰)

- 심각도: **P1(판매 중단 위험)** | 위치: 카페24 연동 · 마켓플러스
- 사용자 관찰(카페24 화면 실측):

  | 상태 | 건수 |
  |------|------|
  | **전송실패** | **544** |
  | 승인대기 | - |
  | 승인반려 | - |
  | **7일 내 종료** | **128** |

- **전송실패 544건**이 가장 크다. 우리가 보낸 상품이 카페24(마켓플러스)에서 마켓으로 나가지 못한 것이라, 그 상품들은 **판매되지 않고 있을 가능성**이 있다. 실패 사유가 무엇인지, 우리 쪽 `is_synced` 와 어떻게 어긋나 있는지 대조가 필요하다.
- **7일 내 종료 128건**은 시한이 있다 — 방치하면 자동으로 판매가 끝난다.
- 착수 시 확인할 것: ① 전송실패 사유 분포(카페24 화면/API) ② 우리 DB `sb_market_registration` 의 CAFE24 행과 대조 ③ [[D-243]](카페24 품절 미반영 676건)과 겹치는지

**진단 (2026-09-01, 라이브 크롬 + 운영 DB 실측)**

*7일 내 종료 128건 — 128건 전부 동일 원인. 계정 설정 문제가 아니다.*

- 계정 설정은 사용자 말대로 정상: G마켓(`/mp/account/regist/gmarket/76767`)·옥션(`/auction/76766`) 둘 다 `use_auto_selling=T`(카페24 진열/판매상태 연동 사용함) + `selling_period_day=F`(**무제한**).
- **"판매기간 자동연장"은 예방적 연장기가 아니다.** 카페24 설명: *"판매종료 상품 수정 시 자동으로 연장되는 판매기간을 설정합니다"*. 아직 판매중인 상품의 종료일이 다가와도 아무 것도 하지 않는다 → 무제한으로 둬도 "7일 내 종료"는 계속 뜬다.
- 증거: 대표 상품 P0000PYM 의 전송이력 1개월 24건이 **전부 `상품수정`**, `기간연장` 유형 **0건**. 08-31·08-27·08-24 상품수정이 모두 **성공**했는데 종료일은 `2026-09-02` 그대로.
- 모수 실측(마켓상품관리, G마켓+옥션):

  | 항목 | 건수 |
  |------|------|
  | 판매중 전체 | 2,414 |
  | 그중 **종료일 보유** | **341** (나머지 2,073 = 종료일 없음 = 무제한) |
  | 341건의 시작일 | 2023-11(31) · 2024-01(310) — **100%** |
  | 341건의 종료일 | 2026-09월 137 · 10월 21 · 11월 183 |
  | 그중 7일 내 종료 | **128** |

  → 2023-11·2024-01 최초 등록 코호트만 유한 판매기간을 물고 있고, 이후 등록분은 전부 무제한이다.
- 매출 위험 실재: 128건(고유 sb_code 121개) 중 **120개가 `IN_STOCK`/`ACTIVE`** (운영 DB `sb_product` 조회). 방치하면 재고 있는 상품의 판매가 끊긴다.

*전송실패 544건 — 사유 100% 단일*

- 상품관리이력 1개월 전송실패 **6,034건**, 5개 페이지(500건) 샘플링 결과 사유 단 하나:
  > `[실패] 연동된 쇼핑몰상품이 '진열/판매'상태일 때만 판매중지를 해제할 수 있습니다. (관련 설정: 기본 > 마켓계정관리 > 마켓수정 > 카페24 진열/판매 연동 설정)`
- 판매종료 55건 중 **54건이 이 실패** 상태. 해당 상품들은 운영 DB 에서 **전부 `OUT_OF_STOCK`** — 재고 0 이라 카페24 쇼핑몰 상품이 판매안함이고, 그래서 마켓플러스가 마켓 상품을 되살리지 못한다.
- 즉 이 실패는 **재고 0 상품의 정상 귀결**이라 즉각적 판매 손실은 없다. 다만 *만료 → 수정전송 → 자동연장* 복구 경로가 이걸로 막혀 있다.
- 마켓별 분해: 판매중 2,414 · 판매중지 554 · 판매종료 55 · 합계 3,023. 2025~2026 신규 시작일 **0건** ([[market-badge-click-publish]] — G마켓/옥션 자동전송 불가와 일치).

**조치 (2026-09-01, 라이브 실행 완료)**

1. **대조 실험 세팅** — `selling_period_day=F`("무제한")가 ⓐ *판매기간을 무제한으로 설정* 인지 ⓑ *자동연장 안 함* 인지 미확정이었다(기간연장 다이얼로그에는 15/30/60/90 만 있고 무제한이 없다). 같은 상품이 G마켓·옥션 양쪽에 있으므로 **옥션만 `90일`로 변경(저장 확인), G마켓은 `무제한` 유지**해 대조군을 만들었다.
   - **판독법**: 앞으로 우리 시스템의 정기 `상품수정` 전송이 도는 동안 **옥션 종료일만 계속 밀리고 G마켓은 고정**이면 ⓑ("무제한"=자동연장 꺼짐)가 확정 → 그때 G마켓도 90일로 바꾼다.
2. **341건 전부 판매기간 90일 연장** — 마켓플러스 `판매관리 > 기간연장`. 100/100/100/41 네 배치.
   - 결과: 상품관리이력 **`판매기간연장` 341건 전부 [성공], 실패 0건**.
   - 종료일 이동: 2026-09월 137 · 10월 21 · 11월 183 → **2026-12월 141 · 2027-01월 200**.
   - **`7일 내 종료` 128 → 0** (마켓플러스 처리필요 카운터 `-`). 9·10·11월 만료분 전부 0건.
3. 자동화 메모: `기간연장`은 `window.open` 팝업(`send_request`)에 폼을 던지고 그 팝업의 **[전송] 2단계 클릭**이 있어야 실제 전송된다. 팝업은 MCP 탭 그룹 밖이라 조작 불가 → **폼 `target`을 `_self`로 바꿔 같은 탭에 확인화면을 띄우고 [전송]을 클릭**하는 방식으로 실행했다. 이 절차를 모르면 "눌렀는데 아무 일도 안 일어남"으로 보인다.

**잔여**

- 판매기간은 **90일마다 다시 밀어줘야 한다**(만료 전에는 자동연장이 개입하지 않으므로). 다음 만료 임박: **2026-12월 141건 · 2027-01월 200건**. 대조 실험 결론이 나면 계정 설정만으로 자동화될 수 있다.
- **전송실패 543건**(D-259 나머지 절반)은 미조치 — 재고 0 상품의 정상 귀결이라 판매 손실은 없으나, 만료 후 자동복구 경로를 막는다. [[D-243]] 과 함께 다룰 것.

**추가 실측 (2026-09-01 14:20, 대조 실험 판독 + 전송실패 해부)**

*① 대조 실험 판독 — 판매중 상품에는 어떤 설정으로도 자동연장이 걸리지 않는다*

- 옥션(자동연장 `90일`) · G마켓(`무제한`) 상태에서 같은 상품 `201120IHB002`(P0000NSU)에 **빠른수정:판매가 전송**(동일가 65,400) → 양 마켓 **[성공]**.
- 결과: **양쪽 다 종료일 `2027-01-30` 그대로.** 옥션도 안 밀렸다.
- 결론: **계정 설정(무제한/15/30/60/90)으로는 "7일 내 종료"를 막을 수 없다.** 자동연장은 판매종료(만료) 상품에만 걸리므로, 판매중 상품은 **90일 주기 수동 `기간연장`이 유일한 수단**이다.
- 부수 정정: 이전 기록의 "종료일 없음"은 틀렸다 — **무제한 = 종료일 `2999-12-31`** 이다(초기 조회 범위 2020~2030이 이를 잘라냈다).

*② "왜 341건만 유한한가" — 재현 가능한 구분자는 없다*

| 축 | 341건(유한) | 전체 판매중 |
|----|-----------|-----------|
| 상품구분(is_matched) | 쇼핑몰 상품 341 | 쇼핑몰 상품 2,414 (마켓/PRO 0) |
| 상품버전(version) | 상품2.0 341 | 상품2.0 2,414 (일반 0) |
| 판매시작일 | 2024-01-25(202)·01-26(108)·2023-11-08~14(31) | 같은 날 2024-01-25 **1,388** · 01-26 **678** |
| 마켓 카테고리 | 꿀·기타영양제·시리얼·설탕… 분산 | 분산 |

- **같은 날 같은 배치에서 15%만 유한**하다 — 등록 시점·상품유형·카테고리 어느 것으로도 갈리지 않는다.
- 판매상태별 종료일 분포: 무제한(2999-12-31) = 판매중 2,073 + 판매중지 522 = **2,595**, 유한 = 판매중 341 + 판매중지 32 + 판매종료 55 = **428**. **무제한 상품 중 판매종료는 0건**이다.
- 가장 잘 맞는 설명(미확증): 자동연장이 걸리는 유일한 계기가 "판매종료 상품 수정"이므로, **과거에 한 번 만료됐다 되살아난 상품만 2999-12-31 을 받았고, 아직 만료를 겪지 않은 428건이 원래 기간을 유지 중**이다. 확증하려면 ESM 판매자센터에서 개별 상품의 판매기간 이력을 봐야 한다(사용자 로그인 필요).

*③ 전송실패 543건 — 우리가 품절처리할 때마다 매번 실패한다 (사용자 지적 사실)*

- 상태 분해: **판매중지 487 · 판매종료 54 · 판매중 2**. 99.6%가 우리가 의도적으로 품절처리한 상품이다.
- 한 상품(`P0000NXN`/201126IHB025)의 1개월 이력: **`상품수정` 24건(마켓 2개 × 12회) 전부 실패, 성공 0건.** 08-05·06·07·11·19·24·27·31 — **우리 배치가 돌 때마다 재발생**한다.
- 연쇄 구조:
  1. `Cafe24MarketClient.syncPriceAndStock` 이 품절 시 `selling="F"`(판매안함) + 재고 0 을 카페24 쇼핑몰에 PUT — **이 PUT 은 성공**한다.
  2. 마켓플러스 공통설정 `마켓상품 자동전송 = 사용함(is_send_rtq=T)` 이라 쇼핑몰 상품 수정을 감지해 **마켓에 `상품수정` 자동 전송**.
  3. 마켓 상품은 이미 판매중지 → 수정하려면 판매 재개가 필요한데 쇼핑몰이 '판매안함' → **거부**.
  4. `BatchPriceStockService` 의 `changed = priceChanged || 상태변경` 이 **환율 변동만으로도 매 배치 참**이 되어 ①이 반복된다.
- **우리 시스템은 이 실패를 볼 수 없다** — 카페24 API 는 성공을 돌려주므로 `is_synced` 는 정상이고, G마켓/옥션은 우리가 직접 쓰지 않는다(마켓플러스 경유). 빨간 543 은 순전히 마켓플러스 쪽 표시다.
- 관련 설정 실측: 공통설정 `품절 전송 설정 = 마켓 상품의 판매 상태를 품절 또는 판매중지로 변경(use_sold_out=T)`, `마켓상품 자동전송 = T`. 계정설정 `카페24 진열/판매상태 연동 = 사용함(use_auto_selling=T)` — 실패 문구가 지목하는 설정이 이것이다.

**제안 (미실행, 승인 대기)**

- **A안(우리 코드, 권장·소규모)**: 품절 상태가 **유지되는 동안**에는 카페24 전송을 하지 않는다. `BatchPriceStockService` 에서 `changed = 상태변경 || (priceChanged && !soldOut)` 로 좁히면 된다(현행 `priceChanged || 상태변경`). 품절 **진입 시 1회**·**재입고 시 1회**만 전송 → 실패가 구조적으로 사라진다. 품절 중 카페24 가격이 낡지만 어차피 판매안함이라 노출되지 않는다.
- **B안(마켓플러스 설정, 라이브 시험 필요)**: 계정설정 `카페24 진열/판매상태 연동 = 사용안함`. 그러면 쇼핑몰 '판매안함'이 마켓에 연동되지 않아 "판매중지 해제" 시도 자체가 없어지고, 품절은 `use_sold_out=T` 가 재고 0 으로 처리한다. 잃는 것은 판매기간 자동연장인데 **①에서 판매중 상품에는 무용함이 확인**됐다. 다만 검증 안 된 가설이라 1개 계정에서 시험 후 판단.
- **되돌릴 것**: ①의 판독이 끝났으므로 **옥션 계정의 `판매기간 자동연장`을 `90일` → `무제한`으로 원복**하는 편이 낫다(만료 후 복구 시 2999-12-31 을 받도록). 현재 옥션만 90일로 남아 있다.

**정정 (2026-09-01 15:30, 사용자 ESM 판매자센터 실측) — 앞의 ①②를 뒤집는다**

ESM 상품 목록에서 세 그룹의 실제 판매종료일·판매상태를 대조했다.

| 그룹 | 마켓플러스 장부 | **ESM 실제** |
|------|----------------|-------------|
| 손 안 댄 상품 (`210125IHB089`·`201120IHB058`) | 종료일 **2999-12-31** | **설정안함** · 판매가능 |
| 오늘 90일 연장한 상품 (`201120IHB002`·`200909WA002`) | 2026-12 / 2027-01 | **2100-06-29 (365일+)** |
| "판매종료" 55건 (`201126IHB015`·`201126IHB019`) | **2026-08-03 · 판매종료** | **2099-12-31 (365일+)** · **판매중지** |

- **마켓플러스의 종료일은 ESM 실제 판매기간이 아니다.** 세 번째 줄이 직접 증거다 — 마켓플러스가 "2026-08-03에 종료됐다"고 분류한 상품이 ESM 에는 **2099-12-31 까지 열려 있고**, 안 팔리는 이유는 만료가 아니라 **판매중지**(우리 품절처리의 정상 귀결)다. 그 날짜는 ESM 에 존재하지 않는다.
- 따라서 **`7일 내 종료` 지표는 실제 판매 중단 위험 신호가 아니다.** 마켓플러스 자체 장부가 ESM 과 어긋난 것이다.
- **앞 기록의 다음 주장은 틀렸다. 폐기한다:**
  - ~~"무제한 상품은 절대 만료되지 않는다 / 판매종료 55건은 만료된 것"~~ → 55건은 **판매중지**다.
  - ~~"과거에 만료됐다 되살아난 상품만 2999-12-31 을 받았다"~~ → 만료 사건 자체가 없었다. 근거 소멸.
  - 무제한 = `2999-12-31` 은 **마켓플러스가 ESM 의 "설정안함"을 표시하는 방식**이었다.
- 오늘의 341건 90일 연장은 **ESM 에 실제로 반영**됐다(연장분만 `2100-06-29`, 대조군은 여전히 `설정안함`). 손해는 없으나 **원래 필요한 작업이 아니었을 가능성이 크다** — 연장 직전 ESM 값은 이제 확인할 수 없다.
- 사용자 확인: ESM 상품 목록에서 **일괄 선택 → `판매기간 변경`** 으로 수정 가능. 즉 원하면 428건을 `설정안함`으로 되돌리거나 통일할 수 있다(실익은 낮다 — 2099/2100년은 74년 뒤다).
- **남은 진짜 문제**: 마켓플러스 화면의 `종료일`·`판매종료` 라벨을 운영 지표로 신뢰할 수 없다. 판매기간 판단의 단일 원본은 **ESM** 이다.

- 상태: **해결(위험 오탐으로 판명)·전송실패는 [[D-262]] 로 분리** 2026-09-01

### D-260: 쿠팡 임시저장 232건이 계속 남아 있다 (2026-09-01, 사용자 관찰)

- 심각도: 표준(재고 자산 미활용) | 위치: 쿠팡 등록 경로
- 사용자 관찰: 쿠팡 상품 관리에 **임시저장 232건**이 쌓여 있고 줄지 않는다. 전체 1,141건 중 **판매중은 366건**뿐이다.
- 임시저장은 등록을 시작했으나 완료되지 않은 상태다. 우리 쪽에서 등록을 시도했다가 실패했는데 **실패로 기록되지 않았거나**, 쿠팡이 심사 전 단계에서 멈춘 것일 수 있다.
- 착수 시 확인할 것: ① 232건이 우리 `sb_market_registration` 에 어떻게 기록돼 있는지(`is_synced` · `last_sync_error`) ② 등록 API 응답이 성공으로 처리됐는지([[D-181]] 봉투 검사 계열 의심) ③ 임시저장을 완료 등록으로 밀어 올릴 수 있는지
- 상태: **발견** 2026-09-01

### D-261: 쿠팡이 상품 품질 경고를 다수 표시한다 (2026-09-01, 사용자 관찰)

- 심각도: 표준(노출·매출 영향) | 위치: 쿠팡 상품 데이터
- 사용자 관찰(쿠팡 상품 관리 화면, 전체 1,141건):

  | 경고 | 건수 |
  |------|------|
  | **브랜드 정보 수정필요** | **181** |
  | **정보 수정하고 매출 올리기** | **47** |
  | 증빙서류 제출필요 | 4 |
  | 품절 | 3 |
  | 아이템위너 아님(상위 20% 중) | 2 |
  | 승인반려 | 1 |

- 대부분 **상품 정보 품질** 문제다. 브랜드 정보 181건이 가장 크고, 이는 우리 `brand` 필드가 쿠팡 브랜드 마스터와 맞지 않아 생겼을 가능성이 크다.
- **작업 방식(사용자 요청)**: 라이브 크롬으로 쿠팡 화면을 보며 경고 항목을 실제로 열어 **무엇을 고쳐야 하는지 실측**한 뒤, 우리 데이터에서 일괄 보정할 수 있는 것과 건별로 봐야 하는 것을 가른다.
- 착수 시 확인할 것: ① 경고별로 쿠팡이 요구하는 필드가 무엇인지 ② 우리 DB 로 일괄 보정 가능한지 ③ [[market-badge-click-publish]] 의 재게시 경로로 반영 가능한지
- 상태: **발견** 2026-09-01

### D-262: 품절 상품에 재전송이 반복돼 마켓플러스 전송실패가 쌓인다 (2026-09-01)

- 심각도: 표준(운영 노이즈·자동복구 차단) | 위치: `BatchPriceStockService` · 카페24↔마켓플러스 경계
- 재현: 재고 0 상품은 `Cafe24MarketClient.syncPriceAndStock` 이 `selling="F"` 를 PUT 한다. 마켓플러스 공통설정 `마켓상품 자동전송=사용함` 이라 쇼핑몰 상품 수정을 감지해 G마켓/옥션에 `상품수정` 을 자동 전송하는데, 마켓 상품이 이미 판매중지라 *"연동된 쇼핑몰상품이 '진열/판매'상태일 때만 판매중지를 해제할 수 있습니다"* 로 거부된다. `changed = priceChanged || 상태변경` 이라 **환율 변동만으로 매 배치 참**이 되어 반복됐다 — 한 상품이 1개월에 24회(2마켓 × 12회) 전부 실패([[D-259]] 의 전송실패 543건 = 판매중지 487 + 판매종료 54 + 판매중 2).
- 우리 쪽에서는 보이지 않았다 — **카페24 API 는 성공을 돌려주므로** `is_synced` 는 정상이고, G마켓/옥션은 우리가 직접 쓰지 않는다(마켓플러스 경유).
- 수정: `changed = 상태변경 || (가격변경 && 품절아님)`. 품절 **진입 시 1회 · 재입고 시 1회**만 전송된다. 크롤 배치·수동 배치 두 경로 모두 적용. 기존 테스트 2건은 3-arg `syncPriceStock` 호출 형태를 단언하고 있어 4-arg 로 맞췄다(행위 동일).
- 테스트: `BatchSoldOutSkipsCafe24ResendTest` 4건(품절유지=false · 재입고=true · 판매중 가격변경=true · 수동배치 품절유지=false). Red 를 인자 불일치로 확인. 회귀 1,025건 통과.
- 검증 대기: 배포 후 다음 배치에서 마켓플러스 `전송실패` 카운터가 더 늘지 않는지 확인. 기존 543건은 각 상품이 재입고돼 전송에 성공할 때 풀린다.
- 상태: **수정완료(배포 대기)** 2026-09-01

### D-263: 마켓플러스 장부의 판매기간·판매종료가 ESM 실제값과 어긋난다 (2026-09-01)

- 심각도: 표준(운영 지표 오탐) | 위치: 마켓플러스 화면 해석
- 실측(사용자 ESM 판매자센터): 마켓플러스가 `종료일 2026-08-03 · 판매종료` 로 분류한 상품이 ESM 에는 `2099-12-31(365일+)` 로 열려 있고 상태는 **판매중지**였다. 마켓플러스가 `2999-12-31`(무제한)로 표시한 상품은 ESM 에서 판매기간 **설정안함**이었다.
- 결론: 마켓플러스의 `종료일`·`7일 내 종료`·`판매종료` 는 **자체 장부**이며 ESM 실제 판매기간과 일치하지 않는다. [[D-259]] 의 "7일 내 종료 128건"은 **판매 중단 위험 오탐**이었다.
- 영향: 이 지표를 근거로 한 정기 `기간연장` 작업은 불필요하다. 판매기간의 단일 원본은 **ESM** 이며, 필요 시 ESM 상품 목록의 **일괄 선택 → 판매기간 변경**으로 직접 다룬다.
- 미확인: 왜 어긋나는지(등록 시점 값의 잔재인지, 동기화 누락인지). 마켓플러스 `마켓정보 동기화` 로 ESM 값을 끌어오는지 시험하려 했으나 렌더러가 멈춰 판정하지 못했다. 재시도 또는 카페24 문의 필요.
- 선택 작업(실익 낮음): 오늘 연장으로 ESM 에 `2100-06-29` 가 심긴 341건을 `설정안함` 으로 되돌리기.
- 상태: **원인규명·조치불요(지표 해석 변경)** 2026-09-01

### D-264: 주문일 30일 창을 벗어난 주문은 마켓에서 무슨 일이 일어나도 반영되지 않는다 (2026-09-01)

- 심각도: **P1(주문 상태 유실)** | 위치: `OrderSyncScheduler` · 4개 마켓 주문 동기화
- 사용자 관찰: 마켓 페이지에서 송장을 바꾸고 배송완료까지 됐는데 우리 시스템에 반영되지 않는다. 반품·교환 이후 상태 변경도 동기화되는지 불명.
- 재현(운영 DB 실측): 14일 이상 정체된 미종결 주문 3건.

  | 마켓 | 주문번호 | 주문일 | 상태 | 마지막 갱신 |
  |------|---------|--------|------|-----------|
  | GMARKET | 4462952064 | 2026-06-30 | SHIPPED | 2026-08-08 |
  | ELEVEN_STREET | 20260727088019019 | 2026-07-27 | SHIPPED | 2026-08-19 |
  | GMARKET | 4478846950 | 2026-08-13 | SHIPPED | 2026-08-19 |

- 원인: `OrderSyncScheduler` 의 4개 마켓 동기화가 전부 **주문일 기준 30일**(`syncXxxOrders()` → `minusDays(30)`)만 조회한다. 30분마다 돌지만 창은 늘 30일이라, **주문일이 30일을 넘어가는 순간 그 주문은 영구히 조회 대상에서 빠진다.** 배송완료 전이·마켓 송장 변경·클레임 어느 것도 더는 들어오지 않는다.
  - 스마트스토어만 `last-changed-statuses`(변경일 기준)라 상대적으로 안전하다.
  - 상태 자체를 막는 가드는 없다 — `MarketLineItemSyncDispatcher.applyLineItem` 은 terminal 가드 없이 마켓 상태를 그대로 반영한다. 즉 **조회만 되면 종결 이후에도 갱신된다.**
- 수정: `OrderSyncScheduler.reconcileOpenOrders()` 신설 — 매일 03:30 KST, **90일 창**, `createMissing=false`(갱신 전용)로 4개 마켓 재동기화. 한 마켓이 실패해도 나머지는 계속한다.
  - `createMissing=false` 를 고른 이유: [[D-160]] 이 "백필에 상태 판정 권한은 없다"를 8건의 테스트로 못박아 두었다. 갱신 전용이면 취소·클레임 **추정**은 하지 않고, 마켓이 실제로 돌려준 상태·송장만 반영한다.
- 테스트: `OrderReconcileScheduleTest` 3건(4마켓 90일 갱신전용 호출 · 1개 실패해도 나머지 진행 · 창이 30일보다 김). Red 를 컴파일 실패로 확인. 회귀 전체 통과.
- **남는 구멍(미조치)**: 11번가 클레임 감지(`detectClaims`)와 쿠팡 취소 감지(`detectCancellations`)는 여전히 정기 30일 창 안에서만 돈다. 주문 후 30일이 지나 반품·교환이 발생하고 그 주문이 마켓 API 응답에서 사라지면 감지하지 못한다. 쿠팡 반품은 `detectReturns` 가 **반품 접수일 기준** 30일로 조회해 DB 전체와 대조하므로 이 구멍에 걸리지 않는다.
- 송장에 대한 확인: 마켓에서 송장을 바꿔도 우리 `tracking_no` 는 바뀌지 않는다 — [[tracking-truth-and-manual-fix]] 대로 **송장의 진실은 iHerb 메일**이고, 마켓 값은 `sb_shipment.market_tracking_no` 에 따로 적재된다(`OrderShipmentUpsertService`: `applyMarketTracking` 은 항상, `applyTracking` 은 우리 송장이 없을 때만). **의도된 설계이며 결함이 아니다.**
- 상태: **수정완료(배포 대기)** 2026-09-01

### D-265: 주문 상태·송장의 진실 규칙이 없어 네 곳이 서로 모르고 덮어쓴다 (2026-09-01)

- 심각도: **P1(구조)** | 위치: 주문 동기화 전반 · `Shipment` · `OrderService` · `MarketLineItemSyncDispatcher`
- 문제: 상태·송장에 쓰는 주체가 넷(마켓 동기화·iHerb 메일·수동 입력·레거시 백필)인데 우선순위 규칙이 없다. 마켓 vs 우리는 **먼저 채운 쪽이**, 메일 vs 수동은 **나중에 쓴 쪽이** 이긴다. 상태는 `MarketLineItemSyncDispatcher`·`OrderService`·`OrderShipProcessor`·각 어댑터 네 곳에 규칙이 흩어져 있다.
- 확인된 피해: ⓐ GMARKET 4462952064 가 두 달째 "배송중"([[D-264]]) ⓑ 배송완료 후 반품된 건이 영영 반영 안 됨(`isNonTerminal` 이 추적에서 제외) ⓒ 마켓에서 송장을 고쳐도 화면이 안 바뀜(메인이 메일 값이라).
- **설계 확정** (사용자 브레인스토밍, 2026-09-01): `docs/normalize/plans/D-265_주문상태-송장-단일진실-설계.md`
  - 지배 원칙 **"마켓이 단일 진실 원본"** — 상태·마켓송장은 마켓 값을 그대로 비추고, 우리는 명령(호출)만 한 뒤 다시 읽어온다. PII·정산액·메일송장은 적용 범위 밖(기존 가드 유지).
  - 송장은 **경합이 아니라 대조** — 마켓송장(구매자가 보는 값)이 메인·읽기전용, 메일송장(실제 배송)이 보조, 불일치면 빨강. 쿠팡만 자동 재전송(수정 API 보유).
  - 수동 입력은 **`PREPARING` 에서만**. `OrderService:394` 의 "DISPATCHED/SHIPPED 에서만 수정 가능"을 뒤집는다.
  - **terminal 개념 폐기** — 배송완료↔반품이 양방향으로 뒤집히므로 상태가 아니라 **주문일 120일**로 추적 범위를 잡는다.
  - 3층 파이프라인 — 수집(30분/30일 목록) · 확증(**1시간**/120일 목록 + 없는 것만 단건) · 즉시(명령 직후 단건).
  - **D-160 폐기** — 가드는 *추정*(응답에 없으면 취소) 때문에 존재한다. 전부 확증으로 바꾸면 판정 범위를 제한할 이유가 사라진다. [[D-264]] 의 `reconcileOpenOrders` 도 확증층이 대체한다.
  - 4단계 분할: ①확증층 도입 ②terminal 폐기+120일 ③명령 경로 전환 ④송장 대조 UI.
- 착수 전 실측: **완료** (2026-09-01, 운영 자격증명 실호출). **4개 마켓 모두 단건 확증 조회 가능 — 설계에 예외가 필요 없다.**
  - 쿠팡 `GET /vendors/{vendorId}/{orderId}/ordersheets`: 45일 전 주문도 `200`, 반품건은 `400` **"해당 주문이 취소 또는 반품 되었습니다."**, 없는 번호는 `400` "유효하지 않은 주문번호 입니다." — **두 문구가 달라 추정 없이 확증된다.** 단 취소/반품 구분은 안 되므로 `detectReturns` 는 유지. 429 발생 → 호출 간격 필요.
  - 카페24 `GET /api/v2/admin/orders/{order_id}`(응답 키 `order` **단수**): 63일 전도 조회, `canceled='T'`·`return_confirmed_date` 로 반품 확정까지 읽힌다. 없는 번호는 `order: null`.
  - 11번가 `GET /rest/claimservice/orderlistall/{ordNo}`: 36일 전도 조회, `ordPrdStatNm='취소완료'`. 없는 번호는 빈 응답.
  - 스마트스토어: `last-changed-statuses`(변경일 기준)라 단건 확증이 애초에 불필요.
- 실측 중 확인된 결함: **GMARKET `4462952064`(2026-06-30)가 카페24에서 이미 `배송완료(T)` 인데 우리는 `SHIPPED`** — [[D-264]] 의 90일 패스가 고칠 대상이다. 반면 `4478846950`·11번가 `20260727088019019` 은 마켓도 배송중이라 우리 값이 맞았다. 정체 3건 중 **진짜 누락은 1건**.
**1단계 구현 완료** (2026-09-01, 브랜치 `feat/d265-order-probe`, 커밋 `3d1acd08..37b55f3d`)

- 서브에이전트 방식으로 7개 태스크 실행, **전부 리뷰 통과**(Critical 0 · 최종 리뷰 Important 1건은 병합 전 수정). **전체 회귀 1,702건 통과.**
- 만든 것: `order/probe/` 패키지 — `MarketOrderProbe`(계약, `probe(Order)` 로 받아 마켓별 조회 키를 프로브가 정한다) · `MarketOrderProbeRouter`(한 프로브가 여러 마켓 담당 가능) · 쿠팡·카페24(G마켓+옥션)·11번가 프로브 3종 · `OrderReconciliationService`(확증층).
- 스케줄러: `reconcileOrders()` 매시 40분, **120일 창**, 갱신 전용 목록 조회 + 목록에 없는 주문만 단건 확증. [[D-264]] 의 `reconcileOpenOrders`(일 1회·90일)를 대체했다.
- **[[D-160]] 폐기 완료** — `CoupangOrderAdapter.detectCancellations`(응답에 없으면 취소로 추정)와 `isNonTerminal`, 그리고 판정범위 가드 테스트를 삭제했다. `detectReturns` 는 남기고 **`createMissing`·`fetchComplete` 와 무관하게 항상 실행**한다(반품 API 직접 조회라 추정이 아니고, 취소와 반품을 가르는 유일한 수단이다). 11번가 `detectClaims` 는 단건 조회로 확증하므로 그대로 둔다.
- 안전 속성: 브랜치 전체에서 프로덕션 쓰기 지점은 `OrderReconciliationService.apply` 하나뿐이고, `NOT_FOUND`·`UNKNOWN`·`TERMINATED`(상태 미지정, 쿠팡)은 **아무 것도 쓰지 않는다**. 429 를 포함한 모든 오류가 `UNKNOWN` 으로 떨어져 상태를 잘못 쓰지 않는다.
- 쿠팡 호출 제한 대응: `sbshop.order.probe-delay-ms`(기본 300ms)로 **프로브 단위** 스로틀. 실측 안전 간격(3초)보다 촘촘하므로 배포 후 429 발생 여부를 관찰한다.

**배포 후 확인할 것**

1. 매시 40분 로그: `docker logs projects-sbshop-api-1 | grep 확증`
2. GMARKET `4462952064` 가 `DELIVERED` 로 바뀌는지 — 카페24 실측값이 `shipping_status='T'` 였다. 1단계의 실효 검증.
3. 쿠팡 429 발생 여부 → 나면 `probe-delay-ms` 를 올린다.

**2단계로 넘긴 것**: `seenMarketOrderNos` 를 sync 서비스가 실제로 돌려주게 하기(지금은 `Set.of()` 라 창 안 전 주문을 프로브한다) · 11번가 `isNonTerminal` 잔여 사용처 제거 · miss 카운터와 '확인 필요' 목록 · 추적 대상을 주문일 120일로 명시 전환.

- 상태: **1단계 완료(배포 대기)·2~4단계 미착수** 2026-09-01

### D-266: 스마트스토어 옛 상품은 필수 Enum 부재로 모든 수정이 400 난다 (2026-09-02)

- 심각도: 표준(가격·재고 갱신 유실) | 위치: `SmartstoreProductPayloadBuilder` · 스마트스토어 상품수정 경로
- 발견 경로: [[D-262]] 라이브 검증 중 부수 관측. 카페24 전송이 스킵되자 그동안 가려져 있던 스마트스토어 실패가 드러났다.
- 실측 원문 (2026-09-02 00:23, `productId=21` / `230602FM001`):

  ```
  400 Bad Request: {"code":"BAD_REQUEST","message":"입력한 데이터가 유효하지 않습니다.",
   "invalidInputs":[{"name":"originProduct.statusType","type":"NotValidEnum",
   "message":"Enum값을 입력하지 않았거나 허용되지 않은 Enum 값입니다."}]}
  ```

- **규모: 스마트스토어 등록 중 `VALIDATION_FAILED` 66건** (그 외 `TRANSIENT_ERROR` 19건). 이 66건은 가격·재고가 마켓에 반영되지 않고 있다.
- [[barcode-market-push]] 에 기록된 *"네이버 옛상품은 `consumptionDateText` 부재로 모든 수정 400"* 과 **같은 뿌리**다 — 옛 상품이 현재 API 가 필수로 요구하는 필드를 갖고 있지 않고, 우리 payload 는 신선 GET 값을 그대로 실어 보내므로 빈 값이 그대로 나간다. `statusType` 은 그 목록에 새로 추가된 필드다.
- 착수 시 확인할 것: ① 66건의 실패 사유가 전부 `statusType` 인지 다른 필드도 섞였는지([[D-267]] 선행 필요) ② `originProduct.statusType` 의 허용값과 기본값 ③ [[barcode-market-push]] 의 원칙(신선 GET → 해당 필드만 → PUT)에 보정 로직을 어디에 끼울지
- 상태: **발견** 2026-09-02

### D-267: 가격·재고 동기화는 실패 사유 원문을 저장하지 않는다 (2026-09-02)

- 심각도: 표준(진단 불가) | 위치: `ProductMarketSyncService:121`
- [[D-258]] 이 `MarketRegistration.recordSyncError(SyncErrorType, String)` 오버로드를 만들어 **원문 사유를 500자까지 저장**하도록 했으나, **가격·재고 동기화 경로는 여전히 1-arg 버전을 부른다**:

  ```java
  reg.recordSyncError(MarketFailureClassifier.classifyError(e));   // 분류만, 원문 유실
  ```

- 결과: 스마트스토어 `VALIDATION_FAILED` 66건 전부 `last_sync_error_message` 가 비어 있다. DB 만 보면 **어느 필드가 문제인지 알 수 없고**, 로그가 만료되면 영영 모른다. [[D-266]] 조사가 이것 때문에 막힌다.
- 부수 관측: 같은 오류가 반복되면 `last_sync_error` 값이 바뀌지 않아 JPA 더티체킹이 UPDATE 를 내지 않고, 그래서 `updated_at` 도 갱신되지 않는다 — **실패가 언제까지 이어졌는지도 알 수 없다**(`productId=21` 은 2026-09-02 에 또 실패했는데 `updated_at` 은 2026-08-30 그대로).
- 수정 방향: 2-arg 오버로드로 교체하고, 재실패 시에도 시각이 갱신되도록 한다. [[D-266]] 의 선행 작업이다.
- 상태: **발견** 2026-09-02

### D-268: 카페24 확증 프로브가 교환을 못 읽어 교환 주문을 배송중으로 되돌렸다 (2026-09-02)

- 심각도: **P1(상태 오염)** | 위치: `Cafe24OrderProbe`
- 발견 경로: [[D-265]] 1단계 배포 후 **첫 확증 실행(2026-09-02 09:40 KST) 라이브 관측**. 로그에 `[GMARKET] 확증 반영: orderNo=4477134670, EXCHANGED → SHIPPED`.
- 원인: 카페24는 **취소·반품을 주문 레벨**(`canceled`·`return_confirmed_date`)에 두지만 **교환은 아이템 레벨에만** 둔다. 실측 응답:

  | 위치 | 값 |
  |------|-----|
  | 주문 레벨 | `canceled='F'` · `shipping_status='M'` → 프로브가 `SHIPPED` 로 판정 |
  | `items[0]` | `order_status='E00'` · `status_text='교환신청'` · `exchange_request_date` 존재 |

  정규 동기화(`Cafe24LineItemMapper.mapStatus`)는 `items[].order_status` 를 읽어 `E*→EXCHANGED` 로 매핑하는데, 프로브는 주문 레벨만 봤다. **프로브가 정규 경로보다 덜 아는 상태로 덮어썼다.**
- 설계 위반이기도 하다 — "마켓이 진실"은 마켓이 *말해준* 것을 따르라는 뜻인데, 여기서는 *못 읽은* 것을 진실로 오인했다.
- 수정: 프로브가 `items[].order_status` 를 **`Cafe24LineItemMapper.mapStatus` 로 매핑**한다(중복 구현하면 또 어긋나므로 클래스·메서드를 public 으로 열어 단일 소스로 쓴다). 판정 순서: ①아이템 종결(C*/R*/E*) → ②주문 레벨 `canceled` → ③아이템 정상 단계 → ④주문 레벨 `shipping_status` → ⑤`UNKNOWN`. 어느 단계에서도 **이전보다 덜 아는 답을 내지 않는다.**
- 테스트: `Cafe24OrderProbeTest` 에 6건 추가(교환·반품·취소·정상단계·종결우선·미매핑시 폴백). Red 를 5건 실패로 확인. 전체 회귀 통과.
- 피해 범위: **1건**(`4477134670`). 반품 2건은 주문 레벨 플래그로 정상 판정돼 무사했다.
- 상태: **수정완료** 2026-09-02

### D-269: 배치 결과 화면이 실패를 숨긴다 — 프론트 UI/UX 전면 개편과 그에 맞물린 리팩토링 (2026-09-02, 사용자 지시)

- 심각도: **P1(운영 판단 오류 유발)** | 위치: 배치 실행 화면 · 진행현황(활동로그) 프론트 · `sb_process_status` 기록 방식
- **착수 시점: [[D-265]] 4단계까지 끝난 뒤.** 그 전에는 손대지 않는다.
- 발단(2026-09-02 [[D-262]] 라이브 검증): 배치 3건을 돌렸더니 **화면은 "완료 3"** 인데 실제로는 스마트스토어 1건이 400 으로 실패했다. 실패 사실이 화면 어디에서도 보이지 않았다. `sb_process_status.status` 는 세 건 모두 `ACTIVE` 였고, 메시지 문자열 안에 `실패1 ([SMART_STORE])` 이 묻혀 있었을 뿐이다.
- **핵심 문제: 부분 실패가 성공으로 보인다.** 한 상품이 여러 마켓에 걸쳐 있으니 결과는 마켓별로 갈리는데(`성공1/스킵1/실패1`), 화면은 상품 단위 한 줄로만 보여준다. 어느 마켓이 왜 실패했는지 알려면 서버 로그를 봐야 하고, 로그는 만료된다.
- 얽혀 있는 것들:
  - [[D-267]] 실패 사유 원문이 DB 에 저장되지 않는다 — 화면에 사유를 띄우려면 이것이 선행이다.
  - [[D-266]] 스마트스토어 옛 상품 400 (66건) — 이렇게 숨겨져 있어서 오래 방치됐다.
  - `sb_process_status` 가 상태를 `ACTIVE` 하나로만 쓰고 성공/실패를 구분하지 않는다.
- 방향(사용자 지시): **프론트 페이지의 획기적인 UI/UX 변경**과 **그에 맞물리는 리팩토링**. 배치 결과와 활동로그에서 마켓별 성공·스킵·실패와 그 사유를 화면에서 바로 볼 수 있어야 한다. 데이터 모델(상태 구분·사유 저장)과 화면을 함께 다시 설계한다.
- 착수 시 확인할 것: ① `sb_process_status` 스키마와 소비 지점 전수 ② 배치 종류별(크롤 업데이트·수동 업데이트·바코드 등) 결과 형태가 제각각인지 ③ 활동로그(`sb_action_log`)와의 역할 분담 ④ 마켓별 결과를 담을 구조
- 상태: **발견(4단계 이후 착수)** 2026-09-02

### D-270: 클레임이 배송 단계를 덮어쓴다 — 교환은 종착역이 아니라 경유지다 (2026-09-02)

- 심각도: **P1(운영 오판)** | 위치: 4마켓 상태 매퍼 전부 · `sb_order_line_item.shipping_status`
- 증상(라이브 4477134670, G마켓): ESM 화면은 **배송중**인데 우리 화면은 **교환됨**. 이력을 보면 08.22 교환요청 → 08.30 재발송(배송중) + 교환수거완료로 **교환은 이미 끝났다**.
- 원인: `shipping_status` 한 필드가 **배송 단계와 클레임을 겸한다.** 클레임이 뜨면 배송 단계가 지워지고, 재발송돼도 되돌아오지 않는다. `Cafe24LineItemMapper.mapStatus`는 `E`로 시작하는 코드를 전부 EXCHANGED로 뭉개, 교환신청과 교환완료를 구분하지 못한다.
- 실측한 두 값이 갈린다: 아이템 `order_status`=`E*`(교환 이력을 붙들고 있음) vs 주문 `shipping_status`=배송중(**마켓 화면과 일치**).
- **결정(사용자, 2026-09-02): 상태는 마켓 화면과 같은 배송 단계로 두고, 클레임은 별도 배지로 뺀다.** 송장에서 이미 택한 패턴과 같다 — 상태는 마켓 값, 부가 정보는 배지.
- **결정 보강(사용자, 2026-09-02): 교환만이 아니라 반품·취소도 함께 세분화한다.** "반품요청"과 "반품완료"가 한 값(RETURNED)으로 뭉쳐 있으면 교환과 같은 문제가 그대로 남는다 — 수거 전인지 환불까지 끝났는지 화면에서 갈리지 않는다. 네 마켓이 이미 반품·취소도 단계별로 주므로(카페24 `R20`~`R43`·`C*`, 11번가 `105`/`106`/`107`/`108`·취소 목록 API, 쿠팡 `receiptStatus` 5단계+`receiptType`, 스토어 `RETURN_REQUEST`/`COLLECTING`/`COLLECT_DONE`/`RETURN_DONE`/`RETURN_REJECT`), **클레임 축 전체를 (타입 × 단계)로 설계한다.**
- 마켓별 분리 가능성 조사:
  | 마켓 | 배송 단계 | 클레임 | 분리 |
  |---|---|---|---|
  | 카페24(G마켓·옥션) | order-level `shipping_status` · **`order_status_before_cs`(CS 전 상태)** | **`claim_type`/`claim_status`** + `order_status_additional_info.status_code` | 가능 |
  | 스마트스토어 | `productOrderStatus` | **`claimType`/`claimStatus`** (별도 필드) | 가능·미구현 |
  | 쿠팡 | `status` | `exchangeRequests`(교환) + `returnRequests`(반품·취소) 별도 API | 가능 |
  | 11번가 | `ordPrdStat` (상태별 목록 API) | **`clmStat` 별도 필드 + 클레임 목록 API 9종** | **가능 — 4마켓 중 가장 깔끔** |
- 범위: 클레임 필드 신설(스키마) + 매퍼 4개 + 프로브 + 프론트 배지. [[D-265]] 2~4단계와 같은 급이라 그 안에서 다룬다.
- 주의: `C*`/`R*`를 함께 들어내면 [[D-098]](취소·반품 정산0 정규화)가 의존하는 상태가 사라진다. 교환만 따로 떼는 것으로는 부족하고, 클레임 필드가 함께 생겨야 한다.
- 11번가 실측(2026-09-02, 사용자가 받아준 OPEN API 문서 22종으로 확정): 라이브 클레임은 0건(DELIVERED 18 / SHIPPED 4 / CANCELED 1)이라 관측은 못 했지만, **문서로 답이 나왔다 — 11번가는 배송 단계와 클레임을 완전히 분리해 준다.**
  - **`clmStat`(클레임 상태)가 `ordPrdStat`(배송 단계)과 별개 필드로 존재한다:**
    `103` 재결제대기중 · `104` 반품보류 · `105` 반품신청 · `106` 반품완료 · `107` 반품거부 · `108` 반품철회 · `109` 반품완료보류 · `201` 교환신청 · `212` 교환승인 · `214` 교환보류 · `221` 교환발송완료 · `232` 교환거부 · `233` 교환철회 · `301` 재배송접수
  - 교환이 6단계로 세분된다. **`221` 교환발송완료 / `301` 재배송접수**가 4477134670 같은 "교환 끝나고 재발송" 상태를 정확히 짚고, `232` 교환거부 / `233` 교환철회는 클레임이 무산돼 배송 단계로 돌아가야 하는 경우를 구분해 준다.
  - **[[D-099]]의 "클레임 목록 조회 REST 부재 라이브확정"은 오판이었다.** 목록조회 API가 9종 존재한다 — 당시 시도한 경로가 틀렸다:
    | 용도 | 엔드포인트 (`http://api.11st.co.kr` + path) |
    |---|---|
    | 교환 요청/완료/철회 | `/rest/claimservice/exchangeorders|exchangedorders|retractexcorders/{start}/{end}` |
    | 반품 요청/완료/철회 | `/rest/claimservice/returnorders|returnedorders|retractretorders/{start}/{end}` |
    | 취소 요청/완료/철회 | `/rest/claimservice/cancelorders|canceledorders|withdrawcanceledorders/{start}/{end}` |
    | 구매확정후직권취소 | `/rest/claimservice/officecancellist/{start}/{end}` |
    | 교환 승인/거부 | `/rest/claimservice/exchangereqconf|exchangereqreject/...` |
    | 반품 승인/거부 | `/rest/claimservice/returnreqconf|returnreqreject/...` |
    조회 기간은 최대 30일. 시각 포맷은 `YYYYMMDDhhmm`.
  - 함정: PDF 텍스트 추출에서 합자(ligature)가 깨진다 — `officecancellist`가 `oﬃcecancellist`로, `affiliateBndlDlvSeq`가 `aﬄiateBndlDlvSeq`로 나온다. 코드에 옮길 때 주의.
  - 현행 매퍼는 `ordPrdStatNm` 문자열에 "교환"이 들어가면 전부 EXCHANGED로 뭉갠다(카페24와 같은 결함). `ordPrdStat` 코드는 받아만 두고 쓰지 않는다.
- 카페24·스마트스토어 문서 확정(2026-09-02, 다운로드 폴더의 기존 문서로 확인 — 추가 수집 불필요):
  - **카페24**: `order_status`가 이미 30개 넘게 세분돼 있다. 교환만 해도 `E00` 교환신청 · `E10` 교환접수 · `E11` 교환접수거부 · `E12` 교환보류 · `E13` 교환접수-수거완료(자동) · `E20` 교환준비 · `E30`~`E36` 교환처리중(수거전/수거완료/입금전/입금완료/환불전/환불완료/환불보류) · `E40` 교환완료. 반품은 `R20`·`R30`/`R31`·`R34`/`R36`·`R40`~`R43`. **접두어 매칭이 이 30여 개를 3개로 뭉갠 것이 D-270의 직접 원인이다.**
  - 카페24는 클레임 축을 따로도 준다: `GET /api/v2/admin/orders/{order_id}/items` 응답의 **`claim_type`(취소/교환/반품 타입) · `claim_status`(요청 상태) · `order_status_before_cs`(CS 전 주문상태)**, 그리고 `order_status_additional_info`의 `claim_quantity`(요청 수량) · `status_code`(`N1` 정상 / `N2` 교환상품 / `C1` 입금전취소 / `C2` 배송전취소 / `C3` 반품 / `E1` 교환) · `status_text` · `open_market_status`(마켓연동 상태값). **`order_status_before_cs`가 있으면 클레임 중에도 원래 배송 단계를 복원할 수 있다.** (`claim_status` 자체의 코드값은 미확인 — 구현 시 확인할 것. 문서 검색에 걸리는 동명 코드표는 카페24가 *네이버페이 연동 주문*에 쓰는 별개 값이니 혼동 금지.)
  - 우리 매퍼는 `N01`(교환접수-교환상품) · `N03`(교환접수-카드결제대기)을 매핑하지 않아 UNKNOWN으로 떨어뜨린다 — **교환으로 새로 나가는 상품이 `N` 계열을 받는데 그것을 못 읽는다.** 별개 결함.
  - 참고: `cancel_N20`/`exchange_N30`/`return_N40` 류의 복합 코드는 주문 *설정*(`claim_request_button_exposure`, 구매자 신청 버튼 노출 범위)이지 응답값이 아니다. 다만 카페24가 "교환신청 + 배송중"을 한 상태로 인정한다는 것 자체가 두 축이 독립임을 보여준다.
  - **스마트스토어**: `productOrder.claimType`/`claimStatus`가 `productOrderStatus`와 별도 필드로 존재한다.
    `claimType` = `CANCEL` · `RETURN` · `EXCHANGE` · `ADMIN_CANCEL` · `PURCHASE_DECISION_HOLDBACK`
    `claimStatus` = `CANCEL_REQUEST`/`CANCELING`/`CANCEL_DONE`/`CANCEL_REJECT` · `RETURN_REQUEST`/`RETURN_DONE`/`RETURN_REJECT` · `EXCHANGE_REQUEST`/**`EXCHANGE_REDELIVERING`**/`EXCHANGE_DONE`/`EXCHANGE_REJECT` · `COLLECTING`/`COLLECT_DONE` · `PURCHASE_DECISION_HOLDBACK`/`PURCHASE_DECISION_REQUEST`
    `EXCHANGE_REDELIVERING`(교환 재배송 중)이 11번가 `301` 재배송접수와 같은 개념 — 4477134670 상황을 정확히 표현한다.
  - **남은 것: 쿠팡** 주문 조회 응답의 상태 코드표와 교환 API 유무(반품은 `returnRequests`로 [[D-097]]에서 확인됨). 문서 미확보.
- 쿠팡 문서 확정(2026-09-02): **교환 전용 API가 따로 있다** — `GET /v2/providers/openapi/apis/api/v4/vendors/{vendorId}/exchangeRequests`. [[D-097]]에서 반품만 쓰고 있었고 교환은 손대지 않았다.
  - `exchangeStatus`(교환 상태) = `RECEIPT` 접수 · `PROGRESS` 진행 · `SUCCESS` 완료 · `REJECT` 거부 · `CANCEL` 취소
  - `collectStatus`(회수 상태) = `BeforeDirection` · `CompleteDirection` · `Delivering` · `CompleteCollect` · `DirectionFail` · `Fail` · `Withdraw` · `NoCollect` · `NoneData`
  - 그 밖에 `referType`(접수경로 VENDOR/CS_CENTER/WEB_PC/WEB_MOBILE) · `faultType`(귀책) · `collectCompleteDate`(입고완료일시). 교환 재발송 송장은 별도 API(교환상품 송장 업로드 처리)로 다룬다.
  - 반품·취소는 `GET /v2/providers/openapi/apis/api/v6/vendors/{vendorId}/returnRequests`. **`receiptType`이 `RETURN`/`CANCEL`을 구분**하고, `receiptStatus` = `RELEASE_STOP_UNCHECKED`(출고중지요청) · `RETURNS_UNCHECKED`(반품접수) · `VENDOR_WAREHOUSE_CONFIRM`(입고완료) · `REQUEST_COUPANG_CHECK`(쿠팡확인요청) · `RETURNS_COMPLETED`(반품완료)

**조사 결론(2026-09-02): 4마켓 전부 클레임을 배송 단계와 분리해 주고, 전부 교환을 세분해 준다.** 뭉갠 것은 우리 매퍼뿐이다.

| 마켓 | 클레임 전용 축 | 교환 세분 |
|---|---|---|
| 카페24 | `claim_type`/`claim_status` · `order_status_before_cs` · `status_code` | `E00`~`E40` 14단계 |
| 11번가 | `clmStat` + 클레임 목록 API 9종 | `201`/`212`/`214`/`221`/`232`/`233` + `301` |
| 쿠팡 | `exchangeRequests` · `returnRequests` 별도 API | `RECEIPT`/`PROGRESS`/`SUCCESS`/`REJECT`/`CANCEL` |
| 스마트스토어 | `claimType`/`claimStatus` | `EXCHANGE_REQUEST`/`REDELIVERING`/`DONE`/`REJECT` |

네 마켓 모두 "교환 진행 중"과 "교환 끝나고 재발송"을 구분할 수단을 준다(카페24 `E40`+`order_status_before_cs`, 11번가 `221`/`301`, 스토어 `EXCHANGE_REDELIVERING`, 쿠팡 `SUCCESS`+`collectStatus`). 설계는 짐작이 아니라 이 코드들을 옮기는 일이 된다.
- 상태: **발견(설계 필요)** 2026-09-02

### D-271: 확증 주기가 ESM 정기 동기화와 같은 분에 시작한다 (2026-09-02)

- 심각도: P2 | 위치: `OrderSyncScheduler`
- `reconcileOrders`가 `0 40 * * * ?`, `syncEsmplusOrders`가 `0 10/30 * * * ?` — **둘 다 :40에 시작해** 같은 카페24 API를 두 번 호출하고 같은 주문 행을 두 스레드가 쓴다. 어느 쪽이 마지막에 쓰는지 보장이 없다.
- 실제로 2026-09-02 00:42 확증이 쓴 값을 00:43 정기 동기화가 54초 뒤 덮었다.
- 정기 동기화가 5분 간격으로 꽉 차 있다(:00/:30 통합, :05/:35 쿠팡, :10/:40 ESM, :15/:45 스토어, :20/:50 11번가). 빈 슬롯은 `:25`와 `:55`.
- 수정: 확증을 `0 25 * * * ?`로 옮긴다. 확증이 2분 16초 걸리므로 :27에 끝나 :30과 3분 여유가 남는다.
- 회귀 고정: `OrderScheduleNoOverlapTest` — `@Scheduled` cron을 리플렉션으로 읽어 확증과 정기 동기화의 분 집합이 겹치지 않는지 검사한다.
- 상태: **수정완료(푸시 대기)** 2026-09-02

