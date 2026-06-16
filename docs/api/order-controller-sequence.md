# OrderController API 시퀀스 다이어그램

> 기준일: 2026-06-16
> API Base URL: `/api/v1/orders`

---

## 1. 주문 목록 검색

```
GET /api/v1/orders?marketTypes=COUPANG&keyword=홍길동&page=0&size=20
```

```mermaid
sequenceDiagram
    participant F as Frontend
    participant C as OrderController
    participant S as OrderService
    participant R as OrderRepositoryImpl<br/>(QueryDSL)
    participant DB as MariaDB

    F->>C: GET /api/v1/orders<br/>?marketTypes=COUPANG<br/>&keyword=홍길동
    C->>S: searchOrders(condition, pageable)
    S->>R: searchOrderGrid(condition, pageable)

    rect rgb(240, 248, 255)
        Note over R,DB: Query 1: Paginated Orders
        R->>DB: SELECT DISTINCT o.*<br/>FROM sb_order o<br/>LEFT JOIN sb_order_line_item li<br/>LEFT JOIN sb_product p<br/>WHERE o.market_type IN (?)<br/>  AND p.original_name LIKE '%홍길동%'<br/>ORDER BY o.order_date DESC<br/>OFFSET 0 LIMIT 20
        DB-->>R: List<Order>
    end

    rect rgb(240, 248, 255)
        Note over R,DB: Query 2: LineItems + Products + MarketRegistrations
        R->>DB: SELECT li.*, p.*, mr.*<br/>FROM sb_order_line_item li<br/>LEFT JOIN sb_product p ON li.product_id = p.id<br/>LEFT JOIN sb_market_registration mr ON mr.product_id = p.id<br/>WHERE li.order_id IN (id1, id2, ...)
        DB-->>R: List<Tuple(li, p, mr)>
    end

    rect rgb(255, 248, 240)
        Note over R: Group by orderId → by lineItemId<br/>→ filter mr by order.marketType
        R->>R: OrderDetailDto 계층 구조 조립
    end

    R-->>S: Page<OrderDetailDto>
    S-->>C: Page<OrderDetailDto>
    C-->>F: 200 OK<br/>{<br/>  content: [{<br/>    order: {...},<br/>    lineItems: [{<br/>      lineItem: {...},<br/>      product: {...},<br/>      marketRegistration: {...}<br/>    }]<br/>  }],<br/>  totalElements: 42,<br/>  totalPages: 3<br/>}
```

---

## 2. 주문 정보 수정

```
PATCH /api/v1/orders/{id}
RequestBody: { recipientName, recipientPhone, zipcode, address, message, customsClearanceNo, customsStatus }
```

```mermaid
sequenceDiagram
    participant F as Frontend
    participant C as OrderController
    participant S as OrderService
    participant R as OrderRepository
    participant DB as MariaDB

    F->>C: PATCH /api/v1/orders/42<br/>{ recipientName: "김철수", address: "서울시..." }
    C->>C: OrderUpdateRequest → OrderUpdateCommand 변환
    C->>S: updateOrder(42, command)
    S->>R: findById(42)
    R-->>S: Order

    rect rgb(240, 248, 255)
        Note over S: order.updateInfo(name, phone, zip, addr, msg)<br/>order.updateCustomsStatus(status)<br/>order.updateCustomsClearanceNo(no)
    end

    Note over S: JPA dirty checking → 자동 UPDATE

    S-->>C: Order (managed entity)
    C-->>F: 200 OK<br/>{ id: 42, recipientName: "김철수", ... }
```

---

## 3. 라인아이템 수정

```
PATCH /api/v1/orders/line-items/{id}
RequestBody: { trackingNo, shippingCarrier, settlementAmount, sourcingAccount, ... }
```

