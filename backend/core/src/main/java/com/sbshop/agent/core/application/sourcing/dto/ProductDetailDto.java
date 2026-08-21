package com.sbshop.agent.core.application.sourcing.dto;

import java.math.BigDecimal;
import java.util.List;

public record ProductDetailDto(
	boolean ok,
	String status,
	String sourceUrl,
	String externalId,
	String nameKo,
	String brandKo,
	String brandCode,
	String rootCategory,
	boolean discontinued,
	String partNumber,
	String upc,
	BigDecimal priceKrw,
	BigDecimal listPriceKrw,
	Boolean inStock,
	BigDecimal shippingWeightGrams,
	Integer packageQuantity,
	String dimensions,
	String ingredientsRaw,
	String mainIngredients,
	String otherIngredients,
	String description,
	String usage,
	String caution,
	List<String> images,
	String error) {
}
