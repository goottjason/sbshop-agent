# SP-E: 발주확인/취소 Cafe24 전환 + ESM+ Selenium 청산 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** G마켓/옥션의 발주확인·취소를 컨테이너에서 못 도는 ESM+ Selenium 대신 Cafe24 주문상태 API로 전환하고, 죽은 ESM+ Selenium 코드를 전부 제거한다.

**Architecture:** `Cafe24OrderApiPort`에 `acceptOrder(orderId)`/`cancelOrder(orderId)`를 추가하고 Cafe24 상태코드는 `Cafe24OrderApiClient`에 격리한다(미검증 값 단일 지점). GMARKET 어댑터(`EsmplusOrderAdapter`, Selenium)를 Cafe24 기반 `Cafe24GmarketOrderAdapter`로 교체하고, `Cafe24AuctionOrderAdapter`에 confirm/cancel을 구현한다. `OrderService.cancelOrder`가 G마켓/옥션에 한해 마켓 취소를 전파한다(기존 `cancelOrderToMarketplace` 재사용). 그 후 Esmplus* Selenium 클래스·주입·디버그 엔드포인트를 삭제한다.

**Tech Stack:** Java 21, Spring Boot 3.5 (core/infrastructure/api/worker), JUnit 5 + Mockito + AssertJ.

## Global Constraints

- 포트-인-코어: `Cafe24OrderApiPort`는 core, 구현 `Cafe24OrderApiClient`는 infrastructure.
- Cafe24 accept/cancel 상태코드는 **best-known 상수 + 라이브 검증 대상** — `Cafe24OrderApiClient`에만 존재. 현재값: `ACCEPT_STATUS = "N20"`(배송준비중), `CANCEL_STATUS = "C40"`(취소완료). PUT 바디 `{"shop_no":1, "request":{"status": status}}`. **이 값·필드명·바디형태는 라이브 검증 후 교정 필요** — 틀리면 Cafe24가 4xx로 실패, 조용히 성공 위장 안 함(SP-A 원칙).
- 취소 마켓전파는 **GMARKET·AUCTION만**. 쿠팡·스마트스토어 취소는 현행(로컬) 유지.
- 마켓 API 실패는 예외 전파로 표면화(은폐 금지).
- GMARKET `MarketOrderPort` 빈은 정확히 1개(교체 시 기존 삭제 동시).
- DDL 없음. 신규 의존성 없음. selenium gradle 의존성은 통관 스크래퍼가 쓰면 유지.
- 커밋 말미: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

---

### Task 1: Cafe24 주문 accept/cancel API

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/port/Cafe24OrderApiPort.java`
- Modify: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/client/Cafe24OrderApiClient.java`
- Test: `backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24OrderApiClientStatusTest.java`

**Interfaces:**
- Produces: `Cafe24OrderApiPort.acceptOrder(String cafe24OrderId)` (void), `Cafe24OrderApiPort.cancelOrder(String cafe24OrderId)` (void). Impl uses `Cafe24RestClient.put(String path, Object body) : String` (already exists).

- [ ] **Step 1: 포트에 두 메서드 추가**

`Cafe24OrderApiPort.java` (인터페이스 끝에 추가):
```java
	/** 발주확인 — 주문 상태를 배송준비 상태로 변경. PUT /admin/orders/{id}. */
	void acceptOrder(String cafe24OrderId);

	/** 주문 취소 — 주문 상태를 취소로 변경. PUT /admin/orders/{id}. */
	void cancelOrder(String cafe24OrderId);
```

- [ ] **Step 2: 실패 테스트 작성 (Mockito on Cafe24RestClient)**

