package com.sbshop.agent.core.application.order.mapper;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 11번가 주문 상태를 내부 배송 상태로 매핑하는 구현체
 */
@Slf4j
@Component
public class ElevenstStatusMapper implements MarketStatusMapper {

	@Override
	public MarketType getMarketType() {
		return MarketType.ELEVEN_STREET;
	}

	@Override
	public ShippingStatus mapStatus(Map<String, String> marketStatuses) {
		String source = marketStatuses.get("source");

		// API 소스에 따라 상태 매핑
		if (source != null) {
			return mapBySource(source);
		}

		return ShippingStatus.UNKNOWN;
	}

	/**
	 * 주문상세(claimservice/orderlistalladdr)의 ordPrdStatNm으로 클레임(취소·반품·교환) 상태를 매핑한다. (D-099)
	 *
	 * <p>11번가는 클레임 목록 조회 REST가 없어(라이브 확정) 4개 진행상태 목록만 조회하므로, 목록에서 사라진
	 * 주문의 실제 상태는 단건 상세조회로만 알 수 있다. 상세 응답의 ordPrdStatNm은 "구매확정"·"취소완료"·
	 * "반품완료"·"교환완료" 등 상태명을 담는다. 숫자 코드(ordPrdStat)는 코드계가 문서마다 달라 신뢰가 낮으므로
	 * 상태명 부분일치로 매핑한다.
	 *
	 * @return 클레임 상태(CANCELED/RETURNED/EXCHANGED). 정상 진행/구매확정 등 클레임이 아니면 {@code null}.
	 */
	public ShippingStatus mapClaimStatus(String ordPrdStat, String ordPrdStatNm) {
		if (ordPrdStatNm == null || ordPrdStatNm.isBlank()) {
			return null;
		}
		if (ordPrdStatNm.contains("취소")) {
			return ShippingStatus.CANCELED;
		}
		if (ordPrdStatNm.contains("반품")) {
			return ShippingStatus.RETURNED;
		}
		if (ordPrdStatNm.contains("교환")) {
			return ShippingStatus.EXCHANGED;
		}
		return null;
	}

	/**
	 * API 호출 소스에 따른 상태 매핑
	 */
	private ShippingStatus mapBySource(String source) {
		return switch (source.toLowerCase()) {
			case "complete" -> ShippingStatus.NEW; // 결제완료
			case "packaging" -> ShippingStatus.PREPARING; // 배송준비중
			case "shipping" -> ShippingStatus.SHIPPED; // 배송중
			case "dlvcompleted" -> ShippingStatus.DELIVERED; // 배송완료
			default -> {
				log.warn("알 수 없는 11번가 주문 상태 소스: {} → UNKNOWN으로 매핑", source);
				yield ShippingStatus.UNKNOWN;
			}
		};
	}
}
