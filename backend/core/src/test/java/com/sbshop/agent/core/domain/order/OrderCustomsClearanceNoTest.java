package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.VerifiedPerson;
import com.sbshop.agent.core.domain.order.vo.CustomsData;

class OrderCustomsClearanceNoTest {

	private Order orderWithCustoms(String no, CustomsStatus status, VerifiedPerson person) {
		return Order.builder()
			.customsData(CustomsData.builder()
				.customsClearanceNo(no)
				.customsStatus(status)
				.verifiedPerson(person)
				.build())
			.build();
	}

	@Test
	@DisplayName("같은 통관번호 재하달 시 사용자 검증상태(VALID/RECIPIENT)를 유지한다 — D-073")
	void sameNumberPreservesVerification() {
		// 사용자가 수기로 검증한 상태
		Order order = orderWithCustoms("P123456789012", CustomsStatus.VALID, VerifiedPerson.RECIPIENT);

		// 마켓 동기화가 같은 번호를 재하달
		order.updateCustomsClearanceNo("P123456789012");

		assertThat(order.getCustomsData().getCustomsClearanceNo()).isEqualTo("P123456789012");
		assertThat(order.getCustomsData().getCustomsStatus()).isEqualTo(CustomsStatus.VALID);
		assertThat(order.getCustomsData().getVerifiedPerson()).isEqualTo(VerifiedPerson.RECIPIENT);
	}

	@Test
	@DisplayName("다른 통관번호로 변경 시 재검증 위해 PENDING/NONE으로 무효화한다")
	void differentNumberResetsVerification() {
		Order order = orderWithCustoms("P123456789012", CustomsStatus.VALID, VerifiedPerson.RECIPIENT);

		order.updateCustomsClearanceNo("P999999999999");

		assertThat(order.getCustomsData().getCustomsClearanceNo()).isEqualTo("P999999999999");
		assertThat(order.getCustomsData().getCustomsStatus()).isEqualTo(CustomsStatus.PENDING);
		assertThat(order.getCustomsData().getVerifiedPerson()).isEqualTo(VerifiedPerson.NONE);
	}

	@Test
	@DisplayName("최초 통관번호 세팅(기존 번호 없음) 시 미검증(PENDING/NONE)으로 저장한다")
	void firstTimeSetIsPending() {
		// 기존 번호 없는 신규 주문
		Order order = orderWithCustoms(null, CustomsStatus.PENDING, VerifiedPerson.NONE);

		order.updateCustomsClearanceNo("P123456789012");

		assertThat(order.getCustomsData().getCustomsClearanceNo()).isEqualTo("P123456789012");
		assertThat(order.getCustomsData().getCustomsStatus()).isEqualTo(CustomsStatus.PENDING);
		assertThat(order.getCustomsData().getVerifiedPerson()).isEqualTo(VerifiedPerson.NONE);
	}
}
