# 송장 출처 표시 (📧 이메일 / ✍ 수동) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 화면의 송장이 iHerb 메일이 확인해 준 진짜 송장인지, 사람·마켓이 넣은 진위 불명 값인지 한눈에 구분한다.

**Architecture:** `sb_shipment`에 `tracking_source` 한 칸을 두고, 세 쓰기 경로(이메일·수동·마켓 동기화)가 자기 출처를 기록한다. 출처의 뜻은 "누가 처음 썼나"가 아니라 **"무엇이 이 값을 확인했나"** 이므로, 이메일은 값을 바꾸지 않는 경우에도 `EMAIL`로 승격시킨다. 화면은 3종을 두 아이콘으로 접어 송장 입력칸 왼쪽에 보여준다.

**Tech Stack:** Java 21 · Spring Boot 3.5 · JPA · QueryDSL · React 19 + TypeScript(Vite)

## Global Constraints

- **스키마는 수동 관리다(Flyway 없음).** 엔티티에 컬럼을 추가하면 운영 DB에 직접 DDL을 실행해야 한다(Task 5).
- 기존 배지 줄(`배지 ↔ 전송` 한 줄)은 건드리지 않는다 — 2026-08-07에 셀 높이를 늘리지 않으려 합친 결정이다.
- **아이콘 자리는 고정폭으로 예약한다.** 아이콘이 없어도 입력칸 시작 위치가 흔들리면 안 된다(사용자 요구).
- 과거 데이터는 소급 판정하지 않는다 — `tracking_source`가 `null`이면 아이콘 없음.
- 프론트 게이트는 `npx tsc -p tsconfig.app.json --noEmit` + `npm run build`다(루트 tsconfig는 references-only).
- 백엔드 회귀는 `cd backend && ./gradlew test` 전량 통과여야 한다(현재 1017건).

## 파일 구조

| 파일 | 책임 |
|---|---|
| `core/.../domain/order/enums/TrackingSource.java` (신규) | 출처 3종 정의 |
| `core/.../domain/order/Shipment.java` (수정) | `trackingSource` 필드 + `applyTrackingSource` |
| `core/.../application/order/service/LineItemShippingWriter.java` (수정) | 이메일·수동 경로가 출처를 싣는 통로 |
| `core/.../application/order/service/OrderShipmentUpsertService.java` (수정) | 마켓 채택 시 `MARKET` 기록 |
| `worker/.../service/EmailFetcherService.java` (수정) | `EMAIL` 기록 + **값이 같아도 승격** |
| `core/.../application/order/service/OrderService.java`, `OrderShipProcessor.java` (수정) | 사람이 넣는 경로에 `MANUAL` |
| `frontend/src/api/orderApi.ts` (수정) | `ShipmentDto.trackingSource` 타입 |
| `frontend/src/pages/OrderGrid.tsx` (수정) | 고정폭 아이콘 슬롯 |

---

### Task 1: 도메인 — 출처 값과 기록 수단

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/TrackingSource.java`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/domain/order/Shipment.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/domain/order/ShipmentTrackingSourceTest.java`

**Interfaces:**
- Consumes: 없음(첫 작업)
- Produces: `TrackingSource.EMAIL|MANUAL|MARKET` · `Shipment.applyTrackingSource(TrackingSource)` · `Shipment.getTrackingSource()`

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.TrackingSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 송장 출처는 "누가 처음 썼나"가 아니라 <b>"무엇이 이 값을 확인했나"</b>다.
 * 화면은 이 값으로 📧(이메일이 확인한 진짜)와 ✍(진위 불명)를 가른다.
 */
class ShipmentTrackingSourceTest {

	private Shipment shipment() {
		return Shipment.builder().orderId(1L).marketShipmentNo("S-1").build();
	}

	@Test
	@DisplayName("출처를 기록한다")
	void recordsSource() {
		Shipment s = shipment();

		s.applyTrackingSource(TrackingSource.EMAIL);

		assertThat(s.getTrackingSource()).isEqualTo(TrackingSource.EMAIL);
	}

	@Test
	@DisplayName("null은 '판단 없음'이라 기존 출처를 지우지 않는다")
	void nullKeepsExisting() {
		Shipment s = shipment();
		s.applyTrackingSource(TrackingSource.EMAIL);

		s.applyTrackingSource(null);

		assertThat(s.getTrackingSource()).isEqualTo(TrackingSource.EMAIL);
	}

