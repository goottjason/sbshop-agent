# 백엔드 리팩토링 사전 서베이

> **⚠️ 리더 정정(2026-08-21):** 아래 "고신뢰 데드코드 3건" 중 `IngredientAliasSeed`는 **오판 — 삭제 금지(실사용 중)**. `BannedIngredientSyncService.java`에 raw NUL(0x00)이 있어 grep이 파일째 건너뛰며 참조가 누락됐던 것(현재 NUL은 `"\0"` 이스케이프로 교정됨). 유효한 고신뢰 삭제는 `BusinessDayCalculator`·`UnipassUpdateRequest` 2건뿐. 미참조 판정은 반드시 `grep -ra`/`rg --text`로 할 것.

조사 범위: `backend/{core,infrastructure,api,worker}` — main 410파일(생성 Q클래스 제외 383), test 240파일. 조사만 수행, 코드 미수정.

방법: 각 main 클래스의 Simple Name을 `backend` 전체 `*.java`(main+test, `build/`·`bin/`·generated 제외)에서 whole-word 카운트하여 자기 선언만 있고 외부 참조가 0인 후보를 추출한 뒤, 후보 56개 전부를 열어 Spring 진입점 여부·`implements`/`extends` 여부를 직접 확인했다. `docs/normalize/codebase-map.md`(2026-07-07)를 참고했으나 그 사이 ESM+ 전용 인프라(`infrastructure/client/esmplus/*`)가 완전히 제거되고 G마켓/옥션이 Cafe24 경유로 통합되는 등 구조가 크게 바뀌어 있어, 최신 코드를 기준으로 다시 조사했다.

---

## ① 데드코드 전역 인벤토리

### 고신뢰 삭제가능 (Spring 진입점 아님, 외부 참조 0, 확인 완료)

| 클래스 | 경로 | 근거 |
|---|---|---|
| `BusinessDayCalculator` | `core/src/main/java/com/sbshop/agent/core/domain/order/util/BusinessDayCalculator.java:12` | 클래스 선언 외 전체 코드베이스에서 0회 참조. `addBusinessDays`(L96)·`countBusinessDays`(L111) 정적 메서드 모두 어디서도 호출되지 않는다. 어노테이션 없음, 인터페이스 미구현. ⚠️ 참고로 내부 공휴일 테이블이 2024~2026년까지만 하드코딩되어 있어(FIXED_HOLIDAYS), 애초에 쓰였더라도 곧 낡을 코드였다 — 삭제가 아니라 활용을 검토한다면 이 부분도 손봐야 함을 남겨둔다. |
| `UnipassUpdateRequest` | `api/src/main/java/com/sbshop/agent/api/dto/UnipassUpdateRequest.java:6` | `@Data` DTO, 필드 `isUnipassDone` 하나. 어떤 컨트롤러의 `@RequestBody`/파라미터 타입으로도 쓰이지 않음(전 코드베이스에서 클래스 선언 1회만 매치). 유니패스 신고여부 수정은 `OrderService`가 다른 요청 DTO로 처리 중인 것으로 보임(`core/application/order/service/OrderService.java` 인근 로직, F-ORD-25/26 참조) — 이 DTO는 대체되고 남은 잔재로 판단. |
| `IngredientAliasSeed` | `core/src/main/java/com/sbshop/agent/core/application/sourcing/customs/IngredientAliasSeed.java:18` | `public final class`, private 생성자(L35), 공개 정적 메서드 `aliasesFor(String)`(L44) 하나. 실제 호출부는 전무 — 유일한 타 파일 언급은 `infrastructure/.../MfdsBannedIngredientClient.java:35`의 Javadoc 주석 `{@code IngredientAliasSeed}가 담당한다` 뿐이며 이는 주석이라 실행 경로가 아니다(주석 자체가 이번 캠페인에서 전량 삭제 대상이므로 이 참조도 함께 사라짐). |

### 데드 의심 (Spring 진입점이라 삭제 금지 — 원장/백로그용 기록만)

