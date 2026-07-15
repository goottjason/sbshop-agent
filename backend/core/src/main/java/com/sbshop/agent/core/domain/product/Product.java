package com.sbshop.agent.core.domain.product;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.vo.ImageInfo;
import com.sbshop.agent.core.domain.product.vo.LogisticsInfo;
import com.sbshop.agent.core.domain.product.vo.PriceInfo;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.core.domain.product.vo.SourcingInfo;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import java.sql.Types;

@Entity
@Table(name = "sb_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

	/** 판매중 상태에서 마켓에 전송하는 기본 재고 수량(실수량 추적 안 함 — 판매중/품절 이분법). */
	public static final int DEFAULT_IN_STOCK_QUANTITY = 999;

	// --- 1. 기본 식별 정보 (Flat 필드) ---

	@Column(name = "sb_code", unique = true, nullable = false, length = 50)
	private String sbCode;

	@Column(name = "brand", length = 100)
	private String brand;

	@Column(name = "product_name", nullable = false, length = 255)
	private String productName;

	@Column(name = "base_name", length = 255)
	private String baseName;

	@Column(name = "original_name", length = 255)
	private String originalName;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "category", length = 50)
	private ProductCategory category;

	// --- 2. 묶음 정보 (Value Objects) ---

	@Embedded
	private PriceInfo priceInfo;

	@Embedded
	private LogisticsInfo logisticsInfo;

	@Embedded
	private ProductSpec productSpec;

	@Embedded
	private SourcingInfo sourcingInfo;

	@Embedded
	private ImageInfo imageInfo;

	// --- 3. 상세 설명 및 부가 정보 ---

	@Column(name = "search_keywords", length = 500)
	private String searchKeywords;

	// @Lob 금지: PostgreSQL에서 @Lob String은 Large Object(OID)로 매핑돼 text 컬럼을
	// getLong()으로 읽다 "Bad value for type long"으로 조회 전체가 깨진다 (D-021, 운영 실측).
	@Column(name = "detail_html", columnDefinition = "text")
	private String detailHtml;

	@Column(name = "memo", length = 2000)
	private String memo;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "stock_status", length = 30)
	private StockStatus stockStatus;

	@Column(name = "restock_date")
	private LocalDate restockDate;

	// --- 4. 생성자 (private) ---

	private Product(
		String sbCode,
		String brand,
		String productName,
		String baseName,
		String originalName,
		ProductCategory category,
		PriceInfo priceInfo,
		LogisticsInfo logisticsInfo,
		ProductSpec productSpec,
		SourcingInfo sourcingInfo,
		ImageInfo imageInfo,
		String searchKeywords,
		String detailHtml,
		String memo) {
		this.sbCode = sbCode;
		this.brand = brand;
		this.productName = productName;
		this.baseName = baseName;
		this.originalName = originalName;
		this.category = category;
		this.priceInfo = priceInfo;
		this.logisticsInfo = logisticsInfo;
		this.productSpec = productSpec;
		this.sourcingInfo = sourcingInfo;
		this.imageInfo = imageInfo;
		this.searchKeywords = searchKeywords;
		this.detailHtml = detailHtml;
		this.memo = memo;
	}

	// --- 5. 도메인 팩토리 ---

	public static Product create(String sbCode, ProductCreateCommand command) {
		String safeBrand = defaultString(command.brand());
		String safeBaseName = defaultString(command.baseName());
		String safeOriginalName = defaultString(command.originalName());

		ProductCategory category = determineCategory(command.rawCategory());
		String hsCode = determineHsCode(category);

		int bundleQty = defaultIfNull(command.bundleQuantity(), 1);
		BigDecimal cap = defaultIfNull(command.capacity(), BigDecimal.ONE);
		MeasureUnit unit = defaultIfNull(command.measureUnit(), MeasureUnit.UNKNOWN);

		String assembledName = assembleMarketName(safeBrand, safeBaseName, cap, unit, bundleQty);
		String searchKeywords = generateSearchKeywords(safeBrand, safeBaseName, safeOriginalName);
		String finalDetailHtml = buildDetailHtml(assembledName, safeOriginalName, bundleQty, cap, unit, command);

		PriceInfo priceInfo = createPriceInfo(command);
		LogisticsInfo logisticsInfo = createLogisticsInfo(command, bundleQty);
		ProductSpec productSpec = createProductSpec(cap, unit);
		ImageInfo imageInfo = createImageInfo(command);
		SourcingInfo sourcingInfo = createSourcingInfo(command, safeBrand, hsCode);

		Product created = new Product(
			sbCode, safeBrand, assembledName, safeBaseName, safeOriginalName,
			category, priceInfo, logisticsInfo, productSpec, sourcingInfo,
			imageInfo, searchKeywords, finalDetailHtml, "{}");
		created.updateStockStatus(command.isAvailable() ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK);
		return created;
	}

	// --- 6. 도메인 업데이트 ---

	public void update(ProductUpdateCommand command) {
		if (command.brand() != null)
			this.brand = command.brand();
		if (command.name() != null)
			this.productName = command.name();
		if (command.baseName() != null)
			this.baseName = command.baseName();
		if (command.originalName() != null)
			this.originalName = command.originalName();
		if (command.category() != null)
			this.category = command.category();
		if (command.searchKeywords() != null)
			this.searchKeywords = command.searchKeywords();
		if (command.detailHtml() != null)
			this.detailHtml = command.detailHtml();
		if (command.memo() != null)
			this.memo = command.memo();

		updatePriceInfo(command);
		updateLogisticsInfo(command);
		updateProductSpec(command);
		updateSourcingInfo(command);
		updateImageInfo(command);
	}

	private void updatePriceInfo(ProductUpdateCommand command) {
		boolean hasUpdate = command.costPrice() != null || command.exchangeRate() != null ||
			command.deliveryFee() != null || command.marginRate() != null ||
			command.salePrice() != null;
		if (!hasUpdate)
			return;
		PriceInfo.PriceInfoBuilder builder = this.priceInfo != null
			? this.priceInfo.toBuilder() : PriceInfo.builder();
		if (command.costPrice() != null)
			builder.costPrice(command.costPrice());
		if (command.exchangeRate() != null)
			builder.exchangeRate(command.exchangeRate());
		if (command.deliveryFee() != null)
			builder.deliveryFee(command.deliveryFee());
		if (command.marginRate() != null)
			builder.marginRate(command.marginRate());
		if (command.salePrice() != null)
			builder.salePrice(command.salePrice());
		this.priceInfo = builder.build();
	}

	private void updateLogisticsInfo(ProductUpdateCommand command) {
		boolean hasUpdate = command.stock() != null || command.weight() != null ||
			command.bundleQuantity() != null;
		if (!hasUpdate)
			return;
		LogisticsInfo.LogisticsInfoBuilder builder = this.logisticsInfo != null
			? this.logisticsInfo.toBuilder() : LogisticsInfo.builder();
		if (command.stock() != null)
			builder.stock(command.stock());
		if (command.weight() != null)
			builder.weight(command.weight());
		if (command.bundleQuantity() != null)
			builder.bundleQuantity(command.bundleQuantity());
		this.logisticsInfo = builder.build();
	}

	private void updateProductSpec(ProductUpdateCommand command) {
		boolean hasUpdate = command.barcode() != null || command.capacity() != null ||
			command.measureUnit() != null;
		if (!hasUpdate)
			return;
		ProductSpec.ProductSpecBuilder builder = this.productSpec != null
			? this.productSpec.toBuilder() : ProductSpec.builder();
		if (command.barcode() != null)
			builder.barcode(command.barcode());
		if (command.capacity() != null)
			builder.capacity(command.capacity());
		if (command.measureUnit() != null)
			builder.measureUnit(command.measureUnit());
		this.productSpec = builder.build();
	}

	private void updateSourcingInfo(ProductUpdateCommand command) {
		boolean hasUpdate = command.vendor() != null || command.sourceUrl() != null ||
			command.manufacturer() != null || command.origin() != null || command.hsCode() != null;
		if (!hasUpdate)
			return;
		SourcingInfo.SourcingInfoBuilder builder = this.sourcingInfo != null
			? this.sourcingInfo.toBuilder() : SourcingInfo.builder();
		if (command.vendor() != null)
			builder.vendor(command.vendor());
		if (command.sourceUrl() != null)
			builder.sourceUrl(command.sourceUrl());
		if (command.manufacturer() != null)
			builder.manufacturer(command.manufacturer());
		if (command.origin() != null)
			builder.origin(command.origin());
		if (command.hsCode() != null)
			builder.hsCode(command.hsCode());
		this.sourcingInfo = builder.build();
	}

	private void updateImageInfo(ProductUpdateCommand command) {
		boolean hasUpdate = command.sourceImages() != null || command.hostedImages() != null;
		if (!hasUpdate)
			return;
		ImageInfo.ImageInfoBuilder builder = this.imageInfo != null
			? this.imageInfo.toBuilder() : ImageInfo.builder();
		// null이면 스킵(기존 이미지 유지), 빈 리스트(non-null)면 전체 제거 (F-PROD-13)
		if (command.sourceImages() != null)
			builder.sourceImages(command.sourceImages());
		if (command.hostedImages() != null)
			builder.hostedImages(command.hostedImages());
		this.imageInfo = builder.build();
	}

	// --- 7. 기존 호환성 위임 메서드 ---

	public void updateStockStatus(StockStatus status) {
		this.stockStatus = status;
	}

	public void updateRestockDate(LocalDate restockDate) {
		this.restockDate = restockDate;
	}

	public void updateCostPrice(BigDecimal costPrice) {
		if (this.priceInfo == null) {
			this.priceInfo = PriceInfo.builder().costPrice(costPrice).build();
		} else {
			this.priceInfo = this.priceInfo.toBuilder().costPrice(costPrice).build();
		}
	}

	public void updateSourcingStock(Integer stock) {
		if (this.logisticsInfo == null) {
			this.logisticsInfo = LogisticsInfo.builder().stock(stock).build();
		} else {
			this.logisticsInfo = this.logisticsInfo.toBuilder().stock(stock).build();
		}
	}

	// --- 8. 이미지 헬퍼 ---

	public List<String> getHostedImages() {
		if (this.imageInfo == null || this.imageInfo.getHostedImages() == null) {
			return new ArrayList<>();
		}
		return this.imageInfo.getHostedImages();
	}

	public String getRepImageUrl() {
		List<String> images = getHostedImages();
		return images.isEmpty() ? "" : images.get(0);
	}

	// --- 8-1. 기존 호환성 위임 게터 ---

	public String getSourcingUrl() {
		return sourcingInfo != null ? sourcingInfo.getSourceUrl() : null;
	}

	public BigDecimal getCostPrice() {
		return priceInfo != null ? priceInfo.getCostPrice() : null;
	}

	public BigDecimal getSalePrice() {
		return priceInfo != null ? priceInfo.getSalePrice() : null;
	}

	public Integer getStock() {
		return logisticsInfo != null ? logisticsInfo.getStock() : null;
	}

	public VendorType getVendor() {
		return sourcingInfo != null ? sourcingInfo.getVendor() : null;
	}

	public List<String> getSourceImages() {
		if (this.imageInfo == null || this.imageInfo.getSourceImages() == null) {
			return new ArrayList<>();
		}
		return this.imageInfo.getSourceImages();
	}

	// --- 9. 도메인 내부 헬퍼 ---

	private static ProductCategory determineCategory(String rawCategory) {
		if (rawCategory == null)
			return ProductCategory.UNKNOWN;
		if (rawCategory.contains("보충제") || rawCategory.contains("미네랄") || rawCategory.contains("비타민")) {
			return ProductCategory.SUPPLEMENT;
		}
		return ProductCategory.UNKNOWN;
	}

	private static String determineHsCode(ProductCategory category) {
		if (category == ProductCategory.SUPPLEMENT) {
			return "2106.90.9099";
		}
		return "";
	}

	private static PriceInfo createPriceInfo(ProductCreateCommand command) {
		BigDecimal cost = defaultIfNull(command.costPrice(), BigDecimal.ZERO);
		BigDecimal margin = defaultIfNull(command.marginRate(), BigDecimal.ZERO);
		BigDecimal sale = cost.multiply(BigDecimal.ONE.add(margin.divide(BigDecimal.valueOf(100))));
		return PriceInfo.builder()
			.costPrice(cost)
			.exchangeRate(BigDecimal.ONE)
			.deliveryFee(BigDecimal.ZERO)
			.marginRate(margin)
			.salePrice(sale)
			.build();
	}

	private static LogisticsInfo createLogisticsInfo(ProductCreateCommand command, int bundleQty) {
		return LogisticsInfo.builder()
			.stock(DEFAULT_IN_STOCK_QUANTITY)   // 기존: command.isAvailable() ? 999 : 0
			.weight(defaultIfNull(command.weight(), BigDecimal.ZERO))
			.bundleQuantity(bundleQty)
			.build();
	}

	private static ProductSpec createProductSpec(BigDecimal cap, MeasureUnit unit) {
		return ProductSpec.builder()
			.barcode("")
			.capacity(cap)
			.measureUnit(unit)
			.build();
	}

	private static ImageInfo createImageInfo(ProductCreateCommand command) {
		return ImageInfo.builder()
			.sourceImages(command.sourceImages() != null ? command.sourceImages() : new ArrayList<>())
			.hostedImages(command.hostedImages() != null ? command.hostedImages() : new ArrayList<>())
			.build();
	}

	private static SourcingInfo createSourcingInfo(ProductCreateCommand command, String brand, String hsCode) {
		return SourcingInfo.builder()
			.vendor(command.vendor() != null ? command.vendor() : VendorType.IHB)
			.sourceUrl(defaultString(command.sourceUrl()))
			.manufacturer(brand)
			.origin(command.origin() != null ? command.origin() : "상세설명 참조")
			.hsCode(hsCode)
			.build();
	}

	private static String generateSearchKeywords(String brand, String baseName, String originalName) {
		String keywords = String.format("%s,%s,%s", brand, baseName, originalName)
			.replaceAll(",,", ",")
			.replaceAll("^,|,$", "");
		return keywords.length() > 500 ? keywords.substring(0, 499) : keywords;
	}

	private static String buildDetailHtml(String name, String originalName, int bundleCount,
		BigDecimal capacity, MeasureUnit unit, ProductCreateCommand command) {
		List<String> hosted = command.hostedImages() != null ? command.hostedImages() : new ArrayList<>();
		String mainImg = hosted.isEmpty() ? "" : hosted.get(0);
		List<String> addImgs = hosted.size() > 1 ? hosted.subList(1, hosted.size()) : new ArrayList<>();
		return generateTemplateHtml(name, originalName, bundleCount, capacity, unit, mainImg, addImgs,
			command.rawSourceHtml());
	}

	private static String assembleMarketName(String brand, String baseName, BigDecimal capacity,
		MeasureUnit unit, int bundleCount) {
		String unitDesc = unit != null && unit != MeasureUnit.UNKNOWN ? unit.getDescription() : "";
		int capInt = capacity.intValue();
		return String.format("%s %s, %d%s, %d개", brand, baseName, capInt, unitDesc, bundleCount)
			.replaceAll(" ,", ",")
			.trim();
	}

	private static String generateTemplateHtml(String name, String originalName, int bundleCount,
		BigDecimal capacity, MeasureUnit measureUnit, String mainImageUrl,
		List<String> additionalImageUrls, String rawSourceHtml) {
		StringBuilder sb = new StringBuilder();
		int capInt = capacity.intValue();

		sb.append(
			"<img src=\"http://ai.esmplus.com/shouldbe2480/notice/sb_top.png\" style=\"margin:0 auto; display:block; max-width:100%;\"><br/><br/>");
		sb.append("<div style=\"text-align: center; margin-bottom: 10px;\">")
			.append("<span style=\"font-size: 22px; color: #00B0A2; font-weight: bold;\">")
			.append(name)
			.append("</span><br/>")
			.append("<span style=\"font-size: 18px; color: #555;\">")
			.append(originalName != null ? originalName : "")
			.append("</span></div><br/><br/>");

		sb.append("<div style=\"text-align: center; margin-bottom: 30px;\">")
			.append("<span style=\"font-size: 20px; color: #EF007C; font-weight: bold;\">")
			.append("[구성품] 총 ")
			.append(bundleCount)
			.append(" 묶음상품 (1개 당 ")
			.append(capInt)
			.append(measureUnit != null ? measureUnit.getDescription() : "")
			.append(")</span></div><br/>");

		if (mainImageUrl != null && !mainImageUrl.isEmpty()) {
			sb.append("<img src=\"").append(mainImageUrl)
				.append("\" style=\"margin:0 auto; display:block; max-width:800px;\"><br/><br/>");
		}
		for (String addImg : additionalImageUrls) {
			sb.append("<img src=\"").append(addImg)
				.append("\" style=\"margin:0 auto; display:block; max-width:800px;\"><br/><br/>");
		}

		sb.append(
			"<div style=\"text-align: left; color: #636363; font-size: 16px; line-height: 1.6; max-width: 800px; margin: 0 auto;\">")
			.append(rawSourceHtml != null ? rawSourceHtml : "")
			.append("</div><br/><br/>");
		sb.append(
			"<img src=\"http://ai.esmplus.com/shouldbe2480/notice/sb_bottom.png\" style=\"margin:0 auto; display:block; max-width:100%;\">");

		return sb.toString();
	}

	private static String defaultString(String str) {
		return str != null ? str : "";
	}

	private static <T> T defaultIfNull(T value, T defaultValue) {
		return value != null ? value : defaultValue;
	}
}
