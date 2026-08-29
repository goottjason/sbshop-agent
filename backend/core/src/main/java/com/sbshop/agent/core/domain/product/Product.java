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
import com.sbshop.agent.core.domain.product.service.BarcodeValidator;
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
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.JdbcTypeCode;
import java.sql.Types;

@Slf4j
@Entity
@Table(name = "sb_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {
	public static final int DEFAULT_IN_STOCK_QUANTITY = 999;

	@Column(name = "sb_code", unique = true, nullable = false, length = 50)
	private String sbCode;

	@Column(name = "deleted_at")
	private java.time.LocalDateTime deletedAt;

	@Column(name = "source_gone_at")
	private java.time.LocalDateTime sourceGoneAt;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "source_gone_reason", length = 32)
	private com.sbshop.agent.core.domain.product.enums.SourceGoneReason sourceGoneReason;

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

	@Column(name = "search_keywords", length = 500)
	private String searchKeywords;

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
		ProductSpec productSpec = createProductSpec(cap, unit, command.barcode(), sbCode);
		ImageInfo imageInfo = createImageInfo(command);
		SourcingInfo sourcingInfo = createSourcingInfo(command, safeBrand, hsCode);

		Product created = new Product(
			sbCode, safeBrand, assembledName, safeBaseName, safeOriginalName,
			category, priceInfo, logisticsInfo, productSpec, sourcingInfo,
			imageInfo, searchKeywords, finalDetailHtml, "{}");
		created.updateStockStatus(command.isAvailable() ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK);
		return created;
	}

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
			applyBarcode(builder, command.barcode());
		if (command.capacity() != null)
			builder.capacity(command.capacity());
		if (command.measureUnit() != null)
			builder.measureUnit(command.measureUnit());
		this.productSpec = builder.build();
	}

	private void applyBarcode(ProductSpec.ProductSpecBuilder builder, String rawBarcode) {
		BarcodeValidator.Result checked = BarcodeValidator.validate(rawBarcode);
		if (checked.valid()) {
			builder.barcode(checked.normalized());
			return;
		}
		if (checked.absent()) {
			builder.barcode("");
			return;
		}
		log.warn("[바코드] 수정 시 형식 위반으로 기존 값 유지 sbCode={} 사유={}", this.sbCode, checked.reason());
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

		if (command.sourceImages() != null)
			builder.sourceImages(command.sourceImages());
		if (command.hostedImages() != null)
			builder.hostedImages(command.hostedImages());
		this.imageInfo = builder.build();
	}

	private static ProductCategory determineCategory(String rawCategory) {
		if (rawCategory == null)
			return ProductCategory.UNKNOWN;
		if (rawCategory.contains("보충제") || rawCategory.contains("미네랄") || rawCategory.contains("비타민")) {
			return ProductCategory.SUPPLEMENT;
		}
		return ProductCategory.UNKNOWN;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public boolean isSourceGone() {
		return sourceGoneAt != null;
	}

	public void markSourceGone(com.sbshop.agent.core.domain.product.enums.SourceGoneReason reason) {
		if (reason == null) {
			throw new IllegalArgumentException(
				"원본 소멸 사유는 필수다 — 왜 사라졌는지 없이 폐기 후보로 만들지 않는다");
		}
		if (sourceGoneAt == null) {
			this.sourceGoneAt = java.time.LocalDateTime.now();
		}
		this.sourceGoneReason = reason;
	}

	public void clearSourceGone() {
		this.sourceGoneAt = null;
		this.sourceGoneReason = null;
	}

	public void markDeleted() {
		if (deletedAt == null) {
			this.deletedAt = java.time.LocalDateTime.now();
		}
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
			.stock(DEFAULT_IN_STOCK_QUANTITY)
			.weight(defaultIfNull(command.weight(), BigDecimal.ZERO))
			.bundleQuantity(bundleQty)
			.build();
	}

	private static ProductSpec createProductSpec(BigDecimal cap, MeasureUnit unit,
		String rawBarcode, String sbCode) {
		BarcodeValidator.Result checked = BarcodeValidator.validate(rawBarcode);
		if (!checked.valid() && !checked.absent()) {
			log.warn("[바코드] 생성 시 형식 위반으로 저장하지 않음 sbCode={} 사유={}", sbCode, checked.reason());
		}
		return ProductSpec.builder()
			.barcode(checked.valid() ? checked.normalized() : "")
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
