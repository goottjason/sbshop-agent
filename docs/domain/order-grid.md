# 통합주문관리 그리드

## GET /api/v1/orders 응답 구조

```
Page<OrderDetailDto> {
  content: [
    {
      order: Order {
        id, marketType, marketOrderNo, orderDate,
        recipientName, recipientPhone, zipcode, address, message,
        ordererName, ordererPhone,
        customsData: { customsClearanceNo, customsStatus }
      },
      lineItems: [
        {
          lineItem: OrderLineItem {
            id, orderId, productId, quantity,
            sourcingData: { sourcingAccount, sourcingOrderNo, sourcingAmount, discountCode },
            settlementData: { settlementAmount, shippingFee, settlementVerified },
            shippingData: { trackingNo, isUnipassDone, shippingStatus, shippingCarrier, trackingSentToMarket }
          },
          product: Product {
            id, sbCode, category, vendor,
            productName: { productName, originalName },
            sourcingInfo: { url },
            stockStatus, restockDate,
            productSpec: { bundleQuantity }
          },
          marketRegistration: MarketRegistration { ... }
        }
      ]
    }
  ],
  totalElements, totalPages, size, number
}
```

## 그리드 컬럼 일람

| 순서 | 헤더명 | 프론트 accessor 경로 | GET /api/v1/orders 응답 필드 | DB 테이블 및 컬럼 | UI 타입 | 비고 | 수정 |
|------|--------|---------------------|-----------------------------|-------------------|---------|------|------|
| 1 | — | `id: 'select'` | — | — | checkbox | 행 선택 (rowSelection) | N |
| 2 | 주문일 | `order.orderDate` | `order.orderDate` | `sb_order.order_date` | text | `toLocaleDateString()` 포맷, 고정(frozen) | N |
| 3 | 마켓 | `order.marketType` | `order.marketType` | `sb_order.market_type` | badge | 마켓별 색상 표시, 필터 아이콘 포함, 고정 | N |
| 4 | 주문번호 | `order.marketOrderNo` | `order.marketOrderNo` | `sb_order.market_order_no` | text | 고정 | N |
| 5 | 주문상태 | `lineItem.shippingData.shippingStatus` | `lineItem.shippingData.shippingStatus` | `sb_order_line_item.shipping_status` | badge | 상태별 색상 표시, UNKNOWN=회색, 고정 | N |
| 6 | 수취인명 | `order.recipientName` | `order.recipientName` (+ `ordererName`) | `sb_order.recipient_name` | text | 수취인≠주문자일 경우 두 줄 표시, 통관상태에 따라 색상: VALID=수취인파랑/주문자빨강, INVALID=수취인빨강/주문자파랑, PENDING/없음=둘다검정, 고정 | N |
| 7 | 통관번호 | `order.customsData.customsClearanceNo` | `order.customsData.customsClearanceNo` | `sb_order.customs_clearance_no` | input | `onBlur` 시 PATCH 요청 | Y |
| 8 | 통관상태 | `order.customsData.customsStatus` | `order.customsData.customsStatus` | `sb_order.customs_status` | badge | VALID/INVALID/PENDING 등 색상 표시, 새로고침 버튼 포함 | N |
| 9 | 휴대폰 | `order.recipientPhone` | `order.recipientPhone` | `sb_order.recipient_phone` | text | | N |
| 10 | 우편번호 | `order.zipcode` | `order.zipcode` | `sb_order.zipcode` | text | | N |
| 11 | 주소 | `order.address` | `order.address` | `sb_order.address` | input | `onBlur` 시 PATCH 요청 | Y |
| 12 | 배송메시지 | `order.message` | `order.message` | `sb_order.message` | text | | N |
| 13 | 등록상품명 | `product.productName.productName` | `product.productName.productName` | `sb_product.product_name` (JSON) | text | | N |
| 14 | 영문상품명 | `product.productName.originalName` | `product.productName.originalName` | `sb_product.product_name` (JSON) | text | 소싱 URL 있을 경우 링크로 표시 | N |
| 15 | 재고현황 | `product.stockStatus` | `product.stockStatus` | `sb_product.stock_status` | badge | IN_STOCK=구입가능(초록), OUT_OF_STOCK=품절(빨강), 새로고침 버튼 포함 | N |
| 16 | 입고예정일 | `product.restockDate` | `product.restockDate` | `sb_product.restock_date` | text | | N |
| 17 | 묶음 | `product.productSpec.bundleQuantity` | `product.productSpec.bundleQuantity` | `sb_product.product_spec` (JSON) | text | | N |
| 18 | 수량 | `lineItem.quantity` | `lineItem.quantity` | `sb_order_line_item.quantity` | text | | N |
| 19 | 총수량 | `id: 'totalQuantity'` | — (계산값) | — | text | `묶음수량 × 수량`으로 실시간 계산 | N |
| 20 | 구매계정 | `lineItem.sourcingData.sourcingAccount` | `lineItem.sourcingData.sourcingAccount` | `sb_order_line_item.sourcing_account` | text | PURCHASED 상태에서 actions 버튼을 통해 모달 수정 | Y(modal) |
| 21 | 구매번호 | `lineItem.sourcingData.sourcingOrderNo` | `lineItem.sourcingData.sourcingOrderNo` | `sb_order_line_item.sourcing_order_no` | text | PURCHASED 상태에서 actions 버튼을 통해 모달 수정 | Y(modal) |
| 22 | 할인코드 | `lineItem.sourcingData.discountCode` | `lineItem.sourcingData.discountCode` | `sb_order_line_item.discount_code` | input | `onBlur` 시 PATCH 요청 | Y |
| 23 | 택배사 | `lineItem.shippingData.shippingCarrier` | `lineItem.shippingData.shippingCarrier` | `sb_order_line_item.shipping_carrier` | select | CJ대한통운/우체국/롯데택배 중 선택, 송장번호 있을 때만 활성화 | Y |
| 24 | 송장번호 | `lineItem.shippingData.trackingNo` | `lineItem.shippingData.trackingNo` | `sb_order_line_item.tracking_no` | input | `onBlur` 시 PATCH 요청 | Y |
| 25 | 유니패스 | `lineItem.shippingData.isUnipassDone` | `lineItem.shippingData.isUnipassDone` | `sb_order_line_item.is_unipass_done` | checkbox | `onChange` 시 PATCH 요청 | Y |
| 26 | 정산금액 | `lineItem.settlementData.settlementAmount` | `lineItem.settlementData.settlementAmount` | `sb_order_line_item.settlement_amount` | text(bold) | 파란색 굵은 글씨, toLocaleString 포맷 | N |
| 27 | 실구매가 | `lineItem.sourcingData.sourcingAmount` | `lineItem.sourcingData.sourcingAmount` | `sb_order_line_item.sourcing_amount` | input(number) | `onBlur` 시 PATCH 요청 | Y |
| 28 | 배송비 | `lineItem.settlementData.shippingFee` | `lineItem.settlementData.shippingFee` | `sb_order_line_item.shipping_fee` | input(number) | `onBlur` 시 PATCH 요청 | Y |
| 29 | 순수익 | `id: 'netProfit'` | — (계산값) | — | text(bold) | `정산금액 - 실구매가 - 배송비`로 실시간 계산, 양수=초록 음수=빨강 | N |
| 30 | 처리 | `id: 'actions'` | — | — | button | 주문상태에 따라 다른 버튼: PREPARING=구매처리, PURCHASED=구매정보수정+배송처리, SHIPPED=송장수정, DELIVERED=대기 | Y(액션) |