`Cafe24OrderApiClientStatusTest.java`:
```java
package com.sbshop.agent.infrastructure.client.cafe24;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24OrderApiClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24OrderApiClientStatusTest {

	@Mock Cafe24RestClient restClient;

	@Test
	@DisplayName("acceptOrder는 PUT /admin/orders/{id}에 배송준비 상태(N20)를 보낸다")
	void acceptOrderSendsPut() {
		var client = new Cafe24OrderApiClient(restClient, new ObjectMapper());
		client.acceptOrder("O123");
		verify(restClient).put(eq("/admin/orders/O123"),
			ArgumentMatchers.argThat(body -> bodyStatus(body).equals("N20")));
	}

	@Test
	@DisplayName("cancelOrder는 PUT /admin/orders/{id}에 취소 상태(C40)를 보낸다")
	void cancelOrderSendsPut() {
		var client = new Cafe24OrderApiClient(restClient, new ObjectMapper());
		client.cancelOrder("O123");
		verify(restClient).put(eq("/admin/orders/O123"),
			ArgumentMatchers.argThat(body -> bodyStatus(body).equals("C40")));
	}

	@SuppressWarnings("unchecked")
	private String bodyStatus(Object body) {
		Map<String, Object> req = (Map<String, Object>) ((Map<String, Object>) body).get("request");
		return String.valueOf(req.get("status"));
	}
}
```
> 참고: `Cafe24OrderApiClient` 생성자가 `(Cafe24RestClient, ObjectMapper)`인지 확인하고 시그니처에 맞춰 생성한다(파일 상단 필드 `restClient`, `objectMapper` 확인됨). 생성자가 다르면 그 형태로 맞춘다.

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*Cafe24OrderApiClientStatusTest*'`
Expected: FAIL(컴파일) — `acceptOrder`/`cancelOrder` 미존재.

- [ ] **Step 4: 클라이언트 구현**

`Cafe24OrderApiClient.java`에 추가(상수 + 두 메서드 + private helper). 필드/`java.util.Map` import 추가:
```java
	// Cafe24 주문 상태코드 — 라이브 검증 대상(값·필드명·바디형태 확정 필요).
	private static final String ACCEPT_STATUS = "N20"; // 배송준비중(=발주확인)
	private static final String CANCEL_STATUS = "C40"; // 취소완료

	@Override
	public void acceptOrder(String cafe24OrderId) {
		updateStatus(cafe24OrderId, ACCEPT_STATUS);
	}

	@Override
	public void cancelOrder(String cafe24OrderId) {
		updateStatus(cafe24OrderId, CANCEL_STATUS);
	}

	private void updateStatus(String cafe24OrderId, String status) {
		java.util.Map<String, Object> body = java.util.Map.of(
			"shop_no", 1,
			"request", java.util.Map.of("status", status));
		restClient.put("/admin/orders/" + cafe24OrderId, body);
		log.info("[Cafe24] 주문상태 변경: orderId={}, status={}", cafe24OrderId, status);
	}
```
(`Cafe24OrderApiClient`에 `@Slf4j`가 없으면 로그 라인 제거 또는 `@Slf4j` 추가.)

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd backend && ./gradlew :infrastructure:test --tests '*Cafe24OrderApiClientStatusTest*'`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/order/port/Cafe24OrderApiPort.java \
        backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/client/cafe24/client/Cafe24OrderApiClient.java \
        backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/client/cafe24/Cafe24OrderApiClientStatusTest.java
git commit -m "feat(SP-E): Cafe24 주문 accept/cancel API (상태변경 PUT) — 상태코드 라이브 검증 대상

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: GMARKET 어댑터 Cafe24화 (EsmplusOrderAdapter 교체)

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/order/adapter/Cafe24GmarketOrderAdapter.java`
- Delete: `backend/core/src/main/java/com/sbshop/agent/core/application/order/adapter/EsmplusOrderAdapter.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/order/adapter/Cafe24GmarketOrderAdapterTest.java`

**Interfaces:**
- Consumes: `Cafe24OrderApiPort.acceptOrder/cancelOrder`(Task 1), `Cafe24ShipmentService.ship(Order, String, ShippingCarrier)`(기존, EsmplusOrderAdapter.shipOrder에서 사용 중), `MarketOrderPort`(기존 인터페이스).
- Produces: `Cafe24GmarketOrderAdapter` — `@Component`, `getMarketType()==GMARKET`.

