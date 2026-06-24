# 스마트스토어 조건형 상품 주문 상세 내역 조회

> **Endpoint**: `GET /external/v1/pay-order/seller/product-orders`
> **Base URL**: `https://api.commerce.naver.com`
> **Purpose**: 조건에 맞는 상품 주문에 대한 상세 내역을 조회 (Order/OrderLineItem insert/select의 핵심 API)

---

## 1. Request

### URL Path

```
GET /external/v1/pay-order/seller/product-orders
```

### Headers

| Header          | Value                       | Description      |
|-----------------|-----------------------------|------------------|
| `Authorization` | `Bearer {accessToken}`      | OAuth2 엑세스 토큰 |
| `Content-Type`  | `application/json`          | 요청 형식         |
| `Accept`        | `application/json`          | 응답 형식         |

### Query Parameters

| Parameter              | Required | Default        | Description                                                  |
|------------------------|----------|----------------|--------------------------------------------------------------|
| `from`                 | Y        | -              | 조회 기준 시작 일시 (ISO 8601, inclusive)                      |
| `to`                   | N        | from + 24시간   | 조회 기준 종료 일시 (ISO 8601, inclusive)                      |
| `rangeType`            | N        | `PAYED_DATETIME` | 조회 기준 유형                                              |
| `productOrderStatuses` | N        | -              | 상품 주문 상태 목록 (배열)                                     |
| `claimStatuses`        | N        | -              | 클레임 상태 목록 (배열)                                        |
| `placeOrderStatusType` | N        | -              | 발주 상태 필터                                                |
| `fulfillment`          | N        | -              | 풀필먼트 배송 여부 (null: 전체, false: 일반, true: 풀필먼트)     |
| `pageSize`             | N        | `300`          | 페이징 사이즈 (1~300)                                         |
| `page`                 | N        | `1`            | 페이지 번호 (1부터)                                           |

### rangeType 값

| 코드                     | 설명           |
|--------------------------|----------------|
| `PAYED_DATETIME`         | 결제일시       |
| `ORDERED_DATETIME`       | 주문일시       |
| `DISPATCHED_DATETIME`    | 발송처리일시   |
| `PURCHASE_DECIDED_DATETIME` | 구매확정일시 |
| `CLAIM_REQUESTED_DATETIME`  | 클레임요청일시 |
| `CLAIM_COMPLETED_DATETIME`  | 클레임완료일시 |

### productOrderStatuses 값

| 코드                     | 설명       | 내부 매핑    |
|--------------------------|-----------|-------------|
| `PAYED`                  | 결제 완료  | `NEW`       |
| `DELIVERING`             | 배송 중    | `SHIPPED`   |
| `DELIVERED`              | 배송 완료  | `DELIVERED` |
| `PURCHASE_DECIDED`       | 구매 확정  | -           |
| `CANCELED`               | 취소       | `CANCELED`  |
| `RETURNED`               | 반품       | `RETURNED`  |
| `EXCHANGED`              | 교환       | `EXCHANGED` |
| `CANCELED_BY_NOPAYMENT`  | 미결제 취소 | `CANCELED` |

> **주의**: `PAYMENT_WAITING`(결제 대기)은 조회에서 제외

### placeOrderStatusType 값

| 코드        | 설명         | 내부 매핑    |
|-------------|-------------|-------------|
| `NOT_YET`   | 발주 미확인  | `NEW`       |
| `OK`        | 발주 확인    | `PREPARING` |
| `CANCEL`    | 발주 확인 해제 | `CANCELED` |

### Request Example

```
GET /external/v1/pay-order/seller/product-orders
    ?from=2024-06-07T19:00:00.000+09:00
    &to=2024-06-08T19:00:00.000+09:00
    &rangeType=PAYED_DATETIME
    &productOrderStatuses=PAYED
    &pageSize=300
    &page=1
```

---

## 2. Response

### Top Level

