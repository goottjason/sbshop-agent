package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.vo.LogisticsInfo;
import com.sbshop.agent.core.domain.product.vo.PriceInfo;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.core.domain.product.vo.SourcingInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProductDetailResponse(
	Long id,
	String sbCode,
	String brand,
	String productName,
	String baseName,
	String originalName,
	ProductCategory category,
	VendorType vendor,
	PriceInfoDto priceInfo,
	LogisticsInfoDto logisticsInfo,
	ProductSpecDto productSpec,
	SourcingInfoDto sourcingInfo,
	List<String> sourceImages,
	List<String> hostedImages,
	String searchKeywords,
	String detailHtml,
	String memo,
	StockStatus stockStatus,
	LocalDate restockDate) {

	public record PriceInfoDto(
		BigDecimal costPrice,
		BigDecimal exchangeRate,
		BigDecimal deliveryFee,
		BigDecimal marginRate,
		BigDecimal salePrice) {

		static PriceInfoDto from(PriceInfo vo) {
			if (vo == null) {
				return null;
			}
			return new PriceInfoDto(
				vo.getCostPrice(),
				vo.getExchangeRate(),
				vo.getDeliveryFee(),
				vo.getMarginRate(),
				vo.getSalePrice());
		}
	}

	public record LogisticsInfoDto(
		Integer stock,
		BigDecimal weight,
		Integer bundleQuantity) {

		static LogisticsInfoDto from(LogisticsInfo vo) {
			if (vo == null) {
				return null;
			}
			return new LogisticsInfoDto(vo.getStock(), vo.getWeight(), vo.getBundleQuantity());
		}
	}

	public record ProductSpecDto(
		String barcode,
		BigDecimal capacity,
		MeasureUnit measureUnit) {

		static ProductSpecDto from(ProductSpec vo) {
			if (vo == null) {
				return null;
			}
			return new ProductSpecDto(vo.getBarcode(), vo.getCapacity(), vo.getMeasureUnit());
		}
	}

	public record SourcingInfoDto(
		VendorType vendor,
		String sourceUrl,
		String manufacturer,
		String origin,
		String hsCode) {

		static SourcingInfoDto from(SourcingInfo vo) {
			if (vo == null) {
				return null;
			}
			return new SourcingInfoDto(
				vo.getVendor(),
				vo.getSourceUrl(),
				vo.getManufacturer(),
				vo.getOrigin(),
				vo.getHsCode());
		}
	}

	public static ProductDetailResponse from(Product p) {
		return new ProductDetailResponse(
			p.getId(),
			p.getSbCode(),
			p.getBrand(),
			p.getProductName(),
			p.getBaseName(),
			p.getOriginalName(),
			p.getCategory(),
			p.getVendor(),
			PriceInfoDto.from(p.getPriceInfo()),
			LogisticsInfoDto.from(p.getLogisticsInfo()),
			ProductSpecDto.from(p.getProductSpec()),
			SourcingInfoDto.from(p.getSourcingInfo()),
			p.getSourceImages(),
			p.getHostedImages(),
			p.getSearchKeywords(),
			p.getDetailHtml(),
			p.getMemo(),
			p.getStockStatus(),
			p.getRestockDate());
	}
}