```mermaid
sequenceDiagram
    participant F as Frontend
    participant C as OrderController
    participant S as OrderService
    participant R as OrderLineItemRepository
    participant OR as OrderRepository
    participant CR as MarketCredentialRepository
    participant M as MarketOrderPort (Adapter)
    participant API as Marketplace API
    participant DB as MariaDB

    F->>C: PATCH /api/v1/orders/line-items/7<br/>{ trackingNo: "1234567890", shippingCarrier: "CJ_LOGISTICS" }
    C->>S: updateOrderLineItem(7, command)

    S->>R: findById(7)
    R-->>S: OrderLineItem (oldTrackingNo = null)

    Note over S: trackingNo 변경 감지<br/>(null → "1234567890")

    rect rgb(240, 248, 255)
        Note over S: lineItem.updateShipping(...)<br/>lineItem.buildShippingData(...)<br/>lineItem.buildSourcingData(...)<br/>lineItem.buildSettlementData(...)
    end

    S->>R: save(lineItem)
    R->>DB: UPDATE sb_order_line_item SET ...
    DB-->>R: OK

    alt trackingNo가 변경됨
        Note over S: syncTrackingToMarketplace()
        S->>OR: findById(orderId)
        OR-->>S: Order
        S->>CR: findByMarketType(order.marketType)
        CR-->>S: MarketCredential
        S->>M: shipOrder(credential, order, lineItem, trackingNo, carrier)
        M->>API: HTTP POST (마켓별 상이)
        API-->>M: 200 OK
        M-->>S: 성공
    end

    S-->>C: OrderLineItem
    C-->>F: 200 OK<br/>{ id: 7, trackingNo: "1234567890", shippingStatus: "SHIPPED", ... }
```

---

## 4. 주문 삭제

```
DELETE /api/v1/orders/{id}
```

```mermaid
sequenceDiagram
    participant F as Frontend
    participant C as OrderController
    participant S as OrderService
    participant R as OrderLineItemRepository
    participant OR as OrderRepository
    participant DB as MariaDB

    F->>C: DELETE /api/v1/orders/42
    C->>S: deleteOrder(42)
    S->>R: findByOrderId(42)
    R-->>S: List<OrderLineItem>
    S->>R: deleteAll(lineItems)
    R->>DB: DELETE FROM sb_order_line_item WHERE order_id = 42

    S->>OR: deleteById(42)
    OR->>DB: DELETE FROM sb_order WHERE id = 42

    S-->>C: void
    C-->>F: 204 No Content
```

---

## 5. 일괄 출고처리

```
POST /api/v1/orders/ship
RequestBody: { orderIds: [42, 43, 44] }
```

```mermaid
sequenceDiagram
    participant F as Frontend
    participant C as OrderController
    participant S as OrderShipService
    participant OR as OrderRepository
    participant CR as MarketCredentialRepository
    participant R as OrderLineItemRepository
    participant M as MarketOrderPort (Adapter)
    participant API as Marketplace API
    participant DB as MariaDB

    F->>C: POST /api/v1/orders/ship<br/>{ orderIds: [42, 43] }
    C->>S: bulkShipOrders([42, 43])
    S->>OR: findById(42)
    OR-->>S: Order

    S->>CR: findByMarketType(order.marketType)
    CR-->>S: MarketCredential

    S->>R: findByOrderId(42)
    R-->>S: [lineItemA, lineItemB]

    loop 각 lineItem
        alt trackingNo가 있음
            S->>M: shipOrder(credential, order, lineItem, trackingNo, carrier)
            M->>API: HTTP POST (송장전송)
            API-->>M: 200 OK

            rect rgb(240, 248, 255)
                Note over S: lineItem.updateShipping(... → SHIPPED)<br/>calculateSettlement(lineItem) → *0.89
            end

            S->>R: save(lineItem)
            R->>DB: UPDATE sb_order_line_item
        else trackingNo 없음
            Note over S: skip (출고 불가)
        end
    end

    S->>OR: findById(43)
    OR-->>S: Order
    Note over S: ... (반복)

    S-->>C: shippedCount = 3
    C-->>F: 200 OK<br/>{ success: true, shippedCount: 3 }
```

---

