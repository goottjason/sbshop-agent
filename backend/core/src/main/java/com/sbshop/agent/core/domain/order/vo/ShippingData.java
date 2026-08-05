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

	/**
	 * <b>마켓이 이 송장을 갖고 있는가.</b>
	 *
	 * <p>D-129 이전에는 "우리 시스템이 전송해 성공했는가"만 뜻해서, 마켓에서 동기화로 들어온 송장은
	 * 마켓이 명백히 보유함에도 null로 남았다. 그 결과 "저장됨 · 마켓 미반영"을 화면에서 구분할 수
	 * 없었다(정상 건까지 미반영으로 보여 경고가 무의미해짐). 지금은 두 경로 모두 true를 남긴다 —
	 * 우리가 보내 성공했거나, 마켓이 실송장을 알려줬거나.
	 */
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

	/**
	 * D-129: 마켓이 준 송장번호를 근거로 <b>마켓이 그 송장을 보유하는지</b>를 판정한다.
	 *
	 * <p>동기화가 마켓 값을 채택한다는 것은 곧 마켓이 그 송장을 갖고 있다는 뜻이므로 {@code TRUE}를
	 * 돌려주고, 자리표시자·빈값이라 채택하지 않는 경우에는 {@code null}을 돌려준다 — null은
	 * "판단 없음"이라 {@code ShippingUpdateCommand}가 기존 값을 덮지 않는다(마킹을 되돌리지 않는다).
	 *
	 * <p>판정 기준은 반드시 {@link #isMeaningfulTracking}과 일치해야 한다. 둘이 갈리면
	 * "송장은 채택했는데 마킹은 안 된"(또는 그 반대) 어긋난 상태가 생긴다.
	 *
	 * @return 마켓 보유가 확인되면 {@code TRUE}, 판단할 수 없으면 {@code null}(기존 값 유지)
	 */
	public static Boolean marketOwnsTracking(String marketTrackingNo) {
		return isMeaningfulTracking(marketTrackingNo) ? Boolean.TRUE : null;
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
