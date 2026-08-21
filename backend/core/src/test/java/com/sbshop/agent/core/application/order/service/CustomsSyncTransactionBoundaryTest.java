package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.port.CustomsClearancePort;
import com.sbshop.agent.core.domain.order.enums.VerifiedPerson;
import org.mockito.Mockito;
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

@ExtendWith(MockitoExtension.class)
class CustomsSyncTransactionBoundaryTest {
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private SyncStatusService syncStatusService;
	@Mock
	private CustomsBatchProcessor customsBatchProcessor;

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
		List<Order> targets = new ArrayList<>();
		for (int i = 0; i < 65; i++) {
			targets.add(Mockito.mock(Order.class));
		}
		when(orderRepository.findByCustomsData_CustomsStatusIn(anyList())).thenReturn(targets);

		service.syncCustomsStatus();

		verify(customsBatchProcessor, times(3)).processBatch(anyList());
		verify(syncStatusService).markRunning(SyncMarketKeys.CUSTOMS);
		verify(syncStatusService).markCompleted(SyncMarketKeys.CUSTOMS);
	}

	@Test
	void batchProcessorAppliesVerificationResultsAndPersists() {
		Order order = Mockito.mock(Order.class);
		when(order.getId()).thenReturn(7L);
		CustomsClearancePort port = Mockito
			.mock(CustomsClearancePort.class);
		CustomsBatchProcessor processor = new CustomsBatchProcessor(orderRepository, port);

		when(port.verifyBulk(anyList())).thenReturn(
			Map.of(7L, CustomsVerificationResult.of(CustomsStatus.VALID,
				VerifiedPerson.ORDERER)));

		processor.processBatch(List.of(order));

		verify(order).updateCustomsStatus(CustomsStatus.VALID,
			VerifiedPerson.ORDERER);
		verify(orderRepository).saveAll(anyList());
	}
}
