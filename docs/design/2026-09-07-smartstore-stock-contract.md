# 스마트스토어 판매용 수량 계약 — API 2.88.0

2026-09-07 현재 공식 문서와 해당 문서가 제공하는 압축 API schema를 대조했다. 기존 수량 영속 큐에 SMART_STORE를 추가하며 가격·판매 상태·다른 필드 성공과 분리한다.

| 상품 형태 | 전송 | 보존/제한 |
|---|---|---|
| 원상품 수량(조합/표준 재고 옵션 없음) | `PATCH /v1/products/origin-products/multi-update`, `multiProductUpdateRequestVos:[{originProductNo, multiUpdateTypes:["STOCK"], stockQuantity}]` | 상태·가격·할인 필드를 전송하지 않음 |
| 정확한 단일 조합형 옵션 | `PUT /v1/products/origin-products/{originProductNo}/option-stock`, `optionInfo.optionCombinations:[{id,stockQuantity,price,usable}]` | 현재 옵션가/사용 여부를 반드시 재조회하여 보존. 생략하면 문서상 옵션가 0/usable=true가 될 수 있음 |
| 정확한 단일 표준형 옵션 | 동일 PUT의 `optionInfo.optionStandards:[{id,stockQuantity,usable}], useStockManagement:true` | 기존 재고 관리 true인 경우만 허용; false/불명 설정을 자동 활성화하지 않음 |
| 다중 옵션, 불명 옵션/가격/사용 여부, SKU 연결 | 보류 | SB상품과 옵션 배분/재고 관리 주체를 추정하지 않음 |

조회는 `GET /v2/products/origin-products/{originProductNo}`이다. 공식 응답 원상품 구조에는 원상품 번호가 없으므로 정확한 요청 경로, 계정 참조, `detailAttribute.sellerCodeInfo.sellerManagementCode`의 SB 일치를 확인한다. 응답에 추가 `id`가 있으면 불일치도 거절한다. 원상품/해당 단일 옵션의 `stockQuantity`는 실제 정수이며 0~99,999,999 범위여야 한다. 시스템 판매용 설정 수량은 기존 0~999,999 정책을 유지한다.

`SALE`, 정상 일시 품절 `OUTOFSTOCK`만 허용한다. `SUSPENSION`, `PROHIBITION`, `DELETE`, 심사/미승인/불명 상태 및 옵션 사용 중지는 자동 쓰기를 보류한다. 판매 상태 재개 API나 `PRODUCT_STATUS_SALE`을 호출하지 않는다.

큐의 resolvedOptionId에는 `ORIGIN:{원상품번호}`, `COMBINATION:{옵션번호}`, `STANDARD:{옵션번호}`를 고정해 같은 숫자의 옵션 타입 변경까지 탐지한다. 전송 직전 adapter 재조회 후 기존 durable guard로 revision·연결·계정·lease를 재검사한다. 요청 접수는 성공 증거가 아니며 별도 GET의 실제 수량 일치만 `CONFIRMED_QUANTITY`로 기록한다. 명시 4xx, 결과 불명, 429 Retry-After는 기존 stock 큐 정책을 따른다.

saved target 분류는 COUPANG/SMART_STORE의 salesQuantity(+memo)를 수량 큐에 배정할 수 있게 확장했다. 실제 저장/자동 접수 허용 여부는 ProductEditPolicy의 조건을 별도로 따라야 한다.

공식 출처: [멀티 상품 변경](https://apicenter.commerce.naver.com/docs/commerce-api/current/update-multi-products-product), [상품 옵션 재고 변경](https://apicenter.commerce.naver.com/docs/commerce-api/current/update-options-product), [(v2) 원상품 조회](https://apicenter.commerce.naver.com/docs/commerce-api/current/read-origin-product-product). 문서 화면과 schema `info.version` 모두 2.88.0으로 확인했다. 로컬 decode 자료는 `/private/tmp/naver-stock-{multi,options,origin}-current.json`이며 운영 데이터/자격증명을 포함하지 않는다.

운영 마켓 쓰기는 실행하지 않았다. 쿠팡/Cafe24 검토 등록 확대는 별도 후속 단위로 남긴다.
