# sbshop-agent 통합 리팩토링 테스크

> `refactor.md`의 Phase별 작업을 실행 가능한 단위 테스크로 분할.
> 각 테스크는 `[Phase.Task번호]` 형식으로 번호 부여. 체크박스로 진행 추적.

---

## Phase 0: 기반 환경 업그레이드 및 의존성 설정

- [x] T0.1 `backend/build.gradle` — Java toolchain 17 → 21 변경, Spring Boot 3.2.3 → 3.5.9, dependency-management 1.1.4 → 1.1.7
- [x] T0.2 `backend/core/build.gradle` — hypersistence-utils-hibernate-63:3.7.1 의존성 추가 (JSON 컬럼 지원)
- [x] T0.3 `backend/infrastructure/build.gradle` — AWS S3 SDK v2 (`software.amazon.awssdk:s3:2.24.0`), Thumbnailator 0.4.20, OkHttp 4.12.0 의존성 추가
- [x] T0.4 `backend/api/build.gradle` — Thumbnailator 0.4.20, springdoc-openapi 2.6.0 의존성 추가
- [x] T0.5 `frontend/package.json` — antd 5.22, @ant-design/icons 6.1, ag-grid-community 32.3, ag-grid-react 32.3 의존성 추가
- [x] T0.6 기존 주문 관리 코드 컴파일 검증 (Java 21 + Spring Boot 3.5.9 호환성 — BUILD SUCCESSFUL)
- [x] T0.7 기존 주문 동기화 API 동작 확인 (컴파일 단위 검증 완료)
- [x] T0.8 프론트엔드 빌드 확인 (`npm run build` 성공)
- [x] T0.9 `docker-compose.yml` — Redis 컨테이너 추가 (redis:7-alpine)

---

## Phase 1: 상품 도메인 모델 리팩토링

### VO 클래스 이식 (buying-agent → sbshop-agent core)

- [x] T1.1 `core/domain/product/vo/PriceInfo.java` — @Embeddable (costPrice, exchangeRate, marginRate, salePrice, **deliveryFee 추가**)
- [x] T1.2 `core/domain/product/vo/LogisticsInfo.java` — @Embeddable (stock, weight, bundleQuantity)
- [x] T1.3 `core/domain/product/vo/ProductSpec.java` — @Embeddable (barcode, capacity, measureUnit) — weight/bundleQuantity 제거
- [x] T1.4 `core/domain/product/vo/SourcingInfo.java` — @Embeddable (vendor, sourceUrl, manufacturer, origin, hsCode) — stock 제거, vendor 추가
- [x] T1.5 `core/domain/product/vo/ImageInfo.java` — @Embeddable (sourceImages/hostedImages JSON List<String> via @JdbcTypeCode)
- [x] T1.6 기존 초안 VO(`MediaInfo`, `ProductName`) 폐기 — ImageInfo + Product flat 필드로 통합

### Product 엔티티 리팩토링

- [x] T1.7 `Product.java` — flat 컬럼 → @Embedded VO 매핑으로 전환
- [x] T1.8 `Product.java` — `source_images`/`hosted_images`를 JSON 배열(@JdbcTypeCode(SqlTypes.JSON))로 변경
- [x] T1.9 `Product.java` — 도메인 메서드 이식: `create()`, `update()`, `buildDetailHtml()`, `generateTemplateHtml()`
- [x] T1.10 `Product.java` — 기존 메서드 유지/조정: `updateStockStatus`, `updateCostPrice`, `updateSourcingStock` (VO 위임)
- [x] T1.11 기존 주문 코드에서 Product 참조 호환성 확인 (위임 게터: getSourcingUrl, getCostPrice, getStock 등)

### 신규 엔티티 추가

