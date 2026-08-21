package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OrderManualEditPreservedTest {
	private Order syncedOrder() {
		Order order = Order.builder()
			.marketType(MarketType.COUPANG)
			.marketOrderNo("O-1")
			.orderDate(LocalDateTime.now())
			.recipientName("정채영")
			.recipientPhone("01012345678")
			.zipcode("06134")
			.address("서울시 강남구 테헤란로 1")
			.message("문앞에 놓아주세요")
			.ordererName("김주문")
			.ordererPhone("01098765432")
			.customsData(CustomsData.builder().customsClearanceNo("P111").build())
			.build();
		order.update("정채영", "01012345678", "06134", "서울시 강남구 테헤란로 1",
			"문앞에 놓아주세요", "김주문", "01098765432", null);
		order.applyCustomsClearanceNoFromMarket("P111");
		return order;
	}

	@Nested
	class 마켓이_같은_값을_재전송하면 {
		@Test
		@DisplayName("수동으로 고친 주소가 유지된다")
		void keepsManualAddress() {
			Order order = syncedOrder();
			order.updateAddress("서울시 강남구 테헤란로 1, 101동 202호");

			order.update("정채영", "01012345678", "06134", "서울시 강남구 테헤란로 1",
				"문앞에 놓아주세요", "김주문", "01098765432", null);

			assertThat(order.getAddress()).isEqualTo("서울시 강남구 테헤란로 1, 101동 202호");
		}

		@Test
		@DisplayName("수동으로 고친 배송메시지가 유지된다")
		void keepsManualMessage() {
			Order order = syncedOrder();
			order.updateMessage("경비실에 맡겨주세요");

			order.update("정채영", "01012345678", "06134", "서울시 강남구 테헤란로 1",
				"문앞에 놓아주세요", "김주문", "01098765432", null);

			assertThat(order.getMessage()).isEqualTo("경비실에 맡겨주세요");
		}

		@Test
		@DisplayName("수동으로 고친 통관번호가 유지된다")
		void keepsManualCustomsNo() {
			Order order = syncedOrder();
			order.updateCustomsClearanceNo("P999");

			order.applyCustomsClearanceNoFromMarket("P111");

			assertThat(order.getCustomsData().getCustomsClearanceNo()).isEqualTo("P999");
		}
	}

	@Nested
	class 마켓_값이_실제로_바뀌면 {
		@Test
		@DisplayName("고객이 배송지를 바꾼 것이므로 주소를 반영한다")
		void appliesChangedAddress() {
			Order order = syncedOrder();
			order.updateAddress("서울시 강남구 테헤란로 1, 101동 202호");

			order.update("정채영", "01012345678", "13529", "성남시 분당구 판교로 5",
				"문앞에 놓아주세요", "김주문", "01098765432", null);

			assertThat(order.getAddress()).isEqualTo("성남시 분당구 판교로 5");
		}

		@Test
		@DisplayName("바뀐 배송메시지를 반영한다")
		void appliesChangedMessage() {
			Order order = syncedOrder();
			order.updateMessage("경비실에 맡겨주세요");

			order.update("정채영", "01012345678", "06134", "서울시 강남구 테헤란로 1",
				"부재시 문앞", "김주문", "01098765432", null);

			assertThat(order.getMessage()).isEqualTo("부재시 문앞");
		}

		@Test
		@DisplayName("바뀐 통관번호를 반영한다")
		void appliesChangedCustomsNo() {
			Order order = syncedOrder();
			order.updateCustomsClearanceNo("P999");

			order.applyCustomsClearanceNoFromMarket("P222");

			assertThat(order.getCustomsData().getCustomsClearanceNo()).isEqualTo("P222");
		}
	}

	@Nested
	class 스냅샷이_없는_행 {
		private Order legacyOrder() {
			return Order.builder()
				.marketType(MarketType.COUPANG)
				.marketOrderNo("O-2")
				.orderDate(LocalDateTime.now())
				.address("기존 주소")
				.message("기존 메시지")
				.customsData(CustomsData.builder().customsClearanceNo("P999").build())
				.build();
		}

		@Test
		@DisplayName("종전대로 마켓 값을 반영한다 — 배포 직전 고객 변경을 놓치지 않기 위해")
		void firstSync_appliesAsBefore() {
			Order order = legacyOrder();

			order.update("정채영", "01012345678", "06134", "성남시 분당구 판교로 5",
				"부재시 문앞", "김주문", "01098765432", null);
			order.applyCustomsClearanceNoFromMarket("P111");

			assertThat(order.getAddress()).isEqualTo("성남시 분당구 판교로 5");
			assertThat(order.getMessage()).isEqualTo("부재시 문앞");
			assertThat(order.getCustomsData().getCustomsClearanceNo()).isEqualTo("P111");
		}

		@Test
		@DisplayName("한 번 동기화되고 나면 그 다음부터 수동 수정본이 보호된다")
		void afterFirstSync_protectionKicksIn() {
			Order order = legacyOrder();
			order.update("정채영", "01012345678", "06134", "성남시 분당구 판교로 5",
				"부재시 문앞", "김주문", "01098765432", null);

			order.updateAddress("성남시 분당구 판교로 5, 3층");

			order.update("정채영", "01012345678", "06134", "성남시 분당구 판교로 5",
				"부재시 문앞", "김주문", "01098765432", null);

			assertThat(order.getAddress()).isEqualTo("성남시 분당구 판교로 5, 3층");
		}
	}

	@Nested
	class 수동_경로는_스냅샷을_건드리지_않는다 {
		@Test
		@DisplayName("수동 편집 후에도 마켓이 같은 값을 보내면 여전히 무시된다(스냅샷 오염 없음)")
		void manualEditDoesNotPoisonSnapshot() {
			Order order = syncedOrder();

			order.updateAddress("1차 수정");
			order.updateAddress("2차 수정");

			order.update("정채영", "01012345678", "06134", "서울시 강남구 테헤란로 1",
				"문앞에 놓아주세요", "김주문", "01098765432", null);

			assertThat(order.getAddress()).isEqualTo("2차 수정");
		}
	}
}
