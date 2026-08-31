package com.sbshop.agent.core.domain.product.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.SourceGoneFilter;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import lombok.Builder;

@Builder
public record ProductSearchCondition(
	String keyword,
	MarketType marketFilterType,
	boolean marketFilterRegistered,
	List<ProductCategory> categories,
	List<VendorType> vendors,
	List<StockStatus> stockStatuses,
	List<MarketType> markets,
	boolean inStockOnly,
	boolean includeUncategorized,
	SourceGoneFilter sourceGone) {

	public ProductSearchCondition {
		keyword = blankToNull(keyword);
		categories = nullToEmpty(categories);
		vendors = nullToEmpty(vendors);
		stockStatuses = nullToEmpty(stockStatuses);
		markets = nullToEmpty(markets);
		sourceGone = (sourceGone == null) ? SourceGoneFilter.ALL : sourceGone;
	}

	public static ProductSearchCondition none() {
		return ProductSearchCondition.builder().build();
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

	private static <T> List<T> nullToEmpty(List<T> values) {
		return (values == null) ? List.of() : List.copyOf(values);
	}
}
