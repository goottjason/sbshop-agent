package com.sbshop.agent.core.application.actionlog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActionLogSyncListenerTest {

	@Mock
	private ActionLogService actionLogService;
	@Mock
	private SyncStatusService syncStatusService;
	@Mock
	private OrderRepository orderRepository;
	@InjectMocks
	private ActionLogSyncListener listener;

	@Test
	void success_recordsSuccess() {
		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.COUPANG));

		verify(actionLogService).record(
			ArgumentMatchers.eq("COUPANG_SYNC"),
			ArgumentMatchers.eq("COUPANG"),
			ArgumentMatchers.eq(ActionStatus.SUCCESS),
			ArgumentMatchers.anyString());
	}

	@Test
	void failure_recordsFailedWithErrorMessage() {
		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.COUPANG, false,
			"쿠팡 주문 조회 실패: 403"));

		verify(actionLogService).record(
			ArgumentMatchers.eq("COUPANG_SYNC"),
			ArgumentMatchers.eq("COUPANG"),
			ArgumentMatchers.eq(ActionStatus.FAILED),
			ArgumentMatchers.contains("403"));
	}

	@Test
	@DisplayName("성공 메시지에 처리 건수와 신규 건수가 남는다 — 0건 성공을 사후에 구분할 수 있어야 한다")
	void success_recordsCounts() {
		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.GMARKET, 12, 3));

		verify(actionLogService).record(
			ArgumentMatchers.eq("GMARKET_SYNC"),
			ArgumentMatchers.eq("GMARKET"),
			ArgumentMatchers.eq(ActionStatus.SUCCESS),
			ArgumentMatchers.contains("처리 12건, 신규 3건"));
	}

	@Test
	@DisplayName("신규 0건이 임계를 넘겨 지속되면 별도 경고 로그를 남긴다")
	void staleNewOrders_recordsWarning() {
		when(syncStatusService.lastNewAt(SyncMarketKeys.GMARKET))
			.thenReturn(Optional.of(LocalDateTime.now().minusDays(12)));
		when(orderRepository.countByMarketTypeInAndOrderDateGreaterThanEqual(any(), any()))
			.thenReturn(45L);

		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.GMARKET, 8, 0));

		verify(actionLogService).record(
			ArgumentMatchers.eq("GMARKET_SYNC_STALE"),
			ArgumentMatchers.eq("GMARKET"),
			ArgumentMatchers.eq(ActionStatus.WARNING),
			ArgumentMatchers.contains("신규 주문이"));
	}

	@Test
	@DisplayName("신규 0건이어도 임계 이내면 경고하지 않는다")
	void freshEnough_doesNotWarn() {
		when(syncStatusService.lastNewAt(SyncMarketKeys.GMARKET))
			.thenReturn(Optional.of(LocalDateTime.now().minusDays(3)));
		when(orderRepository.countByMarketTypeInAndOrderDateGreaterThanEqual(any(), any()))
			.thenReturn(10L);

		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.GMARKET, 8, 0));

		verify(actionLogService, never()).record(
			ArgumentMatchers.eq("GMARKET_SYNC_STALE"),
			ArgumentMatchers.anyString(),
			ArgumentMatchers.any(),
			ArgumentMatchers.anyString());
	}

	@Test
	@DisplayName("신규가 들어온 회차는 공백이 길었더라도 경고하지 않는다")
	void newOrdersArrived_doesNotWarn() {
		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.GMARKET, 8, 2));

		verify(actionLogService, never()).record(
			ArgumentMatchers.eq("GMARKET_SYNC_STALE"),
			ArgumentMatchers.anyString(),
			ArgumentMatchers.any(),
			ArgumentMatchers.anyString());
	}

	@Test
	@DisplayName("D-282: GMARKET 동기화의 임계 계산은 GMARKET과 AUCTION 주문을 합산한 건수로 한다")
	void gmarketThresholdCountsGmarketAndAuction() {
		when(syncStatusService.lastNewAt(SyncMarketKeys.GMARKET))
			.thenReturn(Optional.of(LocalDateTime.now().minusDays(10)));
		when(orderRepository.countByMarketTypeInAndOrderDateGreaterThanEqual(any(), any()))
			.thenReturn(60L);

		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.GMARKET, 8, 0));

		verify(orderRepository).countByMarketTypeInAndOrderDateGreaterThanEqual(
			eq(List.of(MarketType.GMARKET, MarketType.AUCTION)), any());
	}

	@Test
	@DisplayName("D-282: AUCTION 동기화도 GMARKET과 합산된 건수로 판정한다")
	void auctionThresholdCountsGmarketAndAuction() {
		when(syncStatusService.lastNewAt(SyncMarketKeys.GMARKET))
			.thenReturn(Optional.of(LocalDateTime.now().minusDays(10)));
		when(orderRepository.countByMarketTypeInAndOrderDateGreaterThanEqual(any(), any()))
			.thenReturn(60L);

		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.AUCTION, 8, 0));

		verify(orderRepository).countByMarketTypeInAndOrderDateGreaterThanEqual(
			eq(List.of(MarketType.GMARKET, MarketType.AUCTION)), any());
	}

	@Test
	@DisplayName("D-282: 단일 마켓(쿠팡)은 합산 없이 자기 건수만으로 판정한다")
	void coupangThresholdCountsOnlyItself() {
		when(syncStatusService.lastNewAt(SyncMarketKeys.COUPANG))
			.thenReturn(Optional.of(LocalDateTime.now().minusDays(5)));
		when(orderRepository.countByMarketTypeInAndOrderDateGreaterThanEqual(any(), any()))
			.thenReturn(75L);

		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.COUPANG, 8, 0));

		verify(orderRepository).countByMarketTypeInAndOrderDateGreaterThanEqual(
			eq(List.of(MarketType.COUPANG)), any());
	}

	@Test
	@DisplayName("D-282: 경고 메시지의 임계 문구는 계산된 값을 반영한다 — 하드코딩 상수가 아니다")
	void warningMessageReflectsComputedThreshold() {
		when(syncStatusService.lastNewAt(SyncMarketKeys.COUPANG))
			.thenReturn(Optional.of(LocalDateTime.now().minusDays(5)));
		when(orderRepository.countByMarketTypeInAndOrderDateGreaterThanEqual(any(), any()))
			.thenReturn(75L);

		listener.onSyncCompleted(new SyncCompletedEvent(this, MarketType.COUPANG, 8, 0));

		verify(actionLogService).record(
			ArgumentMatchers.eq("COUPANG_SYNC_STALE"),
			ArgumentMatchers.eq("COUPANG"),
			ArgumentMatchers.eq(ActionStatus.WARNING),
			ArgumentMatchers.contains("임계 3일"));
	}
}