```json
{
  "timestamp": "2023-01-16T17:14:51.794+09:00",
  "traceId": "...",
  "data": {
    "contents": [ ... ],
    "pagination": {
      "page": 1,
      "size": 300,
      "hasNext": false
    }
  }
}
```

| Field                    | Type    | Description                    |
|--------------------------|---------|--------------------------------|
| `timestamp`              | string  | 응답 시각                       |
| `traceId`                | string  | 추적 ID                         |
| `data.contents`          | array   | 주문 상세 목록                   |
| `data.pagination.page`   | number  | 현재 페이지                      |
| `data.pagination.size`   | number  | 한 페이지 항목 수                |
| `data.pagination.hasNext`| boolean | 다음 페이지 존재 여부             |

> **페이징 방식**: `page` 기반. `hasNext`가 `false`일 때까지 `page`를 증가시키며 반복 요청.

---

## 3. data.contents[] (상품 주문 항목)

### 구조 요약

```
contents[]
├── productOrderId        (상품 주문 번호)
├── content
│   ├── order             (주문 정보)
│   ├── productOrder      (상품 주문 정보)
│   ├── currentClaim      (현재 클레임 정보)
│   └── delivery          (배송 정보)
```

### order (주문 정보)

| 필드                            | 타입    | Nullable | Description                    |
|---------------------------------|---------|----------|--------------------------------|
| `orderId`                       | string  | N        | 주문 번호                       |
| `ordererName`                   | string  | N        | 주문자 이름 (선물 시 마스킹)     |
| `ordererTel`                    | string  | N        | 주문자 연락처 (선물 시 마스킹)   |
| `ordererId`                     | string  | Y        | 주문자 ID                       |
| `ordererNo`                     | string  | Y        | 주문자 번호                     |
| `orderDate`                     | string  | N        | 주문 일시 (ISO 8601)            |
| `paymentDate`                   | string  | N        | 결제 일시                       |
| `totalPaymentAmount`            | number  | N        | 최초 결제 금액 (할인 적용 후)    |
| `generalPaymentAmount`          | number  | Y        | 일반 결제 수단 금액              |
| `chargeAmountPaymentAmount`     | number  | Y        | 충전금 결제 금액                 |
| `naverMileagePaymentAmount`     | number  | Y        | 네이버페이 포인트 결제 금액       |
| `checkoutAccumulationPaymentAmount` | number | Y     | 네이버페이 적립금 결제 금액       |
| `orderDiscountAmount`           | number  | Y        | 주문 할인액                     |
| `paymentMeans`                  | string  | Y        | 결제 수단 (신용카드, 휴대폰 등)   |
| `isDeliveryMemoParticularInput`| string  | Y        | 배송 메모 개별 입력 여부         |
| `payLocationType`              | string  | Y        | 결제 위치 (PC/MOBILE)           |
| `isMembershipSubscribed`        | boolean | Y        | 주문시점 멤버십 여부              |

### productOrder (상품 주문 정보)

