# Codebase Map — sbshop-agent

## 갱신 이력

| 날짜 | 담당 | 변경 요지 |
|------|------|-----------|
| 2026-07-07 | legacy-mapper | 초기 지도 작성 (이전 지도 없음) |

---

## ① 모듈 지도

### 백엔드 멀티 모듈 구조 (Gradle)

```
backend/
├── core/           순수 도메인 레이어 (외부 의존 없음)
├── infrastructure/ 인프라 구현체 (→ core)
├── api/            REST API 서버 (→ core, infrastructure)
└── worker/         스케줄러 워커 (→ core, infrastructure)
```

의존 방향 (단방향, 역방향 없음):

```
core  ←  infrastructure  ←  api
                         ←  worker
```

`infrastructure/build.gradle:1` — `implementation project(':core')`  
`api/build.gradle:2` — `implementation project(':core')`, `implementation project(':infrastructure')`  
`worker/build.gradle:2` — `implementation project(':core')`, `implementation project(':infrastructure')`

### core 모듈 패키지별 도메인

| 패키지 | 역할 | 주요 클래스 |
|--------|------|-------------|
| `core.domain.order` | 주문 집합체 | `Order`, `OrderLineItem`, 5개 enum |
| `core.domain.product` | 상품 집합체 | `Product`, `ImageDownloadClient`(port), `HtmlImageReplacer` 등 |
| `core.domain.market` | 마켓 등록/자격증명 | `MarketCredential`, `MarketClient`(port), `MarketClientRouter` |
| `core.domain.supplier` | 공급업체/환율 | `Supplier`, `Currency` |
| `core.domain.process` | 배치 진행 상태 | `ProcessStatus`, 3개 enum |
| `core.domain.fee` | 수수료 정책 | `FeePolicy` |
| `core.domain.sourcing` | 소싱 에이전트 | `SourcingAgent`, `SourcingAgentFactory` |
| `core.application.order` | 주문 서비스 | `OrderService`, `OrderShipService`, 4개 SyncService, 4개 adapter, 5개 port |
| `core.application.product` | 상품 서비스 | `BatchPriceStockService`, `ProductCreateUseCase`, `ProductManageUseCase`, `ProductPublishUseCase`, `ProductSearchUseCase`, `ProductSyncService` |
| `core.application.market` | 자격증명 서비스 | `MarketCredentialService` |
| `core.application.sourcing` | 소싱 유스케이스 | `ProductSourcingUseCase` |
| `core.application.sync` | 동기화 상태 추적 | `SyncStatusService` |
| `core.application.process` | 배치 상태 서비스 | `ProcessStatusService` |
| `core.config` | 설정 | `AsyncConfig`(syncTaskExecutor), `JpaAuditingConfig`, `EmailAccountProperties` |

### infrastructure 모듈 패키지별 구조

| 패키지 | 역할 | 핵심 클래스 |
|--------|------|-------------|
| `infrastructure.client.coupang` | 쿠팡 주문/상품 | `CoupangOrderApiClient`, `CoupangRestClient`, `CoupangMarketClient` |
| `infrastructure.client.elevenst` | 11번가 | `ElevenstOrderApiClient`, `ElevenstRestClient`(2벌 — 중복 섹션 참조) |
| `infrastructure.client.esmplus` | ESM+(G마켓/옥션) | `EsmplusOrderApiPortImpl`, `EsmplusScraper` |
| `infrastructure.client.smartstore` | 스마트스토어 | `SmartStoreOrderApiClient`, `SmartstoreRestClient`, `SmartstoreMarketClient` |
| `infrastructure.client.cafe24` | 카페24 | `Cafe24TokenManager`, `Cafe24RestClient`, `Cafe24MarketClient` |
| `infrastructure.client.cloudflare` | 이미지 저장/다운 | `R2ImageStorageClient`, `ImageDownloadService` |
| `infrastructure.client.image` | 이미지 다운로드 | `ImageDownloader`(ImageDownloadClient 구현체) |
| `infrastructure.client.customs` | 통관 스크래퍼 | `GsiExpressScraperAdapter` |
| `infrastructure.client.sourcing` | iHerb 소싱 | `IherbScraperClient` |
| `infrastructure.client.common` | 공용 유틸 | `HtmlImageExtractor` |
| `infrastructure.repository` | JPA 구현체 | `OrderRepositoryImpl`, `OrderLineItemRepositoryImpl`, `ProductReaderImpl`, `ProductWriterImpl` |
| `infrastructure.config` | QueryDSL 설정 | `QueryDslConfig` |

