package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import com.sbshop.agent.core.domain.product.vo.PriceInfo;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProductPricePreviewUseCaseTest {
	private final ProductReader reader = mock(ProductReader.class);
	private final MarketClientRouter router = mock(MarketClientRouter.class);
	private final MarketFeeService fees = mock(MarketFeeService.class);
	private final MarketSalePriceResolver resolver = new MarketSalePriceResolver(new MarginCalculator(), fees,
		mock(PricePolicyService.class), mock(VendorPricePolicyService.class));
	private final ProductPricePreviewUseCase preview = new ProductPricePreviewUseCase(reader, resolver, router);

	@Test
	void explainsMinimumAdjustmentAndKeepsOneMarketFailureSeparateWithoutExternalCalls() {
		Product product = product();
		when(product.getPriceInfo()).thenReturn(PriceInfo.builder().costPrice(new BigDecimal("6340"))
			.marginRate(BigDecimal.ZERO).couponRate(BigDecimal.ZERO).minMarginPrice(BigDecimal.ZERO).build());
		when(router.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(router.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(fees.feeRate(MarketType.COUPANG)).thenReturn(BigDecimal.ZERO);
		when(fees.feeRate(MarketType.SMART_STORE)).thenReturn(new BigDecimal("100"));
		var response = preview.preview(1L);
		assertThat(response.mode()).isEqualTo("READ_ONLY");
		assertThat(response.items()).hasSize(2);
		var good = response.items().getFirst();
		assertThat(good.roundedPrice()).isEqualTo("12300");
		assertThat(good.minimumPrice()).isEqualTo("12340");
		assertThat(good.salePrice()).isEqualTo("12400");
		assertThat(good.minimumAdjusted()).isTrue();
		assertThat(good.reason()).contains("최소마진 보장");
		var failed = response.items().get(1);
		assertThat(failed.status()).isEqualTo(ProductPricePreviewUseCase.Status.FAILED);
		assertThat(failed.salePrice()).isNull();
		assertThat(failed.reason()).contains("100% 미만");
		verify(product, never()).update(any());
		verify(router, never()).getClient(any());
	}

	@Test
	void fallbackIsExplicitlyUnverifiedEvenThoughItHasAPrice() {
		Product product = product();
		when(product.getSalePrice()).thenReturn(new BigDecimal("12650"));
		when(router.hasClient(MarketType.COUPANG)).thenReturn(true);
		var item = preview.preview(1L).items().getFirst();
		assertThat(item.status()).isEqualTo(ProductPricePreviewUseCase.Status.FALLBACK);
		assertThat(item.salePrice()).isEqualTo("12700");
		assertThat(item.minimumPrice()).isNull();
		assertThat(item.reason()).contains("최소마진은 검증하지 못했습니다");
	}

	@Test
	void missingFallbackPriceIsNotSuccess() {
		product();
		when(router.hasClient(MarketType.COUPANG)).thenReturn(true);
		var item = preview.preview(1L).items().getFirst();
		assertThat(item.status()).isEqualTo(ProductPricePreviewUseCase.Status.FAILED);
		assertThat(item.salePrice()).isNull();
	}

	@Test
	void missingOrDeletedProductsAreNotCalculated() {
		assertThatThrownBy(() -> preview.preview(1L)).isInstanceOf(ResourceNotFoundException.class);
		Product product = product();
		when(product.getDeletedAt()).thenReturn(java.time.LocalDateTime.now());
		assertThatThrownBy(() -> preview.preview(1L)).isInstanceOf(ResourceNotFoundException.class);
		verifyNoInteractions(router, fees);
	}

	private Product product() {
		Product product = mock(Product.class);
		when(product.getSbCode()).thenReturn("SB1");
		when(reader.findById(1L)).thenReturn(Optional.of(product));
		return product;
	}
}
