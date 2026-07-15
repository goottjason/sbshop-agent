package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.order.service.MarketShippingResult;
import com.sbshop.agent.core.application.order.service.MarketplaceShippingService;
import com.sbshop.agent.core.config.EmailAccountProperties;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;

/**
 * processIherbShipment 는 private 이므로 테스트 위해 package-private 로 완화하고
 * 동일 패키지에서 직접 호출한다. (public 진입점은 IMAP 연결을 요구하여 단위테스트 부적합)
 */
@ExtendWith(MockitoExtension.class)
class EmailFetcherServiceTest {

	@Mock
	EmailAccountProperties properties;
	@Mock
	OrderEmailParser parser;
	@Mock
	OrderLineItemRepository orderLineItemRepository;
	@Mock
	OrderRepository orderRepository;
	@Mock
	MarketplaceShippingService marketplaceShippingService;

	@InjectMocks
	EmailFetcherService service;

	@Captor
	ArgumentCaptor<OrderLineItem> savedItemCaptor;

	private OrderLineItem shippedItem(String trackingNo, ShippingCarrier carrier) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.sourcingData(SourcingData.builder().sourcingOrderNo("IHERB-1").build())
			.shippingData(ShippingData.builder()
				.trackingNo(trackingNo)
				.shippingCarrier(carrier)
				.shippingStatus(ShippingStatus.SHIPPED)
				.trackingSentToMarket(true)
				.build())
			.build();
	}

	private OrderEmailParser.IherbShipmentData shipment(String trackingNo, String carrier) {
		return OrderEmailParser.IherbShipmentData.builder()
			.orderNo("IHERB-1")
			.trackingNo(trackingNo)
			.carrier(carrier)
			.emailAccount("test@iherb")
			.build();
	}

	@Test
	void 이미SHIPPED이지만_이메일_송장이_다르면_진짜송장으로_교정하고_마켓수정경로로_전송한다() {
		OrderLineItem item = shippedItem("FAKE123", ShippingCarrier.CJ_LOGISTICS);
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("IHERB-1"))
			.thenReturn(List.of(item));
		when(marketplaceShippingService.sendTrackingToMarketplace(any(), eq(true)))
			.thenReturn(MarketShippingResult.ofSent());

		service.processIherbShipment(shipment("REAL999", "DHL"));

		// 저장된 아이템의 송장이 진짜 송장으로 교정됨
		verify(orderLineItemRepository, times(2)).save(savedItemCaptor.capture());
		assertThat(savedItemCaptor.getValue().getShippingData().getTrackingNo()).isEqualTo("REAL999");
		// 상태는 SHIPPED 유지
		assertThat(savedItemCaptor.getValue().getShippingData().getShippingStatus())
			.isEqualTo(ShippingStatus.SHIPPED);
		// 이메일 택배사(DHL→ETC)는 unknown 취급 → 기존 택배사(CJ) 유지
		assertThat(savedItemCaptor.getValue().getShippingData().getShippingCarrier())
			.isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		// 마켓 수정 경로(update, invoiceAlreadyExists=true) 로 전송
		verify(marketplaceShippingService).sendTrackingToMarketplace(any(), eq(true));
		verify(marketplaceShippingService, never()).sendTrackingToMarketplace(any(), eq(false));
	}

	@Test
	void 이미SHIPPED이고_이메일_송장이_같으면_교정_경로를_타지않는다() {
		OrderLineItem item = shippedItem("SAME555", ShippingCarrier.CJ_LOGISTICS);
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("IHERB-1"))
			.thenReturn(List.of(item));

		service.processIherbShipment(shipment("SAME555", "DHL"));

		// alreadyShipped 이면서 이미 마켓 동기화 완료 → 아무 전송/저장도 하지 않음
		verify(marketplaceShippingService, never()).sendTrackingToMarketplace(any(), eq(true));
		verify(orderLineItemRepository, never()).save(any());
	}

	// F-MISC-18: 재진입/동시실행 가드
	// EmailFetchController(수동)와 OrderSyncScheduler(cron) 가 같은 worker JVM에서
	// fetchAndProcessEmails()를 동시에 호출하면 같은 발송메일을 이중 처리 → 마켓 중복 송장 전송.
	// 이미 실행 중이면 두 번째 호출은 본처리(DB 조회)를 스킵해야 한다.

	@Test
	void 이미_실행중이면_두번째_fetchAndProcessEmails_호출은_본처리를_스킵한다() throws Exception {
		when(properties.getAccounts())
			.thenReturn(List.of(new EmailAccountProperties.Account()));

		// 첫 호출이 락을 잡고 본처리 진입한 순간, 같은 서비스로 재호출 시도.
		// 첫 호출의 본처리(DB 조회) 지점에서 재진입을 유발하여 두 번째가 스킵되는지 검증.
		java.util.concurrent.atomic.AtomicBoolean reentrantProcessed =
			new java.util.concurrent.atomic.AtomicBoolean(false);
		when(orderLineItemRepository.findIherbItemsNeedingEmailProcessing())
			.thenAnswer(inv -> {
				// 락을 보유한 채 재진입 — 두 번째 호출은 스킵되어야 하므로
				// 이 스텁이 두 번 호출되면 안 된다.
				if (reentrantProcessed.compareAndSet(false, true)) {
					service.fetchAndProcessEmails(); // 재진입 시도
				}
				return List.of();
			});

		service.fetchAndProcessEmails();

		// 본처리(findIherbItemsNeedingEmailProcessing)는 재진입에도 불구하고 정확히 1회만 실행.
		verify(orderLineItemRepository, times(1)).findIherbItemsNeedingEmailProcessing();
	}

	@Test
	void 실행_종료후_락이_해제되어_다음_호출은_정상_진행한다() {
		when(properties.getAccounts())
			.thenReturn(List.of(new EmailAccountProperties.Account()));
		when(orderLineItemRepository.findIherbItemsNeedingEmailProcessing())
			.thenReturn(List.of());

		service.fetchAndProcessEmails();
		service.fetchAndProcessEmails();

		// 순차 호출은 매번 본처리 진입 (락 정상 해제)
		verify(orderLineItemRepository, times(2)).findIherbItemsNeedingEmailProcessing();
	}
}
