package com.sbshop.agent.core.domain.order;

import java.sql.Types;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.TrackingSource;

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

@Entity
@Table(name = "sb_shipment", uniqueConstraints = @UniqueConstraint(name = "uk_shipment_order_market_no", columnNames = {
	"order_id", "market_shipment_no"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Shipment extends BaseEntity {
	@Column(name = "order_id", nullable = false)
	private Long orderId;

	@Column(name = "market_shipment_no", length = 100, nullable = false)
	private String marketShipmentNo;

	@Column(name = "tracking_no", length = 100)
	private String trackingNo;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "shipping_carrier", length = 30)
	private ShippingCarrier shippingCarrier;

	@Column(name = "delivery_status", length = 30)
	private String deliveryStatus;

	@Column(name = "tracking_sent_to_market")
	private Boolean trackingSentToMarket;

	@Column(name = "shipped_at")
	private LocalDateTime shippedAt;

	@Column(name = "market_tracking_no", length = 100)
	private String marketTrackingNo;

	@Column(name = "manual_fix_required")
	private Boolean manualFixRequired;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "tracking_source", length = 20)
	private TrackingSource trackingSource;

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

	public void applyMarketTracking(String marketTrackingNo) {
		if (marketTrackingNo == null) {
			return;
		}
		this.marketTrackingNo = marketTrackingNo;
		if (marketTrackingNo.equals(this.trackingNo)) {
			this.manualFixRequired = Boolean.FALSE;
		}
	}

	public void applyTrackingSource(TrackingSource source) {
		if (source == null) {
			return;
		}
		this.trackingSource = source;
	}

	public void markManualFixRequired() {
		this.manualFixRequired = Boolean.TRUE;
	}

	public boolean hasOwnTracking() {
		return trackingNo != null && !trackingNo.isBlank();
	}

	public boolean isManualFixRequired() {
		return Boolean.TRUE.equals(manualFixRequired);
	}

	public boolean isMarketOutOfSync() {
		return hasOwnTracking() && !trackingNo.equals(marketTrackingNo);
	}
}
