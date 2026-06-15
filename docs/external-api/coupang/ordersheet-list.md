# 쿠팡 발주서 목록 조회 API

> **Endpoint**: `GET /v2/providers/openapi/apis/api/v4/vendors/{vendorId}/ordersheets`
> **Base URL**: `https://api-gateway.coupang.com`
> **Purpose**: 특정 기간 내 접수된 발주서 목록을 상태별로 페이징 조회

---

## 1. Request

### URL Path

```
/v2/providers/openapi/apis/api/v4/vendors/{vendorId}/ordersheets
```

| Parameter  | Type   | Description                          |
|------------|--------|--------------------------------------|
| `vendorId` | string | 쿠팡 판매자 ID (DB: `sb_market_credential.client_id`) |

### Query Parameters

| Parameter       | Required | Default | Description                                                                 |
|-----------------|----------|---------|-----------------------------------------------------------------------------|
| `createdAtFrom` | Y        | -       | 조회 시작일 (`yyyy-MM-dd`, e.g. `2026-05-17`)                              |
| `createdAtTo`   | Y        | -       | 조회 종료일 (`yyyy-MM-dd`, e.g. `2026-06-16`)                              |
| `searchType`    | Y        | -       | 고정값 `timeframe`                                                          |
| `maxPerPage`    | N        | `50`    | 페이지당 결과 수 (현재 코드에서 고정 `50` 사용)                             |
| `status`        | Y        | -       | 주문 상태 필터 (아래 Status 목록 참고)                                      |
| `nextToken`     | N        | -       | 다음 페이지 토큰 (2페이지 이상 요청 시 응답에서 반환된 값 사용)             |

### Status 필터 값

| Status Value     | 내부 매핑       | 설명               |
|------------------|----------------|--------------------|
| `ACCEPT`         | `NEW`          | 주문 접수          |
| `INSTRUCT`       | `PREPARING`    | 배송 준비 중       |
| `DEPARTURE`      | `SHIPPED`      | 출발               |
| `DELIVERING`     | `SHIPPED`      | 배송 중            |
| `FINAL_DELIVERY` | `DELIVERED`    | 배송 완료          |
| `NONE_TRACKING`  | -              | 운송장 미등록       |

> **주의**: 코드에서는 `PAYMENT_WAITING`, `DEPOSIT_WAITING` 상태가 응답에 포함될 경우 별도로 스킵 처리함.

### Headers

| Header            | Value                                              | Description         |
|-------------------|----------------------------------------------------|---------------------|
| `Authorization`   | `CEA algorithm=HmacSHA256, access-key=..., ...`    | HMAC 서명 인증      |
| `X-Requested-By`  | `{vendorId}`                                       | 판매자 ID           |
| `Accept`          | `application/json`                                 | 응답 형식           |

#### Authorization 서명 생성

```
CEA algorithm=HmacSHA256, access-key={accessKey}, signed-date={datetime}, signature={signature}
```

- `datetime`: UTC 기준 `yyMMdd'T'HHmmss'Z'` 포맷
- `signature`: HMAC-SHA256(`{datetime}GET{path}?{queryString}`, `secretKey`) → hex 인코딩

```
서명 메시지 = { utcDatetime } + "GET" + { path } + "?" + { queryString }
```

---

## 2. Response

### Top Level

```json
{
  "code": "SUCCESS",
  "message": "...",
  "data": [ ... ],
  "nextToken": "abc123..."
}
```

| Field       | Type     | Description                              |
|-------------|----------|------------------------------------------|
| `code`      | string   | `SUCCESS` 또는 `200` 이면 성공           |
| `message`   | string   | 에러 메시지 (성공 시 빈 문자열)          |
| `data`      | array    | 발주서 목록 (배열)                       |
| `nextToken` | string   | 다음 페이지 토큰 (없으면 빈 문자열)     |

> **페이징 방식**: `nextToken` 기반. `nextToken`이 비어있으면 마지막 페이지.

### data[] (발주서 항목)

각 발주서 항목(shipment box 단위)의 구조:

```json
{
  "orderId": "20260615-ABCDEF-123456",
  "shipmentBoxId": "789012345",
  "status": "ACCEPT",
  "orderedAt": "2026-06-15T10:30:00",
  "invoiceNumber": null,
  "deliveryCompanyName": null,
  "parcelPrintMessage": "",
  "receiver": {
    "name": "홍길동",
    "safeNumber": "0101234****",
    "postCode": "06236",
    "addr1": "서울특별시 강남구 테헤란로 152",
    "addr2": "강남파이낸스센터 5층"
  },
  "orderer": {
    "name": "홍길동"
  },
  "overseaShippingInfoDto": null,
  "orderItems": [
    {
      "externalVendorSkuCode": "SB-001",
      "sellerProductId": "12345678",
      "vendorItemId": "987654321",
      "vendorItemName": "상품명 (옵션명)",
      "sellerProductName": "판매자 상품명",
      "shippingCount": 1,
      "orderPrice": "15000"
    }
  ]
}
```

### 필드 상세