	@Test
	@DisplayName("사람이 나중에 덮어쓰면 출처도 사람으로 바뀐다 — 진짜가 가송장으로 바뀐 사실을 숨기지 않는다")
	void manualOverwriteDowngrades() {
		Shipment s = shipment();
		s.applyTrackingSource(TrackingSource.EMAIL);

		s.applyTrackingSource(TrackingSource.MANUAL);

		assertThat(s.getTrackingSource()).isEqualTo(TrackingSource.MANUAL);
	}

	@Test
	@DisplayName("기록한 적 없으면 null — 과거 데이터는 아이콘 없이 둔다")
	void defaultsToNull() {
		assertThat(shipment().getTrackingSource()).isNull();
	}
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd backend && ./gradlew :core:test --tests "*ShipmentTrackingSourceTest*"`
Expected: 컴파일 실패 — `cannot find symbol: TrackingSource`

- [ ] **Step 3: enum을 만든다**

`backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/TrackingSource.java`:

```java
package com.sbshop.agent.core.domain.order.enums;

/**
 * 송장이 <b>무엇에 의해 확인됐는가</b>.
 *
 * <p>"누가 처음 썼나"가 아니다 — 이메일이 같은 값으로 확인해 주면 출처는 {@link #EMAIL}로 승격된다.
 * 화면은 이 값으로 📧(이메일이 확인한 진짜 송장)와 ✍(사람·마켓이 넣은 진위 불명 값)를 가른다.
 */
public enum TrackingSource {
	/** iHerb 발송메일이 준(또는 같은 값으로 확인해 준) 송장 — 진실의 출처다. */
	EMAIL,
	/** 관리자가 화면에서 직접 입력했다. 마감을 맞추려 넣는 가송장이 여기 해당한다. */
	MANUAL,
	/** 마켓 동기화가 마켓 값을 채택했다(우리가 송장을 모를 때만, D-148). */
	MARKET
}
```

- [ ] **Step 4: Shipment에 필드와 기록 수단을 더한다**

`Shipment.java` — `manualFixRequired` 필드 **아래**에 추가:

```java
	/**
	 * 이 송장을 <b>무엇이 확인했는가</b>(📧 이메일 / ✍ 사람·마켓). {@code null}은 출처 미기록 —
	 * 이 기능 이전에 쌓인 과거 데이터다. 소급 판정하지 않고 화면에서 아이콘을 띄우지 않는다.
	 */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "tracking_source", length = 20)
	private TrackingSource trackingSource;
```

같은 파일 `applyMarketTracking` 메서드 아래에 추가:

```java
	/**
	 * 송장 출처를 기록한다. {@code null}은 "이번 쓰기는 출처를 판단하지 않음"이라 기존 값을 지키지 않는다.
	 *
	 * <p>승격도 강등도 모두 허용한다 — 이메일이 확인하면 {@code EMAIL}로 올라가고, 사람이 덮어쓰면
	 * {@code MANUAL}로 내려간다. 진짜가 가송장으로 바뀐 사실을 화면이 숨기면 안 된다.
	 */
	public void applyTrackingSource(TrackingSource source) {
		if (source == null) {
			return;
		}
		this.trackingSource = source;
	}
```

import 추가: `import com.sbshop.agent.core.domain.order.enums.TrackingSource;`

- [ ] **Step 5: 통과를 확인한다**

Run: `cd backend && ./gradlew :core:test --tests "*ShipmentTrackingSourceTest*"`
Expected: PASS (4건)

- [ ] **Step 6: 커밋**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/domain/order/enums/TrackingSource.java \
        backend/core/src/main/java/com/sbshop/agent/core/domain/order/Shipment.java \
        backend/core/src/test/java/com/sbshop/agent/core/domain/order/ShipmentTrackingSourceTest.java
git commit -m "feat(order): 송장 출처(TrackingSource) 도메인 추가

출처는 '누가 처음 썼나'가 아니라 '무엇이 이 값을 확인했나'다.
null은 판단 없음이라 기존 값을 지우지 않고, 승격·강등을 모두 허용한다."
```

---

### Task 2: 세 쓰기 경로가 자기 출처를 기록한다

