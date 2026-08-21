package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderSyncMergeGuardTest {
	@Test
	@DisplayName("빈 문자열 이름/주소/우편/구매자명/메시지는 기존 실값을 덮지 않는다")
	void blankValues_doNotOverwrite() {
		Order order = existingOrder();
		order.update("", "", "", "", "", "", "", null);
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
			"김*문", "010-9876-5432", null);
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
			"이주문", "010-9876-5432", null);
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
		order.update(null, null, null, null, null, null, null, null);
		assertThat(order.getRecipientName()).isEqualTo("정채영");
		assertThat(order.getAddress()).isEqualTo("서울시 강남구 테헤란로 1");
		assertThat(order.getMessage()).isEqualTo("문앞에 놓아주세요");
	}

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
}
