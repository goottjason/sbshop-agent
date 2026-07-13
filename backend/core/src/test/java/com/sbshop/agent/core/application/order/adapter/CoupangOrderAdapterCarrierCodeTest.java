package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.application.order.port.CoupangUpdateInvoiceRequest;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;

/**
 * D-E5 회귀: 쿠팡은 롯데택배를 구 현대택배 코드 "HYUNDAI"로 식별한다.
 * "LOTTE" 전송 시 라이브에서 400 "Delivery company code not supported" 발생 → HYUNDAI로 교정.
 */
class CoupangOrderAdapterCarrierCodeTest {

	@Test
	void updateTracking_롯데택배는_쿠팡_HYUNDAI_코드로_전송된다() {
		// given
		CoupangOrderApiPort apiPort = mock(CoupangOrderApiPort.class);
		MarketRegistrationRepository regRepo = mock(MarketRegistrationRepository.class);
		MarketRegistration reg = mock(MarketRegistration.class);
		when(reg.extractVendorItemId()).thenReturn("999");
		when(regRepo.findByProductIdAndMarketType(any(), any())).thenReturn(Optional.of(reg));

		CoupangOrderAdapter adapter = new CoupangOrderAdapter(apiPort, null, null, null, regRepo);

		MarketCredential cred = MarketCredential.builder()
			.marketType(MarketType.COUPANG).clientId("vendorX").build();
		Order order = Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("14101552820428")
			.shipmentBoxId("708248067784723")
			.build();
		OrderLineItem item = OrderLineItem.builder().productId(2500L).build();

		// when
		adapter.updateTracking(cred, order, item, "315398790560", ShippingCarrier.LOTTE_LOGISTICS);

		// then
		ArgumentCaptor<CoupangUpdateInvoiceRequest> captor =
			ArgumentCaptor.forClass(CoupangUpdateInvoiceRequest.class);
		verify(apiPort).updateTracking(any(), captor.capture());
		String code = captor.getValue().orderSheetInvoiceApplyDtos().get(0).deliveryCompanyCode();
		assertThat(code).isEqualTo("HYUNDAI");
	}
}
