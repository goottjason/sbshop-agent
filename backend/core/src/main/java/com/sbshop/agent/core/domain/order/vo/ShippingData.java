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
	/** 운송장 번호 (택배사 송장번호) */
	@Column(name = "tracking_no", length = 100)
	private String trackingNo;

	/** 실제 배송 상태 (상품준비중, 배송중, 배송완료 등) */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "shipping_status", length = 30)
	private ShippingStatus shippingStatus;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "shipping_carrier", length = 30)
	private ShippingCarrier shippingCarrier;

	/** 마켓플러스(쿠팡/스마트스토어) 송장 동기화 완료 여부 */
	@Column(name = "tracking_sent_to_market")
	private Boolean trackingSentToMarket;

	/**
	 * D-119: 마켓이 준 송장번호가 우리 값을 덮어쓸 만한 "실값"인지 판정한다.
	 * 마켓은 미발송 주문에 빈 문자열이나 전부 0인 자리표시자를 담아 주는 경우가 있고,
	 * 그 값으로 실제 송장을 덮으면 배송정보가 유실된다(D-107/108의 PII 가드와 같은 취지).
	 */
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

	@Builder(toBuilder = true)
	public ShippingData(String trackingNo, ShippingStatus shippingStatus,
		ShippingCarrier shippingCarrier, Boolean trackingSentToMarket) {
		this.trackingNo = trackingNo;
		this.shippingStatus = shippingStatus;
		this.shippingCarrier = shippingCarrier;
		this.trackingSentToMarket = trackingSentToMarket;
	}
}
