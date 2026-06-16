package com.sbshop.agent.core.domain.order.vo;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import java.sql.Types;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShippingData {
	/** 운송장 번호 (택배사 송장번호) */
	@Column(name = "tracking_no", length = 100)
	private String trackingNo;

	/** 유니패스(관세청) 통관 신고 완료 여부 */
	@Column(name = "is_unipass_done")
	private Boolean isUnipassDone;

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

	@Builder(toBuilder = true)
	public ShippingData(String trackingNo, Boolean isUnipassDone, ShippingStatus shippingStatus,
		ShippingCarrier shippingCarrier, Boolean trackingSentToMarket) {
		this.trackingNo = trackingNo;
		this.isUnipassDone = isUnipassDone;
		this.shippingStatus = shippingStatus;
		this.shippingCarrier = shippingCarrier;
		this.trackingSentToMarket = trackingSentToMarket;
	}
}
