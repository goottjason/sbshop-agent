package com.sbshop.agent.core.domain.product;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.VarcharJdbcType;
import java.sql.Types;

@Entity
@Table(name = "sb_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

	@Column(name = "sb_code", unique = true, nullable = false, length = 50)
	private String sbCode;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "category", length = 50)
	private ProductCategory category;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "vendor", length = 50)
	private VendorType vendor;

	@Column(name = "barcode", length = 100)
	private String barcode;

	@Column(name = "brand", length = 100)
	private String brand;

	@Column(name = "original_name", length = 255)
	private String originalName;

	@Column(name = "base_name", length = 255)
	private String baseName;

	@Column(name = "product_name", nullable = false, length = 255)
	private String productName;

	@Column(name = "capacity", precision = 10, scale = 2)
	private BigDecimal capacity;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "measure_unit", length = 20)
	private MeasureUnit measureUnit;

	@Column(name = "weight", precision = 10, scale = 2)
	private BigDecimal weight;

	@Column(name = "bundle_quantity")
	private Integer bundleQuantity;

	@Column(name = "cost_price", precision = 10, scale = 2)
	private BigDecimal costPrice;

	@Column(name = "exchange_rate", precision = 10, scale = 4)
	private BigDecimal exchangeRate;

	@Column(name = "margin_rate", precision = 5, scale = 2)
	private BigDecimal marginRate;

	@Column(name = "sale_price", precision = 10, scale = 2)
	private BigDecimal salePrice;

	@Column(name = "sourcing_url", length = 1000)
	private String sourcingUrl;

	@Column(name = "manufacturer", length = 100)
	private String manufacturer;

	@Column(name = "origin", length = 100)
	private String origin;

	@Column(name = "hs_code", length = 50)
	private String hsCode;

	@Column(name = "stock")
	private Integer stock;

	@JdbcType(VarcharJdbcType.class)
	@Column(name = "source_images")
	private String sourceImages;

	@JdbcType(VarcharJdbcType.class)
	@Column(name = "hosted_images")
	private String hostedImages;

	@Column(name = "search_keywords", length = 500)
	private String searchKeywords;

	@JdbcType(VarcharJdbcType.class)
	@Column(name = "detail_html")
	private String detailHtml;

	@Column(name = "memo", length = 2000)
	private String memo;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "stock_status", length = 30)
	private StockStatus stockStatus;

	@Column(name = "restock_date")
	private LocalDate restockDate;

	@Builder
	public Product(
		String sbCode,
		ProductCategory category,
		VendorType vendor,
		String barcode,
		String brand,
		String originalName,
		String baseName,
		String productName,
		BigDecimal capacity,
		MeasureUnit measureUnit,
		BigDecimal weight,
		Integer bundleQuantity,
		BigDecimal costPrice,
		BigDecimal exchangeRate,
		BigDecimal marginRate,
		BigDecimal salePrice,
		String sourcingUrl,
		String manufacturer,
		String origin,
		String hsCode,
		Integer stock,
		String sourceImages,
		String hostedImages,
		String searchKeywords,
		String detailHtml,
		String memo,
		StockStatus stockStatus,
		LocalDate restockDate) {
		this.sbCode = sbCode;
		this.category = category;
		this.vendor = vendor;
		this.barcode = barcode;
		this.brand = brand;
		this.originalName = originalName;
		this.baseName = baseName;
		this.productName = productName;
		this.capacity = capacity;
		this.measureUnit = measureUnit;
		this.weight = weight;
		this.bundleQuantity = bundleQuantity;
		this.costPrice = costPrice;
		this.exchangeRate = exchangeRate;
		this.marginRate = marginRate;
		this.salePrice = salePrice;
		this.sourcingUrl = sourcingUrl;
		this.manufacturer = manufacturer;
		this.origin = origin;
		this.hsCode = hsCode;
		this.stock = stock;
		this.sourceImages = sourceImages;
		this.hostedImages = hostedImages;
		this.searchKeywords = searchKeywords;
		this.detailHtml = detailHtml;
		this.memo = memo;
		this.stockStatus = stockStatus;
		this.restockDate = restockDate;
	}

	public void updateStockStatus(StockStatus status) {
		this.stockStatus = status;
	}

	public void updateRestockDate(LocalDate restockDate) {
		this.restockDate = restockDate;
	}

	public void updateCostPrice(BigDecimal costPrice) {
		this.costPrice = costPrice;
	}

	public void updateSourcingStock(Integer stock) {
		this.stock = stock;
	}
}