### api 모듈 구조

컨트롤러 12개: `OrderController`, `OrderSyncController`, `ProductController`, `BatchController`, `ProductSourcingController`, `MarketCredentialController`, `MarketRegistrationController`, `ProductSyncController`, `CommonCodeController`, `SupplierController`, `SseNotificationController`, `Cafe24AuthController`

### worker 모듈 구조

스케줄러 3개: `OrderSyncScheduler`, `BatchScheduler`, `ProductSyncScheduler`  
서비스 2개: `EmailFetcherService`, `OrderEmailParser`

### 프론트엔드 구조

```
frontend/src/
├── api/           백엔드 통신 (axios.ts + 6개 API 파일)
├── pages/         페이지 컴포넌트 (7개)
├── components/ui/ 공용 UI (Table.tsx)
├── layouts/       레이아웃 (MainLayout.tsx)
└── App.tsx        라우팅
```

---

## ② 중복 구현 인벤토리

### D-1. ElevenstRestClient 2벌 (분기된 복제)

| 구분 | 경로 | 패키지 |
|------|------|--------|
| A | `infrastructure/client/elevenst/ElevenstRestClient.java:23` | `com.sbshop.agent.infrastructure.client.elevenst` |
| B | `infrastructure/client/elevenst/client/ElevenstRestClient.java:17` | `com.sbshop.agent.infrastructure.client.elevenst.client` |

**A의 특징** (라인 23-79):
- Spring `RestClient` 사용, 반환 타입 `Document` (XML 파싱 내장)
- GET 전용, API 키를 메서드 파라미터로 받음
- EUC-KR 디코딩 + 중복 태그 제거 로직 포함

**B의 특징** (라인 17-62):
- `HttpURLConnection` 직접 사용, 반환 타입 `String` (원시 응답)
- GET/POST/PUT 지원, API 키를 `ElevenstProperties`에서 주입
- EUC-KR 인코딩/디코딩 (raw bytes 수준)

**실제 사용처**:
- A → `ElevenstOrderApiClient` (주문 동기화 포트 구현, XML Document 파싱)
- B → `ElevenstMarketClient` (상품 등록/가격재고 동기화, 문자열 XML)

**판정**: 단순 복제 아님 — 용도와 반환 타입이 다른 **분기된 복제**. 두 클래스가 같은 Spring 컨텍스트에 등록될 때 기본 빈 이름이 모두 `elevenstRestClient`가 되어 **빈 이름 충돌이 발생한다.** 실제로는 패키지가 달라 각자의 의존 클래스가 명확히 타입으로 주입받으므로 런타임 주입 자체는 성공하지만, Spring 컨텍스트 초기화 시 `@Primary` 없이 동일 이름 빈이 2개 등록되면 `ConflictingBeanDefinitionException` 발생 가능.

**통합 권고**: A를 `ElevenstOrderApiRestClient`, B를 `ElevenstProductRestClient`로 이름을 구분하고 `@Bean` 이름을 명시하거나, B를 A 위에 추상화하여 단일 HTTP 클라이언트로 통합 후 호출 측에서 XML 파싱을 담당한다.

---

### D-2. AsyncConfig 2벌 (분기된 복제 — 한쪽 비기능)

| 구분 | 경로 | 라인 |
|------|------|------|
| A | `core/src/main/java/com/sbshop/agent/core/config/AsyncConfig.java` | 1-24 |
| B | `api/src/main/java/com/sbshop/agent/api/config/AsyncConfig.java` | 1-23 |

