package com.sbshop.agent.core.application.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderMarketRefresherTest {

	@Mock
	OrderReconciliationService reconciliationService;
	@Mock
	CoupangOrderSyncService coupangOrderSyncService;
	@Mock
	Cafe24OrderSyncService cafe24OrderSyncService;
	@Mock
	ElevenstOrderSyncService elevenstOrderSyncService;
	@Mock
	SmartStoreOrderSyncService smartStoreOrderSyncService;

	@InjectMocks
	OrderMarketRefresher refresher;

	@Test
	@DisplayName("단건은 확증 서비스에 그대로 위임한다 — 반영 규칙이 두 벌이 되지 않는다")
	void refreshOneDelegates() {
		Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("ORD-1").build();

		refresher.refreshOne(order);

		verify(reconciliationService).reconcileOne(order);
	}

	@Test
	@DisplayName("단건 재조회가 실패해도 명령 자체를 되돌리지 않는다 — 마켓에는 이미 나갔다")
	void refreshOneSwallowsFailure() {
		Order order = Order.builder().marketType(MarketType.COUPANG).marketOrderNo("ORD-1").build();
		doThrow(new RuntimeException("조회 실패")).when(reconciliationService).reconcileOne(order);

		refresher.refreshOne(order);

		verify(reconciliationService).reconcileOne(order);
	}

	@Test
	@DisplayName("일괄 뒤에는 마켓별 목록을 한 번씩만 훑는다 — 건마다 단건 조회하지 않는다")
	void refreshAfterBulkCallsListOncePerMarket() {
		refresher.refreshAfterBulk(Set.of(MarketType.COUPANG, MarketType.GMARKET));

		verify(coupangOrderSyncService).syncCoupangOrders(any(LocalDate.class), any(LocalDate.class), eq(false));
		verify(cafe24OrderSyncService).syncCafe24Orders(any(LocalDate.class), any(LocalDate.class), eq(false));
		verify(reconciliationService, never()).reconcileOne(any());
	}

	@Test
	@DisplayName("G마켓과 옥션이 섞여 있어도 카페24 목록은 한 번만 부른다 — 같은 경로다")
	void gmarketAndAuctionShareOneCafe24Call() {
		refresher.refreshAfterBulk(Set.of(MarketType.GMARKET, MarketType.AUCTION));

		verify(cafe24OrderSyncService).syncCafe24Orders(any(LocalDate.class), any(LocalDate.class), eq(false));
	}

	@Test
	@DisplayName("한 마켓 목록 조회가 실패해도 나머지는 계속 갱신한다")
	void oneMarketFailureDoesNotStopOthers() {
		doThrow(new RuntimeException("쿠팡 실패")).when(coupangOrderSyncService)
			.syncCoupangOrders(any(LocalDate.class), any(LocalDate.class), eq(false));

		refresher.refreshAfterBulk(Set.of(MarketType.COUPANG, MarketType.ELEVEN_STREET));

		verify(elevenstOrderSyncService).syncElevenstOrders(any(LocalDate.class), any(LocalDate.class), eq(false));
	}

	@Test
	@DisplayName("대상 마켓이 없으면 아무 것도 부르지 않는다")
	void emptyMarketsCallsNothing() {
		refresher.refreshAfterBulk(Set.of());

		verify(coupangOrderSyncService, never()).syncCoupangOrders(any(LocalDate.class), any(LocalDate.class),
			eq(false));
	}
}
