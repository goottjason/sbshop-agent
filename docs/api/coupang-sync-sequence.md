# 쿠팡 주문 동기화 시퀀스

## 트리거 방식
- **스케줄러** (`OrderSyncScheduler.syncCoupangOrders()` → `@Scheduled(cron = "0 5/30 * * * ?")`, 현재 비활성화)
- **수동** (`POST /api/v1/orders/sync/coupang` — `OrderSyncController`)

```mermaid
sequenceDiagram
    participant Trigger as 스케줄러/수동요청
    participant SyncSvc as CoupangOrderSyncService
    participant Adapter as CoupangOrderAdapter
    participant ApiPort as CoupangOrderApiPort
    participant DB as Database

    Trigger->>SyncSvc: syncCoupangOrders()

    Note over SyncSvc: AtomicBoolean 중복실행 방지

    SyncSvc->>DB: loadAndValidateCredential()
    DB-->>SyncSvc: MarketCredential

    SyncSvc->>Adapter: fetchOrders(credential, fromDate, toDate)

    loop 6개 상태 (ACCEPT, INSTRUCT, DEPARTURE, DELIVERING, FINAL_DELIVERY, NONE_TRACKING)
        Adapter->>ApiPort: fetchOrders(vendorId, accessKey, secretKey, fromDate, toDate, status)
        ApiPort-->>Adapter: JSON 주문목록
        Adapter->>Adapter: PAYMENT_WAITING/DEPOSIT_WAITING 제외
        loop 각 주문 파싱
            Adapter->>ApiPort: resolveExternalVendorSku → queryProduct() (externalVendorSku 보정)
            ApiPort-->>Adapter: 상품정보
            Adapter->>Adapter: MarketOrderDto 빌드
        end
        Note over Adapter: 1초간 대기 (API Rate Limit)
    end
    Adapter-->>SyncSvc: List<MarketOrderDto>

    SyncSvc->>SyncSvc: processOrders()
    loop 각 MarketOrderDto
        SyncSvc->>DB: findByMarketOrderNo()
        alt 신규 주문
            SyncSvc->>SyncSvc: buildOrderFromDto() → Order.builder()
            SyncSvc->>DB: orderRepository.save(order)
            SyncSvc->>SyncSvc: buildLineItemFromDto() → OrderLineItem.builder() (정산액 = totalAmount × 0.89)
            SyncSvc->>DB: orderLineItemRepository.save(lineItem)
        else 기존 주문
            SyncSvc->>SyncSvc: updateLineItemFromDto() (productId, trackingNo, carrier, status)
            SyncSvc->>SyncSvc: updateOrderInfoFromDto() (받는이, 주소, 메시지 등)
            SyncSvc->>DB: orderRepository.save(order) + orderLineItemRepository.save(lineItem)
        end
    end

    SyncSvc->>SyncSvc: postSyncProcess()
    SyncSvc->>Adapter: detectCancellations(orders, fromDate, toDate)
    Adapter->>DB: 주문 조회 → API 목록에 없는 DB주문 CANCELED 처리
    SyncSvc->>Adapter: fixCarriers(orders)
    Adapter->>DB: 택배사 ETC → 실제 택배사로 보정

    SyncSvc->>SyncSvc: eventPublisher.publishEvent(SyncCompletedEvent)
    Note over SyncSvc: finally 블록 - 항상 실행
```
