package com.sbshop.agent.core.domain.product.dto;

import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;

public record ProductCreateCommand(
		String sourceUrl,
		BigDecimal costPrice,
		String baseName,
		String originalName,
		String brand,
		String origin,
		BigDecimal weight,
		BigDecimal capacity,
		MeasureUnit measureUnit,
		List<String> sourceImages,
		List<String> hostedImages,
		String rawSourceHtml,
		String rawCategory,
		boolean isAvailable,
		Integer bundleQuantity,
		BigDecimal marginRate,
		VendorType vendor) {
}
