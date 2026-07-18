# 배송지시(DISPATCHED) 상태 신설 + PurchaseStatus 분리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ShippingStatus.PURCHASED`를 제거하고 `DISPATCHED(배송지시)`를 신설하며, 구매 처리 여부는 `PurchaseStatus` enum으로 분리하여 `order_line_items` 레벨에서 관리한다.

**Architecture:** 도메인 enum 변경 → 엔티티 필드 추가 → 마켓 매퍼 수정 → 서비스 비즈니스 로직 교체 → API 엔드포인트 추가 → DB 수동 마이그레이션 → 프론트엔드 순으로 진행한다. `PURCHASED` 제거는 모든 참조를 교체한 뒤 마지막에 한 번에 정리한다.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5 + Mockito, React 19 + TypeScript, PostgreSQL (Flyway 미사용)

## Global Constraints

- Flyway 없음 — DB 스키마 변경은 모두 수동 DDL로 실행, 개발자가 직접 운영 DB에서 실행
- 엔티티 변경 시 전 모듈 컴파일 확인 필수 (`./gradlew :backend:core:compileJava :backend:api:compileJava :backend:worker:compileJava`)
- 테스트 없는 수정 금지 — 모든 로직 변경은 Red → Green → Commit 순서
- `DISPATCHED` 순서값(order): 2 (PREPARING=1 와 SHIPPED=3 사이)
- 프론트 타입 게이트: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`

---

## 파일 변경 목록

| 구분 | 파일 |
|------|------|
| **New** | `backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/PurchaseStatus.java` |
| **New** | `backend/core/src/test/java/com/sbshop/agent/core/domain/order/enums/PurchaseStatusTest.java` |
| **New** | `backend/core/src/test/java/com/sbshop/agent/core/domain/order/enums/ShippingStatusDispatchedTest.java` |
| **New** | `backend/api/src/main/java/com/sbshop/agent/api/dto/UpdatePurchaseStatusRequest.java` |
| **Modify** | `backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/ShippingStatus.java` |
| **Modify** | `backend/core/src/main/java/com/sbshop/agent/core/domain/order/OrderLineItem.java` |
| **Modify** | `backend/core/src/main/java/com/sbshop/agent/core/application/order/mapper/CoupangStatusMapper.java` |
| **Modify** | `backend/core/src/main/java/com/sbshop/agent/core/application/order/mapper/SmartStoreStatusMapper.java` |
| **Modify** | `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderService.java` |
| **Modify** | `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderShipProcessor.java` |
| **Modify** | `backend/api/src/main/java/com/sbshop/agent/api/dto/OrderLineItemResponse.java` |
| **Modify** | `backend/api/src/main/java/com/sbshop/agent/api/controller/OrderController.java` |
| **Modify** | `backend/core/src/test/java/com/sbshop/agent/core/application/order/mapper/CoupangStatusMapperTest.java` |
| **Modify** | `backend/core/src/test/java/com/sbshop/agent/core/application/order/mapper/SmartStoreStatusMapperTest.java` |
| **Modify** | `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderServiceShippingGuardTest.java` |
| **Modify** | `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderServiceStateGuardTest.java` |
| **Modify** | `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderShipServiceGuardTest.java` |
| **Modify** | `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderShipServiceResultTest.java` |
| **Modify** | `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderServiceShippingRollbackTest.java` |
| **Modify** | `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderServiceInputGuardTest.java` |
| **Modify** | `frontend/src/api/orderApi.ts` |
| **Modify** | `frontend/src/pages/OrderGrid.tsx` |
| **DB (수동)** | `sb_order_line_item` 테이블: `purchase_status` 컬럼 추가 + 기존 PURCHASED 레코드 이전 |

---

## Task 1: DISPATCHED + PurchaseStatus enum 신규 추가

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/PurchaseStatus.java`
- Create: `backend/core/src/test/java/com/sbshop/agent/core/domain/order/enums/PurchaseStatusTest.java`
- Create: `backend/core/src/test/java/com/sbshop/agent/core/domain/order/enums/ShippingStatusDispatchedTest.java`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/ShippingStatus.java`

**Interfaces:**
- Produces: `ShippingStatus.DISPATCHED` (label="배송지시", order=2), `PurchaseStatus` enum (NOT_PURCHASED, PURCHASED, WAITING_STOCK)

- [ ] **Step 1: ShippingStatus 테스트 작성 (Red)**

파일: `backend/core/src/test/java/com/sbshop/agent/core/domain/order/enums/ShippingStatusDispatchedTest.java`

```java
package com.sbshop.agent.core.domain.order.enums;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ShippingStatusDispatchedTest {

    @Test
    void dispatched_존재하고_order_2이며_label은_배송지시() {
        assertThat(ShippingStatus.DISPATCHED).isNotNull();
        assertThat(ShippingStatus.DISPATCHED.getOrder()).isEqualTo(2);
        assertThat(ShippingStatus.DISPATCHED.getLabel()).isEqualTo("배송지시");
    }

    @Test
    void dispatched_순서는_PREPARING_보다_크고_SHIPPED_보다_작다() {
        assertThat(ShippingStatus.DISPATCHED.getOrder())
            .isGreaterThan(ShippingStatus.PREPARING.getOrder())
            .isLessThan(ShippingStatus.SHIPPED.getOrder());
    }
}
```

- [ ] **Step 2: PurchaseStatus 테스트 작성 (Red)**

파일: `backend/core/src/test/java/com/sbshop/agent/core/domain/order/enums/PurchaseStatusTest.java`

```java
package com.sbshop.agent.core.domain.order.enums;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PurchaseStatusTest {

    @Test
    void 세_가지_값이_존재한다() {
        assertThat(PurchaseStatus.values()).hasSize(3);
        assertThat(PurchaseStatus.valueOf("NOT_PURCHASED")).isEqualTo(PurchaseStatus.NOT_PURCHASED);
        assertThat(PurchaseStatus.valueOf("PURCHASED")).isEqualTo(PurchaseStatus.PURCHASED);
        assertThat(PurchaseStatus.valueOf("WAITING_STOCK")).isEqualTo(PurchaseStatus.WAITING_STOCK);
    }

    @Test
    void label_확인() {
        assertThat(PurchaseStatus.NOT_PURCHASED.getLabel()).isEqualTo("미구매");
        assertThat(PurchaseStatus.PURCHASED.getLabel()).isEqualTo("구매완료");
        assertThat(PurchaseStatus.WAITING_STOCK.getLabel()).isEqualTo("입고대기");
    }
}
```

- [ ] **Step 3: 테스트 실행 → 실패 확인**

```bash
cd /Users/jasonair/Projects/sbshop-agent/backend
./gradlew :core:test --tests "*.enums.ShippingStatusDispatchedTest" --tests "*.enums.PurchaseStatusTest" 2>&1 | tail -20
```

Expected: 컴파일 에러 (DISPATCHED 없음, PurchaseStatus 없음)

- [ ] **Step 4: ShippingStatus에 DISPATCHED 추가** (PURCHASED는 아직 유지)

파일: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/ShippingStatus.java`