**Files:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/LineItemShippingWriter.java`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderShipmentUpsertService.java:53-62`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderService.java:350,491,525`
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/service/OrderShipProcessor.java:102`
- Modify: `backend/worker/src/main/java/com/sbshop/agent/worker/service/EmailFetcherService.java:481,501`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/order/service/TrackingSourceWritePathTest.java`

**Interfaces:**
- Consumes: `TrackingSource` · `Shipment.applyTrackingSource` (Task 1)
- Produces: `LineItemShippingWriter.applyShipping(OrderLineItem, ShippingData, TrackingSource)` — 기존 2-인자 버전은 **출처를 건드리지 않는다**(호출부·테스트 무변경)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

```java
package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.TrackingSource;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 세 쓰기 경로가 각자의 출처를 남기는지 — 이 기록이 없으면 화면이 진짜/가짜를 가릴 근거를 잃는다.
 */
class TrackingSourceWritePathTest {

	private final ShipmentRepository shipmentRepository = mock(ShipmentRepository.class);
	private final OrderLineItemRepository lineItemRepository = mock(OrderLineItemRepository.class);

	private Shipment shipment() {
		return Shipment.builder().orderId(1L).marketShipmentNo("S-1").build();
	}

	private OrderLineItem item(Long shipmentId) {
		return OrderLineItem.builder().orderId(1L).quantity(1).shipmentId(shipmentId)
			.shippingData(ShippingData.builder().build()).build();
	}

	@Test
	@DisplayName("출처를 지정하면 배송에 기록된다")
	void writerRecordsGivenSource() {
		Shipment shipment = shipment();
		when(shipmentRepository.findById(9L)).thenReturn(Optional.of(shipment));
		when(lineItemRepository.findByShipmentId(9L)).thenReturn(List.of());
		LineItemShippingWriter writer = new LineItemShippingWriter(shipmentRepository, lineItemRepository);

		writer.applyShipping(item(9L),
			ShippingData.builder().trackingNo("424438293101")
				.shippingCarrier(ShippingCarrier.CJ_LOGISTICS).build(),
			TrackingSource.EMAIL);

		assertThat(shipment.getTrackingSource()).isEqualTo(TrackingSource.EMAIL);
	}

	@Test
	@DisplayName("출처를 지정하지 않은 기존 호출은 출처를 건드리지 않는다 — 호출부를 한꺼번에 고치지 않아도 안전하다")
	void writerLeavesSourceUntouchedWithoutArgument() {
		Shipment shipment = shipment();
		shipment.applyTrackingSource(TrackingSource.EMAIL);
		when(shipmentRepository.findById(9L)).thenReturn(Optional.of(shipment));
		when(lineItemRepository.findByShipmentId(9L)).thenReturn(List.of());
		LineItemShippingWriter writer = new LineItemShippingWriter(shipmentRepository, lineItemRepository);

		writer.applyShipping(item(9L),
			ShippingData.builder().trackingNo("111122223333").build());

		assertThat(shipment.getTrackingSource()).isEqualTo(TrackingSource.EMAIL);
	}

	@Test
	@DisplayName("마켓 값을 채택할 때만 MARKET으로 기록한다 — 우리 송장이 있으면 채택하지 않으므로 출처도 그대로다")
	void upsertRecordsMarketOnlyWhenAdopted() {
		Shipment adopted = shipment();
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(1L, "S-1"))
			.thenReturn(Optional.of(adopted));
		when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
		OrderShipmentUpsertService service =
			new OrderShipmentUpsertService(shipmentRepository, lineItemRepository);

		service.upsertShipment(1L, MarketShipmentDto.builder()
			.marketShipmentNo("S-1").trackingNo("6079990333504").build());