## 6. 주문 확정

```
POST /api/v1/orders/{id}/confirm
```

```mermaid
sequenceDiagram
    participant F as Frontend
    participant C as OrderController
    participant S as OrderService
    participant OR as OrderRepository
    participant CR as MarketCredentialRepository
    participant R as OrderLineItemRepository
    participant M as MarketOrderPort (Adapter)
    participant API as Marketplace API
    participant DB as MariaDB

    F->>C: POST /api/v1/orders/42/confirm
    C->>S: confirmOrder(42)

    S->>OR: findById(42)
    OR-->>S: Order (marketType=COUPANG)

    S->>OR: findByOrderId(42)
    OR-->>S: [lineItemA, lineItemB]

    alt 모든 lineItem이 이미 PREPARING 이상
        Note over S: early return (이미 확정됨)
        S-->>C: void
        C-->>F: 200 OK { success: true }
    end

    S->>CR: findByMarketType(COUPANG)
    CR-->>S: MarketCredential

    rect rgb(255, 240, 240)
        Note over S: callMarketplaceAcceptApi()
        S->>M: acceptOrders(credential, order)
        M->>API: PUT .../acknowledgement (쿠팡 발주확인)
        API-->>M: 200 OK
    end

    S->>R: findByOrderId(42)
    R-->>S: [lineItemA, lineItemB]

    loop 각 lineItem
        alt shippingStatus == NEW
            S->>R: save(item.shippingStatus = PREPARING)
        end
    end

    S-->>C: void
    C-->>F: 200 OK<br/>{ success: true, message: "Order confirmed successfully." }
```

---

## 7. 일괄 주문 확정

```
POST /api/v1/orders/confirm/batch
RequestBody: { orderIds: [42, 43, 44] }
```

```mermaid
sequenceDiagram
    participant F as Frontend
    participant C as OrderController
    participant S as OrderService

    F->>C: POST /api/v1/orders/confirm/batch<br/>{ orderIds: [42, 43] }
    alt orderIds가 비었음
        C-->>F: 400 Bad Request<br/>{ success: false }
    end

    C->>S: bulkConfirmOrders([42, 43])

    loop 각 orderId
        rect rgb(255, 255, 240)
            Note over S: try { confirmOrder(id) } catch { failedIds.add(id) }
        end
        Note over S: 6. 주문 확정 시퀀스와 동일<br/>(API 호출 + lineItem 상태 변경)
    end

    S-->>C: { successCount: 2, failedCount: 0 }
    C-->>F: 200 OK<br/>{ success: true, result: { successCount: 2, failedCount: 0 } }
```

---

## 8. 주문 취소

```
POST /api/v1/orders/{id}/cancel
```

```mermaid
sequenceDiagram
    participant F as Frontend
    participant C as OrderController
    participant S as OrderService
    participant OR as OrderRepository
    participant R as OrderLineItemRepository
    participant DB as MariaDB

    F->>C: POST /api/v1/orders/42/cancel
    C->>S: cancelOrder(42)

    S->>OR: findById(42)
    OR-->>S: Order

    S->>R: findByOrderId(42)
    R-->>S: [lineItemA, lineItemB]

    loop 각 lineItem
        Note over S: item.updateShipping(trackingNo, CANCELED, isUnipassDone)
        S->>R: save(lineItem)
        R->>DB: UPDATE sb_order_line_item<br/>SET shipping_status = CANCELED
    end

    Note over S: 마켓 API 호출 없음 (로컬 취소만)

    S-->>C: void
    C-->>F: 200 OK<br/>{ success: true, message: "Order canceled successfully." }
```

---

## 9. 구매 처리 (PREPARING → PURCHASED)

```
POST /api/v1/orders/line-items/{lineItemId}/purchase
RequestBody: { sourcingAccount, sourcingOrderNo, discountCode, sourcingVendor }
```