**A의 특징**:
- `@Bean(name = "syncTaskExecutor")` 등록 — 실제 빈 생성
- `SmartStoreOrderSyncService`, `ElevenstOrderSyncService`, `EsmplusOrderSyncService`, `CoupangOrderSyncService`에서 `@Async("syncTaskExecutor")`로 참조됨

**B의 특징**:
- `@Configuration`, `@EnableAsync` 있음
- `productBatchExecutor()` 메서드에 **`@Bean` 어노테이션 없음** — 빈으로 등록되지 않음
- `BatchPriceStockService`는 `@Async` (unnamed)를 사용하므로 디폴트 executor 사용

**판정**: B의 `productBatchExecutor()` 메서드는 `@Bean`이 없어 Spring에 등록되지 않는 **사실상 죽은 코드**. B의 `@EnableAsync`는 A에도 있으므로 중복. `@Configuration` + `@EnableAsync`만 있고 실제 빈 등록이 없는 B는 제거 또는 `@Bean` 추가가 필요.

**통합 권고**: B에 `@Bean` 추가 후 A와 합치거나, B 파일 전체를 제거하고 A에 `productBatchExecutor` 빈을 추가한다.

---

### D-3. 이미지 다운로더 3벌 (역할 분리)

| 구분 | 경로 | 라인 |
|------|------|------|
| I (인터페이스) | `core/domain/product/client/ImageDownloadClient.java` | 1-13 |
| II (구현체) | `infrastructure/client/image/ImageDownloader.java` | 1-104 |
| III (독립 서비스) | `infrastructure/client/cloudflare/ImageDownloadService.java` | 1-74 |

**I의 특징**: `downloadAll`, `download`, `downloadAndConvert` 3개 메서드 선언 (포트 인터페이스)

**II의 특징** (라인 22-104):
- `ImageDownloadClient` 구현, `RestTemplate` 사용
- `downloadAll` → `download` 재사용 구조 (원본 content-type 보존)
- `downloadAndConvert` → Thumbnailator로 1000×1000 JPG 변환

**III의 특징** (라인 18-74):
- `ImageDownloadClient`를 구현하지 않음 (standalone)
- `OkHttpClient` 사용, `downloadAndConvert` 메서드만 있음
- User-Agent 헤더 포함 (크롤링 대상 사이트 호환)

**실제 사용처**:
- II → `ProductCreateUseCase:27` (`ImageDownloadClient` 타입으로 주입, 소싱 상품 벌크 등록 시 이미지 다운)
- III → `ProductController:54` (`ImageDownloadService` 타입으로 직접 주입, `PUT /products/{id}/images/by-url` 엔드포인트)

**판정**: 단순 복제 아님 — II는 인터페이스를 통한 도메인 포트 구현, III는 컨트롤러 레이어가 인터페이스를 우회해 직접 사용하는 구현체. 동일한 `downloadAndConvert` 로직이 양쪽에 중복 존재하며 HTTP 클라이언트가 서로 다름(RestTemplate vs OkHttp).

**통합 권고**: III를 `ImageDownloadClient`를 구현하도록 변경하거나 II에 통합. `ProductController`가 인터페이스(I)를 통해 주입받도록 수정하면 HTTP 클라이언트를 한 곳에서 선택할 수 있다.

---

### D-4. EsmplusScraper vs EsmplusOrderApiPortImpl (역할 분리, 코드 중복)

| 구분 | 경로 | 라인 수 |
|------|------|---------|
| A (탐색용) | `infrastructure/client/esmplus/EsmplusScraper.java` | 272 |
| B (프로덕션) | `infrastructure/client/esmplus/EsmplusOrderApiPortImpl.java` | 747 |

**A의 특징**:
- `EsmplusOrderApiPort` 미구현 — standalone 클래스
- `loginAndScrapeOrders()` 단일 메서드 (탐색/디버그용)
- `OrderSyncController`의 테스트 엔드포인트에서만 호출 (`/esmplus/test`, `/esmplus/scrape`)
- XHR 인터셉터를 주입해 네트워크 응답을 캡처하는 탐색 코드

