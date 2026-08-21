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
@Table(name = "sb_market_registration", uniqueConstraints = @UniqueConstraint(name = "uk_market_registration_product_market", columnNames = {
	"product_id",
	"market_type"}))
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

	@JsonRawValue
	public String getMarketIdentifiers() {
		return isValidJson(marketIdentifiers) ? marketIdentifiers : "{}";
	}

	@JsonRawValue
	public String getMarketDetailedInfo() {
		return isValidJson(marketDetailedInfo) ? marketDetailedInfo : "{}";
	}

	public void markSynced() {
		this.isSynced = true;
		this.lastSyncedAt = LocalDateTime.now();
	}

	public void markSyncFailed() {
		this.isSynced = false;
	}

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

	public String extractDeleteCode() {
		if (marketType == MarketType.COUPANG) {
			if (marketIdentifiers == null || marketIdentifiers.isEmpty()) {
				return null;
			}
			try {
				String v = MAPPER.readTree(marketIdentifiers).path("sellerProductId").asText(null);
				return (v != null && !v.isEmpty()) ? v : null;
			} catch (Exception e) {
				log.warn("sellerProductId 파싱 실패: productId={}, error={}", productId, e.getMessage());
				return null;
			}
		}
		return extractMarketCode();
	}

	public void updateMarketIdentifiers(String marketIdentifiers) {
		this.marketIdentifiers = marketIdentifiers;
	}

	public boolean hasIdentifiers() {
		if (marketIdentifiers == null || marketIdentifiers.isBlank()) {
			return false;
		}
		try {
			JsonNode node = MAPPER.readTree(marketIdentifiers);
			return node.isObject() && node.fieldNames().hasNext();
		} catch (Exception e) {
			return false;
		}
	}

	public String identifier(String key) {
		if (marketIdentifiers == null || marketIdentifiers.isEmpty()) {
			return null;
		}
		try {
			String v = MAPPER.readTree(marketIdentifiers).path(key).asText(null);
			return (v != null && !v.isEmpty()) ? v : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static final String SMARTSTORE_SLUG = "shouldbe_shop";

	public String buildMarketUrl() {
		switch (marketType) {
			case COUPANG: {
				String productId = identifier("productId");
				if (productId == null) {
					return null;
				}
				String vendorItemId = identifier("vendorItemId");
				return vendorItemId != null
					? "https://www.coupang.com/vp/products/" + productId + "?vendorItemId=" + vendorItemId
					: "https://www.coupang.com/vp/products/" + productId;
			}
			case SMART_STORE: {
				String ch = identifier("channelProductNo");
				return ch != null ? "https://smartstore.naver.com/" + SMARTSTORE_SLUG + "/products/" + ch : null;
			}
			case ELEVEN_STREET: {
				String prd = identifier("prdNo");
				return prd != null ? "https://www.11st.co.kr/products/" + prd : null;
			}
			default:
				return null;
		}
	}

	public String buildGmarketUrl() {
		String goods = identifier("gmarket_goodsNo");
		return goods != null ? "http://item.gmarket.co.kr/Item?goodscode=" + goods : null;
	}

	public String buildAuctionUrl() {
		String item = identifier("auction_goodsNo");
		return item != null ? "http://itempage3.auction.co.kr/DetailView.aspx?ItemNo=" + item : null;
	}

	public void enrichIdentifier(String key, String value) {
		if (key == null || key.isEmpty() || value == null || value.isEmpty()) {
			return;
		}
		try {
			JsonNode existing = isValidJson(marketIdentifiers) ? MAPPER.readTree(marketIdentifiers) : null;
			com.fasterxml.jackson.databind.node.ObjectNode node = (existing != null && existing.isObject())
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
}
