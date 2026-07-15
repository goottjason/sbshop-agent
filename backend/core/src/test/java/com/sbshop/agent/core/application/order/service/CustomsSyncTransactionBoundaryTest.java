package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.CustomsVerificationResult;
import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

/**
 * F-SYNC-19: {@code syncCustomsStatus()}는 배치 루프 안에서 {@code Thread.sleep()}을 호출하는데,
 * 메서드 전체가 {@code @Transactional}이면 sleep×배치 수만큼 하나의 트랜잭션이 유지되어
 * DB 커넥션·락을 장기 점유한다.
 *
 * <p>수정 계약:
 * <ul>
 *   <li>오케스트레이션 메서드 {@code syncCustomsStatus()}는 {@code @Transactional}이 아니어야 한다.
 *       (sleep이 트랜잭션을 물지 않도록)</li>
 *   <li>각 배치 처리는 별도 {@code @Transactional} 빈({@code CustomsBatchProcessor})으로 분리되어
 *       배치마다 짧은 트랜잭션으로 커밋된다 (F-SYNC-20 부분 진행 보존).</li>
 *   <li>대상이 여러 배치로 나뉘면 배치 수만큼 processor가 호출된다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CustomsSyncTransactionBoundaryTest {

	@Mock private OrderRepository orderRepository;
	@Mock private SyncStatusService syncStatusService;
	@Mock private CustomsBatchProcessor customsBatchProcessor;

	private CustomsOrderSyncService service;

	@BeforeEach
	void setUp() {
		service = new CustomsOrderSyncService(orderRepository, syncStatusService, customsBatchProcessor);
	}

	@Test
	void orchestrationMethodIsNotTransactional() throws NoSuchMethodException {
		Method m = CustomsOrderSyncService.class.getMethod("syncCustomsStatus");
		assertThat(m.isAnnotationPresent(Transactional.class))
			.as("syncCustomsStatus()는 sleep을 트랜잭션 밖에서 수행하도록 @Transactional이 아니어야 한다")
			.isFalse();
	}

	@Test
	void batchProcessorMethodIsTransactional() throws NoSuchMethodException {
		Method m = CustomsBatchProcessor.class.getMethod("processBatch", List.class);
		assertThat(m.isAnnotationPresent(Transactional.class))
			.as("배치 처리는 짧은 별도 트랜잭션으로 커밋되어야 한다")
			.isTrue();
	}

	@Test
	void eachBatchIsProcessedInSeparateTransactionalUnit() {
		// 65개 대상 → 배치 크기 30 → 3배치 (30, 30, 5)
		List<Order> targets = new ArrayList<>();
		for (int i = 0; i < 65; i++) {
			targets.add(org.mockito.Mockito.mock(Order.class));
		}
		when(orderRepository.findByCustomsData_CustomsStatusIn(anyList())).thenReturn(targets);

		service.syncCustomsStatus();

		// 배치 수만큼 별도 트랜잭션 단위(processor)가 호출된다
		verify(customsBatchProcessor, times(3)).processBatch(anyList());
		verify(syncStatusService).markRunning(SyncMarketKeys.CUSTOMS);
		verify(syncStatusService).markCompleted(SyncMarketKeys.CUSTOMS);
	}

	@Test
	void batchProcessorAppliesVerificationResultsAndPersists() {
		// F-SYNC-20: 배치 처리는 검증 결과를 반영하고 명시적으로 저장하여
		// 배치 단위로 커밋(부분 진행 보존)한다.
		Order order = org.mockito.Mockito.mock(Order.class);
		when(order.getId()).thenReturn(7L);
		com.sbshop.agent.core.application.order.port.CustomsClearancePort port =
			org.mockito.Mockito.mock(com.sbshop.agent.core.application.order.port.CustomsClearancePort.class);
		CustomsBatchProcessor processor = new CustomsBatchProcessor(orderRepository, port);

		when(port.verifyBulk(anyList())).thenReturn(
			Map.of(7L, CustomsVerificationResult.of(CustomsStatus.VALID,
				com.sbshop.agent.core.domain.order.enums.VerifiedPerson.ORDERER)));

		processor.processBatch(List.of(order));

		verify(order).updateCustomsStatus(CustomsStatus.VALID,
			com.sbshop.agent.core.domain.order.enums.VerifiedPerson.ORDERER);
		verify(orderRepository).saveAll(anyList());
	}
}