		assertThat(adopted.getTrackingSource()).isEqualTo(TrackingSource.MARKET);
	}

	@Test
	@DisplayName("우리 송장이 이미 있으면 마켓 값은 채택되지 않고 출처도 바뀌지 않는다")
	void upsertKeepsSourceWhenWeAlreadyKnowTracking() {
		Shipment ours = shipment();
		ours.applyTracking("424438293101", ShippingCarrier.CJ_LOGISTICS, null);
		ours.applyTrackingSource(TrackingSource.EMAIL);
		when(shipmentRepository.findByOrderIdAndMarketShipmentNo(1L, "S-1"))
			.thenReturn(Optional.of(ours));
		when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
		OrderShipmentUpsertService service =
			new OrderShipmentUpsertService(shipmentRepository, lineItemRepository);

		service.upsertShipment(1L, MarketShipmentDto.builder()
			.marketShipmentNo("S-1").trackingNo("6079990333504").build());

		assertThat(ours.getTrackingSource()).isEqualTo(TrackingSource.EMAIL);
	}
}
```

> 두 서비스의 생성자는 모두 `(ShipmentRepository, OrderLineItemRepository)` 순서다(확인함).

- [ ] **Step 2: 실패를 확인한다**

Run: `cd backend && ./gradlew :core:test --tests "*TrackingSourceWritePathTest*"`
Expected: 컴파일 실패 — 3-인자 `applyShipping`이 없다

- [ ] **Step 3: 통로에 출처를 태운다**

`LineItemShippingWriter.java` — 기존 `applyShipping`을 다음으로 바꾼다:

```java
	@Transactional
	public void applyShipping(OrderLineItem item, ShippingData data) {
		applyShipping(item, data, null);
	}

	/**
	 * @param source 이 쓰기가 확인한 출처. {@code null}이면 출처를 건드리지 않는다
	 *               (출처를 판단할 수 없는 호출자를 위한 경로).
	 */
	@Transactional
	public void applyShipping(OrderLineItem item, ShippingData data, TrackingSource source) {
		item.applyShippingData(data);
		orderLineItemRepository.save(item);
		writeThrough(item, data.getTrackingNo(), data.getShippingCarrier(),
			data.getTrackingSentToMarket(), source);
	}
```

같은 파일의 `writeThrough` 시그니처에 `TrackingSource source`를 더하고, `shipment.applyTracking(...)` 바로 아래에 한 줄 추가:

```java
		shipment.applyTracking(trackingNo, carrier, sentToMarket);
		shipment.applyTrackingSource(source);
		shipmentRepository.save(shipment);
```

`writeThrough`를 부르는 다른 자리가 있으면 `null`을 넘겨 종전 동작을 유지한다.

- [ ] **Step 4: 마켓 채택 지점에 MARKET을 기록한다**

`OrderShipmentUpsertService.java` — `if (!shipment.hasOwnTracking())` 블록 안, `applyTracking` 바로 아래:

```java
		if (!shipment.hasOwnTracking()) {
			shipment.applyTracking(trackingNo, meaningful ? dto.getCarrier() : null, ownedByMarket);
			// 마켓 값을 채택한 경우에만 출처가 마켓이다. 우리 송장이 있으면 채택하지 않으므로 출처도 그대로다.
			if (meaningful) {
				shipment.applyTrackingSource(TrackingSource.MARKET);
			}
		}
```

- [ ] **Step 5: 사람이 넣는 경로에 MANUAL을 붙인다**

세 곳 모두 `applyShipping(...)` 호출 끝에 `, TrackingSource.MANUAL`을 더한다:
- `OrderService.java:350` (`updateShippingInfo` — 그리드 인라인 편집)
- `OrderService.java:491` (`processShipping` — 발송처리)
- `OrderService.java:525` (`updateTrackingInfo` — 송장 수정)
- `OrderShipProcessor.java:102` (일괄 발송처리)

- [ ] **Step 6: 이메일 경로에 EMAIL을 붙인다**

`EmailFetcherService.java:481`·`:501`의 두 `applyShipping(...)` 호출 끝에 `, TrackingSource.EMAIL`을 더한다.

- [ ] **Step 7: 통과를 확인한다**

Run: `cd backend && ./gradlew :core:test --tests "*TrackingSourceWritePathTest*" && ./gradlew test`
Expected: 신규 4건 PASS · 전체 회귀 실패 0

- [ ] **Step 8: 커밋**

```bash
git add -A
git commit -m "feat(order): 세 쓰기 경로가 송장 출처를 기록한다

