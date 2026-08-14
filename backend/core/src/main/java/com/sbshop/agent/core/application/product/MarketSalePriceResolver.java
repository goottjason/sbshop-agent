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
	 * 저장된 상품 값만으로 산정한다(신규 등록 경로, 오버라이드 없음).
	 *
	 * <p>쿠폰율·최소마진은 배치 실행 파라미터라 상품에 저장되지 않는다. 그래서 둘은 null로 두고
	 * 원가·마진율·묶음수량만으로 계산한다 — 쿠폰 미반영분만큼 <b>보수적으로(약간 높게)</b> 산정된다.
	 * 이 값을 정확한 값으로 낮춰줄 정기 재가격 배치는 D-093 사용자 결정으로 <b>비활성</b>이다
	 * ({@link com.sbshop.agent.worker.scheduler.BatchScheduler}) — 그래서 호출자가
	 * {@link #resolveForProduct(Product, MarketType, MarketSalePriceOverrides)}로 쿠폰율·최소마진을
	 * 직접 넘기지 않는 한 이 편향은 등록 시점에 그대로 남는다.
	 *
	 * <p>원가·마진율이 없으면 기준가(sale_price)를 그대로 쓴다. 재료가 없다는 이유로
	 * 등록 자체를 막지는 않는다.
	 */
	public BigDecimal resolveForProduct(Product product, MarketType marketType) {
		return resolveForProduct(product, marketType, MarketSalePriceOverrides.EMPTY);
	}

	/**
	 * 신규 등록 경로에서도 쿠폰율·최소마진·마진율을 호출자가 지정할 수 있게 한다(등록가 20%대 고평가 결함 수정).
	 * {@code overrides}의 각 필드가 null이면 그 항목은 반영하지 않는다(마진율은 상품 저장값으로 폴백).
	 * 오버라이드 없이 부르고 싶으면 {@link #resolveForProduct(Product, MarketType)}를 쓴다.
	 */
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
