# 카페24 브랜드 API (2026-09-05, apidocs.cafe24.com 라이브 확인)

[[D-294]] 브랜드 반영과 [[D-295]] (brand_code 불일치) 해소의 근거.

## 브랜드 목록 조회

```
GET https://{mall_id}.cafe24api.com/api/v2/admin/brands
  ?brand_name=...&brand_code=...&use_brand=T&limit=100(최대)
scope: READ_COLLECTION · 40회/초 · 캐시 적용
→ brands[]: { brand_code: "B000000A", brand_name, use_brand, search_keyword, product_count }
```

## 브랜드 등록

```
POST https://{mall_id}.cafe24api.com/api/v2/admin/brands
scope: WRITE_COLLECTION · 40회/초 · 요청 1건
Body: { shop_no: 1, request: { brand_name(필수), use_brand: "T", search_keyword(<=200자) } }
→ brand: { brand_code: "B000000A", ... }
```

## 브랜드 반영 흐름 (D-294)

1. `GET /admin/brands?brand_name={브랜드명}` — 있으면 `brand_code` 사용
2. 없으면 `POST /admin/brands` 로 등록 → 반환된 `brand_code`
3. 상품 `PUT /admin/products/{no}` 에 `brand_code` 로 전송
   (**현재 코드는 `brand` 자유 텍스트를 보내고 있어 무시되는 중일 가능성 — D-295**)
