package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.mapper.SmartStoreStatusMapper;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmartStoreCarrierCodeTest {
	@Mock
	private SmartStoreOrderApiPort apiPort;
	@Mock
	private SmartStoreStatusMapper statusMapper;

	private SmartStoreOrderAdapter adapter;
	private MarketCredential credential;

	@BeforeEach
	void setUp() {
		adapter = new SmartStoreOrderAdapter(apiPort, statusMapper);
		credential = mock(MarketCredential.class);
		when(credential.getClientId()).thenReturn("clientId");
		when(credential.getSecretKey()).thenReturn("secret");
	}

	@Test
	@DisplayName("롯데택배는 네이버 코드 HYUNDAI로 전송된다")
	void lotteIsSentAsHyundai() {
		adapter.shipOrder(credential, order(), lineItem(), "315399497965", ShippingCarrier.LOTTE_LOGISTICS);

		verify(apiPort).shipOrder(eq("clientId"), eq("secret"), eq("2026073137353041"),
			eq("315399497965"), eq("HYUNDAI"));
	}

	@Test
	@DisplayName("매핑할 수 없는 택배사는 CJ로 위조하지 않고 즉시 실패한다")
	void unmappedCarrierFailsInsteadOfForgingCj() {
		assertThatThrownBy(() -> adapter.shipOrder(
			credential, order(), lineItem(), "315399497965", ShippingCarrier.ETC))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("택배사");

		verify(apiPort, never()).shipOrder(any(), any(), any(), any(), any());
	}

	private Order order() {
		return Order.builder().marketType(MarketType.SMART_STORE).marketOrderNo("2026073124339271").build();
	}

	private OrderLineItem lineItem() {
		return OrderLineItem.builder().orderId(1L).quantity(1)
			.marketLineItemNo("2026073137353041").build();
	}
}
