package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * iHerb 주문 확인 메일 → 실구매가 추출 검증.
 * 이메일 동기화가 실구매비용을 자동 주입하는 경로의 유일한 파싱 지점이다.
 */
class OrderEmailParserConfirmationTest {

	private final OrderEmailParser parser = new OrderEmailParser();

	private static final String SUBJECT = "iHerb 주문이 확인되었습니다 #123456789";

	@Test
	@DisplayName("'총 결제 금액'이 1회 등장하는 확인 메일에서 실구매가를 추출한다")
	void extractsAmountFromPrimaryPattern() {
		String body = """
			<html><body>
			<p>주문해 주셔서 감사합니다.</p>
			<table><tr><td>소계</td><td>&#8361;38,000</td></tr>
			<tr><td>배송비</td><td>&#8361;7,000</td></tr>
			<tr><td>총 결제 금액</td><td>&#8361;45,000</td></tr></table>
			</body></html>
			""";

		Optional<OrderEmailParser.IherbConfirmationData> result =
			parser.parseIherbConfirmation(SUBJECT, body);

		assertThat(result).isPresent();
		assertThat(result.get().getOrderNo()).isEqualTo("123456789");
		assertThat(result.get().getTotalAmount()).isEqualByComparingTo(new BigDecimal("45000"));
	}

	@Test
	@DisplayName("'총 금액' 2차 패턴만 있는 확인 메일에서도 실구매가를 추출한다")
	void extractsAmountFromSecondaryPattern() {
		String body = "<div>총 금액 &#8361;45,000</div>";

		Optional<OrderEmailParser.IherbConfirmationData> result =
			parser.parseIherbConfirmation(SUBJECT, body);

		assertThat(result).isPresent();
		assertThat(result.get().getTotalAmount()).isEqualByComparingTo(new BigDecimal("45000"));
	}

	@Test
	@DisplayName("'합계' 3차 패턴만 있는 확인 메일에서도 실구매가를 추출한다")
	void extractsAmountFromTertiaryPattern() {
		String body = "<div>합계 &#8361;45,000</div>";

		Optional<OrderEmailParser.IherbConfirmationData> result =
			parser.parseIherbConfirmation(SUBJECT, body);

		assertThat(result).isPresent();
		assertThat(result.get().getTotalAmount()).isEqualByComparingTo(new BigDecimal("45000"));
	}
}
