# sbshop-agent 통합 리팩토링 테스크

> `refactor.md`의 Phase별 작업을 실행 가능한 단위 테스크로 분할.
> 각 테스크는 `[Phase.Task번호]` 형식으로 번호 부여. 체크박스로 진행 추적.

---

## Phase 0: 기반 환경 업그레이드 및 의존성 설정

- [ ] T0.1 `backend/build.gradle` — Java toolchain 17 → 21 변경, Spring Boot 3.2.3 → 3.5.x, dependency-management 버전 업
- [ ] T0.2 `backend/core/build.gradle` — hypersistence-utils-hibernate-63 의존성 추가 (JSON 컬럼 지원)
- [ ] T0.3 `backend/infrastructure/build.gradle` — AWS S3 SDK v2 (`software.amazon.awssdk:s3`), Thumbnailator, OkHttp 의존성 추가
- [ ] T0.4 `backend/api/build.gradle` — Thumbnailator, springdoc-openapi 의존성 추가
- [ ] T0.5 `frontend/package.json` — `antd`, `@ant-design/icons`, `ag-grid-community`, `ag-grid-react` 의존성 추가
- [ ] T0.6 기존 주문 관리 코드 컴파일 검증 (Java 21 + Spring Boot 3.5.x 호환성)
- [ ] T0.7 기존 주문 동기화 API 동작 확인 (`POST /api/v1/orders/sync/coupang` 등)
- [ ] T0.8 프론트엔드 빌드 확인 (`npm run build` 성공)
- [ ] T0.9 `docker-compose.yml` — Postgres 설정 확인, Redis 컨테이너 추가 (Coupang 메타 캐시용, 옵션)

---

## Phase 1: 상품 도메인 모델 리팩토링

### VO 클래스 이식 (buying-agent → sbshop-agent core)

- [ ] T1.1 `core/domain/product/vo/PriceInfo.java` — @Embeddable (costPrice, exchangeRate, marginRate, salePrice, deliveryFee)
- [ ] T1.2 `core/domain/product/vo/LogisticsInfo.java` — @Embeddable (stock, weight, bundleQuantity)
- [ ] T1.3 `core/domain/product/vo/ProductSpec.java` — @Embeddable (barcode, capacity, measureUnit)
- [ ] T1.4 `core/domain/product/vo/SourcingInfo.java` — @Embeddable (vendor, sourcingUrl, manufacturer, origin, hsCode)
- [ ] T1.5 `core/domain/product/vo/ImageInfo.java` — @Embeddable (sourceImages JSON, hostedImages JSON via @JdbcTypeCode)
- [ ] T1.6 기존 초안 VO(`MediaInfo`, `ProductName`) 통합 또는 폐기 — 중복 필드 정리

### Product 엔티티 리팩토링

- [ ] T1.7 `core/domain/product/Product.java` — flat 컬럼 → @Embedded VO 매핑으로 전환
- [ ] T1.8 `Product.java` — `source_images`/`hosted_images`를 JSON 배열(@JdbcTypeCode(SqlTypes.JSON))로 변경
- [ ] T1.9 `Product.java` — 도메인 메서드 이식: `create()`, `update()`, `buildDetailHtml()`, `generateTemplateHtml()` (buying-agent `Product.java:119-307`)
- [ ] T1.10 `Product.java` — 기존 메서드 유지/조정: `updateStockStatus`, `updateCostPrice`, `updateSourcingStock`
- [ ] T1.11 기존 주문 코드에서 Product 참조 호환성 확인 (OrderLineItem.productId 등)

### 신규 엔티티 추가

- [ ] T1.12 `core/domain/supplier/Supplier.java` — supplierCode(PK), supplierName, currency(FK)
- [ ] T1.13 `core/domain/supplier/Currency.java` — currencyCode(PK), exchangeRate
- [ ] T1.14 `core/domain/supplier/repository/SupplierRepository.java`, `CurrencyRepository.java`
- [ ] T1.15 `core/domain/process/ProcessStatus.java` — batchId, productCode, jobType, step, status, message, details, startedAt, updatedAt
- [ ] T1.16 `core/domain/process/enums/JobType.java` — CRAWL_AND_UPDATE_PRICE_STOCK, MANUAL_UPDATE_PRICE_STOCK, MANUAL_UPDATE_ALL_FIELDS, REGISTER_PRODUCT
- [ ] T1.17 `core/domain/process/enums/ProcessStep.java` — INITIALIZE_BATCH, UPDATE_PRODUCT_CRAWL, UPDATE_PRODUCT_SAVE, UPDATE_PRODUCT_PUBLISH, UPDATE_PRODUCT_ERROR
- [ ] T1.18 `core/domain/process/enums/ProcessStatus.java`(enum) — PENDING, SUCCESS, FAILED
- [ ] T1.19 `core/domain/process/repository/ProcessStatusRepository.java` + Custom 인터페이스

