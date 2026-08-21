package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class ProductMarketSyncPerMarketPriceTest {
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private MarketFeeService marketFeeService;

	private ProductMarketSyncService service;
	private static final Long PRODUCT_ID = 1L;

	@BeforeEach
	void setUp() {
		service = new ProductMarketSyncService(
			marketRegistrationRepository, marketClientRouter,
			new MarketSalePriceResolver(new MarginCalculator(), marketFeeService),
			Mockito.mock(ProductReader.class));
	}

	@Test
	@DisplayName("같은 상품이라도 마켓 수수료가 다르면 각 마켓에 다른 가격을 전송한다")
	void perMarketPrice_usesMarketFee() {
		MarketClient coupangClient = Mockito.mock(MarketClient.class);
		MarketClient storeClient = Mockito.mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.COUPANG, "{\"vendorItemId\":\"CP123\"}"),
				reg(MarketType.SMART_STORE, "{\"originProductNo\":\"OP99\"}")));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);
		when(marketClientRouter.getClient(MarketType.SMART_STORE)).thenReturn(storeClient);
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(marketFeeService.feeRate(MarketType.SMART_STORE)).thenReturn(new BigDecimal("8"));

		PricingInputs pricing = new PricingInputs(
			new BigDecimal("31522"), 2, new BigDecimal("10"), new BigDecimal("15"), new BigDecimal("3500"));

		service.syncPriceStockPerMarket(PRODUCT_ID, pricing, StockStatus.IN_STOCK, true);

		verify(coupangClient).syncPriceAndStock(eq("CP123"), any(), eq(67900), eq(999), eq(false), any());
		verify(storeClient).syncPriceAndStock(eq("OP99"), any(), eq(65400), eq(999), eq(false), any());
	}

	private MarketRegistration reg(MarketType type, String identifiersJson) {
		return MarketRegistration.builder()
			.productId(PRODUCT_ID)
			.marketType(type)
			.marketIdentifiers(identifiersJson)
			.marketDetailedInfo("{}")
			.build();
	}
}
