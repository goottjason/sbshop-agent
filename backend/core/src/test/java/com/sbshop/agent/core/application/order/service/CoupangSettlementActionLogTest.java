package com.sbshop.agent.core.application.order.service;

import java.util.Optional;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.order.adapter.CoupangOrderAdapter;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.product.ProductRepository;

import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class CoupangSettlementActionLogTest {
	@Mock
	MarketCredentialRepository credentialRepository;
	@Mock
	OrderRepository orderRepository;
	@Mock
	OrderLineItemRepository orderLineItemRepository;
	@Mock
	ProductRepository productRepository;
	@Mock
	MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	ApplicationEventPublisher eventPublisher;
	@Mock
	CoupangOrderAdapter coupangOrderAdapter;
	@Mock
	CoupangStatusMapper statusMapper;
	@Mock
	SyncStatusService syncStatusService;
	@Mock
	MarketFeeService marketFeeService;
	@Mock
	ActionLogService actionLogService;

	@InjectMocks
	CoupangOrderSyncService service;

	@Test
	@DisplayName("정산 동기화 완료 → COUPANG_SETTLEMENT_SYNC SUCCESS 기록")
	void settlement_success_recordsSuccess() {
		MarketCredential cred = validCredential();
		when(syncStatusService.tryMarkRunning(any())).thenReturn(true);
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.of(cred));
		when(coupangOrderAdapter.querySettlement(any(), any(), any()))
			.thenReturn(Map.<String, BigDecimal>of());

		service.syncCoupangSettlement();

		verify(actionLogService).record(eq(ActionLogConstants.COUPANG_SETTLEMENT_SYNC),
			eq("COUPANG"), eq(ActionStatus.SUCCESS), any());
	}

	@Test
	@DisplayName("정산 동기화 실패 → COUPANG_SETTLEMENT_SYNC FAILED 기록")
	void settlement_failure_recordsFailed() {
		when(syncStatusService.tryMarkRunning(any())).thenReturn(true);
		when(credentialRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(Optional.empty());

		service.syncCoupangSettlement();

		verify(actionLogService).record(eq(ActionLogConstants.COUPANG_SETTLEMENT_SYNC),
			eq("COUPANG"), eq(ActionStatus.FAILED), any());
	}

	@Test
	@DisplayName("이미 RUNNING(중복) → 스킵, SUCCESS/FAILED 미기록")
	void settlement_duplicateSkip_recordsNothing() {
		when(syncStatusService.tryMarkRunning(any())).thenReturn(false);

		service.syncCoupangSettlement();

		verify(actionLogService, never()).record(eq(ActionLogConstants.COUPANG_SETTLEMENT_SYNC),
			eq("COUPANG"), eq(ActionStatus.SUCCESS), any());
		verify(actionLogService, never()).record(eq(ActionLogConstants.COUPANG_SETTLEMENT_SYNC),
			eq("COUPANG"), eq(ActionStatus.FAILED), any());
	}

	private MarketCredential validCredential() {
		MarketCredential cred = Mockito.mock(MarketCredential.class);
		lenient().when(cred.getClientId()).thenReturn("cid");
		lenient().when(cred.getAccessKey()).thenReturn("ak");
		lenient().when(cred.getSecretKey()).thenReturn("sk");
		return cred;
	}
}
