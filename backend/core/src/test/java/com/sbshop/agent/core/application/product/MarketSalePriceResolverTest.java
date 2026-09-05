package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.vo.PriceInfo;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketSalePriceResolverTest {
	@Mock
	private MarketFeeService marketFeeService;
	@Mock
	private PricePolicyService pricePolicyService;
	@Mock
	private Product product;

	private final MarginCalculator marginCalculator = new MarginCalculator();

	@Test
	@DisplayName("수수료가 높은 마켓일수록 판매가가 높게 산정된다")
	void resolve_higherFeeYieldsHigherPrice() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(marketFeeService.feeRate(MarketType.ELEVEN_STREET)).thenReturn(new BigDecimal("18"));
		PricingInputs inputs = new PricingInputs(new BigDecimal("10000"), 1,
			new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5000"));

		Integer coupang = resolver().resolve(inputs, MarketType.COUPANG);
		Integer elevenst = resolver().resolve(inputs, MarketType.ELEVEN_STREET);

		assertThat(elevenst).isGreaterThan(coupang);
	}

	@Test
	@DisplayName("상품에 원가·마진이 없으면 기준가로 폴백한다 — 계산 재료가 없다고 등록을 막지 않는다")
	void resolveForProduct_fallsBackToStoredSalePrice() {
		when(product.getPriceInfo()).thenReturn(null);
		when(product.getSalePrice()).thenReturn(new BigDecimal("90600"));

		BigDecimal price = resolver().resolveForProduct(product, MarketType.GMARKET);

		assertThat(price).isEqualByComparingTo("90600");
	}

	@Test
	@DisplayName("오버라이드가 없어도 정책의 쿠폰율·최소마진이 등록가에 반영된다")
	void resolveForProduct_appliesPolicyWhenNoOverrides() {
		givenProduct("10000", "15");
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(pricePolicyService.get()).thenReturn(policy("15", "20", "5000"));

		BigDecimal price = resolver().resolveForProduct(product, MarketType.COUPANG);

		assertThat(price).isEqualByComparingTo("19000");
	}

	@Test
	@DisplayName("일부만 오버라이드하면 쿠폰율은 오버라이드를, 최소마진은 정책을 쓴다")
	void resolveForProduct_policyFillsOnlyMissingOverrides() {
		givenProduct("10000", "15");
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(pricePolicyService.get()).thenReturn(policy("15", "20", "8000"));

		BigDecimal price = resolver().resolveForProduct(product, MarketType.COUPANG,
			new MarketSalePriceOverrides(null, new BigDecimal("10"), null));

		assertThat(price).isEqualByComparingTo("23000");
	}

	@Test
	@DisplayName("정책 최소마진이 산출 마진보다 크면 그만큼 등록가를 끌어올린다")
	void resolveForProduct_policyMinMarginRaisesPrice() {
		givenProduct("10000", "15");
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(pricePolicyService.get()).thenReturn(policy("15", "20", "8000"));

		BigDecimal price = resolver().resolveForProduct(product, MarketType.COUPANG);

		assertThat(price).isEqualByComparingTo("22000");
	}

	@Test
	@DisplayName("정책 행이 없으면 쿠폰·최소마진 없이 계산하던 기존 동작을 그대로 유지한다")
	void resolveForProduct_noPolicyKeepsLegacyResult() {
		givenProduct("10000", "15");
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(pricePolicyService.get()).thenReturn(null);

		BigDecimal price = resolver().resolveForProduct(product, MarketType.COUPANG);

		assertThat(price).isEqualByComparingTo("21600");
	}

	@Test
	@DisplayName("마진율은 오버라이드가 상품값보다 우선한다")
	void resolveForProduct_marginRateOverrideBeatsProduct() {
		givenProduct("10000", "15");
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(pricePolicyService.get()).thenReturn(policy("15", "20", "5000"));

		BigDecimal price = resolver().resolveForProduct(product, MarketType.COUPANG,
			new MarketSalePriceOverrides(new BigDecimal("30"), null, null));

		assertThat(price).isEqualByComparingTo("23700");
	}

	@Test
	@DisplayName("마진율은 상품값이 정책값보다 우선한다")
	void resolveForProduct_marginRateProductBeatsPolicy() {
		givenProduct("10000", "15");
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(pricePolicyService.get()).thenReturn(policy("40", "20", "5000"));

		BigDecimal price = resolver().resolveForProduct(product, MarketType.COUPANG);

		assertThat(price).isEqualByComparingTo("19000");
	}

	@Test
	@DisplayName("상품에 마진율이 없으면 정책 마진율로 계산한다")
	void resolveForProduct_marginRateFallsBackToPolicy() {
		givenProduct("10000", null);
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(pricePolicyService.get()).thenReturn(policy("15", "20", "5000"));

		BigDecimal price = resolver().resolveForProduct(product, MarketType.COUPANG);

		assertThat(price).isEqualByComparingTo("19000");
	}

	private void givenProduct(String costPrice, String marginRate) {
		when(product.getPriceInfo()).thenReturn(PriceInfo.builder()
			.costPrice(new BigDecimal(costPrice))
			.marginRate(marginRate != null ? new BigDecimal(marginRate) : null)
			.build());
	}

	@Test
	void explicitDomesticFeeIsUsedInSyncAndNotReplacedByDefaultShipping() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(BigDecimal.ZERO);
		var inputs = new PricingInputs(new BigDecimal("12340"), 1, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
			BigDecimal.ZERO, null);
		assertThat(resolver().resolve(inputs, MarketType.COUPANG)).isEqualTo(12400);
	}

	@Test
	void fullPricingOverridesDoNotRemoveVendorShippingPolicy() {
		givenProduct("12340", "0");
		when(product.getVendor()).thenReturn(com.sbshop.agent.core.domain.product.enums.VendorType.COK);
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(BigDecimal.ZERO);
		var vendorPolicies = org.mockito.Mockito.mock(VendorPricePolicyService.class);
		when(vendorPolicies.find(product.getVendor())).thenReturn(java.util.Optional.of(
			com.sbshop.agent.core.domain.pricing.VendorPricePolicy.builder().domesticFee(BigDecimal.ZERO).build()));
		var resolver = new MarketSalePriceResolver(marginCalculator, marketFeeService, pricePolicyService,
			vendorPolicies);
		assertThat(resolver.resolveForProduct(product, MarketType.COUPANG,
			new MarketSalePriceOverrides(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)))
			.isEqualByComparingTo("12400");
	}

	private PricePolicy policy(String marginRate, String couponRate, String minMarginPrice) {
		return PricePolicy.builder()
			.marginRate(new BigDecimal(marginRate))
			.couponRate(new BigDecimal(couponRate))
			.minMarginPrice(new BigDecimal(minMarginPrice))
			.build();
	}

	private MarketSalePriceResolver resolver() {
		return new MarketSalePriceResolver(marginCalculator, marketFeeService, pricePolicyService,
			org.mockito.Mockito.mock(VendorPricePolicyService.class));
	}
}
