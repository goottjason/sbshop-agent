package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
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
class ProductMarketSyncServiceSoldOutTest {
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
		service = new ProductMarketSyncService(marketRegistrationRepository, marketClientRouter,
			new MarketSalePriceResolver(new MarginCalculator(),
				marketFeeService, Mockito.mock(PricePolicyService.class)),
			Mockito.mock(ProductReader.class));
	}

	@Test
	@DisplayName("OUT_OF_STOCK → 클라이언트에 quantity=1, soldOut=true 전달")
	void outOfStockCallsClientWithQuantityOneAndSoldOutTrue() {
		MarketClient client = Mockito.mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.COUPANG, "{\"vendorItemId\":\"V1\"}")));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(client);

		service.syncPriceStock(PRODUCT_ID, 1000, StockStatus.OUT_OF_STOCK);

		verify(client).syncPriceAndStock(eq("V1"), any(), eq(1000), eq(1), eq(true), any());
	}

	@Test
	@DisplayName("IN_STOCK → 클라이언트에 quantity=999, soldOut=false 전달")
	void inStockCallsClientWithDefaultQuantityAndSoldOutFalse() {
		MarketClient client = Mockito.mock(MarketClient.class);
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID))
			.thenReturn(List.of(reg(MarketType.COUPANG, "{\"vendorItemId\":\"V1\"}")));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(client);

		service.syncPriceStock(PRODUCT_ID, 1000, StockStatus.IN_STOCK);

		verify(client).syncPriceAndStock(eq("V1"), any(), eq(1000), eq(999), eq(false), any());
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
