package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.TrackingSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 송장 출처는 "누가 처음 썼나"가 아니라 <b>"무엇이 이 값을 확인했나"</b>다.
 * 화면은 이 값으로 📧(이메일이 확인한 진짜)와 ✍(진위 불명)를 가른다.
 */
class ShipmentTrackingSourceTest {

	private Shipment shipment() {
		return Shipment.builder().orderId(1L).marketShipmentNo("S-1").build();
	}

	@Test
	@DisplayName("출처를 기록한다")
	void recordsSource() {
		Shipment s = shipment();

		s.applyTrackingSource(TrackingSource.EMAIL);

		assertThat(s.getTrackingSource()).isEqualTo(TrackingSource.EMAIL);
	}

	@Test
	@DisplayName("null은 '판단 없음'이라 기존 출처를 지우지 않는다")
	void nullKeepsExisting() {
		Shipment s = shipment();
		s.applyTrackingSource(TrackingSource.EMAIL);

		s.applyTrackingSource(null);

		assertThat(s.getTrackingSource()).isEqualTo(TrackingSource.EMAIL);
	}

	@Test
	@DisplayName("사람이 나중에 덮어쓰면 출처도 사람으로 바뀐다 — 진짜가 가송장으로 바뀐 사실을 숨기지 않는다")
	void manualOverwriteDowngrades() {
		Shipment s = shipment();
		s.applyTrackingSource(TrackingSource.EMAIL);

		s.applyTrackingSource(TrackingSource.MANUAL);

		assertThat(s.getTrackingSource()).isEqualTo(TrackingSource.MANUAL);
	}

	@Test
	@DisplayName("기록한 적 없으면 null — 과거 데이터는 아이콘 없이 둔다")
	void defaultsToNull() {
		assertThat(shipment().getTrackingSource()).isNull();
	}
}