| 필드                              | 타입    | Nullable | Description                      |
|-----------------------------------|---------|----------|----------------------------------|
| `productOrderId`                  | string  | N        | 상품 주문 번호 (DB: `market_order_no`) |
| `productOrderStatus`              | string  | N        | 상품 주문 상태                     |
| `placeOrderStatus`                | string  | Y        | 발주 상태                         |
| `placeOrderDate`                  | string  | Y        | 발주 확인일                       |
| `claimStatus`                     | string  | Y        | 클레임 상태                       |
| `claimType`                       | string  | Y        | 클레임 구분                       |
| `productId`                       | string  | N        | 채널 상품 번호                     |
| `originalProductId`               | string  | Y        | 원상품 번호                       |
| `productName`                     | string  | N        | 상품명                           |
| `productOption`                   | string  | Y        | 상품 옵션명                       |
| `sellerProductCode`               | string  | Y        | 판매자 상품 코드 (DB: `productId`) |
| `quantity`                        | number  | N        | 최초 수량                         |
| `initialQuantity`                 | number  | Y        | 최초 수량                         |
| `remainQuantity`                  | number  | Y        | 잔여 수량                         |
| `unitPrice`                       | number  | N        | 상품 가격                         |
| `totalPaymentAmount`              | number  | N        | 최초 결제 금액                     |
| `initialPaymentAmount`            | number  | Y        | 최초 결제 금액                     |
| `remainPaymentAmount`             | number  | Y        | 잔여 결제 금액                     |
| `totalProductAmount`              | number  | Y        | 최초 주문 금액 (할인 전)           |
| `productDiscountAmount`           | number  | Y        | 상품별 할인액                     |
| `optionPrice`                     | number  | Y        | 옵션 금액                         |
| `deliveryFeeAmount`               | number  | Y        | 배송비 합계                       |
| `deliveryDiscountAmount`          | number  | Y        | 배송비 할인액                     |
| `sectionDeliveryFee`              | number  | Y        | 지역별 추가 배송비                 |
| `sellerBurdenDiscountAmount`      | number  | Y        | 판매자 부담 할인액                 |
| `expectedSettlementAmount`        | number  | Y        | 정산 예정 금액                     |
| `paymentCommission`               | number  | Y        | 결제 수수료                       |
| `saleCommission`                  | number  | Y        | (구)판매 수수료                   |
| `channelCommission`               | number  | Y        | 채널 수수료                       |
| `individualCustomUniqueCode`      | string  | Y        | 개인통관고유부호 (해외배송만)       |
| `freeGift`                        | string  | Y        | 사은품                           |
| `mallId`                          | string  | Y        | 가맹점 ID                        |
| `optionCode`                      | string  | Y        | 옵션 코드                         |
| `itemNo`                          | string  | Y        | 아이템 번호 (optionCode와 동일)    |
| `optionManageCode`                | string  | Y        | 옵션 관리 코드                    |
| `sellerCustomCode1`               | string  | Y        | 판매자 내부 코드 1                |
| `sellerCustomCode2`               | string  | Y        | 판매자 내부 코드 2                |
| `inflowPath`                      | string  | Y        | 유입 경로                         |
| `inflowPathAdd`                   | string  | Y        | 유입 경로 추가 정보                |
| `logisticsCompanyId`              | string  | Y        | 물류사 코드                       |
| `logisticsCenterId`               | string  | Y        | 물류센터 코드                     |
| `giftReceivingStatus`             | string  | Y        | 선물 수락 상태                    |
| `claimId`                         | string  | Y        | 클레임 번호                       |
| `decisionDate`                    | string  | Y        | 구매 확정일                       |
| `delayedDispatchReason`           | string  | Y        | 발송 지연 사유 코드                |
| `delayedDispatchDetailedReason`   | string  | Y        | 발송 지연 상세 사유                |
| `deliveryPolicyType`              | string  | Y        | 배송비 정책                       |
| `expectedDeliveryMethod`          | string  | Y        | 배송 방법 코드                     |
| `productClass`                    | string  | Y        | 상품 종류 (일반/추가 상품)          |

#### shippingAddress (배송지 정보)

| 필드                  | 타입   | Nullable | Description              |
|-----------------------|--------|----------|--------------------------|
| `name`                | string | N        | 수령자 이름               |
| `tel1`                | string | N        | 수령자 전화번호            |
| `zipCode`             | string | N        | 우편번호                  |
| `baseAddress`         | string | N        | 기본 주소                  |
| `detailedAddress`     | string | Y        | 상세 주소                  |
| `shippingMemo`        | string | Y        | 배송 메모                  |
| `shippingStartDate`   | string | Y        | 발송 시작일               |
| `shippingDueDate`     | string | Y        | 발송 기한                 |
| `shippingFeeType`     | string | Y        | 배송비 형태 (선불/착불/무료) |

