package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.SyncErrorType;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductFieldSyncUseCaseTest {

	@Mock
	private ProductReader productReader;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private ProcessStatusService processStatusService;
	@Mock
	private MarketClient storeClient;
	@Mock
	private Product product;

	private ProductFieldSyncUseCase useCase;

	private static final Long ID = 77L;
	private static final Set<MarketEditField> FIELDS = Set.of(MarketEditField.BRAND);

	@BeforeEach
	void setUp() {
		useCase = new ProductFieldSyncUseCase(productReader, marketRegistrationRepository,
			marketClientRouter, processStatusService);
		lenient().when(productReader.findById(ID)).thenReturn(Optional.of(product));
		lenient().when(product.getSbCode()).thenReturn("SB-1");
		lenient().when(marketClientRouter.hasClient(any())).thenReturn(true);
		lenient().when(marketClientRouter.getClient(any())).thenReturn(storeClient);
		lenient().when(processStatusService.startBatch(eq(JobType.FIELD_SYNC), anyList()))
			.thenReturn("fs-1");
	}

	private MarketRegistration reg(MarketType market) {
		MarketRegistration r = MarketRegistration.builder()
			.productId(ID).marketType(market)
			.marketIdentifiers("{\"originProductNo\":\"123\",\"product_no\":\"45\",\"prdNo\":\"678\"}")
			.marketDetailedInfo("{}").build();
		r.markSynced();
		return r;
	}

	@Test
	@DisplayName("D-294: 대상 마켓에 필드를 전송하고 결과를 기록한다")
	void syncsFieldsToTargetMarkets() {
		when(marketRegistrationRepository.findByProductId(ID)).thenReturn(List.of(reg(MarketType.SMART_STORE)));
		when(storeClient.syncProductFields(any(), anyString(), any(), any())).thenReturn(Map.of("k", "v"));

		ProductFieldSyncUseCase.FieldSyncOutcome out =
			useCase.sync(ID, FIELDS, Set.of(MarketType.SMART_STORE));

		assertThat(out.batchId()).isEqualTo("fs-1");
		assertThat(out.result().synced()).containsExactly(MarketType.SMART_STORE);
		verify(processStatusService).markSuccess(eq("fs-1"), eq("77"), anyString(), anyString());
	}

	@Test
	@DisplayName("D-294: 요청에 없는 마켓은 건드리지 않는다")
	void marketsOutsideRequestAreUntouched() {
		when(marketRegistrationRepository.findByProductId(ID))
			.thenReturn(List.of(reg(MarketType.SMART_STORE), reg(MarketType.CAFE24)));
		when(storeClient.syncProductFields(any(), anyString(), any(), any())).thenReturn(Map.of());

		ProductFieldSyncUseCase.FieldSyncOutcome out =
			useCase.sync(ID, FIELDS, Set.of(MarketType.SMART_STORE));

		assertThat(out.result().synced()).containsExactly(MarketType.SMART_STORE);
		assertThat(out.result().skipped()).isEmpty();
	}

	@Test
	@DisplayName("D-294: 마켓이 막아둔 등록은 건너뛴다 — D-284 규칙을 그대로 받는다")
	void blockedRegistrationIsSkipped() {
		MarketRegistration blocked = reg(MarketType.SMART_STORE);
		blocked.recordSyncError(SyncErrorType.BLOCKED_BY_MARKET, "심사중");
		when(marketRegistrationRepository.findByProductId(ID)).thenReturn(List.of(blocked));

		ProductFieldSyncUseCase.FieldSyncOutcome out =
			useCase.sync(ID, FIELDS, Set.of(MarketType.SMART_STORE));

		assertThat(out.result().skipped()).containsExactly(MarketType.SMART_STORE);
		verify(storeClient, never()).syncProductFields(any(), anyString(), any(), any());
	}

	@Test
	@DisplayName("D-294: 마켓에서 삭제된 등록은 건너뛴다")
	void deletedRegistrationIsSkipped() {
		MarketRegistration deleted = reg(MarketType.SMART_STORE);
		deleted.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
		when(marketRegistrationRepository.findByProductId(ID)).thenReturn(List.of(deleted));

		ProductFieldSyncUseCase.FieldSyncOutcome out =
			useCase.sync(ID, FIELDS, Set.of(MarketType.SMART_STORE));

		assertThat(out.result().skipped()).containsExactly(MarketType.SMART_STORE);
	}

	@Test
	@DisplayName("D-294: 미지원 마켓(UnsupportedOperation)은 실패가 아니라 스킵이다")
	void unsupportedMarketIsSkippedNotFailed() {
		when(marketRegistrationRepository.findByProductId(ID)).thenReturn(List.of(reg(MarketType.SMART_STORE)));
		when(storeClient.syncProductFields(any(), anyString(), any(), any()))
			.thenThrow(new UnsupportedOperationException("미지원"));

		ProductFieldSyncUseCase.FieldSyncOutcome out =
			useCase.sync(ID, FIELDS, Set.of(MarketType.SMART_STORE));

		assertThat(out.result().skipped()).containsExactly(MarketType.SMART_STORE);
		assertThat(out.result().failed()).isEmpty();
	}

	@Test
	@DisplayName("D-294: 마켓이 거부하면 사유와 함께 실패로 남기고 부분실패로 기록한다")
	void marketRejectionIsRecordedWithReason() {
		when(marketRegistrationRepository.findByProductId(ID)).thenReturn(List.of(reg(MarketType.SMART_STORE)));
		when(storeClient.syncProductFields(any(), anyString(), any(), any()))
			.thenThrow(new RuntimeException("400 Bad Request: 어쩌고"));

		ProductFieldSyncUseCase.FieldSyncOutcome out =
			useCase.sync(ID, FIELDS, Set.of(MarketType.SMART_STORE));

		assertThat(out.result().failed()).containsKey(MarketType.SMART_STORE);
		verify(processStatusService).markPartialFailed(eq("fs-1"), eq("77"), contains("SMART_STORE"), anyString());
	}

	@Test
	@DisplayName("D-294: 쿠팡은 1단계 대상이 아니다 — 요청에 넣어도 건너뛴다(심사 트리거 방지)")
	void coupangIsExcludedInPhaseOne() {
		when(marketRegistrationRepository.findByProductId(ID)).thenReturn(List.of(reg(MarketType.COUPANG)));

		ProductFieldSyncUseCase.FieldSyncOutcome out =
			useCase.sync(ID, FIELDS, Set.of(MarketType.COUPANG));

		assertThat(out.result().skipped()).containsExactly(MarketType.COUPANG);
		verify(storeClient, never()).syncProductFields(any(), anyString(), any(), any());
	}
}
