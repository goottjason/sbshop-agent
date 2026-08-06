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
	 * 2단계: 상품주문 상태명({@code ordPrdStatNm})을 진행상태로 매핑한다.
	 *
	 * <p>종전에는 "어느 목록에서 왔는가"로 상태를 정했다({@link #mapBySource}). 그 구조가 D-126을
	 * 낳았고 D-130에서 원인이 확정됐다 — <b>목록 행은 상품주문 단위</b>라서 한 주문의 순번 1과
	 * 순번 2가 서로 다른 목록에 나타난다. 목록 소속은 그 주문의 상태가 아니다.
	 * {@code claimservice/orderlistall}은 행마다 상태명을 직접 주므로 추론이 사라진다.
	 *
	 * <p><b>부분일치 순서가 계약이다.</b> "배송완료"·"배송준비중"은 모두 "배송"을 포함하므로
	 * 좁은 패턴을 먼저 검사한다. 클레임(취소·반품·교환)을 가장 먼저 보는 이유는 "취소신청"처럼
	 * 진행 중 상태와 섞인 이름이 있어서다 — 클레임이 걸린 상품주문은 진행 축으로 읽지 않는다.
	 *
	 * <p>모르는 상태명은 {@link ShippingStatus#UNKNOWN}이다. {@code NEW}로 폴백하지 않는다 —
	 * 새 상태명이 등장했을 때 배송중 주문이 신규로 되돌아가는 것이 가장 나쁜 실패다.
	 */
	public ShippingStatus mapProductOrderStatus(String ordPrdStatNm) {
		if (ordPrdStatNm == null || ordPrdStatNm.isBlank()) {
			return ShippingStatus.UNKNOWN;
		}
		String name = ordPrdStatNm.trim();

		// 클레임 우선 — "취소신청"처럼 진행 중 이름과 섞여 있어도 클레임으로 읽는다.
		if (name.contains("취소")) {
			return ShippingStatus.CANCELED;
		}
		if (name.contains("반품")) {
			return ShippingStatus.RETURNED;
		}
		if (name.contains("교환")) {
			return ShippingStatus.EXCHANGED;
		}

		// 좁은 패턴부터 — "배송완료"·"배송준비중"이 "배송중"으로 오독되지 않게.
		if (name.contains("구매확정") || name.contains("배송완료")) {
			return ShippingStatus.DELIVERED;
		}
		if (name.contains("배송준비중")) {
			return ShippingStatus.PREPARING;
		}
		if (name.contains("발송완료") || name.contains("배송중")) {
			return ShippingStatus.SHIPPED;
		}
		if (name.contains("결제완료")) {
			return ShippingStatus.NEW;
		}

		log.warn("11번가 상품주문 상태명 미매핑: '{}' → UNKNOWN (상태를 덮지 않는다)", ordPrdStatNm);
		return ShippingStatus.UNKNOWN;
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
