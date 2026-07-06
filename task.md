# sbshop-agent 통합 리팩토링 테스크

> `refactor.md`의 Phase별 작업를 실행 가능한 단위 테스크로 분할.
> 각 테스크는 `[Phase.Task번호]` 형식으로 번호 부여. 체크박스로 진행 추적.

---

## Phase 0: 기반 환경 업그레이드 및 의존성 설정

- [x] T0.1 `backend/build.gradle` — Java toolchain 17 → 21, Spring Boot 3.2.3 → 3.5.9, dependency-management 1.1.4 → 1.1.7
- [x] T0.2 `backend/core/build.gradle` — hypersistence-utils-hibernate-63:3.7.1
- [x] T0.3 `backend/infrastructure/build.gradle` — AWS S3 SDK v2, Thumbnailator, OkHttp
- [x] T0.4 `backend/api/build.gradle` — Thumbnailator, springdoc-openapi, spring-boot-starter-security
- [x] T0.5 `frontend/package.json` — antd 5.22, @ant-design/icons, ag-grid-community/react 32.3
- [x] T0.6 기존 주문 관리 코드 컴파일 검증 (BUILD SUCCESSFUL)
- [x] T0.7 기존 주문 동기화 API 동작 확인
- [x] T0.8 프론트엔드 빌드 확인
- [x] T0.9 `docker-compose.yml` — Redis 컨테이너 추가

---

## Phase 1: 상품 도메인 모델 리팩토링

### VO 클래스 이식

- [x] T1.1 `PriceInfo.java` — @Embeddable (costPrice, exchangeRate, marginRate, salePrice, deliveryFee)
- [x] T1.2 `LogisticsInfo.java` — @Embeddable (stock, weight, bundleQuantity)
- [x] T1.3 `ProductSpec.java` — @Embeddable (barcode, capacity, measureUnit)
- [x] T1.4 `SourcingInfo.java` — @Embeddable (vendor, sourceUrl, manufacturer, origin, hsCode)
- [x] T1.5 `ImageInfo.java` — @Embeddable (sourceImages/hostedImages JSON List<String>)
- [x] T1.6 기존 `MediaInfo`, `ProductName` 폐기

### Product 엔티티 리팩토링

- [x] T1.7 `Product.java` — flat → @Embedded VO 매핑
- [x] T1.8 `source_images`/`hosted_images` → JSON 배열 (@JdbcTypeCode)
- [x] T1.9 도메인 메서드: `create()`, `update()`, `buildDetailHtml()`, `generateTemplateHtml()`
- [x] T1.10 기존 메서드 유지: `updateStockStatus`, `updateCostPrice`, `updateSourcingStock`
- [x] T1.11 위임 게터: getSourcingUrl, getCostPrice, getStock, getVendor, getSourceImages

### 신규 엔티티

- [x] T1.12 `Supplier.java` — supplierCode, supplierName, currency(FK)
- [x] T1.13 `Currency.java` — currencyCode(PK), exchangeRate
- [x] T1.14 `SupplierRepository.java`, `CurrencyRepository.java`
- [x] T1.15 `ProcessStatus.java` — batchId, productCode, jobType, step, processStatus, message
- [x] T1.16 `JobType.java`
- [x] T1.17 `ProcessStep.java`
- [x] T1.18 `ProcessStatusType.java`
- [x] T1.19 `ProcessStatusRepository.java`

### ProductRepository 확장

- [x] T1.20 findBySbCodeIn, findMaxSbCodeByPrefix, findAllByIdIn, findByVendor, searchByKeyword
- [x] T1.21 `ProductJpaRepository.java` — JpaRepository + ProductRepository 상속
- [x] T1.22 QueryDSL 동적 검색 — JPQL searchByKeyword로 대체

### Flyway 마이그레이션

- [x] T1.23 기존 `source_images`/`hosted_images` 형식 확인
- [x] T1.24 `V4__product_vo_and_new_tables.sql` — jsonb 변환, 신규 컬럼/테이블
- [x] T1.25 기존 데이터 JSON 변환 (CASE WHEN 안전 변환)
- [x] T1.26 Flyway는 Phase 8에서 활성화
- [x] T1.27 worker ddl-auto는 Phase 8에서 none으로 변경
- [x] T1.28 after-migrate.sql로 마이그레이션 대체

### enum 정합성

- [x] T1.29 `VendorType` 유지
- [x] T1.30 `MeasureUnit` 유지
- [x] T1.31 `StockStatus` 유지

