package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;

public record ProductSaveRequest(
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
		String rawSourceHtml,
		boolean isAvailable,
		Integer bundleQuantity,
		BigDecimal marginRate,
		VendorType vendor) {

	public ProductCreateCommand toCommand() {
		return new ProductCreateCommand(
				sourceUrl, costPrice, baseName, originalName, brand, origin,
				weight, capacity, measureUnit, sourceImages, null, rawSourceHtml,
				null, isAvailable, bundleQuantity, marginRate, vendor);
	}
}