**B의 특징**:
- `EsmplusOrderApiPort` 구현 (fetchOrders, fetchOrderDetail, confirmOrders, cancelOrders)
- `EsmplusOrderSyncService`를 통해 실제 동기화에 사용
- 상세 페이지 드라이버 캐싱(`cachedDetailDriver`) 포함

**공통 중복 코드**:
- `createChromeOptions()` — A:257-270, B:732-745 완전 동일
- 로그인 시퀀스 (`loginAndCreateDriver` vs A의 인라인) — 거의 동일
- XHR 인터셉터 주입 스크립트 — A:127-158, B:562-593 완전 동일

**판정**: A는 프로덕션에서 사용 안 되는 탐색 코드(디버그 엔드포인트 전용). 도메인 로직 중복은 없으나 인프라 코드(Chrome 설정, 로그인 시퀀스, XHR 인터셉터)가 심각하게 중복됨.

**통합 권고**: 공통 부분을 `EsmplusWebDriverFactory` + `EsmplusXhrInterceptor` 유틸로 추출한 후 A를 제거하거나 B의 내부 메서드로 흡수한다.

---

## ③ 죽은/비활성 코드 목록

### Z-1. OrderSyncScheduler 비활성 스케줄 6개

파일: `worker/scheduler/OrderSyncScheduler.java`

모든 6개 메서드에 `// TODO: 리팩토링 완료 후 활성화` 주석이 달려 있으며 `@Scheduled` 어노테이션은 코드에 존재한다. 즉, **스케줄은 런타임에 실제로 실행되지만** 의도적 활성화 전에 실행되는 상태다.

| 메서드 | 크론 | 키 | 서비스 호출 경로 |
|--------|------|-----|------------------|
| `syncOrders()` L38 | `0 0/30 * * * ?` | EMAIL | `EmailFetcherService.fetchAndProcessEmails()` |
| `syncCoupangOrders()` L53 | `0 5/30 * * * ?` | COUPANG | `CoupangOrderSyncService.syncCoupangOrders()` |
| `syncEsmplusOrders()` L68 | `0 10/30 * * * ?` | GMARKET | `EsmplusOrderSyncService.syncEsmplusOrders()` |
| `syncSmartStoreOrders()` L83 | `0 15/30 * * * ?` | SMART_STORE | `SmartStoreOrderSyncService.syncSmartStoreOrders()` |
| `syncElevenstOrders()` L98 | `0 20/30 * * * ?` | ELEVEN_STREET | `ElevenstOrderSyncService.syncElevenstOrders()` |
| `syncCoupangSettlement()` L113 | `0 0 2 * * ?` | COUPANG_SETTLEMENT | `CoupangOrderSyncService.syncCoupangSettlement()` |

비고: `syncCustomsStatus()` (L127, `0 0 * * * ?`) 는 TODO 주석 없음 — 의도적 활성 상태.

---

### Z-2. api/config/AsyncConfig.productBatchExecutor() — @Bean 누락

파일: `api/src/main/java/com/sbshop/agent/api/config/AsyncConfig.java:13`

`productBatchExecutor()` 메서드에 `@Bean` 어노테이션이 없어 Spring 컨텍스트에 빈으로 등록되지 않는다. `BatchPriceStockService`의 `@Async` 메서드들은 이 빈을 참조하지 않고 디폴트 executor를 사용한다. 파일 자체는 `@Configuration`, `@EnableAsync`로 등록되지만 실질적인 빈 기여가 없다.

---

### Z-3. frontend/orderApi.ts 미호출 함수 4개

파일: `frontend/src/api/orderApi.ts`

OrderGrid.tsx의 import에 포함되지 않고, 다른 어떤 페이지에서도 import되지 않는 함수:

