package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.controller.PricePolicyController.PricePolicyRequest;
import com.sbshop.agent.api.controller.PricePolicyController.PricePolicyResponse;
import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class PricePolicyControllerTest {

	@Mock
	private PricePolicyService pricePolicyService;

	@Test
	@DisplayName("GET → 저장된 정책의 마진율·쿠폰율·최소마진을 반환한다")
	void getPolicy_returnsStoredValues() {
		when(pricePolicyService.get()).thenReturn(policy("15", "20", "5000"));

		ResponseEntity<PricePolicyResponse> response = controller().getPolicy();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().marginRate()).isEqualByComparingTo("15");
		assertThat(response.getBody().couponRate()).isEqualByComparingTo("20");
		assertThat(response.getBody().minMarginPrice()).isEqualByComparingTo("5000");
	}

	@Test
	@DisplayName("GET → 정책 행이 없어도 200에 null 필드로 응답한다")
	void getPolicy_noRow_returnsNullFields() {
		when(pricePolicyService.get()).thenReturn(null);

		ResponseEntity<PricePolicyResponse> response = controller().getPolicy();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().marginRate()).isNull();
		assertThat(response.getBody().couponRate()).isNull();
		assertThat(response.getBody().minMarginPrice()).isNull();
	}

	@Test
	@DisplayName("PUT → 요청 본문 세 값을 서비스에 그대로 위임하고 저장 결과를 반환한다")
	void updatePolicy_delegatesToService() {
		when(pricePolicyService.update(new BigDecimal("18"), new BigDecimal("25"), new BigDecimal("7000")))
			.thenReturn(policy("18", "25", "7000"));

		ResponseEntity<PricePolicyResponse> response = controller().updatePolicy(
			new PricePolicyRequest(new BigDecimal("18"), new BigDecimal("25"), new BigDecimal("7000")));

		verify(pricePolicyService).update(new BigDecimal("18"), new BigDecimal("25"), new BigDecimal("7000"));
		assertThat(response.getBody().marginRate()).isEqualByComparingTo("18");
		assertThat(response.getBody().couponRate()).isEqualByComparingTo("25");
		assertThat(response.getBody().minMarginPrice()).isEqualByComparingTo("7000");
	}

	private PricePolicy policy(String marginRate, String couponRate, String minMarginPrice) {
		return PricePolicy.builder()
			.marginRate(new BigDecimal(marginRate))
			.couponRate(new BigDecimal(couponRate))
			.minMarginPrice(new BigDecimal(minMarginPrice))
			.build();
	}

	private PricePolicyController controller() {
		return new PricePolicyController(pricePolicyService);
	}
}
