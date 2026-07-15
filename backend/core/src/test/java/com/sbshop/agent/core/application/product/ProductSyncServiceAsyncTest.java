package com.sbshop.agent.core.application.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-MISC-8/9/10: 재고 동기화 오케스트레이션의 @Async 정상화.
 * 컨트롤러의 원시 {@code new Thread} + 대상선정 로직을 서비스로 이동하고,
 * 크롤 성공/실패를 ActionLog(SUCCESS/FAILED)로 기록하는지 검증한다.
 * (@Async는 단위테스트에서 동기 실행되므로 검증 가능)
 */
@ExtendWith(MockitoExtension.class)
class ProductSyncServiceAsyncTest {

	@Mock private ProductRepository productRepository;
	@Mock private ProductStockCrawlerPort productStockCrawlerPort;
	@Mock private OrderLineItemRepository orderLineItemRepository;
	@Mock private ActionLogService actionLogService;

	private ProductSyncService service;

	@BeforeEach
	void setUp() {
		service = new ProductSyncService(
			productRepository, productStockCrawlerPort, orderLineItemRepository, actionLogService);
	}

	@Test
	@DisplayName("대상선정: NEW·PREPARING 상품ID를 조회·중복제거해 크롤 대상으로 삼는다")
	void selectsTargetsFromNewAndPreparing() {
		when(orderLineItemRepository.findProductIdsByShippingStatus(ShippingStatus.NEW))
			.thenReturn(List.of(1L, 2L));
		when(orderLineItemRepository.findProductIdsByShippingStatus(ShippingStatus.PREPARING))
			.thenReturn(List.of(2L, 3L));

		service.syncStockForPreparingOrdersAsync();

		verify(orderLineItemRepository).findProductIdsByShippingStatus(ShippingStatus.NEW);
		verify(orderLineItemRepository).findProductIdsByShippingStatus(ShippingStatus.PREPARING);
	}

	@Test
	@DisplayName("성공 경로: 크롤 완료 시 ActionLog SUCCESS를 기록한다")
	void recordsSuccessOnCompletion() {
		when(orderLineItemRepository.findProductIdsByShippingStatus(any()))
			.thenReturn(List.of());

		service.syncStockForPreparingOrdersAsync();

		verify(actionLogService).record(
			eq(ActionLogConstants.STOCK_SYNC), isNull(), eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("실패 경로: 오케스트레이션(대상선정 등) 예외 시 ActionLog FAILED(사유)를 기록한다")
	void recordsFailedWhenOrchestrationThrows() {
		doThrow(new RuntimeException("DB 접근 실패"))
			.when(orderLineItemRepository).findProductIdsByShippingStatus(ShippingStatus.NEW);

		service.syncStockForPreparingOrdersAsync();

		verify(actionLogService).record(
			eq(ActionLogConstants.STOCK_SYNC), isNull(), eq(ActionStatus.FAILED), contains("DB 접근 실패"));
	}
}
