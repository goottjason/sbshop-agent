# 포트넘·코스트코 검토형 소싱 갱신

2026-09-08. 운영 DB에 저장된 실제 상품 URL을 기준으로 공개 상품 응답을 읽어 확인했다. 포트넘 butter-crackers-115g/fig-fennel-chutney-250g, 코스트코 139465/100649 상품에서 각각 정확한 식별·가격·통화·상태·이미지·상세정보를 관찰했다.

## 포트넘

`https://www.fortnumandmason.com/graphql`의 GET query로 정확한 url_key 단일 상품을 요청한다. SimpleProduct·숫자 SKU·canonical_url 일치를 요구한다. price_range 최소·최대 final_price가 같은 GBP 양수 가격이어야 한다. stock_status의 IN_STOCK/OUT_OF_STOCK만 판정한다. media_gallery의 활성 ProductImage를 position 순서로 수집하고 image.url 대표 이미지가 목록에 있어야 한다. description.html을 공통 HTML 정제·검토 흐름에 전달한다.

## 코스트코

`https://www.costco.co.uk/rest/v2/uk/products/{code}?fields=FULL`의 code와 상품 URL의 `/p/{code}`를 대조한다. configurable/multiProduct/multidimensional/hasOtherOptionsVariants 및 baseOptions를 검사하여 단일 규격만 다룬다. GBP 양수 가격과 명시 stockLevelStatus를 읽으며 inStock이면 purchasable도 확인한다. GALLERY의 모든 galleryIndex에 superZoom 이미지가 있어야 하고 index 0을 대표로 사용한다. 추천 상품·영상·다른 크기 이미지를 섞지 않는다. `_BD` 등 다른 코드를 대신 조회하여 같은 상품으로 가정하지 않는다.

## 공통 처리

이미지는 정확한 소싱처 호스트와 관측된 이미지 경로만 허용하며 공통 호스팅/정제 이후 기존 이미지·HTML과 선택 비교한다. 가격·재고는 영속 수집→정책 계산 미리보기→사용자 선택 적용 경로를 사용한다. 실재고 수량은 관측되지 않았으므로 임의로 100/999를 만들지 않는다. 환율 조회 실패 시 기존 가격을 유지하며 그 사유를 보인다.

각 HTTP 요청 직전 공유 소싱처 gate를 검사한다. 콘텐츠와 가격·재고의 서로 다른 작업도 같은 소싱처의 429 대기를 공유한다. HTTP 실패/잘못된 식별/불명 상태는 품절이나 수집 성공으로 처리하지 않는다.

공개 응답에서 필요한 필드만 남긴 fixture 2개와 파서 회귀 7건을 추가했다. 공유 429 및 소싱 적용 통합·PostgreSQL DDL을 포함한 90건이 통과했다. 운영 상품 DB 값이나 외부마켓 상품값을 이 검증으로 수정하지 않았다.
