package com.sbshop.agent.core.application.order.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.adapter.Cafe24GmarketOrderAdapter;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

/**
 * D-039: ESM+ 취소/교환 주문이 DB에 없으면 신규 레코드로 생성하던 거동을 차단한다.
 * 사용자 결정: 기존 주문이 취소/교환되면 그 행을 갱신(정상)하되, DB에 없던 주문인데
 * 상태가 CANCELED/EXCHANGED면 신규 생성하지 말고 건너뛴다(로그만).
 * RETURNED는 D-029 이전 거동대로 신규 생성 유지(스코프 크립 금지).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EsmplusOrderSyncTerminalSkipTest {

	@Mock
	private MarketCredentialRepository credentialRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private OrderLineItemRepository orderLineItemRepository;
	@Mock
	private ProductRepository productRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private Cafe24GmarketOrderAdapter esmplusOrderAdapter;

	private EsmplusOrderSyncService newService() {
		return new EsmplusOrderSyncService(
			credentialRepository, orderRepository, orderLineItemRepository, productRepository,
			eventPublisher, esmplusOrderAdapter);
	}

	private void stubCredential() {
		MarketCredential credential = org.mockito.Mockito.mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("masterId");
		// D-045: loadAndValidateCredential이 이제 secretKey(비밀번호)도 검증하므로 완전한 자격증명으로 스텁.
		when(credential.getSecretKey()).thenReturn("password");
		when(credentialRepository.findByMarketType(MarketType.GMARKET)).thenReturn(Optional.of(credential));
	}

	private MarketOrderDto dto(String orderNo, ShippingStatus status) {
		return MarketOrderDto.builder()
			.marketType(MarketType.GMARKET)
			.marketOrderNo(orderNo)
			.status(status)
			.quantity(1)
			.build();
	}

	@Test
	@DisplayName("[D-039] DB에 없는 CANCELED 주문은 신규 생성하지 않고 상세 조회도 하지 않는다")
	void absentCanceled_isSkipped_noCreateNoDetailFetch() {
		stubCredential();
		MarketOrderDto canceled = dto("ESM-CANCEL-1", ShippingStatus.CANCELED);
		when(esmplusOrderAdapter.fetchOrders(any(), any(), any())).thenReturn(List.of(canceled));
		when(orderRepository.findByMarketOrderNo("ESM-CANCEL-1")).thenReturn(Optional.empty());

		newService().syncEsmplusOrders();

		verify(orderRepository, never()).save(any());
		verify(esmplusOrderAdapter, never()).fetchOrderDetail(any(), any());
	}

	@Test
	@DisplayName("[D-039] DB에 없는 EXCHANGED 주문은 신규 생성하지 않고 상세 조회도 하지 않는다")
	void absentExchanged_isSkipped_noCreateNoDetailFetch() {
		stubCredential();
		MarketOrderDto exchanged = dto("ESM-EXCH-1", ShippingStatus.EXCHANGED);
		when(esmplusOrderAdapter.fetchOrders(any(), any(), any())).thenReturn(List.of(exchanged));
		when(orderRepository.findByMarketOrderNo("ESM-EXCH-1")).thenReturn(Optional.empty());

		newService().syncEsmplusOrders();

		verify(orderRepository, never()).save(any());
		verify(esmplusOrderAdapter, never()).fetchOrderDetail(any(), any());
	}

	@Test
	@DisplayName("[D-039 회귀] 기존 주문이 CANCELED로 오면 그 행을 갱신(save)한다")
	void existingCanceled_updatesExistingRow() {
		stubCredential();
		MarketOrderDto canceled = dto("ESM-CANCEL-2", ShippingStatus.CANCELED);
		Order existing = org.mockito.Mockito.mock(Order.class);
		when(existing.getId()).thenReturn(42L);
		when(esmplusOrderAdapter.fetchOrders(any(), any(), any())).thenReturn(List.of(canceled));
		when(orderRepository.findByMarketOrderNo("ESM-CANCEL-2")).thenReturn(Optional.of(existing));
		when(orderLineItemRepository.findByOrderId(42L)).thenReturn(List.of());

		newService().syncEsmplusOrders();

		verify(orderRepository).save(existing);
		verify(esmplusOrderAdapter, never()).fetchOrderDetail(any(), any());
	}

	@Test
	@DisplayName("[D-039 회귀] DB에 없는 정상(NEW) 주문은 신규 생성한다")
	void absentNew_createsNewOrder() {
		stubCredential();
		MarketOrderDto fresh = dto("ESM-NEW-1", ShippingStatus.NEW);
		when(esmplusOrderAdapter.fetchOrders(any(), any(), any())).thenReturn(List.of(fresh));
		when(orderRepository.findByMarketOrderNo("ESM-NEW-1")).thenReturn(Optional.empty());
		when(esmplusOrderAdapter.fetchOrderDetail(any(), any())).thenReturn(fresh);

		newService().syncEsmplusOrders();

		verify(orderRepository).save(any(Order.class));
		verify(orderLineItemRepository).save(any());
	}
}
