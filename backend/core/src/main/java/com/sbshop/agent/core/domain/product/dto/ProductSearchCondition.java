package com.sbshop.agent.core.domain.product.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.SourceGoneFilter;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import lombok.Builder;

@Builder
public record ProductSearchCondition(
	String keyword,
	List<String> sbCodes,
	List<String> brands,
	MarketType marketFilterType,
	boolean marketFilterRegistered,
	List<ProductCategory> categories,
	List<VendorType> vendors,
	List<StockStatus> stockStatuses,
	List<MarketType> markets,
	List<MarketType> registeredMarkets,
	List<MarketType> missingMarkets,
	boolean pendingChangesOnly,
	boolean anyMarketSyncIssue,
	com.sbshop.agent.core.domain.market.marketplus.MarketPlusIssueFilter marketPlusIssue,
	boolean inStockOnly,
	boolean includeUncategorized,
	SourceGoneFilter sourceGone,
	Integer contentAgeDays,
	ProductContentAgeField contentAgeField) {

	public ProductSearchCondition {
		keyword = blankToNull(keyword);
		sbCodes = nullToEmpty(sbCodes).stream()
			.flatMap(value -> Arrays.stream(value.split("[,\\r\\n]+")))
			.map(String::strip)
			.filter(value -> !value.isEmpty())
			.map(value -> value.toUpperCase(Locale.ROOT))
			.distinct().toList();
		brands = nullToEmpty(brands).stream().filter(value -> !value.isBlank()).distinct().toList();
		categories = nullToEmpty(categories);
		vendors = nullToEmpty(vendors);
		stockStatuses = nullToEmpty(stockStatuses);
		markets = nullToEmpty(markets);
		registeredMarkets = nullToEmpty(registeredMarkets).stream().distinct().toList();
		missingMarkets = nullToEmpty(missingMarkets).stream().distinct().toList();
		if (registeredMarkets.stream().anyMatch(missingMarkets::contains)) {
			throw new IllegalArgumentException("같은 마켓을 등록과 미등록 조건에 동시에 지정할 수 없습니다.");
		}
		sourceGone = (sourceGone == null) ? SourceGoneFilter.ALL : sourceGone;
		marketPlusIssue = marketPlusIssue == null
			? com.sbshop.agent.core.domain.market.marketplus.MarketPlusIssueFilter.ALL : marketPlusIssue;
		if (contentAgeDays != null && (contentAgeDays < 1 || contentAgeDays > 36500))
			throw new IllegalArgumentException("콘텐츠 경과일은 1~36,500일 범위로 입력하세요.");
		contentAgeField = contentAgeField == null ? ProductContentAgeField.ANY : contentAgeField;
	}

	public static ProductSearchCondition none() {
		return ProductSearchCondition.builder().build();
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.strip();
	}

	private static <T> List<T> nullToEmpty(List<T> values) {
		return (values == null) ? List.of() : values.stream().filter(Objects::nonNull).toList();
	}
}
