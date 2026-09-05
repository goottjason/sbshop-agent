package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SmartstorePriceUnitTest {
	@Mock
	private MarketFeeService marketFeeService;
	@Mock
	private PricePolicyService pricePolicyService;
	@Mock
	private VendorPricePolicyService vendorPricePolicyService;
	@Mock
	private Product product;

	private final MarginCalculator marginCalculator = new MarginCalculator();

	@Test
	@DisplayName("Q20: 스토어 최소마진 하한 20105는 20200으로 보정한다")
	void resolve_smartStorePreservesMinimumInHundreds() {
		when(marketFeeService.feeRate(MarketType.SMART_STORE)).thenReturn(new BigDecimal("11"));
		PricingInputs inputs = new PricingInputs(new BigDecimal("10000"), 1,
			BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("4105"));

		Integer price = resolver().resolve(inputs, MarketType.SMART_STORE);

		assertThat(price).isEqualTo(20200);
	}

	@Test
	@DisplayName("Q20: 이미 100원 단위인 하한은 그대로 둔다")
	void resolve_alreadyRoundValueUnchanged() {
		when(marketFeeService.feeRate(MarketType.SMART_STORE)).thenReturn(new BigDecimal("11"));
		PricingInputs inputs = new PricingInputs(new BigDecimal("10000"), 1,
			BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("4100"));

		Integer price = resolver().resolve(inputs, MarketType.SMART_STORE);

		assertThat(price).isEqualTo(20100);
	}

	@Test
	@DisplayName("Q20: 다른 마켓도 동일하게 하한을 충족하는 100원 단위로 계산한다")
	void resolve_otherMarketsUseSameRoundingRule() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		PricingInputs inputs = new PricingInputs(new BigDecimal("10000"), 1,
			BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("4105"));

		Integer price = resolver().resolve(inputs, MarketType.COUPANG);

		assertThat(price).isEqualTo(20200);
	}

	@Test
	@DisplayName("Q18: 원가·마진 미보유 기준가는 100원 반올림한다")
	void resolveForProduct_fallbackRoundsToHundreds() {
		when(product.getPriceInfo()).thenReturn(null);
		when(product.getSalePrice()).thenReturn(new BigDecimal("20105"));

		BigDecimal price = resolver().resolveForProduct(product, MarketType.SMART_STORE);

		assertThat(price).isEqualByComparingTo("20100");
	}

	@Test
	@DisplayName("D-280: 폴백 값이 null 이면 올림하지 않고 null 을 그대로 반환한다")
	void resolveForProduct_fallbackNullStaysNull() {
		when(product.getPriceInfo()).thenReturn(null);
		when(product.getSalePrice()).thenReturn(null);

		BigDecimal price = resolver().resolveForProduct(product, MarketType.SMART_STORE);

		assertThat(price).isNull();
	}

	private MarketSalePriceResolver resolver() {
		return new MarketSalePriceResolver(marginCalculator, marketFeeService, pricePolicyService,
			vendorPricePolicyService);
	}
}