| 클래스 | 경로 | 비고 |
|---|---|---|
| `SourcingAgentFactory` + `SourcingAgent` | `core/src/main/java/com/sbshop/agent/core/domain/sourcing/component/SourcingAgentFactory.java:10`, `SourcingAgent.java:5` | **구조적으로 죽은 서브시스템 전체.** `SourcingAgentFactory`는 `@Component`로 등록되어 `List<SourcingAgent> agents`를 주입받아 `getAgentByUrl(url)`을 제공하지만, ①`SourcingAgentFactory` 자체를 호출/주입하는 코드가 전무하고 ②`SourcingAgent` 인터페이스를 `implements`하는 클래스가 코드베이스 전체에 **단 하나도 없다**(즉 런타임에도 빈 리스트가 주입됨). 팩토리와 인터페이스가 쌍으로 고아 상태 — Spring 빈이라 자동삭제 금지 대상이지만, 기능적으로 완전히 죽은 소싱 파이프라인 잔재로 보인다. defect-scout 원장 등재 권고. |
| `@RestController`/`@Component`/`@Configuration`/`@Repository`/`@Scheduled` 중 전역 참조 0~2회(=테스트 코드 전무) | 아래 "무테스트 진입점" 표 | 진입점이라 라우팅/스케줄 자체는 살아있으나, 테스트가 전혀 없어 회귀 안전망 없이 리팩토링 대상이 되는 파일들. 삭제 대상 아님 — 리팩토링 시 주의만 요함. |

**무테스트 진입점** (참조 카운트 ≤2, 모두 Spring 스테레오타입 확인됨 — 정상 동작 중이나 텍스트/테스트 참조 0):
`CommonCodeController`(api), `LegacyShipmentBackfillController`(worker), `MarketLinkBackfillController`(worker), `SmartstoreDiscountController`(worker), `DashboardController`(api), `MarketRegistrationController`(api), `SourcingDiscoveryController`(api), `MarketTokenScheduler`(worker), `ProductSyncScheduler`(worker), `SourcingScheduler`(worker), `BatchScheduler`(worker), `JpaAuditingConfig`(core), `QueryDslConfig`(infrastructure), `SecurityConfig`(api), `BatchGuardReleaseListener`(core), `OrphanedBatchRecoveryRunner`(api), `ActionLogBatchListener`(core). 리포지토리 구현체(`DashboardRepositoryImpl`, `ProductReaderImpl`, `ProductWriterImpl`, `OrderRepositoryImpl` 등)와 포트 구현체(`CoupangOrderApiClient`, `ElevenstOrderApiClient`, `SmartStoreOrderApiClient`, `GsiExpressScraperAdapter` 등)도 낮은 참조 카운트로 잡혔지만 전부 포트 인터페이스로 주입되는 정상 DI 패턴 — 데드 아님, 오탐.

### 확인 후 데드 아님으로 판정 (오탐 배제 기록)

grep 1차 후보 56개 중 위 3건(고신뢰)·서브시스템 1쌍(데드 의심) 외 전부는 직접 파일을 읽어 Spring 스테레오타입 또는 `implements`/`extends`를 확인해 정상 판정했다. 특히 `CoupangAcceptOrdersRequest`(`infrastructure/.../coupang/CoupangOrderApiClient.java:352`에서 `new`), `CoupangCancelOrderResponse`(같은 파일 L401-402에서 역직렬화 대상)는 카운트가 낮아 후보에 걸렸지만 실사용 중.

### 주석처리된 코드 블록

패턴 `^\s*//.*[;{}()]`로 1차 스캔했을 때 50개 이상 파일이 걸렸으나, 표본 확인 결과(`EmailFetcherService`, `OrderService` 등) 대부분은 **괄호를 포함한 한국어 서술형 "왜" 주석**(동시성 가드 근거, 상태 가드 근거, F-ORD-*/D-* 이력 참조)이었고 진짜 비활성 코드가 아니었다. 실제 Java 키워드가 주석 처리된 패턴(`^\s*//\s*(if\(|for\(|return|public |new [A-Z]|@\w+|...)`)으로 재스캔한 결과, 진짜 "주석처리된 코드"는 코드베이스 전체에서 **1건**뿐이다.

| 위치 | 내용 |
|---|---|
| `worker/src/main/java/com/sbshop/agent/worker/scheduler/BatchScheduler.java:27` | `// @Scheduled(cron = "0 0 5 * * ?", zone = "Asia/Seoul")` — 정기 자동 재가격 스케줄이 주석으로 비활성화됨. 바로 위 L22-26에 **삭제 전 반드시 살려야 할 "왜" 주석**이 있다: D-093(2026-07-21, 사용자 결정) — 하드코딩 파라미터(margin 15/coupon 20/minMargin 5000)로 매일 05:00에 재가격하는 배치가 사용자가 수동 배치로 잡아둔 판매가 정책을 몇 시간 만에 덮어써서 의도적으로 껐다는 내용, 재활성화 조건까지 명시. **이 주석은 doctrine 5번 규칙("사라지면 위험한 왜 주석")에 해당 — Phase 2에서 `salvage-backend.md`에 그대로 옮겨 적고 나서 지울 것.** 주석 처리된 `@Scheduled` 라인 자체는 doctrine 1번 규칙에 따라 삭제 대상(주석처리된 코드=데드코드).

