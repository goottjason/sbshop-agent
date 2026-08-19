package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.vo.CustomsData;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 수동으로 고친 주소·배송메시지·통관번호를 마켓 동기화가 되돌리지 않는다.
 *
 * <p><b>왜 단순 잠금이 아닌가:</b> 한 번 손댔다고 그 필드를 영원히 잠그면, 고객이 마켓에서
 * 실제로 배송지를 바꿨을 때 그 변경을 놓쳐 엉뚱한 곳으로 보내게 된다. 그래서 "마켓이 보낸
 * 값"을 따로 보관하고 <b>그 값이 직전과 달라졌을 때만</b> 반영한다. 마켓이 같은 값을 계속
 * 재전송하는 것(=대부분의 동기화)은 무시되므로 수동 수정본이 살아남고, 마켓 값이 진짜로
 * 바뀌면 고객 변경으로 보고 반영한다.
 *
 * <p><b>스냅샷이 없는 행:</b> 종전대로 적용한다. "모르면 덮지 않는다"로 하면, 배포 직전에
 * 고객이 배송지를 바꾼 주문의 변경이 첫 동기화에서 무시된 채 스냅샷에만 기록돼 영영 반영되지
 * 않는다(막으려던 오배송을 오히려 만든다). 대신 배포 시 스냅샷을 현재 값으로 백필해
 * 그 상황 자체를 없앤다.
 */
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
		// 마켓에서 한 번 동기화된 상태를 만든다(스냅샷 확보).
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
			// update()를 한 번도 거치지 않은 상태 = 마켓 원본을 모르는 행(백필 전 창).
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

			// 마켓이 같은 값을 재전송 → 수동 수정본 유지
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
