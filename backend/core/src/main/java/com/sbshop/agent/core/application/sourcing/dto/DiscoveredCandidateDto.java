package com.sbshop.agent.core.application.sourcing.dto;

import java.math.BigDecimal;

public record DiscoveredCandidateDto(
	String vendor,
	String externalId,
	String sourceUrl,
	String partNumber,
	String brand,
	String brandCode,
	String nameKo,
	String categorySlug,
	String imageUrl,
	BigDecimal listPrice,
	BigDecimal discountPrice,
	Integer discountPct,
	BigDecimal rating,
	Integer reviewCount,
	Integer sales30d,
	Integer rankPosition,
	boolean sponsored,
	boolean outOfStock,
	boolean discontinued) {
}
