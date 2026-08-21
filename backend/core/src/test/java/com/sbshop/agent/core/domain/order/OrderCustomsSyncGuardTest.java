package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.VerifiedPerson;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderCustomsSyncGuardTest {
	@Test
	@DisplayName("동기화가 빈 문자열을 주면 기존 통관번호를 보존한다")
	void syncKeepsExistingWhenBlank() {
		Order order = orderWithCustomsNo("P123456789012");

		order.applyCustomsClearanceNoFromMarket("");

		assertThat(order.getCustomsData().getCustomsClearanceNo()).isEqualTo("P123456789012");
	}

	@Test
	@DisplayName("동기화가 null을 주면 기존 통관번호를 보존한다")
	void syncKeepsExistingWhenNull() {
		Order order = orderWithCustomsNo("P123456789012");

		order.applyCustomsClearanceNoFromMarket(null);

		assertThat(order.getCustomsData().getCustomsClearanceNo()).isEqualTo("P123456789012");
	}

	@Test
	@DisplayName("동기화가 마스킹 값을 주면 기존 통관번호를 보존한다")
	void syncKeepsExistingWhenMasked() {
		Order order = orderWithCustomsNo("P123456789012");

		order.applyCustomsClearanceNoFromMarket("P12345******");

		assertThat(order.getCustomsData().getCustomsClearanceNo()).isEqualTo("P123456789012");
	}

	@Test
	@DisplayName("보존 시 사용자의 검증상태(VALID)도 함께 유지된다")
	void syncKeepsVerificationStateWhenPreserving() {
		Order order = orderWithCustomsNo("P123456789012");

		order.applyCustomsClearanceNoFromMarket("");

		assertThat(order.getCustomsData().getCustomsStatus()).isEqualTo(CustomsStatus.VALID);
		assertThat(order.getCustomsData().getVerifiedPerson()).isEqualTo(VerifiedPerson.RECIPIENT);
	}

	@Test
	@DisplayName("동기화가 실값을 주면 반영한다 — 정상 변경을 막지 않는다")
	void syncAppliesRealValue() {
		Order order = orderWithCustomsNo("P123456789012");

		order.applyCustomsClearanceNoFromMarket("P999999999999");

		assertThat(order.getCustomsData().getCustomsClearanceNo()).isEqualTo("P999999999999");
		assertThat(order.getCustomsData().getCustomsStatus()).isEqualTo(CustomsStatus.PENDING);
	}

	@Test
	@DisplayName("통관번호가 비어 있던 주문에는 동기화 실값이 정상 반영된다")
	void syncFillsWhenPreviouslyEmpty() {
		Order order = orderWithCustomsNo(null);

		order.applyCustomsClearanceNoFromMarket("P123456789012");

		assertThat(order.getCustomsData().getCustomsClearanceNo()).isEqualTo("P123456789012");
	}

	@Test
	@DisplayName("수동 편집 경로는 여전히 빈 값으로 지울 수 있다 — F-ORD-23 클리어 시맨틱 회귀")
	void manualPathStillClears() {
		Order order = orderWithCustomsNo("P123456789012");

		order.updateCustomsClearanceNo("");

		assertThat(order.getCustomsData().getCustomsClearanceNo()).isEmpty();
	}

	private Order orderWithCustomsNo(String customsNo) {
		return Order.builder()
			.marketType(MarketType.ELEVEN_STREET)
			.marketOrderNo("20260709083393133")
			.orderDate(LocalDateTime.now())
			.recipientName("이영한")
			.customsData(CustomsData.builder()
				.customsClearanceNo(customsNo)
				.customsStatus(CustomsStatus.VALID)
				.verifiedPerson(VerifiedPerson.RECIPIENT)
				.build())
			.build();
	}
}
