# 묶음배송 주문 모델 1단계(공통 기반) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 주문–배송–상품주문 3계층을 담을 도메인·DTO·upsert 기반을 만든다. 마켓 어댑터는 건드리지 않으므로 **런타임 동작은 바뀌지 않는다**.

**Architecture:** `Shipment` 엔티티를 신설하고 `OrderLineItem`에 마켓 상품주문번호·배송 FK를 더한다. 평면 `MarketOrderDto`를 3계층으로 감싸는 정규화기와, 마켓 식별자로 매칭하는 upsert 서비스를 만든다. 이 코드는 1단계에서 **어디에도 배선하지 않는다** — 2단계(11번가)가 첫 소비자다.

**Tech Stack:** Java 21 · Spring Boot 3.5 · JPA/Hibernate · PostgreSQL · JUnit 5 · AssertJ · Mockito · Gradle 멀티모듈(core/infrastructure/api/worker)

**설계 문서:** `docs/superpowers/specs/2026-08-05-bundle-shipment-order-model-design.md`

## Global Constraints

- 들여쓰기는 **탭**. 주변 코드 스타일을 그대로 따른다.
- 주석·`@DisplayName`은 **한국어**. 왜 그렇게 했는지를 적고, 무엇을 하는지는 코드로 말한다.
- 모든 작업 디렉터리는 `/Users/jasonair/Projects/sbshop-agent`. Gradle 명령은 `backend/`에서 실행한다.
- 스키마는 **수동 관리**(Flyway 없음). 운영 반영은 Task 6의 DDL로만 한다. `ddl-auto=update`가 컬럼·테이블은 만들지만 **UNIQUE 제약은 만들지 않으므로** 수동 DDL이 필요하다.
- 1단계 산출물은 **기존 코드에서 호출되지 않아야 한다**. 어댑터·동기화 서비스·컨트롤러를 수정하지 않는다.
- 회귀 게이트: `cd backend && ./gradlew test` 전체 통과. 1단계는 동작 불변이므로 **기존 테스트가 단 한 건도 바뀌면 안 된다**.
- 테스트 없이 구현 코드를 먼저 쓰지 않는다. Red → Green → Commit.

---

### Task 1: Shipment 엔티티와 리포지토리

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/Shipment.java`
- Create: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/repository/ShipmentRepository.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/domain/order/ShipmentEntityTest.java`

**Interfaces:**
- Consumes: `BaseEntity`(id·status·createdAt·updatedAt 상속), `ShippingCarrier` enum
- Produces:
  - `Shipment.builder().orderId(Long).marketShipmentNo(String).trackingNo(String).shippingCarrier(ShippingCarrier).deliveryStatus(String).trackingSentToMarket(Boolean).shippedAt(LocalDateTime).build()`
  - `shipment.applyTracking(String trackingNo, ShippingCarrier carrier, Boolean sentToMarket)` — null 인자는 기존 값 유지
  - `shipment.applyDeliveryStatus(String)` · `shipment.applyShippedAt(LocalDateTime)`
  - `ShipmentRepository.findByOrderIdAndMarketShipmentNo(Long orderId, String marketShipmentNo) : Optional<Shipment>`
  - `ShipmentRepository.findByOrderId(Long orderId) : List<Shipment>`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`backend/core/src/test/java/com/sbshop/agent/core/domain/order/ShipmentEntityTest.java`:

```java
package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * 배송(Shipment)은 "물리적으로 함께 나가는 단위"다. 송장 1개 = Shipment 1개.
 *
 * <p>마켓별 배송 식별자(11번가 dlvNo · 쿠팡 shipmentBoxId · N스토어 packageNumber ·
 * Cafe24 shipping_code)를 {@code marketShipmentNo} 한 컬럼에 담는다. 넷 다 역할이 같기 때문이다.
 *
 * <p>{@code (order_id, market_shipment_no)} 유니크는 동기화 매칭의 하드가드다. 이게 없으면
 * 같은 배송이 중복 생성돼, 송장을 마켓에 두 번 보내는 사고로 이어진다
 * (11번가 -3308 "해당 배송번호는 이미 발송처리 되었습니다").
 */
@DataJpaTest
@ContextConfiguration(classes = ShipmentEntityTest.TestApp.class)
class ShipmentEntityTest {

	@SpringBootApplication
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.dummy")
	static class TestApp {}

	@Autowired
	private EntityManager em;

	@Test
	@DisplayName("배송을 저장하고 다시 읽으면 송장·택배사·발송일이 보존된다")
	void persistsAndReadsBack() {
		Shipment shipment = Shipment.builder()
			.orderId(100L)
			.marketShipmentNo("2716448228")
			.trackingNo("424079080471")
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.deliveryStatus("DELIVERING")
			.trackingSentToMarket(true)
			.shippedAt(LocalDateTime.of(2026, 8, 5, 12, 0))
			.build();

		em.persist(shipment);
		em.flush();
		em.clear();

		Shipment found = em.find(Shipment.class, shipment.getId());
		assertThat(found.getOrderId()).isEqualTo(100L);
		assertThat(found.getMarketShipmentNo()).isEqualTo("2716448228");
		assertThat(found.getTrackingNo()).isEqualTo("424079080471");
		assertThat(found.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(found.getDeliveryStatus()).isEqualTo("DELIVERING");
		assertThat(found.getTrackingSentToMarket()).isTrue();
		assertThat(found.getShippedAt()).isEqualTo(LocalDateTime.of(2026, 8, 5, 12, 0));
		assertThat(found.getStatus().name()).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("같은 주문에 같은 배송식별자로 두 건을 저장하면 유니크 제약 위반이 발생한다")
	void rejectsDuplicateShipmentNoWithinOrder() {
		em.persist(Shipment.builder().orderId(100L).marketShipmentNo("2716448228").build());
		em.persist(Shipment.builder().orderId(100L).marketShipmentNo("2716448228").build());

		assertThatThrownBy(() -> em.flush())
			.hasRootCauseInstanceOf(ConstraintViolationException.class);
	}

	@Test
	@DisplayName("주문이 다르면 같은 배송식별자를 써도 된다")
	void allowsSameShipmentNoAcrossOrders() {
		em.persist(Shipment.builder().orderId(100L).marketShipmentNo("SAME").build());
		em.persist(Shipment.builder().orderId(200L).marketShipmentNo("SAME").build());

		em.flush();
	}

	@Test
	@DisplayName("applyTracking의 null 인자는 기존 값을 지우지 않는다")
	void applyTrackingKeepsExistingOnNull() {
		// 마켓이 이번 응답에서 송장을 주지 않았다고 해서 이미 가진 송장을 지우면 안 된다
		// (D-119/D-120에서 자리표시자·빈 값이 실송장을 덮어써 배송정보가 유실된 이력).
		Shipment shipment = Shipment.builder()
			.orderId(100L)
			.marketShipmentNo("D1")
			.trackingNo("424079080471")
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.trackingSentToMarket(true)
			.build();

		shipment.applyTracking(null, null, null);

		assertThat(shipment.getTrackingNo()).isEqualTo("424079080471");
		assertThat(shipment.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(shipment.getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("applyTracking에 실값을 주면 갱신된다")
	void applyTrackingUpdatesOnRealValue() {
		Shipment shipment = Shipment.builder().orderId(100L).marketShipmentNo("D1").build();

		shipment.applyTracking("6079990333504", ShippingCarrier.KOREA_POST, true);

		assertThat(shipment.getTrackingNo()).isEqualTo("6079990333504");
		assertThat(shipment.getShippingCarrier()).isEqualTo(ShippingCarrier.KOREA_POST);
		assertThat(shipment.getTrackingSentToMarket()).isTrue();
	}
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
cd backend && ./gradlew :core:test --tests '*ShipmentEntityTest*'
```

