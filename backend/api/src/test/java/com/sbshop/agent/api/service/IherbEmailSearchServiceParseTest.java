package com.sbshop.agent.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OrderEmailParser 와 동일한 이중 find() 결함이 복제돼 있던 경로.
 * 1차·2차 패턴 매칭이 버려지고 본문의 다음 숫자가 실구매가로 잡혔다.
 */
class IherbEmailSearchServiceParseTest {

	private final IherbEmailSearchService service = new IherbEmailSearchService(null);

	@Test
	@DisplayName("'총 결제 금액' 1차 패턴의 첫 매칭을 실구매가로 반환한다")
	void primaryPatternFirstMatch() {
		String body = "소계 ₩38,000 배송비 ₩7,000 총 결제 금액 ₩45,000 문의: 1234";

		Optional<BigDecimal> amount = service.parseAmount(body);

		assertThat(amount).isPresent();
		assertThat(amount.get()).isEqualByComparingTo(new BigDecimal("45000"));
	}

	@Test
	@DisplayName("'총 금액' 2차 패턴만 있어도 실구매가를 반환한다")
	void secondaryPattern() {
		String body = "총 금액 ₩45,000 주문번호 123456789";

		assertThat(service.parseAmount(body)).contains(new BigDecimal("45000"));
	}

	@Test
	@DisplayName("'합계' 3차 패턴만 있어도 실구매가를 반환한다")
	void tertiaryPattern() {
		String body = "합계 ₩45,000";

		assertThat(service.parseAmount(body)).contains(new BigDecimal("45000"));
	}

	@Test
	@DisplayName("금액 패턴이 없으면 빈 값을 반환한다")
	void noPattern() {
		assertThat(service.parseAmount("주문해 주셔서 감사합니다")).isEmpty();
	}
}
