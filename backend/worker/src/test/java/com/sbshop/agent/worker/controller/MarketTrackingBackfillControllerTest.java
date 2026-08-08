package com.sbshop.agent.worker.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.service.Cafe24OrderSyncService;
import com.sbshop.agent.core.application.order.service.CoupangOrderSyncService;
import com.sbshop.agent.core.application.order.service.ElevenstOrderSyncService;
import com.sbshop.agent.core.application.order.service.SmartStoreOrderSyncService;
import com.sbshop.agent.core.config.InternalAccessGuard;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 마켓 보유 송장 백필 트리거의 안전장치.
 *
 * <p>이 트리거는 네 마켓 API를 넓은 기간으로 호출한다 — 실수로 부르면 부담이 크고, 무인증이면
 * 외부에서 부를 수 있다. 그래서 토큰 가드와 기간 상한을 계약으로 고정한다.
 */
class MarketTrackingBackfillControllerTest {

	private CoupangOrderSyncService coupang;
	private SmartStoreOrderSyncService smartStore;
	private ElevenstOrderSyncService elevenst;
	private Cafe24OrderSyncService cafe24;
	private InternalAccessGuard guard;
	private MarketTrackingBackfillController controller;

	@BeforeEach
	void setUp() {
		coupang = mock(CoupangOrderSyncService.class);
		smartStore = mock(SmartStoreOrderSyncService.class);
		elevenst = mock(ElevenstOrderSyncService.class);
		cafe24 = mock(Cafe24OrderSyncService.class);
		guard = mock(InternalAccessGuard.class);
		controller = new MarketTrackingBackfillController(coupang, smartStore, elevenst, cafe24, guard);
	}

	@Test
	@DisplayName("토큰이 없으면 403 — 어떤 마켓 동기화도 시작하지 않는다")
	void rejectsWithoutToken() {
		when(guard.isAllowed(null)).thenReturn(false);

		var response = controller.backfill(120, null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		verify(coupang, never()).syncCoupangOrders(org.mockito.ArgumentMatchers.anyInt());
		verify(smartStore, never()).syncSmartStoreOrders(org.mockito.ArgumentMatchers.anyInt());
		verify(elevenst, never()).syncElevenstOrders(org.mockito.ArgumentMatchers.anyInt());
		verify(cafe24, never()).syncCafe24Orders(org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	@DisplayName("네 마켓 모두 같은 기간으로 시작한다 — 일관성이 이 백필의 목적이다")
	void triggersAllFourMarkets() {
		when(guard.isAllowed("t")).thenReturn(true);

		var response = controller.backfill(120, "t");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(coupang).syncCoupangOrders(120);
		verify(smartStore).syncSmartStoreOrders(120);
		verify(elevenst).syncElevenstOrders(120);
		verify(cafe24).syncCafe24Orders(120);
	}

	@Test
	@DisplayName("한 마켓이 실패해도 나머지는 계속 시작한다 — 백필은 전부-또는-전무일 이유가 없다")
	void oneMarketFailureDoesNotBlockOthers() {
		when(guard.isAllowed("t")).thenReturn(true);
		doThrow(new RuntimeException("자격증명 없음")).when(coupang).syncCoupangOrders(90);

		var response = controller.backfill(90, "t");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(smartStore).syncSmartStoreOrders(90);
		verify(elevenst).syncElevenstOrders(90);
		verify(cafe24).syncCafe24Orders(90);

		@SuppressWarnings("unchecked")
		Map<String, Object> markets = (Map<String, Object>) response.getBody().get("markets");
		assertThat(String.valueOf(markets.get("COUPANG"))).startsWith("failed");
		assertThat(markets.get("ELEVEN_STREET")).isEqualTo("started");
	}

	@Test
	@DisplayName("기간이 상한(365일)을 넘거나 0 이하면 거부한다 — 실수로 마켓 API를 과호출하지 않게")
	void rejectsOutOfRangeDays() {
		when(guard.isAllowed("t")).thenReturn(true);

		assertThat(controller.backfill(0, "t").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(controller.backfill(366, "t").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		verify(coupang, never()).syncCoupangOrders(org.mockito.ArgumentMatchers.anyInt());
	}
}
