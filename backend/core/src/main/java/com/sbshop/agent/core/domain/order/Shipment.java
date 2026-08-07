package com.sbshop.agent.core.domain.order;

import java.sql.Types;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 하나의 배송 — 물리적으로 함께 나가는 단위. 송장 1개가 곧 Shipment 1개다.
 *
 * <p>네 마켓이 모두 주문과 상품 사이에 배송 계층을 갖는다(2026-08-05 API 문서 확인):
 * 11번가 {@code dlvNo}(+{@code bndlDlvSeq}) · 쿠팡 {@code shipmentBoxId} ·
 * N스토어 {@code packageNumber} · Cafe24 {@code shipments} 리소스. 역할이 같으므로
 * {@code marketShipmentNo} 한 컬럼으로 흡수한다.
 *
 * <p>배송상태({@code deliveryStatus})는 <b>배송 자체의 상태</b>(집화·배송중·배송완료)다.
 * 주문상품의 진행상태는 라인아이템에 남는다 — 같은 주문에서도 상품마다 갈리기 때문이다
 * (11번가 20260731088778989: 순번 1 결제완료 / 순번 2 발송완료).
 * 마켓이 배송상태를 주지 않으면 비운다. 마켓마다 코드계가 달라 enum으로 묶지 않았다.
 */
@Entity
@Table(name = "sb_shipment",
	uniqueConstraints = @UniqueConstraint(
		name = "uk_shipment_order_market_no",
		columnNames = {"order_id", "market_shipment_no"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment extends BaseEntity {

	/** 주문 ID (sb_order 참조값) */
	@Column(name = "order_id", nullable = false)
	private Long orderId;

	/** 마켓 배송 식별자 (dlvNo / shipmentBoxId / packageNumber / shipping_code) */
	@Column(name = "market_shipment_no", length = 100, nullable = false)
	private String marketShipmentNo;

	@Column(name = "tracking_no", length = 100)
	private String trackingNo;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "shipping_carrier", length = 30)
	private ShippingCarrier shippingCarrier;

	/** 마켓이 주는 배송 자체의 상태. 코드계가 마켓마다 달라 원문 문자열로 보관한다. */
	@Column(name = "delivery_status", length = 30)
	private String deliveryStatus;

	/** 마켓이 이 송장을 갖고 있는가 (D-129 — 우리가 보내 성공했거나, 마켓이 알려줬거나) */
	@Column(name = "tracking_sent_to_market")
	private Boolean trackingSentToMarket;

	/** 발송처리일 */
	@Column(name = "shipped_at")
	private LocalDateTime shippedAt;

	/**
	 * <b>마켓이 알고 있는 송장.</b> 동기화가 매번 갱신한다 — {@link #trackingNo}(실제 송장)와 다른
	 * 값일 수 있고, 그 <b>불일치가 곧 "마켓 미반영"</b>이다.
	 *
	 * <p>2026-08-07 이전에는 이 구분이 없어 마켓의 가송장이 이메일이 준 진짜 송장을 덮었다.
	 * 되돌아가면 두 값이 같아져 미반영 배지도 꺼지고, 화면·엑셀·고객 응대가 가송장을 진짜처럼 안내했다.
	 */
	@Column(name = "market_tracking_no", length = 100)
	private String marketTrackingNo;

	/**
	 * 마켓이 송장 반영을 <b>영구 거부</b>해 사람이 판매자센터에서 직접 고쳐야 하는 상태.
	 *
	 * <p>네이버는 발송된 주문의 송장 수정 API를 제공하지 않는다(커머스API 공식 답변 2건 + 라이브 시험).
	 * 재시도로는 해결되지 않으므로 사람에게 넘긴다. 사람이 고쳐 두 값이 같아지면 스스로 꺼진다.
	 */
	@Column(name = "manual_fix_required")
	private Boolean manualFixRequired;

	@Builder
	public Shipment(Long orderId, String marketShipmentNo, String trackingNo,
		ShippingCarrier shippingCarrier, String deliveryStatus,
		Boolean trackingSentToMarket, LocalDateTime shippedAt, String marketTrackingNo) {
		this.marketTrackingNo = marketTrackingNo;
		this.orderId = orderId;
		this.marketShipmentNo = marketShipmentNo;
		this.trackingNo = trackingNo;
		this.shippingCarrier = shippingCarrier;
		this.deliveryStatus = deliveryStatus;
		this.trackingSentToMarket = trackingSentToMarket;
		this.shippedAt = shippedAt;
	}

	/**
	 * 송장 정보를 갱신한다. <b>null 인자는 "판단 없음"이라 기존 값을 유지한다.</b>
	 *
	 * <p>마켓이 이번 응답에서 송장을 주지 않았다는 것과 "송장이 없다"는 것은 다르다.
	 * 빈 값·자리표시자가 실송장을 덮어써 배송정보가 유실된 이력이 있다(D-119/D-120).
	 * 실값 여부 판정은 호출자가 {@code ShippingData.isMeaningfulTracking}으로 하고,
	 * 이 메서드는 넘어온 값만 반영한다.
	 */
	public void applyTracking(String trackingNo, ShippingCarrier carrier, Boolean sentToMarket) {
		if (trackingNo != null) {
			this.trackingNo = trackingNo;
		}
		if (carrier != null) {
			this.shippingCarrier = carrier;
		}
		if (sentToMarket != null) {
			this.trackingSentToMarket = sentToMarket;
		}
	}

	public void applyDeliveryStatus(String deliveryStatus) {
		if (deliveryStatus != null) {
			this.deliveryStatus = deliveryStatus;
		}
	}

	public void applyShippedAt(LocalDateTime shippedAt) {
		if (shippedAt != null) {
			this.shippedAt = shippedAt;
		}
	}

	/**
	 * 마켓이 알고 있는 송장을 기록한다. {@code null}은 "이번 응답이 알려주지 않았다"이므로 무시한다.
	 *
	 * <p>기록된 값이 우리가 아는 실제 송장과 같아지면 <b>수동수정 표시를 스스로 끈다</b> —
	 * 사람이 판매자센터에서 고쳤다는 뜻이다. 완료 체크 버튼을 두지 않는 이유이기도 하다:
	 * 사람이 "했다"고 누르는 대신 마켓이 실제로 그렇게 됐는지를 보고 끈다.
	 */
	public void applyMarketTracking(String marketTrackingNo) {
		if (marketTrackingNo == null) {
			return;
		}
		this.marketTrackingNo = marketTrackingNo;
		if (marketTrackingNo.equals(this.trackingNo)) {
			this.manualFixRequired = Boolean.FALSE;
		}
	}

	/** 마켓이 영구 거부했다 — 사람이 판매자센터에서 직접 고쳐야 한다. */
	public void markManualFixRequired() {
		this.manualFixRequired = Boolean.TRUE;
	}

	/** 우리가 아는 실제 송장이 이미 있는가 — 있으면 마켓 값이 이를 덮지 않는다. */
	public boolean hasOwnTracking() {
		return trackingNo != null && !trackingNo.isBlank();
	}

	public boolean isManualFixRequired() {
		return Boolean.TRUE.equals(manualFixRequired);
	}

	/** 마켓이 아는 값과 실제 송장이 다른가 = 마켓 미반영. */
	public boolean isMarketOutOfSync() {
		return hasOwnTracking() && !trackingNo.equals(marketTrackingNo);
	}
}
