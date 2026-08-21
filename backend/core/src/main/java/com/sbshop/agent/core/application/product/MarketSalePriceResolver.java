package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
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
		BigDecimal costPrice = product.getPriceInfo() != null ? product.getPriceInfo().getCostPrice() : null;
		BigDecimal marginRate = o.marginRate() != null ? o.marginRate()
			: (product.getPriceInfo() != null ? product.getPriceInfo().getMarginRate() : null);
		if (costPrice == null || costPrice.signum() <= 0 || marginRate == null) {
			log.info("[등록가] 원가·마진 미보유 → 기준가로 등록: sbCode={}, market={}",
				product.getSbCode(), marketType);
			return product.getSalePrice();
		}
		int bundleQty = product.getLogisticsInfo() != null
			&& product.getLogisticsInfo().getBundleQuantity() != null
				? product.getLogisticsInfo().getBundleQuantity() : 1;
		BigDecimal fee = marketFeeService.feeRate(marketType);
		return marginCalculator.calculateSalePrice(
			costPrice, bundleQty, marginRate, o.couponRate(), o.minMarginPrice(), fee);
	}
}
