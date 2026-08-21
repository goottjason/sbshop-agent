package com.sbshop.agent.core.domain.sourcing;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.sql.Types;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "sb_market_draft")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketDraft extends BaseEntity {
	@Column(name = "draft_id", insertable = false, updatable = false)
	private Long draftId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "market_type", length = 30, nullable = false)
	private MarketType marketType;

	@Column(name = "product_name", length = 500)
	private String productName;

	@Column(name = "category_id", length = 50)
	private String categoryId;

	@Column(name = "category_path", length = 300)
	private String categoryPath;

	@Column(name = "sale_price", precision = 15, scale = 0)
	private BigDecimal salePrice;

	@Column(name = "channel_fee_rate", precision = 5, scale = 2)
	private BigDecimal channelFeeRate;

	@Column(name = "keywords", columnDefinition = "text")
	private String keywords;

	@Column(name = "notice_fields", columnDefinition = "text")
	private String noticeFields;

	@Column(name = "extra_fields", columnDefinition = "text")
	private String extraFields;

	@Column(name = "missing_fields", columnDefinition = "text")
	private String missingFields;

	@Column(name = "is_valid", nullable = false)
	private Boolean isValid = false;

	@Column(name = "enabled", nullable = false)
	private Boolean enabled = true;

	@Column(name = "publish_error", columnDefinition = "text")
	private String publishError;

	@Column(name = "market_identifiers", columnDefinition = "text")
	private String marketIdentifiers;

	@Builder
	private MarketDraft(MarketType marketType, String productName, String categoryId, String categoryPath,
		BigDecimal salePrice, BigDecimal channelFeeRate, String keywords, String noticeFields,
		String extraFields) {
		this.marketType = marketType;
		this.productName = productName;
		this.categoryId = categoryId;
		this.categoryPath = categoryPath;
		this.salePrice = salePrice;
		this.channelFeeRate = channelFeeRate;
		this.keywords = keywords;
		this.noticeFields = noticeFields;
		this.extraFields = extraFields;
		this.enabled = true;
		this.isValid = false;
	}

	public void update(String productName, String categoryId, String categoryPath,
		BigDecimal salePrice, String keywords, String noticeFields, String extraFields, Boolean enabled) {
		if (productName != null)
			this.productName = productName;
		if (categoryId != null)
			this.categoryId = categoryId;
		if (categoryPath != null)
			this.categoryPath = categoryPath;
		if (salePrice != null)
			this.salePrice = salePrice;
		if (keywords != null)
			this.keywords = keywords;
		if (noticeFields != null)
			this.noticeFields = noticeFields;
		if (extraFields != null)
			this.extraFields = extraFields;
		if (enabled != null)
			this.enabled = enabled;
	}

	public void applyValidation(String missingFieldsJson, boolean valid) {
		this.missingFields = missingFieldsJson;
		this.isValid = valid;
	}

	public void markPublished(String identifiersJson) {
		this.marketIdentifiers = identifiersJson;
		this.publishError = null;
	}

	public void markFailed(String error) {
		this.publishError = error;
	}

	public boolean isEnabled() {
		return Boolean.TRUE.equals(enabled);
	}

	public boolean isValid() {
		return Boolean.TRUE.equals(isValid);
	}
}
