# 카페24 세트상품 API (2026-09-05, 라이브 문서 확인)

[[D-294]] 묶음수량 불명 항목 해소.

```
GET /api/v2/admin/bundleproducts   (READ_PRODUCT, 40회/초, limit 최대 100)
→ bundleproducts[]: {
    product_no, product_code,
    bundle_product_components: [{ product_no, product_name, product_code,
                                  product_price, purchase_quantity }],
    product_tag: ["edu","test"],      ← 검색어 배열 (일반 상품에도 있는 필드)
    product_weight: "0.10",           ← kg (일반 상품에도 있는 필드)
    hscode, ...
  }
```

- 카페24의 "묶음"은 **세트상품(별도 product_no)** 로 존재하고 `bundle_product_components` 가
  구성·수량을 담는다. 우리 "묶음수량 2" 는 카페24에선 단일 상품의 표기일 뿐 세트상품이 아니다
  — 즉 **묶음수량 변경은 카페24에선 상품명·가격 수정의 문제**지 세트 API 대상이 아니다.
- `product_tag`·`product_weight` 가 검색 파라미터로도 존재 — 스키마 실재 재확인([[D-295]] ③).
