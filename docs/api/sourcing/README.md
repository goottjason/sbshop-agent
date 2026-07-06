# 소싱 API

## iHerb 상품 소싱
`POST /api/v1/sourcing/iherb`

**Request Body:** `List<String>` (URL 배열)
```json
["https://www.iherb.com/pr/calcium/12345", "https://www.iherb.com/pr/magnesium/67890"]
```

**Response:** `List<ProductSourcingResponse>`
```json
[{
  "sourceUrl": "https://www.iherb.com/pr/calcium/12345",
  "baseName": "Magnesium Taurate",
  "originalName": "Magnesium Taurate 400mg",
  "brand": "KAL",
  "costPrice": 25.00,
  "listPrice": 35.00,
  "discountPrice": 25.00,
  "discountType": 1,
  "couponRate": 20,
  "salesDiscount": 15,
  "isAvailable": true,
  "sourceImages": ["https://s3.images-iherb.com/..."],
  "rawCategory": "비타민/미네랄",
  "capacity": 400,
  "unit": "정",
  "assembledNamePreview": "KAL Magnesium Taurate, 400정, 1개"
}]
```

## 상품 일괄 저장
`POST /api/v1/products/bulk`

**Request Body:** `List<ProductSaveRequest>`
```json
[{
  "sourceUrl": "https://www.iherb.com/pr/12345",
  "baseName": "Magnesium Taurate",
  "brand": "KAL",
  "costPrice": 25.00,
  "capacity": 400,
  "measureUnit": "TABLET",
  "sourceImages": ["https://s3.images-iherb.com/..."],
  "isAvailable": true,
  "bundleQuantity": 2,
  "marginRate": 20,
  "vendor": "IHB"
}]
```

**동작:**
1. SKU 자동 생성 (yyMMdd + IHB + NNN)
2. 소스 이미지 → Cloudflare R2 업로드 (1000x1000 JPG 80% 리사이즈)
3. `Product.create()` 도메인 팩토리 호출
   - 카테고리 자동 추정 (비타민/미네랄 → SUPPLEMENT)
   - HS코드 자동 할당 (2106.90.9099)
   - 마켓용 상품명 조립
   - detailHtml 템플릿 생성
4. DB 일괄 저장

## 마켓 상품 등록 (Publish)
`POST /api/v1/products/{id}/markets/{marketType}`

**지원 마켓:**
- `COUPANG` — 카테고리 예측 → 메타 조회 → 태그 생성 → 상품 등록
- `CAFE24` — 상품 등록 API (POST /admin/products)
- `SMART_STORE` — OAuth2 인증 → 상품 등록 (POST /v2/products)
- `ELEVEN_STREET` — XML 상품 등록 (POST /rest/prodservices/product)

**동작:**
1. ProductSanitizer — 특수문자 제거
2. ProductValidator — 필수 필드 검증 (상품명, 브랜드, 가격, 이미지, HTML)
3. MarketClient.publish() 호출
4. 반환된 마켓 상품 ID로 MarketRegistration 저장
