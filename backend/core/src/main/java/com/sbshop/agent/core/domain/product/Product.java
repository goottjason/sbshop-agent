package com.sbshop.agent.core.domain.product;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.vo.MediaInfo;
import com.sbshop.agent.core.domain.product.vo.PriceInfo;
import com.sbshop.agent.core.domain.product.vo.ProductName;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.core.domain.product.vo.SourcingInfo;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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
import java.sql.Types;

@Entity
@Table(name = "sb_product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseEntity {

	/** 자체 관리 상품 코드 (SB코드, 예: 210129IHB014) */
	@Column(name = "sb_code", unique = true, nullable = false, length = 50)
	private String sbCode;

	/** 상품 카테고리 (건강기능식품, 화장품 등) */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "category", length = 50)
	private ProductCategory category;

	/** 상품 제조사 또는 브랜드 (벤더) */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "vendor", length = 50)
	private VendorType vendor;

	/** 상품 바코드 (UPC, EAN 등) */
	@Column(name = "barcode", length = 100)
	private String barcode;

	/** 상품명 관련 정보 (국문명, 영문명 등) */
	@Embedded
	private ProductName productName;

	/** 상품 상세 스펙 (용량, 캡슐 수 등) */
	@Embedded
	private ProductSpec productSpec;

	/** 가격 정보 (원가, 판매가 등) */
	@Embedded
	private PriceInfo priceInfo;

	/** 소싱 정보 (구매처 URL, 소싱처 등) */
	@Embedded
	private SourcingInfo sourcingInfo;

	/** 미디어 정보 (상품 이미지 URL 등) */
	@Embedded
	private MediaInfo mediaInfo;

	/** 재고 상태 (품절, 판매중 등) */
	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "stock_status", length = 30)
	private StockStatus stockStatus;

	/** 재입고 예정일 (품절 시) */
	@Column(name = "restock_date")
	private LocalDate restockDate;

	@Builder
	public Product(
		String sbCode,
		ProductCategory category,
		VendorType vendor,
		String barcode,
		ProductName productName,
		ProductSpec productSpec,
		PriceInfo priceInfo,
		SourcingInfo sourcingInfo,
		MediaInfo mediaInfo,
		StockStatus stockStatus,
		LocalDate restockDate) {
		this.sbCode = sbCode;
		this.category = category;
		this.vendor = vendor;
		this.barcode = barcode;
		this.productName = productName;
		this.productSpec = productSpec;
		this.priceInfo = priceInfo;
		this.sourcingInfo = sourcingInfo;
		this.mediaInfo = mediaInfo;
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
		if (costPrice == null)
			return;
		if (this.priceInfo == null) {
			this.priceInfo = PriceInfo.builder().costPrice(costPrice).build();
		} else {
			this.priceInfo = PriceInfo.builder()
				.costPrice(costPrice)
				.exchangeRate(this.priceInfo.getExchangeRate())
				.marginRate(this.priceInfo.getMarginRate())
				.salePrice(this.priceInfo.getSalePrice())
				.build();
		}
	}

	public void updateSourcingStock(Integer stock) {
		if (stock == null)
			return;
		if (this.sourcingInfo == null) {
			this.sourcingInfo = SourcingInfo.builder().stock(stock).build();
		} else {
			this.sourcingInfo = SourcingInfo.builder()
				.url(this.sourcingInfo.getUrl())
				.manufacturer(this.sourcingInfo.getManufacturer())
				.origin(this.sourcingInfo.getOrigin())
				.hsCode(this.sourcingInfo.getHsCode())
				.stock(stock)
				.build();
		}
	}
}