**결론**: "고밀도 주석 파일" 목록(아래 ④)을 "삭제할 죽은 코드가 많은 파일"로 오독하지 말 것 — 그 목록은 절대다수가 보존 검토 대상인 설명 주석이다. Phase 2 주석 제거 담당은 파일별로 실제 내용을 확인하며 진행해야 하고, `salvage-<scope>.md` 후보가 특히 많을 것으로 예상되는 파일은 §④에 표시했다.

### 미사용 리소스

`api/src/main/resources/application.yml` 외 main 리소스 파일 없음 (Flyway 완전 제거 확인 — `docs/normalize/codebase-map.md`의 Z-4/후속 `after-migrate.sql` 항목은 현재 파일 자체가 존재하지 않아 완전히 해소됨, 별도 조치 불필요).

---

## ② FQN 사용 현황

탐지 패턴: `\b(com\.sbshop|java\.(util|time|io|math|nio|net|text|function)|org\.springframework|org\.hibernate|jakarta\.)[a-zA-Z0-9_.]*\.[A-Z][a-zA-Z0-9_]*\b`, import문·package 선언 제외, `build/`·`bin/`(생성 Q클래스·spotless 캐시) 제외. 총 히트 392건 — main 233건(55파일), test 159건(64파일).

### main — 파일별 히트 수 (내림차순, 전체)

| 건수 | 파일 |
|---|---|
| 18 | `core/src/main/java/com/sbshop/agent/core/application/order/service/ElevenstOrderSyncService.java` |
| 16 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/smartstore/adapter/SmartstoreMarketClient.java` |
| 15 | `core/src/main/java/com/sbshop/agent/core/application/order/service/CoupangOrderSyncService.java` |
| 11 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/coupang/adapter/CoupangMarketClient.java` |
| 11 | `api/src/main/java/com/sbshop/agent/api/controller/BatchController.java` |
| 9 | `core/src/main/java/com/sbshop/agent/core/application/order/service/SmartStoreOrderSyncService.java` |
| 7 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/coupang/CoupangOrderApiClient.java` |
| 7 | `core/src/main/java/com/sbshop/agent/core/domain/order/repository/OrderLineItemRepository.java` |
| 7 | `core/src/main/java/com/sbshop/agent/core/application/product/BatchPriceStockService.java` |
| 6 | `core/src/main/java/com/sbshop/agent/core/application/order/adapter/ElevenstOrderAdapter.java` |
| 5 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/client/Cafe24OrderApiClient.java` |
| 5 | `core/src/main/java/com/sbshop/agent/core/domain/market/client/MarketClient.java` |
| 5 | `core/src/main/java/com/sbshop/agent/core/application/order/service/CustomsOrderSyncService.java` |
| 5 | `core/src/main/java/com/sbshop/agent/core/application/dashboard/DashboardService.java` |
| 4 | `core/src/main/java/com/sbshop/agent/core/domain/order/OrderLineItem.java` |
| 4 | `core/src/main/java/com/sbshop/agent/core/application/order/service/MarketOrderNormalizer.java` |
| 4 | `core/src/main/java/com/sbshop/agent/core/application/order/adapter/CoupangOrderAdapter.java` |
| 3 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/repository/order/OrderRepositoryImpl.java` |
| 3 | `core/src/main/java/com/sbshop/agent/core/domain/sourcing/SourcingConfig.java` |
| 3 | `core/src/main/java/com/sbshop/agent/core/application/process/ProcessStatusService.java` |
| 3 | `api/src/main/java/com/sbshop/agent/api/controller/SseNotificationController.java` |
| 3 | `api/src/main/java/com/sbshop/agent/api/controller/OrderSyncController.java` |
| 3 | `api/src/main/java/com/sbshop/agent/api/controller/Cafe24AuthController.java` |
| 2 | `core/src/main/java/com/sbshop/agent/core/domain/product/component/ProductSanitizer.java` |
| 2 | `core/src/main/java/com/sbshop/agent/core/application/sourcing/SourcingQueryService.java` |
| 2 | `core/src/main/java/com/sbshop/agent/core/application/sourcing/discovery/CandidateEnrichmentPipeline.java` |
| 2 | `api/src/main/java/com/sbshop/agent/api/controller/ProductController.java` |
| 2 | `api/src/main/java/com/sbshop/agent/api/controller/OrderController.java` |
| 1 | `worker/src/main/java/com/sbshop/agent/worker/service/EmailFetcherService.java` |
| 1 | `worker/src/main/java/com/sbshop/agent/worker/scheduler/OrderSyncScheduler.java` (필드 `com.sbshop.agent.core.application.order.service.Cafe24OrderSyncService` FQN 선언 — import 누락) |
| 1 | `worker/src/main/java/com/sbshop/agent/worker/scheduler/BatchScheduler.java` |
| 1 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/repository/dashboard/DashboardRepositoryImpl.java` |
| 1 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/smartstore/component/SmartstoreAddressBookResolver.java` |
| 1 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/smartstore/client/SmartstoreRestClient.java` |
| 1 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/elevenst/ElevenstOrderRestClient.java` |
| 1 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/elevenst/client/ElevenstMarketRestClient.java` |
| 1 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/elevenst/adapter/ElevenstMarketClient.java` |
| 1 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/coupang/CoupangInvoiceResponse.java` |
| 1 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/coupang/client/CoupangRestClient.java` |
| 1 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/component/Cafe24CategoryResolver.java` |
| 1 | `infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/adapter/Cafe24MarketClient.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/domain/sourcing/ProductDraft.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/domain/product/ProductRepository.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/domain/product/component/ProductValidator.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/domain/market/client/dto/MarketPublishContext.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/sync/SyncStatusService.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/sourcing/enrich/MarketDraftBuilder.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/sourcing/discovery/SourcingDiscoveryUseCase.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/sourcing/discovery/CandidateIngestTxService.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/product/MarketSalePriceResolver.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/product/MarketLinkIdentifierBackfillService.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/product/dto/MarketSalePriceOverrides.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/order/service/OrderService.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/order/service/MarketLineItemSyncPolicy.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/order/service/Cafe24OrderSyncService.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/order/service/Cafe24LineItemMapper.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/order/dto/OrderDetailDto.java` |
| 1 | `core/src/main/java/com/sbshop/agent/core/application/order/dto/MarketOrderDto.java` |

