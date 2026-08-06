package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.adapter.CoupangOrderAdapter;
import com.sbshop.agent.core.application.order.adapter.SmartStoreOrderAdapter;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.domain.fee.repository.FeePolicyRepository;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-074: 진행(progressed) 주문의 수기 보정 주소/우편번호가 동기화로 덮어써지지 않는지 검증.
 * 대표로 스마트스토어(가드 부재였던 3마켓)와 쿠팡 zipcode 세트 보호 회귀를 함께 확인.
 */
@ExtendWith(MockitoExtension.class)
class OrderAddressProtectionTest {

	@Mock private MarketCredentialRepository credentialRepository;
	@Mock private OrderRepository orderRepository;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private ProductRepository productRepository;
	@Mock private MarketRegistrationRepository marketRegistrationRepository;
	@Mock private ApplicationEventPublisher eventPublisher;
	@Mock private SmartStoreOrderAdapter smartStoreOrderAdapter;
	@Mock private CoupangOrderAdapter coupangOrderAdapter;
	@Mock private CoupangStatusMapper coupangStatusMapper;
	@Mock private com.sbshop.agent.core.application.sync.SyncStatusService syncStatusService;

	// 코드 기본요율(빈 정책 폴백)로 정산액을 계산하도록 실제 인스턴스 사용
	private final MarketFeeService marketFeeService = new MarketFeeService(mock(FeePolicyRepository.class));

	private static final String MANUAL_ADDRESS = "서울시 강남구 수기보정로 99";
	private static final String MANUAL_ZIP = "06000";
	private static final String MARKET_ADDRESS = "부산시 해운대구 마켓주소 1";
	private static final String MARKET_ZIP = "48000";

	private MarketCredential validCredential() {
		return MarketCredential.builder().clientId("cid").secretKey("sec").accessKey("akey").build();
	}

	private Order existingOrder() {
		return Order.builder()
			.marketType(MarketType.SMART_STORE)
			.marketOrderNo("SS-1")
			.orderDate(LocalDateTime.now())
			.recipientName("김수취")
			.zipcode(MANUAL_ZIP)
			.address(MANUAL_ADDRESS)
			.build();
	}

	private OrderLineItem lineItem(ShippingStatus status) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}

	private MarketOrderDto marketDto(ShippingStatus status) {
		return MarketOrderDto.builder()
			.marketOrderNo("SS-1")
			.marketType(MarketType.SMART_STORE)
			.recipientName("김수취")
			.zipcode(MARKET_ZIP)
			.address(MARKET_ADDRESS)
			.status(status)
			.build();
	}

	private SmartStoreOrderSyncService smartStoreService() {
		return new SmartStoreOrderSyncService(credentialRepository, orderRepository,
			orderLineItemRepository, productRepository, eventPublisher, smartStoreOrderAdapter,
			syncStatusService, marketFeeService,
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.TerminalSettlementService.class),
			// 이 테스트는 주문 계층(자격정보·이벤트·주소 보호)만 검증한다 — 라인아이템 반영은 범위 밖이라 골격을 목으로 둔다.
			org.mockito.Mockito.mock(MarketLineItemSyncDispatcher.class));
	}

	@Test
	@DisplayName("[스마트스토어] progressed 주문은 동기화해도 수기 주소·우편번호를 유지한다")
	void smartStoreProtectsAddressAndZipcodeWhenProgressed() {
		Order order = existingOrder();
		when(credentialRepository.findByMarketType(MarketType.SMART_STORE))
			.thenReturn(Optional.of(validCredential()));
		when(smartStoreOrderAdapter.fetchOrders(any(), any(), any()))
			.thenReturn(List.of(marketDto(ShippingStatus.PREPARING)));
		when(orderRepository.findByMarketOrderNo("SS-1")).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any()))
			.thenReturn(List.of(lineItem(ShippingStatus.PREPARING)));

		smartStoreService().syncSmartStoreOrders();

		assertThat(order.getAddress()).isEqualTo(MANUAL_ADDRESS);
		assertThat(order.getZipcode()).isEqualTo(MANUAL_ZIP);
	}

	@Test
	@DisplayName("[스마트스토어] progressed 아닌(NEW) 주문은 마켓 주소·우편번호로 갱신한다")
	void smartStoreUpdatesAddressAndZipcodeWhenNotProgressed() {
		Order order = existingOrder();
		when(credentialRepository.findByMarketType(MarketType.SMART_STORE))
			.thenReturn(Optional.of(validCredential()));
		when(smartStoreOrderAdapter.fetchOrders(any(), any(), any()))
			.thenReturn(List.of(marketDto(ShippingStatus.NEW)));
		when(orderRepository.findByMarketOrderNo("SS-1")).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any()))
			.thenReturn(List.of(lineItem(ShippingStatus.NEW)));

		smartStoreService().syncSmartStoreOrders();

		assertThat(order.getAddress()).isEqualTo(MARKET_ADDRESS);
		assertThat(order.getZipcode()).isEqualTo(MARKET_ZIP);
	}

	@Test
	@DisplayName("[쿠팡] progressed 주문은 zipcode도 수기값을 유지한다(세트 보호 회귀)")
	void coupangProtectsZipcodeWhenProgressed() {
		Order order = Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("CP-1")
			.orderDate(LocalDateTime.now())
			.zipcode(MANUAL_ZIP)
			.address(MANUAL_ADDRESS)
			.build();
		MarketCredential cred = MarketCredential.builder()
			.clientId("cid").accessKey("akey").secretKey("sec").build();
		when(credentialRepository.findByMarketType(MarketType.COUPANG)).thenReturn(Optional.of(cred));
		when(coupangOrderAdapter.fetchOrders(any(), any(LocalDate.class), any(LocalDate.class)))
			.thenReturn(List.of(MarketOrderDto.builder()
				.marketOrderNo("CP-1").marketType(MarketType.COUPANG)
				.zipcode(MARKET_ZIP).address(MARKET_ADDRESS)
				.status(ShippingStatus.PREPARING).build()));
		when(orderRepository.findByMarketOrderNo("CP-1")).thenReturn(Optional.of(order));
		when(orderLineItemRepository.findByOrderId(any()))
			.thenReturn(List.of(lineItem(ShippingStatus.PREPARING)));
		lenient().when(orderLineItemRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		CoupangOrderSyncService service = new CoupangOrderSyncService(credentialRepository,
			orderRepository, orderLineItemRepository, productRepository,
			marketRegistrationRepository, eventPublisher, coupangOrderAdapter, coupangStatusMapper,
			syncStatusService, marketFeeService,
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.TerminalSettlementService.class),
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.actionlog.ActionLogService.class),
			// 3계층 반영 골격. 이 테스트들은 라인아이템 반영을 검증하지 않으므로 목으로 둔다.
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.service.MarketLineItemSyncDispatcher.class));
		service.syncCoupangOrders();

		assertThat(order.getAddress()).isEqualTo(MANUAL_ADDRESS);
		assertThat(order.getZipcode()).isEqualTo(MANUAL_ZIP);
	}
}