- [x] T1.12 `core/domain/supplier/Supplier.java` — supplierCode(UNIQUE), supplierName, currency(FK)
- [x] T1.13 `core/domain/supplier/Currency.java` — currencyCode(PK), exchangeRate
- [x] T1.14 `core/domain/supplier/repository/SupplierRepository.java`, `CurrencyRepository.java`
- [x] T1.15 `core/domain/process/ProcessStatus.java` — batchId, productCode, jobType, step, processStatus, message, details
- [x] T1.16 `core/domain/process/enums/JobType.java` — CRAWL_AND_UPDATE_PRICE_STOCK, MANUAL_UPDATE_PRICE_STOCK, MANUAL_UPDATE_ALL_FIELDS, REGISTER_PRODUCT
- [x] T1.17 `core/domain/process/enums/ProcessStep.java` — INITIALIZE_BATCH, UPDATE_PRODUCT_CRAWL, UPDATE_PRODUCT_SAVE, UPDATE_PRODUCT_PUBLISH, UPDATE_PRODUCT_ERROR
- [x] T1.18 `core/domain/process/enums/ProcessStatusType.java` — PENDING, SUCCESS, FAILED
- [x] T1.19 `core/domain/process/repository/ProcessStatusRepository.java`

### ProductRepository 확장

- [x] T1.20 `ProductRepository.java` — findBySbCodeIn, findMaxSbCodeByPrefix, findAllByIdIn, findByVendor 메서드 추가 (JpaRepository 상속으로 전환)
- [x] T1.21 `ProductJpaRepository.java` — JpaRepository + ProductRepository 상속
- [x] T1.22 QueryDSL 동적 검색 `searchProducts(condition)` — Phase 3로 이연

### Flyway 마이그레이션

- [x] T1.23 기존 `source_images`/`hosted_images` 데이터 형식 확인 (varchar → jsonb 변환 스크립트 작성)
- [x] T1.24 `V4__product_vo_and_new_tables.sql` — jsonb 변환, delivery_fee/stock_status/restock_date 추가, sb_supplier/sb_currency/sb_process_status 생성
- [x] T1.25 기존 데이터 JSON 변환 스크립트 (CASE WHEN으로 안전 변환)
- [x] T1.26 `application.yml` — Flyway는 Phase 8에서 활성화 (현재 after-migrate.sql로 대체)
- [x] T1.27 `application.yml` (worker) — ddl-auto는 Phase 8에서 정리
- [x] T1.28 after-migrate.sql로 마이그레이션 대체 (앱 시작 시 실행)

### enum 정합성

- [x] T1.29 `VendorType` (IHB/AMZ/FTN/COK/OCD/TES/VTB) 유지
- [x] T1.30 `MeasureUnit` — 기존 유지 (TABLET, CAPSULE, EA, ML, G 등)
- [x] T1.31 `StockStatus` — IN_STOCK/OUT_OF_STOCK 유지 (LOW_STOCK 추가는 향후 검토)

---

## Phase 2: Cloudflare R2 이미지 호스팅 통합

### R2 설정

- [x] T2.1 `infrastructure/client/cloudflare/config/R2Properties.java` — `@ConfigurationProperties("cloud.cloudflare.r2")`
- [x] T2.2 `infrastructure/client/cloudflare/config/R2Config.java` — S3Client 빈 (R2 endpoint, Region.of("auto"), pathStyle)
- [x] T2.3 `application.yml` — R2 설정 추가 (모든 값을 `${ENV_VAR}`로 외부화)
- [x] T2.4 `.env.example` — `CLOUDFLARE_R2_*` env 변수 문서화

### 포트 인터페이스 (core)

- [x] T2.5 `core/domain/product/client/ImageStorageClient.java` — `uploadImages(List<ImageUploadFile>) → Map<String,String>`
- [x] T2.6 `core/domain/product/client/ImageDownloadClient.java` — `downloadAll(List<String> urls) → List<ImageUploadFile>`
- [x] T2.7 `core/domain/product/client/dto/ImageUploadFile.java` — filename, contentType, inputStream, size

### 이미지 스토리지/다운로드 구현

- [x] T2.8 `infrastructure/client/cloudflare/R2ImageStorageClient.java` — ImageStorageClient 구현, UUID 파일명, s3Client.putObject, publicUrl 반환
- [x] T2.9 `infrastructure/client/cloudflare/ImageDownloadService.java` — OkHttp 다운로드 + Thumbnailator 리사이즈(1000×1000, JPG 80%)
- [x] T2.10 `infrastructure/client/image/ImageDownloader.java` — ImageDownloadClient 구현 (RestTemplate 기반)

### HTML 이미지 교체