```java
@Getter
@RequiredArgsConstructor
public enum ShippingStatus implements EnumMapperType {
    UNKNOWN("알수없음", -2),
    NEW("결제완료", 0),
    PREPARING("구매준비", 1),
    DISPATCHED("배송지시", 2),
    SHIPPED("배송중", 3),
    DELIVERED("배송완료", 4),
    CANCELED("취소됨", -1),
    RETURNED("반품됨", -1),
    EXCHANGED("교환됨", -1);

    private final String label;
    private final int order;

    @Override
    public String getName() {
        return name();
    }
}
```

※ `PURCHASED`는 이 단계에서 제거하지 않는다. Task 5에서 모든 참조 제거 후 삭제.

- [ ] **Step 5: PurchaseStatus 생성**

파일: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/PurchaseStatus.java`

```java
package com.sbshop.agent.core.domain.order.enums;

import com.sbshop.agent.core.domain.common.enums.EnumMapperType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PurchaseStatus implements EnumMapperType {
    NOT_PURCHASED("미구매"),
    PURCHASED("구매완료"),
    WAITING_STOCK("입고대기");

    private final String label;

    @Override
    public String getName() {
        return name();
    }
}
```

- [ ] **Step 6: 테스트 실행 → 통과 확인**

```bash
./gradlew :core:test --tests "*.enums.ShippingStatusDispatchedTest" --tests "*.enums.PurchaseStatusTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/ShippingStatus.java \
        backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/PurchaseStatus.java \
        backend/core/src/test/java/com/sbshop/agent/core/domain/order/enums/ShippingStatusDispatchedTest.java \
        backend/core/src/test/java/com/sbshop/agent/core/domain/order/enums/PurchaseStatusTest.java
git commit -m "feat(domain): ShippingStatus.DISPATCHED 추가 + PurchaseStatus enum 신설"
```

---

## Task 2: OrderLineItem 엔티티 — purchaseStatus 필드 + 신규 메서드

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/OrderLineItem.java`

**Interfaces:**
- Consumes: `PurchaseStatus` (Task 1)
- Produces: `OrderLineItem.getPurchaseStatus()`, `markAsDispatched()`, `updatePurchaseStatus(PurchaseStatus)`

- [ ] **Step 1: 테스트 작성 (Red)**

기존 테스트 파일이 없으면 신규 생성: `backend/core/src/test/java/com/sbshop/agent/core/domain/order/OrderLineItemPurchaseStatusTest.java`

```java
package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import org.junit.jupiter.api.Test;

class OrderLineItemPurchaseStatusTest {

    private OrderLineItem newItem() {
        return OrderLineItem.builder()
            .orderId(1L)
            .quantity(1)
            .shippingData(ShippingData.builder().shippingStatus(ShippingStatus.PREPARING).build())
            .build();
    }

    @Test
    void 기본값은_NOT_PURCHASED() {
        OrderLineItem item = newItem();
        assertThat(item.getPurchaseStatus()).isEqualTo(PurchaseStatus.NOT_PURCHASED);
    }

    @Test
    void updatePurchaseStatus_로_PURCHASED로_변경() {
        OrderLineItem item = newItem();
        item.updatePurchaseStatus(PurchaseStatus.PURCHASED);
        assertThat(item.getPurchaseStatus()).isEqualTo(PurchaseStatus.PURCHASED);
        // shippingStatus는 변경되지 않아야 한다
        assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.PREPARING);
    }

    @Test
    void markAsDispatched_shippingStatus를_DISPATCHED로_변경() {
        OrderLineItem item = newItem();
        item.markAsDispatched();
        assertThat(item.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DISPATCHED);
        // purchaseStatus는 변경되지 않아야 한다
        assertThat(item.getPurchaseStatus()).isEqualTo(PurchaseStatus.NOT_PURCHASED);
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

```bash
./gradlew :core:test --tests "*.domain.order.OrderLineItemPurchaseStatusTest" 2>&1 | tail -20
```

Expected: 컴파일 에러 (purchaseStatus, markAsDispatched, updatePurchaseStatus 없음)

- [ ] **Step 3: OrderLineItem에 purchaseStatus 필드와 메서드 추가**

파일: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/OrderLineItem.java`

imports 섹션에 추가:
```java
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
```

기존 `isUnipassDone` 필드 아래에 추가:
```java
/** 내부 구매 처리 상태 (마켓 동기화와 무관) */
@Column(name = "purchase_status")
@Enumerated(EnumType.STRING)
private PurchaseStatus purchaseStatus = PurchaseStatus.NOT_PURCHASED;
```

기존 `@Builder` 생성자 파라미터에 추가:
```java
@Builder
public OrderLineItem(Long orderId, Long productId, Integer quantity, SourcingData sourcingData,
    SettlementData settlementData, ShippingData shippingData, Boolean isUnipassDone,
    PurchaseStatus purchaseStatus) {
    this.orderId = orderId;
    this.productId = productId;
    this.quantity = quantity;
    this.sourcingData = sourcingData != null ? sourcingData : SourcingData.builder().build();
    this.settlementData = settlementData != null ? settlementData : SettlementData.builder().build();
    this.shippingData = shippingData != null ? shippingData : ShippingData.builder().build();
    this.isUnipassDone = isUnipassDone;
    this.purchaseStatus = purchaseStatus != null ? purchaseStatus : PurchaseStatus.NOT_PURCHASED;
}
```