| 함수 | 라인 | 대응 백엔드 엔드포인트 |
|------|------|----------------------|
| `confirmOrder` (단건) | L186 | `POST /api/v1/orders/{id}/confirm` |
| `purchaseItem` | L207 | `POST /api/v1/orders/line-items/{lineItemId}/purchase` |
| `shipItem` | L218 | `POST /api/v1/orders/line-items/${lineItemId}/ship` |
| `updateTracking` | L227 | `PUT /api/v1/orders/line-items/${lineItemId}/tracking` |

비고: `confirmOrdersBatch` (일괄 발주확인)는 사용 중. 단건 `confirmOrder`는 일괄 처리로 대체되어 사용되지 않는 것으로 보임. `purchaseItem`, `shipItem`, `updateTracking`은 UI에 해당 기능이 구현되지 않은 상태.

---

### Z-4. after-migrate.sql과 V4 마이그레이션의 내용 중복

| 파일 | 경로 |
|------|------|
| V4 마이그레이션 | `infrastructure/src/main/resources/db/migration/V4__product_vo_and_new_tables.sql` |
| Flyway 콜백 | `api/src/main/resources/after-migrate.sql` |

`after-migrate.sql`은 Flyway 콜백으로 매 애플리케이션 기동 시 실행되며, V4의 DDL(`sb_currency`, `sb_supplier`, `sb_process_status` 테이블 생성, `source_images`/`hosted_images` 타입 변환)을 거의 그대로 포함한다. `IF NOT EXISTS`/`IF EXISTS`로 멱등성은 보장하나, V4 이후 기동 시 중복 실행으로 불필요한 DDL 수행이 발생한다.

---

### Z-5. EsmplusScraper — 프로덕션 미사용 탐색 코드

파일: `infrastructure/client/esmplus/EsmplusScraper.java:22`

프로덕션 동기화 흐름에서 참조되지 않으며, `OrderSyncController`의 디버그 전용 엔드포인트 2개(`/esmplus/test`, `/esmplus/scrape`)에서만 사용된다. 이 엔드포인트들은 프론트엔드에 대응하는 API 파일이 없어 수동 호출(curl/Postman)로만 접근 가능하다.

---

## ④ 경계면 목록

### A. API 컨트롤러 ↔ Frontend API 파일 매핑

| 컨트롤러 | 기본 경로 | Frontend 파일 | 미매핑 엔드포인트 |
|----------|-----------|---------------|------------------|
| `OrderController` | `/api/v1/orders` | `orderApi.ts` | `POST /cancel/batch` (frontend 미구현) |
| `OrderSyncController` | `/api/v1/orders/sync` | `orderApi.ts` | `POST /coupang/settlement`, `POST /esmplus/test`, `POST /esmplus/scrape` (frontend 없음) |
| `ProductController` | `/api/v1/products` | `productApi.ts` | 완전 매핑 |
| `BatchController` | `/api/v1/products/batch` | `batchApi.ts` | `GET /status` (전체 배치ID 목록, frontend 미구현) |
| `ProductSourcingController` | `/api/v1/sourcing`, `/api/v1/products` | `sourcingApi.ts` | 완전 매핑 |
| `MarketCredentialController` | `/api/v1/market-credentials` | `marketApi.ts` | 완전 매핑 |
| `MarketRegistrationController` | `/api/v1/products/{id}/markets` | `productApi.ts` | 완전 매핑 |
| `ProductSyncController` | `/api/v1/products/sync` | `orderApi.ts` (syncProductStock) | 완전 매핑 |
| `CommonCodeController` | `/api/v1/common/codes` | `orderApi.ts` (fetchCommonCodes) | 완전 매핑 |
| `SupplierController` | `/api/v1/suppliers`, `/api/v1/currencies` | `supplierApi.ts` | 완전 매핑 |
| `SseNotificationController` | `/api/v1/notifications/subscribe` | **없음** | 전체 미매핑 |
| `Cafe24AuthController` | `/api/admin/sync/cafe24` | **없음** | 전체 미매핑 (admin only) |

