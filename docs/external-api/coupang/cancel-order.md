# 주문 상품 취소 처리 (Cancel Order Processing)

> 결제완료 또는 상품준비중 상태의 상품을 취소하기 위한 API입니다.
> - 결제완료 상태: 즉시 취소
> - 상품준비중 상태: 출고중지

## End Point

```
POST /v2/providers/openapi/apis/api/v5/vendors/{vendorId}/orders/{orderId}/cancel
```

> ⚠️ v5 API (송장업로드는 v4)

## Request

### Path Parameter

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| vendorId | String | O | 판매자 ID (예: A00012345) |
| orderId | Number | O | 주문 번호 (예: 2000006593044) |

### Body Parameter

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| orderId | Number | O | 주문번호 (path variable와 동일) |
| vendorItemIds | Array | O | 취소할 상품 옵션 ID 배열 |
| receiptCounts | Array | O | 취소할 상품 개수 배열 (항상 0보다 큼) |
| bigCancelCode | String | O | 취소 사유 대분류 코드 (`CANERR`) |
| middleCancelCode | String | O | 취소 사유 중분류 코드 |
| vendorId | String | O | 업체 ID (path variable와 동일) |
| userId | String | O | 업체의 쿠팡 Wing 로그인 ID |

### middleCancelCode 값

| 코드 | 설명 |
|------|------|
| CCTTER | 재고 연동 오류 |
| CCPNER | 제휴사이트 오류 (주소 문제) |
| CCPRER | 가격등재오류 |

> 상품준비중 상태 취소 시 중분류는 고정("배송불만", "품절"), 상세 사유는 "파트너 API 강제 취소"

### Request Example

```json
{
  "orderId": 2000006593044,
  "vendorItemIds": [3145181064, 3145181065, 3145181067],
  "receiptCounts": [1, 2, 1],
  "bigCancelCode": "CANERR",
  "middleCancelCode": "CCTTER",
  "userId": "wing_login_id_123",
  "vendorId": "A00123456"
}
```

## Response

| 필드 | 타입 | 설명 |
|------|------|------|
| code | String | 응답 코드 (`200`: 성공, `400`: 실패) |
| message | String | 서버 응답 메시지 |
| data | Object | 응답 데이터 |

### data 항목

| 필드 | 타입 | 설명 |
|------|------|------|
| receiptMap | Object | 취소 접수 정보 (receiptId 기반) |
| orderId | Number | 주문번호 |
| failedVendorItemIds | Array | 취소 실패한 vendorItemId 목록 |

### receiptMap 항목

| 필드 | 타입 | 설명 |
|------|------|------|
| receiptId | Number | 접수 ID |
| receiptType | String | `CANCEL`: 즉시취소, `STOP_SHIPMENT`: 출고중지완료 |
| vendorItemIds | Array | 해당 접수로 취소한 상품 번호 목록 |
| totalCount | Number | 취소한 총 상품 개수 |

### Response Example 1 - 전체 취소 성공

```json
{
  "code": "200",
  "message": "[요청번호] 43b97579-bcb5-4260-84cb-9a4a9063db71\r\n",
  "data": {
    "receiptMap": {
      "181627233": {
        "receiptId": 181627233,
        "receiptType": "CANCEL",
        "vendorItemIds": [70071284034],
        "totalCount": 1
      }
    },
    "orderId": 23000059824637,
    "failedVendorItemIds": []
  }
}
```

### Response Example 2 - 부분 취소 (일부 실패)

```json
{
  "code": "200",
  "message": "[요청번호] cdea5b4b-...\r\n[3145181064]<= 취소 가능한 개수보다 요청한 개수가 더 많습니다.",
  "data": {
    "receiptMap": {
      "44698107": {
        "receiptId": 44698107,
        "receiptType": "STOP_SHIPMENT",
        "vendorItemIds": [3145181065, 3145181067],
        "totalCount": 3
      }
    },
    "orderId": 2000006593044,
    "failedVendorItemIds": [3145181064]
  }
}
```

### Response Example 3 - 전체 실패

```json
{
  "code": "400",
  "message": "[요청번호] 5d803ea1-...\r\n[3145181067, 3145181065, 3145181064]<= 취소 가능한 개수보다 요청한 개수가 더 많습니다",
  "data": {
    "receiptMap": {},
    "orderId": 23000059824637,
    "failedVendorItemIds": [3145181067, 3145181065, 3145181064]
  }
}
```

## 주의사항

- 하나의 주문번호 안에 서로 다른 shipmentBoxId가 존재하는 경우 shipmentBoxId별로 각각 취소 요청 필요
- 판매자 점수(주문이행) 하락 → 재고/가격 관리로 사용 지양
- vendorItemIds와 receiptCounts는 개수가 일치해야 함

## Error Spec

| HTTP 상태 코드 | 에러 메시지 | 해결 방법 |
|---------------|------------|-----------|
| 400 | 주문 ID를 입력해 주세요. | orderId 확인 |
| 400 | 취소할 벤더아이템 아이디 목록을 입력해주세요. | vendorItemIds 확인 |
| 400 | 취소할 아이템 개수 목록을 입력해주세요. | receiptCounts 확인 |
| 400 | 요청한 상품 개수와 취소 개수를 확인해주세요. | vendorItemIds와 receiptCounts 개수 일치 확인 |
| 400 | 취소사유 대분류 코드를 입력해주세요. | bigCancelCode를 "CANERR"로 입력 |
| 400 | 취소사유 중분류 코드를 입력해주세요. | middleCancelCode 확인 |
| 400 | 업체 ID를 입력해주세요. | vendorId 확인 |
| 400 | 업체 ID에 맞는 올바른 유저 ID를 입력해주세요. | userId 확인 |
| 400 | 주문 정보가 없습니다. | 주문번호/상품번호 상태 확인 |
| 400 | 취소 가능한 개수보다 요청한 개수가 더 많습니다. | 취소가능개수 확인 |
| 400 | 해당 벤더아이템이 결제완료/상품준비중 상태가 아닙니다. | 주문상태 확인 |
| 400 | 요청한 업체의 상품이 아닙니다. | vendorItem 소유 확인 |

## 참고

- API Name: `CANCEL_ORDER_PROCESSING`
- API 문서: https://developers.coupangcorp.com/hc/ko/articles/360033843154
