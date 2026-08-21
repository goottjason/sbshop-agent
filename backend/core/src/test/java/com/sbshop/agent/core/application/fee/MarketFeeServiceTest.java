package com.sbshop.agent.core.application.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.fee.FeePolicy;
import com.sbshop.agent.core.domain.fee.repository.FeePolicyRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketFeeServiceTest {

	@Mock
	private FeePolicyRepository feePolicyRepository;

	@InjectMocks
	private MarketFeeService marketFeeService;

	@Test
	@DisplayName("FeePolicy(DB)에 요율이 있으면 그 값을 사용한다")
	void feeRate_dbHit_usesDbValue() {
		when(feePolicyRepository.findByMarketType(MarketType.COUPANG))
			.thenReturn(List.of(fee(MarketType.COUPANG, "11")));
		assertThat(marketFeeService.feeRate(MarketType.COUPANG)).isEqualByComparingTo("11");
	}

	@Test
	@DisplayName("DB에 마켓 행이 없으면 코드 기본 요율로 폴백한다 (쿠팡11·스토어8·11번가/G마켓/옥션/카페24 18)")
	void feeRate_dbMiss_fallsBackToDefault() {
		when(feePolicyRepository.findByMarketType(any()))
			.thenReturn(List.of());
		assertThat(marketFeeService.feeRate(MarketType.COUPANG)).isEqualByComparingTo("11");
		assertThat(marketFeeService.feeRate(MarketType.SMART_STORE)).isEqualByComparingTo("8");
		assertThat(marketFeeService.feeRate(MarketType.ELEVEN_STREET)).isEqualByComparingTo("18");
		assertThat(marketFeeService.feeRate(MarketType.GMARKET)).isEqualByComparingTo("18");
		assertThat(marketFeeService.feeRate(MarketType.AUCTION)).isEqualByComparingTo("18");
		assertThat(marketFeeService.feeRate(MarketType.CAFE24)).isEqualByComparingTo("18");
	}

	@Test
	@DisplayName("정산액 = 총액 × (1 - 요율/100)을 마켓별로 한 번만 적용한다")
	void settlementAmount_appliesMarketRateOnce() {
		when(feePolicyRepository.findByMarketType(any()))
			.thenReturn(List.of());
		assertThat(marketFeeService.settlementAmount(new BigDecimal("10000"), MarketType.COUPANG))
			.isEqualByComparingTo("8900");
		assertThat(marketFeeService.settlementAmount(new BigDecimal("10000"), MarketType.SMART_STORE))
			.isEqualByComparingTo("9200");
		assertThat(marketFeeService.settlementAmount(new BigDecimal("10000"), MarketType.ELEVEN_STREET))
			.isEqualByComparingTo("8200");
	}

	@Test
	@DisplayName("금액이 null이면 정산액도 null")
	void settlementAmount_null_returnsNull() {
		assertThat(marketFeeService.settlementAmount(null, MarketType.COUPANG)).isNull();
	}

	private FeePolicy fee(MarketType market, String rate) {
		return FeePolicy.builder()
			.marketType(market)
			.categoryName("기본")
			.feeRate(new BigDecimal(rate))
			.build();
	}
}