> **맥락:** 기존 `EsmplusOrderAdapter`(GMARKET)는 shipOrder를 이미 Cafe24로 처리하지만 acceptOrders/cancelOrder는 Selenium(`esmplusOrderApiPort`), fetchOrders도 Selenium(현재 G마켓 조회는 `Cafe24OrderSyncService`가 담당하므로 이 포트 fetch는 미사용). 신규 어댑터는 fetch를 빈 리스트로(조회는 sync 서비스 담당), ship은 Cafe24, accept/cancel은 Cafe24 상태 API로 한다.

- [ ] **Step 1: 실패 테스트 작성**

`Cafe24GmarketOrderAdapterTest.java`:
```java
package com.sbshop.agent.core.application.order.adapter;

import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.order.service.Cafe24ShipmentService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24GmarketOrderAdapterTest {

	@Mock Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock Cafe24ShipmentService cafe24ShipmentService;
	@Mock MarketCredential credential;
	@Mock Order order;

	@Test
	@DisplayName("marketType은 GMARKET")
	void marketType() {
		var adapter = new Cafe24GmarketOrderAdapter(cafe24OrderApiPort, cafe24ShipmentService);
		assertThat(adapter.getMarketType()).isEqualTo(MarketType.GMARKET);
	}

	@Test
	@DisplayName("acceptOrders는 Cafe24 acceptOrder(marketOrderNo) 호출")
	void accept() {
		org.mockito.Mockito.when(order.getMarketOrderNo()).thenReturn("O777");
		var adapter = new Cafe24GmarketOrderAdapter(cafe24OrderApiPort, cafe24ShipmentService);
		adapter.acceptOrders(credential, order);
		verify(cafe24OrderApiPort).acceptOrder("O777");
	}

	@Test
	@DisplayName("cancelOrder는 Cafe24 cancelOrder(marketOrderNo) 호출")
	void cancel() {
		org.mockito.Mockito.when(order.getMarketOrderNo()).thenReturn("O777");
		var adapter = new Cafe24GmarketOrderAdapter(cafe24OrderApiPort, cafe24ShipmentService);
		adapter.cancelOrder(credential, order);
		verify(cafe24OrderApiPort).cancelOrder("O777");
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests '*Cafe24GmarketOrderAdapterTest*'`
Expected: FAIL(컴파일) — `Cafe24GmarketOrderAdapter` 미존재.

- [ ] **Step 3: 신규 어댑터 작성 + 기존 삭제**

`Cafe24GmarketOrderAdapter.java` 신규:
```java
package com.sbshop.agent.core.application.order.adapter;

import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.application.order.service.Cafe24ShipmentService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * G마켓(GMARKET) 주문 어댑터 — Cafe24 주문 API 기반(ESM+ Selenium 대체).
 * 조회는 Cafe24OrderSyncService가 담당하므로 fetchOrders는 미사용(빈 리스트).
 * 발주확인/취소는 Cafe24 주문상태 API, 송장은 Cafe24 shipments API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24GmarketOrderAdapter implements MarketOrderPort {

	private final Cafe24OrderApiPort cafe24OrderApiPort;
	private final Cafe24ShipmentService cafe24ShipmentService;

	@Override
	public MarketType getMarketType() {
		return MarketType.GMARKET;
	}

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential, LocalDate fromDate, LocalDate toDate) {
		// G마켓 조회는 Cafe24OrderSyncService(order_place_id=gmarket)가 담당 — 여기선 미사용.
		return List.of();
	}

	@Override
	public void shipOrder(MarketCredential credential, Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		cafe24ShipmentService.ship(order, trackingNo, carrier);
	}

	@Override
	public void acceptOrders(MarketCredential credential, Order order) {
		cafe24OrderApiPort.acceptOrder(order.getMarketOrderNo());
	}

	@Override
	public void cancelOrder(MarketCredential credential, Order order) {
		cafe24OrderApiPort.cancelOrder(order.getMarketOrderNo());
	}
}
```
그리고 `EsmplusOrderAdapter.java` 파일 삭제:
```bash
git rm backend/core/src/main/java/com/sbshop/agent/core/application/order/adapter/EsmplusOrderAdapter.java
```
> 주의: 삭제 후 `EsmplusOrderApiPort`/`EsmplusStatusMapper` 참조가 어댑터에서 사라진다. 이 클래스들은 아직 `EsmplusOrderSyncService` 등이 참조하므로 **이 태스크에서 삭제하지 않는다**(Task 5). `EsmplusOrderAdapter`가 제공하던 `parseOrdersFromJson`/`parseSingleOrder`/`mapSiteIdToMarketType` public 헬퍼를 다른 곳이 쓰는지 확인: `grep -rn "parseOrdersFromJson\|mapSiteIdToMarketType" backend --include=*.java | grep -v EsmplusOrderAdapter` — 참조 있으면 STOP·보고.