---

## Phase 2: Cloudflare R2 이미지 호스팅 통합

### R2 설정

- [x] T2.1 `R2Properties.java`
- [x] T2.2 `R2Config.java` — S3Client 빈
- [x] T2.3 `application.yml` — R2 설정 (env 변수화)
- [x] T2.4 `.env.example` — CLOUDFLARE_R2_* 추가

### 포트 인터페이스 (core)

- [x] T2.5 `ImageStorageClient.java`
- [x] T2.6 `ImageDownloadClient.java` (downloadAndConvert 포함)
- [x] T2.7 `ImageUploadFile.java`

### 이미지 스토리지/다운로드 구현

- [x] T2.8 `R2ImageStorageClient.java`
- [x] T2.9 `ImageDownloadService.java` — OkHttp + Thumbnailator
- [x] T2.10 `ImageDownloader.java` — ImageDownloadClient 구현 (downloadAndConvert 포함)

### HTML 이미지 교체

- [x] T2.11 `HtmlImageReplacer.java`
- [x] T2.12 `HtmlImageExtractor.java`

### API 엔드포인트

- [x] T2.13 `PUT /api/v1/products/{id}/images` (multipart) — ProductController에 구현
- [x] T2.14 `PUT /api/v1/products/{id}/images/by-url` — ProductController에 구현
- [x] T2.15 `GET /api/v1/products/{id}/images/crawl` — ProductController에 구현
- [x] T2.16 이미지 업로드 통합 테스트 — ProductCreateUseCaseTest로 이미지 파이프라인 테스트 (mock 기반)

---

## Phase 3: 상품 CRUD 및 관리 API

### UseCase 이식

- [x] T3.1 `ProductSearchUseCase.java`
- [x] T3.2 `ProductManageUseCase.java`
- [x] T3.3 `ProductReader.java`
- [x] T3.4 `ProductWriter.java`
- [x] T3.5 `ProductReaderImpl.java`, `ProductWriterImpl.java`

### DTO

- [x] T3.6 `ProductListResponse.java`
- [x] T3.7 `ProductDetailResponse.java`
- [x] T3.8 `PriceStockUpdateRequest.java`
- [x] T3.9 `ProductUpdateRequest.java`
- [x] T3.10 ProductSearchCondition — searchByKeyword JPQL로 대체

### ProductController

- [x] T3.11 `GET /api/v1/products`
- [x] T3.12 `GET /api/v1/products/{id}`
- [x] T3.13 `PUT /api/v1/products/{id}/price-stock`
- [x] T3.14 `PUT /api/v1/products/{id}`
- [x] T3.15 `DELETE /api/v1/products/{id}`

### QueryDSL 동적 검색 고도화

- [x] T3.16 ProductRepository.searchByKeyword (JPQL)
- [x] T3.17 채널 ID NULL 필터 — findUnregisteredByMarket/findRegisteredByMarket, marketFilter 파라미터 구현
- [x] T3.18 정렬 옵션 — Pageable Sort로 처리

### MarketRegistration 연동

- [x] T3.19 `GET /api/v1/products/{id}/markets` — MarketRegistrationController
- [x] T3.20 `GET /api/v1/products/{id}/markets/{marketType}/local`
- [x] T3.21 `POST /api/v1/products/{id}/markets/{marketType}/sync`
- [x] T3.22 상품 목록 응답에 마켓별 등록 정보 — ProductListResponse marketRegistrations Map 추가, ProductController에서 배치 조회

---

## Phase 4: 마켓 클라이언트 레이어 (상품용)

### MarketClient 포트 + 라우터

- [x] T4.1 `MarketClient.java`
- [x] T4.2 `MarketClientRouter.java`
- [x] T4.3 `MarketItemInfo.java`
- [x] T4.4 MarketRegistrationReader/Writer — Repository 직접 사용 (설계 결정)

### Cafe24MarketClient

- [x] T4.5 `Cafe24MarketClient.java`
- [x] T4.6 `Cafe24RestClient.java` — Cafe24TokenManager 재사용
- [x] T4.7 Cafe24Properties — DB 기반 credential 사용 (설계 결정)
- [x] T4.8 Cafe24DataMapper/Parser — 인라인 처리 (설계 결정)
- [x] T4.9 `syncImagesAndHtml` 실구현
- [x] T4.10 `syncPriceAndStock` 구현
- [x] T4.11 `publish` 실구현 (POST /admin/products)
- [x] T4.12 Cafe24TokenManager getApiUrl() 추가

