# 운영 대시보드 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 통합 주문 데이터를 일/주/월 캘린더 버킷으로 집계해 추이·분포·이상 패널로 보여주고, 모든 요소 클릭 시 통합 주문 관리(필터 자동적용)로 드릴다운하는 운영 대시보드를 만든다.

**Architecture:** 백엔드는 기간 내 주문 행을 1회 fetch 후 **순수 버킷팅 함수**로 Java 집계(KST·월요일 주·달력 월). `/api/v1/dashboard/*` 4개 엔드포인트로 노출. 프론트는 recharts로 렌더, 드릴다운은 통합 주문 관리 URL 필터를 재사용한다. 재고부족·소싱처 드릴다운을 위해 주문검색에 재고상태·소싱처 필터를 확장한다.

**Tech Stack:** Java 21 · Spring Boot 3.5 · QueryDSL · React 19 · Vite · TanStack Query · react-router-dom v7 · recharts(신규)

## Global Constraints

- 스키마 변경 금지 — 기존 엔티티만 사용(재고상태=Product.stockStatus, 소싱처=OrderLineItem.sourcingData.sourcingVendor, 정산=settlementData.settlementAmount, 실구매가/물류비=sourcingData.sourcingAmount/logisticsCost). Flyway 없음.
- 시각 규칙: 백엔드 `orderDate`는 zone 없는 UTC 벽시계값 → KST 변환 후 버킷팅(프론트 `toKstDate`와 동일 규칙). 서버 집계에서 `ZoneId.of("Asia/Seoul")` 일관 사용.
- 주 버킷 = 월요일 시작(ISO), 월 버킷 = 달력 월. 빈 구간 0채움.
- 금액 = lineItem 합산, 주문수 = distinct order.
- 순수익 = settlementAmount − sourcingAmount − logisticsCost (없으면 0으로 취급).
- `/api/v1/**`는 permitAll 유지(집계는 시크릿 아님). market-credentials만 인증(기존 F-CRED-9).
- 프론트 마켓 색 팔레트는 통합 주문 관리와 동일(쿠팡 #fce4ec/#c2185b · N스토어 #f1f8e9/#689f38 · 11번가 #e3f2fd/#1565c0 · G마켓 #c8e6c9/#1b5e20 · 옥션 #fff3e0/#e65100 · 카페24 #fffde7/#fbc02d).
- 프론트 테스트 러너 없음 → 게이트는 `npx tsc -p tsconfig.app.json --noEmit` + `npm run build`. 순수함수는 향후 러너 대비 분리.
- 배포는 `git push origin main`(웹훅 자동배포). 직접 docker build 금지.

---

## 병렬화 맵 (Parallelization Map)

4개 트랙은 인터페이스 계약(아래)만 공유하며 **동시에 진행 가능**하다.

```
Track A  백엔드 집계 API            ─┐ (계약: DashboardResponse JSON)
Track B  백엔드 주문검색 필터 확장   ─┤ (계약: OrderSearchCondition에 stockStatuses·vendors 추가)
Track C  프론트 대시보드 UI          ─┘ (A 계약에 의존 — 목 데이터로 병렬 개발, 말미 통합)
Track D  프론트 OrderGrid URL 필터   ── (B 계약에 의존 — URL 파싱은 독립, 신규 필터 UI는 B 계약 참조)
```

- **A와 B**: 완전 독립(다른 파일). 병렬.
- **C**: A의 JSON 계약(Task A0에 고정)만 알면 목으로 병렬 개발, A 배포 후 실연동.
- **D**: URL 파라미터 파싱(D1)은 독립. 신규 필터 UI(D2)는 B의 파라미터명(`stockStatuses`,`vendors`)만 참조.
- 통합 순서: A·B 먼저 머지(백엔드) → C·D 실연동 확인 → E(통합 QA).

각 트랙 내부 태스크는 순차(TDD Red→Green→Commit). 트랙 간에는 배리어 없음.

---

## Track A — 백엔드 집계 API

**File Structure:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/dashboard/DashboardBucketing.java` (순수 버킷팅)
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/dashboard/dto/DashboardDtos.java` (요청·응답 DTO + 집계 행)
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/dashboard/DashboardService.java`
- Create: `backend/core/src/main/java/com/sbshop/agent/core/domain/dashboard/DashboardRepository.java` (포트 인터페이스)
- Create: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/repository/dashboard/DashboardRepositoryImpl.java`
- Create: `backend/api/src/main/java/com/sbshop/agent/api/controller/DashboardController.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/dashboard/DashboardBucketingTest.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/dashboard/DashboardServiceTest.java`

**Interfaces:**
- Produces (Task A0 계약, C가 소비):
  - `GET /api/v1/dashboard/summary?start=<ISO_LOCAL_DATE_TIME>&end=<...>` → `{ "period": {"orderCount":int,"settlementSum":long,"profitSum":long}, "current": {"newCount":int,"shippingCount":int,"customsIssueCount":int} }`
  - `GET /api/v1/dashboard/timeseries?start=&end=&unit=DAY|WEEK|MONTH` → `[ {"bucketStart":"2026-07-01","orderCount":int,"settlementSum":long,"profitSum":long}, ... ]`
  - `GET /api/v1/dashboard/breakdown?start=&end=&dimension=MARKET|STATUS|PRODUCT|VENDOR&limit=int` → `[ {"key":str,"label":str,"orderCount":int,"settlementSum":long,"profitSum":long}, ... ]`
  - `GET /api/v1/dashboard/attention` → `{"customsIssue":int,"outOfStock":int,"delayed":int,"returnCancel":int}`
- Consumes: 기존 QueryDSL Q타입(QOrder, QOrderLineItem, QProduct), enum(MarketType, ShippingStatus, CustomsStatus, StockStatus).

### Task A0: 응답 DTO + 집계 행 DTO 고정 (계약)

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/dashboard/dto/DashboardDtos.java`

**Interfaces:**
- Produces: 아래 record들. `AggRow`는 리포지토리→서비스 전달용 평면 행.

- [ ] **Step 1: DTO 파일 작성**

```java
package com.sbshop.agent.core.application.dashboard.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDateTime;
import java.util.List;

/** 대시보드 집계 DTO 모음. */
public final class DashboardDtos {
	private DashboardDtos() {}

	/** 리포지토리가 기간 내 주문×라인아이템×상품을 평면 행으로 투영한 것. 서비스가 Java 집계. */
	public record AggRow(
		Long orderId,
		LocalDateTime orderDate,      // zone 없는 UTC 벽시계값(KST 변환 대상)
		MarketType marketType,
		ShippingStatus shippingStatus,
		long settlementAmount,        // null→0 로 매핑됨
		long sourcingAmount,
		long logisticsCost,
		Long productId,
		String sbCode,
		String productName,
		String sourcingVendor,
		String stockStatus) {         // Product.stockStatus enum name (IN_STOCK/OUT_OF_STOCK) or null
		public long profit() { return settlementAmount - sourcingAmount - logisticsCost; }
	}

	public record SummaryResponse(Period period, Current current) {
		public record Period(int orderCount, long settlementSum, long profitSum) {}
		public record Current(int newCount, int shippingCount, int customsIssueCount) {}
	}

	public record TimeseriesBucket(String bucketStart, int orderCount, long settlementSum, long profitSum) {}

	public record BreakdownItem(String key, String label, int orderCount, long settlementSum, long profitSum) {}

	public record AttentionResponse(int customsIssue, int outOfStock, int delayed, int returnCancel) {}

	public enum Unit { DAY, WEEK, MONTH }
	public enum Dimension { MARKET, STATUS, PRODUCT, VENDOR }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `cd backend && ./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/dashboard/dto/DashboardDtos.java
git commit -m "feat(dashboard): 집계 응답·행 DTO 계약 정의"
```

### Task A1: 버킷팅 순수함수 (핵심 단위, TDD)

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/dashboard/DashboardBucketing.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/dashboard/DashboardBucketingTest.java`

**Interfaces:**
- Produces:
  - `static LocalDate bucketKey(LocalDateTime naiveUtc, Unit unit)` — 해당 시각(KST 변환)이 속한 버킷 시작일.
  - `static List<LocalDate> bucketRange(LocalDateTime start, LocalDateTime end, Unit unit)` — [start,end] 구간의 모든 버킷 시작일(빈 구간 포함, 오름차순).

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.sbshop.agent.core.application.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DashboardBucketingTest {

	// orderDate는 zone 없는 UTC 벽시계값. KST=UTC+9. 2026-07-01T20:00Z → KST 07-02 05:00 → 일버킷 07-02.
	@Test
	@DisplayName("DAY 버킷은 KST 날짜로 매핑(UTC+9 경계)")
	void dayBucketUsesKst() {
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-07-01T20:00:00"), Unit.DAY))
			.isEqualTo(LocalDate.parse("2026-07-02"));
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-07-01T10:00:00"), Unit.DAY))
			.isEqualTo(LocalDate.parse("2026-07-01"));
	}

	@Test
	@DisplayName("WEEK 버킷은 월요일 시작(ISO)")
	void weekBucketStartsMonday() {
		// 2026-07-25(토, KST) → 그 주 월요일 2026-07-20
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-07-25T03:00:00"), Unit.WEEK))
			.isEqualTo(LocalDate.parse("2026-07-20"));
	}

	@Test
	@DisplayName("MONTH 버킷은 달력 1일")
	void monthBucketIsFirstOfMonth() {
		assertThat(DashboardBucketing.bucketKey(LocalDateTime.parse("2026-07-25T03:00:00"), Unit.MONTH))
			.isEqualTo(LocalDate.parse("2026-07-01"));
	}

	@Test
	@DisplayName("bucketRange는 빈 구간 포함 모든 버킷을 오름차순으로 채운다(DAY)")
	void dayRangeFillsEmpty() {
		List<LocalDate> r = DashboardBucketing.bucketRange(
			LocalDateTime.parse("2026-07-01T00:00:00"), LocalDateTime.parse("2026-07-03T23:59:59"), Unit.DAY);
		assertThat(r).containsExactly(
			LocalDate.parse("2026-07-01"), LocalDate.parse("2026-07-02"), LocalDate.parse("2026-07-03"));
	}

	@Test
	@DisplayName("bucketRange WEEK는 월요일 시작 버킷을 채운다")
	void weekRangeMondays() {
		List<LocalDate> r = DashboardBucketing.bucketRange(
			LocalDateTime.parse("2026-07-01T00:00:00"), LocalDateTime.parse("2026-07-20T00:00:00"), Unit.WEEK);
		// 07-01(수) 속한 주 월요일=06-29, 이후 07-06, 07-13, 07-20
		assertThat(r).containsExactly(
			LocalDate.parse("2026-06-29"), LocalDate.parse("2026-07-06"),
			LocalDate.parse("2026-07-13"), LocalDate.parse("2026-07-20"));
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests "com.sbshop.agent.core.application.dashboard.DashboardBucketingTest" -q`
Expected: FAIL (DashboardBucketing 클래스 없음 → 컴파일 에러)

- [ ] **Step 3: 구현**

```java
package com.sbshop.agent.core.application.dashboard;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/** 대시보드 캘린더 버킷팅(KST, 월요일 주, 달력 월). 순수함수. */
public final class DashboardBucketing {
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private DashboardBucketing() {}

	/** zone 없는 UTC 벽시계값을 KST 날짜로 본 뒤, unit 버킷 시작일로 내린다. */
	public static LocalDate bucketKey(LocalDateTime naiveUtc, Unit unit) {
		LocalDate kst = naiveUtc.atZone(ZoneId.of("UTC")).withZoneSameInstant(KST).toLocalDate();
		return floor(kst, unit);
	}

	private static LocalDate floor(LocalDate d, Unit unit) {
		return switch (unit) {
			case DAY -> d;
			case WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
			case MONTH -> d.withDayOfMonth(1);
		};
	}

	private static LocalDate next(LocalDate bucket, Unit unit) {
		return switch (unit) {
			case DAY -> bucket.plusDays(1);
			case WEEK -> bucket.plusWeeks(1);
			case MONTH -> bucket.plusMonths(1);
		};
	}

	/** [start,end] 구간(각각 KST 변환)의 모든 버킷 시작일을 빈 구간 포함 오름차순으로. */
	public static List<LocalDate> bucketRange(LocalDateTime start, LocalDateTime end, Unit unit) {
		LocalDate first = bucketKey(start, unit);
		LocalDate last = bucketKey(end, unit);
		List<LocalDate> out = new ArrayList<>();
		for (LocalDate b = first; !b.isAfter(last); b = next(b, unit)) {
			out.add(b);
		}
		return out;
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests "com.sbshop.agent.core.application.dashboard.DashboardBucketingTest" -q`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/dashboard/DashboardBucketing.java backend/core/src/test/java/com/sbshop/agent/core/application/dashboard/DashboardBucketingTest.java
git commit -m "feat(dashboard): 캘린더 버킷팅 순수함수(KST·월요일주·달력월) + 테스트"
```

### Task A2: DashboardRepository 포트 + QueryDSL 구현

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/domain/dashboard/DashboardRepository.java`
- Create: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/repository/dashboard/DashboardRepositoryImpl.java`

**Interfaces:**
- Produces:
  - `List<AggRow> findRowsBetween(LocalDateTime start, LocalDateTime end)` — 기간 내 주문×라인아이템×상품 평면 행.
  - `int countByShippingStatusIn(List<ShippingStatus>)` — 현재 상태 카운트(distinct order).
  - `int countCustomsIssue(List<CustomsStatus>)`.
  - `int countOutOfStock()` — 미종결 주문 중 상품 OUT_OF_STOCK distinct order.
  - `int countDelayed(LocalDateTime newBefore, LocalDateTime preparingBefore)` — (NEW & orderDate≤newBefore)+(PREPARING & orderDate≤preparingBefore) distinct order.
  - `int countByShippingStatusIn` 재사용으로 returnCancel=CANCELED+RETURNED.

- [ ] **Step 1: 포트 인터페이스 작성**

```java
package com.sbshop.agent.core.domain.dashboard;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AggRow;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDateTime;
import java.util.List;

public interface DashboardRepository {
	List<AggRow> findRowsBetween(LocalDateTime start, LocalDateTime end);
	int countByShippingStatusIn(List<ShippingStatus> statuses);
	int countCustomsIssue(List<CustomsStatus> statuses);
	int countOutOfStock();
	int countDelayed(LocalDateTime newOnOrBefore, LocalDateTime preparingOnOrBefore);
}
```

- [ ] **Step 2: QueryDSL 구현 작성**

`OrderRepositoryImpl`의 조인 패턴(order→lineItem: `qLineItem.orderId.eq(order.id)`, lineItem→product: `qProduct.id.eq(qLineItem.productId)`)을 따른다. `findRowsBetween`은 Tuple로 필요한 컬럼만 select 후 AggRow로 매핑. null 금액은 `Optional`/`coalesce` 처리.

```java
package com.sbshop.agent.infrastructure.repository.dashboard;

import static com.sbshop.agent.core.domain.order.QOrder.order;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AggRow;
import com.sbshop.agent.core.domain.dashboard.DashboardRepository;
import com.sbshop.agent.core.domain.order.QOrderLineItem;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.product.QProduct;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DashboardRepositoryImpl implements DashboardRepository {

	private final JPAQueryFactory queryFactory;

	@Override
	public List<AggRow> findRowsBetween(LocalDateTime start, LocalDateTime end) {
		QOrderLineItem li = QOrderLineItem.orderLineItem;
		QProduct p = QProduct.product;
		List<Tuple> rows = queryFactory
			.select(order.id, order.orderDate, order.marketType,
				li.shippingData.shippingStatus,
				li.settlementData.settlementAmount, li.sourcingData.sourcingAmount,
				li.sourcingData.logisticsCost, li.productId,
				p.sbCode, p.productName, li.sourcingData.sourcingVendor, p.stockStatus)
			.from(order)
			.join(li).on(li.orderId.eq(order.id))
			.leftJoin(p).on(p.id.eq(li.productId))
			.where(order.orderDate.goe(start), order.orderDate.loe(end))
			.fetch();
		return rows.stream().map(t -> new AggRow(
			t.get(order.id), t.get(order.orderDate), t.get(order.marketType),
			t.get(li.shippingData.shippingStatus),
			toLong(t.get(li.settlementData.settlementAmount)),
			toLong(t.get(li.sourcingData.sourcingAmount)),
			toLong(t.get(li.sourcingData.logisticsCost)),
			t.get(li.productId), t.get(p.sbCode), t.get(p.productName),
			t.get(li.sourcingData.sourcingVendor),
			t.get(p.stockStatus) != null ? t.get(p.stockStatus).name() : null
		)).toList();
	}

	private static long toLong(Number n) { return n == null ? 0L : n.longValue(); }

	@Override
	public int countByShippingStatusIn(List<ShippingStatus> statuses) {
		QOrderLineItem li = QOrderLineItem.orderLineItem;
		Long c = queryFactory.select(order.id.countDistinct())
			.from(order).join(li).on(li.orderId.eq(order.id))
			.where(li.shippingData.shippingStatus.in(statuses)).fetchOne();
		return c == null ? 0 : c.intValue();
	}

	@Override
	public int countCustomsIssue(List<CustomsStatus> statuses) {
		Long c = queryFactory.select(order.id.countDistinct()).from(order)
			.where(order.customsData.customsStatus.in(statuses)).fetchOne();
		return c == null ? 0 : c.intValue();
	}

	@Override
	public int countOutOfStock() {
		QOrderLineItem li = QOrderLineItem.orderLineItem;
		QProduct p = QProduct.product;
		BooleanExpression open = li.shippingData.shippingStatus.in(
			ShippingStatus.NEW, ShippingStatus.PREPARING, ShippingStatus.DISPATCHED);
		Long c = queryFactory.select(order.id.countDistinct()).from(order)
			.join(li).on(li.orderId.eq(order.id))
			.join(p).on(p.id.eq(li.productId))
			.where(open, p.stockStatus.stringValue().eq("OUT_OF_STOCK")).fetchOne();
		return c == null ? 0 : c.intValue();
	}

	@Override
	public int countDelayed(LocalDateTime newOnOrBefore, LocalDateTime preparingOnOrBefore) {
		QOrderLineItem li = QOrderLineItem.orderLineItem;
		BooleanExpression delayed = li.shippingData.shippingStatus.eq(ShippingStatus.NEW)
			.and(order.orderDate.loe(newOnOrBefore))
			.or(li.shippingData.shippingStatus.eq(ShippingStatus.PREPARING)
				.and(order.orderDate.loe(preparingOnOrBefore)));
		Long c = queryFactory.select(order.id.countDistinct()).from(order)
			.join(li).on(li.orderId.eq(order.id)).where(delayed).fetchOne();
		return c == null ? 0 : c.intValue();
	}
}
```

> 주: `p.stockStatus`가 enum이면 `.eq(StockStatus.OUT_OF_STOCK)` 로 교체(아래 A2a에서 실제 타입 확인 후 조정). Product의 stockStatus 타입은 구현 시 `grep "stockStatus" backend/core/.../product/Product.java`로 확인하고 enum이면 enum 비교로 바꿀 것.

- [ ] **Step 3: Product.stockStatus 실제 타입 확인 후 비교식 정합**

Run: `grep -n "stockStatus" backend/core/src/main/java/com/sbshop/agent/core/domain/product/Product.java`
enum이면 `countOutOfStock`의 `p.stockStatus.stringValue().eq("OUT_OF_STOCK")`를 `p.stockStatus.eq(<StockStatusEnum>.OUT_OF_STOCK)`로, `findRowsBetween`의 매핑도 enum name 그대로 유지.

- [ ] **Step 4: 컴파일 확인**

Run: `cd backend && ./gradlew :infrastructure:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/domain/dashboard/DashboardRepository.java backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/repository/dashboard/DashboardRepositoryImpl.java
git commit -m "feat(dashboard): 집계용 리포지토리 포트+QueryDSL 구현(행 fetch·현재상태 카운트)"
```

### Task A3: DashboardService (집계 로직, TDD)

**Files:**
- Create: `backend/core/src/main/java/com/sbshop/agent/core/application/dashboard/DashboardService.java`
- Test: `backend/core/src/test/java/com/sbshop/agent/core/application/dashboard/DashboardServiceTest.java`

**Interfaces:**
- Consumes: `DashboardRepository`, `DashboardBucketing`, `DashboardDtos`.
- Produces:
  - `SummaryResponse summary(LocalDateTime start, LocalDateTime end)`
  - `List<TimeseriesBucket> timeseries(LocalDateTime start, LocalDateTime end, Unit unit)`
  - `List<BreakdownItem> breakdown(LocalDateTime start, LocalDateTime end, Dimension dim, int limit)`
  - `AttentionResponse attention(LocalDateTime now)`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.sbshop.agent.core.application.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AggRow;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.BreakdownItem;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Dimension;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.TimeseriesBucket;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import com.sbshop.agent.core.domain.dashboard.DashboardRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

	@Mock DashboardRepository repo;
	DashboardService service;

	@BeforeEach
	void setUp() { service = new DashboardService(repo); }

	private AggRow row(long orderId, String date, MarketType mk, ShippingStatus st,
		long settle, long src, long logi, String sb) {
		return new AggRow(orderId, LocalDateTime.parse(date), mk, st, settle, src, logi,
			1L, sb, "상품" + sb, "IHB", "IN_STOCK");
	}

	@Test
	@DisplayName("summary.period: 주문수는 distinct order, 금액은 lineItem 합산")
	void summaryAggregates() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-07-01T01:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 6000, 1000, "A"),
			row(1, "2026-07-01T01:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 5000, 3000, 500, "B"),
			row(2, "2026-07-02T01:00:00", MarketType.SMART_STORE, ShippingStatus.SHIPPED, 20000, 12000, 2000, "C")));
		var s = service.summary(LocalDateTime.parse("2026-07-01T00:00:00"), LocalDateTime.parse("2026-07-31T23:59:59"));
		assertThat(s.period().orderCount()).isEqualTo(2);          // distinct order 1,2
		assertThat(s.period().settlementSum()).isEqualTo(35000);   // 10000+5000+20000
		assertThat(s.period().profitSum()).isEqualTo(35000 - 21000 - 3500);
	}

	@Test
	@DisplayName("timeseries DAY: 빈 날도 0으로 채우고 KST 날짜로 버킷")
	void timeseriesFillsEmptyDays() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-07-01T01:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 6000, 1000, "A")));
		List<TimeseriesBucket> ts = service.timeseries(
			LocalDateTime.parse("2026-07-01T00:00:00"), LocalDateTime.parse("2026-07-03T23:59:59"), Unit.DAY);
		assertThat(ts).hasSize(3);
		assertThat(ts.get(0).bucketStart()).isEqualTo("2026-07-01");
		assertThat(ts.get(0).orderCount()).isEqualTo(1);
		assertThat(ts.get(1).orderCount()).isEqualTo(0);   // 07-02 빈 구간
		assertThat(ts.get(2).orderCount()).isEqualTo(0);
	}

	@Test
	@DisplayName("breakdown MARKET: 마켓별 distinct 주문수·합계, 라벨 한글")
	void breakdownByMarket() {
		when(repo.findRowsBetween(any(), any())).thenReturn(List.of(
			row(1, "2026-07-01T01:00:00", MarketType.COUPANG, ShippingStatus.DELIVERED, 10000, 0, 0, "A"),
			row(2, "2026-07-02T01:00:00", MarketType.COUPANG, ShippingStatus.SHIPPED, 5000, 0, 0, "B"),
			row(3, "2026-07-02T01:00:00", MarketType.SMART_STORE, ShippingStatus.SHIPPED, 7000, 0, 0, "C")));
		List<BreakdownItem> b = service.breakdown(
			LocalDateTime.parse("2026-07-01T00:00:00"), LocalDateTime.parse("2026-07-31T23:59:59"), Dimension.MARKET, 10);
		assertThat(b).extracting(BreakdownItem::key).containsExactlyInAnyOrder("COUPANG", "SMART_STORE");
		var coupang = b.stream().filter(x -> x.key().equals("COUPANG")).findFirst().orElseThrow();
		assertThat(coupang.orderCount()).isEqualTo(2);
		assertThat(coupang.settlementSum()).isEqualTo(15000);
	}

	@Test
	@DisplayName("attention: 리포지토리 카운트를 그대로 조립")
	void attentionAssembles() {
		when(repo.countCustomsIssue(any())).thenReturn(3);
		when(repo.countOutOfStock()).thenReturn(2);
		when(repo.countDelayed(any(), any())).thenReturn(5);
		when(repo.countByShippingStatusIn(List.of(ShippingStatus.CANCELED, ShippingStatus.RETURNED))).thenReturn(4);
		var a = service.attention(LocalDateTime.parse("2026-07-25T10:00:00"));
		assertThat(a.customsIssue()).isEqualTo(3);
		assertThat(a.outOfStock()).isEqualTo(2);
		assertThat(a.delayed()).isEqualTo(5);
		assertThat(a.returnCancel()).isEqualTo(4);
	}
}
```

> `any()` static import: `import static org.mockito.ArgumentMatchers.any;` 를 추가할 것.

- [ ] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :core:test --tests "com.sbshop.agent.core.application.dashboard.DashboardServiceTest" -q`
Expected: FAIL (DashboardService 없음)

- [ ] **Step 3: 구현**

```java
package com.sbshop.agent.core.application.dashboard;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AggRow;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AttentionResponse;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.BreakdownItem;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Dimension;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.SummaryResponse;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.TimeseriesBucket;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import com.sbshop.agent.core.domain.dashboard.DashboardRepository;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardService {

	private static final List<CustomsStatus> CUSTOMS_ISSUES = List.of(
		CustomsStatus.PENDING, CustomsStatus.INVALID_PCCC,
		CustomsStatus.INVALID_PHONE, CustomsStatus.INVALID_ZIPCODE);

	private final DashboardRepository repo;

	public SummaryResponse summary(LocalDateTime start, LocalDateTime end) {
		List<AggRow> rows = repo.findRowsBetween(start, end);
		int orderCount = (int) rows.stream().map(AggRow::orderId).distinct().count();
		long settlement = rows.stream().mapToLong(AggRow::settlementAmount).sum();
		long profit = rows.stream().mapToLong(AggRow::profit).sum();
		var current = new SummaryResponse.Current(
			repo.countByShippingStatusIn(List.of(ShippingStatus.NEW)),
			repo.countByShippingStatusIn(List.of(ShippingStatus.DISPATCHED, ShippingStatus.SHIPPED)),
			repo.countCustomsIssue(CUSTOMS_ISSUES));
		return new SummaryResponse(new SummaryResponse.Period(orderCount, settlement, profit), current);
	}

	public List<TimeseriesBucket> timeseries(LocalDateTime start, LocalDateTime end, Unit unit) {
		List<AggRow> rows = repo.findRowsBetween(start, end);
		// 버킷별: distinct 주문 집합 + 금액 합.
		// A1 리뷰 Important 대응: 축(x)은 bucketRange(빈 구간 0채움)와 실제 주문의 KST 버킷키의
		// 합집합으로 구성한다. naive 경계(bucketRange)와 KST 주문키(bucketKey)의 9h 스큐로 마지막 날
		// UTC 꼬리 주문이 다음 KST 버킷으로 가더라도 축에 포함되어 절대 누락되지 않는다.
		Map<LocalDate, Set<Long>> orders = new java.util.TreeMap<>();  // 버킷키 오름차순 정렬
		Map<LocalDate, long[]> sums = new java.util.HashMap<>();       // [settlement, profit]
		java.util.function.Consumer<LocalDate> ensure = b -> {
			orders.computeIfAbsent(b, k -> new java.util.HashSet<>());
			sums.computeIfAbsent(b, k -> new long[2]);
		};
		for (LocalDate b : DashboardBucketing.bucketRange(start, end, unit)) ensure.accept(b);
		for (AggRow r : rows) {
			LocalDate b = DashboardBucketing.bucketKey(r.orderDate(), unit);
			ensure.accept(b);                     // 축에 없던 KST 꼬리 버킷도 편입(누락 방지)
			orders.get(b).add(r.orderId());
			long[] s = sums.get(b);
			s[0] += r.settlementAmount();
			s[1] += r.profit();
		}
		List<TimeseriesBucket> out = new ArrayList<>();
		for (LocalDate b : orders.keySet()) {     // TreeMap → 오름차순
			long[] s = sums.get(b);
			out.add(new TimeseriesBucket(b.toString(), orders.get(b).size(), s[0], s[1]));
		}
		return out;
	}

	public List<BreakdownItem> breakdown(LocalDateTime start, LocalDateTime end, Dimension dim, int limit) {
		List<AggRow> rows = repo.findRowsBetween(start, end);
		Map<String, Set<Long>> ordersByKey = new LinkedHashMap<>();
		Map<String, long[]> sumsByKey = new LinkedHashMap<>();
		Map<String, String> labels = new LinkedHashMap<>();
		for (AggRow r : rows) {
			String key = keyOf(r, dim);
			if (key == null) continue;
			ordersByKey.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(r.orderId());
			sumsByKey.computeIfAbsent(key, k -> new long[2]);
			sumsByKey.get(key)[0] += r.settlementAmount();
			sumsByKey.get(key)[1] += r.profit();
			labels.putIfAbsent(key, labelOf(r, dim, key));
		}
		List<BreakdownItem> items = ordersByKey.keySet().stream()
			.map(k -> new BreakdownItem(k, labels.get(k), ordersByKey.get(k).size(),
				sumsByKey.get(k)[0], sumsByKey.get(k)[1]))
			.sorted((a, b) -> Integer.compare(b.orderCount(), a.orderCount()))
			.collect(Collectors.toList());
		return limit > 0 && items.size() > limit ? items.subList(0, limit) : items;
	}

	public AttentionResponse attention(LocalDateTime now) {
		LocalDateTime newBefore = now.minusDays(1);
		LocalDateTime preparingBefore = now.minusDays(3);
		return new AttentionResponse(
			repo.countCustomsIssue(CUSTOMS_ISSUES),
			repo.countOutOfStock(),
			repo.countDelayed(newBefore, preparingBefore),
			repo.countByShippingStatusIn(List.of(ShippingStatus.CANCELED, ShippingStatus.RETURNED)));
	}

	private String keyOf(AggRow r, Dimension dim) {
		return switch (dim) {
			case MARKET -> r.marketType() == null ? null : r.marketType().name();
			case STATUS -> r.shippingStatus() == null ? null : r.shippingStatus().name();
			case PRODUCT -> r.sbCode();
			case VENDOR -> r.sourcingVendor();
		};
	}

	private String labelOf(AggRow r, Dimension dim, String key) {
		return switch (dim) {
			case MARKET -> { try { yield MarketType.valueOf(key).getLabel(); } catch (Exception e) { yield key; } }
			case PRODUCT -> r.productName() != null ? r.productName() : key;
			default -> key;   // STATUS·VENDOR는 프론트에서 라벨링
		};
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew :core:test --tests "com.sbshop.agent.core.application.dashboard.DashboardServiceTest" -q`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/dashboard/DashboardService.java backend/core/src/test/java/com/sbshop/agent/core/application/dashboard/DashboardServiceTest.java
git commit -m "feat(dashboard): 집계 서비스(요약·시계열·분포·이상) + 테스트"
```

### Task A4: DashboardController (엔드포인트)

**Files:**
- Create: `backend/api/src/main/java/com/sbshop/agent/api/controller/DashboardController.java`

**Interfaces:**
- Consumes: `DashboardService`. Produces: Task A0의 4개 HTTP 계약.

- [ ] **Step 1: 컨트롤러 작성**

```java
package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.dashboard.DashboardService;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AttentionResponse;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.BreakdownItem;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Dimension;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.SummaryResponse;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.TimeseriesBucket;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DashboardController {

	private final DashboardService dashboardService;

	@GetMapping("/summary")
	public SummaryResponse summary(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
		return dashboardService.summary(start, end);
	}

	@GetMapping("/timeseries")
	public List<TimeseriesBucket> timeseries(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
		@RequestParam Unit unit) {
		return dashboardService.timeseries(start, end, unit);
	}

	@GetMapping("/breakdown")
	public List<BreakdownItem> breakdown(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
		@RequestParam Dimension dimension,
		@RequestParam(defaultValue = "10") int limit) {
		return dashboardService.breakdown(start, end, dimension, limit);
	}

	@GetMapping("/attention")
	public AttentionResponse attention() {
		return dashboardService.attention(LocalDateTime.now());
	}
}
```

- [ ] **Step 2: 전체 컴파일 + 회귀**

Run: `cd backend && ./gradlew :api:compileJava -q && ./gradlew test 2>&1 | grep -iE "BUILD (SUCCESS|FAIL)|tests completed"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add backend/api/src/main/java/com/sbshop/agent/api/controller/DashboardController.java
git commit -m "feat(dashboard): /api/v1/dashboard 집계 엔드포인트 4종"
```

---

## Track B — 백엔드 주문검색 필터 확장 (재고상태·소싱처)

**File Structure:**
- Modify: `backend/core/src/main/java/com/sbshop/agent/core/application/order/dto/OrderSearchCondition.java`
- Modify: `backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/repository/order/OrderRepositoryImpl.java`
- Test: `backend/infrastructure/src/test/java/com/sbshop/agent/infrastructure/repository/order/OrderSearchFilterTest.java` (신규; DB 필요 시 기존 리포지토리 테스트 패턴을 따르되, 없으면 predicate 단위검증으로 대체)

**Interfaces:**
- Produces: `OrderSearchCondition`에 `List<String> stockStatuses` (IN_STOCK/OUT_OF_STOCK), `List<String> vendors` 추가. 프론트/URL 파라미터명 = `stockStatuses`, `vendors`.

### Task B1: OrderSearchCondition 필드 추가

- [ ] **Step 1: 필드 추가**

`OrderSearchCondition`에 아래 2필드 추가:

```java
	// 재고상태 필터(대시보드 '재고부족' 드릴다운). Product.stockStatus enum name. 미지정 시 무시.
	private List<String> stockStatuses;
	// 소싱처 필터(대시보드 소싱처별 드릴다운). OrderLineItem.sourcingData.sourcingVendor. 미지정 시 무시.
	private List<String> vendors;
```

- [ ] **Step 2: 컴파일**

Run: `cd backend && ./gradlew :core:compileJava -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add backend/core/src/main/java/com/sbshop/agent/core/application/order/dto/OrderSearchCondition.java
git commit -m "feat(order-search): 재고상태·소싱처 필터 필드 추가"
```

### Task B2: QueryDSL predicate + 조인 반영

**Interfaces:**
- Consumes: B1 필드. Produces: searchOrderGrid가 stockStatuses·vendors로 필터.

- [ ] **Step 1: predicate 헬퍼 추가 + where 배선**

`OrderRepositoryImpl`에 헬퍼 추가하고 `searchOrderGrid`의 메인 쿼리 where에 배선한다. 재고상태는 product 조인이 필요하므로, 메인 쿼리가 product를 조인하지 않으면 `li.productId in (재고조건 만족 product id)` 서브쿼리로 처리(조인 구조 변경 최소화):

```java
	private BooleanExpression stockStatusIn(List<String> stockStatuses, QOrderLineItem li) {
		if (stockStatuses == null || stockStatuses.isEmpty()) return null;
		QProduct sp = QProduct.product;
		return li.productId.in(
			com.querydsl.jpa.JPAExpressions.select(sp.id).from(sp)
				.where(sp.stockStatus.stringValue().in(stockStatuses)));
	}

	private BooleanExpression vendorIn(List<String> vendors, QOrderLineItem li) {
		if (vendors == null || vendors.isEmpty()) return null;
		return li.sourcingData.sourcingVendor.in(vendors);
	}
```

메인 쿼리는 order 기준 select라 lineItem 서브쿼리 exists로 감싸야 한다. 기존 `keywordContains`가 lineItem을 참조하는 방식(서브쿼리 exists 패턴, OrderRepositoryImpl:193 부근)을 따라 동일 패턴으로 배선:

```java
	// where(...)에 추가
	stockStatusExists(condition.getStockStatuses()),
	vendorExists(condition.getVendors())
```

```java
	private BooleanExpression stockStatusExists(List<String> stockStatuses) {
		if (stockStatuses == null || stockStatuses.isEmpty()) return null;
		QOrderLineItem sli = QOrderLineItem.orderLineItem;
		QProduct sp = QProduct.product;
		return com.querydsl.jpa.JPAExpressions.selectOne().from(sli)
			.leftJoin(sp).on(sp.id.eq(sli.productId))
			.where(sli.orderId.eq(order.id), sp.stockStatus.stringValue().in(stockStatuses))
			.exists();
	}

	private BooleanExpression vendorExists(List<String> vendors) {
		if (vendors == null || vendors.isEmpty()) return null;
		QOrderLineItem sli = QOrderLineItem.orderLineItem;
		return com.querydsl.jpa.JPAExpressions.selectOne().from(sli)
			.where(sli.orderId.eq(order.id), sli.sourcingData.sourcingVendor.in(vendors))
			.exists();
	}
```

> `p.stockStatus`가 enum이면 `.stringValue().in(stockStatuses)` 그대로 동작(enum name 문자열 비교). count 쿼리(있으면)에도 동일 배선.

- [ ] **Step 2: 컴파일 + 회귀**

Run: `cd backend && ./gradlew :infrastructure:compileJava -q && ./gradlew test 2>&1 | grep -iE "BUILD (SUCCESS|FAIL)"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add backend/infrastructure/src/main/java/com/sbshop/agent/infrastructure/repository/order/OrderRepositoryImpl.java
git commit -m "feat(order-search): 재고상태·소싱처 QueryDSL exists 필터 배선"
```

---

## Track C — 프론트 대시보드 UI

**File Structure:**
- Create: `frontend/src/pages/dashboard/dashboardApi.ts`
- Create: `frontend/src/pages/dashboard/drilldown.ts`
- Create: `frontend/src/pages/dashboard/PeriodControl.tsx`
- Create: `frontend/src/pages/dashboard/KpiCards.tsx`
- Create: `frontend/src/pages/dashboard/TrendChart.tsx`
- Create: `frontend/src/pages/dashboard/BreakdownPanels.tsx`
- Create: `frontend/src/pages/dashboard/AttentionPanel.tsx`
- Rewrite: `frontend/src/pages/Dashboard.tsx`
- Modify: `frontend/package.json` (recharts 추가)

**Interfaces:**
- Consumes: Track A HTTP 계약(A0). Produces: `/` 라우트 대시보드.

### Task C1: recharts 설치 + dashboardApi

- [ ] **Step 1: recharts 설치**

Run: `cd frontend && npm install recharts`
Expected: package.json dependencies에 recharts 추가

- [ ] **Step 2: dashboardApi.ts 작성**

```typescript
import { apiClient } from '../../api/axios';

export type Unit = 'DAY' | 'WEEK' | 'MONTH';
export type Dimension = 'MARKET' | 'STATUS' | 'PRODUCT' | 'VENDOR';

export interface Summary {
  period: { orderCount: number; settlementSum: number; profitSum: number };
  current: { newCount: number; shippingCount: number; customsIssueCount: number };
}
export interface TimeseriesBucket { bucketStart: string; orderCount: number; settlementSum: number; profitSum: number; }
export interface BreakdownItem { key: string; label: string; orderCount: number; settlementSum: number; profitSum: number; }
export interface Attention { customsIssue: number; outOfStock: number; delayed: number; returnCancel: number; }

const iso = (d: string) => d; // 'YYYY-MM-DDTHH:mm:ss' 형태로 이미 조립됨

export const fetchSummary = async (start: string, end: string): Promise<Summary> =>
  (await apiClient.get('/api/v1/dashboard/summary', { params: { start: iso(start), end: iso(end) } })).data;

export const fetchTimeseries = async (start: string, end: string, unit: Unit): Promise<TimeseriesBucket[]> =>
  (await apiClient.get('/api/v1/dashboard/timeseries', { params: { start, end, unit } })).data;

export const fetchBreakdown = async (start: string, end: string, dimension: Dimension, limit = 10): Promise<BreakdownItem[]> =>
  (await apiClient.get('/api/v1/dashboard/breakdown', { params: { start, end, dimension, limit } })).data;

export const fetchAttention = async (): Promise<Attention> =>
  (await apiClient.get('/api/v1/dashboard/attention')).data;
```

- [ ] **Step 3: 타입체크**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`
Expected: 에러 없음

- [ ] **Step 4: Commit**

```bash
git add frontend/package.json frontend/package-lock.json frontend/src/pages/dashboard/dashboardApi.ts
git commit -m "feat(dashboard): recharts 설치 + 집계 API 클라이언트"
```

### Task C2: drilldown.ts (순수함수, 단일 출처)

**Interfaces:**
- Produces: `buildOrderGridUrl(filters): string` — 대시보드 필터 → `/orders?...` URL. Track D의 URL 파라미터 계약과 정확히 일치해야 함.

- [ ] **Step 1: drilldown.ts 작성**

```typescript
// 대시보드 요소 → 통합 주문 관리(/orders) 드릴다운 URL 빌더. OrderGrid의 URL 파라미터 파싱과 계약 일치.
export interface DrilldownFilters {
  markets?: string[];
  statuses?: string[];
  customsStatuses?: string[];
  stockStatuses?: string[];
  vendors?: string[];
  keyword?: string;
  startDate?: string; // 'YYYY-MM-DD'
  endDate?: string;   // 'YYYY-MM-DD'
}

export function buildOrderGridUrl(f: DrilldownFilters): string {
  const p = new URLSearchParams();
  f.markets?.forEach((m) => p.append('markets', m));
  f.statuses?.forEach((s) => p.append('statuses', s));
  f.customsStatuses?.forEach((c) => p.append('customsStatuses', c));
  f.stockStatuses?.forEach((s) => p.append('stockStatuses', s));
  f.vendors?.forEach((v) => p.append('vendors', v));
  if (f.keyword) p.set('keyword', f.keyword);
  if (f.startDate) p.set('startDate', f.startDate);
  if (f.endDate) p.set('endDate', f.endDate);
  const qs = p.toString();
  return qs ? `/orders?${qs}` : '/orders';
}
```

> 주: `/orders` 라우트 경로는 `App.tsx`의 실제 통합 주문 관리 경로로 맞출 것(구현 시 `grep -n "OrderGrid\|path=" frontend/src/App.tsx` 확인; 다르면 그 값으로 교체).

- [ ] **Step 2: 타입체크 + Commit**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`

```bash
git add frontend/src/pages/dashboard/drilldown.ts
git commit -m "feat(dashboard): 드릴다운 URL 빌더(순수함수)"
```

### Task C3: PeriodControl (기간 컨트롤)

**Interfaces:**
- Produces: `<PeriodControl value={{year,month,unit}} onChange={...} />` + `computeRange(year,month,unit)` → `{start,end}` (ISO_LOCAL_DATE_TIME). 이번 달 기본, ‹ › 로 월 이동, 일/주/월 토글.

- [ ] **Step 1: PeriodControl.tsx 작성**

```typescript
import type { Unit } from './dashboardApi';

export interface PeriodValue { year: number; month: number; unit: Unit; } // month: 1-12

// 선택된 (연,월,단위) → 조회 구간. 일/주는 그 달, 월은 최근 12개월.
export function computeRange(v: PeriodValue): { start: string; end: string } {
  if (v.unit === 'MONTH') {
    const endD = new Date(v.year, v.month, 0); // 그 달 말일
    const startD = new Date(v.year, v.month - 1 - 11, 1); // 12개월 전 1일
    return { start: fmtStart(startD), end: fmtEnd(endD) };
  }
  const startD = new Date(v.year, v.month - 1, 1);
  const endD = new Date(v.year, v.month, 0);
  return { start: fmtStart(startD), end: fmtEnd(endD) };
}
const pad = (n: number) => String(n).padStart(2, '0');
const fmtStart = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T00:00:00`;
const fmtEnd = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T23:59:59`;

export function PeriodControl({ value, onChange }: { value: PeriodValue; onChange: (v: PeriodValue) => void }) {
  const move = (delta: number) => {
    const d = new Date(value.year, value.month - 1 + delta, 1);
    onChange({ ...value, year: d.getFullYear(), month: d.getMonth() + 1 });
  };
  const units: Unit[] = ['DAY', 'WEEK', 'MONTH'];
  const label: Record<Unit, string> = { DAY: '일별', WEEK: '주별', MONTH: '월별' };
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <button onClick={() => move(-1)} style={btn}>‹</button>
        <span style={{ fontWeight: 600, minWidth: 110, textAlign: 'center' }}>{value.year}년 {value.month}월</span>
        <button onClick={() => move(1)} style={btn}>›</button>
      </div>
      <div style={{ display: 'flex', border: '1px solid #d1d5db', borderRadius: 6, overflow: 'hidden' }}>
        {units.map((u) => (
          <button key={u} onClick={() => onChange({ ...value, unit: u })}
            style={{ padding: '6px 14px', border: 'none', cursor: 'pointer',
              background: value.unit === u ? 'var(--primary-color)' : '#fff',
              color: value.unit === u ? '#fff' : '#333' }}>{label[u]}</button>
        ))}
      </div>
    </div>
  );
}
const btn: React.CSSProperties = { padding: '4px 10px', border: '1px solid #d1d5db', borderRadius: 6, background: '#fff', cursor: 'pointer', fontSize: 16 };
```

- [ ] **Step 2: 타입체크 + Commit**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`

```bash
git add frontend/src/pages/dashboard/PeriodControl.tsx
git commit -m "feat(dashboard): 기간 컨트롤(월 이동·일/주/월 토글·구간 계산)"
```

### Task C4: KpiCards + AttentionPanel

**Interfaces:**
- Consumes: fetchSummary, fetchAttention, buildOrderGridUrl, useNavigate.

- [ ] **Step 1: KpiCards.tsx 작성**

```typescript
import { useNavigate } from 'react-router-dom';
import type { Summary } from './dashboardApi';
import { buildOrderGridUrl } from './drilldown';

export function KpiCards({ data, range }: { data?: Summary; range: { start: string; end: string } }) {
  const nav = useNavigate();
  const d0 = range.start.slice(0, 10), d1 = range.end.slice(0, 10);
  const won = (n?: number) => (n == null ? '-' : `${n.toLocaleString()}원`);
  const num = (n?: number) => (n == null ? '-' : n.toLocaleString());
  const cards = [
    { title: '주문 수', value: num(data?.period.orderCount), onClick: () => nav(buildOrderGridUrl({ startDate: d0, endDate: d1 })) },
    { title: '정산금액', value: won(data?.period.settlementSum), onClick: () => nav(buildOrderGridUrl({ startDate: d0, endDate: d1 })) },
    { title: '순수익', value: won(data?.period.profitSum), sub: '실구매가 입력 기준', onClick: () => nav(buildOrderGridUrl({ startDate: d0, endDate: d1 })) },
    { title: '미발주', value: num(data?.current.newCount), onClick: () => nav(buildOrderGridUrl({ statuses: ['NEW'] })) },
    { title: '배송중', value: num(data?.current.shippingCount), onClick: () => nav(buildOrderGridUrl({ statuses: ['DISPATCHED', 'SHIPPED'] })) },
    { title: '통관오류', value: num(data?.current.customsIssueCount), onClick: () => nav(buildOrderGridUrl({ customsStatuses: ['PENDING', 'INVALID_PCCC', 'INVALID_PHONE', 'INVALID_ZIPCODE'] })) },
  ];
  return (
    <div className="dashboard-grid">
      {cards.map((c) => (
        <div className="card" key={c.title} onClick={c.onClick} style={{ cursor: 'pointer' }}>
          <div className="card-title">{c.title}</div>
          <div className="card-value">{c.value}</div>
          {c.sub && <div style={{ fontSize: 11, color: '#9ca3af' }}>{c.sub}</div>}
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 2: AttentionPanel.tsx 작성**

```typescript
import { useNavigate } from 'react-router-dom';
import type { Attention } from './dashboardApi';
import { buildOrderGridUrl } from './drilldown';

const CUSTOMS = ['PENDING', 'INVALID_PCCC', 'INVALID_PHONE', 'INVALID_ZIPCODE'];
const todayMinus = (n: number) => { const d = new Date(); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10); };

export function AttentionPanel({ data }: { data?: Attention }) {
  const nav = useNavigate();
  const rows = [
    { label: '통관 오류/대기', v: data?.customsIssue, to: buildOrderGridUrl({ customsStatuses: CUSTOMS }) },
    { label: '재고부족(품절) 주문', v: data?.outOfStock, to: buildOrderGridUrl({ stockStatuses: ['OUT_OF_STOCK'], statuses: ['NEW', 'PREPARING', 'DISPATCHED'] }) },
    { label: '배송/처리 지연(미발주 1일+)', v: data?.delayed, to: buildOrderGridUrl({ statuses: ['NEW'], endDate: todayMinus(1) }) },
    { label: '반품/취소', v: data?.returnCancel, to: buildOrderGridUrl({ statuses: ['CANCELED', 'RETURNED'] }) },
  ];
  return (
    <div className="card">
      <div className="card-title">문제 / 이상</div>
      {rows.map((r) => (
        <div key={r.label} onClick={() => nav(r.to)}
          style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderBottom: '1px solid #f1f5f9', cursor: 'pointer' }}>
          <span>{r.label}</span>
          <span style={{ fontWeight: 700, color: (r.v ?? 0) > 0 ? '#c62828' : '#9ca3af' }}>{r.v ?? '-'}건</span>
        </div>
      ))}
    </div>
  );
}
```

> 지연 드릴다운은 미발주(NEW) 기준만 링크(대표). PREPARING 지연은 패널 카운트에 합산돼 있고, 필요 시 별도 행으로 분리 가능(YAGNI로 1행 유지).

- [ ] **Step 3: 타입체크 + Commit**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`

```bash
git add frontend/src/pages/dashboard/KpiCards.tsx frontend/src/pages/dashboard/AttentionPanel.tsx
git commit -m "feat(dashboard): KPI 카드 + 이상 패널(드릴다운)"
```

### Task C5: TrendChart (recharts 콤보)

- [ ] **Step 1: TrendChart.tsx 작성**

```typescript
import { ComposedChart, Bar, Line, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import { useNavigate } from 'react-router-dom';
import type { TimeseriesBucket } from './dashboardApi';
import { buildOrderGridUrl } from './drilldown';

export function TrendChart({ data }: { data?: TimeseriesBucket[] }) {
  const nav = useNavigate();
  const onClick = (e: { activeLabel?: string }) => {
    if (e?.activeLabel) nav(buildOrderGridUrl({ startDate: e.activeLabel, endDate: e.activeLabel }));
  };
  return (
    <div className="card" style={{ height: 340 }}>
      <div className="card-title">추이</div>
      <ResponsiveContainer width="100%" height="90%">
        <ComposedChart data={data ?? []} onClick={onClick}>
          <CartesianGrid strokeDasharray="3 3" stroke="#eef2f7" />
          <XAxis dataKey="bucketStart" fontSize={11} />
          <YAxis yAxisId="left" fontSize={11} />
          <YAxis yAxisId="right" orientation="right" fontSize={11} tickFormatter={(v) => `${(v / 10000).toLocaleString()}만`} />
          <Tooltip formatter={(v: number, n) => (n === '주문수' ? `${v}건` : `${v.toLocaleString()}원`)} />
          <Legend />
          <Bar yAxisId="left" dataKey="orderCount" name="주문수" fill="#c7d2fe" radius={[3, 3, 0, 0]} />
          <Line yAxisId="right" dataKey="settlementSum" name="정산금액" stroke="#1565c0" dot={false} strokeWidth={2} />
          <Line yAxisId="right" dataKey="profitSum" name="순수익" stroke="#2e7d32" dot={false} strokeWidth={2} />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
}
```

- [ ] **Step 2: 타입체크 + Commit**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`

```bash
git add frontend/src/pages/dashboard/TrendChart.tsx
git commit -m "feat(dashboard): 추이 콤보차트(주문수 막대·정산/순수익 선)"
```

### Task C6: BreakdownPanels (마켓 도넛·상태 퍼널·상품 Top N·소싱처)

- [ ] **Step 1: BreakdownPanels.tsx 작성**

```typescript
import { PieChart, Pie, Cell, BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { fetchBreakdown, type Dimension } from './dashboardApi';
import { buildOrderGridUrl, type DrilldownFilters } from './drilldown';

const MARKET_COLOR: Record<string, string> = {
  COUPANG: '#c2185b', SMART_STORE: '#689f38', ELEVEN_STREET: '#1565c0',
  GMARKET: '#1b5e20', AUCTION: '#e65100', CAFE24: '#fbc02d',
};
const STATUS_LABEL: Record<string, string> = {
  NEW: '결제완료', PREPARING: '구매준비', DISPATCHED: '배송지시', SHIPPED: '배송중',
  DELIVERED: '배송완료', CANCELED: '취소', RETURNED: '반품', EXCHANGED: '교환', UNKNOWN: '알수없음',
};

export function BreakdownPanels({ range }: { range: { start: string; end: string } }) {
  const nav = useNavigate();
  const d0 = range.start.slice(0, 10), d1 = range.end.slice(0, 10);
  const q = (dim: Dimension, limit = 10) => useQuery({
    queryKey: ['breakdown', dim, range.start, range.end],
    queryFn: () => fetchBreakdown(range.start, range.end, dim, limit),
  });
  const market = q('MARKET'), status = q('STATUS'), product = q('PRODUCT', 10), vendor = q('VENDOR');
  const go = (f: DrilldownFilters) => nav(buildOrderGridUrl({ ...f, startDate: d0, endDate: d1 }));

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
      <div className="card">
        <div className="card-title">마켓별</div>
        <ResponsiveContainer width="100%" height={220}>
          <PieChart>
            <Pie data={market.data ?? []} dataKey="orderCount" nameKey="label" innerRadius={50} outerRadius={80}
              onClick={(e: { key?: string }) => e?.key && go({ markets: [e.key] })}>
              {(market.data ?? []).map((it) => <Cell key={it.key} fill={MARKET_COLOR[it.key] ?? '#9ca3af'} />)}
            </Pie>
            <Tooltip formatter={(v: number) => `${v}건`} />
          </PieChart>
        </ResponsiveContainer>
      </div>

      <div className="card">
        <div className="card-title">주문상태</div>
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={(status.data ?? []).map((s) => ({ ...s, label: STATUS_LABEL[s.key] ?? s.key }))}
            onClick={(e: { activeLabel?: string }) => { const it = (status.data ?? []).find((x) => (STATUS_LABEL[x.key] ?? x.key) === e.activeLabel); if (it) go({ statuses: [it.key] }); }}>
            <XAxis dataKey="label" fontSize={11} /><YAxis fontSize={11} />
            <Tooltip formatter={(v: number) => `${v}건`} />
            <Bar dataKey="orderCount" fill="#93c5fd" radius={[3, 3, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>

      <div className="card">
        <div className="card-title">상품 Top 10</div>
        <ResponsiveContainer width="100%" height={260}>
          <BarChart layout="vertical" data={product.data ?? []}
            onClick={(e: { activeLabel?: string }) => { const it = (product.data ?? []).find((x) => x.label === e.activeLabel); if (it) go({ keyword: it.key }); }}>
            <XAxis type="number" fontSize={11} /><YAxis type="category" dataKey="label" width={120} fontSize={10} />
            <Tooltip formatter={(v: number) => `${v}건`} />
            <Bar dataKey="orderCount" fill="#a7f3d0" radius={[0, 3, 3, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>

      <div className="card">
        <div className="card-title">소싱처별</div>
        <ResponsiveContainer width="100%" height={260}>
          <BarChart layout="vertical" data={vendor.data ?? []}
            onClick={(e: { activeLabel?: string }) => { const it = (vendor.data ?? []).find((x) => x.key === e.activeLabel); if (it) go({ vendors: [it.key] }); }}>
            <XAxis type="number" fontSize={11} /><YAxis type="category" dataKey="key" width={80} fontSize={11} />
            <Tooltip formatter={(v: number) => `${v}건`} />
            <Bar dataKey="orderCount" fill="#fde68a" radius={[0, 3, 3, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
```

> `q()`를 조건부 아닌 고정 4회 호출로 유지(리액트 훅 규칙 — 반복문/조건 금지). 위처럼 4번 명시 호출.

- [ ] **Step 2: 타입체크 + Commit**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit`

```bash
git add frontend/src/pages/dashboard/BreakdownPanels.tsx
git commit -m "feat(dashboard): 분포 패널(마켓 도넛·상태·상품 Top10·소싱처)"
```

### Task C7: Dashboard.tsx 조립 + 라우팅

- [ ] **Step 1: Dashboard.tsx 재작성**

```typescript
import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { PeriodControl, computeRange, type PeriodValue } from './dashboard/PeriodControl';
import { KpiCards } from './dashboard/KpiCards';
import { TrendChart } from './dashboard/TrendChart';
import { BreakdownPanels } from './dashboard/BreakdownPanels';
import { AttentionPanel } from './dashboard/AttentionPanel';
import { fetchSummary, fetchTimeseries, fetchAttention } from './dashboard/dashboardApi';

export default function Dashboard() {
  const now = new Date();
  const [period, setPeriod] = useState<PeriodValue>({ year: now.getFullYear(), month: now.getMonth() + 1, unit: 'DAY' });
  const range = useMemo(() => computeRange(period), [period]);

  const summary = useQuery({ queryKey: ['summary', range.start, range.end], queryFn: () => fetchSummary(range.start, range.end) });
  const timeseries = useQuery({ queryKey: ['timeseries', range.start, range.end, period.unit], queryFn: () => fetchTimeseries(range.start, range.end, period.unit) });
  const attention = useQuery({ queryKey: ['attention'], queryFn: fetchAttention, refetchInterval: 60000 });

  return (
    <div style={{ padding: '16px 24px' }}>
      <h1 style={{ marginBottom: 16 }}>대시보드</h1>
      <PeriodControl value={period} onChange={setPeriod} />
      <KpiCards data={summary.data} range={range} />
      <div style={{ marginTop: 16 }}><TrendChart data={timeseries.data} /></div>
      <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 16, marginTop: 16 }}>
        <BreakdownPanels range={range} />
        <AttentionPanel data={attention.data} />
      </div>
    </div>
  );
}
```

- [ ] **Step 2: 라우트 경로 확인 (기존 대시보드 라우트 유지)**

Run: `grep -n "Dashboard" frontend/src/App.tsx`
Expected: 기존 `Dashboard` import·라우트가 새 default export를 그대로 사용(경로 동일). import 경로가 `./pages/Dashboard`이면 그대로 동작.

- [ ] **Step 3: 빌드 게이트**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: 에러 없음, built 성공

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/Dashboard.tsx
git commit -m "feat(dashboard): 대시보드 조립(기간·KPI·추이·분포·이상)"
```

---

## Track D — 프론트 OrderGrid URL 필터 수용 + 신규 필터 UI

**File Structure:**
- Modify: `frontend/src/pages/OrderGrid.tsx` (URL 파라미터 초기화 + 재고상태·소싱처 필터 UI)
- Modify: `frontend/src/api/orderApi.ts` (fetchOrders에 stockStatuses·vendors 전달)

**Interfaces:**
- Consumes: Track B 파라미터명(`stockStatuses`, `vendors`), Track C drilldown URL 계약.

### Task D1: OrderGrid가 URL 쿼리파라미터를 초기 필터로 수용

- [ ] **Step 1: useSearchParams로 초기 queryParams 구성**

`OrderGrid.tsx` 상단 `OrderGrid` 컴포넌트에서 `useSearchParams()`로 URL을 읽어 `queryParams` 초기값을 만든다. 파라미터 있으면 그 값, 없으면 기존 기본값(종결상태 제외 등 유지). 기존 `useState<{...}>` 초기화 부분을 아래로 교체:

```typescript
import { useSearchParams } from 'react-router-dom';
// ...
const [searchParams] = useSearchParams();
const initialFromUrl = useMemo(() => {
  const getAll = (k: string) => searchParams.getAll(k);
  const markets = getAll('markets');
  const statuses = getAll('statuses');
  const stockStatuses = getAll('stockStatuses');
  const vendors = getAll('vendors');
  const customsStatuses = getAll('customsStatuses');
  const keyword = searchParams.get('keyword') ?? '';
  const startDate = searchParams.get('startDate') ?? defaultStart;
  const endDate = searchParams.get('endDate') ?? defaultEnd;
  const hasAny = markets.length || statuses.length || stockStatuses.length || vendors.length || customsStatuses.length || searchParams.get('keyword');
  return hasAny ? {
    keyword,
    markets: markets.length ? markets : ['COUPANG','SMART_STORE','ELEVEN_STREET','CAFE24','GMARKET','AUCTION'],
    statuses: statuses.length ? statuses : DEFAULT_VISIBLE_STATUSES,
    purchaseStatuses: ['NOT_PURCHASED','PURCHASED','WAITING_STOCK'],
    stockStatuses, vendors, customsStatuses, startDate, endDate,
  } : null;
  // eslint-disable-next-line react-hooks/exhaustive-deps
}, []);
```

그리고 `queryParams` 초기값을 `initialFromUrl ?? { ...기존 기본값 }` 로 설정. `fetchOrders` 호출부에 `stockStatuses`, `vendors`, `customsStatuses`를 함께 전달.

- [ ] **Step 2: 빌드 게이트**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`
Expected: 에러 없음

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/OrderGrid.tsx
git commit -m "feat(order-grid): URL 쿼리파라미터를 초기 필터로 수용(대시보드 드릴다운)"
```

### Task D2: fetchOrders + 주문검색 API에 stockStatuses·vendors 배선

- [ ] **Step 1: orderApi.fetchOrders 확장**

`fetchOrders` 시그니처에 `stockStatuses?: string[]`, `vendors?: string[]`, `customsStatuses?: string[]` 추가하고 URLSearchParams에 append:

```typescript
  if (stockStatuses) stockStatuses.forEach(s => params.append('stockStatuses', s));
  if (vendors) vendors.forEach(v => params.append('vendors', v));
  if (customsStatuses) customsStatuses.forEach(c => params.append('customsStatuses', c));
```

- [ ] **Step 2: 빌드 게이트 + Commit**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`

```bash
git add frontend/src/api/orderApi.ts
git commit -m "feat(order-api): fetchOrders에 재고상태·소싱처·통관상태 파라미터 추가"
```

### Task D3: 통합 주문 관리 필터 패널에 재고상태·소싱처 체크박스 추가

- [ ] **Step 1: OrderFilterPanel에 두 필터 UI 추가**

`OrderFilterPanel`에 재고상태(있음/품절), 소싱처(IHB/AMZ/FTN/COK/OCD/TES/VTB) 체크박스 그룹을 추가하고, `onSearch` 콜백 시그니처에 `stockStatuses`, `vendors`를 포함시킨다(기존 마켓/상태 체크박스 UI 패턴을 그대로 복제). `OrderGrid`의 `onSearch` 핸들러가 이 값을 `queryParams`에 반영.

- [ ] **Step 2: 빌드 게이트 + Commit**

Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build`

```bash
git add frontend/src/pages/OrderGrid.tsx
git commit -m "feat(order-grid): 재고상태·소싱처 필터 UI 추가"
```

---

## Track E — 통합 검증 (A~D 머지 후)

- [ ] **Step 1: 전체 회귀**

Run: `cd backend && ./gradlew test 2>&1 | grep -iE "BUILD (SUCCESS|FAIL)"` → SUCCESSFUL
Run: `cd frontend && npx tsc -p tsconfig.app.json --noEmit && npm run build` → 성공

- [ ] **Step 2: 배포 + 라이브 스모크**

```bash
git push origin main
```
배포 후(api 재기동 확인) 라이브 검증:
```bash
# 요약(이번 달), 시계열, 분포, 이상
ssh -i ssh-key-2026-06-25.key ubuntu@168.107.31.154 "docker exec projects-sbshop-api-1 curl -s 'localhost:8080/api/v1/dashboard/summary?start=2026-07-01T00:00:00&end=2026-07-31T23:59:59'"
ssh ... "curl -s 'localhost:8080/api/v1/dashboard/attention'"
```
Expected: JSON 정상. 대시보드 화면에서 기간 이동·차트·드릴다운(클릭→통합 주문 관리 필터 적용) 수동 확인.

- [ ] **Step 3: QA 경계면 교차검증(integration-qa)**

대시보드 집계 건수와 동일 조건 주문검색 건수가 일치하는지(예: 마켓=쿠팡·이번 달 → breakdown.COUPANG.orderCount == /orders?markets=COUPANG&기간 총건수) integration-qa로 검증.

---

## Self-Review 결과 (작성자 점검)

- **스펙 커버리지**: 시간모델(A1)·집계 4종(A3/A4)·분포 4축(C6)·이상 4종(A3/C4)·드릴다운(C2/D)·재고상태·소싱처 필터확장(B/D2/D3)·순수익 주석(C4)·빈 구간 0채움(A1/A3) — 모두 태스크 존재. KPI 카드(C4)·기간 컨트롤(C3)·recharts(C1) 포함.
- **플레이스홀더 스캔**: 구현 코드는 실제 코드 제시. UI 반복 컴포넌트(D3)는 "기존 마켓/상태 체크박스 패턴 복제"로 지시(동일 코드가 OrderGrid에 이미 존재 — 참조 명시). 
- **타입 일관성**: 파라미터명 `stockStatuses`/`vendors`가 B(condition)·C2(drilldown)·D1/D2(orderApi)에서 일치. `bucketStart` 문자열 계약이 A0·A3·C5 일치. `Dimension`/`Unit` enum 이름 A0·dashboardApi 일치.
- **알려진 구현 시 확인 항목(코드에 주석으로 명시)**: Product.stockStatus 실제 타입(A2 Step3), `/orders` 라우트 실제 경로(C2), OrderRepositoryImpl lineItem exists 패턴 실제 위치(B2).
