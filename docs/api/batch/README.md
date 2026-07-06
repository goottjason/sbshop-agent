# 배치 API

## 크롤 기반 일괄 가격/재고 변경
`POST /api/v1/products/batch/crawl-and-update`

**Request:**
```json
{
  "productIds": [1, 2, 3],
  "marginRate": 15,
  "couponRate": 20,
  "minMarginPrice": 5000
}
```

**동작 (비동기 @Async):**
1. iHerb 크롤 → 원가/재고/입고예정일 조회
2. MarginCalculator로 판매가 계산 (할인/쿠폰/배송비/18.5% 수수료/100원 올림)
3. Product 엔티티 업데이트 → DB 저장
4. process_status 테이블에 단계별 상태 기록
5. 완료 시 BatchCompletedEvent 발행 (SSE 알림)

## 수동 일괄 가격/재고 수정
`POST /api/v1/products/batch/manual-update-price-stock`

**Request:**
```json
{
  "productIds": [1, 2],
  "prices": [50000, 30000],
  "stocks": [100, 50]
}
```

**특징:** 기존 DB 값과 비교하여 변경된 필드만 업데이트 (변경 감지 로직)

## 전체 필드 일괄 수정
`POST /api/v1/products/batch/manual-update-all`

**Request:**
```json
{
  "productIds": [1, 2],
  "commands": [{ "brand": "...", "salePrice": 50000, ... }]
}
```

## 소싱업체별 일괄 업데이트
`POST /api/v1/products/batch/by-supplier`

**Request:**
```json
{
  "supplierCode": "IHB",
  "marginRate": 15,
  "couponRate": 20,
  "minMarginPrice": 5000
}
```

## 배치 진행 상태 조회
`GET /api/v1/products/batch/status/{batchId}`

**Response:** `List<ProcessStatus>`
```json
[{
  "batchId": "a1b2c3d4",
  "productCode": "260707IHB001",
  "jobType": "CRAWL_AND_UPDATE_PRICE_STOCK",
  "step": "UPDATE_PRODUCT_SAVE",
  "processStatus": "SUCCESS",
  "message": "가격:50000, 재고:100",
  "startedAt": "2026-07-06T15:00:00"
}]
```

## 전체 배치 목록
`GET /api/v1/products/batch/status`

**Response:** `List<String>` (batchId 목록)
