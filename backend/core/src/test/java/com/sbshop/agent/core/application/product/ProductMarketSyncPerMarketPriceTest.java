package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-094: 마켓별 실수수료로 판매가를 따로 산정해 각 마켓에 전송하는지 검증.
 * 하나의 상품(같은 원가·마진·쿠폰·최소마진)이라도 마켓 수수료가 다르면 전송 가격이 달라야 한다.
 */
@ExtendWith(MockitoExtension.class)
class ProductMarketSyncPerMarketPriceTest {

	@Mock private MarketRegistrationRepository marketRegistrationRepository;
	@Mock private MarketClientRouter marketClientRouter;
	@Mock private MarketFeeService marketFeeService;

	private ProductMarketSyncService service;
	private static final Long PRODUCT_ID = 1L;

	@BeforeEach
	void setUp() {
		service = new ProductMarketSyncService(
			marketRegistrationRepository, marketClientRouter, new MarginCalculator(), marketFeeService);
	}

	private MarketRegistration reg(MarketType type, String identifiersJson) {
		return MarketRegistration.builder()
			.productId(PRODUCT_ID)
			.marketType(type)
			.marketIdentifiers(identifiersJson)
			.marketDetailedInfo("{}")
			.build();
	}

	@Test
	@DisplayName("같은 상품이라도 마켓 수수료가 다르면 각 마켓에 다른 가격을 전송한다")
	void perMarketPrice_usesMarketFee() {
		MarketClient coupangClient = org.mockito.Mockito.mock(MarketClient.class);
		MarketClient storeClient = org.mockito.Mockito.mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.COUPANG, "{\"vendorItemId\":\"CP123\"}"),
				reg(MarketType.SMART_STORE, "{\"originProductNo\":\"OP99\"}")));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);
		when(marketClientRouter.getClient(MarketType.SMART_STORE)).thenReturn(storeClient);
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(marketFeeService.feeRate(MarketType.SMART_STORE)).thenReturn(new BigDecimal("8"));

		// 원가 31522·묶음2·마진10·쿠폰15·최소3500 (실매입 53587.4)
		PricingInputs pricing = new PricingInputs(
			new BigDecimal("31522"), 2, new BigDecimal("10"), new BigDecimal("15"), new BigDecimal("3500"));

		service.syncPriceStockPerMarket(PRODUCT_ID, pricing, StockStatus.IN_STOCK, true);

		// 쿠팡 11% → 67900, 스토어 8% → 65400
		verify(coupangClient).syncPriceAndStock(eq("CP123"), any(), eq(67900), eq(999), eq(false));
		verify(storeClient).syncPriceAndStock(eq("OP99"), any(), eq(65400), eq(999), eq(false));
	}
}