### ProductRepository 확장

- [ ] T1.20 `core/domain/product/repository/ProductRepository.java` — `searchByNameOrSku`, `findBySku`, `findBySkuIn`, `findMaxSkuByPrefix`, `findAllByIds`, `findBySupplier` 메서드 추가
- [ ] T1.21 `infrastructure/.../repository/product/ProductJpaRepository.java` — @Query 확장 (searchByNameOrSku, findMaxSkuByPrefix)
- [ ] T1.22 `infrastructure/.../repository/product/ProductRepositoryImpl.java` — QueryDSL 동적 검색 `searchProducts(condition)` 구현

### Flyway 마이그레이션

- [ ] T1.23 기존 `source_images`/`hosted_images` 데이터 형식 확인 (파이프/콤마 구분 여부)
- [ ] T1.24 `V4__product_vo_and_new_tables.sql` — sb_product 컬럼 조정 (JSON 변환), sb_supplier/sb_currency/sb_process_status 테이블 생성
- [ ] T1.25 기존 데이터 JSON 변환 스크립트 작성 (문자열 → JSON 배열)
- [ ] T1.26 `application.yml` — `spring.flyway.enabled: true` 활성화
- [ ] T1.27 `application.yml` (worker) — `hibernate.ddl-auto: none`으로 변경 (update 제거)
- [ ] T1.28 Flyway 마이그레이션 실행 검증

### enum 정합성

- [ ] T1.29 `VendorType` (IHB/AMZ/FTN/COK/OCD/TES/VTB) 유지 확인
- [ ] T1.30 `MeasureUnit` 확장 — TABLET, CAPSULE, EA, ML, G, KG 등 (buying-agent 참고)
- [ ] T1.31 `StockStatus` 검토 — IN_STOCK/OUT_OF_STOCK (LOW_STOCK 추가 여부 결정)

---

## Phase 2: Cloudflare R2 이미지 호스팅 통합

### R2 설정

- [ ] T2.1 `infrastructure/client/cloudflare/config/R2Properties.java` — `@ConfigurationProperties("cloud.cloudflare.r2")` (endpoint, accessKey, secretKey, bucket, publicUrl)
- [ ] T2.2 `infrastructure/client/cloudflare/config/R2Config.java` — S3Client 빈 (R2 endpoint, Region.of("auto"), pathStyleAccess)
- [ ] T2.3 `application.yml` — R2 설정 추가 (모든 값을 `${ENV_VAR}`로 외부화)
- [ ] T2.4 `.env.example` — `CLOUDFLARE_R2_*` env 변수 문서화

### 포트 인터페이스 (core)

- [ ] T2.5 `core/domain/product/client/ImageStorageClient.java` — `uploadImages(List<ImageUploadFile>) → Map<String,String>`
- [ ] T2.6 `core/domain/product/client/ImageDownloadClient.java` — `downloadAll(List<String> urls) → List<ImageUploadFile>`
- [ ] T2.7 `core/domain/product/client/dto/ImageUploadFile.java` — filename, contentType, inputStream, size

### 이미지 스토리지/다운로드 구현

- [ ] T2.8 `infrastructure/client/cloudflare/R2ImageStorageClient.java` — ImageStorageClient 구현, UUID 파일명, s3Client.putObject, publicUrl 반환
- [ ] T2.9 `infrastructure/client/cloudflare/ImageDownloadService.java` — ImageDownloadClient 구현, OkHttp 다운로드 + Thumbnailator 리사이즈(1000×1000, JPG 80%)
- [ ] T2.10 `infrastructure/client/image/ImageDownloader.java` — 소스 이미지 일괄 다운로드 (RestTemplate 기반, buying-agent 참고)

### HTML 이미지 교체

- [ ] T2.11 `core/domain/product/component/HtmlImageReplacer.java` — `replaceImagesBySku(html, sku, hostedImages)` (regex 기반, buying-agent 포팅)
- [ ] T2.12 `infrastructure/.../common/util/HtmlImageExtractor.java` — HTML에서 `<img src>` URL 추출 (cafe24 상세 HTML에서 이미지 복구용)

### API 엔드포인트

