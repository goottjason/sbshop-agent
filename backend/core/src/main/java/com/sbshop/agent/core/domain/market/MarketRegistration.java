package com.sbshop.agent.core.domain.market;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.sql.Types;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.JdbcTypeCode;

@Slf4j
@Entity
@Table(
	name = "sb_market_registration",
	// F-PSRC-13 / R3: 같은 상품·마켓의 등록행 중복을 DB 레벨에서 하드 차단(동시 재게시 경쟁 방지).
	// savePending의 findByProductIdAndMarketType 재사용은 순차 재호출에만 멱등이므로,
	// 동시성 안전을 위해 유니크 제약을 둔다.
	uniqueConstraints = @UniqueConstraint(
		name = "uk_market_registration_product_market",
		columnNames = {"product_id", "market_type"}
	)
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketRegistration extends BaseEntity {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Column(name = "product_id", nullable = false)
	private Long productId;

	@Column(name = "sb_product_id")
	private Long sbProductId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "market_type", length = 50, nullable = false)
	private MarketType marketType;

	@Column(name = "market_product_name", length = 255)
	private String marketProductName;

	@Getter(AccessLevel.NONE)
	@Column(name = "market_identifiers", columnDefinition = "TEXT")
	private String marketIdentifiers;

	@Getter(AccessLevel.NONE)
	@Column(name = "market_detailed_info", columnDefinition = "TEXT")
	private String marketDetailedInfo;

	@Column(name = "is_synced", nullable = false)
	private Boolean isSynced = false;

	@Column(name = "last_synced_at")
	private LocalDateTime lastSyncedAt;

	@JsonRawValue
	public String getMarketIdentifiers() {
		return isValidJson(marketIdentifiers) ? marketIdentifiers : "{}";
	}

	@JsonRawValue
	public String getMarketDetailedInfo() {
		return isValidJson(marketDetailedInfo) ? marketDetailedInfo : "{}";
	}

	private boolean isValidJson(String value) {
		if (value == null || value.isEmpty())
			return false;
		try {
			MAPPER.readTree(value);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Builder
	public MarketRegistration(Long productId, Long sbProductId, MarketType marketType, String marketProductName,
		String marketIdentifiers, String marketDetailedInfo) {
		this.productId = productId;
		this.sbProductId = sbProductId;
		this.marketType = marketType;
		this.marketProductName = marketProductName;
		this.marketIdentifiers = marketIdentifiers;
		this.marketDetailedInfo = marketDetailedInfo;
	}

	public void markSynced() {
		this.isSynced = true;
		this.lastSyncedAt = LocalDateTime.now();
	}

	/**
	 * 동기화 실패 표시. isSynced=false로 내려 "직전 동기화 미성공"을 나타낸다 —
	 * 변경없음이어도 다음 배치에서 재시도되도록 하는 신호(Cafe24 변경감지 스킵과 연동).
	 */
	public void markSyncFailed() {
		this.isSynced = false;
	}

	/**
	 * marketIdentifiers JSON에서 vendorItemId 추출
	 */
	public String extractVendorItemId() {
		if (marketIdentifiers == null || marketIdentifiers.isEmpty()) {
			return null;
		}
		try {
			JsonNode node = MAPPER.readTree(marketIdentifiers);
			String vendorItemId = node.path("vendorItemId").asText(null);
			return (vendorItemId != null && !vendorItemId.isEmpty()) ? vendorItemId : null;
		} catch (Exception e) {
			log.warn("vendorItemId 파싱 실패: productId={}, error={}", productId, e.getMessage());
			return null;
		}
	}

	/**
	 * (D-052) marketIdentifiers JSON에서 "마켓별 상품코드"를 추출한다.
	 * extractVendorItemId()는 쿠팡 전용 키(vendorItemId)만 읽어 스토어/11번가/카페24는
	 * 항상 null→productId 폴백('미확인')이 됐다. 각 마켓 클라이언트가 저장하는 실제 키로 분기한다.
	 *   COUPANG       : vendorItemId → sellerProductId
	 *   SMART_STORE   : originProductNo → channelProductNo
	 *   ELEVEN_STREET : elevenstId → prdNo
	 *   CAFE24        : product_no → product_code
	 *   GMARKET/AUCTION(ESM+) : goodsNo → itemNo → goodsCode
	 */
	public String extractMarketCode() {
		if (marketIdentifiers == null || marketIdentifiers.isEmpty()) {
			return null;
		}
		try {
			JsonNode node = MAPPER.readTree(marketIdentifiers);
			String[] keys;
			switch (marketType) {
				case COUPANG:
					keys = new String[] {"vendorItemId", "sellerProductId"};
					break;
				case SMART_STORE:
					keys = new String[] {"originProductNo", "channelProductNo"};
					break;
				case ELEVEN_STREET:
					keys = new String[] {"elevenstId", "prdNo"};
					break;
				case CAFE24:
					keys = new String[] {"product_no", "product_code"};
					break;
				case GMARKET:
				case AUCTION:
					keys = new String[] {"goodsNo", "itemNo", "goodsCode"};
					break;
				default:
					keys = new String[] {};
					break;
			}
			for (String k : keys) {
				String v = node.path(k).asText(null);
				if (v != null && !v.isEmpty()) {
					return v;
				}
			}
			return null;
		} catch (Exception e) {
			log.warn("marketCode 파싱 실패: productId={}, marketType={}, error={}",
				productId, marketType, e.getMessage());
			return null;
		}
	}

	public void updateMarketIdentifiers(String marketIdentifiers) {
		this.marketIdentifiers = marketIdentifiers;
	}

	/**
	 * 기존 marketIdentifiers JSON을 보존하며 단일 키를 병합한다.
	 * (D-046) 발행 시 sellerProductId만 저장되고 vendorItemId를 채우는 write-path가
	 * 없던 구조적 공백을 메우기 위한 보강 진입점. 값이 비면 no-op.
	 */
	public void enrichIdentifier(String key, String value) {
		if (key == null || key.isEmpty() || value == null || value.isEmpty()) {
			return;
		}
		try {
			JsonNode existing = isValidJson(marketIdentifiers) ? MAPPER.readTree(marketIdentifiers) : null;
			com.fasterxml.jackson.databind.node.ObjectNode node =
				(existing != null && existing.isObject())
					? (com.fasterxml.jackson.databind.node.ObjectNode)existing
					: MAPPER.createObjectNode();
			node.put(key, value);
			this.marketIdentifiers = MAPPER.writeValueAsString(node);
		} catch (Exception e) {
			log.warn("marketIdentifiers 보강 실패: productId={}, key={}, error={}", productId, key, e.getMessage());
		}
	}

	public void updateMarketDetailedInfo(String marketDetailedInfo) {
		this.marketDetailedInfo = marketDetailedInfo;
	}

	public void assignSbProductId(Long sbProductId) {
		this.sbProductId = sbProductId;
	}
}