상태 변경 메서드 섹션에 추가 (기존 `markAsShipped()` 아래):
```java
/** 배송지시로 변경 (마켓에 송장 전송 성공 시) */
public void markAsDispatched() {
    this.shippingData = this.shippingData.toBuilder()
        .shippingStatus(ShippingStatus.DISPATCHED)
        .build();
}

/** 내부 구매 상태 변경 (마켓 동기화와 무관) */
public void updatePurchaseStatus(PurchaseStatus purchaseStatus) {
    this.purchaseStatus = purchaseStatus;
}
```

- [ ] **Step 4: 테스트 실행 → 통과 확인**

```bash
./gradlew :core:test --tests "*.domain.order.OrderLineItemPurchaseStatusTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/domain/order/OrderLineItem.java \
        backend/core/src/test/java/com/sbshop/agent/core/domain/order/OrderLineItemPurchaseStatusTest.java
git commit -m "feat(entity): OrderLineItem에 purchaseStatus 필드 + markAsDispatched/updatePurchaseStatus 추가"
```

---

## Task 3: 마켓 매퍼 수정 — Coupang·SmartStore

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/mapper/CoupangStatusMapper.java`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/mapper/SmartStoreStatusMapper.java`
- Modify: `backend/core/src/test/java/com/sbshop/agent/core/application/order/mapper/CoupangStatusMapperTest.java`
- Modify: `backend/core/src/test/java/com/sbshop/agent/core/application/order/mapper/SmartStoreStatusMapperTest.java`

**Interfaces:**
- Consumes: `ShippingStatus.DISPATCHED` (Task 1)

- [ ] **Step 1: CoupangStatusMapperTest 업데이트 (Red → 기존 통과 + 신규 실패)**

파일: `backend/core/src/test/java/com/sbshop/agent/core/application/order/mapper/CoupangStatusMapperTest.java`

기존 내용 전체를 아래로 교체:

```java
package com.sbshop.agent.core.application.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoupangStatusMapperTest {

    private final CoupangStatusMapper mapper = new CoupangStatusMapper();

    @Test
    @DisplayName("INSTRUCT(배송지시) → DISPATCHED 매핑")
    void instruct_mapsToDispatched() {
        ShippingStatus result = mapper.mapStatus(Map.of("status", "INSTRUCT"));
        assertThat(result).isEqualTo(ShippingStatus.DISPATCHED);
    }

    @Test
    @DisplayName("[D-030] NONE_TRACKING 상태는 SHIPPED로 매핑된다")
    void noneTracking_mapsToShipped() {
        ShippingStatus result = mapper.mapStatus(Map.of("status", "NONE_TRACKING"));
        assertThat(result).isEqualTo(ShippingStatus.SHIPPED);
    }

    @Test
    @DisplayName("[D-030] 회귀 방지: 기존 매핑(ACCEPT→NEW, DELIVERING→SHIPPED, 미인식→UNKNOWN)은 유지된다")
    void existingMappings_unchanged() {
        assertThat(mapper.mapStatus(Map.of("status", "ACCEPT"))).isEqualTo(ShippingStatus.NEW);
        assertThat(mapper.mapStatus(Map.of("status", "DELIVERING"))).isEqualTo(ShippingStatus.SHIPPED);
        assertThat(mapper.mapStatus(Map.of("status", "SOME_UNKNOWN_CODE"))).isEqualTo(ShippingStatus.UNKNOWN);
    }
}
```

- [ ] **Step 2: SmartStoreStatusMapperTest 업데이트 (Red)**

파일: `backend/core/src/test/java/com/sbshop/agent/core/application/order/mapper/SmartStoreStatusMapperTest.java`

기존 내용에 아래 테스트를 추가 (기존 테스트는 유지하고 `DISPATCHED → SHIPPED` 테스트가 있으면 `DISPATCHED → DISPATCHED`로 수정):

```java
package com.sbshop.agent.core.application.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SmartStoreStatusMapperTest {

    private final SmartStoreStatusMapper mapper = new SmartStoreStatusMapper();

    @Test
    @DisplayName("DISPATCHED(발송처리) → DISPATCHED 매핑 (배송지시 상태)")
    void dispatched_mapsToDispatched() {
        ShippingStatus result = mapper.mapStatus(Map.of("status", "DISPATCHED"));
        assertThat(result).isEqualTo(ShippingStatus.DISPATCHED);
    }

    @Test
    @DisplayName("DELIVERING → SHIPPED 유지")
    void delivering_mapsToShipped() {
        ShippingStatus result = mapper.mapStatus(Map.of("status", "DELIVERING"));
        assertThat(result).isEqualTo(ShippingStatus.SHIPPED);
    }

    @Test
    @DisplayName("PRODUCT_PREPARE → PREPARING 유지")
    void productPrepare_mapsToPreparing() {
        ShippingStatus result = mapper.mapStatus(Map.of("status", "PRODUCT_PREPARE"));
        assertThat(result).isEqualTo(ShippingStatus.PREPARING);
    }
}
```

- [ ] **Step 3: 테스트 실행 → Coupang INSTRUCT·SmartStore DISPATCHED 테스트 실패 확인**

```bash
./gradlew :core:test --tests "*.mapper.CoupangStatusMapperTest" --tests "*.mapper.SmartStoreStatusMapperTest" 2>&1 | tail -30
```

Expected: instruct_mapsToDispatched, dispatched_mapsToDispatched FAIL

- [ ] **Step 4: CoupangStatusMapper 수정**

파일: `backend/core/src/main/java/com/sbshop/agent/core/application/order/mapper/CoupangStatusMapper.java`

`mapBasicStatus` 메서드에서 INSTRUCT 라인 변경:

```java
case "INSTRUCT" -> ShippingStatus.DISPATCHED;
```

- [ ] **Step 5: SmartStoreStatusMapper 수정**

파일: `backend/core/src/main/java/com/sbshop/agent/core/application/order/mapper/SmartStoreStatusMapper.java`

switch 문에서 DISPATCHED 라인 변경:

```java
case "DISPATCHED" -> ShippingStatus.DISPATCHED;
case "DELIVERING" -> ShippingStatus.SHIPPED;
```

- [ ] **Step 6: 테스트 실행 → 통과 확인**

```bash
./gradlew :core:test --tests "*.mapper.CoupangStatusMapperTest" --tests "*.mapper.SmartStoreStatusMapperTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/order/mapper/CoupangStatusMapper.java \
        backend/core/src/main/java/com/sbshop/agent/core/application/order/mapper/SmartStoreStatusMapper.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/order/mapper/CoupangStatusMapperTest.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/order/mapper/SmartStoreStatusMapperTest.java
git commit -m "fix(mapper): Coupang INSTRUCT + SmartStore DISPATCHED → DISPATCHED 매핑 교정"
```

