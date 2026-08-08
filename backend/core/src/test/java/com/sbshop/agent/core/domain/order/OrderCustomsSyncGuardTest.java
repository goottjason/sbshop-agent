package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.VerifiedPerson;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 동기화가 통관번호를 지우지 못하게 하는 중앙 가드(D-107 규율의 통관번호 확장).
 *
 * <p>마켓은 주문이 배송중·배송완료로 넘어가면 개인정보 보호차원에서 필드를 빼거나 마스킹해 내려준다
 * (11번가 배송중 목록엔 {@code psnCscUniqNo} 태그 자체가 없다 — 2026-08-08 라이브 확인).
 * 그런 비실값으로 기존 실값을 덮으면 통관번호가 유실되고, 통관번호는 <b>마켓에서 다시 받아올 수도 없다</b>
 * (지워지면 복구 불가). 지금은 어댑터들이 empty→null로 정규화해 막고 있지만, 어댑터 하나만 바뀌어도
 * 뚫리는 얇은 방어다 — 이름·주소가 그렇게 유실됐던 D-107의 반복을 막기 위해 도메인에 정본 가드를 둔다.
 *
 * <p>수동 편집의 클리어 시맨틱(F-ORD-23)은 {@code updateCustomsClearanceNo} 별도 경로라 영향받지 않는다.
 */
class OrderCustomsSyncGuardTest {

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
		// 번호가 실제로 바뀌었으므로 검증상태는 무효화된다(D-073).
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
}
