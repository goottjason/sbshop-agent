package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductSanitizer;
import com.sbshop.agent.core.domain.product.component.ProductValidator;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductPublishPerMarketPriceTest {
	private static final Long PRODUCT_ID = 1L;

	@Mock
	private ProductReader productReader;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private MarketRegistrationTxService registrationTxService;
	@Mock
	private ProductSanitizer productSanitizer;
	@Mock
	private ProductValidator productValidator;
	@Mock
	private MarketSalePriceResolver marketSalePriceResolver;
	@Mock
	private MarketClient client;
	@Mock
	private Product product;
	@Mock
	private MarketRegistration registration;

	@Test
	@DisplayName("게시 시 마켓별 산정가를 MarketPublishContext.salePrice로 전달한다")
	void publish_passesPerMarketPrice() {
		when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		when(marketClientRouter.hasClient(MarketType.ELEVEN_STREET)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.ELEVEN_STREET)).thenReturn(client);
		when(registrationTxService.savePending(any(), any(), any())).thenReturn(registration);
		when(marketSalePriceResolver.resolveForProduct(product, MarketType.ELEVEN_STREET))
			.thenReturn(new BigDecimal("103000"));
		when(client.publish(any(), any())).thenReturn(Map.of("elevenstId", "999"));

		new ProductPublishUseCase(productReader, marketClientRouter, registrationTxService,
			new ObjectMapper(), productSanitizer, productValidator, marketSalePriceResolver)
			.publishToMarket(PRODUCT_ID, MarketType.ELEVEN_STREET);

		ArgumentCaptor<MarketPublishContext> captor = ArgumentCaptor.forClass(MarketPublishContext.class);
		verify(client).publish(any(), captor.capture());
		assertThat(captor.getValue().salePrice()).isEqualByComparingTo("103000");
	}
}