이메일=EMAIL, 사람 입력=MANUAL, 마켓 채택=MARKET.
출처를 지정하지 않은 기존 호출은 출처를 건드리지 않아 호출부를 한꺼번에 고칠 필요가 없다."
```

---

### Task 3: 이메일 승격 — 값이 같아도 EMAIL로

**Files:**
- Modify: `backend/worker/src/main/java/com/sbshop/agent/worker/service/EmailFetcherService.java` (`marketHasInvoice && sameTracking` 분기)
- Test: `backend/worker/src/test/java/com/sbshop/agent/worker/service/EmailTrackingSourcePromotionTest.java`

**Interfaces:**
- Consumes: `TrackingSource` (Task 1) · `LineItemShippingWriter.applyShipping(item, data, source)` (Task 2)
- Produces: 없음(행위 변경)

- [ ] **Step 1: 실패하는 테스트를 쓴다**

기존 `EmailFetcherMarketSyncTruthTest`의 하네스를 그대로 본뜬다(같은 시나리오를 이미 다룬다).

```java
package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.service.LineItemShippingWriter;
import com.sbshop.agent.core.application.order.service.MarketShippingResult;
import com.sbshop.agent.core.application.order.service.MarketplaceShippingService;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.enums.TrackingSource;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 마켓이 먼저 알려준 진짜 송장은 동기화가 채택해 {@code MARKET}으로 기록된다. 그 뒤 iHerb 메일이
 * 도착해도 <b>값이 같으면</b> 이메일 경로가 {@code sameTracking} 분기로 빠져 값을 쓰지 않는다 —
 * 승격을 놓치면 진짜 송장이 영영 ✍(진위 불명)로 남는다. 이 기능에서 가장 놓치기 쉬운 경로다.
 */
class EmailTrackingSourcePromotionTest {

	private static final String REAL = "315399497965";

	private ShipmentRepository shipmentRepository;
	private OrderLineItemRepository lineItemRepository;
	private MarketplaceShippingService shippingService;
	private EmailFetcherService service;

	@BeforeEach
	void setUp() {
		shipmentRepository = mock(ShipmentRepository.class);
		lineItemRepository = mock(OrderLineItemRepository.class);
		shippingService = mock(MarketplaceShippingService.class);
		when(lineItemRepository.save(any(OrderLineItem.class))).thenAnswer(inv -> inv.getArgument(0));
		when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
		when(lineItemRepository.findByShipmentId(any())).thenReturn(List.of());
		when(shippingService.sendTrackingToMarketplace(any(), anyBoolean()))
			.thenReturn(MarketShippingResult.ofSent());

		OrderRepository orderRepository = mock(OrderRepository.class);
		when(orderRepository.findById(any())).thenReturn(Optional.empty());
		service = new EmailFetcherService(null, null, lineItemRepository, orderRepository, shippingService,
			mock(com.sbshop.agent.core.application.actionlog.ActionLogService.class), null);
		ReflectionTestUtils.setField(service, "shippingWriter",
			new LineItemShippingWriter(shipmentRepository, lineItemRepository));
	}

	private OrderEmailParser.IherbShipmentData email() {
		OrderEmailParser.IherbShipmentData data = mock(OrderEmailParser.IherbShipmentData.class);
		when(data.getOrderNo()).thenReturn("344143953");
		when(data.getTrackingNo()).thenReturn(REAL);
		when(data.getCarrier()).thenReturn("롯데택배");
		return data;
	}

