package com.sbshop.agent.core.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

/**
 * 배송(Shipment)은 "물리적으로 함께 나가는 단위"다. 송장 1개 = Shipment 1개.
 *
 * <p>마켓별 배송 식별자(11번가 dlvNo · 쿠팡 shipmentBoxId · N스토어 packageNumber ·
 * Cafe24 shipping_code)를 {@code marketShipmentNo} 한 컬럼에 담는다. 넷 다 역할이 같기 때문이다.
 *
 * <p>{@code (order_id, market_shipment_no)} 유니크는 동기화 매칭의 하드가드다. 이게 없으면
 * 같은 배송이 중복 생성돼, 송장을 마켓에 두 번 보내는 사고로 이어진다
 * (11번가 -3308 "해당 배송번호는 이미 발송처리 되었습니다").
 */
@DataJpaTest
@ContextConfiguration(classes = ShipmentEntityTest.TestApp.class)
class ShipmentEntityTest {

	@SpringBootApplication
	@EnableJpaRepositories(basePackages = "com.sbshop.agent.core.dummy")
	static class TestApp {}

	@Autowired
	private EntityManager em;

	@Test
	@DisplayName("배송을 저장하고 다시 읽으면 송장·택배사·발송일이 보존된다")
	void persistsAndReadsBack() {
		Shipment shipment = Shipment.builder()
			.orderId(100L)
			.marketShipmentNo("2716448228")
			.trackingNo("424079080471")
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.deliveryStatus("DELIVERING")
			.trackingSentToMarket(true)
			.shippedAt(LocalDateTime.of(2026, 8, 5, 12, 0))
			.build();

		em.persist(shipment);
		em.flush();
		em.clear();

		Shipment found = em.find(Shipment.class, shipment.getId());
		assertThat(found.getOrderId()).isEqualTo(100L);
		assertThat(found.getMarketShipmentNo()).isEqualTo("2716448228");
		assertThat(found.getTrackingNo()).isEqualTo("424079080471");
		assertThat(found.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(found.getDeliveryStatus()).isEqualTo("DELIVERING");
		assertThat(found.getTrackingSentToMarket()).isTrue();
		assertThat(found.getShippedAt()).isEqualTo(LocalDateTime.of(2026, 8, 5, 12, 0));
		assertThat(found.getStatus().name()).isEqualTo("ACTIVE");
	}

	@Test
	@DisplayName("같은 주문에 같은 배송식별자로 두 건을 저장하면 유니크 제약 위반이 발생한다")
	void rejectsDuplicateShipmentNoWithinOrder() {
		em.persist(Shipment.builder().orderId(100L).marketShipmentNo("2716448228").build());
		em.flush();

		// GenerationType.IDENTITY 엔티티는 persist 시점에 즉시 INSERT되어 ID를 얻으므로,
		// 유니크 제약 위반이 persist(+flush) 경로에서 표면화된다.
		assertThatThrownBy(() -> {
			em.persist(Shipment.builder().orderId(100L).marketShipmentNo("2716448228").build());
			em.flush();
		}).isInstanceOf(ConstraintViolationException.class);
	}

	@Test
	@DisplayName("주문이 다르면 같은 배송식별자를 써도 된다")
	void allowsSameShipmentNoAcrossOrders() {
		em.persist(Shipment.builder().orderId(100L).marketShipmentNo("SAME").build());
		em.persist(Shipment.builder().orderId(200L).marketShipmentNo("SAME").build());

		em.flush();
	}

	@Test
	@DisplayName("applyTracking의 null 인자는 기존 값을 지우지 않는다")
	void applyTrackingKeepsExistingOnNull() {
		// 마켓이 이번 응답에서 송장을 주지 않았다고 해서 이미 가진 송장을 지우면 안 된다
		// (D-119/D-120에서 자리표시자·빈 값이 실송장을 덮어써 배송정보가 유실된 이력).
		Shipment shipment = Shipment.builder()
			.orderId(100L)
			.marketShipmentNo("D1")
			.trackingNo("424079080471")
			.shippingCarrier(ShippingCarrier.CJ_LOGISTICS)
			.trackingSentToMarket(true)
			.build();

		shipment.applyTracking(null, null, null);

		assertThat(shipment.getTrackingNo()).isEqualTo("424079080471");
		assertThat(shipment.getShippingCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(shipment.getTrackingSentToMarket()).isTrue();
	}

	@Test
	@DisplayName("applyTracking에 실값을 주면 갱신된다")
	void applyTrackingUpdatesOnRealValue() {
		Shipment shipment = Shipment.builder().orderId(100L).marketShipmentNo("D1").build();

		shipment.applyTracking("6079990333504", ShippingCarrier.KOREA_POST, true);

		assertThat(shipment.getTrackingNo()).isEqualTo("6079990333504");
		assertThat(shipment.getShippingCarrier()).isEqualTo(ShippingCarrier.KOREA_POST);
		assertThat(shipment.getTrackingSentToMarket()).isTrue();
	}
}
