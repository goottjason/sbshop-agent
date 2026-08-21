package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductSanitizer;
import com.sbshop.agent.core.domain.product.component.ProductValidator;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ProductPublishPriceOverrideTest {
	private static final Long PRODUCT_ID = 1L;
	private static final MarketType MARKET = MarketType.ELEVEN_STREET;

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
	@DisplayName("오버라이드가 있으면 resolver의 3-인자(오버라이드 반영) 산정 경로로 넘긴다")
	void publish_withOverrides_passesThemToResolver() {
		when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		when(marketClientRouter.hasClient(MARKET)).thenReturn(true);
		when(marketClientRouter.getClient(MARKET)).thenReturn(client);
		when(registrationTxService.savePending(any(), any(), any())).thenReturn(registration);
		MarketSalePriceOverrides overrides =
			new MarketSalePriceOverrides(new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5000"));
		when(marketSalePriceResolver.resolveForProduct(product, MARKET, overrides))
			.thenReturn(new BigDecimal("51400"));
		when(client.publish(any(), any())).thenReturn(Map.of("elevenstId", "1"));

		useCase().publishToMarket(PRODUCT_ID, MARKET, overrides);

		verify(marketSalePriceResolver).resolveForProduct(product, MARKET, overrides);
		verify(marketSalePriceResolver, never()).resolveForProduct(product, MARKET);
	}

	@Test
	@DisplayName("오버라이드가 null이면 기존 2-인자 산정 경로를 그대로 쓴다 — 기존 호출부 비파괴")
	void publish_withoutOverrides_usesLegacyResolverPath() {
		when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		when(marketClientRouter.hasClient(MARKET)).thenReturn(true);
		when(marketClientRouter.getClient(MARKET)).thenReturn(client);
		when(registrationTxService.savePending(any(), any(), any())).thenReturn(registration);
		when(marketSalePriceResolver.resolveForProduct(product, MARKET)).thenReturn(new BigDecimal("62200"));
		when(client.publish(any(), any())).thenReturn(Map.of("elevenstId", "1"));

		useCase().publishToMarket(PRODUCT_ID, MARKET, null);

		verify(marketSalePriceResolver).resolveForProduct(product, MARKET);
		verify(marketSalePriceResolver, never()).resolveForProduct(any(), any(), any());
	}

	@Test
	@DisplayName("두 값 모두 같은 재료로 계산하면 오버라이드가 실제로 등록가를 낮춘다(쿠폰 반영분)")
	void resolver_overridesLowerRegistrationPriceToMatchSyncPath() {
		MarketFeeService feeService = Mockito
			.mock(MarketFeeService.class);
		when(feeService.feeRate(MARKET)).thenReturn(new BigDecimal("18"));
		MarketSalePriceResolver resolver = new MarketSalePriceResolver(
			new MarginCalculator(), feeService, Mockito.mock(PricePolicyService.class));
		Product realProduct = Product.create("250101IHB001",
			new ProductCreateCommand(
				"https://kr.iherb.com/pr/x/1", new BigDecimal("40000"), "테스트 상품",
				"Test Product", "브랜드", "미국",
				new BigDecimal("60"), new BigDecimal("180"),
				MeasureUnit.EA,
				List.of("https://src/1.jpg"), List.of("https://cdn/1.jpg"),
				"<div>본문</div>", "보충제", true, 1, new BigDecimal("15"),
				VendorType.IHB));

		BigDecimal withoutOverride = resolver.resolveForProduct(realProduct, MARKET);
		BigDecimal withOverride = resolver.resolveForProduct(realProduct, MARKET,
			new MarketSalePriceOverrides(null, new BigDecimal("20"), null));

		assertThat(withOverride).isLessThan(withoutOverride);
	}

	private ProductPublishUseCase useCase() {
		return new ProductPublishUseCase(productReader, marketClientRouter, registrationTxService,
			new ObjectMapper(), productSanitizer, productValidator, marketSalePriceResolver);
	}
}