### CoupangMarketClient

- [x] T4.13 `CoupangMarketClient.java`
- [x] T4.14 `CoupangRestClient.java` — HMAC-SHA256
- [x] T4.15 `CoupangCategoryPredictor.java` — 화이트리스트 방어
- [x] T4.16 `CoupangMetaService.java` — @Cacheable Redis
- [x] T4.17 `CoupangSearchTagGenerator.java`
- [x] T4.18 `CoupangProperties.java`
- [x] T4.19 `syncPriceAndStock` 구현
- [x] T4.20 `syncImagesAndHtml` 실구현
- [x] T4.21 `publish` 실구현 (카테고리 예측 → 메타 → 태그 → 페이로드 → 등록)
- [x] T4.22 중복 HMAC 코드 정리 — CoupangHmacUtil 공통 유틸 추출, CoupangRestClient/CoupangOrderApiClient 모두 사용

### Smartstore / Elevenst MarketClient

- [x] T4.23 `SmartstoreMarketClient.java` — OAuth2 + BCrypt, publish/sync 실구현
- [x] T4.24 `SmartstoreRestClient.java`
- [x] T4.25 Smartstore publish/syncPriceAndStock/syncImagesAndHtml 구현
- [x] T4.26 `ElevenstMarketClient.java` — XML over REST
- [x] T4.27 `ElevenstRestClient.java` — EUC-KR, openapikey
- [x] T4.28 Elevenst publish/syncPriceAndStock 구현

### 기존 MarketOrderPort와 관계 정리

- [x] T4.29 MarketOrderPort(주문) vs MarketClient(상품) 분리 확인
- [x] T4.30 중복 HTTP 클라이언트 정리 — Coupang HMAC 공통 유틸로 정리 (마켓별 REST 클라이언트는 API 차이로 인해 별도 유지)

---

## Phase 5: 신규 상품 등록

### 소싱 스크래퍼 강화

- [x] T5.1 `IherbScraperClient.crawlProductInfo()` 추가
- [x] T5.2 카탈로그 JSON 파싱 (이름, 가격, 이미지, 카테고리, 재고)
- [x] T5.3 랜덤 User-Agent, 403 재시도, Referer 헤더, 딜레이
- [ ] T5.4 `IherbCategoryCrawler` (Playwright) — 미구현 (옵션)

### 소싱 UseCase

- [x] T5.5 ScraperClient — ProductInfoCrawlerPort로 대체
- [x] T5.6 `SourcingAgent.java`, `SourcingAgentFactory.java`
- [x] T5.7 `ProductSourcingUseCase.java`
- [x] T5.8 ScrapedDataProcessor — IherbScraperClient.toScrapedDto() 인라인 처리
- [x] T5.9 `ScrapedProductDto.java`, `ProductSourcingResponse.java`

### 상품 생성 UseCase

- [x] T5.10 `ProductCreateUseCase.java`
- [x] T5.11 SKU 생성 로직 (yyMMdd + IHB + NNN)
- [x] T5.12 `Product.create()` 도메인 팩토리
- [x] T5.13 이미지 처리 파이프라인 (downloadAndConvert → uploadImages)
- [x] T5.14 `detailHtml` 템플릿 생성 (sb_top + 이미지 + sb_bottom)

### 상품 publish UseCase

- [x] T5.15 `ProductPublishUseCase.java`
- [x] T5.16 `ProductSanitizer.java` — 특수문자 제거, ProductPublishUseCase에 적용
- [x] T5.17 `ProductValidator.java` — 필수 필드 검증, ProductPublishUseCase에 적용
- [x] T5.18 MarketClient.publish → MarketRegistration 저장

### 가격 계산 엔진

- [x] T5.19 `MarginCalculator.java`
- [x] T5.20 할인 로직 (discountType==2 특가, else max(couponRate, salesDiscount))
- [x] T5.21 배송비 로직 (< 40000 → +6000)
- [x] T5.22 수수료/마진 (18.5% 채널 수수료, 100원 올림)
- [x] T5.23 최소 마진가 보장
- [x] T5.24 전략 패턴 — SourcingAgent 인터페이스로 소싱업체 확장 구조 마련 (MarginCalculator는 단일 클래스, 향후 전략화 가능)

### API 엔드포인트

- [x] T5.25 `POST /api/v1/sourcing/iherb`
- [x] T5.26 `POST /api/v1/products/bulk`
- [x] T5.27 `POST /api/v1/products/{id}/markets/{marketType}`
- [x] T5.28 `ProductSaveRequest.java`