---

## Task 4: 서비스 로직 — PURCHASED → DISPATCHED/PurchaseStatus 전환

이 태스크는 `ShippingStatus.PURCHASED` 참조를 제거하되, enum에서는 아직 삭제하지 않는다(Task 5에서 정리).

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderService.java`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderShipProcessor.java`
- Modify: 테스트 파일 5개 (OrderServiceShippingGuardTest, OrderServiceStateGuardTest, OrderShipServiceGuardTest, OrderShipServiceResultTest, OrderServiceShippingRollbackTest, OrderServiceInputGuardTest)

**Interfaces:**
- Consumes: `ShippingStatus.DISPATCHED`, `PurchaseStatus`, `markAsDispatched()`, `updatePurchaseStatus()` (Tasks 1-2)

### 4-A: OrderServiceInputGuardTest 업데이트

- [ ] **Step 1: OrderServiceInputGuardTest 수정 (Red)**

파일: `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderServiceInputGuardTest.java`

기존 `PURCHASED→SHIPPED` 케이스를 `PREPARING→DISPATCHED`로 수정:

```java
// 기존: "F-H4: PURCHASED→SHIPPED 전이 시 trackingNo 없으면 차단"
// 변경 후:
@DisplayName("F-H4: PREPARING→DISPATCHED 전이 시 trackingNo 없으면 차단, 마켓 전송·저장 없음")
void preparing_to_dispatched_without_trackingNo_blocked() {
    OrderLineItem item = itemWithStatus(ShippingStatus.PREPARING);
    when(orderLineItemRepository.findById(1L)).thenReturn(Optional.of(item));
    ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
        .trackingNo(null)
        .shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
        .build();
    assertThatThrownBy(() -> service().updateShippingInfo(1L, cmd))
        .isInstanceOf(IllegalStateException.class);
    verify(marketplaceShippingService, never()).sendTrackingToMarketplace(any(), anyBoolean());
    verify(orderLineItemRepository, never()).save(any());
}

@DisplayName("F-H4: PREPARING→DISPATCHED 전이 시 trackingNo가 공백이면 차단")
void preparing_to_dispatched_with_blank_trackingNo_blocked() {
    OrderLineItem item = itemWithStatus(ShippingStatus.PREPARING);
    when(orderLineItemRepository.findById(1L)).thenReturn(Optional.of(item));
    ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
        .trackingNo("   ")
        .shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
        .build();
    assertThatThrownBy(() -> service().updateShippingInfo(1L, cmd))
        .isInstanceOf(IllegalStateException.class);
    verify(marketplaceShippingService, never()).sendTrackingToMarketplace(any(), anyBoolean());
    verify(orderLineItemRepository, never()).save(any());
}
```

### 4-B: OrderServiceShippingGuardTest 업데이트

- [ ] **Step 2: OrderServiceShippingGuardTest 수정**

파일: `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderServiceShippingGuardTest.java`

PURCHASED 상태를 사용하는 테스트를 DISPATCHED로 교체. PURCHASED가 허용되던 케이스는 이제 PREPARING 또는 DISPATCHED로 변경:

```java
// PURCHASED로 itemWithStatus(ShippingStatus.PURCHASED) 호출하는 테스트를
// itemWithStatus(ShippingStatus.DISPATCHED) 로 변경한다.
// 또한 NEW/UNKNOWN 차단 테스트에서 PREPARING 차단 케이스가 있다면 제거한다
// (PREPARING은 이제 허용됨).
```

PREPARING 차단 테스트가 있으면 다음처럼 교체:

```java
@Test
@DisplayName("PREPARING + trackingNo 있으면 → DISPATCHED 전이 성공 (차단 없음)")
void preparing_with_trackingNo_proceeds() {
    OrderLineItem item = itemWithStatus(ShippingStatus.PREPARING);
    when(orderLineItemRepository.findById(1L)).thenReturn(Optional.of(item));
    Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("ORD-001").build();
    when(orderRepository.findById(any())).thenReturn(Optional.of(order));
    when(credentialRepository.findByMarketType(any())).thenReturn(Optional.empty());
    when(marketplaceShippingService.sendTrackingToMarketplace(any(), anyBoolean()))
        .thenReturn(MarketShippingResult.ofSkipped("test"));
    ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
        .trackingNo("TRK123")
        .shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
        .build();
    assertThatCode(() -> service().updateShippingInfo(1L, cmd)).doesNotThrowAnyException();
}
```

### 4-C: OrderServiceStateGuardTest 업데이트

- [ ] **Step 3: OrderServiceStateGuardTest 수정**

파일: `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderServiceStateGuardTest.java`

"PREPARING 소싱수정(주문번호 있음) → PURCHASED 전이" 테스트를 수정:

```java
@Test
@DisplayName("PREPARING 소싱수정(주문번호 있음) → purchaseStatus=PURCHASED로 변경, shippingStatus 유지 (F-S3 특성 고정)")
void preparing_sourcing_update_with_orderNo_sets_purchaseStatus() {
    // given
    OrderLineItem item = itemWithStatus(ShippingStatus.PREPARING);
    when(orderLineItemRepository.findById(1L)).thenReturn(Optional.of(item));
    when(orderLineItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    SourcingUpdateCommand cmd = SourcingUpdateCommand.builder()
        .sourcingOrderNo("VENDOR-001")
        .sourcingVendor("테스트소싱처")
        .build();
    // when
    OrderLineItem result = service().updateSourcingInfo(1L, cmd);
    // then
    assertThat(result.getPurchaseStatus()).isEqualTo(PurchaseStatus.PURCHASED);
    assertThat(result.getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.PREPARING);
}
```

"PURCHASED 주문 취소 → 차단" 테스트는 DISPATCHED로 교체:

```java
@Test
@DisplayName("DISPATCHED 주문 취소 → 차단(IllegalStateException)")
void dispatched_cancel_blocked() {
    OrderLineItem item = itemWithStatus(ShippingStatus.DISPATCHED);
    when(orderLineItemRepository.findById(1L)).thenReturn(Optional.of(item));
    assertThatThrownBy(() -> service().cancelOrder(1L))
        .isInstanceOf(IllegalStateException.class);
}
```

