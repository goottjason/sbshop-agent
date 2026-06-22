# 송장업데이트 처리 (Update Waybill)

> 잘못 등록한 운송장 내용을 변경합니다.
> 배송상태가 배송지시(DEPARTURE), 배송중(DELIVERING), 배송완료(FINAL_DELIVERY), 업체직송(NONE_TRACKING) 상태일 때만 가능합니다.

## End Point

```
POST /v2/providers/openapi/apis/api/v4/vendors/{vendorId}/orders/updateInvoices
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
| orderSheetInvoiceApplyDtos | Array | O | 송장 변경 대상 목록 |

#### orderSheetInvoiceApplyDtos 항목

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| shipmentBoxId | Number | O | 묶음배송번호 |
| orderId | Number | O | 주문번호 |
| vendorItemId | Number | O | 옵션 ID |
| deliveryCompanyCode | String | O | 변경할 택배사 코드 |
| invoiceNumber | String | O | 변경할 송장번호 |
| splitShipping | Boolean | O | 분리배송 여부 |
| preSplitShipped | Boolean | O | 분리배송중인지 여부 |
| estimatedShippingDate | String | O | 출고예정일 (YYYY-MM-DD, 분리배송 시 선택) |

### Request Example

```json
{
  "vendorId": "A00012345",
  "orderSheetInvoiceApplyDtos": [
    {
      "shipmentBoxId": 123456789012345678,
      "orderId": 2000019631453,
      "vendorItemId": 3819657333,
      "deliveryCompanyCode": "KDEXP",
      "invoiceNumber": "201808231414",
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

동일 (송장업로드와 동일한 resultCode 사용)

### Response Example

```json
{
  "code": 200,
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
- 배송상태 변경 히스토리 조회: https://developers.coupangcorp.com/hc/ko/articles/360033792934
