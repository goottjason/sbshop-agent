package com.sbshop.agent.core.application.order.mapper;

import java.util.HashMap;
import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SmartStoreStatusMapperTest {
	private final SmartStoreStatusMapper mapper = new SmartStoreStatusMapper();

	@Test
	@DisplayName("DISPATCHED(발송처리) → DISPATCHED 매핑 (배송지시 상태)")
	void dispatched_mapsToDispatched() {
		ShippingStatus result = mapper.mapStatus(Map.of("status", "DISPATCHED"));
		assertThat(result).isEqualTo(ShippingStatus.DISPATCHED);
	}

	@Test
	@DisplayName("DELIVERING → SHIPPED 유지")
	void delivering_mapsToShipped() {
		ShippingStatus result = mapper.mapStatus(Map.of("status", "DELIVERING"));
		assertThat(result).isEqualTo(ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("PRODUCT_PREPARE → PREPARING 유지")
	void productPrepare_mapsToPreparing() {
		ShippingStatus result = mapper.mapStatus(Map.of("status", "PRODUCT_PREPARE"));
		assertThat(result).isEqualTo(ShippingStatus.PREPARING);
	}

	@Test
	@DisplayName("D-117: PAYED + 발주확인해제(placeOrderStatus=CANCEL) → NEW (취소 아님, 발송대기 주문)")
	void payedWithPlaceOrderCanceled_mapsToNew() {
		ShippingStatus result = mapper.mapStatus(
			Map.of("status", "PAYED", "placeOrderStatus", "CANCEL"));
		assertThat(result).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("D-117: PAYED + 발주확인완료(OK) → PREPARING 유지")
	void payedWithPlaceOrderOk_mapsToPreparing() {
		ShippingStatus result = mapper.mapStatus(
			Map.of("status", "PAYED", "placeOrderStatus", "OK"));
		assertThat(result).isEqualTo(ShippingStatus.PREPARING);
	}

	@Test
	@DisplayName("D-117: PAYED + 발주확인전(NOT_YET) → NEW 유지")
	void payedWithPlaceOrderNotYet_mapsToNew() {
		ShippingStatus result = mapper.mapStatus(
			Map.of("status", "PAYED", "placeOrderStatus", "NOT_YET"));
		assertThat(result).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("D-117: placeOrderStatus 누락(null)이어도 NPE 없이 NEW로 매핑")
	void payedWithNullPlaceOrderStatus_mapsToNewWithoutException() {
		Map<String, String> statuses = new HashMap<>();
		statuses.put("status", "PAYED");
		statuses.put("placeOrderStatus", null);

		ShippingStatus result = mapper.mapStatus(statuses);
		assertThat(result).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("D-270: 취소(CANCELED)는 배송 단계를 말해주지 않는다 — UNKNOWN, 클레임은 mapClaim이 읽는다")
	void canceled_mapsToUnknown() {
		ShippingStatus result = mapper.mapStatus(Map.of("status", "CANCELED"));
		assertThat(result).isEqualTo(ShippingStatus.UNKNOWN);
	}

	@Test
	@DisplayName("D-270: 미결제취소(CANCELED_BY_NOPAYMENT)도 배송 단계는 UNKNOWN이다")
	void canceledByNoPayment_mapsToUnknown() {
		ShippingStatus result = mapper.mapStatus(Map.of("status", "CANCELED_BY_NOPAYMENT"));
		assertThat(result).isEqualTo(ShippingStatus.UNKNOWN);
	}

	@Test
	@DisplayName("D-270: 반품(RETURNED)도 배송 단계는 UNKNOWN이다")
	void returned_mapsToUnknown() {
		ShippingStatus result = mapper.mapStatus(Map.of("status", "RETURNED"));
		assertThat(result).isEqualTo(ShippingStatus.UNKNOWN);
	}

	@Test
	@DisplayName("D-270: 교환(EXCHANGED)도 배송 단계는 UNKNOWN이다")
	void exchanged_mapsToUnknown() {
		ShippingStatus result = mapper.mapStatus(Map.of("status", "EXCHANGED"));
		assertThat(result).isEqualTo(ShippingStatus.UNKNOWN);
	}

	@Test
	@DisplayName("D-270: 구매확정(PURCHASE_DECIDED)은 CONFIRMED다 — 배송완료로 뭉개지 않는다")
	void purchaseDecided_mapsToConfirmed() {
		ShippingStatus result = mapper.mapStatus(Map.of("status", "PURCHASE_DECIDED"));
		assertThat(result).isEqualTo(ShippingStatus.CONFIRMED);
	}
}
