# 송장업로드 처리 (Upload Waybill)

> 상품준비중(PREPARING) 상태의 주문에 대해서만 송장업로드가 가능합니다.

## End Point

```
POST /v2/providers/openapi/apis/api/v4/vendors/{vendorId}/orders/invoices
```

## Request

### Path Parameter

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| vendorId | String | O | 판매자 ID (예: A00012345) |

### Body Parameter

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| vendorId | String | O | 판매자 ID |
| orderSheetInvoiceApplyDtos | Array | O | 송장 등록 대상 목록 |

#### orderSheetInvoiceApplyDtos 항목

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| shipmentBoxId | Number | O | 묶음배송번호 |
| orderId | Number | O | 주문번호 |
| vendorItemId | Number | O | 옵션 ID |
| deliveryCompanyCode | String | O | 택배사 코드 |
| invoiceNumber | String | O | 송장번호 (분리배송 시 선택, 없으면 "") |
| splitShipping | Boolean | O | 분리배송 여부 |
| preSplitShipped | Boolean | O | 분리배송중인지 여부 |
| estimatedShippingDate | String | O | 출고예정일 (YYYY-MM-DD, 분리배송 시 선택) |

### Request Example

```json
{
  "vendorId": "A00034612",
  "orderSheetInvoiceApplyDtos": [
    {
      "shipmentBoxId": 123456789012345678,
      "orderId": 4000019469460,
      "vendorItemId": 3823839899,
      "deliveryCompanyCode": "KDEXP",
      "invoiceNumber": "20180731040123",
      "splitShipping": false,
      "preSplitShipped": false,
      "estimatedShippingDate": ""
    }
  ]
}
```

## Response

| 필드 | 타입 | 설명 |
|------|------|------|
| code | Number | 서버 응답 코드 |
| message | String | 서버 응답 메세지 |
| data | Object | 응답 데이터 |

### data.responseList 항목

| 필드 | 타입 | 설명 |
|------|------|------|
| shipmentBoxId | Number | 묶음배송번호 |
| succeed | Boolean | 성공여부 |
| resultCode | String | 결과코드 |
| resultMessage | String | 결과메세지 |
| retryRequired | Boolean | retry 가능 여부 |

### resultCode 매핑

| resultCode | retry | 설명 |
|------------|-------|------|
| OK | false | 성공 |
| NOT_FOUND_SHIPMENT_BOX | false | 존재하지 않는 송장번호 |
| INVALID_STATUS | false | 배송진행상태가 유효하지 않음 |
| PERMISSION_DENIED | true | 권한 없음 |
| DUPLICATE_INVOICE_NUMBER | true | 이미 저장된 송장번호 |
| INVALID_INVOICE_NUMBER | true | 송장번호가 유효하지 않음 |
| ORDER_DELIVERY_CANCELED | false | 취소된 주문건 |
| ORDER_DELIVERY_PARTIAL_STOP_REQUESTED | true | 출고중지 요청건 |
| ORDER_DELIVERY_CANCELED_HOLDING_FOR_CANCEL | true | 취소대기상태 주문건 |
| UNDEFINED_ERROR_OCCUR | true | 알수없는 오류 |

### Response Example

```json
{
  "code": "200",
  "message": "OK",
  "data": {
    "responseCode": 0,
    "responseMessage": "SUCCESS",
    "responseList": [
      {
        "shipmentBoxId": 123456789012345678,
        "succeed": true,
        "resultCode": "OK",
        "retryRequired": false,
        "resultMessage": null
      }
    ]
  }
}
```

## 참고

- 택배사 코드: https://developers.coupangcorp.com/hc/ko/articles/360034156033
- 6개월 이내 중복 송장번호 입력 시 송장중복에러 발생 가능
- 분리배송 예시: https://developers.coupangcorp.com/hc/ko/articles/360023108613