Expected: 컴파일 실패 — `cannot find symbol: class Shipment`

- [ ] **Step 3: Shipment 엔티티를 만든다**

`backend/core/src/main/java/com/sbshop/agent/core/domain/order/Shipment.java`:

```java
package com.sbshop.agent.core.domain.order;

import java.sql.Types;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 하나의 배송 — 물리적으로 함께 나가는 단위. 송장 1개가 곧 Shipment 1개다.
 *
 * <p>네 마켓이 모두 주문과 상품 사이에 배송 계층을 갖는다(2026-08-05 API 문서 확인):
 * 11번가 {@code dlvNo}(+{@code bndlDlvSeq}) · 쿠팡 {@code shipmentBoxId} ·
 * N스토어 {@code packageNumber} · Cafe24 {@code shipments} 리소스. 역할이 같으므로
 * {@code marketShipmentNo} 한 컬럼으로 흡수한다.
 *
 * <p>배송상태({@code deliveryStatus})는 <b>배송 자체의 상태</b>(집화·배송중·배송완료)다.
 * 주문상품의 진행상태는 라인아이템에 남는다 — 같은 주문에서도 상품마다 갈리기 때문이다
 * (11번가 20260731088778989: 순번 1 결제완료 / 순번 2 발송완료).
 * 마켓이 배송상태를 주지 않으면 비운다. 마켓마다 코드계가 달라 enum으로 묶지 않았다.
 */
@Entity
@Table(name = "sb_shipment",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_shipment_order_market_no",
		columnNames = {"order_id", "market_shipment_no"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment extends BaseEntity {

	/** 주문 ID (sb_order 참조값) */
	@Column(name = "order_id", nullable = false)
	private Long orderId;

	/** 마켓 배송 식별자 (dlvNo / shipmentBoxId / packageNumber / shipping_code) */
	@Column(name = "market_shipment_no", length = 100, nullable = false)
	private String marketShipmentNo;

	@Column(name = "tracking_no", length = 100)
	private String trackingNo;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "shipping_carrier", length = 30)
	private ShippingCarrier shippingCarrier;

	/** 마켓이 주는 배송 자체의 상태. 코드계가 마켓마다 달라 원문 문자열로 보관한다. */
	@Column(name = "delivery_status", length = 30)
	private String deliveryStatus;

	/** 마켓이 이 송장을 갖고 있는가 (D-129 — 우리가 보내 성공했거나, 마켓이 알려줬거나) */
	@Column(name = "tracking_sent_to_market")
	private Boolean trackingSentToMarket;

	/** 발송처리일 */
	@Column(name = "shipped_at")
	private LocalDateTime shippedAt;

	@Builder
	public Shipment(Long orderId, String marketShipmentNo, String trackingNo,
		ShippingCarrier shippingCarrier, String deliveryStatus,
		Boolean trackingSentToMarket, LocalDateTime shippedAt) {
		this.orderId = orderId;
		this.marketShipmentNo = marketShipmentNo;
		this.trackingNo = trackingNo;
		this.shippingCarrier = shippingCarrier;
		this.deliveryStatus = deliveryStatus;
		this.trackingSentToMarket = trackingSentToMarket;
		this.shippedAt = shippedAt;
	}

	/**
	 * 송장 정보를 갱신한다. <b>null 인자는 "판단 없음"이라 기존 값을 유지한다.</b>
	 *
	 * <p>마켓이 이번 응답에서 송장을 주지 않았다는 것과 "송장이 없다"는 것은 다르다.
	 * 빈 값·자리표시자가 실송장을 덮어써 배송정보가 유실된 이력이 있다(D-119/D-120).
	 * 실값 여부 판정은 호출자가 {@code ShippingData.isMeaningfulTracking}으로 하고,
	 * 이 메서드는 넘어온 값만 반영한다.
	 */
	public void applyTracking(String trackingNo, ShippingCarrier carrier, Boolean sentToMarket) {
		if (trackingNo != null) {
			this.trackingNo = trackingNo;
		}
		if (carrier != null) {
			this.shippingCarrier = carrier;
		}
		if (sentToMarket != null) {
			this.trackingSentToMarket = sentToMarket;
		}
	}

	public void applyDeliveryStatus(String deliveryStatus) {
		if (deliveryStatus != null) {
			this.deliveryStatus = deliveryStatus;
		}
	}

	public void applyShippedAt(LocalDateTime shippedAt) {
		if (shippedAt != null) {
			this.shippedAt = shippedAt;
		}
	}
}
```

- [ ] **Step 4: 리포지토리를 만든다**

`backend/core/src/main/java/com/sbshop/agent/core/domain/order/repository/ShipmentRepository.java`:

```java
package com.sbshop.agent.core.domain.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.sbshop.agent.core.domain.order.Shipment;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

	/** 동기화 매칭 키 — 배열 순서가 아니라 마켓 식별자로 찾는다. */
	Optional<Shipment> findByOrderIdAndMarketShipmentNo(Long orderId, String marketShipmentNo);

	List<Shipment> findByOrderId(Long orderId);
}
```

- [ ] **Step 5: 테스트를 돌려 통과를 확인한다**

```bash
cd backend && ./gradlew :core:test --tests '*ShipmentEntityTest*'
```

Expected: PASS (5건)

- [ ] **Step 6: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/domain/order/Shipment.java \
        backend/core/src/main/java/com/sbshop/agent/core/domain/order/repository/ShipmentRepository.java \
        backend/core/src/test/java/com/sbshop/agent/core/domain/order/ShipmentEntityTest.java
git commit -m "feat(order): 배송(Shipment) 엔티티 신설 — 묶음배송 1단계

네 마켓 모두 주문과 상품 사이에 배송 계층을 갖는다(11번가 dlvNo, 쿠팡
shipmentBoxId, N스토어 packageNumber, Cafe24 shipments). 역할이 같아
marketShipmentNo 한 컬럼으로 흡수한다.

(order_id, market_shipment_no) 유니크는 중복 배송 생성을 막는 하드가드다.
중복되면 같은 송장을 마켓에 두 번 보내 11번가 -3308을 맞는다.

아직 아무도 호출하지 않는다 — 2단계(11번가)가 첫 소비자다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: OrderLineItem에 마켓 상품주문번호·배송 FK 추가

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/OrderLineItem.java`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/repository/OrderLineItemRepository.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/domain/order/OrderLineItemMarketKeyTest.java`

**Interfaces:**
- Consumes: Task 1의 `Shipment`
- Produces:
  - `OrderLineItem.builder()…marketLineItemNo(String).shipmentId(Long).build()`
  - `lineItem.assignMarketLineItemNo(String)` · `lineItem.assignShipmentId(Long)`
  - `OrderLineItemRepository.findByOrderIdAndMarketLineItemNo(Long, String) : Optional<OrderLineItem>`
  - `OrderLineItemRepository.findByShipmentId(Long) : List<OrderLineItem>`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`backend/core/src/test/java/com/sbshop/agent/core/domain/order/OrderLineItemMarketKeyTest.java`:

