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

		Optional<OrderEmailParser.IherbConfirmationData> result = parser.parseIherbConfirmation(SUBJECT, body);

		assertThat(result).isPresent();
		assertThat(result.get().getOrderNo()).isEqualTo("123456789");
		assertThat(result.get().getTotalAmount()).isEqualByComparingTo(new BigDecimal("45000"));
	}

	@Test
	@DisplayName("'총 금액' 2차 패턴만 있는 확인 메일에서도 실구매가를 추출한다")
	void extractsAmountFromSecondaryPattern() {
		String body = "<div>총 금액 &#8361;45,000</div>";

		Optional<OrderEmailParser.IherbConfirmationData> result = parser.parseIherbConfirmation(SUBJECT, body);

		assertThat(result).isPresent();
		assertThat(result.get().getTotalAmount()).isEqualByComparingTo(new BigDecimal("45000"));
	}

	@Test
	@DisplayName("'결제가 처리되었습니다' 제목도 확인 메일로 인식한다(주문번호가 문구 앞)")
	void recognizesPaymentProcessedSubject() {
		String subject = "iHerb 주문 #343977053 결제가 처리되었습니다";
		String body = "총 결제 금액: $48.00";

		Optional<OrderEmailParser.IherbConfirmationData> result = parser.parseIherbConfirmation(subject, body);

		assertThat(result).isPresent();
		assertThat(result.get().getOrderNo()).isEqualTo("343977053");
		assertThat(result.get().getTotalAmount()).isEqualByComparingTo(new BigDecimal("48.00"));
	}

	@Test
	@DisplayName("'총 주문' 라벨(페이코 결제 메일)에서 실구매가를 읽는다")
	void extractsAmountFromOrderTotalLabel() {
		// 실제 운영 본문: "… 대한민국 (KR) 결제 유형: 페이코 총 주문: ₩40,418"
		String subject = "iHerb 주문 #343977053 결제가 처리되었습니다";
		String body = "배송지 대한민국 (KR) 결제 유형: 페이코 총 주문: &#8361;40,418";

		OrderEmailParser.IherbConfirmationData data = parser.parseIherbConfirmation(subject, body).get();

		assertThat(data.getTotalAmount()).isEqualByComparingTo(new BigDecimal("40418"));
		assertThat(data.getCurrency()).isEqualTo(OrderEmailParser.KRW);
	}

	@Test
	@DisplayName("'총 주문 수량' 같은 비금액 매칭은 하한 가드로 걸러지고 다음 패턴으로 넘어간다")
	void orderQuantityDoesNotBecomeAmount() {
		String body = "총 주문 수량 3 개 합계 &#8361;40,418";

		assertThat(parser.parseIherbConfirmation(SUBJECT, body).get().getTotalAmount())
			.isEqualByComparingTo(new BigDecimal("40418"));
	}

	@Test
	@DisplayName("'결제 대기 중'은 결제 확정 전이므로 확인 메일로 보지 않는다")
	void ignoresPaymentPendingSubject() {
		String subject = "주문번호 #343977053 결제 대기 중";

		assertThat(parser.parseIherbConfirmation(subject, "총 결제 금액: $48.00")).isEmpty();
	}

	@Test
	@DisplayName("제목 종류 판정: 발송·확인·대기를 구분한다")
	void classifiesSubjects() {
		assertThat(OrderEmailParser.isConfirmationSubject("iHerb 주문이 확인되었습니다 #1")).isTrue();
		assertThat(OrderEmailParser.isConfirmationSubject("iHerb 주문 #1 결제가 처리되었습니다")).isTrue();
		assertThat(OrderEmailParser.isConfirmationSubject("주문번호 #1 결제 대기 중")).isFalse();
		assertThat(OrderEmailParser.isConfirmationSubject("주문이 발송되었습니다 #1")).isFalse();
		assertThat(OrderEmailParser.isShipmentSubject("주문이 발송되었습니다 #1")).isTrue();
		assertThat(OrderEmailParser.isShipmentSubject("iHerb 주문 #1 결제가 처리되었습니다")).isFalse();
	}

	@Test
	@DisplayName("달러 표기 확인 메일은 액면 금액과 USD 통화를 함께 돌려준다")
	void marksUsdDenominatedTotal() {
		// 실제 운영 메일: "결제 수단: Master Card x2218 총 결제 금액: $48.00"
		String body = "결제 수단: Master Card x2218 총 결제 금액: $48.00 주문 확인 / 관리";

		Optional<OrderEmailParser.IherbConfirmationData> result = parser.parseIherbConfirmation(SUBJECT, body);

		assertThat(result).isPresent();
		assertThat(result.get().getTotalAmount()).isEqualByComparingTo(new BigDecimal("48.00"));
		assertThat(result.get().getCurrency()).isEqualTo(OrderEmailParser.USD);
	}

	@Test
	@DisplayName("달러는 원화 하한(1,000원) 검사를 적용받지 않는다")
	void usdIsNotSubjectToKrwFloor() {
		String body = "총 결제 금액: $30.61";

		OrderEmailParser.IherbConfirmationData data = parser.parseIherbConfirmation(SUBJECT, body).get();

		assertThat(data.getTotalAmount()).isEqualByComparingTo(new BigDecimal("30.61"));
		assertThat(data.getCurrency()).isEqualTo(OrderEmailParser.USD);
	}

	@Test
	@DisplayName("원화 표기는 KRW 통화로 표시된다")
	void marksKrwDenominatedTotal() {
		String body = "총 결제 금액 &#8361;45,254";

		OrderEmailParser.IherbConfirmationData data = parser.parseIherbConfirmation(SUBJECT, body).get();

		assertThat(data.getTotalAmount()).isEqualByComparingTo(new BigDecimal("45254"));
		assertThat(data.getCurrency()).isEqualTo(OrderEmailParser.KRW);
	}

	@Test
	@DisplayName("통화기호 없는 원화 표기도 실구매가로 인정한다")
	void acceptsKrwWithoutSymbol() {
		String body = "총 결제 금액 45,254";

		assertThat(parser.parseIherbConfirmation(SUBJECT, body).get().getTotalAmount())
			.isEqualByComparingTo(new BigDecimal("45254"));
	}

	@Test
	@DisplayName("금액이 실재 불가능한 소액이면 주입하지 않는다(태그 분할 등 오파싱 방어)")
	void rejectsImplausiblySmallAmount() {
		// 숫자가 태그 경계로 쪼개져 "31 ,441" 처럼 평탄화되면 앞 토막만 잡힌다.
		String body = "<td>총 결제 금액</td><td>&#8361;<span>31</span><span>,441</span></td>";

		Optional<OrderEmailParser.IherbConfirmationData> result = parser.parseIherbConfirmation(SUBJECT, body);

		assertThat(result).isPresent();
		assertThat(result.get().getTotalAmount()).isNull();
	}

	@Test
	@DisplayName("소액 매칭 뒤에 정상 금액 패턴이 있으면 그쪽을 채택한다")
	void fallsBackToNextPatternWhenFirstIsImplausible() {
		String body = "<td>총 결제 금액</td><td>&#8361;<span>31</span><span>,441</span></td>"
			+ "<td>합계</td><td>&#8361;31,441</td>";

		Optional<OrderEmailParser.IherbConfirmationData> result = parser.parseIherbConfirmation(SUBJECT, body);

		assertThat(result).isPresent();
		assertThat(result.get().getTotalAmount()).isEqualByComparingTo(new BigDecimal("31441"));
	}

	@Test
	@DisplayName("'합계' 3차 패턴만 있는 확인 메일에서도 실구매가를 추출한다")
	void extractsAmountFromTertiaryPattern() {
		String body = "<div>합계 &#8361;45,000</div>";

		Optional<OrderEmailParser.IherbConfirmationData> result = parser.parseIherbConfirmation(SUBJECT, body);

		assertThat(result).isPresent();
		assertThat(result.get().getTotalAmount()).isEqualByComparingTo(new BigDecimal("45000"));
	}
}
