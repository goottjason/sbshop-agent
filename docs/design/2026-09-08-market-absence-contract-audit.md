# 현재 단일 계정의 상품 부재 판별 근거

2026-09-08 사용자가 쿠팡·11번가·카페24의 기존 상품은 각 현재 단일 계정에 속한다고 확인했다. 이 확인에 따라 현재 계정의 인증된 상품 조회가 아래의 구체적인 부재 응답을 반환하면 활성 연결 제외 후보로 분류한다. 계정 고정·연결 이력 보존·실제 해제는 코어 점검 서비스에서 수행하며, 어댑터는 조회 관측만 반환한다.

## 실제 읽기 관측과 구현

| 마켓 | 읽은 저장 상품 | 실제 응답 | 부재 판별 조건 |
|---|---|---|---|
| 쿠팡 | 기존 연결 4건 | HTTP 400, `application/json`, `{"code":"DEFAULT","message":"Product(<요청한 등록상품 ID>) data not found."}` | 인증된 단건 조회의 400 응답에 한정한다. 정확한 코드·요청 ID·문구, JSON 두 필드만 존재, 중복 키·뒤따르는 JSON 없음, 조회 전후 동일 계정 참조를 확인한다. |
| 11번가 | 기존 연결 4건 | HTTP 200 XML, 루트 `Product`, `message=[<ID>] 상품 정보 조회중 오류입니다.해당 상품의 정보를 찾을 수 없습니다. 상품번호 : <ID>`, `nResult=0` | `requestStrict(GET)`의 성공 응답에서 정확한 요청 ID 두 곳·문구·nResult를 확인한다. 상품번호/상품명/SB코드/판매상태/결과코드/검증 오류가 비어 있어야 한다. 중복 필드와 안전하지 않은 XML은 거절하고 조회 전후 동일 계정을 확인한다. |
| 카페24 | 기존 연결 2건 및 정상 대조 상품 1건 | 부재 상품의 단건 GET은 HTTP 200 `{"product":{}}`, 동일 상품번호를 지정한 목록 GET은 HTTP 200 `{"products":[]}` | 단건 응답 하나만으로 판단하지 않는다. 같은 `shop_no=1`과 정확한 `product_no` 필터로 별도 목록 조회도 빈 배열인지 확인한다. 두 응답은 각각 해당 필드만 존재해야 하며 중복 키·뒤따르는 JSON·조회 사이 계정 변경은 거절한다. 정상 대조 상품은 두 조회 모두 같은 상품번호/SB코드를 반환했다. |

부재 관측 코드는 `PRODUCT_ABSENT/COUPANG_NOT_FOUND`, `PRODUCT_ABSENT/ELEVENST_NOT_FOUND`, `PRODUCT_ABSENT/CAFE24_EMPTY_PRODUCT_AND_FILTERED_LIST`다. 코어는 이 접두사를 명시적 상품 삭제·영구 금지와 구분하며, 해당 마켓의 사용자가 확인한 계정 참조가 현재 참조와 일치할 때만 자동 해제를 허용한다.

조회 경로는 다음과 같다.

- 쿠팡: `GET /v2/providers/seller_api/apis/api/v1/marketplace/seller-products/{sellerProductId}`
- 11번가: `GET /rest/prodmarketservice/prodmarket/{prdNo}`
- 카페24 단건: `GET /admin/products/{product_no}?shop_no=1`
- 카페24 재확인: `GET /admin/products?shop_no=1&product_no={product_no}&fields=product_no,shop_no,custom_product_code&limit=2`

원본 결과는 운영 백업 경로 `/home/ubuntu/backups/sbshop-products-delivery-20260908/market-absence-observation.json`과 `cafe24-absence-confirm.json`에 보관했다. 저장소에는 비밀값·인증 헤더·상품 상세 HTML을 제외한 [관측 증거](evidence/2026-09-08-market-absence-observations.json)를 기록했다. 최초 관측은 2026-09-08 09:04 KST, 카페24 대조 재조회는 09:07 KST다. 이번 조사에서는 마켓 쓰기, 연결 상태 변경, DB 스케줄 변경을 수행하지 않았다.

