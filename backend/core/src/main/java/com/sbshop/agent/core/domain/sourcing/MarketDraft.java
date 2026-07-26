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

/**
 * 마켓 1곳에 등록할 초안 데이터.
 *
 * <p>상품명·카테고리·키워드·판매가는 마켓마다 다르다(글자수 제한, 카테고리 체계, 수수료율).
 * 그래서 공통 {@link ProductDraft} 아래에 마켓별로 하나씩 둔다.
 *
 * <p>{@code missingFields}는 <b>등록 전 필수필드 검사 결과</b>다. 비어 있지 않으면 등록을 막는다 —
 * 마켓 API가 400으로 거절하는 걸 사후에 보는 것보다, 검수 화면에서 무엇이 빠졌는지 먼저 보여주는 게 낫다.
 */
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

	/** 마켓 규칙(글자수·금지어)에 맞춰 생성한 상품명. */
	@Column(name = "product_name", length = 500)
	private String productName;

	/** 마켓 카테고리 식별자 (쿠팡 displayCategoryCode / 스토어 leafCategoryId / 11번가 dispCtgrNo / Cafe24 category_no). */
	@Column(name = "category_id", length = 50)
	private String categoryId;

	@Column(name = "category_path", length = 300)
	private String categoryPath;

	/** 마켓별 실수수료를 반영해 산정한 판매가(원). */
	@Column(name = "sale_price", precision = 15, scale = 0)
	private BigDecimal salePrice;

	/** 적용된 채널 수수료율(%) — 판매가가 왜 이 값인지 설명하기 위해 남긴다. */
	@Column(name = "channel_fee_rate", precision = 5, scale = 2)
	private BigDecimal channelFeeRate;

	/** 검색 키워드 JSON 배열. */
	@Column(name = "keywords", columnDefinition = "text")
	private String keywords;

	/** 상품정보제공고시 항목 JSON 오브젝트. */
	@Column(name = "notice_fields", columnDefinition = "text")
	private String noticeFields;

	/** 마켓 고유 필드 JSON (출고지코드·반품지코드·A/S안내 등). */
	@Column(name = "extra_fields", columnDefinition = "text")
	private String extraFields;

	/** 미충족 필수필드 JSON 배열. 비어 있어야 등록 가능. */
	@Column(name = "missing_fields", columnDefinition = "text")
	private String missingFields;

	@Column(name = "is_valid", nullable = false)
	private Boolean isValid = false;

	/** 사용자가 이 마켓을 등록 대상에서 제외할 수 있다. */
	@Column(name = "enabled", nullable = false)
	private Boolean enabled = true;

	/** 등록 결과 — 실패 사유를 남겨 재시도 판단에 쓴다. */
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