```java
package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * 라인아이템은 <b>마켓 상품주문 1건</b>을 뜻한다(11번가 ordPrdSeq · N스토어 productOrderId ·
 * 쿠팡 orderItems 원소 · Cafe24 items 원소).
 *
 * <p>{@code marketLineItemNo}가 동기화의 매칭 키다. 지금까지 Cafe24는 배열 인덱스로 짝지어
 * 반영했는데, 마켓이 순서를 바꾸면 엉뚱한 상품에 송장이 붙는다. 식별자로만 매칭한다.
 *
 * <p>두 컬럼 모두 nullable이다. 기존 행은 값이 없고, 1단계에서는 아무도 채우지 않는다.
 */
@DataJpaTest
@ContextConfiguration(classes = OrderLineItemMarketKeyTest.TestApp.class)
class OrderLineItemMarketKeyTest {

	@SpringBootApplication
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.dummy")
	static class TestApp {}

	@Autowired
	private EntityManager em;

	@Test
	@DisplayName("마켓 상품주문번호와 배송 FK를 저장하고 다시 읽는다")
	void persistsMarketKeys() {
		OrderLineItem item = OrderLineItem.builder()
			.orderId(100L)
			.quantity(1)
			.marketLineItemNo("1")
			.shipmentId(7L)
			.build();

		em.persist(item);
		em.flush();
		em.clear();

		OrderLineItem found = em.find(OrderLineItem.class, item.getId());
		assertThat(found.getMarketLineItemNo()).isEqualTo("1");
		assertThat(found.getShipmentId()).isEqualTo(7L);
	}

	@Test
	@DisplayName("두 키를 생략해도 기존처럼 저장된다 (레거시 행 호환)")
	void allowsNullMarketKeys() {
		OrderLineItem item = OrderLineItem.builder()
			.orderId(100L)
			.quantity(1)
			.build();

		em.persist(item);
		em.flush();
		em.clear();

		OrderLineItem found = em.find(OrderLineItem.class, item.getId());
		assertThat(found.getMarketLineItemNo()).isNull();
		assertThat(found.getShipmentId()).isNull();
	}

	@Test
	@DisplayName("assign 메서드로 나중에 채울 수 있다")
	void assignsAfterCreation() {
		OrderLineItem item = OrderLineItem.builder().orderId(100L).quantity(1).build();

		item.assignMarketLineItemNo("2");
		item.assignShipmentId(9L);

		assertThat(item.getMarketLineItemNo()).isEqualTo("2");
		assertThat(item.getShipmentId()).isEqualTo(9L);
	}
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
cd backend && ./gradlew :core:test --tests '*OrderLineItemMarketKeyTest*'
```

Expected: 컴파일 실패 — `cannot find symbol: method marketLineItemNo(String)`

- [ ] **Step 3: OrderLineItem에 필드를 더한다**

`OrderLineItem.java`의 `purchaseStatus` 필드 선언 **아래**에 추가:

```java
	/**
	 * 마켓 상품주문 식별자 (11번가 ordPrdSeq · N스토어 productOrderId ·
	 * 쿠팡 orderItems 원소 · Cafe24 items 원소).
	 *
	 * <p>동기화의 매칭 키다. 배열 인덱스로 짝짓지 않는 이유는, 마켓이 순서를 바꾸면
	 * 엉뚱한 상품에 송장·상태가 붙기 때문이다(Cafe24 현행 방식의 결함).
	 * 레거시 행은 null이며, 그때는 product_id로 매칭한다.
	 */
	@Column(name = "market_line_item_no", length = 100)
	private String marketLineItemNo;

	/** 소속 배송 (sb_shipment 참조값). 배선 전이므로 당분간 null이다. */
	@Column(name = "shipment_id")
	private Long shipmentId;
```

`@Builder` 생성자를 다음으로 교체(두 파라미터 추가):

```java
	@Builder
	public OrderLineItem(Long orderId, Long productId, Integer quantity, SourcingData sourcingData,
		SettlementData settlementData, ShippingData shippingData, Boolean isUnipassDone,
		PurchaseStatus purchaseStatus, String marketLineItemNo, Long shipmentId) {
		this.orderId = orderId;
		this.productId = productId;
		this.quantity = quantity;
		this.sourcingData = sourcingData != null ? sourcingData : SourcingData.builder().build();
		this.settlementData = settlementData != null ? settlementData : SettlementData.builder().build();
		this.shippingData = shippingData != null ? shippingData : ShippingData.builder().build();
		this.isUnipassDone = isUnipassDone;
		this.purchaseStatus = purchaseStatus != null ? purchaseStatus : PurchaseStatus.NOT_PURCHASED;
		this.marketLineItemNo = marketLineItemNo;
		this.shipmentId = shipmentId;
	}
```

"하위 호환" 구역의 `assignProductId` 아래에 추가:

```java
	public void assignMarketLineItemNo(String marketLineItemNo) {
		this.marketLineItemNo = marketLineItemNo;
	}

	public void assignShipmentId(Long shipmentId) {
		this.shipmentId = shipmentId;
	}
```

- [ ] **Step 4: 리포지토리에 조회 메서드를 더한다**

`OrderLineItemRepository.java`의 `findBySourcingData_SourcingOrderNo` 선언 **아래**에 추가:

```java
	/** 동기화 매칭 키 — 배열 순서가 아니라 마켓 상품주문번호로 찾는다. */
	java.util.Optional<OrderLineItem> findByOrderIdAndMarketLineItemNo(Long orderId, String marketLineItemNo);

	List<OrderLineItem> findByShipmentId(Long shipmentId);
```

- [ ] **Step 5: 테스트를 돌려 통과를 확인한다**

```bash
cd backend && ./gradlew :core:test --tests '*OrderLineItemMarketKeyTest*'
```

Expected: PASS (3건)

- [ ] **Step 6: 기존 테스트가 깨지지 않았는지 확인한다**

```bash
cd backend && ./gradlew :core:test
```

Expected: BUILD SUCCESSFUL. 빌더에 파라미터를 더했을 뿐이라 기존 호출부는 영향이 없다.

- [ ] **Step 7: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/domain/order/OrderLineItem.java \
        backend/core/src/main/java/com/sbshop/agent/core/domain/order/repository/OrderLineItemRepository.java \
        backend/core/src/test/java/com/sbshop/agent/core/domain/order/OrderLineItemMarketKeyTest.java
git commit -m "feat(order): 라인아이템에 마켓 상품주문번호·배송 FK 추가

marketLineItemNo가 동기화의 매칭 키다. Cafe24는 지금 배열 인덱스로 짝지어
반영하는데, 마켓이 순서를 바꾸면 엉뚱한 상품에 송장이 붙는다.

둘 다 nullable이다 — 기존 행은 값이 없고 1단계에서는 아무도 채우지 않는다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 중첩 DTO — 배송·상품주문

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/order/dto/MarketLineItemDto.java`
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/order/dto/MarketShipmentDto.java`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/dto/MarketOrderDto.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/order/dto/MarketShipmentDtoTest.java`

**Interfaces:**
- Consumes: `ShippingStatus`, `ShippingCarrier` enum
- Produces:
  - `MarketLineItemDto.builder().marketLineItemNo(String).marketProductCode(String).sellerProductId(String).productName(String).quantity(Integer).orderPrice(BigDecimal).totalAmount(BigDecimal).settlementAmount(BigDecimal).status(ShippingStatus).marketSpecificData(Map<String,Object>).build()`
  - `MarketShipmentDto.builder().marketShipmentNo(String).trackingNo(String).carrier(ShippingCarrier).deliveryStatus(String).shippedAt(LocalDateTime).lineItems(List<MarketLineItemDto>).build()`
  - `MarketOrderDto.getShipments() : List<MarketShipmentDto>` (기본 null — 평면 DTO 표시)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`backend/core/src/test/java/com/sbshop/agent/core/application/order/dto/MarketShipmentDtoTest.java`:

```java
package com.sbshop.agent.core.application.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 어댑터가 마켓 응답을 (주문 / 배송 / 상품주문) 3계층으로 정규화해 넘기기 위한 DTO.
 * 상위 서비스는 이 구조만 보고 마켓을 모른다.
 *
 * <p>정산예정금액을 상품주문에 둔 것은, 11번가 {@code stlPlnAmt}·N스토어
 * {@code expectedSettlementAmount}가 상품주문별로 오기 때문이다. 요율을 곱해 추정하고
 * 분배하는 대신 실측값을 그대로 담을 자리를 미리 만들어 둔다(도입 자체는 별도 항목).
 */
