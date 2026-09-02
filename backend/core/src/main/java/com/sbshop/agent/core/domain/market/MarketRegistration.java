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

	public static final String GMARKET_IDENTIFIER_KEY = "gmarket_goodsNo";
	public static final String AUCTION_IDENTIFIER_KEY = "auction_goodsNo";

	public static final String COUPANG_LOOKUP_KEY = "sellerProductId";
	public static final String COUPANG_VENDOR_ITEM_KEY = "vendorItemId";
	public static final String SMART_STORE_LOOKUP_KEY = "originProductNo";
	public static final String ELEVEN_STREET_LOOKUP_KEY = "prdNo";
	public static final String ELEVEN_STREET_LOOKUP_FALLBACK_KEY = "elevenstId";
	public static final String CAFE24_LOOKUP_KEY = "product_no";
	public static final String CAFE24_PRODUCT_CODE_KEY = "product_code";

	public static final String PREVIOUS_IDENTIFIERS_KEY = "previousIdentifiers";

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

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "unsync_reason", length = 32)
	private UnsyncReason unsyncReason;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "last_sync_error", length = 32)
	private SyncErrorType lastSyncError;

	/** 실패 원문. 분류만으로는 "심사중이라 못 지운다"와 "권한이 없다"를 구분할 수 없다. */
	@Column(name = "last_sync_error_message", length = 500)
	private String lastSyncErrorMessage;

	/** 마지막 실패 시각. 같은 오류가 반복되면 다른 필드가 안 바뀌어 갱신 흔적이 남지 않는다. */
	@Column(name = "last_sync_error_at")
	private LocalDateTime lastSyncErrorAt;

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
		this.unsyncReason = null;
		this.lastSyncError = null;
		this.lastSyncErrorMessage = null;
		this.lastSyncErrorAt = null;
	}

	public void confirmPresentOnMarket() {
		this.isSynced = true;
		this.unsyncReason = null;
	}

	public void recordSyncError(SyncErrorType errorType) {
		recordSyncError(errorType, null);
	}

	private static final int MAX_SYNC_ERROR_MESSAGE = 500;

	/**
	 * 실패 분류와 <b>원문 사유</b>를 함께 남긴다.
	 *
	 * <p>분류만으로는 조치를 정할 수 없다 — {@code BLOCKED_BY_MARKET} 이
	 * "심사중이라 삭제가 막혔다"(심사 끝나면 풀림)인지 "권한이 없다"(계정 문제)인지 구분되지 않는다.
	 */
	public void recordSyncError(SyncErrorType errorType, String message) {
		this.lastSyncError = errorType;
		this.lastSyncErrorAt = LocalDateTime.now();
		if (message == null || message.isBlank()) {
			return;
		}
		this.lastSyncErrorMessage = message.length() > MAX_SYNC_ERROR_MESSAGE
			? message.substring(0, MAX_SYNC_ERROR_MESSAGE) : message;
	}

	public void markAbsentFromMarket(UnsyncReason reason) {
		if (reason == null) {
			throw new IllegalArgumentException("부재 사유는 필수다 — is_synced=false 는 사유 없이 만들지 않는다");
		}
		this.isSynced = false;
		this.unsyncReason = reason;
	}

	public void replaceIdentifiersArchivingPrevious(String newIdentifiersJson) {
		if (!hasIdentifiers()) {
			this.marketIdentifiers = newIdentifiersJson;
			return;
		}
		try {
			JsonNode previous = MAPPER.readTree(marketIdentifiers);
			JsonNode incoming = isValidJson(newIdentifiersJson)
				? MAPPER.readTree(newIdentifiersJson)
				: MAPPER.createObjectNode();
			if (!incoming.isObject()) {
				this.marketIdentifiers = newIdentifiersJson;
				return;
			}
			com.fasterxml.jackson.databind.node.ObjectNode next = (com.fasterxml.jackson.databind.node.ObjectNode)incoming;
			com.fasterxml.jackson.databind.node.ArrayNode archive = (previous.has(PREVIOUS_IDENTIFIERS_KEY)
				&& previous.get(PREVIOUS_IDENTIFIERS_KEY).isArray())
					? ((com.fasterxml.jackson.databind.node.ArrayNode)previous.get(PREVIOUS_IDENTIFIERS_KEY)).deepCopy()
					: MAPPER.createArrayNode();
			com.fasterxml.jackson.databind.node.ObjectNode snapshot = ((com.fasterxml.jackson.databind.node.ObjectNode)previous)
				.deepCopy();
			snapshot.remove(PREVIOUS_IDENTIFIERS_KEY);
			snapshot.put("archivedAt", LocalDateTime.now().toString());
			archive.add(snapshot);
			next.set(PREVIOUS_IDENTIFIERS_KEY, archive);
			this.marketIdentifiers = MAPPER.writeValueAsString(next);
			log.info("[식별자보존] 이전 마켓 식별자를 보관했다: productId={}, market={}, previous={}",
				productId, marketType, snapshot);
		} catch (Exception e) {
			log.warn("[식별자보존] 실패 — 새 식별자로 대체한다: productId={}, error={}", productId, e.getMessage());
			this.marketIdentifiers = newIdentifiersJson;
		}
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

	public static String[] liveLookupKeys(MarketType marketType) {
		if (marketType == null) {
			return new String[] {};
		}
		switch (marketType) {
			case COUPANG:
				return new String[] {COUPANG_LOOKUP_KEY};
			case SMART_STORE:
				return new String[] {SMART_STORE_LOOKUP_KEY};
			case ELEVEN_STREET:
				return new String[] {ELEVEN_STREET_LOOKUP_KEY, ELEVEN_STREET_LOOKUP_FALLBACK_KEY};
			case CAFE24:
				return new String[] {CAFE24_LOOKUP_KEY};
			default:
				return new String[] {};
		}
	}

	public String extractLiveLookupId() {
		for (String key : liveLookupKeys(marketType)) {
			String value = identifier(key);
			if (value != null) {
				return value;
			}
		}
		return null;
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
		String goods = identifier(GMARKET_IDENTIFIER_KEY);
		return goods != null ? "http://item.gmarket.co.kr/Item?goodscode=" + goods : null;
	}

	public String buildAuctionUrl() {
		String item = identifier(AUCTION_IDENTIFIER_KEY);
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