주로 `java.util.Map`/`java.util.Set`/`java.util.HashMap`류를 메서드 본문 안에서 즉석으로 FQN 표기하는 패턴(`ElevenstOrderSyncService`, `SmartstoreMarketClient`, `CoupangOrderSyncService`)과 `com.sbshop.agent...` 필드 타입을 import 없이 그대로 쓴 패턴(`OrderSyncScheduler`의 `Cafe24OrderSyncService` 필드 등)이 섞여 있다. 동일 파일에 같은 simple name이 이미 다른 타입으로 import되어 있어 FQN을 써야 하는 실제 충돌 케이스는 발견되지 않았다(전부 단순 import 누락).

### test — 파일별 히트 수 (내림차순, 전체)

| 건수 | 파일 |
|---|---|
| 17 | `core/src/test/java/com/sbshop/agent/core/application/order/service/Cafe24OrderSyncServiceTest.java` |
| 13 | `core/src/test/java/com/sbshop/agent/core/application/order/service/ElevenstDetectCancellationsTest.java` |
| 9 | `core/src/test/java/com/sbshop/agent/core/application/order/service/OrderSyncEventEmissionTest.java` |
| 8 | `core/src/test/java/com/sbshop/agent/core/application/supplier/SupplierServiceTest.java` |
| 8 | `core/src/test/java/com/sbshop/agent/core/application/product/ProductPublishPriceOverrideTest.java` |
| 7 | `core/src/test/java/com/sbshop/agent/core/application/product/BatchProcessStatusKeyTest.java` |
| 7 | `core/src/test/java/com/sbshop/agent/core/application/order/service/ElevenstThreeTierSyncTest.java` |
| 6 | `core/src/test/java/com/sbshop/agent/core/application/product/BatchForwardsStockStatusTest.java` |
| 6 | `core/src/test/java/com/sbshop/agent/core/application/order/service/OrderAddressProtectionTest.java` |
| 5 | `infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24TokenManagerConcurrencyTest.java` |
| 5 | `core/src/test/java/com/sbshop/agent/core/application/order/service/MarketCredentialValidationTest.java` |
| 4 | `core/src/test/java/com/sbshop/agent/core/application/product/BatchCompletedEventPublishTest.java` |
| 4 | `core/src/test/java/com/sbshop/agent/core/application/order/service/ElevenstMissingOrderStateTest.java` |
| 4 | `core/src/test/java/com/sbshop/agent/core/application/order/service/CustomsSyncTransactionBoundaryTest.java` |
| 4 | `core/src/test/java/com/sbshop/agent/core/application/order/service/CoupangCancelDetectionScopeTest.java` |
| 4 | `api/src/test/java/com/sbshop/agent/api/ElevenstRestClientBeanConflictTest.java` |
| 4 | `api/src/test/java/com/sbshop/agent/api/controller/ProductControllerActionLogDetailTest.java` |
| 3 | `worker/src/test/java/com/sbshop/agent/worker/service/EmailFetcherMarketSyncTruthTest.java` |
| 3 | `core/src/test/java/com/sbshop/agent/core/application/product/ProductMarketSyncServiceTest.java` |
| 3 | `core/src/test/java/com/sbshop/agent/core/application/product/ProductMarketSyncServiceSoldOutTest.java` |
| 3 | `core/src/test/java/com/sbshop/agent/core/application/order/service/OrderShipmentUpsertServiceTest.java` |
| 3 | `core/src/test/java/com/sbshop/agent/core/application/order/service/CoupangOrderProductMappingTest.java` |
| 3 | `core/src/test/java/com/sbshop/agent/core/application/order/adapter/CoupangOrderAdapterCarrierCodeTest.java` |
| 3 | `api/src/test/java/com/sbshop/agent/api/dto/OrderResponseContractTest.java` |
| 3 | `api/src/test/java/com/sbshop/agent/api/controller/OrderControllerMarketTypeLogTest.java` |
| 3 | `api/src/test/java/com/sbshop/agent/api/controller/OrderControllerBulkResultLogTest.java` |
| 2 | `worker/src/test/java/com/sbshop/agent/worker/service/EmailFetcherServiceTest.java` |
| 2 | `infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24TokenManagerTest.java` |
| 2 | `infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24TokenManagerFailFastTest.java` |
| 2 | `core/src/test/java/com/sbshop/agent/core/application/sourcing/discovery/CandidateScoringServiceTest.java` |
| 2 | `core/src/test/java/com/sbshop/agent/core/application/product/ProductSyncServiceRestockDateTest.java` |
| 2 | `core/src/test/java/com/sbshop/agent/core/application/product/BatchManualUpdatePairBindingTest.java` |
| 2 | `core/src/test/java/com/sbshop/agent/core/application/order/service/SmartStoreThreeTierSyncTest.java` |
| 2 | `core/src/test/java/com/sbshop/agent/core/application/order/service/OrderServiceStateGuardTest.java` |
| 2 | `core/src/test/java/com/sbshop/agent/core/application/order/service/CoupangThreeTierSyncTest.java` |
| 2 | `core/src/test/java/com/sbshop/agent/core/application/order/service/CoupangSettlementActionLogTest.java` |
| 2 | `core/src/test/java/com/sbshop/agent/core/application/order/adapter/SmartStoreOrderFetchFailureTest.java` |
| 2 | `api/src/test/java/com/sbshop/agent/api/controller/ProductControllerR6QueryTest.java` |
| 2 | `api/src/test/java/com/sbshop/agent/api/controller/ProductControllerInputValidationTest.java` |
| 2 | `api/src/test/java/com/sbshop/agent/api/controller/ProductControllerImageUploadTest.java` |
| 2 | `api/src/test/java/com/sbshop/agent/api/controller/ProductControllerImagePartialFailureTest.java` |
| 1 | `worker/src/test/java/com/sbshop/agent/worker/service/EmailTrackingSourcePromotionTest.java` |
| 1 | `worker/src/test/java/com/sbshop/agent/worker/service/EmailFetcherBodyExtractionTest.java` |
| 1 | `infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24OrderApiClientStatusTest.java` |
| 1 | `infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24OAuthTokenHttpClientTest.java` |
| 1 | `infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24MarketClientCategoryTest.java` |
| 1 | `core/src/test/java/com/sbshop/agent/core/domain/market/MarketRegistrationUniqueConstraintTest.java` |
| 1 | `core/src/test/java/com/sbshop/agent/core/application/product/ProductMarketSyncPerMarketPriceTest.java` |
| 1 | `core/src/test/java/com/sbshop/agent/core/application/product/BatchPriceStockAsyncPoolTest.java` |
| 1 | `core/src/test/java/com/sbshop/agent/core/application/order/service/TerminalSettlementServiceTest.java` |
| 1 | `core/src/test/java/com/sbshop/agent/core/application/order/service/SyncServiceSelfRecordsStatusTest.java` |
| 1 | `core/src/test/java/com/sbshop/agent/core/application/order/service/MarketLineItemSyncDispatcherTest.java` |
| 1 | `core/src/test/java/com/sbshop/agent/core/application/order/service/Cafe24ShipmentServiceTest.java` |
| 1 | `core/src/test/java/com/sbshop/agent/core/application/order/mapper/SmartStoreStatusMapperTest.java` |
| 1 | `core/src/test/java/com/sbshop/agent/core/application/order/adapter/SmartStoreOrderThrottleTest.java` |
| 1 | `core/src/test/java/com/sbshop/agent/core/application/order/adapter/SmartStoreOrderParseTest.java` |
| 1 | `core/src/test/java/com/sbshop/agent/core/application/order/adapter/ElevenstShippingRecipientPreservationTest.java` |
| 1 | `core/src/test/java/com/sbshop/agent/core/application/order/adapter/ElevenstShipOrderDlvNoTest.java` |
| 1 | `api/src/test/java/com/sbshop/agent/api/controller/SupplierControllerCurrencyGuardTest.java` |
| 1 | `api/src/test/java/com/sbshop/agent/api/controller/ProductControllerMarketMapTest.java` |
| 1 | `api/src/test/java/com/sbshop/agent/api/controller/BatchControllerTriggerCharacterizationTest.java` |
| 1 | `api/src/test/java/com/sbshop/agent/api/controller/BatchControllerSupplierValidationTest.java` |
| 1 | `api/src/test/java/com/sbshop/agent/api/controller/BatchControllerManualUpdateAllValidationTest.java` |
| 1 | `api/src/test/java/com/sbshop/agent/api/controller/BatchControllerCrawlValidationTest.java` |