**누락 짝 요약**:
- `SseNotificationController` — frontend에 SSE 구독 코드 없음. 이벤트는 발행되지만 프론트에서 수신하지 않음.
- `POST /api/v1/orders/sync/coupang/settlement` — frontend `orderApi.ts`에 대응 함수 없음.
- `POST /api/v1/orders/cancel/batch` — backend 구현 있음(`OrderController:97`), frontend 미구현.
- `POST /api/v1/orders/{id}/confirm` (단건) — frontend `confirmOrder` 함수 정의만 있고 미사용.

---

### B. 스케줄러 ↔ 서비스 경계

| 스케줄러 | 메서드 | 크론 | 상태 | 서비스 |
|----------|--------|------|------|--------|
| `OrderSyncScheduler` | `syncOrders` | `0 0/30 * * * ?` | TODO주석 | `EmailFetcherService.fetchAndProcessEmails()` |
| `OrderSyncScheduler` | `syncCoupangOrders` | `0 5/30 * * * ?` | TODO주석 | `CoupangOrderSyncService.syncCoupangOrders()` |
| `OrderSyncScheduler` | `syncEsmplusOrders` | `0 10/30 * * * ?` | TODO주석 | `EsmplusOrderSyncService.syncEsmplusOrders()` |
| `OrderSyncScheduler` | `syncSmartStoreOrders` | `0 15/30 * * * ?` | TODO주석 | `SmartStoreOrderSyncService.syncSmartStoreOrders()` |
| `OrderSyncScheduler` | `syncElevenstOrders` | `0 20/30 * * * ?` | TODO주석 | `ElevenstOrderSyncService.syncElevenstOrders()` |
| `OrderSyncScheduler` | `syncCoupangSettlement` | `0 0 2 * * ?` | TODO주석 | `CoupangOrderSyncService.syncCoupangSettlement()` |
| `OrderSyncScheduler` | `syncCustomsStatus` | `0 0 * * * ?` | 활성 | `CustomsOrderSyncService.syncCustomsStatus()` |
| `BatchScheduler` | `scheduleDailyIherbPriceUpdate` | `0 0 5 * * ?` | 활성 | `BatchPriceStockService.crawlAndUpdatePriceStock()` |
| `ProductSyncScheduler` | `syncIherbProductStock` | `0 0 4 * * ?` | 활성 | `ProductSyncService.syncStockForPreparingOrders()` |

---

### C. Flyway 마이그레이션 ↔ 엔티티 매핑

| 마이그레이션 | 대상 테이블 | 대응 엔티티 |
|-------------|------------|-------------|
| `V1__init_schema.sql` | `sb_product`, `sb_order`, `sb_order_line_item`, `sb_fee_policy` | `Product`, `Order`, `OrderLineItem`, `FeePolicy` |
| `V2__move_shipping_fee_to_logistics_cost.sql` | `sb_order_line_item` (`logistics_cost` 추가, `shipping_fee` 제거) | `OrderLineItem` (`SourcingData` VO의 `logisticsCost`) |
| `V3__move_unipass_done_to_line_item.sql` | `sb_order_line_item` (`is_unipass_done` 이동), `sb_order` (컬럼 제거) | `OrderLineItem.isUnipassDone`, `Order` (제거) |
| `V4__product_vo_and_new_tables.sql` | `sb_product` (타입 변환, 3개 컬럼 추가), `sb_currency`, `sb_supplier`, `sb_process_status` | `Product` (`stockStatus`, `restockDate`), `Currency`, `Supplier`, `ProcessStatus` |
| `after-migrate.sql` (콜백) | V4와 동일 DDL 반복 | ⚠️ 확인 필요: V4와 내용 중복, 의도적 멱등 실행인지 정리 필요 |

**⚠️ 확인 필요**: `after-migrate.sql`이 `api/src/main/resources/`에 위치하여 Flyway 콜백으로 실행되는지, 또는 별도 초기화 스크립트인지 Flyway 설정 파일에서 재확인 필요. (Flyway 기본 콜백 위치는 `db/migration/` 하위 또는 classpath 루트.)

---

*문서 생성: legacy-mapper | 2026-07-07*
