# 11번가 발주확인

> **Endpoint**: `GET /rest/ordservices/reqpackaging/{ordNo}/{ordPrdSeq}/{addPrdYn}/{addPrdNo}/{dlvNo}`
> **Base URL**: `https://api.11st.co.kr`
> **Purpose**: 개별 상품 단위 발주확인 처리

---

## 1. Request

### URL Path

```
GET /rest/ordservices/reqpackaging/{ordNo}/{ordPrdSeq}/{addPrdYn}/{addPrdNo}/{dlvNo}
```

### Headers

| Header          | Value                  | Description      |
|-----------------|------------------------|------------------|
| `openapikey`    | `{apiKey}`             | 11번가 API 키     |
| `Accept`        | `application/xml`      | 응답 형식         |

### Path Parameters

| Parameter   | Required | Description                          |
|-------------|----------|--------------------------------------|
| `ordNo`     | Y        | 주문번호 (`order.marketOrderNo`)       |
| `ordPrdSeq` | Y        | 주문상품순번 (상품 식별자)             |
| `addPrdYn`  | Y        | 추가구성상품여부 (Y/N)                |
| `addPrdNo`  | Y        | 추가구성상품번호                      |
| `dlvNo`     | Y        | 배송번호                              |

### Request Example

```
GET /rest/ordservices/reqpackaging/20260612076034242/1/N/0/20260612076034242
Authorization: openapikey {apiKey}
```

---

## 2. Response

### Content-Type

```
application/xml; charset=EUC-KR
```

### Response Body

```xml
<?xml version="1.0" encoding="euc-kr"?>
<result>
  <result_code>0</result_code>
  <result_text>발주확인완료</result_text>
</result>
```

### Response Fields

| Field         | Type   | Description                           |
|---------------|--------|---------------------------------------|
| `result_code` | string | 결과 코드 (0: 성공, 그 외: 실패)        |
| `result_text` | string | 결과 설명                              |

---

## 3. 상태 매핑

| 내부 ShippingStatus | placeOrderStatus | 설명       |
|---------------------|------------------|-----------|
| `NEW`               | `NOT_YET`        | 발주 미확인 |
| `PREPARING`         | `OK`             | 발주 확인   |
| `SHIPPED`           | -                | 배송 중     |
| `DELIVERED`         | -                | 배송 완료   |

---

## 4. 흐름도

```
┌──────────────────────────────────────────────┐
│  acceptOrders() 호출                          │
├──────────────────────────────────────────────┤
│  1. Order에서 marketSpecificData 추출         │
│     ├─ ordPrdSeq, addPrdYn, addPrdNo, dlvNo  │
│     └─ 없으면 IllegalArgumentException 발생    │
│                                               │
│  2. ElevenstOrderApiPort.confirmOrder() 호출  │
│     ├─ GET /rest/ordservices/reqpackaging/... │
│     └─ XML 응답 파싱                          │
│                                               │
│  3. result_code 확인                           │
│     ├─ "0" → 성공                             │
│     └─ 그 외 → RuntimeException 발생           │
└──────────────────────────────────────────────┘
```

---

## 5. 데이터 흐름

```
동기화 시 (ElevenstOrderSyncService)
  │
  ├─ 11번가 XML 응답에서 ordPrdSeq, addPrdYn, addPrdNo, dlvNo 추출
  ├─ MarketOrderDto.marketSpecificData에 저장
  └─ Order.marketSpecificData (JSON) 컬럼에 저장

발주확인 시 (ElevenstOrderAdapter.acceptOrders)
  │
  ├─ Order.getMarketSpecificDataMap()으로 추출
  ├─ ElevenstOrderApiPort.confirmOrder() 호출
  └─ API 결과 확인 (result_code = "0")
```

---

## 6. 주의사항

### 개별 상품 단위 확인

11번가 발주확인은 주문이 아닌 **개별 상품 단위**로 처리. 하나의 주문에 여러 상품이 있으면 각 상품을 개별로 확인해야 함.

### 파라미터 필수

`ordPrdSeq`, `addPrdYn`, `addPrdNo`, `dlvNo`는 반드시 `Order.marketSpecificData`에 저장되어 있어야 함. 동기화 시 XML에서 추출하여 저장.

### 단일 상품 가정

현재 시스템은 주문당 상품 1개를 기준으로 설계. `ordPrdSeq`는 항상 `"1"`, `addPrdYn`은 `"N"`, `addPrdNo`는 `"0"`.

---

## 7. 소스 코드 참조

| 파일 위치                              | 설명                     |
|---------------------------------------|--------------------------|
| `ElevenstOrderApiPort.java`           | 포트 인터페이스            |
| `ElevenstOrderApiClient.java`         | REST API 호출 구현        |
| `ElevenstOrderAdapter.java`           | 어댑터 (MarketOrderPort)  |
| `ElevenstOrderSyncService.java`       | 동기화 서비스              |
