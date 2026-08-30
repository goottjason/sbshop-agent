package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VendorPricePolicyResolutionTest {
	@Mock
	private MarketFeeService marketFeeService;
	@Mock
	private PricePolicyService pricePolicyService;
	@Mock
	private VendorPricePolicyService vendorPricePolicyService;

	private final MarginCalculator marginCalculator = new MarginCalculator();

	private MarketSalePriceResolver resolver() {
		return new MarketSalePriceResolver(marginCalculator, marketFeeService, pricePolicyService,
			vendorPricePolicyService);
	}

	private Product product(VendorType vendor) {
		return Product.create("SB-" + vendor, new ProductCreateCommand(
			"https://example.com/p/1", new BigDecimal("10000"), "n", "on", "b", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 1, new BigDecimal("25"), vendor, null));
	}

	private VendorPricePolicy vendorPolicy(String margin, String coupon, String minMargin) {
		return VendorPricePolicy.builder()
			.vendor(VendorType.VTB)
			.marginRate(new BigDecimal(margin))
			.couponRate(new BigDecimal(coupon))
			.minMarginPrice(new BigDecimal(minMargin))
			.build();
	}

	@Test
	@DisplayName("소싱처 정책의 쿠폰율이 전역 정책을 이긴다 — 아이허브 쿠폰 20%가 영국 소싱처에 걸리면 안 된다")
	void vendorPolicyBeatsGlobalPolicy() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(vendorPricePolicyService.find(VendorType.VTB))
			.thenReturn(Optional.of(vendorPolicy("25", "0", "5000")));
		lenient().when(pricePolicyService.get()).thenReturn(PricePolicy.builder()
			.marginRate(new BigDecimal("15")).couponRate(new BigDecimal("20"))
			.minMarginPrice(new BigDecimal("5000")).build());

		BigDecimal actual = resolver().resolveForProduct(product(VendorType.VTB), MarketType.COUPANG);

		BigDecimal expected = new BigDecimal(marginCalculator.calculateSalePrice(
			new BigDecimal("10000"), 1, new BigDecimal("25"), BigDecimal.ZERO,
			new BigDecimal("5000"), new BigDecimal("11")).toBigInteger());
		assertThat(actual).isEqualByComparingTo(expected);
	}

	@Test
	@DisplayName("상품이 마진율을 갖고 있으면 소싱처 정책보다 우선한다 — 개별 조정이 정책에 덮이지 않는다")
	void productMarginBeatsVendorPolicy() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(vendorPricePolicyService.find(VendorType.VTB))
			.thenReturn(Optional.of(vendorPolicy("40", "0", "5000")));

		BigDecimal actual = resolver().resolveForProduct(product(VendorType.VTB), MarketType.COUPANG);

		BigDecimal expected = new BigDecimal(marginCalculator.calculateSalePrice(
			new BigDecimal("10000"), 1, new BigDecimal("25"), BigDecimal.ZERO,
			new BigDecimal("5000"), new BigDecimal("11")).toBigInteger());
		assertThat(actual).isEqualByComparingTo(expected);
	}

	@Test
	@DisplayName("소싱처 정책이 없으면 전역 정책으로 폴백한다 — 기존 상품의 동작이 바뀌지 않는다")
	void fallsBackToGlobalWhenVendorPolicyMissing() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(vendorPricePolicyService.find(any())).thenReturn(Optional.empty());
		when(pricePolicyService.get()).thenReturn(PricePolicy.builder()
			.marginRate(new BigDecimal("15")).couponRate(new BigDecimal("20"))
			.minMarginPrice(new BigDecimal("5000")).build());

		BigDecimal actual = resolver().resolveForProduct(product(VendorType.OCD), MarketType.COUPANG);

		BigDecimal expected = new BigDecimal(marginCalculator.calculateSalePrice(
			new BigDecimal("10000"), 1, new BigDecimal("25"), new BigDecimal("20"),
			new BigDecimal("5000"), new BigDecimal("11")).toBigInteger());
		assertThat(actual).isEqualByComparingTo(expected);
	}
}