---

## ③ 아키텍처 비일관성

### (a) 마켓별 패키지 구조 불일치 — **구조 변경 백로그행** (파일 이동은 이번 캠페인 범위 밖)

`infrastructure/client/{market}` 하위 구조를 마켓 4곳(cafe24/coupang/elevenst/smartstore)에서 비교:

```
cafe24:      adapter/Cafe24MarketClient · client/Cafe24OrderApiClient · client/Cafe24RestClient · component/ · (config 없음)
coupang:     adapter/CoupangMarketClient · CoupangOrderApiClient(루트) · client/CoupangRestClient · component/ · config/ · dto/ · mapper/ · parser/
elevenst:    adapter/ElevenstMarketClient · ElevenstOrderApiClient(루트) · client/ElevenstMarketRestClient · config/ · (component 없음)
smartstore:  adapter/SmartstoreMarketClient · SmartStoreOrderApiClient(루트) · client/SmartstoreRestClient · component/ · config/ · (SmartStoreDispatchResult도 루트)
```

핵심 불일치: **`client/` 서브패키지의 의미가 마켓마다 다르다.** coupang·elevenst·smartstore는 `client/`에 "저수준 HTTP 래퍼"만 두고, 포트를 구현하는 실제 주문 API 클라이언트(`XxxOrderApiClient`)는 패키지 루트에 둔다. 반면 cafe24만 `client/Cafe24OrderApiClient.java`로 **포트 구현체까지 `client/` 서브패키지 안에 넣었다** — 이 부분이 옛 지도(D-1, 2026-07-07)가 지적했던 `elevenst/ElevenstRestClient` vs `elevenst/client/ElevenstRestClient` 이중구조 패턴과 본질적으로 같은 종류의 불일치가 cafe24에서 재발한 것. 그 외 coupang은 `dto/`+루트에 요청/응답 레코드가 섞여 있고(`CoupangAcceptOrdersRequest`, `CoupangCancelOrderResponse`, `CoupangInvoiceResponse`는 루트, `CategoryMetaResult`, `CoupangProductPayload`는 `dto/`), elevenst·smartstore·cafe24는 `dto/` 패키지 자체가 없다. cafe24는 다른 세 마켓과 달리 `config/` 패키지가 없다(`Cafe24OAuthTokenClient`/`Cafe24OAuthTokenHttpClient`/`Cafe24TokenManager`가 전부 루트).

