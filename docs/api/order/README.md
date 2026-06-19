# 주문 관리 API

## Base URL
```
/api/v1/orders
```

## 엔드포인트

### 1. 주문 목록 조회

| 항목 | 내용 |
|------|------|
| **Method** | `GET` |
| **URL** | `/api/v1/orders` |
| **설명** | 검색 조건과 페이징에 따라 주문 목록을 조회합니다. |

**Query Parameters (OrderSearchCondition)**

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| `marketTypes` | `List<MarketType>` | No | 마켓 타입 필터 (COUPANG, SMARTSTORE, ELEVENST, ESMPLUS) |
| `shippingStatuses` | `List<ShippingStatus>` | No | 배송 상태 필터 (NEW, PREPARING, SHIPPED, DELIVERED, CANCELLED) |
| `keyword` | `String` | No | 검색 키워드 |
| `startDate` | `LocalDateTime` | No | 시작일 |
| `endDate` | `LocalDateTime` | No | 종료일 |

**Response (200 OK)**
```json
{
  "content": [
    {
      "order": {
        "id": 1,
        "orderNo": "ORD-20240615-001",
        "marketType": "COUPANG",
        "recipientName": "홍길동",
        "recipientPhone": "010-1234-5678",
        "zipcode": "06123",
        "address": "서울시 강남구...",
        "shippingStatus": "NEW",
        "customsStatus": "PENDING",
        "createdAt": "2024-06-15T10:30:00"
      },
      "lineItems": [
        {
          "lineItem": {
            "id": 1,
            "productName": "상품명",
            "quantity": 2,
            "price": 15000
          },
          "product": {},
          "marketRegistration": {}
        }
      ]
    }
  ],
  "totalElements": 100,
  "totalPages": 10,
  "size": 10,
  "number": 0
}
```

---

### 2. 주문 수정

| 항목 | 내용 |
|------|------|
| **Method** | `PATCH` |
| **URL** | `/api/v1/orders/{id}` |
| **설명** | 주문 정보를 수정합니다. |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `id` | `Long` | 주문 ID |

**Request Body (OrderUpdateRequest)**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `recipientName` | `String` | No | 수취인 이름 |
| `recipientPhone` | `String` | No | 수취인 전화번호 |
| `zipcode` | `String` | No | 우편번호 |
| `address` | `String` | No | 주소 |
| `message` | `String` | No | 메모 |
| `customsClearanceNo` | `String` | No | 통관번호 |
| `customsStatus` | `CustomsStatus` | No | 통관 상태 |

**Response (200 OK)**
```json
{
  "id": 1,
  "orderNo": "ORD-20240615-001",
  "recipientName": "홍길동",
  "recipientPhone": "010-1234-5678",
  "zipcode": "06123",
  "address": "서울시 강남구..."
}
```

---

### 3. 주문 삭제

| 항목 | 내용 |
|------|------|
| **Method** | `DELETE` |
| **URL** | `/api/v1/orders/{id}` |
| **설명** | 주문을 삭제합니다. |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `id` | `Long` | 주문 ID |

**Response (204 No Content)**

---

### 4. 주문 발송 처리

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/orders/ship` |
| **설명** | 여러 주문을 일괄 발송 처리합니다. |

**Request Body (OrderShipRequest)**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `orderIds` | `List<Long>` | Yes | 발송 처리할 주문 ID 목록 |

**Request Example**
```json
{
  "orderIds": [1, 2, 3]
}
```

**Response (200 OK)**
```json
{
  "success": true,
  "shippedCount": 3,
  "message": "Successfully shipped 3 orders."
}
```

---

### 5. 주문 확정

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/orders/{id}/confirm` |
| **설명** | 단일 주문을 확정 처리합니다. |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `id` | `Long` | 주문 ID |

**Response (200 OK)**
```json
{
  "success": true,
  "message": "Order confirmed successfully."
}
```

---

### 6. 주문 일괄 확정

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/orders/confirm/batch` |
| **설명** | 여러 주문을 일괄 확정 처리합니다. |

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `orderIds` | `List<Long>` | Yes | 확정할 주문 ID 목록 |

**Request Example**
```json
{
  "orderIds": [1, 2, 3]
}
```

**Response (200 OK)**
```json
{
  "success": true,
  "result": {
    "successCount": 3,
    "failedCount": 0
  }
}
```

---

### 7. 주문 취소

| 항목 | 내용 |
|------|------|
| **Method** | `POST` |
| **URL** | `/api/v1/orders/{id}/cancel` |
| **설명** | 주문을 취소 처리합니다. |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `id` | `Long` | 주문 ID |

**Response (200 OK)**
```json
{
  "success": true,
  "message": "Order canceled successfully."
}
```

---

### 8. 구매(소싱) 정보 저장

| 항목 | 내용 |
|------|------|
| **Method** | `PUT` |
| **URL** | `/api/v1/orders/line-items/{lineItemId}/sourcing` |
| **설명** | 주문 항목의 구매(소싱) 정보를 저장하거나 수정합니다. |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `lineItemId` | `Long` | 주문 항목 ID |

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `sourcingAccount` | `String` | No | 소싱 계정 |
| `sourcingOrderNo` | `String` | No | 소싱 주문 번호 |
| `discountCode` | `String` | No | 할인 코드 |
| `sourcingVendor` | `String` | No | 소싱 벤더 |

**Request Example**
```json
{
  "sourcingAccount": "coupang_account",
  "sourcingOrderNo": "CP-12345",
  "discountCode": "SAVE10",
  "sourcingVendor": "VendorA"
}
```

**Response (200 OK)**
```json
{
  "success": true,
  "message": "구매 정보가 저장되었습니다."
}
```

---

### 9. 배송 정보 저장

| 항목 | 내용 |
|------|------|
| **Method** | `PUT` |
| **URL** | `/api/v1/orders/line-items/{lineItemId}/shipping` |
| **설명** | 주문 항목의 배송 정보를 저장하거나 수정합니다. |

**Path Parameters**

| 파라미터 | 타입 | 설명 |
|----------|------|------|
| `lineItemId` | `Long` | 주문 항목 ID |

**Request Body**

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `trackingNo` | `String` | Yes | 운송장 번호 |
| `carrier` | `String` | Yes | 택배사 (ShippingCarrier Enum) |

**Request Example**
```json
{
  "trackingNo": "123456789012",
  "carrier": "CJ_LOGISTICS"
}
```

**Response (200 OK)**
```json
{
  "success": true,
  "message": "배송 정보가 저장되었습니다."
}
```
