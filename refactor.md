# sbshop-agent 통합 리팩토링 계획

> 세 프로젝트(purchase-agent, buying-agent)의 기능을 sbshop-agent에 병합하여
> **신규 상품 등록 · 상품 수정(이미지 변경 포함) · 주문 관리**를 아우르는 통합 시스템을 구축한다.

---

## 1. 프로젝트 현황 요약

### 1.1 sbshop-agent (대상 프로젝트)

| 항목 | 내용 |
|---|---|
| 경로 | `/Users/jason/IdeaProjects/sbshop-agent` |
| 스택 | Spring Boot 3.2.3 / Java 17 / Gradle 멀티모듈 (core·infrastructure·api·worker) |
| DB | PostgreSQL 16 (Docker, Oracle Cloud) — cafe24 MariaDB에서 마이그레이션 완료 |
| 프론트엔드 | React 19 + Vite + TypeScript + react-query + react-table |
| 주문 관리 | **~85% 완료** — 쿠팡/스마트스토어/11번가/ESM+(G마켓·옥션) 주문 동기화, 발주확인/취소, 소싱, 배송, 통관, 정산 |
| 상품 기능 | **부분적** — `Product` 엔티티 + `ProductSyncService`(iHerb 재고 크롤링)만 존재. CRUD·목록·이미지·등록 UI 없음 |
| 아키텍처 | DDD 레이어드 (api → application → domain → infrastructure) + worker(스케줄러·IMAP) |
| 핵심 엔티티 | `sb_order`, `sb_order_line_item`, `sb_product`, `sb_market_credential`, `sb_market_registration`, `sb_fee_policy` |
| 마켓 연동 | `MarketOrderPort`(주문용) — 쿠팡/스마트스토어/11번가/ESM+ 어댑터 + `Cafe24TokenManager` |

### 1.2 purchase-agent (참고 프로젝트)

| 항목 | 내용 |
|---|---|
| 경로 | `/Users/jason/IdeaProjects/purchase-agent` |
| 스택 | Spring Boot 3.5.5 / Java 21 / 단일 모듈 / Thymeleaf / RabbitMQ / Spring Security |
| DB | MariaDB (cafe24 호스팅) — `product`, `product_channel_mapping`, `supplier`, `currency`, `category`, `sale_channel`, `process_status`, `users` |
| 핵심 기능 | ① 상품 목록 조회(DataTables, 필터·페이징) ② 재고/가격 일괄 변경(iHerb 크롤 + 수동 인라인 수정) ③ 소싱업체별 가격 일괄 업데이트 ④ 신규 상품 등록(iHerb 카테고리/링크 크롤 → ESM 이미지 업로드 → 마켓 등록) |
| 오케스트레이션 | RabbitMQ 14큐 + `process_status` 테이블로 배치 추적 |
| 가격 계산 | `Calculator` — iHerb 특화 (할인·쿠폰·배송비·마진·18.5% 수수료 → 100원 단위 올림) |
| 이미지 호스팅 | ESM Plus Selenium (`ai.esmplus.com`) |
| 마켓 API | Cafe24(실구현, FTP 이미지 업로드), Coupang(실구현), Smartstore(실구현), Elevenst(실구현) — 모두 시크릿 하드코딩 |

### 1.3 buying-agent (참고 프로젝트, 신규 버전)

| 항목 | 내용 |
|---|---|
| 경로 | `/Users/jason/IdeaProjects/buying-agent` |
| 스택 | Spring Boot 3.5.9 / Java 21 / Gradle 멀티모듈 (core·infrastructure·api·frontend) |
| DB | MariaDB (cafe24 호스팅) — `products`, `market_registrations`, `product_sources`, `sourcing_sites`, `sync_logs`, `margin_policies` (soft delete, VO @Embeddable, JSON 컬럼) |
| 핵심 기능 | ① 상품 수정(가격/재고 + 마켓 브로드캐스트) ② 이미지 업로드(Cloudflare R2) + 이미지 교체 ③ 상품 상세설명 HTML 이미지 교체(`HtmlImageReplacer`) ④ 신규 상품 등록(iHerb 소싱 → R2 업로드 → bulk 저장 → 마켓 publish) ⑤ 소싱 시스템(SourcingSite·ProductSource·MarginPolicy·SyncLog) |
| 이미지 호스팅 | **Cloudflare R2** (AWS S3 SDK v2 + Thumbnailator 리사이즈 1000×1000 JPG 80%) |
| 마켓 클라이언트 | `MarketClient` 인터페이스 + `MarketClientRouter` — Coupang publish **실구현**(카테고리 예측·메타·태그 생성), Cafe24 syncImagesAndHtml **실구현**(publish는 stub) |
| 프론트엔드 | React 19 + Vite + **Ant Design 6 + AG Grid** — ProductPage(1189줄), ProductRegisterPage(685줄) |
| 아키텍처 | DDD + UseCase 패턴 (`ProductCreateUseCase`, `ProductManageUseCase`, `ProductPublishUseCase`, `ProductSearchUseCase`) |

---

## 2. 핵심 의사결정 (확정)

| # | 의사결정 | 선택 | 근거 |
|---|---|---|---|
| 1 | Java/Spring Boot 버전 | **Java 21 + Spring Boot 3.5.x로 업그레이드** | 두 레거시 코드를 거의 그대로 포팅 가능 |
| 2 | 프론트엔드 UI 라이브러리 | **Ant Design + AG Grid 채택** (상품 화면) | buying-agent의 풍부한 UI 컴포넌트 재사용. 주문 화면은 기존 react-table 유지 |
| 3 | 배치 오케스트레이션 | **Spring @Async + process_status 테이블** | RabbitMQ 인프라 없이 경량 구현. DB 기반 상태 추적 |
| 4 | 상품 도메인 모델 | **VO @Embeddable 구조 도입** | buying-agent의 PriceInfo/LogisticsInfo/ProductSpec/SourcingInfo/ImageInfo 임베디드 |

