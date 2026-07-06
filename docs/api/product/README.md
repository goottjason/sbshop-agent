# 상품 관리 API

## 상품 목록 조회
`GET /api/v1/products`

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| keyword | String | X | 상품명, SB코드, 브랜드 검색 |
| page | int | X | 페이지 번호 (기본값 0) |
| size | int | X | 페이지 크기 (기본값 50) |

## 상품 상세 조회
`GET /api/v1/products/{id}`

## 가격/재고 수정
`PUT /api/v1/products/{id}/price-stock`
```json
{ "price": 50000, "stock": 100 }
```

## 이미지 업로드 (파일)
`PUT /api/v1/products/{id}/images` (multipart/form-data)
- `images`: MultipartFile 리스트 (1000x1000 JPG 80%로 자동 리사이즈 → Cloudflare R2 업로드 → 상세 HTML 이미지 교체)

## 이미지 업로드 (URL)
`PUT /api/v1/products/{id}/images/by-url`
```json
["https://img.iherb.com/1.jpg", "https://img.iherb.com/2.jpg"]
```

## 소스 이미지 크롤
`GET /api/v1/products/{id}/images/crawl`
- 상품의 소싱 URL(iHerb)에서 이미지 URL 목록을 크롤하여 반환

## 전체 필드 수정
`PUT /api/v1/products/{id}`
```json
{ "brand": "KAL", "salePrice": 50000, "stock": 100, ... }
```

## 상품 삭제
`DELETE /api/v1/products/{id}`

## 마켓 등록 목록
`GET /api/v1/products/{id}/markets`

## 마켓별 로컬 데이터
`GET /api/v1/products/{id}/markets/{marketType}/local`

## 마켓 실시간 동기화
`POST /api/v1/products/{id}/markets/{marketType}/sync`
```

# 소싱 API

## iHerb 상품 소싱
`POST /api/v1/sourcing/iherb`
```json
["https://www.iherb.com/pr/calcium/12345", "https://www.iherb.com/pr/magnesium/67890"]
```
응답: 스크래핑된 상품 정보 리스트 (상품명, 브랜드, 가격, 이미지, 카테고리 등)

## 상품 일괄 저장
`POST /api/v1/products/bulk`
```json
[{ "sourceUrl": "...", "baseName": "...", "brand": "...", "costPrice": 25.00, "sourceImages": [...], ... }]
```
- SKU 자동 생성 (yyMMdd + IHB + NNN)
- 이미지 R2 업로드 → Product.create() 도메인 팩토리 → DB 저장

## 마켓 상품 등록 (Publish)
`POST /api/v1/products/{id}/markets/{marketType}`
- Coupang: 카테고리 예측 → 메타 조회 → 태그 생성 → 상품 등록
- Cafe24: 상품 등록 API 호출
- Smartstore: OAuth2 인증 → 상품 등록
- Elevenst: XML 상품 등록
```

# 배치 API

## 크롤 기반 일괄 가격/재고 변경
`POST /api/v1/products/batch/crawl-and-update`
```json
{ "productIds": [1, 2, 3], "marginRate": 15, "couponRate": 20, "minMarginPrice": 5000 }
```
- iHerb 크롤 → 가격 계산 → DB 저장 (비동기 @Async)

## 수동 일괄 가격/재고 수정
`POST /api/v1/products/batch/manual-update-price-stock`
```json
{ "productIds": [1, 2], "prices": [50000, 30000], "stocks": [100, 50] }
```

## 소싱업체별 일괄 업데이트
`POST /api/v1/products/batch/by-supplier`
```json
{ "supplierCode": "IHB", "marginRate": 15, "couponRate": 20, "minMarginPrice": 5000 }
```

## 배치 진행 상태 조회
`GET /api/v1/products/batch/status/{batchId}`
- batchId별 상품별 진행 상태 (step, status, message)