- [ ] **Step 4: 테스트 통과 + core 컴파일**

Run: `cd backend && ./gradlew :core:test --tests '*Cafe24GmarketOrderAdapterTest*' :core:compileJava`
Expected: PASS / 컴파일 성공. (EsmplusOrderAdapter를 참조하던 테스트가 있으면 이 태스크에서 정리.)

- [ ] **Step 5: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/order/adapter/Cafe24GmarketOrderAdapter.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/order/adapter/Cafe24GmarketOrderAdapterTest.java
git add -u backend/core/src/main/java/com/sbshop/agent/core/application/order/adapter/EsmplusOrderAdapter.java
git commit -m "feat(SP-E): G마켓 어댑터 Cafe24화 — EsmplusOrderAdapter(Selenium) → Cafe24GmarketOrderAdapter

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 옥션 어댑터 confirm/cancel 구현

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/adapter/Cafe24AuctionOrderAdapter.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/order/adapter/Cafe24AuctionOrderAdapterTest.java`

**Interfaces:**
- Consumes: `Cafe24OrderApiPort.acceptOrder/cancelOrder`(Task 1).
- Produces: `Cafe24AuctionOrderAdapter` (AUCTION) with functional `acceptOrders`/`cancelOrder`.

- [ ] **Step 1: 실패 테스트 작성**

`Cafe24AuctionOrderAdapterTest.java`:
```java
package com.sbshop.agent.core.application.order.adapter;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.order.service.Cafe24ShipmentService;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24AuctionOrderAdapterTest {

	@Mock Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock Cafe24ShipmentService cafe24ShipmentService;
	@Mock MarketCredential credential;
	@Mock Order order;

	@Test
	@DisplayName("acceptOrders는 Cafe24 acceptOrder(marketOrderNo) 호출")
	void accept() {
		when(order.getMarketOrderNo()).thenReturn("A55");
		var adapter = new Cafe24AuctionOrderAdapter(cafe24ShipmentService, cafe24OrderApiPort);
		adapter.acceptOrders(credential, order);
		verify(cafe24OrderApiPort).acceptOrder("A55");
	}

	@Test
	@DisplayName("cancelOrder는 Cafe24 cancelOrder(marketOrderNo) 호출")
	void cancel() {
		when(order.getMarketOrderNo()).thenReturn("A55");
		var adapter = new Cafe24AuctionOrderAdapter(cafe24ShipmentService, cafe24OrderApiPort);
		adapter.cancelOrder(credential, order);
		verify(cafe24OrderApiPort).cancelOrder("A55");
	}
}
```
(생성자 인자 순서는 구현 Step에서 맞춘다 — `@RequiredArgsConstructor`가 필드 선언 순서대로 생성.)

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests '*Cafe24AuctionOrderAdapterTest*'`
Expected: FAIL(컴파일) — 생성자에 `Cafe24OrderApiPort` 없음, `cancelOrder` 미구현.

- [ ] **Step 3: 어댑터 수정**

