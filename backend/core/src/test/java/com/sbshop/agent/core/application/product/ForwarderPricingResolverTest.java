package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.pricing.ForwarderShipping;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
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

/** D-360 6-3 고정 결정 4·5·7·9·10 — 판매가 경로가 소싱처 배대지 요금표와 공통 정책 하한을 읽는다. */
@ExtendWith(MockitoExtension.class)
class ForwarderPricingResolverTest {
	@Mock
	private MarketFeeService marketFeeService;
	@Mock
	private PricePolicyService pricePolicyService;
	@Mock
	private VendorPricePolicyService vendorPricePolicyService;

	private final MarginCalculator marginCalculator = new MarginCalculator();
	private static final ForwarderShipping.Rate OREGON = new ForwarderShipping.Rate(new BigDecimal("10500"), 500,
		new BigDecimal("2600"), 500, 200);

	private MarketSalePriceResolver resolver() {
		return new MarketSalePriceResolver(marginCalculator, marketFeeService, pricePolicyService,
			vendorPricePolicyService);
	}

	/** 예 A의 상품: 개당 원가 27,881원, 2개입, 0.3kg, 상품 마진 15%. */
	private Product ihbProduct(BigDecimal weightKg) {
		return Product.create("SB-IHB", new ProductCreateCommand(
			"https://example.com/p/1", new BigDecimal("27881"), "n", "on", "b", "US",
			weightKg, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 2, new BigDecimal("15"), VendorType.IHB, null));
	}

	/** 아이허브 정책: 쿠폰 0·최소마진 15,000, 국내 배송비 0(무료 기준 0). */
	private VendorPricePolicy ihbPolicy(ForwarderShipping.Rate forwarder) {
		return VendorPricePolicy.builder().vendor(VendorType.IHB).couponRate(BigDecimal.ZERO)
			.minMarginPrice(new BigDecimal("15000")).shipCurrency("KRW").domesticFee(BigDecimal.ZERO)
			.domesticFreeOver(BigDecimal.ZERO).forwarder(forwarder).build();
	}

	private PricePolicy commonPolicy(String floorRate, String floorAmount) {
		return PricePolicy.builder().marginRate(new BigDecimal("15")).couponRate(new BigDecimal("20"))
			.minMarginPrice(new BigDecimal("5000"))
			.marginFloorRate(floorRate == null ? null : new BigDecimal(floorRate))
			.marginFloorAmount(floorAmount == null ? null : new BigDecimal(floorAmount)).build();
	}

	@Test
	@DisplayName("새 칸이 모두 비어 있으면 판매가가 지금 식과 1원도 다르지 않다")
	void emptyNewColumnsKeepCurrentPrice() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(vendorPricePolicyService.find(VendorType.IHB)).thenReturn(Optional.of(
			VendorPricePolicy.builder().vendor(VendorType.IHB).couponRate(BigDecimal.ZERO)
				.minMarginPrice(new BigDecimal("15000")).domesticFee(new BigDecimal("6000"))
				.domesticFreeOver(new BigDecimal("40000")).build()));
		when(pricePolicyService.get()).thenReturn(commonPolicy(null, null));

		var explanation = resolver().explainForProduct(ihbProduct(new BigDecimal("0.3")), MarketType.COUPANG,
			MarketSalePriceOverrides.EMPTY);

