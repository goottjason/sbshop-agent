package com.sbshop.agent.core.domain.order.vo;

import java.sql.Types;

import org.hibernate.annotations.JdbcTypeCode;

import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShippingData {
	@Column(name = "tracking_no", length = 100)
	private String trackingNo;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "shipping_status", length = 30)
	private ShippingStatus shippingStatus;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "shipping_carrier", length = 30)
	private ShippingCarrier shippingCarrier;

	@Column(name = "tracking_sent_to_market")
	private Boolean trackingSentToMarket;

	@Builder(toBuilder = true)
	public ShippingData(String trackingNo, ShippingStatus shippingStatus,
		ShippingCarrier shippingCarrier, Boolean trackingSentToMarket) {
		this.trackingNo = trackingNo;
		this.shippingStatus = shippingStatus;
		this.shippingCarrier = shippingCarrier;
		this.trackingSentToMarket = trackingSentToMarket;
	}

	public static boolean isMeaningfulTracking(String trackingNo) {
		if (trackingNo == null) {
			return false;
		}
		String normalized = trackingNo.replaceAll("[\\s-]", "");
		if (normalized.isEmpty()) {
			return false;
		}
		return !normalized.chars().allMatch(ch -> ch == '0');
	}

	public static Boolean marketOwnsTracking(String marketTrackingNo) {
		return isMeaningfulTracking(marketTrackingNo) ? Boolean.TRUE : null;
	}
}
