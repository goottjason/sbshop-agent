# 주문 취소 처리 (Cancel Orders)

> 결제완료 또는 상품준비중 상태의 주문에 대해 취소 요청을 보냅니다.
> - 결제완료 상태: 즉시 취소
> - 상품준비중 상태: 출고중지 후 취소

## End Point

```
POST https://api.commerce.naver.com/external/v1/pay-order/seller/product-orders/cancel
```

## Request

### Header

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| Authorization | String | O | Bearer {accessToken} |
| Content-Type | String | O | application/json; charset=UTF-8 |

### Body Parameter

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| productOrderIds | Array | O | 취소할 주문번호 목록 |

### Request Example

```json
{
  "productOrderIds": [
    "2024010112345678",
    "2024010187654321"
  ]
}
```

## Response

### Response Example - 전체 성공

```json
{
  "code": 0,
  "message": "success",
  "detail": [
    {
      "productOrderId": "2024010112345678",
      "cancel": true
    },
    {
      "productOrderId": "2024010187654321",
      "cancel": true
    }
  ]
}
```

### Response Example - 부분 실패

```json
{
  "code": 0,
  "message": "success",
  "detail": [
    {
      "productOrderId": "2024010112345678",
      "cancel": true
    },
    {
      "productOrderId": "2024010187654321",
      "cancel": false
    }
  ]
}
```

## 응답 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| code | Number | 응답 코드 (0: 성공) |
| message | String | 응답 메시지 |
| detail | Array | 개별 주문 처리 결과 |
| detail[].productOrderId | String | 주문번호 |
| detail[].cancel | Boolean | 취소 성공 여부 |

## 주의사항

- `cancel: false`인 경우 주문상태가 결제완료/상품준비중이 아님 (이미 취소됨, 배송완료됨 등)
- 하나의 요청에 여러 건을 배치 처리할 수 있으나, 개별 실패가 가능하므로 response의 `cancel` 필드를 반드시 확인
- 토큰 갱신 시 OAuth2 엑세스 토큰 재발급 필요

## API 상태 변경 흐름

```
결제완료 → (취소) → 취소완료
상품준비중 → (취소) → 출고중지 → 취소완료
```

## 참고

- API Name: `PAY_ORDER_SELLER_PRODUCT_ORDERS_CANCEL`
- 스마트스토어 파트너 개발자 문서: https://developers.naver.com/docs/sales/api/