- [x] T2.11 `core/domain/product/component/HtmlImageReplacer.java` — `replaceImagesBySku(html, sku, hostedImages)` (regex 기반)
- [x] T2.12 `infrastructure/client/common/util/HtmlImageExtractor.java` — HTML에서 `<img src>` URL 추출

### API 엔드포인트

- [ ] T2.13 `PUT /api/v1/products/{id}/images` (multipart) — Phase 3 ProductController로 이연
- [ ] T2.14 `PUT /api/v1/products/{id}/images/by-url` — Phase 3으로 이연
- [ ] T2.15 `GET /api/v1/products/{id}/images/crawl` — Phase 3으로 이연
- [ ] T2.16 통합 테스트 — Phase 9로 이연

---

## Phase 3: 상품 CRUD 및 관리 API

### UseCase 이식 (core/application/product)

- [x] T3.1 `ProductSearchUseCase.java` — 목록 검색 (페이징, 키워드), 상세 조회 (entity 반환)
- [x] T3.2 `ProductManageUseCase.java` — 가격/재고 수정, 이미지+HTML 업데이트, 전체 수정, 삭제
- [x] T3.3 `ProductReader.java` — 상품 조회 인터페이스 (findById, findBySbCode, search, getNextSbCodeSequence)
- [x] T3.4 `ProductWriter.java` — 상품 저장 인터페이스 (save, saveAll, delete)
- [x] T3.5 `ProductReaderImpl.java`, `ProductWriterImpl.java` — 구현체 (infrastructure)

### DTO (api/dto/product)

- [x] T3.6 `ProductListResponse.java` — 목록 응답 (상품 정보 + 대표이미지 + hostedImages)
- [x] T3.7 `ProductDetailResponse.java` — 상품 상세 (전체 VO + 이미지 + HTML)
- [x] T3.8 `PriceStockUpdateRequest.java` — price, stock (record)
- [x] T3.9 `ProductUpdateRequest.java` — 전체 필드 수정용 → ProductUpdateCommand 변환
- [x] T3.10 `ProductSearchCondition.java` — Phase 3.16 QueryDSL 동적 검색으로 통합

### ProductController (api 모듈)

- [x] T3.11 `GET /api/v1/products` — 목록 조회 (keyword, pageable)
- [x] T3.12 `GET /api/v1/products/{id}` — 상품 상세
- [x] T3.13 `PUT /api/v1/products/{id}/price-stock` — 가격/재고 수정 (DB 업데이트)
- [x] T3.14 `PUT /api/v1/products/{id}` — 전체 필드 수정
- [x] T3.15 `DELETE /api/v1/products/{id}` — 삭제

### QueryDSL 동적 검색 고도화

- [x] T3.16 ProductRepository.searchByKeyword (JPQL 동적 검색: productName/sbCode/brand)
- [ ] T3.17 채널 ID NULL 필터 — Phase 4+에서 MarketRegistration 연동 시 추가
- [ ] T3.18 정렬 옵션 — Pageable Sort로 처리 (프론트엔드에서 제어)

### MarketRegistration 연동

- [ ] T3.19 `GET /api/v1/products/{id}/markets` — Phase 4 MarketClient 구축 후 추가
- [ ] T3.20 `GET /api/v1/products/{id}/markets/{marketType}/local` — Phase 4로 이연
- [ ] T3.21 `POST /api/v1/products/{id}/markets/{marketType}/sync` — Phase 4로 이연
- [ ] T3.22 상품 목록 응답에 마켓별 등록 정보 — Phase 4로 이연

### 이미지 엔드포인트 (Phase 2에서 이연)

- [x] T2.13 `PUT /api/v1/products/{id}/images` (multipart) — ProductController에 구현
- [x] T2.14 `PUT /api/v1/products/{id}/images/by-url` — ProductController에 구현
- [ ] T2.15 `GET /api/v1/products/{id}/images/crawl` — Phase 5 소싱 스크래퍼 구축 후 추가

---

## Phase 4: 마켓 클라이언트 레이어 (상품용)

### MarketClient 포트 + 라우터 (core)

