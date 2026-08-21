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

/**
 * D-145: 네이버의 롯데 택배사 코드는 {@code HYUNDAI}다 — {@code LOTTE}를 보내면 `104119 택배사코드 확인`.
 *
 * <p>근거는 실측이다. 네이버가 우리 주문에 돌려주는 {@code delivery.deliveryCompany} 분포가
 * CJGLS 14 · EPOST 4 · <b>HYUNDAI 2</b>이고, 그 HYUNDAI 두 건의 송장이 롯데 번호(3153·3159로 시작)였다.
 * {@code LOTTE}라는 코드는 응답 어디에도 없다. 쿠팡과 같은 관례다(D-E5).
 */
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

	private Order order() {
		return Order.builder().marketType(MarketType.SMART_STORE).marketOrderNo("2026073124339271").build();
	}

	private OrderLineItem lineItem() {
		return OrderLineItem.builder().orderId(1L).quantity(1)
			.marketLineItemNo("2026073137353041").build();
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
		// 종전 default 분기는 모르는 택배사를 전부 "CJGLS"로 보냈다. 마켓에는 엉뚱한 택배사가
		// 등록되고, 고객은 그 택배사에서 조회되지 않는 송장을 받는다.
		assertThatThrownBy(() -> adapter.shipOrder(
			credential, order(), lineItem(), "315399497965", ShippingCarrier.ETC))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("택배사");

		verify(apiPort, never()).shipOrder(any(), any(), any(), any(), any());
	}
}