---

## 3. 대상 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                        Frontend (React 19)                       │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐ │
│  │  주문 관리    │  │  상품 관리    │  │  신규 상품 등록         │ │
│  │  react-table │  │  Ant Design  │  │  Ant Design + AG Grid  │ │
│  │  + react-q   │  │  + AG Grid   │  │  (ProductRegisterPage) │ │
│  └──────┬───────┘  └──────┬───────┘  └───────────┬────────────┘ │
│         │    axios + react-query + SSE            │              │
└─────────┼─────────────────┼──────────────────────┼──────────────┘
          │                 │                      │
┌─────────▼─────────────────▼──────────────────────▼──────────────┐
│                    API Module (port 8080)                        │
│  ┌─────────────┐  ┌───────────────┐  ┌────────────────────────┐ │
│  │ Order       │  │ Product       │  │ ProductRegistration    │ │
│  │ Controller  │  │ Controller    │  │ Controller             │ │
│  └──────┬──────┘  └──────┬────────┘  └───────────┬────────────┘ │
│         │                │                       │              │
│  ┌──────▼────────────────▼───────────────────────▼────────────┐ │
│  │              Application Layer (core)                       │ │
│  │  ┌─────────────┐  ┌──────────────┐  ┌────────────────────┐ │ │
│  │  │ OrderService│  │ ProductManage│  │ ProductCreate      │ │ │
│  │  │ (기존 85%)  │  │ UseCase      │  │ UseCase            │ │ │
│  │  │             │  │ ProductSearch│  │ ProductPublish     │ │ │
│  │  │             │  │ UseCase      │  │ UseCase            │ │ │
│  │  │             │  │ BatchPrice   │  │ ProductSourcing    │ │ │
│  │  │             │  │ StockService │  │ UseCase            │ │ │
│  │  └──────┬──────┘  └──────┬───────┘  └─────────┬──────────┘ │ │
│  │         │                │                    │            │ │
│  │  ┌──────▼────────────────▼────────────────────▼──────────┐ │ │
│  │  │              Domain Layer (core)                       │ │ │
│  │  │  Order · Product(VO) · MarketRegistration             │ │ │
│  │  │  Supplier · Currency · ProcessStatus · FeePolicy      │ │ │
│  │  │  Ports: MarketOrderPort · MarketClient ·              │ │ │
│  │  │         ImageStorageClient · ProductStockCrawlerPort  │ │ │
│  │  └──────┬────────────────┬───────────────┬───────────────┘ │ │
│  └─────────┼────────────────┼───────────────┼─────────────────┘ │
└────────────┼────────────────┼───────────────┼──────────────────┘
             │                │               │