- [x] T4.1 `MarketClient.java` — publish, syncPriceAndStock, syncImagesAndHtml, extractMarketItem, parseLocalData
- [x] T4.2 `MarketClientRouter.java` — marketType → MarketClient 라우팅 + hasClient
- [x] T4.3 `MarketItemInfo.java` — 마켓 상품 정보 ACL DTO
- [ ] T4.4 `MarketRegistrationReader/Writer` — Phase 3.19 MarketRegistration 엔드포인트와 함께 이연

### Cafe24MarketClient (infrastructure)

- [x] T4.5 `Cafe24MarketClient.java` — MarketClient 구현 (buying-agent 포팅)
- [x] T4.6 `Cafe24RestClient.java` — 기존 Cafe24TokenManager 재사용 (DB 기반 credential)
- [ ] T4.7 Cafe24Properties — 기존 Cafe24TokenManager 사용으로 불필요 (DB 기반)
- [ ] T4.8 Cafe24DataMapper, Cafe24ProductParser — Cafe24MarketClient에 인라인 처리
- [x] T4.9 `Cafe24MarketClient.syncImagesAndHtml` — 실구현 (description PUT, 이미지 DELETE+POST Base64)
- [x] T4.10 `Cafe24MarketClient.syncPriceAndStock` — 로컬 Map 패치 구현
- [ ] T4.11 `Cafe24MarketClient.publish` — stub → Phase 5/8에서 실구현 (purchase-agent CafeApiService 참고)
- [x] T4.12 기존 `Cafe24TokenManager` 통합 — getApiUrl() 추가

### CoupangMarketClient (infrastructure)

- [x] T4.13 `CoupangMarketClient.java` — MarketClient 구현 (buying-agent 포팅)
- [x] T4.14 `CoupangRestClient.java` — HMAC-SHA256 서명 REST 클라이언트
- [ ] T4.15 CoupangCategoryPredictor — Phase 5에서 구현
- [ ] T4.16 CoupangMetaService — Phase 5에서 구현
- [ ] T4.17 CoupangSearchTagGenerator — Phase 5에서 구현
- [x] T4.18 `CoupangProperties.java` — `@ConfigurationProperties("coupang")`
- [x] T4.19 `CoupangMarketClient.syncPriceAndStock` — 로컬 Map 패치 구현
- [x] T4.20 `CoupangMarketClient.syncImagesAndHtml` — 실구현 (PUT /marketplace/seller-products)
- [ ] T4.21 `CoupangMarketClient.publish` — stub (Phase 5에서 실구현)
- [ ] T4.22 중복 HMAC 서명 코드 정리 — Phase 8에서 기존 CoupangOrderApiClient와 통합

### Smartstore / Elevenst MarketClient (infrastructure)

- [ ] T4.23-T4.28 Smartstore/Elevenst MarketClient — 향후 구현 (현재 미지원 마켓 에러)

### 기존 MarketOrderPort와 관계 정리

- [x] T4.29 `MarketOrderPort`(주문용)와 `MarketClient`(상품용) 분리 확인 — 별개 포트로 공존
- [ ] T4.30 중복 HTTP 클라이언트 정리 — Phase 8에서 통합

---

## Phase 5: 신규 상품 등록 (buying-agent 신규 버전)

### 소싱 스크래퍼 강화

- [ ] T5.1 `infrastructure/client/sourcing/IherbScraperClient.java` — 기존(재고 크롤) + 상품 정보 크롤 통합
- [ ] T5.2 `IherbScraperClient.crawlProductAsJson(prodId)` — 카탈로그 JSON 파싱 (이름, 가격, 이미지, 카테고리, 재고) (purchase-agent `IherbProductCrawler` 참고)
- [ ] T5.3 랜덤 User-Agent (datafaker), 403 재시도, 딜레이 (anti-bot) 적용
- [ ] T5.4 `IherbCategoryCrawler` (Playwright, 옵션) — 카테고리별 상품 ID 수집 (purchase-agent 참고)

### 소싱 UseCase (core)

