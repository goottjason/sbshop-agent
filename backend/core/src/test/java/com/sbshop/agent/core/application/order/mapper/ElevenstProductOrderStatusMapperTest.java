package com.sbshop.agent.core.application.order.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

/**
 * 2단계: 상태를 <b>목록 소속</b>이 아니라 상품주문의 {@code ordPrdStatNm}으로 판정한다.
 *
 * <p>종전에는 4개 목록(결제완료/배송준비중/배송중/배송완료) 중 어디서 왔는지로 상태를 정했다.
 * 그 구조가 D-126을 낳았고, D-130에서 원인이 확정됐다 — 목록 행은 <b>상품주문 단위</b>라서
 * 한 주문의 순번 1과 순번 2가 서로 다른 목록에 나타난다. 목록 소속은 그 주문의 상태가 아니다.
 *
 * <p>{@code claimservice/orderlistall}은 행마다 {@code ordPrdStatNm}을 직접 주므로 추론이 사라진다.
 */
class ElevenstProductOrderStatusMapperTest {

	private final ElevenstStatusMapper mapper = new ElevenstStatusMapper();

	@Test
	@DisplayName("결제완료 → NEW")
	void mapsPaymentComplete() {
		assertThat(mapper.mapProductOrderStatus("결제완료")).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("배송준비중 → PREPARING")
	void mapsPreparing() {
		assertThat(mapper.mapProductOrderStatus("배송준비중")).isEqualTo(ShippingStatus.PREPARING);
	}

	@Test
	@DisplayName("발송완료·배송중 → SHIPPED")
	void mapsShipped() {
		// 정나영 건 순번2가 "발송완료"였다. 11번가는 발송처리 직후를 발송완료로, 택배 추적이
		// 시작되면 배송중으로 표시한다 — 우리 모델에서는 둘 다 SHIPPED(송장 보유·배송 진행)다.
		assertThat(mapper.mapProductOrderStatus("발송완료")).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(mapper.mapProductOrderStatus("배송중")).isEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("배송완료·구매확정 → DELIVERED")
	void mapsDelivered() {
		assertThat(mapper.mapProductOrderStatus("배송완료")).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(mapper.mapProductOrderStatus("구매확정")).isEqualTo(ShippingStatus.DELIVERED);
	}

	@Test
	@DisplayName("클레임 상태는 종결 상태로 매핑한다")
	void mapsClaims() {
		assertThat(mapper.mapProductOrderStatus("취소완료")).isEqualTo(ShippingStatus.CANCELED);
		assertThat(mapper.mapProductOrderStatus("취소신청")).isEqualTo(ShippingStatus.CANCELED);
		assertThat(mapper.mapProductOrderStatus("반품완료")).isEqualTo(ShippingStatus.RETURNED);
		assertThat(mapper.mapProductOrderStatus("반품신청")).isEqualTo(ShippingStatus.RETURNED);
		assertThat(mapper.mapProductOrderStatus("교환완료")).isEqualTo(ShippingStatus.EXCHANGED);
	}

	@Test
	@DisplayName("배송완료가 배송중으로 오독되지 않는다 — 부분일치 순서 고정")
	void doesNotConfuseDeliveredWithShipping() {
		// "배송완료"·"배송준비중"은 모두 "배송"을 포함한다. 넓은 패턴을 먼저 검사하면 오독한다.
		// 이 테스트가 그 순서를 고정한다.
		assertThat(mapper.mapProductOrderStatus("배송완료")).isNotEqualTo(ShippingStatus.SHIPPED);
		assertThat(mapper.mapProductOrderStatus("배송준비중")).isNotEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("모르는 상태명은 UNKNOWN이다 — 임의로 NEW로 되돌리지 않는다")
	void unknownStaysUnknown() {
		// Cafe24 매퍼가 미매핑 코드를 NEW로 폴백해 배송중 주문이 신규로 되돌아갈 위험이 있다(백로그).
		// 같은 실수를 하지 않는다 — 모르면 UNKNOWN이고, UNKNOWN은 상태를 덮지 않는다.
		assertThat(mapper.mapProductOrderStatus("듣보잡상태")).isEqualTo(ShippingStatus.UNKNOWN);
	}

	@Test
	@DisplayName("빈 값·null은 UNKNOWN이다")
	void blankStaysUnknown() {
		assertThat(mapper.mapProductOrderStatus(null)).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(mapper.mapProductOrderStatus("  ")).isEqualTo(ShippingStatus.UNKNOWN);
	}
}
