package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import java.math.BigDecimal;
import java.util.List;

public record ProductSourcingResponse(
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
	String rawCategory,
	BigDecimal capacity,
	String unit,
	String assembledNamePreview) {

	public static ProductSourcingResponse from(ScrapedProductDto dto) {
		String preview = "";
		if (dto.brand() != null && dto.baseName() != null) {
			String unitDesc = dto.unit() != null ? dto.unit() : "";
			BigDecimal cap = dto.capacity() != null ? dto.capacity() : BigDecimal.ONE;
			preview = String.format("%s %s, %d%s, %d개",
				dto.brand(), dto.baseName(), cap.intValue(), unitDesc,
				1);
		}
		return new ProductSourcingResponse(
			dto.sourceUrl(), dto.baseName(), dto.originalName(), dto.brand(),
			dto.costPrice(), dto.listPrice(), dto.discountPrice(),
			dto.discountType(), dto.couponRate(), dto.salesDiscount(),
			dto.isAvailable(), dto.sourceImages(), dto.rawCategory(),
			dto.capacity(), dto.unit(), preview);
	}
}
