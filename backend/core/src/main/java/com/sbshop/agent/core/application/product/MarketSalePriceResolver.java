package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * D-094: 마켓별 실수수료(sb_fee_policy)로 그 마켓의 판매가를 산정한다.
 *
 * <p>이 계산의 <b>단일 출처</b>다. 동기화 경로({@link ProductMarketSyncService})와
 * 신규 등록 경로({@link ProductPublishUseCase})가 서로 다른 가격을 만들면,
 * 등록 직후와 배치 이후의 가격이 달라져 원인을 알 수 없는 가격 변동으로 보인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketSalePriceResolver {

	private final MarginCalculator marginCalculator;
	private final MarketFeeService marketFeeService;

	/** 계산 재료가 모두 있을 때(배치 경로): 마켓 실수수료로 산정한 판매가(원, 정수). */
	public Integer resolve(PricingInputs p, MarketType marketType) {
		BigDecimal fee = marketFeeService.feeRate(marketType);
		return marginCalculator.calculateSalePrice(p.buyPrice(), p.bundleQty(), p.marginRate(),
			p.couponRate(), p.minMarginPrice(), fee).intValue();
	}

	/**
	 * 저장된 상품 값만으로 산정한다(신규 등록 경로).
	 *
	 * <p>쿠폰율·최소마진은 배치 실행 파라미터라 상품에 저장되지 않는다. 그래서 둘은 null로 두고
	 * 원가·마진율·묶음수량만으로 계산한다 — 쿠폰 미반영분만큼 <b>보수적으로(약간 높게)</b> 산정되며,
	 * 다음 재가격 배치가 정확한 값으로 낮춘다. 마진이 깎이는 방향이 아니므로 이 편향은 안전하다.
	 *
	 * <p>원가·마진율이 없으면 기준가(sale_price)를 그대로 쓴다. 재료가 없다는 이유로
	 * 등록 자체를 막지는 않는다.
	 */
	public BigDecimal resolveForProduct(Product product, MarketType marketType) {
		BigDecimal costPrice = product.getPriceInfo() != null ? product.getPriceInfo().getCostPrice() : null;
		BigDecimal marginRate = product.getPriceInfo() != null ? product.getPriceInfo().getMarginRate() : null;
		if (costPrice == null || costPrice.signum() <= 0 || marginRate == null) {
			log.info("[등록가] 원가·마진 미보유 → 기준가로 등록: sbCode={}, market={}",
				product.getSbCode(), marketType);
			return product.getSalePrice();
		}
		int bundleQty = product.getLogisticsInfo() != null
			&& product.getLogisticsInfo().getBundleQuantity() != null
				? product.getLogisticsInfo().getBundleQuantity() : 1;
		BigDecimal fee = marketFeeService.feeRate(marketType);
		return marginCalculator.calculateSalePrice(costPrice, bundleQty, marginRate, null, null, fee);
	}
}
