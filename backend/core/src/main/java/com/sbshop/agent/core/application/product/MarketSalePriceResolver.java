package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

	public Integer resolve(PricingInputs p, MarketType marketType) {
		BigDecimal fee = marketFeeService.feeRate(marketType);
		BigDecimal price = marginCalculator.calculateSalePrice(p.buyPrice(), p.bundleQty(), p.marginRate(),
			p.couponRate(), p.minMarginPrice(), fee);
		return roundForMarket(price, marketType).intValue();
	}

	public BigDecimal resolveForProduct(Product product, MarketType marketType) {
		return resolveForProduct(product, marketType, MarketSalePriceOverrides.EMPTY);
	}

	public BigDecimal resolveForProduct(Product product, MarketType marketType,
		MarketSalePriceOverrides overrides) {
		MarketSalePriceOverrides o = overrides != null ? overrides : MarketSalePriceOverrides.EMPTY;
		PricePolicy policy = isFullyOverridden(o) ? null : pricePolicyService.get();
		VendorPricePolicy vendorPolicy = isFullyOverridden(o) ? null
			: vendorPricePolicyService.find(product.getVendor()).orElse(null);
		BigDecimal costPrice = product.getPriceInfo() != null ? product.getPriceInfo().getCostPrice() : null;
		BigDecimal marginRate = resolveMarginRate(product, o, vendorPolicy, policy);
		BigDecimal couponRate = resolveCouponRate(product, o, vendorPolicy, policy);
		BigDecimal minMarginPrice = resolveMinMarginPrice(product, o, vendorPolicy, policy);
		if (costPrice == null || costPrice.signum() <= 0 || marginRate == null) {
			log.info("[등록가] 원가·마진 미보유 → 기준가로 등록: sbCode={}, market={}",
				product.getSbCode(), marketType);
			return roundForMarket(product.getSalePrice(), marketType);
		}
		int bundleQty = product.getLogisticsInfo() != null
			&& product.getLogisticsInfo().getBundleQuantity() != null
				? product.getLogisticsInfo().getBundleQuantity() : 1;
		BigDecimal fee = marketFeeService.feeRate(marketType);
		if (vendorPolicy == null || vendorPolicy.getDomesticFee() == null) {
			return roundForMarket(marginCalculator.calculateSalePrice(costPrice, bundleQty, marginRate,
				couponRate, minMarginPrice, fee), marketType);
		}
		return roundForMarket(marginCalculator.calculateSalePrice(costPrice, bundleQty, marginRate,
			couponRate, minMarginPrice, fee,
			vendorPolicy.getDomesticFee(), vendorPolicy.getDomesticFreeOver()), marketType);
	}

	private BigDecimal roundForMarket(BigDecimal price, MarketType marketType) {
		if (price == null || marketType != MarketType.SMART_STORE) {
			return price;
		}
		return price.divide(BigDecimal.TEN, 0, RoundingMode.CEILING).multiply(BigDecimal.TEN);
	}

	private boolean isFullyOverridden(MarketSalePriceOverrides o) {
		return o.marginRate() != null && o.couponRate() != null && o.minMarginPrice() != null;
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
