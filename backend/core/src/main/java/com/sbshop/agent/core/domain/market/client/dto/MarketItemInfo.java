package com.sbshop.agent.core.domain.market.client.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.Builder;

@Builder
public record MarketItemInfo(
	boolean isMasterData,
	String mappingKey,
	Map<String, String> marketIdentifiers,
	String name,
	String originalName,
	BigDecimal salePrice,
	Integer stock,
	String detailHtml,
	List<String> images,
	String brand,
	String manufacturer,
	String barcode,
	String generalProductName,
	Map<String, Object> rawData) {
}