`Cafe24AuctionOrderAdapter.java` — 필드에 `Cafe24OrderApiPort` 추가, acceptOrders 교체, cancelOrder 구현:
```java
	private final Cafe24ShipmentService cafe24ShipmentService;
	private final com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort cafe24OrderApiPort;
```
```java
	@Override
	public void acceptOrders(MarketCredential credential, Order order) {
		cafe24OrderApiPort.acceptOrder(order.getMarketOrderNo());
	}

	@Override
	public void cancelOrder(MarketCredential credential, Order order) {
		cafe24OrderApiPort.cancelOrder(order.getMarketOrderNo());
	}
```
(기존 no-op `log.debug` acceptOrders 본문을 위로 교체. `@RequiredArgsConstructor`이므로 필드 선언 순서 = 생성자 인자 순서: `(Cafe24ShipmentService, Cafe24OrderApiPort)` — 테스트와 일치.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests '*Cafe24AuctionOrderAdapterTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/order/adapter/Cafe24AuctionOrderAdapter.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/order/adapter/Cafe24AuctionOrderAdapterTest.java
git commit -m "feat(SP-E): 옥션 어댑터 발주확인/취소를 Cafe24 주문상태 API로 구현

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: 취소 마켓전파 배선 (G마켓/옥션 한정)

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/OrderService.java` (`cancelOrder` `:136-156`)
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/order/OrderServiceCancelPropagationTest.java`

**Interfaces:**
- Consumes: `MarketplaceShippingService.cancelOrderToMarketplace(Order)` (기존, `:114-125` — 내부에서 `getPort(marketType).cancelOrder(cred, order)`). G마켓/옥션 어댑터가 Task 2/3에서 cancelOrder 구현됨.

- [ ] **Step 1: 실패 테스트 작성**

`OrderServiceCancelPropagationTest.java`: `OrderService`를 Mockito로 구성(orderRepository·orderLineItemRepository·marketplaceShippingService 등 의존성 mock — 기존 OrderService 테스트가 있으면 그 준비 패턴 재사용). 검증:
```java
// GMARKET 주문 취소 → 마켓 전파
when(order.getMarketType()).thenReturn(MarketType.GMARKET);
when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
when(orderLineItemRepository.findByOrderId(any())).thenReturn(List.of());
service.cancelOrder(1L);
verify(marketplaceShippingService).cancelOrderToMarketplace(order);

// COUPANG 주문 취소 → 마켓 전파 안 함(회귀 불변)
when(order.getMarketType()).thenReturn(MarketType.COUPANG);
service.cancelOrder(1L);
verify(marketplaceShippingService, never()).cancelOrderToMarketplace(order);
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests '*OrderServiceCancelPropagationTest*'`
Expected: FAIL — 현재 `cancelOrder`는 `cancelOrderToMarketplace`를 호출하지 않음.

- [ ] **Step 3: cancelOrder에 G마켓/옥션 전파 추가**

`OrderService.cancelOrder`(`:136`)에서, 주문 로드 직후·로컬 상태변경 전에 마켓 전파(실패 시 전파, confirm과 동일 패턴):
```java
		Order order = orderRepository.findById(id)
			.orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));

		// G마켓/옥션은 Cafe24 주문상태 API로 실제 취소를 전파(그 외 마켓은 현행 로컬-only 유지).
		MarketType mt = order.getMarketType();
		if (mt == MarketType.GMARKET || mt == MarketType.AUCTION) {
			try {
				marketplaceShippingService.cancelOrderToMarketplace(order);
			} catch (Exception e) {
				log.error("마켓 주문취소 전파 실패: order={} ({}): {}", id, order.getMarketOrderNo(), e.getMessage());
				throw new RuntimeException("마켓 주문취소 실패: " + e.getMessage(), e);
			}
		}

		// 라인아이템 배송상태를 CANCELED로 변경
		List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
		...(기존 로컬 CANCELED 로직 유지)...
```
(`OrderService`에 `MarketType` import가 없으면 추가. `marketplaceShippingService` 필드는 이미 confirm 경로가 쓰므로 존재.)

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests '*OrderServiceCancelPropagationTest*'`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/order/OrderService.java \
        backend/core/src/test/java/com/sbshop/agent/core/application/order/OrderServiceCancelPropagationTest.java
git commit -m "feat(SP-E): 주문 취소를 G마켓/옥션 한정 Cafe24로 전파 (그 외 마켓 현행 유지)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: ESM+ Selenium 청산 (주입·엔드포인트·클래스 삭제)

**Files:**
- Modify: `backend/worker/src/main/java/com/sbshop/agent/worker/scheduler/OrderSyncScheduler.java` (주입 `:32`, TODO 주석 `:38,53,83,98,113`)
- Modify: `backend/api/src/main/java/com/sbshop/agent/api/controller/OrderSyncController.java` (주입 `:43,51`, 엔드포인트 `/esmplus/test` `:249`, `/esmplus/scrape` `:273`)
- Delete: `EsmplusOrderSyncService`, `EsmplusOrderApiPortImpl`, `EsmplusOrderApiPort`, `EsmplusScraper`, `EsmplusDriverFactory` (경로는 grep으로 확인)
- Modify(조건부): `backend/infrastructure/build.gradle` (selenium)

**Interfaces:** 없음(삭제/정리 전용).

> **순서 규율(컴파일 유지):** ①주입·엔드포인트·주석 제거 → ②각 클래스가 unreferenced인지 grep 확인 → ③파일 삭제 → ④컴파일. 참조가 남으면 삭제 대신 STOP·보고.

- [ ] **Step 1: 스케줄러 정리**

`OrderSyncScheduler.java`:
- `private final EsmplusOrderSyncService esmplusOrderSyncService;` (line ~32) 필드 + import 제거(호출 없음).
- `// TODO: 리팩토링 완료 후 활성화` 주석 5개(EMAIL/COUPANG/SMART_STORE/ELEVEN_STREET/COUPANG_SETTLEMENT 스케줄러 위) 제거 — 이미 활성 상태이므로 오해 소지 제거.

- [ ] **Step 2: 컨트롤러 정리**

`OrderSyncController.java`:
- `private final EsmplusOrderSyncService esmplusOrderSyncService;` (line ~43) + `private final EsmplusScraper esmplusScraper;` (line ~51) 필드·import 제거.
- 디버그 엔드포인트 `/esmplus/test`(~:249)와 `/esmplus/scrape`(~:273) 핸들러 메서드 전체 제거(프론트 미매핑, esmplusScraper 유일 사용처).

- [ ] **Step 3: unreferenced 확인 (삭제 전 필수)**

Run 각 클래스별:
```bash
cd /Users/jasonair/Projects/sbshop-agent
for c in EsmplusOrderSyncService EsmplusOrderApiPortImpl EsmplusOrderApiPort EsmplusScraper EsmplusDriverFactory; do
  echo "== $c =="; grep -rn "$c" backend --include="*.java" | grep -v "class $c\|interface $c"
done
```
기대: 각 클래스가 자기 정의 파일 외에서 참조되지 않음(또는 서로만 참조 — 함께 삭제 가능). 외부(비-Esmplus) 참조가 있으면 STOP·보고.
또 `EsmplusStatusMapper` 참조 확인: `grep -rn "EsmplusStatusMapper" backend --include=*.java` — Cafe24OrderSyncService 등이 쓰면 **유지**, EsmplusOrderAdapter(삭제됨)만 썼으면 함께 삭제.

- [ ] **Step 4: 클래스 삭제**

Step 3에서 확인된 unreferenced 클래스 파일들을 `git rm`. 서로만 참조하는 Esmplus* 묶음(예: EsmplusOrderApiPortImpl → EsmplusDriverFactory)은 함께 삭제하면 상호참조가 동시에 사라져 컴파일 유지.
```bash
git rm <각 클래스 파일 경로>
```

- [ ] **Step 5: selenium 의존성 조건부 정리**

```bash
grep -rn "org.openqa.selenium" backend/infrastructure/src/main --include="*.java" | grep -v esmplus
```
결과가 있으면(예: `GsiExpressScraperAdapter`) selenium gradle 의존성 **유지**. 결과가 비면 `infrastructure/build.gradle`의 `selenium-java` 라인 제거.

- [ ] **Step 6: 전체 컴파일·컨텍스트 확인**

Run: `cd backend && ./gradlew :core:compileJava :infrastructure:compileJava :api:compileJava :worker:compileJava :core:test`
Expected: 전 모듈 컴파일 성공, core 테스트 PASS(pre-existing `SmartStoreOrderFetchFailureTest` 제외). GMARKET `MarketOrderPort` 빈이 `Cafe24GmarketOrderAdapter` 하나만 남았는지(중복 빈 없음) 확인.

- [ ] **Step 7: 커밋**

```bash
git add -A backend
git commit -m "refactor(SP-E): ESM+ Selenium 잔재 전면 청산 — 클래스·주입·디버그 엔드포인트·TODO 주석 제거

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: 통합 게이트 + 라이브 확인 준비

**Files:** 없음(검증 전용).

- [ ] **Step 1: 백엔드 전체 게이트**

Run: `cd backend && ./gradlew :core:test :infrastructure:test --tests '*Cafe24*' --tests '*OrderService*' --tests '*Adapter*' :api:compileJava :worker:compileJava`
Expected: SP-E 신규/변경 테스트 PASS, 전 모듈 컴파일. pre-existing 무관 실패(core `SmartStoreOrderFetchFailureTest`, infra `ImageDownloadServiceCharacterizationTest` SIGABRT)는 `git diff --name-only <base>..HEAD`로 diff 밖임을 확인해 기록.

- [ ] **Step 2: 잔재 부재 확인**

Run: `grep -rn "Esmplus\|EsmplusScraper\|RemoteWebDriver" backend/core/src/main backend/api/src/main backend/worker/src/main --include="*.java"` → ESM+ 관련 참조 0(통관 스크래퍼의 selenium은 별개).

- [ ] **Step 3: 라이브 확인 체크리스트 문서화**

배포 후(사용자 허가): G마켓·옥션 실주문 발주확인/취소 → Cafe24 반영 + 원마켓 상태 변경 확인. **Cafe24 accept/cancel 실 상태코드(`N20`/`C40`)·필드명·바디형태 확정** — 틀리면 4xx 실패로 표면화되므로 로그 확인 후 상수 교정. 취소가 G마켓/옥션만 전파되고 쿠팡/스마트스토어는 로컬-only 유지되는지.

---

## Self-Review 체크

- **Spec 커버리지:** Cafe24 accept/cancel API(Task 1)·G마켓 어댑터 교체(Task 2)·옥션 어댑터 구현(Task 3)·취소 전파 G마켓/옥션 한정(Task 4)·Selenium 청산(Task 5)·게이트(Task 6). DDL 없음. ✅
- **Placeholder:** 코드/명령/기대출력 구체화. 상태코드 `N20`/`C40`은 best-known 상수로 명시(라이브 검증 대상 — 플레이스홀더 아님, 동작하는 값). 삭제 대상은 grep-확인 후 삭제로 안전장치. ✅
- **타입 일관성:** 포트 `acceptOrder(String)`/`cancelOrder(String)` — Task 1 정의, Task 2/3 어댑터 사용 일치. 어댑터 생성자: GMARKET `(Cafe24OrderApiPort, Cafe24ShipmentService)`, AUCTION `(Cafe24ShipmentService, Cafe24OrderApiPort)` — 각 테스트와 일치(주의: 순서 다름, `@RequiredArgsConstructor` 필드순). `cancelOrderToMarketplace(Order)` — 기존 시그니처, Task 4 사용. ✅
- **컴파일 원자성:** Task 2가 EsmplusOrderAdapter만 삭제(Esmplus* 포트/서비스는 Task 5까지 유지)해 중간 컴파일 유지. Task 5는 주입 제거→grep→삭제 순서로 안전. ✅
- **미검증 API 주의:** Cafe24 상태코드/바디는 Task 1·6에 라이브 검증 명시, 실패 표면화 규율 준수.