```mermaid
sequenceDiagram
    participant F as Frontend
    participant C as OrderController
    participant S as OrderService
    participant IES as IherbEmailSearchService
    participant R as OrderLineItemRepository
    participant DB as MariaDB

    F->>C: POST /api/v1/orders/line-items/7/purchase<br/>{ sourcingAccount: "iherb", sourcingOrderNo: "IH12345" }

    C->>S: markAsPurchasedWithAmount(7, "iherb", "IH12345", null, null, null)

    S->>R: findById(7)
    R-->>S: OrderLineItem (status=PREPARING)

    alt status != PREPARING
        Note over S: throw IllegalStateException<br/>"구매 처리 가능한 상태가 아닙니다"
        S-->>C: throw
        C-->>F: 400 Bad Request
    end

    rect rgb(240, 248, 255)
        Note over S: sourcingAccount가 있음 →<br/>item.updateSourcingForIherb(account, orderNo, discountCode)<br/>item.markAsPurchased()<br/>→ shippingStatus = PURCHASED
    end

    S->>R: save(lineItem)
    R->>DB: UPDATE sb_order_line_item
    DB-->>R: OK
    S-->>C: void

    alt sourcingAccount/OrderNo가 있고 iHerb 상품
        Note over C: 백그라운드 스레드 시작<br/>(비동기 이메일 검색)
        C->>IES: findConfirmationAmount("IH12345")
        IES->>IES: IMAP 이메일 검색<br/>(iHerb 결제확인 메일)
        IES-->>C: Optional<BigDecimal> amount
        alt amount 있음
            C->>S: updateSourcingAmount(7, amount)
            S->>R: findById(7)
            S->>R: save(updatedSourcingAmount)
        end
    end

    C-->>F: 200 OK (즉시 응답)<br/>{ success: true, message: "구매 완료 처리됨" }
```

---

## 10. 배송 처리 (PURCHASED → SHIPPED)

```
POST /api/v1/orders/line-items/{lineItemId}/ship
RequestBody: { trackingNo, carrier }
```

```mermaid
sequenceDiagram
    participant F as Frontend
    participant C as OrderController
    participant S as OrderService
    participant R as OrderLineItemRepository
    participant OR as OrderRepository
    participant CR as MarketCredentialRepository
    participant M as MarketOrderPort (Adapter)
    participant API as Marketplace API
    participant DB as MariaDB

    F->>C: POST /api/v1/orders/line-items/7/ship<br/>{ trackingNo: "CJ123456", carrier: "CJ_LOGISTICS" }

    C->>S: processShipping(7, "CJ123456", CJ_LOGISTICS)

    S->>R: findById(7)
    R-->>S: OrderLineItem (status=PURCHASED)

    alt status != PURCHASED
        Note over S: throw IllegalStateException
        S-->>C: throw
        C-->>F: 400 Bad Request
    end

    rect rgb(240, 248, 255)
        Note over S: item.updateTrackingInfo("CJ123456", CJ_LOGISTICS)<br/>item.updateShippingStatus(SHIPPED)
    end

    S->>R: save(lineItem)
    R->>DB: UPDATE sb_order_line_item
    DB-->>R: OK

    rect rgb(255, 255, 240)
        Note over S: syncTrackingToMarketplace()
        S->>OR: findById(orderId)
        OR-->>S: Order
        S->>CR: findByMarketType(order.marketType)
        CR-->>S: MarketCredential
        S->>M: shipOrder(cred, order, lineItem, trackingNo, carrier)
        M->>API: POST .../dispatch (마켓 송장전송)
        API-->>M: 200 OK
    end

    S-->>C: void
    C-->>F: 200 OK<br/>{ success: true, message: "배송 처리 완료" }
```

---

## 11. 송장 수정 (SHIPPED 상태)

```
PUT /api/v1/orders/line-items/{lineItemId}/tracking
RequestBody: { trackingNo, carrier }
```