	@Test
	@DisplayName("값이 같아도 이메일이 확인하면 출처가 EMAIL로 승격된다")
	void promotesToEmailWhenValueUnchanged() {
		// 마켓이 먼저 알려준 진짜 송장 — 값은 이메일과 같고 출처만 MARKET이다.
		Shipment shipment = Shipment.builder().orderId(1L).marketShipmentNo("S-1").build();
		shipment.applyTracking(REAL, ShippingCarrier.LOTTE_LOGISTICS, true);
		shipment.applyMarketTracking(REAL);
		shipment.applyTrackingSource(TrackingSource.MARKET);
		ReflectionTestUtils.setField(shipment, "id", 12L);
		when(shipmentRepository.findById(12L)).thenReturn(Optional.of(shipment));

		OrderLineItem item = OrderLineItem.builder()
			.orderId(1L).quantity(1).shipmentId(12L)
			.sourcingData(SourcingData.builder().sourcingOrderNo("344143953").build())
			.shippingData(ShippingData.builder()
				.trackingNo(REAL).shippingCarrier(ShippingCarrier.LOTTE_LOGISTICS)
				.shippingStatus(ShippingStatus.SHIPPED).build())
			.build();
		when(lineItemRepository.findBySourcingData_SourcingOrderNo("344143953")).thenReturn(List.of(item));

		service.processIherbShipment(email());

		assertThat(shipment.getTrackingNo()).isEqualTo(REAL);          // 값은 그대로
		assertThat(shipment.getTrackingSource()).isEqualTo(TrackingSource.EMAIL);   // 출처만 승격
	}
}
```

- [ ] **Step 2: 실패를 확인한다**

Run: `cd backend && ./gradlew :worker:test --tests "*EmailTrackingSourcePromotionTest*"`
Expected: FAIL — 출처가 `MARKET`인 채로 남는다(승격 미구현)

- [ ] **Step 3: 승격을 구현한다**

`EmailFetcherService.java` — `if (sameTracking) {` 블록 **맨 앞**(스킵/재전송 분기보다 위)에 넣는다:

```java
				if (sameTracking) {
					// 출처는 "무엇이 이 값을 확인했나"다. 값이 같아 쓰지 않고 지나가더라도,
					// 이메일이 이 송장을 진짜라고 확인해 준 사실은 남긴다 — 그러지 않으면
					// 마켓이 먼저 알려준 진짜 송장이 영영 ✍(진위 불명)로 표시된다.
					shippingWriter.promoteTrackingSourceToEmail(item);
```

`LineItemShippingWriter`에 메서드를 더한다:

```java
	/**
	 * 값은 그대로 두고 <b>출처만</b> 이메일로 올린다. 이메일이 같은 값으로 확인해 준 경우다.
	 */
	@Transactional
	public void promoteTrackingSourceToEmail(OrderLineItem item) {
		Long shipmentId = item.getShipmentId();
		if (shipmentId == null) {
			return;
		}
		shipmentRepository.findById(shipmentId).ifPresent(shipment -> {
			shipment.applyTrackingSource(TrackingSource.EMAIL);
			shipmentRepository.save(shipment);
		});
	}
```

- [ ] **Step 4: 통과를 확인한다**

Run: `cd backend && ./gradlew test`
Expected: 전체 회귀 실패 0

- [ ] **Step 5: 커밋**

```bash
git add -A
git commit -m "feat(order): 이메일이 같은 값을 확인해도 출처를 EMAIL로 승격

마켓이 먼저 알려준 진짜 송장은 MARKET으로 기록되는데, 이후 메일이 와도 값이 같으면
이메일 경로가 값을 쓰지 않고 지나가 출처가 굳는다. 승격을 놓치면 진짜가 ✍로 남는다."
```

---

### Task 4: 화면 — 고정폭 아이콘 슬롯

**Files:**
- Modify: `frontend/src/api/orderApi.ts` (`ShipmentDto`)
- Modify: `frontend/src/pages/OrderGrid.tsx` (`ShippingEditCell`)

**Interfaces:**
- Consumes: 백엔드가 `shipment.trackingSource`를 그대로 직렬화한다(`OrderDetailDto`가 `Shipment` 엔티티를 담으므로 별도 매핑 불필요)
- Produces: 없음(화면 종단)

- [ ] **Step 1: 타입을 더한다**

`orderApi.ts`의 `ShipmentDto`에 추가:

```ts
  /**
   * 이 송장을 무엇이 확인했는가. 'EMAIL'은 iHerb 메일이 확인한 진짜 송장,
   * 'MANUAL'·'MARKET'은 사람·마켓이 넣은 진위 불명 값이다. null은 이 기능 이전의 과거 데이터.
   */
  trackingSource?: 'EMAIL' | 'MANUAL' | 'MARKET' | null;
```

- [ ] **Step 2: 아이콘 정의와 슬롯을 만든다**

`OrderGrid.tsx` — `SYNC_BADGE` 정의 아래에 추가:

```tsx
/**
 * 송장 출처 — 마켓 반영 여부(SYNC_BADGE)와는 <b>다른 축</b>이다.
 * 저장은 EMAIL/MANUAL/MARKET 3종이지만 화면은 "이메일이 확인했나"만 물으므로 둘로 접는다.
 */
const SOURCE_ICON: Record<'EMAIL' | 'MANUAL' | 'MARKET', { icon: string; title: string }> = {
  EMAIL: { icon: '📧', title: 'iHerb 발송메일이 확인해 준 진짜 송장입니다.' },
  MANUAL: { icon: '✍', title: '관리자가 직접 입력한 값입니다. 진짜인지 가송장인지 알 수 없습니다 — iHerb 메일이 도착하면 자동으로 진짜 송장으로 바뀝니다.' },
  MARKET: { icon: '✍', title: '마켓이 알려준 값을 채택했습니다. 진짜인지 알 수 없습니다 — iHerb 메일이 도착하면 자동으로 확인됩니다.' },
};
```

- [ ] **Step 3: 셀에 슬롯을 넣는다**

`ShippingEditCell`의 props에 `trackingSource`를 더하고, 송장 `<input>`을 다음으로 감싼다:

```tsx
      {/* 출처 아이콘은 고정폭 슬롯이다 — 아이콘이 없어도 입력칸 시작 위치가 흔들리면 안 된다. */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '3px' }}>
        <span
          title={trackingSource ? SOURCE_ICON[trackingSource].title : ''}
          style={{ width: '14px', flexShrink: 0, fontSize: '11px', textAlign: 'center', lineHeight: 1 }}
        >
          {trackingSource ? SOURCE_ICON[trackingSource].icon : ''}
        </span>
        <input type="text" value={draftTracking} placeholder="송장번호"
          style={{ ...inputStyle, flex: 1, textAlign: 'center', borderColor: border, borderWidth: changed ? 2 : 1 }}
          onChange={(e) => setDraftTracking(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') send(); else if (e.key === 'Escape') { setDraftCarrier(carrier); setDraftTracking(trackingNo); } }} />
      </div>
```

호출부(그리드 컬럼 정의)에서 `trackingSource={row.original.shipment?.trackingSource ?? null}`을 넘긴다.

- [ ] **Step 4: 게이트를 통과시킨다**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: 타입체크 통과 · 빌드 성공

- [ ] **Step 5: 눈으로 확인한다**

세 경우가 한 화면에 보이도록 확인한다 — 아이콘 있음(📧)·있음(✍)·없음(과거 데이터). **입력칸 왼쪽 모서리가 세 행 모두 같은 x좌표**여야 한다.

- [ ] **Step 6: 커밋**

```bash
git add frontend/src/api/orderApi.ts frontend/src/pages/OrderGrid.tsx
git commit -m "feat(order): 송장 출처 아이콘(📧 이메일 / ✍ 진위 불명)

송장 입력칸 왼쪽 고정폭 슬롯에 표시한다. 아이콘이 없어도 입력칸 정렬이 흔들리지 않는다.
기존 배지 줄(배지↔전송)은 건드리지 않는다."
```

---

### Task 5: 운영 반영과 라이브 검증

**Files:** 없음(운영 작업)

**Interfaces:**
- Consumes: Task 1~4 전부

- [ ] **Step 1: 운영 DB에 컬럼을 만든다**

스키마는 수동 관리다(Flyway 없음). **배포 전에** 실행한다 — 컬럼이 없으면 기동 시 매핑 오류가 난다.

```bash
ssh -i ssh-key-2026-06-25.key ubuntu@168.107.31.154 \
  "docker exec projects-postgres-1 psql -U canagent -d sbshop -c \
   \"ALTER TABLE sb_shipment ADD COLUMN IF NOT EXISTS tracking_source VARCHAR(20);\""
```

- [ ] **Step 2: 배포한다**

```bash
git push origin main
```

- [ ] **Step 3: 배포를 확인한다**

서버 커밋 해시 + 컨테이너 재생성 시각 + **주문조회 200**까지 본다(기동 로그만으로 끝내지 않는다).

```bash
ssh -i ssh-key-2026-06-25.key ubuntu@168.107.31.154 \
  "cd /home/ubuntu/projects/sbshop-agent && git log --oneline -1; \
   docker ps --filter name=projects-sbshop-api-1 --format '{{.Status}} | {{.CreatedAt}}'"
curl -s -o /dev/null -w "%{http_code}\n" "http://168.107.31.154/sbshop-agent/api/v1/orders?page=0&size=1"
```

- [ ] **Step 4: 출처가 실제로 쌓이는지 본다**

이메일 페치를 1회 돌린 뒤 분포를 확인한다. 과거 데이터는 `null`로 남아야 정상이다.

```bash
ssh -i ssh-key-2026-06-25.key ubuntu@168.107.31.154 \
  "docker exec projects-sbshop-api-1 curl -s -X POST localhost:8080/internal/email/fetch >/dev/null; \
   docker exec projects-postgres-1 psql -U canagent -d sbshop -tAc \
   \"select coalesce(tracking_source,'(미기록)'), count(*) from sb_shipment group by 1 order by 2 desc\""
```

- [ ] **Step 5: 결과서를 쓰고 커밋한다**

`docs/normalize/working_history/{YYYYMMDD_HHmm}_결과서.md`에 처리 내용·게이트·라이브 검증 결과와
`## 다음 단계 참조`를 남긴다.
