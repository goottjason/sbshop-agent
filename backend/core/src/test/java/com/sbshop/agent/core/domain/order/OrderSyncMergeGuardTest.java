package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-107(전 마켓 확장): 마켓 동기화 병합(9-arg {@link Order#update})은 개인정보 필드를
 * 빈 문자열·공백·마스킹값으로 기존 실값을 덮어써선 안 된다.
 *
 * <p>마켓들은 배송중·배송완료·오래된 주문에서 개인정보 보호차원으로 이름/주소를 반환하지 않거나
 * ("") 마스킹("정*영")해 내려준다. 전화번호는 이미 {@code isUsablePhone}로 보호되고 있었으나
 * 이름·주소·우편번호·구매자명은 {@code != null}로만 가드해 ""·마스킹값이 실값을 덮어썼다.
 * 이 테스트는 이름/주소/우편/구매자명은 blank+mask, 메시지는 blank를 거부함을 고정한다.
 * (수동 편집 클리어는 {@code updateAddress} 등 별도 경로이므로 영향 없음.)
 */
class OrderSyncMergeGuardTest {

	private Order existingOrder() {
		return Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("O-1")
			.orderDate(LocalDateTime.now())
			.recipientName("정채영")
			.recipientPhone("010-1234-5678")
			.zipcode("06134")
			.address("서울시 강남구 테헤란로 1")
			.message("문앞에 놓아주세요")
			.ordererName("김주문")
			.ordererPhone("010-9876-5432")
			.build();
	}

	@Test
	@DisplayName("빈 문자열 이름/주소/우편/구매자명/메시지는 기존 실값을 덮지 않는다")
	void blankValues_doNotOverwrite() {
		Order order = existingOrder();
		order.update("", "", "", "", "", "", "", null, null);
		assertThat(order.getRecipientName()).isEqualTo("정채영");
		assertThat(order.getAddress()).isEqualTo("서울시 강남구 테헤란로 1");
		assertThat(order.getZipcode()).isEqualTo("06134");
		assertThat(order.getOrdererName()).isEqualTo("김주문");
		assertThat(order.getMessage()).isEqualTo("문앞에 놓아주세요");
	}

	@Test
	@DisplayName("마스킹된(*) 이름/주소/우편/구매자명은 기존 실값을 덮지 않는다")
	void maskedValues_doNotOverwrite() {
		Order order = existingOrder();
		order.update("정*영", "010-1234-5678", "0****", "서울시 강남구 ***", "메시지",
			"김*문", "010-9876-5432", null, null);
		assertThat(order.getRecipientName()).isEqualTo("정채영");
		assertThat(order.getZipcode()).isEqualTo("06134");
		assertThat(order.getAddress()).isEqualTo("서울시 강남구 테헤란로 1");
		assertThat(order.getOrdererName()).isEqualTo("김주문");
	}

	@Test
	@DisplayName("정상 실값은 기존 값을 갱신한다(값 변경 허용)")
	void realValues_overwrite() {
		Order order = existingOrder();
		order.update("이선", "010-1234-5678", "48058", "부산시 해운대구 2", "메시지 변경",
			"이주문", "010-9876-5432", null, null);
		assertThat(order.getRecipientName()).isEqualTo("이선");
		assertThat(order.getZipcode()).isEqualTo("48058");
		assertThat(order.getAddress()).isEqualTo("부산시 해운대구 2");
		assertThat(order.getMessage()).isEqualTo("메시지 변경");
		assertThat(order.getOrdererName()).isEqualTo("이주문");
	}

	@Test
	@DisplayName("null(미전송)은 기존 값을 유지한다")
	void nullValues_keepExisting() {
		Order order = existingOrder();
		order.update(null, null, null, null, null, null, null, null, null);
		assertThat(order.getRecipientName()).isEqualTo("정채영");
		assertThat(order.getAddress()).isEqualTo("서울시 강남구 테헤란로 1");
		assertThat(order.getMessage()).isEqualTo("문앞에 놓아주세요");
	}
}