┌────────────▼────────────────▼───────────────▼──────────────────┐
│              Infrastructure Module                              │
│  ┌─────────────┐  ┌───────────────┐  ┌────────────────────────┐│
│  │ Order API   │  │ Market Client │  │ Image Storage          ││
│  │ Clients     │  │ (Product용)    │  │ Cloudflare R2          ││
│  │ (Coupang    │  │ Cafe24·Coupang│  │ (S3 SDK + Thumbnailator)││
│  │  SmartStore │  │  Smartstore   │  └────────────────────────┘│
│  │  Elevenst   │  │  Elevenst     │  ┌────────────────────────┐│
│  │  ESM+·Customs│  │ MarketClientRouter│  │ Sourcing Scrapers    ││
│  └─────────────┘  └───────────────┘  │ IherbScraperClient   ││
│                                        └────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  PostgreSQL Repositories (QueryDSL + JPA)                   ││
│  └─────────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│              Worker Module (port 8081)                           │
│  Scheduler (주문 동기화 · 통관 · IMAP) + @Async 배치 실행          │
└──────────────────────────────────────────────────────────────────┘
```

### 핵심 설계 원칙

1. **주문용 마켓 연동과 상품용 마켓 연동을 분리**
   - `MarketOrderPort` (기존) → 주문 동기화/발주/배송
   - `MarketClient` (신규, buying-agent 포팅) → 상품 등록/수정/이미지/가격·재고 동기화
   - 두 포트는 동일한 `MarketCredential`을 공유

2. **상품 도메인은 VO 임베디드 구조**
   - `Product` 엔티티 내에 `PriceInfo`, `LogisticsInfo`, `ProductSpec`, `SourcingInfo`, `ImageInfo`를 `@Embeddable`로 구성
   - 기존 flat 컬럼에서 VO 매핑으로 전환 (스키마 변경 최소화 — 컬럼명은 그대로 유지하거나 마이그레이션)

3. **배치 처리는 Spring @Async + process_status**
   - RabbitMQ 없이 `@Async` 스레드풀 + `process_status` 테이블로 배치 상태 추적
   - 일괄 가격/재고 변경, 신규 상품 등록 등에 적용

4. **이미지 호스팅은 Cloudflare R2 통합**
   - `R2ImageStorageClient` (S3 SDK v2) + `Thumbnailator` 리사이즈
   - ESM Plus Selenium 방식(purchase-agent)은 폐기

5. **프론트엔드는 하이브리드**
   - 주문 관리: 기존 react-table + react-query 유지
   - 상품 관리/등록: Ant Design + AG Grid (buying-agent 포팅)
   - 두 UI 라이브러리가 공존 (점진적 통합 가능)

---

## 4. DB 스키마 통합 계획

### 4.1 기존 sbshop-agent 스키마 (PostgreSQL)

| 테이블 | 설명 |
|---|---|
| `sb_product` | 상품 (flat 컬럼: sbCode, vendor, brand, costPrice, salePrice, stock, sourceImages, hostedImages, detailHtml, ...) |
| `sb_order` | 주문 |
| `sb_order_line_item` | 주문 라인아이템 (소싱·배송·정산 VO 임베디드) |
| `sb_market_credential` | 마켓별 API 인증정보 |
| `sb_market_registration` | 상품-마켓 매핑 (marketIdentifiers JSON) |
| `sb_fee_policy` | 마켓별 수수료 정책 |

### 4.2 신규 추가/변경 스키마

| 테이블 | 출처 | 설명 |
|---|---|---|
| `sb_product` (변경) | buying-agent VO 구조 | flat 컬럼 → VO 매핑. `source_images`/`hosted_images`를 JSON 배열로 변경(hypersistence-utils) |
| `sb_supplier` (신규) | purchase-agent | 소싱업체 (supplier_code, supplier_name, currency_code FK) |
| `sb_currency` (신규) | purchase-agent | 환율 (currency_code, exchange_rate) |
| `sb_process_status` (신규) | purchase-agent | 배치 작업 추적 (batchId, productCode, jobType, step, status, message) |
| `sb_category` (신규, 옵션) | purchase-agent | 카테고리 트리 (self-referential) |
| `sb_margin_policy` (신규, 옵션) | buying-agent | 마진 정책 (exchange_rate, shipping_fee, margin_rate, commission_rate) |

### 4.3 컬럼 매핑 (sb_product VO 전환)

| VO | 컬럼 | 기존 | 변경 |
|---|---|---|---|
| `PriceInfo` | cost_price, exchange_rate, margin_rate, sale_price | flat | @Embedded |
| `LogisticsInfo` | stock, weight, bundle_quantity | flat | @Embedded |
| `ProductSpec` | barcode, capacity, measure_unit | flat | @Embedded |
| `SourcingInfo` | vendor, sourcing_url, manufacturer, origin, hs_code | flat | @Embedded |
| `ImageInfo` | source_images, hosted_images | varchar (문자열 리스트) | **JSON 배열** (hypersistence-utils `@JdbcTypeCode(SqlTypes.JSON)`) |

> **주의**: `source_images`/`hosted_images`를 JSON으로 변경하려면 기존 데이터 마이그레이션이 필요. 기존 값이 파이프(`|`) 또는 콤마 구분 문자열인지 확인 후 JSON 배열로 변환하는 Flyway 마이그레이션 스크립트 작성.

### 4.4 Postgres용 하이버네이트 설정

- `hypersistence-utils-hibernate-63` 의존성 추가 (JSON 컬럼 지원)
- `hibernate.dialect: PostgreSQLDialect` 유지
- `ddl-auto: none` 유지, Flyway 활성화하여 스키마 변경 관리

---

## 5. 리팩토링 Phase 계획

### Phase 0: 기반 환경 업그레이드 및 의존성 설정

**목표**: sbshop-agent를 Java 21 + Spring Boot 3.5.x로 올리고, 병합에 필요한 의존성을 추가한다.

**작업 범위**:
1. `backend/build.gradle` — Java toolchain 17 → 21, Spring Boot 3.2.3 → 3.5.x, dependency-management 버전 업
2. 각 모듈(`core`, `infrastructure`, `api`, `worker`) `build.gradle` 업데이트
3. 신규 의존성 추가:
   - `software.amazon.awssdk:s3` (Cloudflare R2 — infrastructure)
   - `net.coobird:thumbnailator` (이미지 리사이즈 — infrastructure, api)
   - `io.hypersistence:hypersistence-utils-hibernate-63` (JSON 컬럼 — core)
   - `okhttp3` (이미지 다운로드 — infrastructure)
   - `opencsv` (CSV — infrastructure, 옵션)
   - 프론트엔드: `antd`, `@ant-design/icons`, `ag-grid-community`, `ag-grid-react` (frontend package.json)
4. 기존 주문 관리 기능 호환성 검증 (버전 업 후 컴파일 + 기본 동작 확인)
5. `docker-compose.yml` — 필요 시 Postgres 설정 조정

**검증 기준**: 기존 주문 동기화 API가 정상 동작. 프론트엔드 빌드 성공.

---

### Phase 1: 상품 도메인 모델 리팩토링

**목표**: `Product` 엔티티를 VO 임베디드 구조로 전환하고, 신규 엔티티(Supplier, Currency, ProcessStatus)를 추가한다.

**작업 범위**:
1. **VO 클래스 이식** (buying-agent → sbshop-agent core):
   - `core/domain/product/vo/PriceInfo.java` — costPrice, exchangeRate, marginRate, salePrice, deliveryFee
   - `core/domain/product/vo/LogisticsInfo.java` — stock, weight, bundleQuantity
   - `core/domain/product/vo/ProductSpec.java` — barcode, capacity, measureUnit
   - `core/domain/product/vo/SourcingInfo.java` — vendor(VendorType), sourcingUrl, manufacturer, origin, hsCode
   - `core/domain/product/vo/ImageInfo.java` — sourceImages(JSON), hostedImages(JSON)
   - 기존 초안 VO(`MediaInfo`, `ProductName` 등)는 통합 또는 폐기
2. **Product 엔티티 리팩토링**:
   - flat 컬럼 → `@Embedded` VO 매핑 (`backend/core/.../domain/product/Product.java`)
   - `source_images`/`hosted_images` → `@JdbcTypeCode(SqlTypes.JSON)` JSON 배열
   - 도메인 메서드 이식: `Product.create()`, `Product.update()`, `buildDetailHtml()`, `generateTemplateHtml()` (buying-agent `Product.java:119-307`)
   - `updateStockStatus`, `updateCostPrice`, `updateSourcingStock` 등 기존 메서드 유지
3. **신규 엔티티 추가**:
   - `core/domain/supplier/Supplier.java` — supplierCode(PK), supplierName, currency(FK)
   - `core/domain/supplier/Currency.java` — currencyCode(PK), exchangeRate
   - `core/domain/process/ProcessStatus.java` — batchId, productCode, jobType(enum), step, status, message, details, startedAt, updatedAt
   - `core/domain/process/enums/JobType.java` — CRAWL_AND_UPDATE_PRICE_STOCK, MANUAL_UPDATE_PRICE_STOCK, MANUAL_UPDATE_ALL_FIELDS, REGISTER_PRODUCT
   - `core/domain/process/enums/ProcessStep.java` — INITIALIZE_BATCH, UPDATE_PRODUCT_CRAWL, UPDATE_PRODUCT_SAVE, UPDATE_PRODUCT_PUBLISH, UPDATE_PRODUCT_ERROR
4. **Repository 추가**:
   - `SupplierRepository`, `CurrencyRepository`, `ProcessStatusRepository` (+ Custom 구현체)
   - `ProductRepository` 확장 — `searchByNameOrSku`, `findBySku`, `findBySkuIn`, `findMaxSkuByPrefix`, `findAllByIds`, `findBySupplier` 등 (buying-agent + purchase-agent 쿼리 참고)
5. **Flyway 마이그레이션**:
   - `V4__product_vo_and_new_tables.sql` — sb_product 컬럼 조정(JSON 변환), sb_supplier/sb_currency/sb_process_status 테이블 생성
   - 기존 데이터 JSON 변환 스크립트 포함
   - Flyway 활성화 (`spring.flyway.enabled: true`)
6. **enum 정합성**:
   - `VendorType` (IHB/AMZ/FTN/COK/OCD/TES/VTB) 유지
   - `ProductCategory` 유지 (SUPPLEMENT/FOOD/COSMETICS/UNKNOWN)
   - `MeasureUnit` 유지 (TABLET/CAPSULE/EA 등)
   - `StockStatus` 확장 (IN_STOCK/OUT_OF_STOCK → LOW_STOCK 추가 옵션)

**참고 소스**:
- buying-agent: `core/.../product/model/Product.java`, `core/.../vo/*.java`
- purchase-agent: `entity/Supplier.java`, `entity/Currency.java`, `entity/ProcessStatus.java`

**검증 기준**: JPA 엔티티 매핑 정상, Flyway 마이그레이션 통과, 기존 주문에서 Product 참조 호환.

---

### Phase 2: Cloudflare R2 이미지 호스팅 통합

**목표**: 이미지 업로드(파일/URL), 리사이즈, R2 저장, 상품 이미지 연관 기능을 구현한다.

**작업 범위**:
1. **R2 설정** (buying-agent 포팅):
   - `infrastructure/client/cloudflare/config/R2Properties.java` — `cloud.cloudflare.r2.*` 바인딩
   - `infrastructure/client/cloudflare/config/R2Config.java` — S3Client 빈 (R2 endpoint, pathStyleAccess)
   - `application.yml` — R2 설정 (endpoint, access-key, secret-key, bucket, public-url)을 env 변수로 외부화
2. **이미지 스토리지 클라이언트**:
   - `infrastructure/client/cloudflare/R2ImageStorageClient.java` — `ImageStorageClient` 포트 구현, `uploadImages(List<ImageUploadFile>)` → `Map<filename, publicUrl>`
   - UUID 파일명 생성, `s3Client.putObject`
3. **이미지 다운로드 서비스**:
   - `infrastructure/client/cloudflare/ImageDownloadService.java` — `ImageDownloadClient` 포트 구현, OkHttp로 URL 이미지 다운로드 + Thumbnailator 리사이즈(1000×1000, JPG 80%)
   - `infrastructure/client/image/ImageDownloader.java` — 소스 이미지 일괄 다운로드
4. **포트 인터페이스** (core):
   - `core/domain/product/client/ImageStorageClient.java` — `uploadImages`
   - `core/domain/product/client/ImageDownloadClient.java` — `downloadAll(urls)`
5. **HTML 이미지 교체 컴포넌트**:
   - `core/domain/product/component/HtmlImageReplacer.java` — `replaceImagesBySku(html, sku, hostedImages)` (buying-agent 포팅, regex 기반)
   - `infrastructure/.../common/util/HtmlImageExtractor.java` — HTML에서 이미지 URL 추출
6. **API 엔드포인트** (api 모듈):
   - `PUT /api/v1/products/{id}/images` (multipart) — 파일 업로드 → R2 → 상품 이미지 교체 + HTML 수정
   - `PUT /api/v1/products/{id}/images/by-url` — URL 기반 업로드
   - `GET /api/v1/products/{id}/images/crawl` — 소스(iHerb)에서 이미지 크롤

**참고 소스**:
- buying-agent: `infrastructure/.../cloudflare/*`, `infrastructure/.../image/ImageDownloader.java`, `core/.../product/component/HtmlImageReplacer.java`

**검증 기준**: 이미지 업로드 → R2 URL 반환 → 상품 hostedImages 갱신 → detailHtml 이미지 교체 확인.

---

### Phase 3: 상품 CRUD 및 관리 API

**목표**: 상품 목록 조회, 상세, 가격/재고 수정, 이미지 관리 API를 구현한다.

**작업 범위**:
1. **ProductController** (api 모듈, buying-agent 포팅):
   - `GET /api/v1/products` — 목록 조회 (페이징, 검색: name/sku, 필터: vendor/category/supplier)
   - `GET /api/v1/products/{id}` — 상품 상세
   - `PUT /api/v1/products/{id}/price-stock` — 가격/재고 수정 + 마켓 브로드캐스트
   - `PUT /api/v1/products/{id}` — 전체 필드 수정
   - `DELETE /api/v1/products/{id}` — 삭제
2. **UseCase 이식** (core/application/product):
   - `ProductSearchUseCase` — 목록 검색 (QueryDSL 동적 쿼리: keyword, vendor, category, supplier 필터)
   - `ProductManageUseCase` — 상품 수정 (가격/재고, 이미지+HTML, 전체 필드) + 마켓 브로드캐스트
   - `ProductReader` / `ProductWriter` 컴포넌트 인터페이스 + 구현체
3. **DTO** (api/dto/product):
   - `ProductListResponse`, `ProductDetailResponse`, `PriceStockUpdateRequest`, `ProductUpdateRequest`
   - purchase-agent의 `ProductDto` 필드 참고 (channel mapping 정보 포함)
4. **QueryDSL 동적 검색**:
   - `ProductRepositoryImpl` 확장 — `searchProducts(condition)` 동적 BooleanExpression (keywordContains, vendorEq, categoryEq, supplierEq)
   - purchase-agent의 `findProductsWithFilters` 참고 (channel-id NULL 필터 포함)
5. **MarketRegistration 연동**:
   - 상품 목록에 마켓별 등록 여부/마켓 상품ID 표시
   - `GET /api/v1/products/{id}/markets` — 상품의 마켓 등록 목록
   - `GET /api/v1/products/{id}/markets/{marketType}/local` — 마켓별 로컬 데이터
   - `POST /api/v1/products/{id}/markets/{marketType}/sync` — 마켓 실시간 동기화

**참고 소스**:
- buying-agent: `core/.../application/product/ProductManageUseCase.java`, `ProductSearchUseCase.java`, `api/.../product/controller/ProductController.java`
- purchase-agent: `service/products/ProductService.java` (목록 쿼리), `repository/ProductRepository.java` (필터 쿼리)

**검증 기준**: 상품 목록/상세/수정 API 정상. 가격/재고 수정 시 DB + 마켓 브로드캐스트 동작.

---

### Phase 4: 마켓 클라이언트 레이어 (상품용)

**목표**: `MarketClient` 인터페이스와 마켓별 어댑터를 포팅하여 상품 등록/수정/동기화를 지원한다.

**작업 범위**:
1. **MarketClient 포트 + 라우터** (core):
   - `core/domain/market/client/MarketClient.java` — `publish`, `syncPriceAndStock`, `syncImagesAndHtml`, `extractMarketItem`, `deleteMarketProduct`, `fetchAllMarketItemIds`
   - `core/domain/market/client/MarketClientRouter.java` — marketType → MarketClient 라우팅
   - `core/domain/market/client/dto/MarketItemInfo.java` — 마켓 상품 정보 ACL DTO
2. **Cafe24MarketClient** (infrastructure, buying-agent 포팅):
   - `syncImagesAndHtml` — 실구현 (description PUT, 이미지 DELETE+POST, 외부 이미지 Base64)
   - `syncPriceAndStock` — 실구현으로 전환 (buying-agent에서 commented out → 활성화)
   - `publish` — stub → 실구현 (purchase-agent의 `CafeApiService.registerProduct` 참고: FTP 이미지 업로드 + POST /admin/products)
   - `Cafe24RestClient`, `Cafe24DataMapper`, `Cafe24ProductParser`, `Cafe24Properties` 포팅
   - 기존 `Cafe24TokenManager` 재사용 (sbshop-agent에 이미 존재)
3. **CoupangMarketClient** (infrastructure, buying-agent 포팅):
   - `publish` — 실구현 (카테고리 예측, 메타 조회, 태그 생성, 상품 등록)
   - `CoupangCategoryPredictor`, `CoupangMetaService` (Redis 캐avage), `CoupangSearchTagGenerator`
   - `syncImagesAndHtml` — 실구현
   - `syncPriceAndStock` — commented out → 활성화
   - `CoupangRestClient` (HMAC 서명) — sbshop-agent의 `CoupangOrderApiClient`와 중복 제거/통합
4. **SmartstoreMarketClient** (infrastructure):
   - buying-agent에 없음 → purchase-agent의 `SmartstoreApiService` 참고하여 신규 구현
   - `publish`, `syncPriceAndStock` (Smartstore OAuth2 + BCrypt 토큰 교환)
5. **ElevenstMarketClient** (infrastructure):
   - purchase-agent의 `ElevenstApiService` 참고 (XML over REST)
   - `publish`, `syncPriceAndStock`
6. **기존 MarketOrderPort와의 관계 정리**:
   - `MarketOrderPort` (주문용)와 `MarketClient` (상품용)는 별개 포트로 공존
   - 동일한 `MarketCredential` 엔티티 공유
   - 중복 HTTP 클라이언트 코드 정리 (Coupang HMAC 서명 등)

**참고 소스**:
- buying-agent: `infrastructure/.../coupang/*`, `infrastructure/.../cafe24/*`, `core/.../market/client/*`
- purchase-agent: `external/cafe/CafeApiService.java`, `external/coupang/*`, `external/smartstore/*`, `external/elevenst/*`

**검증 기준**: 각 마켓에 대해 상품 가격/재고/이미지 동기화 API 동작. Coupang 신규 상품 publish 성공.

---

### Phase 5: 신규 상품 등록 (buying-agent 신규 버전)

**목표**: iHerb 소싱 → R2 이미지 업로드 → bulk 저장 → 마켓 publish 파이프라인을 구현한다.

**작업 범위**:
1. **소싱 스크래퍼 강화**:
   - `infrastructure/client/sourcing/IherbScraperClient.java` — 기존(재고 크롤링) + buying-agent의 상품 정보 크롤링 통합
   - `IherbProductCrawler.crawlProductAsJson(prodId)` (purchase-agent) — 카탈로그 JSON 파싱 (이름, 가격, 이미지, 카테고리, 재고)
   - `IherbCategoryCrawler` (purchase-agent, Playwright) — 카테고리별 상품 ID 수집 (옵션)
   - 랜덤 User-Agent, 403 재시도, 딜레이 (anti-bot)
2. **ProductSourcingUseCase** (core):
   - `sourceFromIherb(urls)` — URL → 스크래핑 → `ScrapedProductDto` → `ScrapedDataProcessor` 정제
   - `ScrapedDataProcessor` — 데이터 정제, 파생 필드 계산
   - 소싱 사이트 인터페이스: `SourcingAgent`, `SourcingAgentFactory` (buying-agent)
3. **ProductCreateUseCase** (core):
   - `createBulk(commands)` — SKU 생성(yyyyMMdd+IHB+NNN), R2 이미지 업로드, `Product.create()` 도메인 팩토리, bulk 저장
   - `Product.create()` — 카테고리 추정, HS코드 자동 할당, 마켓명 조합, searchKeywords 생성, detailHtml 생성(sb_top/이미지/sb_bottom 템플릿)
   - `ProductSaveRequest` DTO → `ProductCreateCommand` 변환
4. **ProductPublishUseCase** (core):
   - `publishToMarket(productId, marketType)` — 상품 검증(sanitize/validate) → `MarketClient.publish()` → `MarketRegistration` 저장
   - `ProductSanitizer`, `ProductValidator` 컴포넌트
5. **API 엔드포인트**:
   - `POST /api/v1/sourcing/iherb` — iHerb URL 소싱
   - `POST /api/v1/products/bulk` — bulk 저장
   - `POST /api/v1/products/{id}/markets/{marketType}` — 마켓 publish
6. **가격 계산 엔진** (purchase-agent `Calculator` 참고):
   - `core/domain/product/service/MarginCalculator` — iHerb 할인/쿠폰/배송비/마진/수수료 → 판매가 계산
   - 100원 단위 올림, 최소 마진가 보장 로직
   - 향후 타 소싱업체 확장 고려 (전략 패턴)

**참고 소스**:
- buying-agent: `core/.../application/product/ProductCreateUseCase.java`, `ProductPublishUseCase.java`, `core/.../application/sourcing/*`
- purchase-agent: `external/iherb/IherbProductCrawler.java`, `IherbCategoryCrawler.java`, `util/Calculator.java`, `service/product_registration/*`

**검증 기준**: iHerb URL 입력 → 스크래핑 → R2 이미지 업로드 → DB 저장 → Coupang publish 성공.

---

### Phase 6: 배치 가격/재고 일괄 변경 (purchase-agent 기능)

**목표**: 다중 선택 상품의 iHerb 크롤 기반 가격/재고 일괄 변경, 수동 일괄 수정, 소싱업체별 일괄 업데이트를 구현한다.

**작업 범위**:
1. **ProcessStatus 서비스** (core):
   - `ProcessStatusService` — batchId 생성, `process_status` 행 생성/갱신, 단계별 상태 추적
   - `mergeChannelResult` — 마켓별 결과 JSON 병합
2. **BatchPriceStockService** (core/application/product):
   - `processBatchUpdate(jobType, requests, params)` — 배치 시작 → @Async 비동기 처리
   - `crawlAndUpdatePriceStock(productDto, marginRate, couponRate, minMarginPrice)` — iHerb 크롤 → 가격 계산 → DB 저장 → 마켓 브로드캐스트
   - `manualUpdatePriceStock(requests)` — 수동 수정 (변경된 필드만 마켓 동기화)
   - `manualUpdateAllFields(requests)` — 전체 필드 수정
   - `makeRequestsBySupplier(supplierCode)` — 소싱업체별 전체 상품 조회 → 배치 요청 생성
3. **@Async 설정**:
   - `AsyncConfig` — 스레드풀 설정 (core 2 / max 5 / queue 100)
   - `@EnableAsync` 활성화
   - 큐 대신 `LinkedBlockingQueue` + `@Async` 조합 (또는 단순 @Async 병렬 처리)
4. **API 엔드포인트**:
   - `POST /api/v1/products/batch/crawl-and-update` — 크롤 기반 일괄 가격/재고 변경 (marginRate, couponRate, minMarginPrice 파라미터)
   - `POST /api/v1/products/batch/manual-update-price-stock` — 수동 일괄 가격/재고 수정
   - `POST /api/v1/products/batch/manual-update-all` — 수동 전체 필드 수정
   - `POST /api/v1/products/batch/by-supplier` — 소싱업체별 일괄 업데이트 (supplierCode 파라미터)
   - `GET /api/v1/products/batch/status/{batchId}` — 배치 진행 상태 조회
   - `GET /api/v1/products/batch/status` — 전체 배치 목록
5. **소싱업체 관리** (옵션):
   - `GET/POST/PUT/DELETE /api/v1/suppliers` — Supplier CRUD
   - `GET/POST/PUT/DELETE /api/v1/currencies` — Currency CRUD
6. **iHerb 외 소싱업체 확장 준비**:
   - `SourcingAgent` 인터페이스 + `SourcingAgentFactory` — URL 패턴별 에이전트 라우팅
   - 현재는 IHB만 구현, 향후 AMZ/FTN 등 추가 가능

**참고 소스**:
- purchase-agent: `service/products/ProductService.java` (processProductUpdate, crawlAndUpdatePriceStock), `service/autoupdate/ProductUpdateConsumer.java`, `controller/AutoUpdateController.java`
- purchase-agent: `util/Calculator.java` (가격 계산 공식)

**검증 기준**: 다중 상품 선택 → 크롤 기반 가격/재고 일괄 변경 → process_status 진행 추적 → 마켓 동기화 완료.

---

### Phase 7: 프론트엔드 통합

**목표**: Ant Design + AG Grid 기반 상품 관리/등록 화면을 sbshop-agent 프론트엔드에 통합한다.

**작업 범위**:
1. **의존성 추가** (frontend/package.json):
   - `antd`, `@ant-design/icons`, `ag-grid-community`, `ag-grid-react`
   - 기존 react-query/react-table 유지 (주문 화면용)
2. **API 레이어** (frontend/src/api):
   - `productApi.ts` — 상품 CRUD, 가격/재고, 이미지 업로드, 마켓 동기화 (buying-agent `productApi.js` 포팅 → TypeScript)
   - `sourcingApi.ts` — iHerb 소싱, bulk 저장, 마켓 publish
   - `batchApi.ts` — 배치 작업 요청/상태 조회
   - 기존 `orderApi.ts` 유지
3. **페이지 이식** (frontend/src/pages):
   - `ProductPage.tsx` — AG Grid 상품 목록 (이미지 썸네일, sku, name, 가격, 재고, 마켓 코드, 상세HTML 보기, 가격/재고 관리 모달, 이미지 관리 모달) (buying-agent `ProductPage.jsx:1189` 포팅)
   - `ProductRegisterPage.tsx` — iHerb URL 소싱 → 리뷰/편집 → bulk 저장 (buying-agent `ProductRegisterPage.jsx:685` 포팅)
   - `BatchUpdatePage.tsx` — 배치 가격/재고 일괄 변경 (purchase-agent `auto-update.html` + `list.html` 배치 기능 참고, React 재작성)
   - `ProcessStatusPage.tsx` — 배치 진행 현황 (purchase-agent `process-status.html` 참고)
4. **레이아웃/네비게이션**:
   - `MainLayout.tsx` — 사이드바에 상품 관리 메뉴 추가 (상품 목록, 신규 등록, 배치 업데이트, 진행 현황)
   - 기존 Dashboard/OrderGrid/Settings 메뉴 유지
5. **Ant Design ConfigProvider**:
   - `App.tsx` — `<ConfigProvider theme={{ token: { colorPrimary: '#000' } }}>` 래핑
   - 테마 통일 (흑백 심플 테마, buying-agent 스타일)
6. **라우팅**:
   - `/products` → ProductPage
   - `/register` → ProductRegisterPage
   - `/batch` → BatchUpdatePage
   - `/process-status` → ProcessStatusPage
   - 기존 `/`, `/orders`, `/settings` 유지
7. **vite.config.ts** — proxy 설정 유지 (`/api` → localhost:8080), baseURL `/sbshop-agent` 유지

**참고 소스**:
- buying-agent: `frontend/src/pages/ProductPage.jsx`, `ProductRegisterPage.jsx`, `frontend/src/api/productApi.js`, `sourcingApi.js`
- purchase-agent: `templates/pages/auto-update.html`, `products/list.html`, `process-status.html`

**검증 기준**: 상품 목록/등록/배치 화면이 정상 렌더링. API 호출 정상. 네비게이션 통합.

---

### Phase 8: 시크릿 외부화 및 보안 강화

**목표**: 하드코딩된 시크릿을 env로 이관하고, 인증/인가 기반을 마련한다.

**작업 범위**:
1. **시크릿 외부화**:
   - Cafe24: mall-id, client-id, client-secret, redirect-uri → env (`CAFE24_*`)
   - Coupang: vendor-id, access-key, secret-key → env (`COUPANG_*`) — sbshop-agent `.env.example`에 이미 존재
   - Smartstore: client-id, client-secret → env (`SMARTSTORE_*`) — 이미 존재
   - Elevenst: api-key → env (`ELEVENST_*`) — 이미 존재
   - Cloudflare R2: endpoint, access-key, secret-key, bucket, public-url → env (`CLOUDFLARE_R2_*`)
   - ESM Plus: user-id, password → env (`ESMPLUS_*`) — 이미 존재
   - 모든 `@Value` / `@ConfigurationProperties`로 바인딩
   - `token_info.json`, `refresh_token.txt` — `.gitignore` 추가, env 경로로 이동
2. **application.yml 정리**:
   - 모든 시크릿을 `${ENV_VAR}` 참조로 변경
   - `.env.example` 업데이트 (모든 신규 env 변수 문서화)
   - `application.yml`에 하드코딩된 값 제거
3. **Spring Security (옵션)**:
   - purchase-agent의 SecurityConfig 참고 (form login + Kakao OAuth2)
   - 또는 간단한 토큰 기반 인증 (Phase 8에서 기반만 마련, 상세는 별도)
   - CORS 설정 유지 (개발 환경)
4. **Flyway 정식 활성화**:
   - `spring.flyway.enabled: true`
   - `spring.jpa.hibernate.ddl-auto: none` (모든 모듈)
   - worker 모듈의 `ddl-auto: update` 제거
   - 버전별 마이그레이션 스크립트 관리

**검증 기준**: 소스코드에 시크릿 없음. env만으로 실행 가능. Flyway 마이그레이션 자동 실행.

---

### Phase 9: 테스트 및 배포

**목표**: 통합 테스트를 작성하고, Docker 배포 설정을 완료한다.

**작업 범위**:
1. **통합 테스트**:
   - 상품 CRUD 테스트
   - 이미지 업로드/R2 연동 테스트 (mock S3)
   - 배치 가격/재고 변경 테스트 (mock iHerb 크롤)
   - 신규 상품 등록 파이프라인 테스트
   - 마켓 클라이언트 동기화 테스트 (mock API)
2. **Docker 설정 업데이트**:
   - `Dockerfile.backend` — Java 21 베이스 이미지
   - `docker-compose.yml` — 신규 env 변수 추가, Redis 컨테이너 추가(Coupang 메타 캐시용, 옵션)
   - `Dockerfile.frontend` — Ant Design 번들 크기 대응
3. **배포 스크립트**:
   - `deploy-sbshop.sh` 업데이트
   - `start.sh` — worker 스케줄러 활성화 (주문 동기화 cron)
4. **스케줄러 활성화**:
   - `OrderSyncScheduler` — 주석 해제 (주문 동기화 cron)
   - 상품 재고 동기화 스케줄러 추가 (iHerb 주기적 크롤)
5. **문서 업데이트**:
   - `docs/api/product/README.md` — 상품 API 문서
   - `docs/api/sourcing/README.md` — 소싱 API 문서
   - `docs/domain/product-refactor.md` — 상품 도메인 설계 문서

**검증 기준**: 테스트 통과. Docker 빌드/실행 성공. 스케줄러 정상 동작.

---

## 6. Phase 간 의존관계

```
Phase 0 (기반 업그레이드)
  │
  ├──▶ Phase 1 (상품 도메인 모델)
  │      │
  │      ├──▶ Phase 2 (R2 이미지 호스팅)
  │      │      │
  │      │      └──▶ Phase 3 (상품 CRUD API) ──▶ Phase 4 (마켓 클라이언트)
  │      │                                              │
  │      ├──▶ Phase 5 (신규 상품 등록) ◀─────────────────┘
  │      │
  │      └──▶ Phase 6 (배치 가격/재고)
  │
  ├──▶ Phase 7 (프론트엔드) ◀── Phase 3, 5, 6 완료 후
  │
  ├──▶ Phase 8 (시크릿/보안) — 언제나 병행 가능
  │
  └──▶ Phase 9 (테스트/배포) — 최종
```

- Phase 0 → Phase 1은 선형 의존
- Phase 2, 3, 4는 순차 진행 권장 (R2 → CRUD → 마켓 클라이언트)
- Phase 5, 6은 Phase 4 완료 후 진행 (마켓 클라이언트 필요)
- Phase 7(프론트엔드)은 Phase 3, 5, 6이 어느 정도 진행된 후 병행 가능
- Phase 8은 전 Phase에 걸쳐 병행 가능
- Phase 9는 최종 통합 단계

---

## 7. 리스크 및 고려사항

### 7.1 DB 마이그레이션 리스크
- **`source_images`/`hosted_images` JSON 변환**: 기존 파이프/콤마 구분 문자열을 JSON 배열로 변환하는 마이그레이션 스크립트 필요. 기존 데이터 형식 사전 확인 필수.
- **`ddl-auto: update` (worker)**: 현재 worker가 런타임에 스키마를 변경함. Flyway 활성화 전 반드시 `none`으로 전환해야 충돌 방지.
- **PostgreSQL vs MariaDB 문법**: 기존 V1 마이그레이션이 MySQL 문법(`AUTO_INCREMENT`, `DATETIME(6)`). Postgres 호환 마이그레이션으로 재작성 고려.

### 7.2 코드 중복/충돌
- **Coupang HTTP 클라이언트**: sbshop-agent의 `CoupangOrderApiClient`(주문용)와 buying-agent의 `CoupangRestClient`(상품용)가 HMAC 서명 로직 중복. 공통 유틸로 추출.
- **Cafe24 토큰 관리**: sbshop-agent의 `Cafe24TokenManager`와 buying-agent의 `Cafe24TokenManager`가 유사. sbshop-agent 것을 기준으로 통일.
- **iHerb 스크래퍼**: sbshop-agent의 `IherbScraperClient`(재고 크롤링)와 purchase-agent의 `IherbProductCrawler`(상품 정보 크롤링) 통합.

### 7.3 마켓 API 실구현 갭
- **Cafe24 publish**: buying-agent에서 stub. purchase-agent의 `CafeApiService.registerProduct`(FTP 업로드 + POST)를 참고해 실구현 필요.
- **Coupang/Smartstore syncPriceAndStock**: buying-agent에서 commented out. 활성화 필요.
- **Smartstore/Elevenst MarketClient**: buying-agent에 없음. purchase-agent 코드 참고 신규 구현.

### 7.4 프론트엔드 라이브러리 공존
- react-table(주문) + AG Grid(상품) 공존 → 번들 크기 증가. code splitting으로 대응.
- Ant Design + 기존 컴포넌트 스타일 충돌 주의 (CSS 격리).

### 7.5 비즈니스 로직 보존
- **가격 계산 공식**: purchase-agent의 `Calculator` (18.5% 수수료, 배송비 6000원 if <40000, 100원 올림) 정확히 포팅. 공식 변경 시 비즈니스 영향 확인 필수.
- **iHerb 재고 매핑**: `isAvailableToPurchase ? 500 : 0` — 500이라는 매직넘버 확인 필요.
- **상품 코드 규칙**: `yyMMdd+IHB+NNN` (purchase-agent) vs `yyyyMMdd+IHB+NNN` (buying-agent) — 통일 필요.

### 7.6 보안
- 하드코딩된 시크릿 다수 (Cafe24, Coupang, Smartstore, Elevenst, ESM, R2). Phase 8에서 일괄 외부화.
- `refresh_token.txt`, `token_info.json`가 git에 커밋되어 있음 → `.gitignore` 추가 후 히스토리 정리 고려.