- [ ] T2.13 `api/.../controller/ProductController.java` — `PUT /api/v1/products/{id}/images` (multipart) 엔드포인트
- [ ] T2.14 `api/.../controller/ProductController.java` — `PUT /api/v1/products/{id}/images/by-url` 엔드포인트
- [ ] T2.15 `api/.../controller/ProductController.java` — `GET /api/v1/products/{id}/images/crawl` 엔드포인트 (소스 이미지 크롤)
- [ ] T2.16 이미지 업로드 → R2 저장 → 상품 hostedImages 갱신 → detailHtml 이미지 교체 통합 테스트

---

## Phase 3: 상품 CRUD 및 관리 API

### UseCase 이식 (core/application/product)

- [ ] T3.1 `core/application/product/ProductSearchUseCase.java` — 목록 검색 (페이징, 검색, 필터)
- [ ] T3.2 `core/application/product/ProductManageUseCase.java` — 상품 수정 (가격/재고, 이미지+HTML, 전체 필드) + 마켓 브로드캐스트
- [ ] T3.3 `core/domain/product/component/ProductReader.java` — 상품 조회 인터페이스 (findById, findBySku, search, getNextSkuSequence)
- [ ] T3.4 `core/domain/product/component/ProductWriter.java` — 상품 저장 인터페이스 (save, saveAll, delete)
- [ ] T3.5 `infrastructure/.../persistence/product/ProductReaderImpl.java`, `ProductWriterImpl.java` — 구현체

### DTO (api/dto/product)

- [ ] T3.6 `api/dto/product/ProductListResponse.java` — 목록 응답 (페이징 메타 + 상품 목록 + 마켓 등록 정보)
- [ ] T3.7 `api/dto/product/ProductDetailResponse.java` — 상품 상세 (전체 필드 + 마켓 등록 목록)
- [ ] T3.8 `api/dto/product/PriceStockUpdateRequest.java` — price, stock (record)
- [ ] T3.9 `api/dto/product/ProductUpdateRequest.java` — 전체 필드 수정용
- [ ] T3.10 `api/dto/product/ProductSearchCondition.java` — 검색 조건 (keyword, vendor, category, supplier, channel-id 필터)

### ProductController (api 모듈)

- [ ] T3.11 `GET /api/v1/products` — 목록 조회 (page, size, keyword, vendor, category, supplier 파라미터)
- [ ] T3.12 `GET /api/v1/products/{id}` — 상품 상세
- [ ] T3.13 `PUT /api/v1/products/{id}/price-stock` — 가격/재고 수정 + 마켓 브로드캐스트
- [ ] T3.14 `PUT /api/v1/products/{id}` — 전체 필드 수정
- [ ] T3.15 `DELETE /api/v1/products/{id}` — 삭제 (soft delete 고려)

### QueryDSL 동적 검색 고도화

- [ ] T3.16 `ProductRepositoryImpl.searchProducts(condition)` — BooleanExpression 동적 쿼리 (keywordContains, vendorEq, categoryEq, supplierEq)
- [ ] T3.17 채널 ID NULL 필터 추가 (filterNullVendorItemId, filterNullSellerProductId 등 — purchase-agent 참고)
- [ ] T3.18 정렬 옵션 추가 (createdAt, updatedAt, sbCode, name asc/desc)

### MarketRegistration 연동

- [ ] T3.19 `GET /api/v1/products/{id}/markets` — 상품의 마켓 등록 목록
- [ ] T3.20 `GET /api/v1/products/{id}/markets/{marketType}/local` — 마켓별 로컬 데이터
- [ ] T3.21 `POST /api/v1/products/{id}/markets/{marketType}/sync` — 마켓 실시간 동기화 (MarketClient.extractMarketItem)
- [ ] T3.22 상품 목록 응답에 마켓별 등록 여부/마켓 상품ID 포함

---

## Phase 4: 마켓 클라이언트 레이어 (상품용)

### MarketClient 포트 + 라우터 (core)

- [ ] T4.1 `core/domain/market/client/MarketClient.java` — publish, syncPriceAndStock, syncImagesAndHtml, extractMarketItem, deleteMarketProduct, fetchAllMarketItemIds
- [ ] T4.2 `core/domain/market/client/MarketClientRouter.java` — marketType → MarketClient 라우팅
- [ ] T4.3 `core/domain/market/client/dto/MarketItemInfo.java` — 마켓 상품 정보 ACL DTO
- [ ] T4.4 `core/domain/market/component/MarketRegistrationReader.java`, `MarketRegistrationWriter.java` — 인터페이스

### Cafe24MarketClient (infrastructure)