### currentClaim (현재 클레임 정보)

클레임이 없으면 `null`. 클레임 발생 시 `cancel` 또는 `return` 객체 포함.

#### currentClaim.cancel

| 필드                    | 타입   | Description      |
|-------------------------|--------|------------------|
| `claimId`               | string | 클레임 번호       |
| `cancelReason`          | string | 클레임 요청 사유   |
| `cancelDetailedReason`  | string | 취소 상세 사유     |
| `claimRequestDate`      | string | 클레임 요청일      |
| `claimStatus`           | string | 클레임 상태        |
| `cancelApprovalDate`    | string | 취소 승인일        |
| `cancelCompletedDate`   | string | 취소 완료일        |

#### currentClaim.return

| 필드                    | 타입   | Description      |
|-------------------------|--------|------------------|
| `claimId`               | string | 클레임 번호       |
| `returnReason`          | string | 클레임 요청 사유   |
| `returnDetailedReason`  | string | 반품 상세 사유     |
| `claimRequestDate`      | string | 클레임 요청일      |
| `claimStatus`           | string | 클레임 상태        |

### delivery (배송 정보)

배송 전이면 모든 필드가 빈 문자열 또는 `null`.

| 필드                      | 타입    | Nullable | Description              |
|---------------------------|---------|----------|--------------------------|
| `deliveryCompany`         | string  | Y        | 택배사 코드               |
| `deliveryStatus`          | string  | Y        | 배송 상세 상태             |
| `trackingNumber`          | string  | Y        | 송장 번호                 |
| `sendDate`                | string  | Y        | 발송 일시                 |
| `pickupDate`              | string  | Y        | 집화 일시                 |
| `isWrongTrackingNumber`   | boolean | Y        | 오류 송장 여부             |
| `wrongTrackingNumberRegisteredDate` | string | Y | 오류 송장 등록 일시        |
| `wrongTrackingNumberType` | string  | Y        | 오류 사유                 |

#### deliveryStatus 값

| 코드                     | 설명         |
|--------------------------|-------------|
| `COLLECT_REQUEST`        | 수거 요청    |
| `COLLECT_WAIT`           | 수거 대기    |
| `COLLECT_CARGO`          | 집화         |
| `DELIVERING`             | 배송중       |
| `DELIVERY_COMPLETION`    | 배송 완료    |
| `DELIVERY_FAIL`          | 배송 실패    |
| `WRONG_INVOICE`          | 오류 송장    |
| `NOT_TRACKING`           | 배송 추적 없음 |

---

## 4. 페이징 동작

```
┌──────────────────────────────────────────────────────────────┐
│  요청 1: page=1, productOrderStatuses=PAYED, from ~ to       │
│  ├─ 응답: contents=[...], hasNext=true                       │
│  │                                                           │
│  요청 2: page=2                                               │
│  ├─ 응답: contents=[...], hasNext=true                       │
│  │                                                           │
│  요청 3: page=3                                               │
│  └─ 응답: contents=[...], hasNext=false  ← 종료              │
└──────────────────────────────────────────────────────────────┘
```

- `pageSize=300` 고정 (1회당 최대 300건)
- `hasNext`가 `false`일 때까지 `page`를 1씩 증가
- 요청 간 **300ms** sleep (Rate limit 보호)

---

## 5. DB 매핑 (Order / OrderLineItem)

### Order 테이블 매핑

| API 필드                         | DB 컬럼            | 변환                                |
|----------------------------------|--------------------|--------------------------------------|
| `content.order.orderId`          | `market_order_no`  | 그대로 사용                           |
| `content.order.ordererName`      | `orderer_name`     | 그대로 사용                           |
| `content.order.ordererTel`       | `orderer_phone`    | `-` 제거                             |
| `content.order.orderDate`        | `order_date`       | ISO 8601 → LocalDateTime 파싱         |
| `content.order.paymentDate`      | -                  | 별도 저장 안 함                        |
| -                                | `market_type`      | `SMART_STORE` 고정                   |