### 4-D: OrderShipServiceGuardTest + ResultTest 업데이트

- [ ] **Step 4: OrderShipServiceGuardTest 수정**

파일: `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderShipServiceGuardTest.java`

```java
// "PURCHASED 라인아이템은 발송한다" → "PREPARING 라인아이템은 발송한다"로 변경
@DisplayName("PREPARING 라인아이템은 발송한다(port.shipOrder 호출)")
void preparing_lineItem_ships() {
    OrderLineItem item = itemWith(ShippingStatus.PREPARING);
    // ... (기존 테스트 로직 동일, ShippingStatus.PURCHASED → PREPARING으로만 변경)
}
```

DISPATCHED 스킵 테스트 추가:

```java
@DisplayName("DISPATCHED 라인아이템은 재발송 스킵한다")
void dispatched_lineItem_skipped() {
    OrderLineItem item = itemWith(ShippingStatus.DISPATCHED);
    // port.shipOrder 가 호출되지 않아야 함
    verify(port, never()).shipOrder(any(), any(), any(), any(), any());
}
```

- [ ] **Step 5: OrderShipServiceResultTest 수정**

파일: `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderShipServiceResultTest.java`

PURCHASED 대신 PREPARING 사용, 결과 상태는 DISPATCHED:

```java
// .shippingStatus(ShippingStatus.PURCHASED) → .shippingStatus(ShippingStatus.PREPARING)
// 발송 성공 후 저장된 상태가 DISPATCHED인지 검증:
ArgumentCaptor<OrderLineItem> captor = ArgumentCaptor.forClass(OrderLineItem.class);
verify(orderLineItemRepository).save(captor.capture());
assertThat(captor.getValue().getShippingData().getShippingStatus()).isEqualTo(ShippingStatus.DISPATCHED);
```

### 4-E: OrderServiceShippingRollbackTest 업데이트

- [ ] **Step 6: OrderServiceShippingRollbackTest 수정**

파일: `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderServiceShippingRollbackTest.java`

```java
// .shippingStatus(ShippingStatus.PURCHASED) → ShippingStatus.PREPARING으로 교체
// "PURCHASED + 기존 송장 없음 → false" → "PREPARING + 기존 송장 없음 → false"로 테스트명 수정
```

### 4-F: 테스트 실행 → 실패 확인

- [ ] **Step 7: 수정된 테스트 실행 — 현재 구현이 새 테스트를 통과 못함 확인**

```bash
./gradlew :core:test --tests "*.service.OrderServiceInputGuardTest" \
    --tests "*.service.OrderServiceShippingGuardTest" \
    --tests "*.service.OrderServiceStateGuardTest" \
    --tests "*.service.OrderShipServiceGuardTest" \
    --tests "*.service.OrderShipServiceResultTest" \
    --tests "*.service.OrderServiceShippingRollbackTest" 2>&1 | tail -40
```

Expected: 여러 테스트 실패 (PURCHASED→DISPATCHED 로직 미구현)

### 4-G: OrderService 비즈니스 로직 수정

- [ ] **Step 8: OrderService — updateShippingInfo 수정**

파일: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderService.java`

`updateShippingInfo` 메서드 내 가드 및 전이 로직 수정:

```java
// 기존 가드 (PREPARING 차단) 교체:
// NEW/UNKNOWN 상태이면 수정 차단 (PREPARING은 이제 허용 — 첫 송장 등록 진입점)
if (currentStatus == null || currentStatus == ShippingStatus.NEW || currentStatus == ShippingStatus.UNKNOWN) {
    throw new IllegalStateException("발주확인 전에는 배송 정보를 수정할 수 없습니다.");
}

// 기존 isShipTransition → isDispatchTransition 으로 교체:
// PREPARING이면 최초 배송처리 → DISPATCHED, DISPATCHED/SHIPPED 이후는 송장만 수정
boolean isDispatchTransition = currentStatus == ShippingStatus.PREPARING;

// PREPARING→DISPATCHED 최초 전이에는 송장번호 필수
if (isDispatchTransition && (command.getTrackingNo() == null || command.getTrackingNo().isBlank())) {
    throw new IllegalStateException("배송 처리 시 송장번호는 필수입니다.");
}

// 공통: 송장데이터 반영 → (PREPARING → DISPATCHED 전이 시에만 마킹) → 저장
item.applyShippingData(command.toShippingData(item.getShippingData()));
if (isDispatchTransition) {
    item.markAsDispatched();
}
orderLineItemRepository.save(item);
```

로그 메시지도 수정:

```java
if (isDispatchTransition) {
    log.info("라인아이템 {} 배송지시 처리: tracking={}, carrier={}", lineItemId, command.getTrackingNo(),
        command.getShippingCarrier());
} else {
    log.info("라인아이템 {} 송장번호 업데이트: tracking={}, carrier={}", lineItemId,
        command.getTrackingNo(), command.getShippingCarrier());
}
```

- [ ] **Step 9: OrderService — processShipping 수정**

파일: `OrderService.java` (line 464 근처, `processShipping` 메서드)

```java
/** 배송 처리 (PREPARING → DISPATCHED) */
@Transactional
public void processShipping(Long lineItemId, String trackingNo, ShippingCarrier carrier) {
    OrderLineItem item = orderLineItemRepository.findById(lineItemId)
        .orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));

    ShippingStatus currentStatus = item.getShippingData() != null
        ? item.getShippingData().getShippingStatus() : null;
    if (currentStatus != ShippingStatus.PREPARING && currentStatus != ShippingStatus.DISPATCHED) {
        throw new IllegalStateException("배송 처리는 PREPARING 또는 DISPATCHED 상태에서만 가능합니다. 현재: " + currentStatus);
    }

    boolean invoiceAlreadyExists = item.getShippingData() != null
        && item.getShippingData().getTrackingNo() != null
        && !item.getShippingData().getTrackingNo().isBlank();

    ShippingData currentShipping = item.getShippingData() != null
        ? item.getShippingData() : ShippingData.builder().build();
    item.applyShippingData(currentShipping.toBuilder()
        .trackingNo(trackingNo)
        .shippingCarrier(carrier)
        .shippingStatus(ShippingStatus.DISPATCHED)
        .build());
    orderLineItemRepository.save(item);

    MarketShippingResult sendResult = marketplaceShippingService.sendTrackingToMarketplace(item, invoiceAlreadyExists);
    markSentIfSucceeded(item, sendResult, lineItemId);

    log.info("라인아이템 {} 배송지시 처리: tracking={}, carrier={}", lineItemId, trackingNo, carrier);
}
```

- [ ] **Step 10: OrderService — updateTrackingInfo 수정**

`updateTrackingInfo` 메서드의 가드를 DISPATCHED도 허용하도록 수정:

```java
// 기존: SHIPPED만 허용
// 변경: DISPATCHED 또는 SHIPPED 허용
if (currentStatus != ShippingStatus.DISPATCHED && currentStatus != ShippingStatus.SHIPPED) {
    throw new IllegalStateException("송장 수정은 DISPATCHED 또는 SHIPPED 상태에서만 가능합니다. 현재: " + currentStatus);
}
```

- [ ] **Step 11: OrderService — updateSourcingInfo 수정**

`updateSourcingInfo` 메서드 (line 280 근처):

```java
// 기존: isPurchaseTransition(PREPARING) → markAsPurchased() (shippingStatus 변경)
// 변경: sourcingOrderNo가 있으면 purchaseStatus를 PURCHASED로 업데이트