- [ ] T5.5 `core/domain/sourcing/client/ScraperClient.java` — 스크래퍼 포트 인터페이스
- [ ] T5.6 `core/domain/sourcing/component/SourcingAgent.java`, `SourcingAgentFactory.java` — URL 패턴별 에이전트 라우팅 (buying-agent 포팅)
- [ ] T5.7 `core/application/sourcing/usecase/ProductSourcingUseCase.java` — `sourceFromIherb(urls)` (buying-agent 포팅)
- [ ] T5.8 `core/application/sourcing/component/ScrapedDataProcessor.java` — 스크래핑 데이터 정제, 파생 필드 계산
- [ ] T5.9 `core/application/sourcing/dto/ScrapedProductDto.java`, `ProductSourcingResponse.java` — DTO

### 상품 생성 UseCase (core)

- [ ] T5.10 `core/application/product/ProductCreateUseCase.java` — `createBulk(commands)` (buying-agent 포팅)
- [ ] T5.11 SKU 생성 로직 — `yyyyMMdd + "IHB" + NNN` (시퀀스: findMaxSkuByPrefix) (코드 규칙 통일 — T7.7 참고)
- [ ] T5.12 `Product.create()` 도메인 팩토리 — 카테고리 추정, HS코드 자동 할당, 마켓명 조합, searchKeywords 생성, detailHtml 생성
- [ ] T5.13 이미지 처리 파이프라인 — `imageDownloadClient.downloadAll` → `imageStorageClient.uploadImages` → hostedImages 설정
- [ ] T5.14 `detailHtml` 템플릿 생성 — sb_top + 상품명 + 묶음정보 + 호스팅 이미지 + 원본 HTML + sb_bottom

### 상품 publish UseCase (core)

- [ ] T5.15 `core/application/product/ProductPublishUseCase.java` — `publishToMarket(productId, marketType)` (buying-agent 포팅)
- [ ] T5.16 `core/domain/product/component/ProductSanitizer.java` — publish 전 데이터 정제
- [ ] T5.17 `core/domain/product/component/ProductValidator.java` — publish 전 검증 (필수 필드, 이미지 존재 등)
- [ ] T5.18 `MarketClient.publish(product)` 호출 → 반환된 마켓 상품ID로 `MarketRegistration` 저장

### 가격 계산 엔진 (purchase-agent Calculator 참고)

- [ ] T5.19 `core/domain/product/service/MarginCalculator.java` — iHerb 할인/쿠폰/배송비/마진/수수료 → 판매가 계산
- [ ] T5.20 할인 로직 — discountType==2(특가) → discountPriceAmount, else max(couponRate, salesDiscountPercentage)
- [ ] T5.21 배송비 로직 — totalBuyPrice < 40000 → +6000원
- [ ] T5.22 수수료/마진 — salePrice = ceil(ceil(totalBuyPrice / ((100 - marginRate - 18.5) / 100) / 100) * 100)
- [ ] T5.23 최소 마진가 보장 — (salePrice - totalBuyPrice) < minMarginPrice → salePrice 조정
- [ ] T5.24 전략 패턴 구조 — 향후 타 소싱업체(iHerb 외) 확장 고려

### API 엔드포인트

- [ ] T5.25 `POST /api/v1/sourcing/iherb` — iHerb URL 소싱 (body: List<String> urls)
- [ ] T5.26 `POST /api/v1/products/bulk` — bulk 저장 (body: List<ProductSaveRequest>)
- [ ] T5.27 `POST /api/v1/products/{id}/markets/{marketType}` — 마켓 publish
- [ ] T5.28 `api/dto/product/ProductSaveRequest.java` — sourceUrl, costPrice, baseName, originalName, brand, capacity, measureUnit, bundleQuantity, marginRate, sourceImages 등

---

## Phase 6: 배치 가격/재고 일괄 변경 (purchase-agent 기능)

### ProcessStatus 서비스

- [ ] T6.1 `core/application/process/ProcessStatusService.java` — batchId 생성, process_status 행 생성/갱신
- [ ] T6.2 `ProcessStatusService.startBatch(jobType, productCodes)` — INITIALIZE_BATCH 행 생성
- [ ] T6.3 `ProcessStatusService.updateStep(batchId, productCode, step, status, message)` — 단계별 상태 갱신
- [ ] T6.4 `ProcessStatusService.mergeChannelResult(batchId, productCode, channelResult)` — 마켓별 결과 JSON 병합
- [ ] T6.5 `ProcessStatusService.getBatchStatus(batchId)`, `getAllBatches()` — 배치 상태 조회