### OrderLineItem 테이블 매핑

| API 필드                                    | DB 컬럼          | 변환                              |
|---------------------------------------------|------------------|------------------------------------|
| `productOrderId`                            | `market_order_no` | `order.marketOrderNo`와 동일       |
| `content.productOrder.productId`            | `product_id`     | `sellerProductCode` 우선 사용       |
| `content.productOrder.sellerProductCode`    | `product_id`     | `productId` 대체                   |
| `content.productOrder.quantity`             | `quantity`       | 그대로 사용                         |
| `content.productOrder.unitPrice`            | `order_price`    | 그대로 사용                         |
| `content.productOrder.totalPaymentAmount`   | `total_amount`   | 그대로 사용                         |
| `content.productOrder.productOrderStatus` + | `shipping_status`| `SmartStoreStatusMapper`로 변환     |
| `content.productOrder.placeOrderStatus`     |                  |                                    |
| `content.delivery.trackingNumber`           | -                | `ShippingData.trackingNo`          |
| `content.delivery.deliveryCompany`          | -                | `ShippingData.shippingCarrier`     |
| `content.productOrder.individualCustomUniqueCode` | -        | `Order.customsData`                |

### shippingAddress 매핑

| API 필드                        | DB 컬럼 / 객체          |
|---------------------------------|------------------------|
| `shippingAddress.name`          | `recipient_name`       |
| `shippingAddress.tel1`          | `recipient_phone`      |
| `shippingAddress.zipCode`       | `zipcode`              |
| `shippingAddress.baseAddress` + | `address`              |
| `shippingAddress.detailedAddress` |                      |
| `shippingAddress.shippingMemo`  | `message`              |

### 상태 매핑 테이블

| productOrderStatus | placeOrderStatus | → 내부 ShippingStatus |
|--------------------|------------------|-----------------------|
| `PAYED`            | `NOT_YET`        | `NEW`                 |
| `PAYED`            | `OK`             | `PREPARING`           |
| `DELIVERING`       | `OK`             | `SHIPPED`             |
| `DELIVERED`        | `OK`             | `DELIVERED`           |
| `CANCELED`         | any              | `CANCELED`            |
| `RETURNED`         | any              | `RETURNED`            |
| `EXCHANGED`        | any              | `EXCHANGED`           |

---

## 6. 주의사항

### 풀필먼트 구분

`fulfillment` 파라미터로 풀필먼트 설정된 상품과 일반 상품을 구분 조회 가능:
- `null`: 전체 조회
- `false`: 일반 상품만 (현재 시스템에서 사용)
- `true`: 풀필먼트 설정 상품만

### 클레임 주문

클레임이 진행 중인 주문은 `productOrderStatus`가 `CANCELED`/`RETURNED`/`EXCHANGED`로 변경되며, `currentClaim` 객체에 상세 정보 포함.

### rate limit

스마트스토어 API는 초당 10건, 일당 15,000건 요청 제한. 페이징 조회 시 300ms sleep 권장.

### 상품 식별

`productId`(채널 상품 번호)와 `sellerProductCode`(판매자 상품 코드)가 혼재됨. 내부 시스템에서는 `sellerProductCode`를 `product_id`로 사용하는 것이 일관적.

---

## 7. 소스 코드 참조

| 파일 위치                            | 설명                                    |
|-------------------------------------|-----------------------------------------|
| `SmartStoreOrderApiClient.java`     | `fetchOrders()` - 2단계 조회 구현         |
| `SmartStoreOrderAdapter.java`       | `fetchOrders()` - 1일 단위 청크 + 파싱    |
| `SmartStoreStatusMapper.java`       | 스마트스토어 상태 → 내부 상태 매핑          |