```mermaid
sequenceDiagram
    participant F as Frontend
    participant C as OrderController
    participant S as OrderService
    participant R as OrderLineItemRepository
    participant OR as OrderRepository
    participant CR as MarketCredentialRepository
    participant M as MarketOrderPort (Adapter)
    participant API as Marketplace API
    participant DB as MariaDB

    F->>C: PUT /api/v1/orders/line-items/7/tracking<br/>{ trackingNo: "CJ999999", carrier: "CJ_LOGISTICS" }

    C->>S: updateTrackingInfo(7, "CJ999999", CJ_LOGISTICS)

    S->>R: findById(7)
    R-->>S: OrderLineItem (status=SHIPPED)

    alt status != SHIPPED
        Note over S: throw IllegalStateException
        S-->>C: throw
        C-->>F: 400 Bad Request
    end

    rect rgb(240, 248, 255)
        Note over S: item.updateTrackingInfo("CJ999999", CJ_LOGISTICS)<br/>※ shippingStatus 변경 없음 (SHIPPED 유지)
    end

    S->>R: save(lineItem)
    R->>DB: UPDATE sb_order_line_item
    DB-->>R: OK

    rect rgb(255, 255, 240)
        Note over S: syncTrackingToMarketplace()
        S->>OR: findById(orderId)
        OR-->>S: Order
        S->>CR: findByMarketType(order.marketType)
        CR-->>S: MarketCredential
        S->>M: shipOrder(cred, order, lineItem, trackingNo, carrier)
        M->>API: POST ... (마켓 송장 재전송)
        API-->>M: 200 OK
    end

    S-->>C: void
    C-->>F: 200 OK<br/>{ success: true, message: "송장 수정 완료" }
```

---

## ShippingStatus 생명주기

```mermaid
stateDiagram-v2
    [*] --> NEW : 마켓 동기화
    NEW --> PREPARING : 주문 확정 (confirm)
    NEW --> CANCELED : 주문 취소
    PREPARING --> PURCHASED : 구매 처리 (markAsPurchased)
    PREPARING --> CANCELED : 주문 취소
    PURCHASED --> SHIPPED : 배송 처리 (processShipping)
    PURCHASED --> CANCELED : 주문 취소
    SHIPPED --> DELIVERED : 마켓 동기화
    SHIPPED --> CANCELED : 주문 취소

    CANCELED --> [*]
    DELIVERED --> [*]
```

---

## 전체 API 요약

| # | Method | URL | Controller Method | Service Method |
|---|--------|-----|-------------------|----------------|
| 1 | `GET` | `/api/v1/orders` | `getOrders()` | `OrderService.searchOrders()` |
| 2 | `PATCH` | `/api/v1/orders/{id}` | `updateOrder()` | `OrderService.updateOrder()` |
| 3 | `PATCH` | `/api/v1/orders/line-items/{id}` | `updateOrderLineItem()` | `OrderService.updateOrderLineItem()` |
| 4 | `DELETE` | `/api/v1/orders/{id}` | `deleteOrder()` | `OrderService.deleteOrder()` |
| 5 | `POST` | `/api/v1/orders/ship` | `shipOrders()` | `OrderShipService.bulkShipOrders()` |
| 6 | `POST` | `/api/v1/orders/{id}/confirm` | `confirmOrder()` | `OrderService.confirmOrder()` |
| 7 | `POST` | `/api/v1/orders/confirm/batch` | `bulkConfirmOrders()` | `OrderService.bulkConfirmOrders()` |
| 8 | `POST` | `/api/v1/orders/{id}/cancel` | `cancelOrder()` | `OrderService.cancelOrder()` |
| 9 | `POST` | `/api/v1/orders/line-items/{lineItemId}/purchase` | `markLineItemPurchased()` | `OrderService.markAsPurchasedWithAmount()` |
| 10 | `POST` | `/api/v1/orders/line-items/{lineItemId}/ship` | `processShipping()` | `OrderService.processShipping()` |
| 11 | `PUT` | `/api/v1/orders/line-items/{lineItemId}/tracking` | `updateTrackingInfo()` | `OrderService.updateTrackingInfo()` |