---

## Phase 6: 배치 가격/재고 일괄 변경

### ProcessStatus 서비스

- [x] T6.1 `ProcessStatusService.java`
- [x] T6.2 `startBatch(jobType, productCodes)`
- [x] T6.3 `updateStep(batchId, productCode, step, status, message)`
- [x] T6.4 `mergeChannelResult` — ProcessStatus.mergeChannelResult()
- [x] T6.5 `getBatchStatus(batchId)` + `getAllBatchIds()` + `getAllBatches()` 구현

### BatchPriceStockService

- [x] T6.6 `BatchPriceStockService.java`
- [x] T6.7 배치 시작 → @Async (crawlAndUpdatePriceStock/manualUpdatePriceStock)
- [x] T6.8 `crawlAndUpdatePriceStock` — iHerb 크롤 → 가격 계산 → DB 저장
- [x] T6.9 `manualUpdatePriceStock` — 수동 일괄 수정
- [x] T6.10 `manualUpdateAllFields` — BatchPriceStockService에 구현
- [x] T6.11 `getProductIdsByVendor` — 소싱업체별 상품 조회
- [x] T6.12 변경 감지 로직 — manualUpdatePriceStock에서 기존 DB값과 비교, 변경 없으면 스킵

### @Async 설정

- [x] T6.13 `AsyncConfig.java` — @EnableAsync, 스레드풀
- [x] T6.14 `@Async` 적용
- [x] T6.15 배치 완료 시 SSE 알림 — BatchCompletedEvent 발행, SseNotificationController에서 수신

### API 엔드포인트

- [x] T6.16 `POST /api/v1/products/batch/crawl-and-update`
- [x] T6.17 `POST /api/v1/products/batch/manual-update-price-stock`
- [x] T6.18 `POST /api/v1/products/batch/manual-update-all` — BatchController에 구현
- [x] T6.19 `POST /api/v1/products/batch/by-supplier`
- [x] T6.20 `GET /api/v1/products/batch/status/{batchId}`
- [x] T6.21 `GET /api/v1/products/batch/status` (전체 목록) — BatchController에 구현

### 소싱업체 관리

- [x] T6.22 `GET/POST /api/v1/suppliers` — SupplierController
- [x] T6.23 `GET/POST /api/v1/currencies`
- [x] T6.24 `SupplierController.java`

### iHerb 외 소싱업체 확장

- [x] T6.25 `SourcingAgent` + `SourcingAgentFactory`

---

## Phase 7: 프론트엔드 통합

### 의존성 및 설정

- [x] T7.1 antd, @ant-design/icons, ag-grid 설치
- [x] T7.2 ConfigProvider (흑백 테마)
- [x] T7.3 code splitting (React.lazy + Suspense)

### API 레이어

- [x] T7.4 `productApi.ts`
- [x] T7.5 `sourcingApi.ts`
- [x] T7.6 `batchApi.ts`
- [x] T7.6.1 `supplierApi.ts`

### 페이지

- [x] T7.7 `ProductPage.tsx` — AG Grid 상품 목록
- [x] T7.8 `ProductRegisterPage.tsx` — iHerb 소싱 → bulk 저장
- [x] T7.9 `BatchUpdatePage.tsx` — 배치 일괄 변경
- [x] T7.10 `ProcessStatusPage.tsx` — 배치 진행 현황

### 레이아웃/네비게이션

- [x] T7.11 MainLayout 사이드바 메뉴 추가
- [x] T7.12 기존 Dashboard/OrderGrid/Settings 유지
- [x] T7.13 라우팅: /products, /register, /batch, /process-status

### 통합 검증

- [x] T7.14 상품 목록 페이지 빌드 확인
- [x] T7.15 신규 상품 등록 페이지 빌드 확인
- [x] T7.16 배치 업데이트 페이지 빌드 확인
- [x] T7.17 주문 관리 페이지 호환 확인 (빌드 단위)

---

## Phase 8: 시크릿 외부화 및 보안 강화

### 시크릿 외부화

- [x] T8.1 Cafe24 — DB 기반 credential (MarketCredential)
- [x] T8.2 Coupang — `${COUPANG_*}` env
- [x] T8.3 Smartstore — `${SMARTSTORE_*}` env
- [x] T8.4 Elevenst — `${ELEVENST_*}` env
- [x] T8.5 R2 — `${CLOUDFLARE_R2_*}` env
- [x] T8.6 ESM Plus — `${ESMPLUS_*}` env (OrderSyncController 수정)
- [x] T8.7 token_info.json, refresh_token.txt — .gitignore 추가
- [x] T8.8 하드코딩 시크릿 제거 (grep 검증)

