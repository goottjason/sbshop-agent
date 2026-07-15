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

/**
 * F-PROD-6: GET /products/{id} 상세 응답. 도메인 VO(PriceInfo/LogisticsInfo/ProductSpec/SourcingInfo)를
 * 직접 노출하지 않고 API 소유의 중첩 DTO(record)로 래핑한다. 도메인 VO에 필드가 추가돼도 API 계약(직렬화
 * 형태)에 자동으로 새지 않도록 매핑 지점을 이 DTO에 고정한다. 현재 JSON 형태(중첩 구조·필드명)는 그대로 보존.
 */
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

	/** PriceInfo VO 미러. 필드명·순서 보존(costPrice, exchangeRate, deliveryFee, marginRate, salePrice). */
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

	/** LogisticsInfo VO 미러. 필드명·순서 보존(stock, weight, bundleQuantity). */
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

	/** ProductSpec VO 미러. 필드명·순서 보존(barcode, capacity, measureUnit). */
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

	/** SourcingInfo VO 미러. 필드명·순서 보존(vendor, sourceUrl, manufacturer, origin, hsCode). */
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