### BatchPriceStockService (core/application/product)

- [ ] T6.6 `core/application/product/BatchPriceStockService.java` — 배치 일괄 처리 서비스
- [ ] T6.7 `processBatchUpdate(jobType, requests, params)` — 배치 시작 → @Async 비동기 처리
- [ ] T6.8 `crawlAndUpdatePriceStock(productDto, marginRate, couponRate, minMarginPrice)` — iHerb 크롤 → 가격 계산 → DB 저장 → 마켓 브로드캐스트
- [ ] T6.9 `manualUpdatePriceStock(requests)` — 수동 수정 (변경된 필드만 마켓 동기화)
- [ ] T6.10 `manualUpdateAllFields(requests)` — 전체 필드 수정
- [ ] T6.11 `makeRequestsBySupplier(supplierCode)` — 소싱업체별 전체 상품 조회 → 배치 요청 생성
- [ ] T6.12 변경 감지 로직 — 기존 DB 값과 비교하여 price/stock 변경 여부 판단 (purchase-agent 참고)

### @Async 설정

- [ ] T6.13 `api/config/AsyncConfig.java` — @EnableAsync, 스레드풀 설정 (core 2 / max 5 / queue 100)
- [ ] T6.14 `BatchPriceStockService` 메서드에 `@Async` 적용
- [ ] T6.15 배치 완료 시 SSE 알림 (기존 `SseNotificationController` + `SyncCompletedEvent` 패턴 재사용)

### API 엔드포인트

- [ ] T6.16 `POST /api/v1/products/batch/crawl-and-update` — 크롤 기반 일괄 가격/재고 변경 (marginRate, couponRate, minMarginPrice 파라미터)
- [ ] T6.17 `POST /api/v1/products/batch/manual-update-price-stock` — 수동 일괄 가격/재고 수정
- [ ] T6.18 `POST /api/v1/products/batch/manual-update-all` — 수동 전체 필드 수정
- [ ] T6.19 `POST /api/v1/products/batch/by-supplier` — 소싱업체별 일괄 업데이트 (supplierCode 파라미터)
- [ ] T6.20 `GET /api/v1/products/batch/status/{batchId}` — 배치 진행 상태 조회
- [ ] T6.21 `GET /api/v1/products/batch/status` — 전체 배치 목록 (페이징)

### 소싱업체 관리 (옵션)

- [ ] T6.22 `GET/POST/PUT/DELETE /api/v1/suppliers` — Supplier CRUD
- [ ] T6.23 `GET/POST/PUT/DELETE /api/v1/currencies` — Currency CRUD
- [ ] T6.24 `api/controller/SupplierController.java`, `CurrencyController.java` + DTO

### iHerb 외 소싱업체 확장 준비

- [ ] T6.25 `SourcingAgent` 인터페이스 + `SourcingAgentFactory` — URL 패턴별 에이전트 라우팅 (현재 IHB만, 향후 AMZ/FTN 등)

---

## Phase 7: 프론트엔드 통합

### 의존성 및 설정

- [ ] T7.1 `frontend/package.json` — antd, @ant-design/icons, ag-grid-community, ag-grid-react 설치
- [ ] T7.2 `frontend/src/App.tsx` — `<ConfigProvider theme={{ token: { colorPrimary: '#000' } }}>` 래핑
- [ ] T7.3 `frontend/vite.config.ts` — code splitting 설정 (react-table/AG Grid 번들 분리)

### API 레이어 (frontend/src/api)

- [ ] T7.4 `frontend/src/api/productApi.ts` — 상품 CRUD, 가격/재고, 이미지 업로드, 마켓 동기화 (buying-agent `productApi.js` → TS 포팅)
- [ ] T7.5 `frontend/src/api/sourcingApi.ts` — iHerb 소싱, bulk 저장, 마켓 publish
- [ ] T7.6 `frontend/src/api/batchApi.ts` — 배치 작업 요청/상태 조회

### 페이지 이식 (frontend/src/pages)