## 공식 계약과 관측 근거의 경계

쿠팡의 인증된 단건 조회 경로·반환 상품번호·판매자 ID·등록 상태는 [공식 상품 조회 문서](https://developers.coupang.com/ko/api/products/querying-product)에서 확인했다. 위 `DEFAULT` 오류의 정확한 문구는 문서화된 오류 코드라고 주장하지 않는다. 현재 계정에 저장된 서로 다른 4개 상품번호의 실제 응답을 근거로 제한적으로 허용한다. 상품번호가 다르거나 일반 `ERROR`, 다른 상태 코드, HTML, 빈 응답이면 부재로 판단하지 않는다.

11번가의 원문은 [신규상품조회.pdf](../external-api/elevenst/신규상품조회.pdf) 1–3쪽이며, 단건 경로와 `prdNo`, `sellerPrdCd`, `selStatCd` 계약을 확인했다. 위 오류 XML은 기존 4개 연결의 실제 인증 응답에서 확인했다. 일반 `ClientMessage` 오류, ‘상품이 없습니다’ 같은 유사 문구, HTTP 404 오류 본문에 동일 문구가 포함된 경우는 부재 증거로 채택하지 않는다. 수량 변경·신규 등록 계약의 미확보 사항은 [별도 재감사](2026-09-08-elevenst-stock-publication-reaudit.md)에 그대로 남아 있다.

카페24의 Admin 상품 목록과 `product_no` 필터는 [공식 Admin API](https://developers.cafe24.com/docs/ko/api/admin/) 및 로컬 원문 추출본 `/private/tmp/sbshop-final-contracts/cafe24.txt`의 상품 목록 구간(5974행 이후)에서 확인했다. 정상 대조 상품 328 / 10619와 부재 상품 2067 / 18686, 3133 / 5495로 같은 필터의 실제 동작을 검증했다. 일반 404는 잘못된 URL 등 여러 이유로도 발생할 수 있다. 또한 [Front API의 상품 조회 변경 공지](https://developers.cafe24.com/cs/front/notice/372)는 Admin API의 부재 계약으로 전용하지 않았다. 카페24 본상품 부재를 G마켓·옥션의 부재로 복제하지 않는다.

## 검증과 배포 후 표본

`MarketplaceMissingProductEvidenceTest` 58건과 기존 `MarketplaceInspectionEvidenceTest` 20건, 합계 78건이 통과했다(실패·스킵 0). 실제 부재 형태, 다른 요청 ID, 권한 오류/일반 404/429/5xx, JSON 중복·추가 필드·잘못된 Content-Type, 모순된 XML, 계정 변경, 카페24 이중 조회의 불일치, 정상 판매/중단 상태를 포함한다. 429의 `Retry-After`는 미확정 상태와 함께 유지된다. 변경한 어댑터 3개와 신규 테스트만 포맷했다.

배포 후 코어 연결 점검으로 확인할 최소 표본은 `/private/tmp/sbshop-market-absence-detach-samples.json`에 전달했다. 최초 조사 시 모두 `LINKED`이며, 기존 `unsyncReason=DELETED_ON_MARKET`만으로 새 판정을 내리지 않았다.

| 마켓 | productId | registrationId | 외부 상품번호 |
|---|---:|---:|---|
| 쿠팡 | 1563 | 6369 | 14813282146 |
| 11번가 | 1223 | 7643 | 3782058673 |
| 카페24 | 2067 | 2061 | 18686 |

이 문서는 구현·로컬 회귀 및 읽기 관측 완료 시점의 기록이다. 실제 활성 연결 제외/외부 번호 이력 보존의 운영 검증 결과는 배포 후 별도로 기록한다.
