package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
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

	public Integer resolve(PricingInputs p, MarketType marketType) {
		BigDecimal fee = marketFeeService.feeRate(marketType);
		return marginCalculator.calculateSalePrice(p.buyPrice(), p.bundleQty(), p.marginRate(),
			p.couponRate(), p.minMarginPrice(), fee).intValue();
	}

	public BigDecimal resolveForProduct(Product product, MarketType marketType) {
		return resolveForProduct(product, marketType, MarketSalePriceOverrides.EMPTY);
	}

	public BigDecimal resolveForProduct(Product product, MarketType marketType,
		MarketSalePriceOverrides overrides) {
		MarketSalePriceOverrides o = overrides != null ? overrides : MarketSalePriceOverrides.EMPTY;
		PricePolicy policy = isFullyOverridden(o) ? null : pricePolicyService.get();
		BigDecimal costPrice = product.getPriceInfo() != null ? product.getPriceInfo().getCostPrice() : null;
		BigDecimal marginRate = resolveMarginRate(product, o, policy);
		BigDecimal couponRate = resolveCouponRate(product, o, policy);
		BigDecimal minMarginPrice = resolveMinMarginPrice(product, o, policy);
		if (costPrice == null || costPrice.signum() <= 0 || marginRate == null) {
			log.info("[등록가] 원가·마진 미보유 → 기준가로 등록: sbCode={}, market={}",
				product.getSbCode(), marketType);
			return product.getSalePrice();
		}
		int bundleQty = product.getLogisticsInfo() != null
			&& product.getLogisticsInfo().getBundleQuantity() != null
				? product.getLogisticsInfo().getBundleQuantity() : 1;
		BigDecimal fee = marketFeeService.feeRate(marketType);
		return marginCalculator.calculateSalePrice(costPrice, bundleQty, marginRate,
			couponRate, minMarginPrice, fee);
	}

	private boolean isFullyOverridden(MarketSalePriceOverrides o) {
		return o.marginRate() != null && o.couponRate() != null && o.minMarginPrice() != null;
	}

	private BigDecimal resolveMarginRate(Product product, MarketSalePriceOverrides o, PricePolicy policy) {
		if (o.marginRate() != null) {
			return o.marginRate();
		}
		BigDecimal productRate = product.getPriceInfo() != null
			? product.getPriceInfo().getMarginRate() : null;
		if (productRate != null) {
			return productRate;
		}
		return policy != null ? policy.getMarginRate() : null;
	}

	private BigDecimal resolveCouponRate(Product product, MarketSalePriceOverrides o, PricePolicy policy) {
		if (o.couponRate() != null) {
			return o.couponRate();
		}
		BigDecimal productRate = product.getPriceInfo() != null
			? product.getPriceInfo().getCouponRate() : null;
		if (productRate != null) {
			return productRate;
		}
		return policy != null ? policy.getCouponRate() : null;
	}

	private BigDecimal resolveMinMarginPrice(Product product, MarketSalePriceOverrides o, PricePolicy policy) {
		if (o.minMarginPrice() != null) {
			return o.minMarginPrice();
		}
		BigDecimal productValue = product.getPriceInfo() != null
			? product.getPriceInfo().getMinMarginPrice() : null;
		if (productValue != null) {
			return productValue;
		}
		return policy != null ? policy.getMinMarginPrice() : null;
	}
}
