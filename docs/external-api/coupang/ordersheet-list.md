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
  "code": 200,
  "message": "OK",
  "data": [ ... ],
  "nextToken": ""
}
```

| Field       | Type     | Description                              |
|-------------|----------|------------------------------------------|
| `code`      | number/string | `200` 또는 `"SUCCESS"` 이면 성공           |
| `message`   | string   | 에러 메시지 (성공 시 `"OK"`)          |
| `data`      | array    | 발주서 목록 (배열)                       |
| `nextToken` | string   | 다음 페이지 토큰 (없으면 빈 문자열)     |

> **페이징 방식**: `nextToken` 기반. `nextToken`이 비어있으면 마지막 페이지.

### data[] (발주서 항목)

각 발주서 항목(shipment box 단위)의 구조. 아래는 `8100198119354` 주문의 실제 Response:

```json
{
  "shipmentBoxId": 698535699660808,
  "orderId": "8100198119354",
  "orderedAt": "2026-06-15T02:16:52",
  "orderer": {
    "name": "이정수",
    "email": "",
    "safeNumber": "0502-4272-3093",
    "ordererNumber": null
  },
  "paidAt": "2026-06-15T02:16:54",
  "status": "INSTRUCT",
  "shippingPrice": 0,
  "remotePrice": 0,
  "remoteArea": false,
  "parcelPrintMessage": "문 앞",
  "splitShipping": false,
  "ableSplitShipping": false,
  "receiver": {
    "name": "이정수",
    "safeNumber": "0502-4272-3093",
    "receiverNumber": null,
    "addr1": "충청북도 청주시 흥덕구 봉명동 1120",
    "addr2": "301호",
    "postCode": "28563"
  },
  "orderItems": [
    {
      "vendorItemPackageId": 0,
      "vendorItemPackageName": "California Gold Nutrition Omega 800 캘리포니아 골드 CGN 오메가800 30소프트젤 2팩",
      "productId": 6447227891,
      "vendorItemId": 87712424088,
      "vendorItemName": "California Gold Nutrition Omega 800 캘리포니아 골드 CGN 오메가800 30소프트젤 2팩, 30정, 2개",
      "shippingCount": 1,
      "salesPrice": 41200,
      "orderPrice": 41200,
      "discountPrice": 0,
      "instantCouponDiscount": 0,
      "downloadableCouponDiscount": 0,
      "coupangDiscount": 0,
      "externalVendorSkuCode": "P000BFLG000A",
      "etcInfoHeader": null,
      "etcInfoValue": null,
      "etcInfoValues": null,
      "sellerProductId": 14491299726,
      "sellerProductName": "(2개) 캘리포니아 골드 뉴트리션 오메가 800 30캡슐",
      "sellerProductItemName": "단일상품",
      "firstSellerProductItemName": "(2개) 캘리포니아 골드 뉴트리션 오메가 800 30캡슐",
      "cancelCount": 0,
      "holdCountForCancel": 0,
      "estimatedShippingDate": "2026-06-22",
      "plannedShippingDate": "",
      "invoiceNumberUploadDate": "",
      "extraProperties": {},
      "pricingBadge": false,
      "usedProduct": false,
      "confirmDate": null,
      "deliveryChargeTypeName": "무료",
      "upBundleVendorItemId": null,
      "upBundleVendorItemName": null,
      "upBundleSize": null,
      "canceled": false,
      "upBundleItem": false
    }
  ],
  "overseaShippingInfoDto": {
    "personalCustomsClearanceCode": "P210011766727",
    "ordererSsn": "",
    "ordererPhoneNumber": "01067227207"
  },
  "deliveryCompanyName": "",
  "invoiceNumber": "",
  "inTrasitDateTime": "",
  "deliveredDate": "",
  "refer": "아이폰앱",
  "shipmentType": "THIRD_PARTY"
}
```

### 필드 상세

#### 최상위 필드

| 필드 | 타입 | Nullable | Description |
|------|------|----------|-------------|
| `orderId` | string | N | 주문 번호 (시장 주문번호). e.g. `"8100198119354"` |
| `shipmentBoxId` | number | Y | 배송 박스 ID (발주확인 시 사용) |
| `status` | string | N | 주문 상태 (`ACCEPT`, `INSTRUCT`, `DEPARTURE`, `DELIVERING`, `FINAL_DELIVERY`, `NONE_TRACKING`) |
| `orderedAt` | string | N | 주문일시 (`yyyy-MM-dd'T'HH:mm:ss`) |
| `paidAt` | string | N | 결제일시 (`yyyy-MM-dd'T'HH:mm:ss`) |
| `parcelPrintMessage` | string | Y | 택배 인쇄 메시지 (배송 메모). e.g. `"문 앞"` |
| `invoiceNumber` | string | Y | 운송장 번호 (배송 전이면 빈 문자열) |
| `deliveryCompanyName` | string | Y | 택배사명 (배송 전이면 빈 문자열) |
| `inTrasitDateTime` | string | Y | 출고일시 |
| `deliveredDate` | string | Y | 배송완료일시 |
| `shippingPrice` | number | N | 배송비 |
| `remotePrice` | number | N | 도서산간 추가 배송비 |
| `remoteArea` | boolean | N | 도서산간 지역 여부 |
| `splitShipping` | boolean | N | 분할배송 가능 여부 |
| `ableSplitShipping` | boolean | N | 분할배송 설정 가능 여부 |
| `refer` | string | Y | 주문 경로 (e.g. `"아이폰앱"`) |
| `shipmentType` | string | Y | 배송 유형 (`"THIRD_PARTY"`: 판매자 직접 배송, `"ROCKET"`: 로켓배송) |

#### receiver (수취인 정보)

| 필드 | 타입 | Nullable | Description |
|------|------|----------|-------------|
| `name` | string | N | 수령자 이름 |
| `safeNumber` | string | N | 수령자 전화번호 (마스킹 처리, e.g. `"0502-4272-3093"`) |
| `receiverNumber` | string | Y | 수령자 실제 전화번호 (nullable, 보통 마스킹 처리됨) |
| `postCode` | string | N | 우편번호 |
| `addr1` | string | N | 주소 기본 |
| `addr2` | string | N | 주소 상세 |

#### orderer (주문자 정보)

| 필드 | 타입 | Nullable | Description |
|------|------|----------|-------------|
| `name` | string | N | 주문자 이름 |
| `safeNumber` | string | Y | 주문자 전화번호 (마스킹 처리) |
| `email` | string | Y | 주문자 이메일 |
| `ordererNumber` | string | Y | 주문자 실제 전화번호 (nullable) |

#### overseaShippingInfoDto (추가/해외배송 정보)

| 필드 | 타입 | Nullable | Description |
|------|------|----------|-------------|
| `personalCustomsClearanceCode` | string | Y | 개인통관고유부호 (e.g. `"P210011766727"`) |
| `ordererPhoneNumber` | string | Y | 주문자 실제 전화번호 (마스킹 없음) |
| `ordererSsn` | string | Y | 주문자 주민등록번호 (보통 빈 문자열) |

> ⚠️ **해외배송 상품만 `overseaShippingInfoDto`가 존재**함. 국내배송 상품은 `null`.

### orderItems[] (주문 상품)

| 필드 | 타입 | Nullable | Description |
|------|------|----------|-------------|
| `externalVendorSkuCode` | string | Y | 판매자 상품 코드 ⚠️ 부정확할 수 있음 (아래 주의사항 참고) |
| `sellerProductId` | number | Y | 쿠팡 상품 ID |
| `sellerProductName` | string | Y | 판매자 상품명 |
| `productId` | number | Y | 쿠팡 상품 ID (sellerProductId와 유사) |
| `vendorItemId` | number | Y | 쿠팡 상품 옵션 ID |
| `vendorItemName` | string | N | 상품명 (옵션 포함) |
| `vendorItemPackageId` | number | Y | 묶음 상품 ID (단일이면 0) |
| `vendorItemPackageName` | string | Y | 묶음 상품명 |
| `shippingCount` | number | N | 배송 수량 |
| `orderPrice` | number | N | 주문 단가 |
| `salesPrice` | number | N | 판매 단가 |
| `discountPrice` | number | N | 할인 금액 |
| `instantCouponDiscount` | number | N | 즉시할인쿠폰 할인액 |
| `downloadableCouponDiscount` | number | N | 다운로드쿠폰 할인액 |
| `coupangDiscount` | number | N | 쿠팡 할인액 |
| `estimatedShippingDate` | string | Y | 예상 출고일 (`yyyy-MM-dd`) |
| `deliveryChargeTypeName` | string | Y | 배송비 유형 (e.g. `"무료"`) |
| `cancelCount` | number | N | 취소 수량 |
| `canceled` | boolean | N | 취소 여부 |
| `confirmDate` | string | Y | 구매확정일시 |
| `usedProduct` | boolean | N | 중고상품 여부 |

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

## 5. 실제 조회 결과: `8100198119354` 주문

최근 30일간 `INSTRUCT`(배송준비중) 상태에서 조회된 실제 주문:

```
GET /v2/providers/openapi/apis/api/v4/vendors/A00213055/ordersheets
    ?createdAtFrom=2026-05-17
    &createdAtTo=2026-06-16
    &maxPerPage=50
    &searchType=timeframe
    &status=INSTRUCT