| 필드                       | 타입    | Nullable | Description                                      |
|---------------------------|---------|----------|--------------------------------------------------|
| `orderId`                 | string  | N        | 주문 번호 (시장 주문번호)                        |
| `shipmentBoxId`           | string  | Y        | 배송 박스 ID (발주확인 시 사용)                  |
| `status`                  | string  | N        | 주문 상태                                        |
| `orderedAt`               | string  | N        | 주문일시 (`yyyy-MM-dd'T'HH:mm:ss`)              |
| `invoiceNumber`           | string  | Y        | 운송장 번호 (배송 전이면 null)                   |
| `deliveryCompanyName`     | string  | Y        | 택배사명 (배송 전이면 null)                      |
| `parcelPrintMessage`      | string  | Y        | 택배 인쇄 메시지 (배송 메모)                     |
| `receiver.name`           | string  | N        | 수령자 이름                                      |
| `receiver.safeNumber`     | string  | N        | 수령자 전화번호 (마스킹 처리)                    |
| `receiver.postCode`       | string  | N        | 우편번호                                         |
| `receiver.addr1`          | string  | N        | 주소第一行                                      |
| `receiver.addr2`          | string  | N        | 주소第二行 (상세주소)                            |
| `orderer.name`            | string  | Y        | 주문자 이름                                      |
| `overseaShippingInfoDto`  | object  | Y        | 해외배송 정보 (국내 배송이면 null)               |
| `overseaShippingInfoDto.ordererPhoneNumber` | string | Y | 주문자 전화번호                           |
| `overseaShippingInfoDto.ordererName`        | string | Y | 주문자 이름                              |
| `overseaShippingInfoDto.personalCustomsClearanceCode` | string | Y | 개인통관고유부호                  |

### orderItems[] (주문 상품)

| 필드                    | 타입    | Nullable | Description                                      |
|------------------------|---------|----------|--------------------------------------------------|
| `externalVendorSkuCode`| string  | Y        | 판매자 상품 코드 ⚠️ 부정확할 수 있음 (아래 주의사항 참고) |
| `sellerProductId`      | string  | Y        | 쿠팡 상품 ID                                     |
| `vendorItemId`         | string  | Y        | 쿠팡 상품 옵션 ID                                |
| `vendorItemName`       | string  | N        | 상품명 (옵션 포함)                               |
| `sellerProductName`    | string  | Y        | 판매자 상품명                                    |
| `shippingCount`        | number  | N        | 배송 수량                                        |
| `orderPrice`           | string  | N        | 주문 단가 (문자열, e.g. `"15000"`)               |

---

## 3. 페이징 동작

```
┌─────────────────────────────────────────────────────────┐
│  요청 1: status=ACCEPT, createdAtFrom ~ createdAtTo     │
│  ├─ 응답: data=[...], nextToken="abc123"               │
│  │                                                      │
│  요청 2: + nextToken="abc123"                           │
│  ├─ 응답: data=[...], nextToken="def456"               │
│  │                                                      │
│  요청 3: + nextToken="def456"                           │
│  └─ 응답: data=[...], nextToken=""  ← 종료             │
└─────────────────────────────────────────────────────────┘
```

- `maxPerPage=50` 고정 (1회당 최대 50건)
- `nextToken`이 빈 문자열이 될 때까지 반복
- 요청 간 **300ms** sleep (Rate limit 보호)

---

## 4. 실제 사용 방식 (Adapter 레이어)

코드에서는 **6개 상태를 순회**하며 각각 페이징 조회:

```
for each status in [ACCEPT, INSTRUCT, DEPARTURE, DELIVERING, FINAL_DELIVERY, NONE_TRACKING]:
    1. 해당 status로 전체 페이징 조회
    2. 각 order를 MarketOrderDto로 파싱
    3. PAYMENT_WAITING / DEPOSIT_WAITING 스킵
    4. 상태 간 1000ms sleep
```

- **조회 범위**: 최근 **30일** (fromDate = today - 30, toDate = today)
- **총 요청 수**: status(6) × 페이지 수 = 최소 6회, 많으면 수십회

---

## 5. 참고: `8100198119354` 주문 예시 (conceptual)

```
GET /v2/providers/openapi/apis/api/v4/vendors/{vendorId}/ordersheets
    ?createdAtFrom=2026-05-17
    &createdAtTo=2026-06-16
    &maxPerPage=50
    &searchType=timeframe
    &status=ACCEPT

Authorization: CEA algorithm=HmacSHA256, access-key=xxxxx, signed-date=260616T120000Z, signature=yyyyy
X-Requested-By: {vendorId}
```

응답에서 `orderId` 또는 `orderItems[].vendorItemId` 등으로 `8100198119354` 식별 가능.

---

## 6. 주의사항

### externalVendorSkuCode 부정확성

쿠팡 주문 API의 `orderItems[].externalVendorSkuCode`는 **부정확한 값을 반환**할 수 있음 (e.g. `P0000NPQ000A`).

현재 코드에서는 이를 보완하기 위해:
1. `externalVendorSkuCode` 사용 시도
2. 실패 시 `sellerProductId`로 상품상세조회 API (`/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/{sellerProductId}`) 호출
3. 상품상세의 `items[].vendorItemId` 매칭으로 올바른 `externalVendorSku` 확보
4. 그래도 없으면 `sellerProductId` 자체를 사용 (최후의 수단)

### dump_coupang.js 서명 버그

JS 덤프 스크립트의 HMAC 서명에서 **쿼리 스트링이 포함되지 않는** 버그 존재.

- Java (정확): `datetime + "GET" + path + "?" + query`
- JS (버그): `datetime + "GET" + path` (쿼리 스트링 누락)

---

## 7. 소스 코드 참조

| 파일 위치 | 라인 | 설명 |
|-----------|------|------|
| `CoupangOrderApiClient.java` | 29-88 | GET ordersheet 구현 (HMAC 포함) |
| `CoupangOrderAdapter.java` | 47-98 | 상태 순회 + 페이징 호출 로직 |
| `CoupangOrderAdapter.java` | 164-286 | 응답 파싱 → MarketOrderDto 변환 |
| `CoupangStatusMapper.java` | 30-44 | 쿠팡 상태 → 내부 상태 매핑 |
