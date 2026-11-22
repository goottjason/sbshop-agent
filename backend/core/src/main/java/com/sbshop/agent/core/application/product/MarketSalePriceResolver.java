package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import com.sbshop.agent.core.domain.pricing.ForwarderShipping;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import com.sbshop.agent.core.domain.product.service.SalePriceRounding;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketSalePriceResolver {
	private final MarginCalculator marginCalculator;
	private final MarketFeeService marketFeeService;
	private final PricePolicyService pricePolicyService;
	private final VendorPricePolicyService vendorPricePolicyService;

	public enum Basis {
		CALCULATED, STORED_PRICE_FALLBACK,
		/** 계산 재료(배대지 요금이 있는 소싱처의 상품 무게 등)가 없어 계산하지 않았다 — 기존 가격을 유지한다(D-360). */
		UNAVAILABLE
	}

	/**
	 * unavailableReason: UNAVAILABLE일 때 사유. forwarderNote: 배대지 배송비를 더했을 때 근거
	 * (예: "배대지 배송비 13,100원 · 계산 무게 800g = 300g × 2 + 포장 200g"), 더하지 않았으면 null.
	 */
	public record Explanation(Basis basis, SalePriceRounding.Result result, String unavailableReason,
		String forwarderNote) {
		public Explanation(Basis basis, SalePriceRounding.Result result) {
			this(basis, result, null, null);
		}

		public BigDecimal salePrice() {
			return result == null ? null : result.salePrice();
		}
	}

	public Integer resolve(PricingInputs p, MarketType marketType) {
		BigDecimal fee = marketFeeService.feeRate(marketType);
		BigDecimal price = p.domesticFee() == null
			? marginCalculator.calculateSalePrice(p.buyPrice(), p.bundleQty(), p.marginRate(), p.couponRate(),
				p.minMarginPrice(), fee)
			: marginCalculator.calculateSalePrice(p.buyPrice(), p.bundleQty(), p.marginRate(), p.couponRate(),
				p.minMarginPrice(), fee,
				p.domesticFee(), p.domesticFreeOver());
		return SalePriceRounding.nearestHundred(price).intValueExact();
	}

	public BigDecimal resolveForProduct(Product product, MarketType marketType) {
		return resolveForProduct(product, marketType, MarketSalePriceOverrides.EMPTY);
	}

	public BigDecimal resolveForProduct(Product product, MarketType marketType,
		MarketSalePriceOverrides overrides) {
		Explanation explanation = explainForProduct(product, marketType, overrides);
		if (explanation.basis() == Basis.UNAVAILABLE)
			throw new IllegalStateException(explanation.unavailableReason());
		return explanation.salePrice();
	}

	public Explanation explainForProduct(Product product, MarketType marketType, MarketSalePriceOverrides overrides) {
		MarketSalePriceOverrides o = overrides != null ? overrides : MarketSalePriceOverrides.EMPTY;
		// 마진 하한은 공통 정책에 있어 마진·쿠폰·최소마진을 모두 지정해도 읽는다(D-360 6-3 고정 결정 7).
		PricePolicy policy = pricePolicyService.get();
		// 마진·쿠폰·최소마진을 지정해도 소싱처의 국내 배송비 규칙은 유지한다.
		VendorPricePolicy vendorPolicy = vendorPricePolicyService.find(product.getVendor()).orElse(null);
		BigDecimal costPrice = product.getPriceInfo() != null ? product.getPriceInfo().getCostPrice() : null;
		BigDecimal marginRate = resolveMarginRate(product, o, vendorPolicy, policy);
		BigDecimal couponRate = resolveCouponRate(product, o, vendorPolicy, policy);
		BigDecimal minMarginPrice = resolveMinMarginPrice(product, o, vendorPolicy, policy);
		if (costPrice == null || costPrice.signum() <= 0 || marginRate == null) {
			log.info("[등록가] 원가·마진 미보유 → 기준가로 등록: sbCode={}, market={}",
				product.getSbCode(), marketType);
			return new Explanation(Basis.STORED_PRICE_FALLBACK, product.getSalePrice() == null ? null
				: SalePriceRounding.fromPrice(product.getSalePrice(), null));
		}
		int bundleQty = product.getLogisticsInfo() != null
			&& product.getLogisticsInfo().getBundleQuantity() != null
				? product.getLogisticsInfo().getBundleQuantity() : 1;
		BigDecimal weightKg = product.getLogisticsInfo() != null ? product.getLogisticsInfo().getWeight() : null;
		var forwarder = ForwarderShipping.quote(vendorPolicy == null ? null : vendorPolicy.forwarderRate(), weightKg,
			bundleQty);
		if (!forwarder.available()) {
			log.info("[판매가] 계산 불가 — 기존 가격 유지: sbCode={}, market={}, 사유={}", product.getSbCode(), marketType,
				forwarder.unavailableReason());
			return new Explanation(Basis.UNAVAILABLE, null, forwarder.unavailableReason(), null);
		}
		BigDecimal fee = marketFeeService.feeRate(marketType);
		// 소싱처 국내 배송비가 비어 있으면 코드 기본값(6,000원/40,000원)을 쓴다. 0과 빈 값은 다르다.
		boolean defaultDomestic = vendorPolicy == null || vendorPolicy.getDomesticFee() == null;
		BigDecimal domesticFee = defaultDomestic ? MarginCalculator.DELIVERY_FEE : vendorPolicy.getDomesticFee();
		BigDecimal domesticFreeOver = defaultDomestic
			? MarginCalculator.DELIVERY_FEE_THRESHOLD : vendorPolicy.getDomesticFreeOver();
		return new Explanation(Basis.CALCULATED, marginCalculator.quoteSalePrice(costPrice, bundleQty, marginRate,
			couponRate, minMarginPrice, fee, domesticFee, domesticFreeOver, forwarder.amount(), floor(policy)),
			null, forwarder.note());
	}

	/**
	 * 소싱 초안의 마켓 판매가(D-360 6-3 고정 결정 10). 초안의 기존 식(국내 배송비 기본값, 최소마진 없음)은 그대로 두고
	 * 배대지 배송비와 마진 하한만 상품 경로와 같은 규칙으로 더한다 — 새 칸이 비어 있으면 지금과 같다.
	 */
	public Explanation explainForDraft(VendorType vendor, BigDecimal costPrice, int bundleQty, BigDecimal marginRate,
		BigDecimal couponRate, BigDecimal weightKg, BigDecimal feeRate) {
		VendorPricePolicy vendorPolicy = vendorPricePolicyService.find(vendor).orElse(null);
		var forwarder = ForwarderShipping.quote(vendorPolicy == null ? null : vendorPolicy.forwarderRate(), weightKg,
			bundleQty);
		if (!forwarder.available())
			return new Explanation(Basis.UNAVAILABLE, null, forwarder.unavailableReason(), null);
		return new Explanation(Basis.CALCULATED, marginCalculator.quoteSalePrice(costPrice, bundleQty, marginRate,
			couponRate, null, feeRate, MarginCalculator.DELIVERY_FEE, MarginCalculator.DELIVERY_FEE_THRESHOLD,
			forwarder.amount(), floor(pricePolicyService.get())), null, forwarder.note());
	}

	private static MarginCalculator.MarginFloor floor(PricePolicy policy) {
		return policy == null ? MarginCalculator.MarginFloor.NONE : policy.marginFloor();
	}

	private BigDecimal resolveMarginRate(Product product, MarketSalePriceOverrides o,
		VendorPricePolicy vendorPolicy, PricePolicy policy) {
		if (o.marginRate() != null) {
			return o.marginRate();
		}
		BigDecimal productRate = product.getPriceInfo() != null
			? product.getPriceInfo().getMarginRate() : null;
		if (productRate != null) {
			return productRate;
		}
		if (vendorPolicy != null && vendorPolicy.getMarginRate() != null) {
			return vendorPolicy.getMarginRate();
		}
		return policy != null ? policy.getMarginRate() : null;
	}

	private BigDecimal resolveCouponRate(Product product, MarketSalePriceOverrides o,
		VendorPricePolicy vendorPolicy, PricePolicy policy) {
		if (o.couponRate() != null) {
			return o.couponRate();
		}
		BigDecimal productRate = product.getPriceInfo() != null
			? product.getPriceInfo().getCouponRate() : null;
		if (productRate != null) {
			return productRate;
		}
		if (vendorPolicy != null && vendorPolicy.getCouponRate() != null) {
			return vendorPolicy.getCouponRate();
		}
		return policy != null ? policy.getCouponRate() : null;
	}

	private BigDecimal resolveMinMarginPrice(Product product, MarketSalePriceOverrides o,
		VendorPricePolicy vendorPolicy, PricePolicy policy) {
		if (o.minMarginPrice() != null) {
			return o.minMarginPrice();
		}
		BigDecimal productValue = product.getPriceInfo() != null
			? product.getPriceInfo().getMinMarginPrice() : null;
		if (productValue != null) {
			return productValue;
		}
		if (vendorPolicy != null && vendorPolicy.getMinMarginPrice() != null) {
			return vendorPolicy.getMinMarginPrice();
		}
		return policy != null ? policy.getMinMarginPrice() : null;
	}
}
