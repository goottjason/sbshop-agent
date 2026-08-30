package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import com.sbshop.agent.core.domain.product.vo.PriceInfo;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PricingParamsSymmetryTest {
	@Mock
	private MarketFeeService marketFeeService;
	@Mock
	private PricePolicyService pricePolicyService;

	private final MarginCalculator marginCalculator = new MarginCalculator();

	private MarketSalePriceResolver resolver() {
		return new MarketSalePriceResolver(marginCalculator, marketFeeService, pricePolicyService,
			org.mockito.Mockito.mock(VendorPricePolicyService.class));
	}

	private Product base(String sb, BigDecimal margin) {
		return Product.create(sb, new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", new BigDecimal("10000"), "n", "on", "b", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 1, margin, VendorType.IHB, null));
	}

	private Product productWith(BigDecimal margin, BigDecimal coupon, BigDecimal minMargin) {
		Product p = base("SB-TEST", margin);
		p.update(ProductUpdateCommand.builder()
			.costPrice(new BigDecimal("10000")).marginRate(margin)
			.couponRate(coupon).minMarginPrice(minMargin).build());
		return p;
	}

	private PricePolicy policy(String margin, String coupon, String minMargin) {
		return PricePolicy.builder()
			.marginRate(new BigDecimal(margin))
			.couponRate(new BigDecimal(coupon))
			.minMarginPrice(new BigDecimal(minMargin))
			.build();
	}

	@Test
	@DisplayName("배치가 쓴 세 값(마진율·쿠폰율·최소마진가)이 모두 상품에 남는다 — 마진율만 남으면 가격을 재현할 수 없다")
	void update_persistsAllThreePricingParams() {
		Product product = base("SB-TEST-1", new BigDecimal("10"));

		product.update(ProductUpdateCommand.builder()
			.costPrice(new BigDecimal("10000"))
			.marginRate(new BigDecimal("25"))
			.couponRate(BigDecimal.ZERO)
			.minMarginPrice(new BigDecimal("7000"))
			.build());

		PriceInfo info = product.getPriceInfo();
		assertThat(info.getMarginRate()).isEqualByComparingTo("25");
		assertThat(info.getCouponRate()).isEqualByComparingTo("0");
		assertThat(info.getMinMarginPrice()).isEqualByComparingTo("7000");
	}

	@Test
	@DisplayName("상품에 저장된 쿠폰율·최소마진가가 전역 정책보다 우선한다 — 마진율과 같은 우선순위여야 한다")
	void resolveForProduct_productParamsBeatPolicy() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		lenient().when(pricePolicyService.get()).thenReturn(policy("15", "20", "5000"));

		Product coupled = productWith(new BigDecimal("25"), BigDecimal.ZERO, new BigDecimal("5000"));

		BigDecimal actual = resolver().resolveForProduct(coupled, MarketType.COUPANG);

		BigDecimal expectedNoCoupon = new BigDecimal(marginCalculator.calculateSalePrice(
			new BigDecimal("10000"), 1, new BigDecimal("25"), BigDecimal.ZERO,
			new BigDecimal("5000"), new BigDecimal("11")).toBigInteger());
		assertThat(actual).isEqualByComparingTo(expectedNoCoupon);
	}

	@Test
	@DisplayName("상품에 값이 없으면 전역 정책으로 폴백한다 — 기존 상품의 동작이 바뀌지 않는다")
	void resolveForProduct_fallsBackToPolicyWhenProductHasNone() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(pricePolicyService.get()).thenReturn(policy("15", "20", "5000"));

		Product legacy = productWith(new BigDecimal("15"), null, null);

		BigDecimal actual = resolver().resolveForProduct(legacy, MarketType.COUPANG);

		BigDecimal expectedWithPolicyCoupon = new BigDecimal(marginCalculator.calculateSalePrice(
			new BigDecimal("10000"), 1, new BigDecimal("15"), new BigDecimal("20"),
			new BigDecimal("5000"), new BigDecimal("11")).toBigInteger());
		assertThat(actual).isEqualByComparingTo(expectedWithPolicyCoupon);
	}

	@Test
	@DisplayName("오버라이드는 여전히 상품값을 이긴다 — 배치가 명시한 값이 최우선")
	void resolveForProduct_overrideBeatsProduct() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));

		Product product = productWith(new BigDecimal("25"), BigDecimal.ZERO, new BigDecimal("5000"));

		BigDecimal actual = resolver().resolveForProduct(product, MarketType.COUPANG,
			new MarketSalePriceOverrides(new BigDecimal("4"), new BigDecimal("20"), new BigDecimal("5000")));

		BigDecimal expected = new BigDecimal(marginCalculator.calculateSalePrice(
			new BigDecimal("10000"), 1, new BigDecimal("4"), new BigDecimal("20"),
			new BigDecimal("5000"), new BigDecimal("11")).toBigInteger());
		assertThat(actual).isEqualByComparingTo(expected);
	}

	@Test
	@DisplayName("배치가 쿠폰 0%를 쓰면 0이 그대로 저장된다 — null 과 0 을 구분하지 못하면 전역 20%로 되돌아간다")
	void update_persistsZeroCouponDistinctFromNull() {
		Product product = base("SB-TEST-2", new BigDecimal("25"));

		product.update(ProductUpdateCommand.builder()
			.costPrice(new BigDecimal("10000"))
			.marginRate(new BigDecimal("25"))
			.couponRate(BigDecimal.ZERO)
			.minMarginPrice(new BigDecimal("5000"))
			.build());

		assertThat(product.getPriceInfo().getCouponRate()).isNotNull();
		assertThat(product.getPriceInfo().getCouponRate()).isEqualByComparingTo("0");
	}
}
