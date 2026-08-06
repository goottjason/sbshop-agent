package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.service.MarketShippingResult;
import com.sbshop.agent.core.application.order.service.MarketplaceShippingService;
import com.sbshop.agent.core.config.EmailAccountProperties;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;
import com.sbshop.agent.core.application.order.service.LineItemShippingWriter;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * D-123: 마켓 전송이 영구 거부(terminal)로 종결될 때 남기는 감사 로그가 마켓을 "COUPANG"으로
 * 하드코딩하고 있었다. 11번가·Cafe24 건까지 쿠팡으로 기록되면 원인 추적이 어긋난다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailFetcherTerminalMarketLogTest {

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
	@Mock
	ActionLogService actionLogService;

	@InjectMocks
	EmailFetcherService service;

	@Mock
	ShipmentRepository shipmentRepository;

	/**
	 * D-133: 송장 쓰기 통로는 <b>진짜 객체</b>를 끼운다. {@code @InjectMocks}가 목을 넣거나 null로
	 * 남기면 라인아이템 쓰기 자체가 사라져, 검증이 통과해도 아무것도 증명하지 못한다.
	 * 이 테스트들의 라인아이템은 {@code shipment_id}가 null이므로 통로는 배송을 건드리지 않는다 —
	 * 종전과 동작이 같다는 사실이 곧 회귀 증거다.
	 */
	@BeforeEach
	void injectRealShippingWriter() {
		ReflectionTestUtils.setField(service, "shippingWriter",
			new LineItemShippingWriter(shipmentRepository, orderLineItemRepository));
	}

	@Captor
	ArgumentCaptor<String> marketCaptor;

	private OrderEmailParser.IherbShipmentData shipment() {
		return OrderEmailParser.IherbShipmentData.builder()
			.orderNo("IHERB-1")
			.trackingNo("424438293101")
			.carrier("CJGLS")
			.emailAccount("test@iherb")
			.build();
	}

	@Test
	@DisplayName("D-123: 11번가 영구거부 종결 로그에 실제 마켓(ELEVEN_STREET)이 기록된다")
	void terminalLog_recordsActualMarketType() {
		OrderLineItem item = OrderLineItem.builder()
			.orderId(77L)
			.quantity(1)
			.sourcingData(SourcingData.builder().sourcingOrderNo("IHERB-1").build())
			.shippingData(ShippingData.builder()
				.trackingNo("OLD-1")
				.shippingStatus(ShippingStatus.SHIPPED)
				.build())
			.build();

		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("IHERB-1"))
			.thenReturn(List.of(item));
		when(orderRepository.findById(77L)).thenReturn(Optional.of(
			Order.builder().marketType(MarketType.ELEVEN_STREET).marketOrderNo("2026").build()));
		when(marketplaceShippingService.sendTrackingToMarketplace(any(), anyBoolean()))
			.thenReturn(MarketShippingResult.ofTerminal("11번가 발송처리 실패: 존재하지 않는 배송번호 입니다."));

		service.processIherbShipment(shipment());

		verify(actionLogService).record(any(), marketCaptor.capture(), eq(ActionStatus.FAILED), any());
		assertThat(marketCaptor.getValue()).isEqualTo("ELEVEN_STREET");
	}
}
