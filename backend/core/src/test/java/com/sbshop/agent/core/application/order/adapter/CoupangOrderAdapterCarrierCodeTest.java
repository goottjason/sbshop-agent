package com.sbshop.agent.core.application.order.adapter;

import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
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

class CoupangOrderAdapterCarrierCodeTest {
	@Test
	void updateTracking_롯데택배는_쿠팡_HYUNDAI_코드로_전송된다() {
		CoupangOrderApiPort apiPort = mock(CoupangOrderApiPort.class);
		MarketRegistrationRepository regRepo = mock(MarketRegistrationRepository.class);
		MarketRegistration reg = mock(MarketRegistration.class);
		when(reg.extractVendorItemId()).thenReturn("999");
		when(regRepo.findByProductIdAndMarketType(any(), any())).thenReturn(Optional.of(reg));

		ShipmentRepository shipmentRepo = mock(
			ShipmentRepository.class);
		when(shipmentRepo.findById(900L)).thenReturn(Optional.of(
			Shipment.builder()
				.orderId(1L).marketShipmentNo("708248067784723").build()));

		CoupangOrderAdapter adapter = new CoupangOrderAdapter(apiPort, null, null, null, regRepo, shipmentRepo);

		MarketCredential cred = MarketCredential.builder()
			.marketType(MarketType.COUPANG).clientId("vendorX").build();
		Order order = Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("14101552820428")
			.build();
		OrderLineItem item = OrderLineItem.builder().productId(2500L).build();
		item.assignShipmentId(900L);

		adapter.updateTracking(cred, order, item, "315398790560", ShippingCarrier.LOTTE_LOGISTICS);

		ArgumentCaptor<CoupangUpdateInvoiceRequest> captor = ArgumentCaptor.forClass(CoupangUpdateInvoiceRequest.class);
		verify(apiPort).updateTracking(any(), captor.capture());
		String code = captor.getValue().orderSheetInvoiceApplyDtos().get(0).deliveryCompanyCode();
		assertThat(code).isEqualTo("HYUNDAI");
	}
}
