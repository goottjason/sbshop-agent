package com.sbshop.agent.core.domain.product.dto;

import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.vo.ProductWeight;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

@Builder
public record ProductUpdateCommand(
	String brand,
	String name,
	String baseName,
	String originalName,
	ProductCategory category,
	BigDecimal costPrice,
	BigDecimal exchangeRate,
	BigDecimal deliveryFee,
	BigDecimal marginRate,
	BigDecimal couponRate,
	BigDecimal minMarginPrice,
	BigDecimal salePrice,
	Integer stock,
	BigDecimal weight, // kg. 기존 값의 단위 확인·변환은 별도 검토 절차를 따른다.
	Integer bundleQuantity,
	String barcode,
	BigDecimal capacity,
	MeasureUnit measureUnit,
	VendorType vendor,
	String sourceUrl,
	String manufacturer,
	String origin,
	String hsCode,
	List<String> sourceImages,
	List<String> hostedImages,
	String searchKeywords,
	String detailHtml,
	String memo,
	Integer salesQuantity) {
	public ProductUpdateCommand(
		String brand,
		String name,
		String baseName,
		String originalName,
		ProductCategory category,
		BigDecimal costPrice,
		BigDecimal exchangeRate,
		BigDecimal deliveryFee,
		BigDecimal marginRate,
		BigDecimal couponRate,
		BigDecimal minMarginPrice,
		BigDecimal salePrice,
		Integer stock,
		BigDecimal weight, // kg. 기존 값의 단위 확인·변환은 별도 검토 절차를 따른다.
		Integer bundleQuantity,
		String barcode,
		BigDecimal capacity,
		MeasureUnit measureUnit,
		VendorType vendor,
		String sourceUrl,
		String manufacturer,
		String origin,
		String hsCode,
		List<String> sourceImages,
		List<String> hostedImages,
		String searchKeywords,
		String detailHtml,
		String memo) {
		this(brand, name, baseName, originalName, category, costPrice, exchangeRate, deliveryFee, marginRate,
			couponRate, minMarginPrice, salePrice, stock, weight, bundleQuantity, barcode, capacity, measureUnit,
			vendor, sourceUrl, manufacturer, origin, hsCode, sourceImages, hostedImages, searchKeywords, detailHtml,
			memo, null);
	}

	public ProductUpdateCommand {
		if (salesQuantity != null && (salesQuantity < 0 || salesQuantity > 999999))
			throw new IllegalArgumentException("판매용 설정 수량은 0~999,999 범위로 입력하세요.");
		ProductWeight.requireKilograms(weight);
	}
}
