package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 마켓별 판매가 산정의 단일 출처 검증.
 * 동기화 경로(ProductMarketSyncService)와 신규 등록 경로(ProductPublishUseCase)가
 * 같은 계산을 쓰게 하려고 추출한 컴포넌트다.
 */
@ExtendWith(MockitoExtension.class)
class MarketSalePriceResolverTest {

	@Mock
	private MarketFeeService marketFeeService;
	@Mock
	private Product product;

	private final MarginCalculator marginCalculator = new MarginCalculator();

	private MarketSalePriceResolver resolver() {
		return new MarketSalePriceResolver(marginCalculator, marketFeeService);
	}

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
}
