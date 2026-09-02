package com.sbshop.agent.core.application.order.mapper;

import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

		if (source != null) {
			return mapBySource(source);
		}

		return ShippingStatus.UNKNOWN;
	}

	/**
	 * 배송 단계만 돌려준다. 클레임 이름(반품·교환·취소 계열)은 배송 단계를 말해주지 않으므로
	 * {@code UNKNOWN} 이다 — 클레임은 {@link #mapClaim} / {@link #mapClaimByStatusName} 이 읽는다(D-270).
	 */
	public ShippingStatus mapProductOrderStatus(String ordPrdStatNm) {
		if (ordPrdStatNm == null || ordPrdStatNm.isBlank()) {
			return ShippingStatus.UNKNOWN;
		}
		String name = ordPrdStatNm.trim();

		if (name.contains("구매확정")) {
			return ShippingStatus.CONFIRMED;
		}
		if (name.contains("배송완료")) {
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

		log.warn("11번가 상품주문 상태명 미매핑(또는 클레임 이름): '{}' → UNKNOWN (배송 단계를 덮지 않는다)", ordPrdStatNm);
		return ShippingStatus.UNKNOWN;
	}

	/**
	 * clmStat 코드 단위 클레임 매핑. {@code ordPrdStat}(배송 단계)과 별개 필드다(D-270).
	 *
	 * <p>취소(cancel) 코드는 문서에서 확인되지 않아 여기 담지 않는다 — {@link #mapClaimByStatusName}
	 * 가 상태명으로 취소를 대신 판정한다.
	 */
	public ClaimData mapClaim(String clmStat) {
		if (clmStat == null || clmStat.isBlank()) {
			return ClaimData.builder().build();
		}
		return toClaimData(byCode(clmStat.trim()), clmStat.trim());
	}

	/**
	 * clmStat 코드가 아직 실려오지 않는 응답(orderlistalladdr)을 위한 다리다 — {@code ordPrdStatNm}
	 * 텍스트를 clmStat 라벨과 정확히 대조한다(부분일치 아님 — D-270이 고친 결함은 접두어로 뭉개던 것이다).
	 * clmStat 코드가 실제로 실리는 API가 붙으면 {@link #mapClaim(String)} 로 옮겨간다.
	 */
	public ClaimData mapClaimByStatusName(String ordPrdStatNm) {
		if (ordPrdStatNm == null || ordPrdStatNm.isBlank()) {
			return ClaimData.builder().build();
		}
		String name = ordPrdStatNm.trim();
		return toClaimData(byName(name), name);
	}

	private static ClaimData toClaimData(ClaimEntry entry, String rawCode) {
		if (entry == null) {
			return ClaimData.builder().build();
		}
		return ClaimData.builder()
			.claimType(entry.type())
			.claimStage(entry.stage())
			.claimRawCode(rawCode)
			.build();
	}

	private record ClaimEntry(String code, String name, ClaimType type, ClaimStage stage) {
	}

	private static final ClaimEntry[] CLAIM_TABLE = {
		new ClaimEntry("103", "재결제대기중", ClaimType.RETURN, ClaimStage.IN_PROGRESS),
		new ClaimEntry("104", "반품보류", ClaimType.RETURN, ClaimStage.IN_PROGRESS),
		new ClaimEntry("105", "반품신청", ClaimType.RETURN, ClaimStage.REQUESTED),
		new ClaimEntry("106", "반품완료", ClaimType.RETURN, ClaimStage.DONE),
		new ClaimEntry("107", "반품거부", ClaimType.RETURN, ClaimStage.REJECTED),
		new ClaimEntry("108", "반품철회", ClaimType.RETURN, ClaimStage.REJECTED),
		new ClaimEntry("109", "반품완료보류", ClaimType.RETURN, ClaimStage.IN_PROGRESS),
		new ClaimEntry("201", "교환신청", ClaimType.EXCHANGE, ClaimStage.REQUESTED),
		new ClaimEntry("212", "교환승인", ClaimType.EXCHANGE, ClaimStage.IN_PROGRESS),
		new ClaimEntry("214", "교환보류", ClaimType.EXCHANGE, ClaimStage.IN_PROGRESS),
		new ClaimEntry("221", "교환발송완료", ClaimType.EXCHANGE, ClaimStage.DONE),
		new ClaimEntry("232", "교환거부", ClaimType.EXCHANGE, ClaimStage.REJECTED),
		new ClaimEntry("233", "교환철회", ClaimType.EXCHANGE, ClaimStage.REJECTED),
		new ClaimEntry("301", "재배송접수", ClaimType.EXCHANGE, ClaimStage.IN_PROGRESS),
		new ClaimEntry(null, "취소신청", ClaimType.CANCEL, ClaimStage.REQUESTED),
		new ClaimEntry(null, "취소완료", ClaimType.CANCEL, ClaimStage.DONE),
	};

	private static ClaimEntry byCode(String code) {
		for (ClaimEntry entry : CLAIM_TABLE) {
			if (code.equals(entry.code())) {
				return entry;
			}
		}
		return null;
	}

	private static ClaimEntry byName(String name) {
		for (ClaimEntry entry : CLAIM_TABLE) {
			if (name.equals(entry.name())) {
				return entry;
			}
		}
		return null;
	}

	private ShippingStatus mapBySource(String source) {
		return switch (source.toLowerCase()) {
			case "complete" -> ShippingStatus.NEW;
			case "packaging" -> ShippingStatus.PREPARING;
			case "shipping" -> ShippingStatus.SHIPPED;
			case "dlvcompleted" -> ShippingStatus.DELIVERED;
			default -> {
				log.warn("알 수 없는 11번가 주문 상태 소스: {} → UNKNOWN으로 매핑", source);
				yield ShippingStatus.UNKNOWN;
			}
		};
	}
}