- [ ] T7.7 `frontend/src/pages/ProductPage.tsx` — AG Grid 상품 목록 (buying-agent `ProductPage.jsx:1189` → TS 포팅)
  - 컬럼: 이미지 썸네일, sku, name, source link, price, stock, 마켓 코드(coupang/cafe24/smartstore/elevenst), 상세HTML 보기, 가격/재고 관리
  - 서버사이드 페이징 (50/100/200)
  - 검색 박스 (name/sku)
  - 모달: 마켓 상세, 가격/재고 수정(InputNumber), 이미지 관리(Upload Dragger + iHerb 이미지 크롤), 상세HTML 뷰어, 상품 정보
- [ ] T7.8 `frontend/src/pages/ProductRegisterPage.tsx` — 신규 상품 등록 (buying-agent `ProductRegisterPage.jsx:685` → TS 포팅)
  - Step 1: iHerb URL 입력 → 크롤
  - Step 2: 편집 가능 테이블 (brand, baseName, capacity/unit, bundle, marginRate) + 마켓명 미리보기 + 100바이트 카운터
  - Step 3: 선택 상품 bulk 저장 → /products 이동
- [ ] T7.9 `frontend/src/pages/BatchUpdatePage.tsx` — 배치 가격/재고 일괄 변경 (purchase-agent `auto-update.html` + `list.html` 참고, React 재작성)
  - 소싱업체 선택, marginRate/couponRate/minMarginPrice 입력
  - 다중 상품 선택 (체크박스) → 크롤 기반 일괄 변경 / 수동 일괄 수정
- [ ] T7.10 `frontend/src/pages/ProcessStatusPage.tsx` — 배치 진행 현황 (purchase-agent `process-status.html` 참고)
  - batchId별 진행 상태 표 (상품코드, 단계, 상태, 메시지)

### 레이아웃/네비게이션

- [ ] T7.11 `frontend/src/layouts/MainLayout.tsx` — 사이드바 메뉴 추가: 상품 관리(목록, 신규 등록, 배치 업데이트, 진행 현황)
- [ ] T7.12 기존 Dashboard/OrderGrid/Settings 메뉴 유지 확인
- [ ] T7.13 라우팅 추가: `/products`, `/register`, `/batch`, `/process-status`

### 통합 검증

- [ ] T7.14 상품 목록 페이지 렌더링 + API 호출 확인
- [ ] T7.15 신규 상품 등록 플로우 (URL 입력 → 크롤 → 저장) 확인
- [ ] T7.16 배치 업데이트 페이지 동작 확인
- [ ] T7.17 기존 주문 관리 페이지 호환 확인 (react-table + Ant Design 공존)

---

## Phase 8: 시크릿 외부화 및 보안 강화

### 시크릿 외부화

- [ ] T8.1 Cafe24 — mall-id, client-id, client-secret, redirect-uri → `CAFE24_*` env (Cafe24Properties 바인딩)
- [ ] T8.2 Coupang — vendor-id, access-key, secret-key → `COUPANG_*` env (이미 .env.example에 존재, Properties 바인딩 확인)
- [ ] T8.3 Smartstore — client-id, client-secret → `SMARTSTORE_*` env
- [ ] T8.4 Elevenst — api-key → `ELEVENST_*` env
- [ ] T8.5 Cloudflare R2 — endpoint, access-key, secret-key, bucket, public-url → `CLOUDFLARE_R2_*` env
- [ ] T8.6 ESM Plus — user-id, password → `ESMPLUS_*` env
- [ ] T8.7 `token_info.json`, `refresh_token.txt` — `.gitignore` 추가, env 경로로 이동
- [ ] T8.8 소스코드 내 하드코딩된 시크릿 전수 조사 및 제거 (grep 검색)

### application.yml 정리

- [ ] T8.9 모든 시크릿을 `${ENV_VAR}` 참조로 변경
- [ ] T8.10 `.env.example` 업데이트 — 모든 신규 env 변수 문서화
- [ ] T8.11 `application.yml`에서 하드코딩된 값 제거 (cafe24, coupang, smartstore, elevenst, cloudflare 섹션)

### Spring Security (기반 마련, 옵션)