class MarketShipmentDtoTest {

	@Test
	@DisplayName("배송 하나에 상품주문 여러 건을 담는다 — 묶음배송의 표현")
	void holdsMultipleLineItems() {
		MarketShipmentDto shipment = MarketShipmentDto.builder()
			.marketShipmentNo("2716448228")
			.trackingNo("424079080471")
			.carrier(ShippingCarrier.CJ_LOGISTICS)
			.lineItems(List.of(
				MarketLineItemDto.builder()
					.marketLineItemNo("1")
					.productName("쏜리서치 Calcium Magnesium")
					.quantity(1)
					.totalAmount(new BigDecimal("57700"))
					.status(ShippingStatus.NEW)
					.build(),
				MarketLineItemDto.builder()
					.marketLineItemNo("2")
					.productName("쏜리서치 베이직 뉴트리언트")
					.quantity(1)
					.totalAmount(new BigDecimal("52800"))
					.status(ShippingStatus.SHIPPED)
					.build()))
			.build();

		assertThat(shipment.getLineItems()).hasSize(2);
		// 같은 배송인데 상품주문마다 상태가 갈린다 — 상태를 라인아이템에 두는 이유다.
		assertThat(shipment.getLineItems())
			.extracting(MarketLineItemDto::getStatus)
			.containsExactly(ShippingStatus.NEW, ShippingStatus.SHIPPED);
		assertThat(shipment.getLineItems())
			.extracting(MarketLineItemDto::getMarketLineItemNo)
			.containsExactly("1", "2");
	}

	@Test
	@DisplayName("lineItems를 안 주면 빈 목록이다 — null 방어 없이 순회할 수 있어야 한다")
	void defaultsToEmptyLineItems() {
		MarketShipmentDto shipment = MarketShipmentDto.builder()
			.marketShipmentNo("D1")
			.build();

		assertThat(shipment.getLineItems()).isEmpty();
	}

	@Test
	@DisplayName("주문 DTO는 배송 목록을 담을 수 있고, 안 담으면 null이다(평면 DTO 표시)")
	void orderDtoCarriesShipments() {
		MarketOrderDto flat = MarketOrderDto.builder()
			.marketOrderNo("20260731088778989")
			.build();
		assertThat(flat.getShipments()).isNull();

		MarketOrderDto nested = MarketOrderDto.builder()
			.marketOrderNo("20260731088778989")
			.shipments(List.of(MarketShipmentDto.builder().marketShipmentNo("D1").build()))
			.build();
		assertThat(nested.getShipments()).hasSize(1);
	}
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
cd backend && ./gradlew :core:test --tests '*MarketShipmentDtoTest*'
```

Expected: 컴파일 실패 — `cannot find symbol: class MarketShipmentDto`

- [ ] **Step 3: MarketLineItemDto를 만든다**

`backend/core/src/main/java/com/sbshop/agent/core/application/order/dto/MarketLineItemDto.java`:

```java
package com.sbshop.agent.core.application.order.dto;

import java.math.BigDecimal;
import java.util.Map;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * 마켓 상품주문 1건 (11번가 ordPrdSeq · N스토어 productOrderId ·
 * 쿠팡 orderItems 원소 · Cafe24 items 원소).
 *
 * <p>진행상태({@code status})가 여기 있는 이유: 같은 주문·같은 배송지라도 상품마다 상태가
 * 갈린다. 11번가 20260731088778989는 순번 1이 결제완료, 순번 2가 발송완료였다.
 */
@Getter
@Setter
@Builder
public class MarketLineItemDto {

	/** 마켓 상품주문 식별자 — 동기화 매칭 키 */
	private String marketLineItemNo;

	private String marketProductCode;
	private String sellerProductId;
	private String productName;
	private Integer quantity;
	private BigDecimal orderPrice;
	private BigDecimal totalAmount;

	/**
	 * 마켓이 알려준 정산예정금액 (11번가 stlPlnAmt · N스토어 expectedSettlementAmount).
	 * 상품주문별로 오므로 분배가 필요 없다. 도입 전에는 null이며, 그때는 요율 추정을 쓴다.
	 */
	private BigDecimal settlementAmount;

	/** 이 상품주문의 진행상태 */
	private ShippingStatus status;

	/** 마켓별 부가 데이터 (11번가 ordPrdSeq·addPrdYn 등) */
	private Map<String, Object> marketSpecificData;
}
```

- [ ] **Step 4: MarketShipmentDto를 만든다**

`backend/core/src/main/java/com/sbshop/agent/core/application/order/dto/MarketShipmentDto.java`:

```java
package com.sbshop.agent.core.application.order.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

/**
 * 하나의 배송 — 송장 1개가 곧 이것 하나다.
 *
 * <p>{@code marketShipmentNo}에는 마켓별 배송 식별자가 들어간다:
 * 11번가 {@code dlvNo} · 쿠팡 {@code shipmentBoxId} · N스토어 {@code packageNumber} ·
 * Cafe24 shipment 식별자. 얻을 수 없으면 상품주문번호로 대체한다(배송 1 : 상품주문 1).
 * 배송이 없는 주문은 만들지 않는다 — 상위 로직에 분기가 생기기 때문이다.
 */
@Getter
@Setter
@Builder
public class MarketShipmentDto {

	/** 마켓 배송 식별자 — 동기화 매칭 키이자 발송처리 API의 호출 단위 */
	private String marketShipmentNo;

	private String trackingNo;
	private ShippingCarrier carrier;

	/** 마켓이 주는 배송 자체의 상태. 코드계가 마켓마다 달라 원문 문자열로 둔다. */
	private String deliveryStatus;

	private LocalDateTime shippedAt;