// PREPARING이면 구매 처리 (purchaseStatus 업데이트, shippingStatus 변경 없음)
boolean hasSourcingOrderNo = command.getSourcingOrderNo() != null
    && !command.getSourcingOrderNo().isEmpty();
if (hasSourcingOrderNo
    && (command.getSourcingOrderNo() == null || command.getSourcingOrderNo().isEmpty())) {
    throw new IllegalStateException("구매정보 수정 시 주문번호는 필수입니다.");
}

// 공통: 소싱데이터 반영 → (주문번호 있고 미구매 상태이면 purchaseStatus 업데이트) → 저장
boolean hasSourcingOrderNo = command.getSourcingOrderNo() != null
    && !command.getSourcingOrderNo().isEmpty();

item.applySourcingData(command.toSourcingData(item.getSourcingData()));
if (hasSourcingOrderNo && item.getPurchaseStatus() == PurchaseStatus.NOT_PURCHASED) {
    item.updatePurchaseStatus(PurchaseStatus.PURCHASED);
    log.info("라인아이템 {} 구매완료로 변경 (vendor: {}, orderNo: {})",
        lineItemId, command.getSourcingVendor(), command.getSourcingOrderNo());
} else {
    log.info("라인아이템 {} 구매 정보 수정 완료", lineItemId);
}
orderLineItemRepository.save(item);
```

- [ ] **Step 12: OrderService — markAsPurchased 메서드들 수정**

`markAsPurchased(Long lineItemId, String sourcingAccount, ...)` 메서드 (line 379):

```java
// 기존: item.markAsPurchased() → shippingStatus = PURCHASED
// 변경: item.updatePurchaseStatus(PurchaseStatus.PURCHASED)
item.applySourcingData(SourcingData.builder()
    .sourcingAccount(sourcingAccount)
    .sourcingOrderNo(sourcingOrderNo)
    .build());
item.updatePurchaseStatus(PurchaseStatus.PURCHASED);
orderLineItemRepository.save(item);
log.info("라인아이템 {} 구매완료로 변경 (account: {}, orderNo: {})",
    lineItemId, sourcingAccount, sourcingOrderNo);
```

`markAsPurchasedWithAmount` 도 동일하게 `item.updatePurchaseStatus(PurchaseStatus.PURCHASED)`로 변경.

- [ ] **Step 13: OrderShipProcessor 수정**

파일: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderShipProcessor.java`

Line 78 근처 스킵 조건에 DISPATCHED 추가:

```java
// 기존
if (status == ShippingStatus.SHIPPED || status == ShippingStatus.DELIVERED
    || status == ShippingStatus.CANCELED || status == ShippingStatus.RETURNED
    || status == ShippingStatus.EXCHANGED) {
    continue;
}

// 변경 후 (DISPATCHED 추가 — 이미 배송지시 처리된 주문은 재발송하지 않는다)
if (status == ShippingStatus.DISPATCHED || status == ShippingStatus.SHIPPED
    || status == ShippingStatus.DELIVERED || status == ShippingStatus.CANCELED
    || status == ShippingStatus.RETURNED || status == ShippingStatus.EXCHANGED) {
    log.info("라인아이템 {} 스킵 — 이미 {} 상태(재발송 대상 아님)", item.getId(), status);
    continue;
}
```

Line 95 근처 발송 성공 후 상태 변경:

```java
// 기존: shippingStatus(SHIPPED)
// 변경: shippingStatus(ShippingStatus.DISPATCHED)
ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
    .trackingNo(trackingNo)
    .shippingStatus(ShippingStatus.DISPATCHED)
    .build();
```

- [ ] **Step 14: 테스트 실행 → 통과 확인**

```bash
./gradlew :core:test --tests "*.service.OrderServiceInputGuardTest" \
    --tests "*.service.OrderServiceShippingGuardTest" \
    --tests "*.service.OrderServiceStateGuardTest" \
    --tests "*.service.OrderShipServiceGuardTest" \
    --tests "*.service.OrderShipServiceResultTest" \
    --tests "*.service.OrderServiceShippingRollbackTest" 2>&1 | tail -40
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 15: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/order/service/ \
        backend/core/src/test/java/com/sbshop/agent/core/application/order/service/
git commit -m "refactor(service): PURCHASED → DISPATCHED/PurchaseStatus 전환 — OrderService + OrderShipProcessor"
```

---

## Task 5: PURCHASED 제거 + OrderLineItem.markAsPurchased() 정리

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/ShippingStatus.java`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/OrderLineItem.java`

- [ ] **Step 1: ShippingStatus에서 PURCHASED 제거**

파일: `ShippingStatus.java`

`PURCHASED("구매완료", 2),` 라인 삭제. 결과:

```java
public enum ShippingStatus implements EnumMapperType {
    UNKNOWN("알수없음", -2),
    NEW("결제완료", 0),
    PREPARING("구매준비", 1),
    DISPATCHED("배송지시", 2),
    SHIPPED("배송중", 3),
    DELIVERED("배송완료", 4),
    CANCELED("취소됨", -1),
    RETURNED("반품됨", -1),
    EXCHANGED("교환됨", -1);
    // ...
}
```

- [ ] **Step 2: OrderLineItem.markAsPurchased() 제거**

