package com.sbshop.agent.core.application.sourcing.dto;

import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

@Builder
public record ScrapedProductDto(
	String sourceUrl,
	String baseName,
	String originalName,
	String brand,
	BigDecimal costPrice,
	BigDecimal listPrice,
	BigDecimal discountPrice,
	int discountType,
	int couponRate,
	int salesDiscount,
	boolean isAvailable,
	List<String> sourceImages,
	String rawSourceHtml,
	String rawCategory,
	BigDecimal capacity,
	String unit,
	VendorType vendor) {
	public MeasureUnit inferMeasureUnit() {
		if (unit == null)
			return MeasureUnit.UNKNOWN;
		String lower = unit.toLowerCase();
		if (lower.contains("capsule") || lower.contains("캡슐"))
			return MeasureUnit.CAPSULE;
		if (lower.contains("tablet") || lower.contains("정") || lower.contains("softgel"))
			return MeasureUnit.TABLET;
		if (lower.contains("ml"))
			return MeasureUnit.ML;
		if (lower.contains("g"))
			return MeasureUnit.G;
		if (lower.contains("oz"))
			return MeasureUnit.OZ;
		return MeasureUnit.UNKNOWN;
	}
}