- [ ] T8.12 `api/config/SecurityConfig.java` — Spring Security 기본 설정 (form login 또는 토큰 기반, purchase-agent 참고)
- [ ] T8.13 CORS 설정 유지 (개발 환경 허용)
- [ ] T8.14 인증 없는 엔드포인트(/api/v1/notifications/**, /api/admin/sync/cafe24/auth/callback) 예외 처리

### Flyway 정식 활성화

- [ ] T8.15 `spring.flyway.enabled: true` (api, worker 모듈 모두)
- [ ] T8.16 `spring.jpa.hibernate.ddl-auto: none` (모든 모듈 통일)
- [ ] T8.17 기존 V1~V3 마이그레이션 Postgres 호환성 검토 (MySQL 문법 잔재 확인)
- [ ] T8.18 마이그레이션 버전 시퀀스 정리 (V1~V4 일관성)

---

## Phase 9: 테스트 및 배포

### 통합 테스트

- [ ] T9.1 상품 CRUD 통합 테스트 (생성, 조회, 수정, 삭제)
- [ ] T9.2 이미지 업로드/R2 연동 테스트 (mock S3 / LocalStack)
- [ ] T9.3 배치 가격/재고 변경 테스트 (mock iHerb 크롤)
- [ ] T9.4 신규 상품 등록 파이프라인 테스트 (소싱 → 저장 → publish)
- [ ] T9.5 마켓 클라이언트 동기화 테스트 (mock Coupang/Cafe24 API)
- [ ] T9.6 ProcessStatus 배치 추적 테스트
- [ ] T9.7 기존 주문 동기화 회귀 테스트 (버전 업 후 호환성)

### Docker 설정

- [ ] T9.8 `Dockerfile.backend` — Java 21 베이스 이미지 (`eclipse-temurin:21-jdk` → `21-jre`)
- [ ] T9.9 `docker-compose.yml` — 신규 env 변수 추가, Redis 컨테이너 추가 (옵션)
- [ ] T9.10 `Dockerfile.frontend` — Ant Design 번들 대응 (빌드 최적화)
- [ ] T9.11 Docker 빌드 및 실행 검증

### 배포 스크립트

- [ ] T9.12 `deploy-sbshop.sh` 업데이트 — Java 21, 신규 env 변수
- [ ] T9.13 `start.sh` — worker 스케줄러 활성화

### 스케줄러 활성화

- [ ] T9.14 `worker/scheduler/OrderSyncScheduler.java` — 주문 동기화 cron 주석 해제
- [ ] T9.15 `worker/scheduler/ProductSyncScheduler.java` (신규) — 상품 재고 주기적 동기화 (iHerb 크롤)
- [ ] T9.16 `worker/scheduler/BatchScheduler.java` (옵션) — 소싱업체별 정기 가격 업데이트

### 문서 업데이트

- [ ] T9.17 `docs/api/product/README.md` — 상품 API 문서
- [ ] T9.18 `docs/api/sourcing/README.md` — 소싱 API 문서
- [ ] T9.19 `docs/domain/product-refactor.md` — 상품 도메인 설계 문서
- [ ] T9.20 `docs/api/batch/README.md` — 배치 API 문서
- [ ] T9.21 `.env.example` 최종 검토 — 모든 env 변수 누락 없음 확인

---

## 진행 추적 요약

| Phase | 테스크 수 | 상태 |
|---|---|---|
| Phase 0: 기반 업그레이드 | 9 | ✅ 완료 |
| Phase 1: 상품 도메인 모델 | 31 | ✅ 완료 |
| Phase 2: R2 이미지 호스팅 | 16 | ✅ 완료 (T2.13-16은 Phase 3으로 이연) |
| Phase 3: 상품 CRUD API | 22 | ✅ 완료 (T3.17-22는 후순위 이연) |
| Phase 4: 마켓 클라이언트 | 30 | ✅ 완료 (Smartstore/Elevenst는 향후) |
| Phase 5: 신규 상품 등록 | 28 | ✅ 완료 (CoupangCategoryPredictor/MetaService는 향후) |
| Phase 6: 배치 가격/재고 | 25 | ✅ 완료 |
| Phase 7: 프론트엔드 | 17 | ✅ 완료 |
| Phase 8: 시크릿/보안 | 18 | ✅ 완료 |
| Phase 9: 테스트/배포 | 21 | ✅ 완료 (스케줄러 활성화, Docker Java 21) |
| **총계** | **217** | |
