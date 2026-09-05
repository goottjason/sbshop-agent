package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductFieldSyncBatchServiceTest {

	@Mock
	private ProductRepository productRepository;
	@Mock
	private ProductFieldSyncUseCase fieldSyncUseCase;
	@Mock
	private ProcessStatusService processStatusService;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@InjectMocks
	private ProductFieldSyncBatchService service;

	private static final Set<MarketEditField> FIELDS = Set.of(MarketEditField.BRAND);
	private static final Set<MarketType> MARKETS = Set.of(MarketType.SMART_STORE);

	@Test
	@DisplayName("D-294: 백필로 브랜드가 갱신된 상품만 대상으로 뽑는다")
	void targetsAreBackfilledProducts() {
		when(productRepository.findFieldSyncTargetIds()).thenReturn(List.of(1L, 2L, 3L));

		assertThat(service.findTargets(0)).containsExactly(1L, 2L, 3L);
	}

	@Test
	@DisplayName("D-294: limit 을 주면 그만큼만 자른다 — 소량 파일럿용")
	void limitCutsTargets() {
		when(productRepository.findFieldSyncTargetIds()).thenReturn(List.of(1L, 2L, 3L, 4L));

		assertThat(service.findTargets(2)).containsExactly(1L, 2L);
	}

	@Test
	@DisplayName("D-294: 상품별로 같은 batchId 아래 실행하고 한 건이 실패해도 계속 간다")
	void batchContinuesPastFailures() {
		when(fieldSyncUseCase.syncOne(eq("b1"), eq(1L), any(), any()))
			.thenThrow(new RuntimeException("boom"));
		when(fieldSyncUseCase.syncOne(eq("b1"), eq(2L), any(), any()))
			.thenReturn(new MarketRepublishResult(List.of(MarketType.SMART_STORE), List.of(), Map.of()));

		service.runBatch("b1", List.of(1L, 2L), FIELDS, MARKETS);

		verify(fieldSyncUseCase).syncOne(eq("b1"), eq(2L), any(), any());
		verify(processStatusService).markFailed(eq("b1"), eq("1"), anyString());
	}
}
