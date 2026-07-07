package com.sbshop.agent.infrastructure.client.sourcing.dto;

import java.math.BigDecimal;
import java.util.List;

public record IherbProductInfo(
	String productName,
	String englishName,
	String brandName,
	BigDecimal listPrice,
	BigDecimal discountPrice,
	int discountType,
	int couponRate,
	int salesDiscount,
	boolean isAvailable,
	List<String> imageLinks,
	String categoryPath,
	String htmlDescription,
	BigDecimal capacity,
	String unit,
	String sourceUrl) {
}