파일: `OrderLineItem.java`

아래 메서드 전체 삭제:
```java
/** 구매 완료로 변경 */
public void markAsPurchased() {
    this.shippingData = this.shippingData.toBuilder()
        .shippingStatus(ShippingStatus.PURCHASED)
        .build();
}
```

- [ ] **Step 3: 전체 컴파일 확인**

```bash
./gradlew :backend:core:compileJava :backend:api:compileJava :backend:worker:compileJava 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. 컴파일 에러 시 남은 `ShippingStatus.PURCHASED` 참조를 찾아서 수정:

```bash
grep -rn "ShippingStatus.PURCHASED\|markAsPurchased" /Users/jasonair/Projects/sbshop-agent/backend/src --include="*.java"
# (남은 참조 발견 시 해당 파일을 수정한 뒤 재컴파일)
```

- [ ] **Step 4: 전체 테스트 실행**

```bash
./gradlew :core:test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/ShippingStatus.java \
        backend/core/src/main/java/com/sbshop/agent/core/domain/order/OrderLineItem.java
git commit -m "refactor(domain): ShippingStatus.PURCHASED 제거 + OrderLineItem.markAsPurchased 삭제"
```

---

## Task 6: API 레이어 — purchase-status 엔드포인트 + 응답 DTO

**Files:**
- Create: `backend/api/src/main/java/com/sbshop/agent/api/dto/UpdatePurchaseStatusRequest.java`
- Modify: `backend/api/src/main/java/com/sbshop/agent/api/dto/OrderLineItemResponse.java`
- Modify: `backend/api/src/main/java/com/sbshop/agent/api/controller/OrderController.java`

**Interfaces:**
- Consumes: `PurchaseStatus`, `OrderLineItem.updatePurchaseStatus()`, `OrderLineItem.getPurchaseStatus()` (Tasks 1-2)
- Produces: `PATCH /api/v1/orders/line-items/{lineItemId}/purchase-status`, `OrderLineItemResponse.purchaseStatus`

- [ ] **Step 1: UpdatePurchaseStatusRequest DTO 생성**

파일: `backend/api/src/main/java/com/sbshop/agent/api/dto/UpdatePurchaseStatusRequest.java`

```java
package com.sbshop.agent.api.dto;

import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdatePurchaseStatusRequest {
    private PurchaseStatus purchaseStatus;
}
```

- [ ] **Step 2: OrderLineItemResponse에 purchaseStatus 추가**

파일: `backend/api/src/main/java/com/sbshop/agent/api/dto/OrderLineItemResponse.java`

필드 추가:

```java
private final PurchaseStatus purchaseStatus;
```

`from()` 메서드에 추가:

```java
.purchaseStatus(item.getPurchaseStatus())
```

imports:

```java
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
```

- [ ] **Step 3: OrderController에 PATCH 엔드포인트 추가**

**먼저 OrderService에 updatePurchaseStatus 메서드 추가** (`backend/core/src/main/java/.../service/OrderService.java`):

```java
/** 라인아이템 구매 상태 변경 */
@Transactional
public OrderLineItem updatePurchaseStatus(Long lineItemId, PurchaseStatus purchaseStatus) {
    OrderLineItem item = orderLineItemRepository.findById(lineItemId)
        .orElseThrow(() -> new IllegalArgumentException("LineItem not found: " + lineItemId));
    item.updatePurchaseStatus(purchaseStatus);
    orderLineItemRepository.save(item);
    log.info("라인아이템 {} 구매상태 변경: {}", lineItemId, purchaseStatus);
    return item;
}
```

**그 다음 OrderController에 엔드포인트 추가** (`backend/api/src/main/java/.../controller/OrderController.java`):

기존 `updateShippingInfo` 엔드포인트 아래에 추가:

```java
/** 라인아이템 구매 상태 수정 */
@PatchMapping("/line-items/{lineItemId}/purchase-status")
public ResponseEntity<OrderLineItemResponse> updatePurchaseStatus(
    @PathVariable Long lineItemId,
    @RequestBody UpdatePurchaseStatusRequest request) {

    OrderLineItem updated = orderService.updatePurchaseStatus(lineItemId, request.getPurchaseStatus());
    return ResponseEntity.ok(OrderLineItemResponse.from(updated));
}
```

- [ ] **Step 4: 전체 컴파일 확인**

```bash
./gradlew :backend:api:compileJava 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add backend/api/src/main/java/com/sbshop/agent/api/dto/UpdatePurchaseStatusRequest.java \
        backend/api/src/main/java/com/sbshop/agent/api/dto/OrderLineItemResponse.java \
        backend/api/src/main/java/com/sbshop/agent/api/controller/OrderController.java
git commit -m "feat(api): PATCH /line-items/{id}/purchase-status 엔드포인트 추가 + 응답 DTO purchaseStatus 포함"
```

---

## Task 7: DB 수동 마이그레이션

운영 DB에서 직접 실행. 배포 전에 반드시 완료해야 한다.

- [ ] **Step 1: purchase_status 컬럼 추가**

```bash
docker exec projects-postgres-1 psql -U canagent -d sbshop -c "
ALTER TABLE sb_order_line_item
  ADD COLUMN IF NOT EXISTS purchase_status VARCHAR(50) NOT NULL DEFAULT 'NOT_PURCHASED';
"
```

- [ ] **Step 2: 기존 PURCHASED 레코드 이전**

```bash
docker exec projects-postgres-1 psql -U canagent -d sbshop -c "
SELECT COUNT(*) FROM sb_order_line_item WHERE shipping_data_shipping_status = 'PURCHASED';
"
```

레코드가 있으면 실제 상태를 검토 후 수동 결정. 기본 이전 쿼리:

```bash
docker exec projects-postgres-1 psql -U canagent -d sbshop -c "
UPDATE sb_order_line_item
SET purchase_status = 'PURCHASED',
    shipping_data_shipping_status = 'PREPARING'