## UI 타입 범례

| UI 타입 | 설명 |
|---------|------|
| text | 단순 텍스트 표시 (읽기 전용) |
| text(bold) | 굵은 글씨 텍스트 |
| input | `<input>` 텍스트 필드 (onBlur 시 DB 저장) |
| input(number) | `<input type="number">` 숫자 필드 |
| checkbox | `<input type="checkbox">` |
| select | `<select>` 드롭다운 |
| badge | `<span>` 배지 (상태별 색상) |
| button | `<button>` (액션 트리거) |

## PATCH 요청 경로

`onBlur` 또는 `onChange` 시 호출되는 `handleUpdate(orderId, lineItemId, fieldPath, value)`의 `fieldPath` 값은 백엔드 `OrderLineItemUpdateRequest` DTO의 필드명과 매핑됩니다.

| fieldPath 값 | 매핑 DTO 필드 |
|-------------|---------------|
| `order.customsClearanceNo` | `OrderUpdateRequest.customsClearanceNo` |
| `order.address` | `OrderUpdateRequest.address` |
| `lineItem.sourcingAccount` | `OrderLineItemUpdateRequest.sourcingAccount` (modal 전용, input 미사용) |
| `lineItem.sourcingOrderNo` | `OrderLineItemUpdateRequest.sourcingOrderNo` (modal 전용, input 미사용) |
| `lineItem.discountCode` | `OrderLineItemUpdateRequest.discountCode` |
| `lineItem.shippingCarrier` | `OrderLineItemUpdateRequest.shippingCarrier` |
| `lineItem.trackingNo` | `OrderLineItemUpdateRequest.trackingNo` |
| `lineItem.isUnipassDone` | `OrderLineItemUpdateRequest.isUnipassDone` |
| `lineItem.sourcingAmount` | `OrderLineItemUpdateRequest.sourcingAmount` |
| `lineItem.shippingFee` | `OrderLineItemUpdateRequest.shippingFee` |

## 액션 버튼 일람

| 주문상태 | 표시 버튼 | 열리는 모달 | 모달 필드 |
|---------|-----------|------------|----------|
| PREPARING | 구매처리 | 구매 처리 모달 | 공급업체(iherb/other), 이메일계정, 아이허브주문번호/공급업체명, 할인코드 |
| PURCHASED | 구매정보수정 | 구매정보 수정 모달 | 이메일계정, 구매번호, 할인코드 |
| PURCHASED | 배송처리 | 배송 처리 모달 | 택배사, 송장번호 |
| SHIPPED | 송장수정 | 송장 수정 모달 | 택배사, 송장번호 |
