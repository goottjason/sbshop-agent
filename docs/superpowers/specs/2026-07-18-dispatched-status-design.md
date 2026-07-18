# 배송지시(DISPATCHED) 상태 신설 + PurchaseStatus 분리 설계

**날짜:** 2026-07-18  
**상태:** 승인됨

---

## 배경

쿠팡 주문에서 고객 취소 방지를 위해 가짜 송장을 등록하면 쿠팡 상태는 `INSTRUCT`(배송지시)로 유지되고, 실제 정상 송장 교체 후 택배사 픽업 시점에 비로소 `DELIVERING`(배송중)으로 전환된다. 현재 시스템은 `INSTRUCT`를 `PREPARING(구매준비)`으로 처리하여 상태 불일치가 발생한다.

스마트스토어 역시 동일한 시점에 `DISPATCHED` 상태가 존재하며, 현재 `SHIPPED(배송중)`으로 잘못 매핑되어 있다.

또한 기존 `ShippingStatus.PURCHASED(구매완료)`는 마켓 동기화 상태가 아닌 내부 소싱/구매 처리 여부를 나타내는 상태로, 관심사가 혼재되어 있다. 이를 분리한다.

---

## 설계 목표

1. 마켓 상태와 1:1로 대응하는 `DISPATCHED(배송지시)` 상태를 `ShippingStatus`에 추가한다.
2. 내부 구매 처리 여부는 별도 `PurchaseStatus` enum으로 분리하여 `order_line_items` 레벨에서 관리한다.
3. 미래에 다품목 주문이 가능한 구조를 유지한다.

---

## 섹션 1: ShippingStatus 변경

### 새 상태 흐름

```
NEW(결제완료, 0)
  → PREPARING(구매준비, 1)
  → DISPATCHED(배송지시, 2)   ← 신규: 송장 등록됨, 택배사 미픽업
  → SHIPPED(배송중, 3)
  → DELIVERED(배송완료, 4)
```

### enum 변경

- `DISPATCHED("배송지시", 2)` 추가
- `PURCHASED("구매완료", 2)` 제거

### 마켓별 상태 매핑 변경

| 마켓 | 원본 상태 | 변경 전 | 변경 후 |
|------|----------|---------|---------|
| 쿠팡 | `INSTRUCT` | `PREPARING` | **`DISPATCHED`** |
| 스마트스토어 | `DISPATCHED` | `SHIPPED` | **`DISPATCHED`** |
| 11번가 | (배송지시 없음) | — | 변경 없음 |
| G마켓/옥션 ESM+ | (배송지시 없음) | — | 변경 없음 |
| Cafe24 | `N21`(배송대기) | `PREPARING` | `PREPARING` 유지 |
| Cafe24 | `N22`(배송보류) | `PREPARING` | `PREPARING` 유지 |

**결정 근거:**
- 11번가·G마켓/옥션: 송장 등록 즉시 마켓 측에서 "배송중"으로 전환. 배송지시 개념 없음.
- Cafe24 N21(배송대기): G마켓/옥션 주문이 Cafe24를 통해 들어오는 경로이므로 G마켓/옥션 기준에 맞춤. N21 → N30 전환 후 G마켓/옥션에 배송중이 반영되므로 N21 시점은 PREPARING.

### 마켓 배송 처리 시 즉시 반영

`MarketplaceShippingService`에서 마켓에 송장 전송 성공 시, 다음 동기화를 기다리지 않고 내부 상태를 즉시 `DISPATCHED`로 업데이트한다. 이후 동기화가 같은 상태로 덮어써도 무방하다.

---

## 섹션 2: PurchaseStatus 신설

### enum 정의

```java
// core/domain/order/enums/PurchaseStatus.java
public enum PurchaseStatus implements EnumMapperType {
    NOT_PURCHASED("미구매"),
    PURCHASED("구매완료"),
    WAITING_STOCK("입고대기");
}
```