WHERE shipping_data_shipping_status = 'PURCHASED';
"
```

※ `shipping_data_shipping_status` 컬럼명이 다르면 실제 컬럼명으로 교체:

```bash
docker exec projects-postgres-1 psql -U canagent -d sbshop -c "\d sb_order_line_item" | grep -i ship
```

- [ ] **Step 3: 컬럼 존재 및 기본값 확인**

```bash
docker exec projects-postgres-1 psql -U canagent -d sbshop -c "
SELECT purchase_status, COUNT(*) FROM sb_order_line_item GROUP BY purchase_status;
"
```

Expected: `NOT_PURCHASED | N` (기존 행들)

---

## Task 8: 프론트엔드 — DISPATCHED 레이블 + PurchaseStatus 셀렉트박스

**Files:**
- Modify: `frontend/src/api/orderApi.ts`
- Modify: `frontend/src/pages/OrderGrid.tsx`

**Interfaces:**
- Consumes: `PATCH /api/v1/orders/line-items/{id}/purchase-status` (Task 6)

- [ ] **Step 1: orderApi.ts 수정**

파일: `frontend/src/api/orderApi.ts`

`OrderLineItemDto`에 `purchaseStatus` 추가:

```typescript
export interface OrderLineItemDto {
  id?: number;
  quantity?: number;
  unitPrice?: number;
  isUnipassDone?: boolean;
  purchaseStatus?: 'NOT_PURCHASED' | 'PURCHASED' | 'WAITING_STOCK';  // 신규
  sourcingData?: { ... };  // 기존 유지
  settlementData?: { ... };  // 기존 유지
  shippingData?: {
    trackingNo?: string;
    shippingStatus?: string;
    shippingCarrier?: string;
  };
}
```

`updatePurchaseStatus` API 함수 추가 (기존 함수들 뒤에):

```typescript
export const updatePurchaseStatus = async (
  lineItemId: number,
  purchaseStatus: 'NOT_PURCHASED' | 'PURCHASED' | 'WAITING_STOCK'
): Promise<OrderLineItemDto> => {
  const { data } = await apiClient.patch(
    `/api/v1/orders/line-items/${lineItemId}/purchase-status`,
    { purchaseStatus }
  );
  return data;
};
```

- [ ] **Step 2: OrderGrid.tsx — DISPATCHED 상태 추가**

파일: `frontend/src/pages/OrderGrid.tsx`

Line 244 근처 `allStatuses` 배열에서 `PURCHASED` 제거, `DISPATCHED` 추가:

```typescript
const allStatuses = ['UNKNOWN', 'NEW', 'PREPARING', 'DISPATCHED', 'SHIPPED', 'DELIVERED', 'CANCELED', 'RETURNED', 'EXCHANGED'];
```

Line 339 근처 상태 필터 옵션에서 PURCHASED 제거, DISPATCHED 추가:

```typescript
{ id: 'PREPARING', label: '구매준비' },
{ id: 'DISPATCHED', label: '배송지시' },   // 신규
{ id: 'SHIPPED', label: '배송중' },
{ id: 'DELIVERED', label: '배송완료' },
// PURCHASED 제거
```

Line 839 근처 `colorMap`에 DISPATCHED 추가, PURCHASED 제거:

```typescript
const colorMap: Record<string, { bg: string; text: string }> = {
  'UNKNOWN':   { bg: '#f5f5f5', text: '#666' },
  'NEW':       { bg: '#e0f7fa', text: '#006064' },
  'PREPARING': { bg: '#fff3e0', text: '#e65100' },
  'DISPATCHED':{ bg: '#fce4ec', text: '#880e4f' },  // 신규: 핑크계열 (배송지시)
  'SHIPPED':   { bg: '#f1f8e9', text: '#558b2f' },
  'DELIVERED': { bg: '#e1f5fe', text: '#0277bd' },
  'CANCELED':  { bg: '#ffebee', text: '#c62828' },
  'RETURNED':  { bg: '#f3e5f5', text: '#6a1b9a' },
  'EXCHANGED': { bg: '#e8eaf6', text: '#283593' },
  // PURCHASED 제거
};
```

- [ ] **Step 3: OrderGrid.tsx — PurchaseStatus 셀렉트박스 추가**

`shippingStatus` 컬럼 셀 정의 아래에 `purchaseStatus` 컬럼 추가:

```typescript
columnHelper.accessor('lineItem.purchaseStatus', {
  id: 'purchaseStatus',
  header: '구매상태',
  size: 100,
  cell: info => {
    const row = info.row.original;
    const lineItemId = row.lineItem?.id;
    const currentVal = (info.getValue() as string) || 'NOT_PURCHASED';

    const PURCHASE_OPTIONS = [
      { value: 'NOT_PURCHASED', label: '미구매' },
      { value: 'PURCHASED',     label: '구매완료' },
      { value: 'WAITING_STOCK', label: '입고대기' },
    ] as const;

    const handleChange = async (e: React.ChangeEvent<HTMLSelectElement>) => {
      if (!lineItemId) return;
      const newVal = e.target.value as 'NOT_PURCHASED' | 'PURCHASED' | 'WAITING_STOCK';
      try {
        await updatePurchaseStatus(lineItemId, newVal);
      } catch (err) {
        console.error('구매 상태 변경 실패', err);
      }
    };

    return (
      <select value={currentVal} onChange={handleChange}
        style={{ fontSize: '12px', padding: '2px 4px', borderRadius: '4px' }}>
        {PURCHASE_OPTIONS.map(opt => (
          <option key={opt.value} value={opt.value}>{opt.label}</option>
        ))}
      </select>
    );
  }
}),
```

imports에 `updatePurchaseStatus` 추가:

```typescript
import { ..., updatePurchaseStatus } from '../api/orderApi';
```

- [ ] **Step 4: 타입 검사**

```bash
cd /Users/jasonair/Projects/sbshop-agent/frontend
npx tsc -p tsconfig.app.json --noEmit 2>&1 | tail -20
```

Expected: 에러 없음

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/api/orderApi.ts frontend/src/pages/OrderGrid.tsx
git commit -m "feat(frontend): DISPATCHED 상태 표시 + PurchaseStatus 셀렉트박스 추가"
```

---

## 완료 후 검증

- [ ] 백엔드 전체 테스트: `./gradlew :core:test :api:test 2>&1 | tail -20` → BUILD SUCCESSFUL
- [ ] 프론트 타입 검사: `cd frontend && npx tsc -p tsconfig.app.json --noEmit` → 에러 없음
- [ ] DB 확인: `purchase_status` 컬럼 존재, 기존 PURCHASED 레코드 이전 완료
- [ ] 운영 배포 후 쿠팡 주문 `18101568712956` 상태가 `DISPATCHED(배송지시)`로 표시되는지 확인