		var expected = marginCalculator.quoteSalePrice(new BigDecimal("27881"), 2, new BigDecimal("15"),
			BigDecimal.ZERO, new BigDecimal("15000"), new BigDecimal("11"), new BigDecimal("6000"),
			new BigDecimal("40000"));
		assertThat(explanation.basis()).isEqualTo(MarketSalePriceResolver.Basis.CALCULATED);
		assertThat(explanation.result()).isEqualTo(expected);
		assertThat(explanation.forwarderNote()).isNull();
	}

	@Test
	@DisplayName("예 A: 아이허브 배대지 요금표가 있으면 13,100원을 더해 쿠팡 93,100원")
	void exampleA_forwarderFeeFromVendorPolicy() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(vendorPricePolicyService.find(VendorType.IHB)).thenReturn(Optional.of(ihbPolicy(OREGON)));
		when(pricePolicyService.get()).thenReturn(commonPolicy("5", "5000"));

		var explanation = resolver().explainForProduct(ihbProduct(new BigDecimal("0.3")), MarketType.COUPANG,
			MarketSalePriceOverrides.EMPTY);

		assertThat(explanation.salePrice()).isEqualByComparingTo("93100");
		assertThat(explanation.forwarderNote()).contains("13,100원").contains("800g");
	}

	@Test
	@DisplayName("예 B: 배치 값(마진 3%·최소마진 0)을 상품에 저장해도 공통 정책 하한이 82,000원으로 올린다")
	void exampleB_floorFromCommonPolicy() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(vendorPricePolicyService.find(VendorType.IHB)).thenReturn(Optional.of(ihbPolicy(OREGON)));
		when(pricePolicyService.get()).thenReturn(commonPolicy("5", "5000"));
		Product product = ihbProduct(new BigDecimal("0.3"));
		product.update(ProductUpdateCommand.builder().marginRate(new BigDecimal("3")).couponRate(BigDecimal.ZERO)
			.minMarginPrice(BigDecimal.ZERO).build());

		var explanation = resolver().explainForProduct(product, MarketType.COUPANG, MarketSalePriceOverrides.EMPTY);

		assertThat(explanation.salePrice()).isEqualByComparingTo("82000");
		assertThat(explanation.result().floorApplied()).isTrue();
	}

	@Test
	@DisplayName("마진·쿠폰·최소마진을 모두 지정해도(등록 오버라이드) 하한은 공통 정책에서 읽는다")
	void fullOverridesStillApplyFloor() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(vendorPricePolicyService.find(VendorType.IHB)).thenReturn(Optional.of(ihbPolicy(OREGON)));
		when(pricePolicyService.get()).thenReturn(commonPolicy("5", "5000"));

		BigDecimal price = resolver().resolveForProduct(ihbProduct(new BigDecimal("0.3")), MarketType.COUPANG,
			new MarketSalePriceOverrides(new BigDecimal("3"), BigDecimal.ZERO, BigDecimal.ZERO));

		assertThat(price).isEqualByComparingTo("82000");
	}

	@Test
	@DisplayName("배대지 요금이 있는데 상품 무게가 없으면 계산하지 않는다 — 사유를 돌려주고 등록은 거부한다")
	void missingWeightIsUnavailable() {
		when(vendorPricePolicyService.find(VendorType.IHB)).thenReturn(Optional.of(ihbPolicy(OREGON)));
		Product product = ihbProduct(null);

		var explanation = resolver().explainForProduct(product, MarketType.COUPANG, MarketSalePriceOverrides.EMPTY);

		assertThat(explanation.basis()).isEqualTo(MarketSalePriceResolver.Basis.UNAVAILABLE);
		assertThat(explanation.salePrice()).isNull();
		assertThat(explanation.unavailableReason()).contains("무게");
		assertThatThrownBy(() -> resolver().resolveForProduct(product, MarketType.COUPANG))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("무게");
	}

	@Test
	@DisplayName("배대지 요금이 없는 소싱처는 무게가 없어도 지금처럼 계산한다")
	void missingWeightWithoutForwarderIsCalculated() {
		when(marketFeeService.feeRate(MarketType.COUPANG)).thenReturn(new BigDecimal("11"));
		when(vendorPricePolicyService.find(VendorType.IHB)).thenReturn(Optional.of(ihbPolicy(null)));

		var explanation = resolver().explainForProduct(ihbProduct(null), MarketType.COUPANG,
			MarketSalePriceOverrides.EMPTY);

		assertThat(explanation.basis()).isEqualTo(MarketSalePriceResolver.Basis.CALCULATED);
		assertThat(explanation.salePrice()).isNotNull();
	}

	@Test
	@DisplayName("소싱 초안: 새 칸이 비면 지금 초안 식(국내 배송비 기본값·최소마진 없음)과 같다")
	void draftWithEmptyColumnsKeepsCurrentPrice() {
		when(vendorPricePolicyService.find(VendorType.IHB)).thenReturn(Optional.of(ihbPolicy(null)));
		when(pricePolicyService.get()).thenReturn(commonPolicy(null, null));

		var explanation = resolver().explainForDraft(VendorType.IHB, new BigDecimal("27881"), 2,
			new BigDecimal("20"), new BigDecimal("10"), new BigDecimal("0.3"), new BigDecimal("11"));

		BigDecimal current = marginCalculator.calculateSalePrice(new BigDecimal("27881"), 2, new BigDecimal("20"),
			new BigDecimal("10"), null, new BigDecimal("11"));
		assertThat(explanation.salePrice()).isEqualByComparingTo(current);
	}

	@Test
	@DisplayName("소싱 초안: 배대지 배송비와 마진 하한을 상품 경로와 같은 규칙으로 더한다")
	void draftAddsForwarderFeeAndFloor() {
		when(vendorPricePolicyService.find(VendorType.IHB)).thenReturn(Optional.of(ihbPolicy(OREGON)));
		when(pricePolicyService.get()).thenReturn(commonPolicy("5", "5000"));

		var explanation = resolver().explainForDraft(VendorType.IHB, new BigDecimal("27881"), 2,
			new BigDecimal("3"), BigDecimal.ZERO, new BigDecimal("0.3"), new BigDecimal("11"));

		// 총 매입가 55,762 + 배대지 13,100 = 68,862원(55,762원 ≥ 40,000원이라 국내 배송비 없음) → 하한가 82,000원
		assertThat(explanation.salePrice()).isEqualByComparingTo("82000");
		assertThat(explanation.forwarderNote()).contains("13,100원");
	}

	@Test
	@DisplayName("소싱 초안: 배대지 요금이 있는데 무게가 없으면 계산하지 않는다")
	void draftMissingWeightIsUnavailable() {
		when(vendorPricePolicyService.find(VendorType.IHB)).thenReturn(Optional.of(ihbPolicy(OREGON)));

		var explanation = resolver().explainForDraft(VendorType.IHB, new BigDecimal("27881"), 2,
			new BigDecimal("20"), BigDecimal.ZERO, null, new BigDecimal("11"));

		assertThat(explanation.basis()).isEqualTo(MarketSalePriceResolver.Basis.UNAVAILABLE);
	}
}
