package com.sbshop.agent.core.domain.product.dto;

import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.vo.ProductWeight;
import java.math.BigDecimal;
import java.util.List;

public record ProductCreateCommand(
	String sourceUrl,
	BigDecimal costPrice,
	String baseName,
	String originalName,
	String brand,
	String origin,
	BigDecimal weight, // kg. g 등 원본 단위는 호출 경계에서 변환한다.
	BigDecimal capacity,
	MeasureUnit measureUnit,
	List<String> sourceImages,
	List<String> hostedImages,
	String rawSourceHtml,
	String rawCategory,
	boolean isAvailable,
	Integer bundleQuantity,
	BigDecimal marginRate,
	VendorType vendor,
	String barcode) {
	public ProductCreateCommand {
		ProductWeight.requireKilograms(weight);
	}
}