이건 파일 이동(패키지 재배치)이 필요해 이번 "구조 변경만, 주석/FQN/순서/데드코드" 캠페인의 4개 허용 항목에 들지 않는다 — **구조 변경 백로그행**으로 분류, 별도 리팩토링 스프린트 필요.

### (b) 중복/역할분리 클래스 — 재확인 결과 대부분 기존에 해소됨

- `AsyncConfig` 2벌(`core/config/AsyncConfig.java`, `api/config/AsyncConfig.java`)은 옛 지도의 D-2 지적대로 여전히 동일 simple name이 존재하지만, **이미 D-011로 의도적 정리가 완료된 상태**다. `core`판이 `syncTaskExecutor`+`productBatchExecutor` 두 빈을 모두 소유하고, `api`판(`@Configuration("apiAsyncConfig")`)은 빈이 없는 빈 껍데기이며 그 이유가 클래스 상단 Javadoc에 상세히 적혀 있다(빈 이름 `apiAsyncConfig`와 회귀 테스트 보존 목적, "정리 후보"라고 스스로 명시). **이 Javadoc은 doctrine 5번 규칙의 "왜" 주석에 해당 — 주석 전량삭제 전 `salvage-backend.md`에 옮겨 적을 것.** 구조상 손댈 것 없음(이미 의도된 상태), 주석 처리만 신경 쓰면 됨.
- `ElevenstRestClient` 이중구조(D-1)는 D-010에서 `ElevenstOrderRestClient`/`ElevenstMarketRestClient`로 개명 완료, 확인됨 — 재발 없음.
- 이미지 다운로더 3벌(D-3)은 D-004에서 단일 `ImageDownloadClient` 구현으로 통합 완료, 확인됨(`infrastructure/client/image/` 패키지 자체가 더 이상 존재하지 않음).
- ESM+ 전용 인프라(`EsmplusScraper`, `EsmplusOrderApiPortImpl`, D-4)는 **패키지째 삭제되어 더 이상 존재하지 않는다** — G마켓/옥션 주문 동기화는 `Cafe24OrderSyncService`(`OrderSyncScheduler.syncEsmplusOrders()`가 실제로는 이걸 호출)로 완전히 이관됨. 옛 지도의 D-4/Z-5는 전량 해소.

