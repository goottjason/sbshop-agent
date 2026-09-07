# 쿠팡 승인 필요 필드 검토 전송

2026-09-08 구현. 운영 상품 수정이나 심사 요청은 이번 검사에서 실행하지 않았다.

## 확인한 계약과 범위

저장된 원문 `docs/external-api/coupang/쿠팡상품수정(승인필요).pdf`, `/private/tmp/sbshop-final-contracts/coupang-approval.txt`와 최신 공식 [승인 필요 상품 수정](https://developers.coupang.com/ko/api/products/modify-product), [상품 조회](https://developers.coupang.com/ko/api/products/querying-product)를 대조했다. 수정은 최신 조회 전문에서 원하는 필드만 변경하여 전체 전문을 PUT하며, `requested=true`는 저장과 심사 요청을 뜻한다. 접수 응답은 심사 완료나 실제 노출 반영 증거가 아니다.

| 시스템 필드 | 쿠팡 변경·재조회 값 |
|---|---|
| name | sellerProductName + displayProductName 두 값을 함께 검토하고 확인 |
| brand | brand |
| manufacturer | manufacture |
| barcode | 단일 items 옵션 barcode, emptyBarcode=false, emptyBarcodeReason=null. 빈 바코드 삭제는 사유 계약 확인 전 제외 |
| hostedImages | 단일 옵션 images의 순서·REPRESENTATION/DETAIL·vendorPath. 대표 1장과 추가 최대 9장 |
| detailHtml | 단일 옵션 contents의 HTML/TEXT 컨텐츠 전문 |

가격·판매용 수량·분류·구매옵션·묶음구성·상품 고시는 선택 필드에서 제외하며 최신 GET 값 그대로 유지한다. 기존 필수 옵션을 추측해서 보정하는 legacy sanitize 경로를 호출하지 않는다.

## 확인 및 실패 처리

조회와 쓰기 전후에 연동 vendorId에서 만든 계정 참조, sellerProductId, 단일 vendorItemId, sellerProductItemId, SB코드를 확인한다. 복수 옵션·다른 상품·옵션 변경은 보류한다. 심사 완료와 옵션의 명시적 onSale=true가 확인되어야 쓰며, 판매가 중지된 옵션은 자동 재개하지 않는다.

공식 statusName 중 심사중·승인대기중은 PENDING, 승인반려는 REJECTED, 승인완료만 APPROVED이다. 부분승인완료·임시저장·삭제·알 수 없는 상태는 UNKNOWN으로 남긴다. 엔진은 명시적인 심사 요청 동의 후에만 PUT하며 PENDING은 재전송 없이 재조회한다.

매 PUT 직전 queue callback을 호출하며 callback 예외는 그대로 전달한다. 429와 Retry-After를 보존하고, ERROR 봉투는 명시 거절로 분류한다. 실제 필드와 승인 상태를 별도 조회하기 전에는 성공으로 바꾸지 않는다. 대표·추가 이미지 외 중고 갤러리나 순서 모호성이 있으면 대체하지 않는다. 이미지 GET에 vendorPath가 사라지고 CDN 경로만 남으면 같은 이미지라고 추정하지 않으므로 추가 확인이 필요할 수 있다.

## 검사

`CoupangReviewedFieldsTest` 16건과 기존 쿠팡 adapter 검사 103건, 총 119건 통과. 최신 전문 보존, 선택 필드와 심사 플래그, 계정·상품·옵션·SB 불일치, callback 예외, 모든 심사 상태, 판매 중지, 이미지 모호성, 반려·429를 검증했다. 로그: `/private/tmp/sbshop-coupang-reviewed-fields-tests.log`.
