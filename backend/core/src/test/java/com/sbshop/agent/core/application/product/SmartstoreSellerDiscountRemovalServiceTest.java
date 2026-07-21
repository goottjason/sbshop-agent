package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-096: 스마트스토어 판매자 즉시할인 일괄 제거 — 순회·집계.
 */
@ExtendWith(MockitoExtension.class)
class SmartstoreSellerDiscountRemovalServiceTest {

	@Mock private MarketRegistrationRepository marketRegistrationRepository;
	@Mock private MarketClientRouter marketClientRouter;
	@Mock private MarketClient smartstoreClient;

	private SmartstoreSellerDiscountRemovalService service;

	@BeforeEach
	void setUp() {
		service = new SmartstoreSellerDiscountRemovalService(marketRegistrationRepository, marketClientRouter);
		service.setRetryBackoffMs(0L); // 테스트: 백오프 지연 제거
		service.setThrottleMs(0L);
	}

	private MarketRegistration reg(String originProductNo) {
		return MarketRegistration.builder()
			.productId(1L)
			.marketType(MarketType.SMART_STORE)
			.marketIdentifiers("{\"originProductNo\":\"" + originProductNo + "\"}")
			.marketDetailedInfo("{}")
			.build();
	}

	@Test
	@DisplayName("스토어 등록 상품별로 즉시할인 제거를 호출하고 제거/스킵/실패를 집계한다")
	void removesAndAggregates() {
		when(marketRegistrationRepository.findByProductIdIn(List.of(1L, 2L)))
			.thenReturn(List.of(reg("OP1"), reg("OP2")));
		when(marketClientRouter.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.SMART_STORE)).thenReturn(smartstoreClient);
		when(smartstoreClient.removeSellerImmediateDiscount(eq("OP1"), eq(false)))
			.thenReturn(Optional.of("9 PERCENT"));
		when(smartstoreClient.removeSellerImmediateDiscount(eq("OP2"), eq(false)))
			.thenReturn(Optional.empty());

		Map<String, Object> summary = service.removeForProducts(List.of(1L, 2L), false);

		assertThat(summary.get("removed")).isEqualTo(1);
		assertThat(summary.get("skipped")).isEqualTo(1);
		assertThat(summary.get("failed")).isEqualTo(0);
		assertThat(summary.get("total")).isEqualTo(2);
	}

	@Test
	@DisplayName("일시 실패(429 등)는 재시도해 성공으로 처리한다 — rate limit 복원력")
	void transientFailure_isRetriedThenSucceeds() {
		when(marketRegistrationRepository.findByProductIdIn(List.of(1L)))
			.thenReturn(List.of(reg("OP1")));
		when(marketClientRouter.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.SMART_STORE)).thenReturn(smartstoreClient);
		when(smartstoreClient.removeSellerImmediateDiscount(eq("OP1"), eq(false)))
			.thenThrow(new RuntimeException("429 TOO_MANY_REQUESTS"))
			.thenReturn(Optional.of("9 PERCENT"));

		Map<String, Object> summary = service.removeForProducts(List.of(1L), false);

		assertThat(summary.get("removed")).isEqualTo(1);
		assertThat(summary.get("failed")).isEqualTo(0);
	}
}