향후 확장 가능 예시: `PARTIALLY_PURCHASED`, `BACK_ORDER` 등.

### 위치: order_line_items 레벨

현재 1주문=1아이템이지만, 추후 옵션 다품목 지원 시 아이템별 구매 상태 관리가 필요하므로 `OrderLineItem` 엔티티에 추가한다.

```java
// OrderLineItem 엔티티
@Column(name = "purchase_status")
@Enumerated(EnumType.STRING)
private PurchaseStatus purchaseStatus = PurchaseStatus.NOT_PURCHASED;
```

### 상태 변경 방식

마켓 동기화와 **무관하게** 독립 동작한다. 동기화 시 `shippingStatus`만 갱신하며 `purchaseStatus`는 건드리지 않는다.

변경 API:
```
PATCH /orders/{orderId}/items/{itemId}/purchase-status
{ "purchaseStatus": "PURCHASED" }
```

### 기존 PURCHASED 레코드 마이그레이션

기존 `shipping_status = 'PURCHASED'` 레코드를 아래 쿼리로 이전한다. `shipping_status`는 실제 마켓 상태에 맞게 수동 검토 후 재분류한다.

```sql
-- 기본값으로 PREPARING 처리 (실제 상황에 맞게 조정 필요)
UPDATE order_line_items
SET purchase_status = 'PURCHASED',
    shipping_status = 'PREPARING'
WHERE shipping_status = 'PURCHASED';
```

---

## 섹션 3: 프론트엔드

### DTO 확장

```typescript
// api/orderApi.ts
export interface OrderLineItemDto {
  purchaseStatus?: 'NOT_PURCHASED' | 'PURCHASED' | 'WAITING_STOCK';
  shippingData?: { ... };  // 기존 유지
}
```

### ShippingStatus 레이블 추가

```typescript
const SHIPPING_STATUS_LABEL: Record<string, string> = {
  NEW: '결제완료',
  PREPARING: '구매준비',
  DISPATCHED: '배송지시',   // 신규
  SHIPPED: '배송중',
  DELIVERED: '배송완료',
  CANCELED: '취소됨',
  RETURNED: '반품됨',
  EXCHANGED: '교환됨',
};
```

### PurchaseStatus 셀렉트박스

주문 상세/목록 화면에서 ShippingStatus 옆에 나란히 배치:

```
배송상태: [ 배송지시 ▼ ]    구매상태: [ 미구매 ▼ ]
```

- 셀렉트 변경 즉시 `PATCH` API 호출
- 옵션: 미구매 / 구매완료 / 입고대기

---

## 전체 변경 목록

| 레이어 | 대상 | 변경 내용 |
|--------|------|----------|
| enum | `ShippingStatus` | `DISPATCHED` 추가, `PURCHASED` 제거 |
| enum | `PurchaseStatus` | 신규 생성 |
| mapper | `CoupangStatusMapper` | `INSTRUCT` → `DISPATCHED` |
| mapper | `SmartStoreStatusMapper` | `DISPATCHED` → `DISPATCHED` (SHIPPED에서 수정) |
| service | `MarketplaceShippingService` | 송장 전송 성공 시 즉시 `DISPATCHED` 업데이트 |
| entity | `OrderLineItem` | `purchaseStatus` 필드 추가 |
| API | 신규 엔드포인트 | `PATCH /orders/{id}/items/{itemId}/purchase-status` |
| DB | `order_line_items` | `purchase_status VARCHAR(50) DEFAULT 'NOT_PURCHASED'` 컬럼 추가 |
| DB | 마이그레이션 | 기존 `PURCHASED` 레코드 재분류 |
| frontend | `orderApi.ts` | `purchaseStatus` 필드 추가 |
| frontend | 상태 레이블 | `DISPATCHED` 레이블 추가 |
| frontend | 주문 화면 | `PurchaseStatus` 셀렉트박스 추가 |
