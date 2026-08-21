package com.sbshop.agent.core.domain.sourcing;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.sourcing.enums.DraftStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;

@Entity
@Table(name = "sb_product_draft")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductDraft extends BaseEntity {
	@Column(name = "candidate_id")
	private Long candidateId;

	@Column(name = "base_name_ko", length = 255)
	private String baseNameKo;

	@Column(name = "original_name", length = 500)
	private String originalName;

	@Column(name = "brand", length = 100)
	private String brand;

	@Column(name = "bundle_qty", nullable = false)
	private Integer bundleQty = 1;

	@Column(name = "margin_rate", precision = 5, scale = 2)
	private BigDecimal marginRate;

	@Column(name = "cost_price", precision = 15, scale = 2)
	private BigDecimal costPrice;

	@Column(name = "source_url", columnDefinition = "text")
	private String sourceUrl;

	@Column(name = "vendor", length = 10)
	private String vendor;

	@Column(name = "origin", length = 100)
	private String origin;

	@Column(name = "hs_code", length = 30)
	private String hsCode;

	@Column(name = "barcode", length = 50)
	private String barcode;

	@Column(name = "weight_g", precision = 10, scale = 2)
	private BigDecimal weightG;

	@Column(name = "capacity", precision = 10, scale = 2)
	private BigDecimal capacity;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "measure_unit", length = 20)
	private MeasureUnit measureUnit;

	@Column(name = "category", length = 50)
	private String category;

	@Column(name = "detail_html", columnDefinition = "text")
	private String detailHtml;

	@Column(name = "source_images", columnDefinition = "text")
	private String sourceImages;

	@Column(name = "hosted_images", columnDefinition = "text")
	private String hostedImages;

	@Column(name = "ingredients_ko", columnDefinition = "text")
	private String ingredientsKo;

	@Column(name = "usage_ko", columnDefinition = "text")
	private String usageKo;

	@Column(name = "caution_ko", columnDefinition = "text")
	private String cautionKo;

	@Column(name = "customs_ack", nullable = false)
	private Boolean customsAck = false;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "draft_status", length = 20, nullable = false)
	private DraftStatus draftStatus = DraftStatus.ENRICHING;

	@Column(name = "enrich_note", columnDefinition = "text")
	private String enrichNote;

	@Column(name = "product_id")
	private Long productId;

	@OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
	@JoinColumn(name = "draft_id")
	private List<MarketDraft> marketDrafts = new ArrayList<>();

	@Builder
	private ProductDraft(Long candidateId, String baseNameKo, String originalName, String brand,
		Integer bundleQty, BigDecimal marginRate, BigDecimal costPrice, String sourceUrl, String vendor,
		String origin, String hsCode, String barcode, BigDecimal weightG, BigDecimal capacity,
		MeasureUnit measureUnit, String category, String sourceImages, String ingredientsKo,
		String usageKo, String cautionKo) {
		this.candidateId = candidateId;
		this.baseNameKo = baseNameKo;
		this.originalName = originalName;
		this.brand = brand;
		this.bundleQty = bundleQty != null ? bundleQty : 1;
		this.marginRate = marginRate;
		this.costPrice = costPrice;
		this.sourceUrl = sourceUrl;
		this.vendor = vendor;
		this.origin = origin;
		this.hsCode = hsCode;
		this.barcode = barcode;
		this.weightG = weightG;
		this.capacity = capacity;
		this.measureUnit = measureUnit;
		this.category = category;
		this.sourceImages = sourceImages;
		this.ingredientsKo = ingredientsKo;
		this.usageKo = usageKo;
		this.cautionKo = cautionKo;
		this.draftStatus = DraftStatus.ENRICHING;
	}

	public void putMarketDraft(MarketDraft draft) {
		marketDrafts.removeIf(d -> d.getMarketType() == draft.getMarketType());
		marketDrafts.add(draft);
	}

	public Optional<MarketDraft> findMarketDraft(
		MarketType marketType) {
		return marketDrafts.stream().filter(d -> d.getMarketType() == marketType).findFirst();
	}

	public List<MarketDraft> enabledMarketDrafts() {
		return marketDrafts.stream().filter(MarketDraft::isEnabled).toList();
	}

	public void updateCommon(String baseNameKo, Integer bundleQty, BigDecimal marginRate,
		BigDecimal costPrice, String origin, String hsCode, String barcode,
		BigDecimal weightG, BigDecimal capacity, MeasureUnit measureUnit, String detailHtml) {
		if (baseNameKo != null)
			this.baseNameKo = baseNameKo;
		if (bundleQty != null && bundleQty > 0)
			this.bundleQty = bundleQty;
		if (marginRate != null)
			this.marginRate = marginRate;
		if (costPrice != null)
			this.costPrice = costPrice;
		if (origin != null)
			this.origin = origin;
		if (hsCode != null)
			this.hsCode = hsCode;
		if (barcode != null)
			this.barcode = barcode;
		if (weightG != null)
			this.weightG = weightG;
		if (capacity != null)
			this.capacity = capacity;
		if (measureUnit != null)
			this.measureUnit = measureUnit;
		if (detailHtml != null)
			this.detailHtml = detailHtml;
	}

	public void applyEnrichment(String detailHtml, String hostedImages, String enrichNote) {
		this.detailHtml = detailHtml;
		this.hostedImages = hostedImages;
		this.enrichNote = enrichNote;
		this.draftStatus = DraftStatus.READY;
	}

	public void acknowledgeCustoms(boolean ack) {
		this.customsAck = ack;
	}

	public void markPublishing() {
		this.draftStatus = DraftStatus.PUBLISHING;
	}

	public void markPublished(Long productId) {
		this.productId = productId;
		this.draftStatus = DraftStatus.PUBLISHED;
	}

	public void markFailed(Long productId) {
		this.productId = productId;
		this.draftStatus = DraftStatus.FAILED;
	}

	public BigDecimal totalCostPrice() {
		if (costPrice == null)
			return BigDecimal.ZERO;
		return costPrice.multiply(BigDecimal.valueOf(bundleQty != null ? bundleQty : 1));
	}
}