Authorization: CEA algorithm=HmacSHA256, access-key=97211801-..., signed-date=260616T001051Z, signature=...
X-Requested-By: A00213055
```

**응답 요약:**

| 필드 | 값 |
|------|-----|
| `orderId` | `8100198119354` |
| `status` | `INSTRUCT` (배송준비중) |
| `orderedAt` | `2026-06-15T02:16:52` |
| 상품 | California Gold Nutrition Omega 800 (2팩) |
| 수취인 | 이정수 / 0502-4272-3093 / 충북 청주시 흥덕구 |
| 우편번호 | `28563` |
| 배송메모 | `문 앞` |
| 통관번호 | `P210011766727` (해외배송) |
| orderPrice | 41,200원 |
| `externalVendorSkuCode` | `P000BFLG000A` |

> **특이사항**: 이 주문은 `overseaShippingInfoDto`가 존재하는 **해외배송 상품**으로,
> `personalCustomsClearanceCode`(통관번호)와 `ordererPhoneNumber`(실제 전화번호)가 포함되어 있음.
> 국내배송 상품의 경우 `overseaShippingInfoDto`는 `null`.

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

JS 덤프 스크립트의 HMAC 서명 방식이 **Java 구현과 다름**:

- **Java (정확)**: `datetime + "GET" + path + query`
  - `path`는 `?` 제외한 경로, `query`는 `?` 없는 쿼리스트링
  - e.g. `260616T001051ZGET/v2/providers/.../ordersheetscreatedAtFrom=...&status=INSTRUCT`
  - `?`가 **없음** (path와 query 사이에 `?` 미포함)
- **JS (오류)**: `datetime + "GET" + path` (path에 `?` 포함된 전체 경로)
  - e.g. `20260616T001200ZGET/v2/providers/.../ordersheets?createdAtFrom=...&status=INSTRUCT`
  - Coupang API는 이 형식을 **거부**함 (`HMAC format is invalid`)

> 또 다른 차이: Java는 2자리 연도(`yy`), JS는 4자리 연도(`yyyy`) 사용.
> → 반드시 **Java 형식**(`yyMMdd'T'HHmmss'Z'`, `path`와 `query` 사이 `?` 없음)을 따라야 함.

### HMAC 서명 시 datetime 포맷 주의

Java `DateTimeFormatter` 패턴 `"yyMMdd'T'HHmmss'Z'"`:

| 의도 | 실제 출력 |
|------|-----------|
| `'T'` (literal T) | `T` |
| `'Z'` (literal Z) | `Z` |
| 결과 | `260616T001051Z` |

> ⚠️ **bash `date` 명령어 사용 시**: `date -u +"%y%m%dT%H%M%SZ"` — 작은따옴표를 **포함하지 마라**.
> 잘못된 예: `date -u +"%y%m%d'T'%H%M%S'Z'"` → `260616'T'001051'Z'` (틀림) ❌
> 올바른 예: `date -u +"%y%m%dT%H%M%SZ"` → `260616T001051Z` (정확) ✅

---

## 7. 소스 코드 참조

| 파일 위치 | 라인 | 설명 |
|-----------|------|------|
| `CoupangOrderApiClient.java` | 29-88 | GET ordersheet 구현 (HMAC 포함) |
| `CoupangOrderAdapter.java` | 47-98 | 상태 순회 + 페이징 호출 로직 |
| `CoupangOrderAdapter.java` | 164-286 | 응답 파싱 → MarketOrderDto 변환 |
| `CoupangStatusMapper.java` | 30-44 | 쿠팡 상태 → 내부 상태 매핑 |
