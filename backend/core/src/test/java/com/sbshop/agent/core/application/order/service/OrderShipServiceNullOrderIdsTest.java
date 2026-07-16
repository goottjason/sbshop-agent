package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sbshop.agent.core.application.order.dto.BulkShipResult;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-ORD-33(오탐 확인): 발송 시 orderIds가 null이면 NPE(500)로 터진다는 결함 신고에 대한 재현/반증 테스트.
 * 진입부 null 가드(OrderShipService:38-43)가 이미 존재하므로 NPE 없이 빈 결과를 반환한다 — 오탐 근거를 고정한다.
 */
class OrderShipServiceNullOrderIdsTest {

	private OrderRepository orderRepository;
	private MarketCredentialRepository credentialRepository;
	private OrderLineItemRepository orderLineItemRepository;
	private MarketplaceShippingService marketplaceShippingService;
	private OrderShipService service;

	@BeforeEach
	void setUp() {
		orderRepository = mock(OrderRepository.class);
		credentialRepository = mock(MarketCredentialRepository.class);
		orderLineItemRepository = mock(OrderLineItemRepository.class);
		marketplaceShippingService = mock(MarketplaceShippingService.class);
		service = new OrderShipService(orderRepository, credentialRepository,
			orderLineItemRepository, marketplaceShippingService);
	}

	@Test
	@DisplayName("orderIds가 null이어도 NPE 없이 빈 결과를 반환한다(진입부 null 가드 존재)")
	void bulkShipOrders_nullOrderIds_returnsEmptyResultWithoutNpe() {
		BulkShipResult[] holder = new BulkShipResult[1];

		assertThatCode(() -> holder[0] = service.bulkShipOrders(null)).doesNotThrowAnyException();

		BulkShipResult result = holder[0];
		assertThat(result).isNotNull();
		assertThat(result.getSuccessCount()).isZero();
		assertThat(result.getFailedCount()).isZero();
		assertThat(result.getSkippedCount()).isZero();
		assertThat(result.getFailedIds()).isEmpty();
		assertThat(result.getErrors()).isNull();
		// null 입력은 조회조차 하지 않고 조기 반환한다(부작용 없음).
		verifyNoInteractions(orderRepository, credentialRepository,
			orderLineItemRepository, marketplaceShippingService);
	}
}