### (c) 계층 위반 — **기계적 정리 범위 밖, 별도 판단 필요**

`api/src/main/java/com/sbshop/agent/api/controller/Cafe24AuthController.java:6-7`이 `com.sbshop.agent.infrastructure.client.cafe24.Cafe24TokenManager`와 `...cafe24.client.Cafe24RestClient`를 **직접 import**한다. `api` 모듈의 다른 컨트롤러는 전부 `core`의 서비스/유스케이스를 경유하는데 이 컨트롤러만 `infrastructure`를 직접 뚫는다(Cafe24 OAuth 콜백/관리자 수동 동기화 트리거용, `/api/admin/sync/cafe24`). `build.gradle`상 `api → infrastructure` 의존 자체는 선언돼 있어 컴파일은 되지만(`api/build.gradle`: `project(':core')`, `project(':infrastructure')`, `project(':worker')`), 레이어드 아키텍처 원칙상 core 포트를 우회하는 유일한 사례. 구조 변경(core에 OAuth 트리거 포트 신설)이 필요해 백로그행 — 이번 캠페인에서 손대지 않는다.

core 모듈은 `infrastructure`나 `springframework.web`을 전혀 import하지 않음(0건 확인) — core의 순수성 자체는 잘 지켜지고 있다.

`api`가 `worker`에도 의존(`api/build.gradle: implementation project(':worker')`)하는 것은 위반이 아니라 CLAUDE.md에 문서화된 현재 배포 토폴로지(worker가 api JVM에 라이브러리로 통합)를 그대로 반영한 정상 상태.

### (d) 네이밍 비일관 — 소규모, 기계적 수정 범위 밖(동작 미변경 원칙상 이번엔 이름 안 바꿈)

`worker/src/main/java/com/sbshop/agent/worker/scheduler/OrderSyncScheduler.java`의 메서드 `syncEsmplusOrders()`(L57)는 실제로는 `cafe24OrderSyncService.syncCafe24Orders()`를 호출한다(L61) — ESM+ 인프라가 Cafe24로 전면 대체된 뒤에도 메서드명·로그 메시지("G마켓/옥션(Cafe24 주문API) 동기화 트리거...")는 새 구현을 설명하되 메서드명 자체는 옛 이름을 유지한 상태. 주석에는 "(Selenium ESM+ → Cafe24 주문 API로 선회)"라고 정확히 남아 있어 혼란을 방지하고 있지만, 이 주석도 doctrine 1번 규칙상 전량삭제 대상이라 **삭제 시 메서드명만 남아 맥락을 잃는다.** salvage 후보로 표시하거나, Phase 2에서 이름 자체를 `syncGmarketAuctionOrders()` 등으로 바꾸는 걸 백로그에 남기는 것을 권고(이번 캠페인 "동작 변경 금지" 원칙상 이름 변경도 보수적으로 접근 — 리더 판단 필요).

