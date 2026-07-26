package com.sbshop.agent.core.domain.sourcing;

import com.sbshop.agent.core.domain.common.BaseEntity;
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

/**
 * 등록 초안 — 후보를 실제 상품으로 만들기 전, 사용자가 검수하는 중간 산출물.
 *
 * <p>왜 {@code Product}를 바로 만들지 않는가: 마켓별 상품명·카테고리·키워드는 마켓마다 다르고
 * 검수 중 여러 번 바뀐다. 미검수 상태의 상품을 {@code sb_product}에 넣으면 재고 배치·가격 배치가
 * 그걸 실제 판매 상품으로 착각해 마켓에 동기화하려 든다. 초안을 분리해 그 오염을 막는다.
 *
 * <p>검수 완료 후 {@code DraftPublishUseCase}가 {@code ProductCreateCommand}로 변환해
 * 기존 상품 생성 경로에 태운다.
 */
@Entity
@Table(name = "sb_product_draft")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductDraft extends BaseEntity {

	@Column(name = "candidate_id")
	private Long candidateId;

	// --- 공통 상품 정보 (마켓 공용) ---

	/** 검수 대상 1순위 — 마켓별 상품명의 기반이 되는 한글 기본명. */
	@Column(name = "base_name_ko", length = 255)
	private String baseNameKo;

	@Column(name = "original_name", length = 500)
	private String originalName;

	@Column(name = "brand", length = 100)
	private String brand;

	/** 검수 대상 2순위 — 묶음 수량. 원가·판매가·상세HTML이 전부 여기에 연동된다. */
	@Column(name = "bundle_qty", nullable = false)
	private Integer bundleQty = 1;

	@Column(name = "margin_rate", precision = 5, scale = 2)
	private BigDecimal marginRate;

	/** 단품 매입가(원). 묶음 수량은 곱하지 않은 값. */
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

	// --- 상세 콘텐츠 ---

	@Column(name = "detail_html", columnDefinition = "text")
	private String detailHtml;

	/** 원본 이미지 URL JSON 배열. */
	@Column(name = "source_images", columnDefinition = "text")
	private String sourceImages;

	/** R2 호스팅 후 URL JSON 배열. */
	@Column(name = "hosted_images", columnDefinition = "text")
	private String hostedImages;

	@Column(name = "ingredients_ko", columnDefinition = "text")
	private String ingredientsKo;

	@Column(name = "usage_ko", columnDefinition = "text")
	private String usageKo;

	@Column(name = "caution_ko", columnDefinition = "text")
	private String cautionKo;

	// --- 통관 승인 ---

	/**
	 * 통관 REVIEW 판정에 대한 사용자 승인. false면 등록을 막는다 —
	 * 경고를 띄우기만 하고 등록을 허용하면 경고가 없는 것과 같다.
	 */
	@Column(name = "customs_ack", nullable = false)
	private Boolean customsAck = false;

	// --- 상태 ---

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "draft_status", length = 20, nullable = false)
	private DraftStatus draftStatus = DraftStatus.ENRICHING;

	@Column(name = "enrich_note", columnDefinition = "text")
	private String enrichNote;

	/** 등록 성공 후 생성된 sb_product.id. */
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

	// --- 마켓 초안 ---

	public void putMarketDraft(MarketDraft draft) {
		marketDrafts.removeIf(d -> d.getMarketType() == draft.getMarketType());
		marketDrafts.add(draft);
	}

	public Optional<MarketDraft> findMarketDraft(
		com.sbshop.agent.core.domain.order.enums.MarketType marketType) {
		return marketDrafts.stream().filter(d -> d.getMarketType() == marketType).findFirst();
	}

	/** 등록 대상(enabled)인 마켓 초안만. */
	public List<MarketDraft> enabledMarketDrafts() {
		return marketDrafts.stream().filter(MarketDraft::isEnabled).toList();
	}

	// --- 검수 반영 ---

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

	/** 총 매입가(원) = 단품가 × 묶음수량. 배대지 배송비는 여기 포함하지 않는다. */
	public BigDecimal totalCostPrice() {
		if (costPrice == null)
			return BigDecimal.ZERO;
		return costPrice.multiply(BigDecimal.valueOf(bundleQty != null ? bundleQty : 1));
	}
}