	@Builder.Default
	private List<MarketLineItemDto> lineItems = new ArrayList<>();
}
```

- [ ] **Step 5: MarketOrderDto에 배송 목록을 더한다**

`MarketOrderDto.java`의 `marketSpecificData` 필드 **아래**에 추가:

```java
	/**
	 * 3계층으로 정규화된 배송 목록 (묶음배송·다품목 주문의 표현).
	 *
	 * <p>{@code null}이면 아직 평면 DTO라는 뜻이다 — {@code MarketOrderNormalizer}가
	 * 배송 1 : 상품주문 1로 감싼다. 어댑터가 마켓별로 순차 전환되는 동안 두 형태가
	 * 공존하므로, 소비자는 정규화기를 거친 값만 본다.
	 */
	private java.util.List<MarketShipmentDto> shipments;
```

- [ ] **Step 6: 테스트를 돌려 통과를 확인한다**

```bash
cd backend && ./gradlew :core:test --tests '*MarketShipmentDtoTest*'
```

Expected: PASS (3건)

- [ ] **Step 7: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/order/dto/ \
        backend/core/src/test/java/com/sbshop/agent/core/application/order/dto/MarketShipmentDtoTest.java
git commit -m "feat(order): 주문 DTO를 (주문-배송-상품주문) 3계층으로 확장

어댑터가 마켓 응답을 이 구조로 정규화해 넘기면 상위 서비스는 마켓을 모른다.
상태를 상품주문에 둔 것은 같은 주문에서도 상품마다 갈리기 때문이다.

기존 평면 필드는 그대로 두고 shipments를 옵션으로 더했다 — 어댑터가 마켓별로
순차 전환되는 동안 두 형태가 공존한다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 평면 DTO를 3계층으로 감싸는 정규화기

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/MarketOrderNormalizer.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/MarketOrderNormalizerTest.java`

**Interfaces:**
- Consumes: Task 3의 `MarketOrderDto`·`MarketShipmentDto`·`MarketLineItemDto`
- Produces: `MarketOrderNormalizer.normalize(MarketOrderDto) : MarketOrderDto` — `shipments`가 항상 채워진 DTO를 돌려준다(원본 미변경, 새 인스턴스)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`backend/core/src/test/java/com/sbshop/agent/core/application/order/service/MarketOrderNormalizerTest.java`:

```java
package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 어댑터를 마켓별로 순차 전환하는 동안 평면 DTO와 3계층 DTO가 공존한다.
 * 소비자가 분기를 갖지 않도록, 정규화기가 평면 DTO를 배송 1 : 상품주문 1로 감싼다.
 *
 * <p>이 래핑이 1단계 "동작 불변"의 핵심이다 — 단일 상품 주문(현재 데이터의 전부)이
 * 3계층을 거쳐도 같은 결과가 나와야 한다.
 */
class MarketOrderNormalizerTest {

	@Test
	@DisplayName("평면 DTO는 배송 1 : 상품주문 1로 감싸지고 값이 그대로 옮겨진다")
	void wrapsFlatDtoIntoSingleShipmentAndLineItem() {
		MarketOrderDto flat = MarketOrderDto.builder()
			.marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo("20260731088778989")
			.marketProductCode("210121IHB011")
			.productName("쏜리서치 Calcium Magnesium")
			.quantity(1)
			.orderPrice(new BigDecimal("57700"))
			.totalAmount(new BigDecimal("57700"))
			.status(ShippingStatus.NEW)
			.trackingNo("424079080471")
			.carrier(ShippingCarrier.CJ_LOGISTICS)
			.marketSpecificData(Map.of("ordPrdSeq", "1"))
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(flat);

		assertThat(result.getShipments()).hasSize(1);
		MarketShipmentDto shipment = result.getShipments().get(0);
		assertThat(shipment.getTrackingNo()).isEqualTo("424079080471");
		assertThat(shipment.getCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);

		assertThat(shipment.getLineItems()).hasSize(1);
		MarketLineItemDto item = shipment.getLineItems().get(0);
		assertThat(item.getMarketProductCode()).isEqualTo("210121IHB011");
		assertThat(item.getProductName()).isEqualTo("쏜리서치 Calcium Magnesium");
		assertThat(item.getQuantity()).isEqualTo(1);
		assertThat(item.getOrderPrice()).isEqualByComparingTo("57700");
		assertThat(item.getTotalAmount()).isEqualByComparingTo("57700");
		assertThat(item.getStatus()).isEqualTo(ShippingStatus.NEW);
		assertThat(item.getMarketSpecificData()).containsEntry("ordPrdSeq", "1");
	}

	@Test
	@DisplayName("배송 식별자가 없으면 주문번호로 대체한다 — 배송 없는 주문은 만들지 않는다")
	void fallsBackToOrderNoAsShipmentNo() {
		MarketOrderDto flat = MarketOrderDto.builder()
			.marketOrderNo("20260731088778989")
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(flat);

		assertThat(result.getShipments().get(0).getMarketShipmentNo())
			.isEqualTo("20260731088778989");
		assertThat(result.getShipments().get(0).getLineItems().get(0).getMarketLineItemNo())
			.isEqualTo("20260731088778989");
	}

	@Test
	@DisplayName("쿠팡처럼 shipmentBoxId가 있으면 그것을 배송 식별자로 쓴다")
	void usesShipmentBoxIdWhenPresent() {
		MarketOrderDto flat = MarketOrderDto.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("3000012345")
			.shipmentBoxId("77001122")
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(flat);

		assertThat(result.getShipments().get(0).getMarketShipmentNo()).isEqualTo("77001122");
	}

	@Test
	@DisplayName("이미 3계층인 DTO는 그대로 돌려준다")
	void passesThroughAlreadyNestedDto() {
		MarketShipmentDto given = MarketShipmentDto.builder()
			.marketShipmentNo("D1")
			.lineItems(List.of(MarketLineItemDto.builder().marketLineItemNo("1").build()))
			.build();
		MarketOrderDto nested = MarketOrderDto.builder()
			.marketOrderNo("20260731088778989")
			.shipments(List.of(given))
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(nested);

		assertThat(result.getShipments()).containsExactly(given);
	}

	@Test
	@DisplayName("원본 DTO를 건드리지 않는다")
	void doesNotMutateInput() {
		MarketOrderDto flat = MarketOrderDto.builder().marketOrderNo("A1").build();

		MarketOrderNormalizer.normalize(flat);

		assertThat(flat.getShipments()).isNull();
	}

	@Test
	@DisplayName("주문 공통 필드(수취인·주소·통관번호)는 주문 계층에 남는다")
	void keepsOrderLevelFields() {
		MarketOrderDto flat = MarketOrderDto.builder()
			.marketOrderNo("A1")
			.recipientName("정나영")
			.address("서울특별시 양천구")
			.customsClearanceNo("P200032008307")
			.build();

		MarketOrderDto result = MarketOrderNormalizer.normalize(flat);

		assertThat(result.getRecipientName()).isEqualTo("정나영");
		assertThat(result.getAddress()).isEqualTo("서울특별시 양천구");
		assertThat(result.getCustomsClearanceNo()).isEqualTo("P200032008307");
	}
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
cd backend && ./gradlew :core:test --tests '*MarketOrderNormalizerTest*'
```

Expected: 컴파일 실패 — `cannot find symbol: class MarketOrderNormalizer`

- [ ] **Step 3: 정규화기를 만든다**

`backend/core/src/main/java/com/sbshop/agent/core/application/order/service/MarketOrderNormalizer.java`:

```java
package com.sbshop.agent.core.application.order.service;

import java.util.List;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;

/**
 * 평면 {@link MarketOrderDto}를 (주문 / 배송 / 상품주문) 3계층으로 정규화한다.
 *
 * <p>어댑터를 마켓별로 순차 전환하는 동안 두 형태가 공존한다. 소비자가 분기를 갖지 않도록
 * 여기서 흡수한다 — 평면 DTO는 <b>배송 1 : 상품주문 1</b>로 감싸고, 이미 3계층인 DTO는
 * 그대로 통과시킨다.
 *
 * <p>배송 식별자를 얻지 못하면 주문번호로 대체한다. 배송 계층이 항상 존재해야
 * 상위 로직("이 배송의 상품들")에 null 분기가 생기지 않는다.
 */
public final class MarketOrderNormalizer {

	private MarketOrderNormalizer() {}

	public static MarketOrderDto normalize(MarketOrderDto dto) {
		if (dto == null) {
			return null;
		}
		if (dto.getShipments() != null) {
			return dto;
		}

		String shipmentNo = resolveShipmentNo(dto);

		MarketLineItemDto lineItem = MarketLineItemDto.builder()
			.marketLineItemNo(shipmentNo)
			.marketProductCode(dto.getMarketProductCode())
			.sellerProductId(dto.getSellerProductId())
			.productName(dto.getProductName())
			.quantity(dto.getQuantity())
			.orderPrice(dto.getOrderPrice())
			.totalAmount(dto.getTotalAmount())
			.status(dto.getStatus())
			.marketSpecificData(dto.getMarketSpecificData())
			.build();

		MarketShipmentDto shipment = MarketShipmentDto.builder()
			.marketShipmentNo(shipmentNo)
			.trackingNo(dto.getTrackingNo())
			.carrier(dto.getCarrier())
			.lineItems(List.of(lineItem))
			.build();

		// 원본을 건드리지 않는다 — 호출자가 평면 필드를 계속 쓰고 있을 수 있다.
		return dto.toBuilder()
			.shipments(List.of(shipment))
			.build();
	}

	/**
	 * 배송 식별자를 고른다. 쿠팡은 {@code shipmentBoxId}가 이미 평면 DTO에 있고,
	 * 나머지 마켓은 전환 전이라 주문번호로 대체한다(배송 1 : 상품주문 1).
	 */
	private static String resolveShipmentNo(MarketOrderDto dto) {
		String boxId = dto.getShipmentBoxId();
		if (boxId != null && !boxId.isBlank()) {
			return boxId;
		}
		return dto.getMarketOrderNo();
	}
}
```

- [ ] **Step 4: MarketOrderDto에 toBuilder를 켠다**

정규화기가 원본을 복제하려면 필요하다. `MarketOrderDto.java`의 클래스 애너테이션을 바꾼다:

```java
@Getter
@Setter
@Builder(toBuilder = true)
public class MarketOrderDto {
```

- [ ] **Step 5: 테스트를 돌려 통과를 확인한다**

```bash
cd backend && ./gradlew :core:test --tests '*MarketOrderNormalizerTest*'
```

Expected: PASS (6건)

- [ ] **Step 6: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/order/service/MarketOrderNormalizer.java \
        backend/core/src/main/java/com/sbshop/agent/core/application/order/dto/MarketOrderDto.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/order/service/MarketOrderNormalizerTest.java
git commit -m "feat(order): 평면 주문 DTO를 3계층으로 감싸는 정규화기

어댑터를 마켓별로 순차 전환하는 동안 평면 DTO와 3계층 DTO가 공존한다.
소비자가 분기를 갖지 않도록 정규화기가 흡수한다 — 평면은 배송 1 : 상품주문 1로
감싸고, 이미 3계층이면 통과시킨다.

배송 식별자를 못 얻으면 주문번호로 대체한다. 배송 계층이 항상 있어야
'이 배송의 상품들'을 묻는 상위 로직에 null 분기가 안 생긴다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: 배송 upsert와 라인아이템 연결

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderShipmentUpsertService.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderShipmentUpsertServiceTest.java`

**Interfaces:**
- Consumes: Task 1의 `Shipment`·`ShipmentRepository`, Task 2의 `OrderLineItem`·`OrderLineItemRepository`, Task 3의 `MarketShipmentDto`
- Produces:
  - `OrderShipmentUpsertService.upsertShipment(Long orderId, MarketShipmentDto dto) : Shipment`
  - `OrderShipmentUpsertService.linkToShipment(OrderLineItem item, Shipment shipment) : void` — 배송 FK 연결 + 송장 미러 반영

- [ ] **Step 1: 실패하는 테스트를 쓴다**

`backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderShipmentUpsertServiceTest.java`:

```java
package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 배송 upsert는 <b>마켓 배송식별자로만</b> 매칭한다. 배열 순서에 기대지 않는다 —
 * Cafe24 현행 방식(인덱스 짝짓기)은 마켓이 순서를 바꾸면 엉뚱한 상품에 송장을 붙인다.
 *
 * <p>{@code linkToShipment}는 설계 4.4의 미러 규칙을 구현한다. 라인아이템의 송장 컬럼은
 * 기존 그리드·엑셀·정산 쿼리·이메일 파이프라인이 전부 읽으므로 당분간 유지하되,
 * <b>쓰기는 배송이 단일 원본</b>이고 라인아이템에는 복제만 내려쓴다.
 */
@ExtendWith(MockitoExtension.class)
class OrderShipmentUpsertServiceTest {