Service/Manager/Handler 등 접미사 혼용은 훑어본 범위에서 뚜렷한 문제 없음 — `*Service`(애플리케이션 서비스), `*UseCase`(유스케이스), `*Adapter`(포트-마켓 어댑터), `*Client`(외부 API 클라이언트), `*Resolver`/`*Builder`/`*Predictor`(단일책임 컴포넌트) 규칙이 core/infrastructure 전반에서 대체로 일관되게 지켜지고 있다.

카테고리 해석기는 coupang/smartstore/cafe24 세 마켓이 `MarketCategoryResolverPort`를 구현하는 `*CategoryResolver`를 갖고 있으나 elevenst만 없다 — 이는 버그가 아니라 기존에 확인된 의도적 제약(11번가는 카테고리 해석기 부재로 상품 자동등록 상시 거부, 마켓 정책 문서화됨)이므로 여기서는 단순 참고 사항으로만 기록.

---

## ④ 주석 규모 (참고용, 라인 근사치)

`^\s*(//|/\*|\*)` 패턴 매칭 라인 수, main 소스만:

| 모듈 | 주석 라인(근사) |
|---|---|
| core | 4,090 |
| infrastructure | 684 |
| api | 531 |
| worker | 329 |

core에 압도적으로 몰려 있다 — 도메인 규칙·상태 가드·마켓별 함정을 설명하는 "왜" 주석이 core의 order/product 애플리케이션 서비스에 특히 밀집(`OrderService`, `MarketplaceShippingService`, `CoupangOrderSyncService`, `ElevenstOrderAdapter`, `ElevenstMarketClient` 등 — F-ORD-*, D-*, F-SYNC-* 코드가 촘촘히 박혀 있음). **주석 제거 담당 에이전트는 이 파일들을 특히 신중히 다뿔 것 — salvage-backend.md 후보가 여기 몰려 있을 가능성이 높다.** 참고로 위 §① 말미에서 확인했듯 "고밀도 주석 = 죽은 코드"가 아니라 "고밀도 주석 = salvage 위험도 높음"으로 해석해야 한다.

---

## 요약 (팀리더용)

- **고신뢰 데드코드 3건**: `BusinessDayCalculator`, `UnipassUpdateRequest`, `IngredientAliasSeed` — 전부 확인 완료, 삭제 안전.
- **데드 의심 서브시스템 1쌍**: `SourcingAgentFactory`+`SourcingAgent`(인터페이스인데 구현체가 코드베이스에 전혀 없음) — Spring 빈이라 삭제 금지, defect-scout 원장 등재 권고.
- **주석처리된 진짜 죽은 코드는 1건**: `BatchScheduler.java:27`의 비활성 `@Scheduled` — 바로 위 D-093 "왜" 주석은 salvage 필수.
- **주요 비일관성**:
  1. `infrastructure/client/{market}` 4곳의 패키지 구조가 마켓마다 다름(특히 `client/` 서브패키지 의미가 cafe24만 다르게 쓰임) — 구조 변경 백로그행.
  2. `api/controller/Cafe24AuthController`가 core를 건너뛰고 `infrastructure`를 직접 import — 유일한 계층 위반, 포트 신설 필요해 백로그행.
  3. `AsyncConfig` 2벌은 이미 D-011로 의도적으로 정리된 상태(빈 껍데기 유지 이유가 Javadoc에 있음) — salvage만 하면 되고 추가 통합 불필요.
  4. ESM+ 전용 인프라·D-1/D-3/D-4/Z-4/Z-5(옛 지도)는 전부 이미 해소되어 재확인만 하면 됨 — 최신 지도에 반영 필요.
  5. `OrderSyncScheduler.syncEsmplusOrders()`가 실제로는 Cafe24 경유 — 이름과 구현이 어긋난 네이밍 잔재, 동작 변경 금지 원칙상 이번엔 보류 권고.
- **주석 제거 시 함정**: 고밀도 주석 파일 목록(core 위주)은 죽은 코드가 아니라 대부분 F-ORD-*/D-*/F-SYNC-* 근거를 담은 "왜" 주석이다. 기계적으로 밀도만 보고 정리하면 안 되고, 파일별로 salvage 여부를 판단해야 한다.

전체 상세 내역(FQN 파일별 전체 목록 포함)은 `_workspace/refactor/survey-backend.md` 참조.
