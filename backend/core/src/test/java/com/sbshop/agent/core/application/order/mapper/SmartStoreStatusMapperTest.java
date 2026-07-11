package com.sbshop.agent.core.application.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-068 회귀 방지: 네이버 커머스 표준 productOrderStatus 누락 코드가 UNKNOWN으로 오맵핑돼
 * 배송정보 수정이 400 차단되던 문제(이명동 주문). 표준 누락 코드 추가 + 기존 매핑/default 유지 확인.
 */
class SmartStoreStatusMapperTest {

	private final SmartStoreStatusMapper mapper = new SmartStoreStatusMapper();

	@Test
	@DisplayName("[D-068] 표준 누락 코드 PAYMENT_WAITING(결제대기)은 NEW로 매핑된다")
	void paymentWaiting_mapsToNew() {
		assertThat(mapper.mapStatus(Map.of("status", "PAYMENT_WAITING"))).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("[D-068] 표준 누락 코드 CANCELED_BY_NOPAYMENT(미결제취소)은 CANCELED로 매핑된다")
	void canceledByNopayment_mapsToCanceled() {
		assertThat(mapper.mapStatus(Map.of("status", "CANCELED_BY_NOPAYMENT")))
				.isEqualTo(ShippingStatus.CANCELED);
	}

	@Test
	@DisplayName("[D-068] 회귀 방지: 기존 표준 매핑은 그대로 유지된다")
	void existingMappings_unchanged() {
		assertThat(mapper.mapStatus(Map.of("status", "PAYED", "placeOrderStatus", "OK")))
				.isEqualTo(ShippingStatus.PREPARING);
		assertThat(mapper.mapStatus(Map.of("status", "DELIVERING"))).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(mapper.mapStatus(Map.of("status", "DELIVERED"))).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(mapper.mapStatus(Map.of("status", "PURCHASE_DECIDED")))
				.isEqualTo(ShippingStatus.DELIVERED);
		assertThat(mapper.mapStatus(Map.of("status", "CANCELED"))).isEqualTo(ShippingStatus.CANCELED);
		assertThat(mapper.mapStatus(Map.of("status", "RETURNED"))).isEqualTo(ShippingStatus.RETURNED);
		assertThat(mapper.mapStatus(Map.of("status", "EXCHANGED"))).isEqualTo(ShippingStatus.EXCHANGED);
	}

	@Test
	@DisplayName("[D-068] default 유지: 미지의 코드는 여전히 UNKNOWN으로 매핑된다(라이브 로그 노출용)")
	void unknownCode_mapsToUnknown() {
		assertThat(mapper.mapStatus(Map.of("status", "SOME_UNKNOWN_CODE")))
				.isEqualTo(ShippingStatus.UNKNOWN);
	}
}