### application.yml 정리

- [x] T8.9 모든 시크릿 `${ENV_VAR}` 참조
- [x] T8.10 `.env.example` 업데이트
- [x] T8.11 application.yml 하드코딩 제거

### Spring Security

- [x] T8.12 `SecurityConfig.java` — CORS, STATELESS, permitAll
- [x] T8.13 CORS 설정
- [x] T8.14 인증 없는 엔드포인트 permitAll 처리

### Flyway 정식 활성화

- [x] T8.15 `spring.flyway.enabled: true` (api, worker)
- [x] T8.16 `ddl-auto: none` (worker)
- [x] T8.17 V1~V3 Postgres 호환성 재작성 (BIGSERIAL, TIMESTAMP, TEXT, UPDATE FROM)
- [x] T8.18 마이그레이션 버전 V1~V4 Postgres 호환 정리 완료

---

## Phase 9: 테스트 및 배포

### 통합 테스트

- [x] T9.1 상품 도메인 테스트 (ProductTest — create, update)
- [x] T9.2 이미지 업로드/R2 연동 테스트 — ProductCreateUseCaseTest로 이미지 파이프라인 테스트 (mock)
- [ ] T9.3 배치 가격/재고 변경 테스트 — 미구현 (BatchPriceStockService 테스트 미작성)
- [x] T9.4 신규 상품 등록 파이프라인 테스트 — ProductCreateUseCaseTest (bulk 생성, 이미지 업로드)
- [ ] T9.5 마켓 클라이언트 동기화 테스트 — 미구현 (Cafe24/Coupang/Smartstore/Elevenst mock 테스트 미작성)
- [x] T9.6 ProcessStatus 배치 추적 테스트 — ProcessStatusServiceTest (배치 시작, 상태 조회)
- [ ] T9.7 주문 동기화 회귀 테스트 — 미구현 (기존 주문 동기화 호환성 테스트 미작성)

### Docker 설정

- [x] T9.8 `Dockerfile.backend` — Java 21
- [x] T9.9 `docker-compose.yml` — env 변수, Redis 추가
- [x] T9.10 `Dockerfile.frontend` — NODE_OPTIONS 메모리 확장 추가
- [ ] T9.11 Docker 빌드 검증 — 미실행 (docker build 명령 미실행)

### 배포 스크립트

- [x] T9.12 `deploy-sbshop.sh` 업데이트
- [x] T9.13 `start.sh` — 기존 스크립트 확인, 스케줄러는 @EnableScheduling로 자동 활성화되므로 변경 불필요

### 스케줄러 활성화

- [x] T9.14 `OrderSyncScheduler` — @Scheduled 주석 해제
- [x] T9.15 `ProductSyncScheduler` — 매일 새벽 4시 iHerb 재고 동기화
- [x] T9.16 `BatchScheduler` — 매일 새벽 5시 iHerb 정기 가격/재고 업데이트 구현

### 문서 업데이트

- [x] T9.17 `docs/api/product/README.md` — 상품/소싱/배치 API 문서
- [x] T9.18 `docs/api/sourcing/README.md` — 소싱 API 문서 작성
- [x] T9.19 `docs/domain/product-refactor.md` — 상품 도메인 설계 문서 작성
- [x] T9.20 `docs/api/batch/README.md` — 배치 API 문서 작성
- [x] T9.21 `.env.example` 최종 검토

---

## 진행 추적 요약

| Phase | 전체 | 완료 | 미구현 |
|---|---|---|---|
| Phase 0 | 9 | 9 | 0 |
| Phase 1 | 31 | 31 | 0 |
| Phase 2 | 16 | 16 | 0 |
| Phase 3 | 22 | 22 | 0 |
| Phase 4 | 30 | 30 | 0 |
| Phase 5 | 28 | 27 | 1 (T5.4 IherbCategoryCrawler — 옵션) |
| Phase 6 | 25 | 25 | 0 |
| Phase 7 | 18 | 18 | 0 |
| Phase 8 | 18 | 18 | 0 |
| Phase 9 | 21 | 17 | 4 (T9.3, T9.5, T9.7, T9.11) |
| **총계** | **218** | **213** | **5** |
