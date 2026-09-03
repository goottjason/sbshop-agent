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
	@DisplayName("D-280: 스토어 판매가 20105 는 20110 으로 올려 보낸다")
	void resolve_smartStoreCeilsToTen() {
		when(marketFeeService.feeRate(MarketType.SMART_STORE)).thenReturn(new BigDecimal("11"));
		PricingInputs inputs = new PricingInputs(new BigDecimal("10000"), 1,
			BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("4105"));

		Integer price = resolver().resolve(inputs, MarketType.SMART_STORE);

		assertThat(price).isEqualTo(20110);
	}

	@Test
	@DisplayName("D-280: 이미 10원 단위인 값은 그대로 둔다")
	void resolve_alreadyRoundValueUnchanged() {
		when(marketFeeService.feeRate(MarketType.SMART_STORE)).thenReturn(new BigDecimal("11"));
		PricingInputs inputs = new PricingInputs(new BigDecimal("10000"), 1,
			BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("4100"));

		Integer price = resolver().resolve(inputs, MarketType.SMART_STORE);

		assertThat(price).isEqualTo(20100);
	}

	@Test
	@DisplayName("D-280: 다른 마켓은 1원 단위 값도 그대로 보낸다")
	void resolve_otherMarketsUnaffected() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		PricingInputs inputs = new PricingInputs(new BigDecimal("10000"), 1,
			BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("4105"));

		Integer price = resolver().resolve(inputs, MarketType.COUPANG);

		assertThat(price).isEqualTo(20105);
	}

	@Test
	@DisplayName("D-280: 원가·마진 미보유로 기준가 폴백해도 스토어면 올림한다")
	void resolveForProduct_fallbackAlsoCeilsForSmartStore() {
		when(product.getPriceInfo()).thenReturn(null);
		when(product.getSalePrice()).thenReturn(new BigDecimal("20105"));

		BigDecimal price = resolver().resolveForProduct(product, MarketType.SMART_STORE);

		assertThat(price).isEqualByComparingTo("20110");
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