- [ ] T4.5 `infrastructure/client/cafe24/adapter/Cafe24MarketClient.java` — MarketClient 구현 (buying-agent 포팅)
- [ ] T4.6 `infrastructure/client/cafe24/client/Cafe24RestClient.java` — REST 클라이언트 (buying-agent 포팅)
- [ ] T4.7 `infrastructure/client/cafe24/config/Cafe24Properties.java` — `@ConfigurationProperties("cafe24")` (mall-id, client-id, client-secret 등)
- [ ] T4.8 `infrastructure/client/cafe24/mapper/Cafe24DataMapper.java`, `parser/Cafe24ProductParser.java` — 데이터 매핑/파싱
- [ ] T4.9 `Cafe24MarketClient.syncImagesAndHtml` — 실구현 확인 (description PUT, 이미지 DELETE+POST, 외부 이미지 Base64)
- [ ] T4.10 `Cafe24MarketClient.syncPriceAndStock` — commented out → 실구현 전환
- [ ] T4.11 `Cafe24MarketClient.publish` — stub → 실구현 (purchase-agent `CafeApiService.registerProduct` 참고: FTP 업로드 + POST /admin/products)
- [ ] T4.12 기존 `Cafe24TokenManager`와 통합 (sbshop-agent 기존 코드 재사용)

### CoupangMarketClient (infrastructure)

- [ ] T4.13 `infrastructure/client/coupang/adapter/CoupangMarketClient.java` — MarketClient 구현 (buying-agent 포팅)
- [ ] T4.14 `infrastructure/client/coupang/client/CoupangRestClient.java` — HMAC 서명 REST 클라이언트
- [ ] T4.15 `infrastructure/client/coupang/component/CoupangCategoryPredictor.java` — 카테고리 예측 (whitelist 방어)
- [ ] T4.16 `infrastructure/client/coupang/component/CoupangMetaService.java` — 카테고리 메타 조회 (@Cacheable Redis)
- [ ] T4.17 `infrastructure/client/coupang/component/CoupangSearchTagGenerator.java` — SEO 태그 생성
- [ ] T4.18 `infrastructure/client/coupang/config/CoupangProperties.java` — `@ConfigurationProperties("coupang")`
- [ ] T4.19 `CoupangMarketClient.syncPriceAndStock` — commented out → 실구현 전환
- [ ] T4.20 `CoupangMarketClient.syncImagesAndHtml` — 실구현 확인
- [ ] T4.21 `CoupangMarketClient.publish` — 실구현 확인
- [ ] T4.22 중복 HMAC 서명 코드 정리 — `CoupangOrderApiClient`(주문용)와 `CoupangRestClient`(상품용) 공통 유틸 추출

### Smartstore / Elevenst MarketClient (infrastructure)

- [ ] T4.23 `infrastructure/client/smartstore/adapter/SmartstoreMarketClient.java` — MarketClient 구현 (purchase-agent `SmartstoreApiService` 참고 신규)
- [ ] T4.24 `infrastructure/client/smartstore/client/SmartstoreRestClient.java` — OAuth2 + BCrypt 토큰 교환
- [ ] T4.25 `SmartstoreMarketClient.publish`, `syncPriceAndStock` 구현
- [ ] T4.26 `infrastructure/client/elevenst/adapter/ElevenstMarketClient.java` — MarketClient 구현 (purchase-agent `ElevenstApiService` 참고)
- [ ] T4.27 `infrastructure/client/elevenst/client/ElevenstRestClient.java` — XML over REST
- [ ] T4.28 `ElevenstMarketClient.publish`, `syncPriceAndStock` 구현

### 기존 MarketOrderPort와 관계 정리

- [ ] T4.29 `MarketOrderPort`(주문용)와 `MarketClient`(상품용)가 `MarketCredential` 공유 확인
- [ ] T4.30 마켓별 중복 HTTP 클라이언트/인증 코드 정리 (공통 모듈로 추출)

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
| Phase 0: 기반 업그레이드 | 9 | 미시작 |
| Phase 1: 상품 도메인 모델 | 31 | 미시작 |
| Phase 2: R2 이미지 호스팅 | 16 | 미시작 |
| Phase 3: 상품 CRUD API | 22 | 미시작 |
| Phase 4: 마켓 클라이언트 | 30 | 미시작 |
| Phase 5: 신규 상품 등록 | 28 | 미시작 |
| Phase 6: 배치 가격/재고 | 25 | 미시작 |
| Phase 7: 프론트엔드 | 17 | 미시작 |
| Phase 8: 시크릿/보안 | 18 | 미시작 |
| Phase 9: 테스트/배포 | 21 | 미시작 |
| **총계** | **217** | |
