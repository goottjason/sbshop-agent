# 상품 도메인 설계 문서

## 개요
purchase-agent와 buying-agent의 상품 도메인을 sbshop-agent에 통합한 설계.

## 엔티티 구조

### Product (sb_product)
상품 마스터 엔티티. flat 필드 + 5개의 @Embedded VO로 구성.

| 영역 | 필드 | VO | 설명 |
|---|---|---|---|
| 식별 | sbCode | (flat) | 상품 고유 코드 (yyMMdd + IHB + NNN) |
| 명칭 | brand, productName, baseName, originalName | (flat) | 브랜드/마켓용명/기본명/원본명 |
| 분류 | category | (flat) | SUPPLEMENT / FOOD / COSMETICS / UNKNOWN |
| 가격 | costPrice, exchangeRate, deliveryFee, marginRate, salePrice | PriceInfo | 원가/환율/배송비/마진율/판매가 |
| 물류 | stock, weight, bundleQuantity | LogisticsInfo | 재고/중량/묶음수량 |
| 규격 | barcode, capacity, measureUnit | ProductSpec | 바코드/용량/단위 |
| 소싱 | vendor, sourceUrl, manufacturer, origin, hsCode | SourcingInfo | 소싱업체/URL/제조사/원산지/HS코드 |
| 이미지 | sourceImages, hostedImages | ImageInfo | JSON 배열 (원본/R2 호스팅 URL) |
| 부가 | searchKeywords, detailHtml, memo | (flat) | 검색키워드/상세HTML/메모 |
| 재고상태 | stockStatus, restockDate | (flat) | IN_STOCK/OUT_OF_STOCK, 입고예정일 |

### 도메인 메서드
- `Product.create(sbCode, command)` — 도메인 팩토리. 카테고리 추정, HS코드 할당, 마켓명 조립, detailHtml 템플릿 생성
- `Product.update(command)` — 부분 업데이트. null이 아닌 필드만 VO 빌더로 병합 (toBuilder 패턴)

### Supplier (sb_supplier)
소싱업체. supplierCode (PK), supplierName, currency (FK).

### Currency (sb_currency)
환율. currencyCode (PK), exchangeRate.

### ProcessStatus (sb_process_status)
배치 작업 추적. batchId, productCode, jobType, step, processStatus, message, details.

## 마켓 클라이언트 아키텍처

```
MarketClient (interface)
├── Cafe24MarketClient — syncImagesAndHtml (실구현), publish (실구현)
├── CoupangMarketClient — publish (실구현: CategoryPredictor→MetaService→Tags→Payload), syncImagesAndHtml (실구현)
├── SmartstoreMarketClient — OAuth2+BCrypt, publish/sync (실구현)
└── ElevenstMarketClient — XML over REST, publish/sync (실구현)
```

`MarketClientRouter`가 `MarketType` → `MarketClient` 매핑.

주문용 `MarketOrderPort`와 상품용 `MarketClient`는 별개 포트로 공존.

## 이미지 호스팅 파이프라인

```
소스 이미지 URL (iHerb)
  → ImageDownloadClient.downloadAndConvert() (OkHttp + Thumbnailator 1000x1000 JPG 80%)
  → ImageStorageClient.uploadImages() (Cloudflare R2 via S3 SDK)
  → hostedImages (JSON 배열, Product.imageInfo)
  → HtmlImageReplacer.replaceImagesBySku() (detailHtml 내 이미지 태그 교체)
```

## 가격 계산 엔진 (MarginCalculator)

```
1. 유효 원가 계산:
   - discountType==2 (특가): discountPrice
   - else: listPrice × (100 - max(couponRate, salesDiscount)) / 100

2. 총 원가 = 유효원가 × 묶음수량
   - if 총원가 < 40000: 총원가 += 6000 (배송비)

3. 판매가 = ceil(ceil(총원가 / ((100 - 마진율 - 18.5) / 100) / 100) × 100)
   - 100원 단위 올림, 18.5% = 채널 수수료

4. 최소 마진가 보장:
   - if (판매가 - 총원가) < minMarginPrice: 판매가 += (minMarginPrice - 마진)
```