	@Mock
	private ShipmentRepository shipmentRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;

	private OrderShipmentUpsertService service() {
		return new OrderShipmentUpsertService(shipmentRepository, orderLineItemRepository);
	}

	@Test
	@DisplayName("배송이 없으면 새로 만든다")
	void createsWhenAbsent() {
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(100L, "2716448228"))
			.thenReturn(Optional.empty());
		when(shipmentRepository.save(any(Shipment.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		Shipment result = service().upsertShipment(100L, MarketShipmentDto.builder()
			.marketShipmentNo("2716448228")
			.trackingNo("424079080471")
			.carrier(ShippingCarrier.CJ_LOGISTICS)
			.build());

		assertThat(result.getOrderId()).isEqualTo(100L);
		assertThat(result.getMarketShipmentNo()).isEqualTo("2716448228");
		assertThat(result.getTrackingNo()).isEqualTo("424079080471");
	}

	@Test
	@DisplayName("같은 배송식별자면 기존 배송을 갱신한다 — 중복 생성하지 않는다")
	void updatesExistingInsteadOfDuplicating() {
		Shipment existing = Shipment.builder()
			.orderId(100L)
			.marketShipmentNo("2716448228")
			.build();
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(100L, "2716448228"))
			.thenReturn(Optional.of(existing));
		when(shipmentRepository.save(any(Shipment.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		Shipment result = service().upsertShipment(100L, MarketShipmentDto.builder()
			.marketShipmentNo("2716448228")
			.trackingNo("6079990333504")
			.carrier(ShippingCarrier.KOREA_POST)
			.build());

		assertThat(result).isSameAs(existing);
		assertThat(result.getTrackingNo()).isEqualTo("6079990333504");
		assertThat(result.getShippingCarrier()).isEqualTo(ShippingCarrier.KOREA_POST);
	}

	@Test
	@DisplayName("자리표시자·빈 송장은 기존 실송장을 덮지 않는다")
	void placeholderTrackingDoesNotOverwrite() {
		// D-119/D-120: 마켓이 미발송 주문에 '00000000'이나 빈 문자열을 담아 주는 경우가 있고,
		// 그 값으로 실제 송장을 덮으면 배송정보가 유실된다.
		Shipment existing = Shipment.builder()
			.orderId(100L)
			.marketShipmentNo("D1")
			.trackingNo("424079080471")
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.build();
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(100L, "D1"))
			.thenReturn(Optional.of(existing));
		when(shipmentRepository.save(any(Shipment.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		Shipment result = service().upsertShipment(100L, MarketShipmentDto.builder()
			.marketShipmentNo("D1")
			.trackingNo("00000000")
			.carrier(ShippingCarrier.HANJIN)
			.build());

		assertThat(result.getTrackingNo()).isEqualTo("424079080471");
		assertThat(result.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
	}

	@Test
	@DisplayName("마켓이 준 실송장이면 마켓 보유(trackingSentToMarket=true)로 마킹한다")
	void marksMarketOwnershipOnRealTracking() {
		// D-129: 마켓이 실송장을 알려줬다는 것은 곧 마켓이 그 송장을 보유한다는 뜻이다.
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(100L, "D1"))
			.thenReturn(Optional.empty());
		when(shipmentRepository.save(any(Shipment.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		Shipment result = service().upsertShipment(100L, MarketShipmentDto.builder()
			.marketShipmentNo("D1")
			.trackingNo("424079080471")
			.build());

		assertThat(result.getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("배송식별자가 없으면 저장하지 않고 예외를 던진다")
	void rejectsMissingShipmentNo() {
		OrderShipmentUpsertService service = service();
		MarketShipmentDto dto = MarketShipmentDto.builder().trackingNo("X").build();

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> service.upsertShipment(100L, dto))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("배송 식별자");

		verify(shipmentRepository, never()).save(any());
	}

	@Test
	@DisplayName("라인아이템을 배송에 연결하면 송장 미러가 함께 내려간다")
	void linkMirrorsTrackingToLineItem() {
		Shipment shipment = Shipment.builder()
			.orderId(100L)
			.marketShipmentNo("D1")
			.trackingNo("424079080471")
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.trackingSentToMarket(true)
			.build();
		setId(shipment, 7L);
		OrderLineItem item = OrderLineItem.builder().orderId(100L).quantity(1).build();
		when(orderLineItemRepository.save(any(OrderLineItem.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		service().linkToShipment(item, shipment);

		ArgumentCaptor<OrderLineItem> captor = ArgumentCaptor.forClass(OrderLineItem.class);
		verify(orderLineItemRepository).save(captor.capture());
		OrderLineItem saved = captor.getValue();
		assertThat(saved.getShipmentId()).isEqualTo(7L);
		ShippingData shipping = saved.getShippingData();
		assertThat(shipping.getTrackingNo()).isEqualTo("424079080471");
		assertThat(shipping.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(shipping.getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("미러가 라인아이템의 진행상태를 건드리지 않는다")
	void linkKeepsLineItemStatus() {
		// 상태는 상품주문마다 갈리므로 배송이 덮으면 안 된다
		// (11번가 20260731088778989: 순번 1 결제완료 / 순번 2 발송완료).
		Shipment shipment = Shipment.builder()
			.orderId(100L).marketShipmentNo("D1").trackingNo("424079080471").build();
		setId(shipment, 7L);
		OrderLineItem item = OrderLineItem.builder()
			.orderId(100L)
			.quantity(1)
			.shippingData(ShippingData.builder()
				.shippingStatus(com.sbshop.agent.core.domain.order.enums.ShippingStatus.NEW)
				.build())
			.build();
		when(orderLineItemRepository.save(any(OrderLineItem.class)))
			.thenAnswer(inv -> inv.getArgument(0));

		service().linkToShipment(item, shipment);

		assertThat(item.getShippingData().getShippingStatus())
			.isEqualTo(com.sbshop.agent.core.domain.order.enums.ShippingStatus.NEW);
	}

	/** BaseEntity.id는 생성자로 못 넣으므로 리플렉션으로 채운다(테스트 전용). */
	private static void setId(Object entity, Long id) {
		try {
			java.lang.reflect.Field field =
				com.sbshop.agent.core.domain.common.BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(entity, id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
```

- [ ] **Step 2: 테스트를 돌려 실패를 확인한다**

```bash
cd backend && ./gradlew :core:test --tests '*OrderShipmentUpsertServiceTest*'
```

Expected: 컴파일 실패 — `cannot find symbol: class OrderShipmentUpsertService`

- [ ] **Step 3: 서비스를 만든다**

`backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderShipmentUpsertService.java`:

```java
package com.sbshop.agent.core.application.order.service;

import org.springframework.stereotype.Service;

import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

import lombok.RequiredArgsConstructor;

/**
 * 배송을 <b>마켓 배송식별자로</b> 찾아 upsert하고, 라인아이템을 그 배송에 연결한다.
 *
 * <p>배열 순서에 기대지 않는 것이 핵심이다. Cafe24 현행 방식은 items 배열 인덱스로
 * 라인아이템을 짝짓는데, 마켓이 순서를 바꾸면 엉뚱한 상품에 송장이 붙는다.
 *
 * <p>라인아이템 생성은 여기서 하지 않는다 — 상품 매핑(sbCode 조회)과 정산액 계산이
 * 마켓 고유 로직이라 각 동기화 서비스에 남는다. 이 서비스는 배송 계층과 연결만 책임진다.
 */
@Service
@RequiredArgsConstructor
public class OrderShipmentUpsertService {

	private final ShipmentRepository shipmentRepository;
	private final OrderLineItemRepository orderLineItemRepository;

	public Shipment upsertShipment(Long orderId, MarketShipmentDto dto) {
		String shipmentNo = dto.getMarketShipmentNo();
		if (shipmentNo == null || shipmentNo.isBlank()) {
			throw new IllegalArgumentException(
				"배송 식별자 없이 배송을 만들 수 없습니다: orderId=" + orderId);
		}

		// D-119/D-120: 마켓의 자리표시자·빈 값이 실송장을 덮지 않도록 실값일 때만 반영한다.
		boolean meaningful = ShippingData.isMeaningfulTracking(dto.getTrackingNo());
		String trackingNo = meaningful ? dto.getTrackingNo() : null;
		// D-129: 마켓이 실송장을 알려줬다 = 마켓이 그 송장을 보유한다.
		Boolean ownedByMarket = ShippingData.marketOwnsTracking(dto.getTrackingNo());

		Shipment shipment = shipmentRepository
			.findByOrderIdAndMarketShipmentNo(orderId, shipmentNo)
			.orElseGet(() -> Shipment.builder()
				.orderId(orderId)
				.marketShipmentNo(shipmentNo)
				.build());

		shipment.applyTracking(trackingNo, meaningful ? dto.getCarrier() : null, ownedByMarket);
		shipment.applyDeliveryStatus(dto.getDeliveryStatus());
		shipment.applyShippedAt(dto.getShippedAt());

		return shipmentRepository.save(shipment);
	}

	/**
	 * 라인아이템을 배송에 연결하고 송장 정보를 <b>미러로</b> 내려쓴다(설계 4.4).
	 *
	 * <p>라인아이템의 송장 컬럼은 기존 그리드·엑셀·정산 쿼리·이메일 파이프라인이 전부
	 * 읽는다. 한 번에 다 옮기면 검증 범위가 통제 불가능해지므로 당분간 복제를 유지한다.
	 * <b>쓰기의 단일 원본은 배송이다</b> — 소비처를 모두 옮긴 뒤 미러 컬럼을 제거한다.
	 *
	 * <p>진행상태는 건드리지 않는다. 같은 배송이라도 상품주문마다 상태가 갈린다.
	 */
	public void linkToShipment(OrderLineItem item, Shipment shipment) {
		item.assignShipmentId(shipment.getId());
		item.applyShippingData(item.getShippingData().toBuilder()
			.trackingNo(shipment.getTrackingNo())
			.shippingCarrier(shipment.getShippingCarrier())
			.trackingSentToMarket(shipment.getTrackingSentToMarket())
			.build());
		orderLineItemRepository.save(item);
	}
}
```

- [ ] **Step 4: 테스트를 돌려 통과를 확인한다**

```bash
cd backend && ./gradlew :core:test --tests '*OrderShipmentUpsertServiceTest*'
```

Expected: PASS (7건)

- [ ] **Step 5: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderShipmentUpsertService.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/order/service/OrderShipmentUpsertServiceTest.java
git commit -m "feat(order): 배송 upsert와 라인아이템 연결 서비스

배송을 마켓 배송식별자로 찾아 upsert한다. 배열 순서에 기대지 않는 것이 핵심이다 —
Cafe24 현행 방식은 items 인덱스로 짝짓는데 마켓이 순서를 바꾸면 엉뚱한 상품에
송장이 붙는다.

linkToShipment는 설계 4.4의 미러 규칙이다. 라인아이템의 송장 컬럼은 기존
그리드·엑셀·정산·이메일이 전부 읽으므로 당분간 복제를 유지하되, 쓰기의 단일
원본은 배송이다. 진행상태는 건드리지 않는다 — 상품주문마다 갈리기 때문이다.

라인아이템 생성은 하지 않는다. 상품 매핑과 정산액 계산이 마켓 고유라 각
동기화 서비스에 남는다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 운영 DDL 준비와 회귀 게이트

**Files:**
- Create: `backend/docs/ddl/2026-08-05-shipment.sql`
- Test: 전 모듈 회귀

**Interfaces:**
- Consumes: Task 1·2의 엔티티 매핑
- Produces: 운영 DB에 적용할 DDL 스크립트

- [ ] **Step 1: DDL 스크립트를 쓴다**

`backend/docs/ddl/2026-08-05-shipment.sql`:

```sql
-- 묶음배송·다품목 주문 모델 1단계 (설계: docs/superpowers/specs/2026-08-05-bundle-shipment-order-model-design.md)
--
-- ddl-auto=update가 테이블·컬럼은 만들지만 UNIQUE 제약은 만들지 않는다.
-- 배포 전에 이 스크립트를 먼저 적용한다.
--
-- 안전성: 신설 테이블 1개 + nullable 컬럼 2개. 기존 행은 두 컬럼이 NULL로 남고,
-- PostgreSQL은 UNIQUE 인덱스에서 NULL끼리 충돌로 보지 않으므로 기존 데이터에 영향이 없다.

CREATE TABLE IF NOT EXISTS sb_shipment (
    id                      BIGSERIAL PRIMARY KEY,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMP,
    updated_at              TIMESTAMP,
    order_id                BIGINT       NOT NULL,
    market_shipment_no      VARCHAR(100) NOT NULL,
    tracking_no             VARCHAR(100),
    shipping_carrier        VARCHAR(30),
    delivery_status         VARCHAR(30),
    tracking_sent_to_market BOOLEAN,
    shipped_at              TIMESTAMP,
    CONSTRAINT uk_shipment_order_market_no UNIQUE (order_id, market_shipment_no)
);

CREATE INDEX IF NOT EXISTS ix_shipment_order_id ON sb_shipment (order_id);

ALTER TABLE sb_order_line_item ADD COLUMN IF NOT EXISTS market_line_item_no VARCHAR(100);
ALTER TABLE sb_order_line_item ADD COLUMN IF NOT EXISTS shipment_id BIGINT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_line_item_order_market_no
    ON sb_order_line_item (order_id, market_line_item_no);

CREATE INDEX IF NOT EXISTS ix_line_item_shipment_id ON sb_order_line_item (shipment_id);
```

- [ ] **Step 2: 전 모듈 회귀를 돌린다**

```bash
cd backend && ./gradlew test
```

Expected: BUILD SUCCESSFUL, 실패 0건.

**중요:** 1단계는 동작 불변이다. **기존 테스트가 단 한 건이라도 수정이 필요했다면 그건 설계 위반**이므로 멈추고 원인을 보고한다.

- [ ] **Step 3: 새 코드가 어디에도 배선되지 않았는지 확인한다**

```bash
cd backend && grep -rn "Shipment\b" --include="*.java" \
  core/src/main/java/com/sbshop/agent/core/application/order/adapter/ \
  core/src/main/java/com/sbshop/agent/core/application/order/service/ \
  | grep -v "OrderShipmentUpsertService.java" | grep -v "MarketShipmentDto" | grep -v "shipmentBoxId"
```

Expected: 출력 없음. `Shipment`를 쓰는 곳은 새로 만든 서비스뿐이어야 한다.

- [ ] **Step 4: 커밋**

```bash
git add backend/docs/ddl/2026-08-05-shipment.sql
git commit -m "chore(ddl): 배송 테이블·라인아이템 컬럼 DDL (묶음배송 1단계)

ddl-auto=update가 테이블·컬럼은 만들지만 UNIQUE 제약은 만들지 않아
수동 DDL이 필요하다.

신설 테이블 1개 + nullable 컬럼 2개라 기존 행에 영향이 없다. PostgreSQL은
UNIQUE 인덱스에서 NULL끼리 충돌로 보지 않으므로, 값이 없는 레거시 행은
제약에 걸리지 않는다.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 5: 운영 DDL 적용 — 사용자 승인 대기**

**이 단계는 스스로 실행하지 않는다.** 운영 DB 쓰기이므로 사용자에게 다음을 보고하고 승인을 받는다:

- 적용할 SQL 전문
- 영향: 신설 테이블 1개, `sb_order_line_item`에 nullable 컬럼 2개, 인덱스 3개
- 기존 행 변경 없음 · 되돌리기는 `DROP TABLE sb_shipment` + `ALTER TABLE … DROP COLUMN`

승인 후 적용 명령:

```bash
ssh -i ssh-key-2026-06-25.key -o StrictHostKeyChecking=no ubuntu@168.107.31.154 \
  "docker exec -i projects-postgres-1 psql -U canagent -d sbshop" \
  < backend/docs/ddl/2026-08-05-shipment.sql
```

적용 확인:

```bash
ssh -i ssh-key-2026-06-25.key -o StrictHostKeyChecking=no ubuntu@168.107.31.154 \
  "docker exec projects-postgres-1 psql -U canagent -d sbshop -c '\\d sb_shipment'"
```

Expected: 테이블과 `uk_shipment_order_market_no` 제약이 보인다.

---

## 완료 기준

1단계는 다음이 모두 참일 때 끝난다.

- [ ] `sb_shipment` 테이블과 리포지토리가 있고 유니크 제약이 동작한다
- [ ] `OrderLineItem`이 `marketLineItemNo`·`shipmentId`를 갖는다
- [ ] 평면 DTO가 3계층으로 정규화되고, 단일 상품 주문이 원래 값 그대로 왕복한다
- [ ] 배송 upsert가 마켓 식별자로 매칭하고, 자리표시자 송장을 거부하며, 미러를 내려쓴다
- [ ] `./gradlew test` 전체 통과 — **기존 테스트 수정 0건**
- [ ] 새 코드가 어댑터·동기화 서비스에 배선되지 않았다
- [ ] 운영 DDL이 승인 후 적용됐고 스키마가 확인된다

## 다음 단계

2단계(11번가)에서 첫 소비자가 붙는다. 착수 전에 별도 계획을 세운다. 주요 내용:

- `claimservice/orderlistall/{ordNo}`로 상태 판정 전환 — "어느 목록에서 왔는가"로 추론하는 구조 제거
- `ElevenstOrderAdapter`가 `ordPrdSeq` 단위 라인아이템과 `dlvNo` 단위 배송을 직접 emit
- D-126의 목록 병합·등급 우선순위 로직 제거
- 부분발송처리(`partDlvYn=Y` + `ordPrdSeq` 목록) 도입
- 레거시 라인아이템 매칭(설계 5.4): `product_id` 일치로 붙이고, 못 붙인 상품주문은 신규 생성
- 검증: 정나영 주문 `20260731088778989`가 2행으로 나뉘고 송장이 순번 2에만 붙는가
