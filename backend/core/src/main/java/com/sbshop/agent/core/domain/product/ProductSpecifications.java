package com.sbshop.agent.core.domain.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.dto.ProductSearchCondition;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.domain.Specification;

public final class ProductSpecifications {

	private static final Map<MarketType, String> DERIVED_IDENTIFIER_KEYS = Map.of(
		MarketType.GMARKET, MarketRegistration.GMARKET_IDENTIFIER_KEY,
		MarketType.AUCTION, MarketRegistration.AUCTION_IDENTIFIER_KEY);

	private ProductSpecifications() {}

	public static Specification<Product> matching(ProductSearchCondition condition) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.isNull(root.get("deletedAt")));
			addKeyword(predicates, condition, root, cb);
			addMarketFilter(predicates, condition, root, query, cb);
			addMarkets(predicates, condition, root, query, cb);
			addCategories(predicates, condition, root, cb);
			addVendors(predicates, condition, root);
			addStockStatuses(predicates, condition, root, cb);
			addInStockOnly(predicates, condition, root, cb);
			addSourceGone(predicates, condition, root, cb);
			return cb.and(predicates.toArray(new Predicate[0]));
		};
	}

	/** 폐기 후보(원본 소멸)만 / 정상만 걸러낸다. 판정 기준은 {@code sourceGoneAt} 의 존재다. */
	private static void addSourceGone(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root, CriteriaBuilder cb) {
		switch (condition.sourceGone()) {
			case GONE_ONLY -> predicates.add(cb.isNotNull(root.get("sourceGoneAt")));
			case ALIVE_ONLY -> predicates.add(cb.isNull(root.get("sourceGoneAt")));
			default -> {
			}
		}
	}

	private static void addKeyword(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root, CriteriaBuilder cb) {
		if (condition.keyword() == null) {
			return;
		}
		String pattern = "%" + condition.keyword().toLowerCase() + "%";
		predicates.add(cb.or(
			cb.like(cb.lower(root.get("productName")), pattern),
			cb.like(cb.lower(root.get("sbCode")), pattern),
			cb.like(cb.lower(root.get("brand")), pattern)));
	}

	private static void addMarketFilter(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
		if (condition.marketFilterType() == null) {
			return;
		}
		Subquery<Long> subquery = query.subquery(Long.class);
		Root<MarketRegistration> registration = subquery.from(MarketRegistration.class);
		subquery.select(registration.get("productId"))
			.where(cb.equal(registration.get("marketType"), condition.marketFilterType()));
		Predicate registered = root.get("id").in(subquery);
		predicates.add(condition.marketFilterRegistered() ? registered : cb.not(registered));
	}

	private static void addMarkets(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
		if (condition.markets().isEmpty()) {
			return;
		}
		Subquery<Long> subquery = query.subquery(Long.class);
		Root<MarketRegistration> registration = subquery.from(MarketRegistration.class);
		List<Predicate> alternatives = new ArrayList<>();
		List<MarketType> registrationBacked = condition.markets().stream()
			.filter(market -> !DERIVED_IDENTIFIER_KEYS.containsKey(market))
			.toList();
		if (!registrationBacked.isEmpty()) {
			alternatives.add(registration.get("marketType").in(registrationBacked));
		}
		for (MarketType market : condition.markets()) {
			String key = DERIVED_IDENTIFIER_KEYS.get(market);
			if (key != null) {
				alternatives.add(cb.and(
					cb.equal(registration.get("marketType"), MarketType.CAFE24),
					identifierPresent(cb, registration.get("marketIdentifiers"), key)));
			}
		}
		subquery.select(registration.get("productId"))
			.where(cb.or(alternatives.toArray(new Predicate[0])));
		predicates.add(root.get("id").in(subquery));
	}

	private static Predicate identifierPresent(CriteriaBuilder cb, Path<String> identifiers, String key) {
		String quotedKey = "\"" + key + "\"";
		return cb.and(
			cb.like(identifiers, "%" + quotedKey + "%"),
			cb.notLike(identifiers, "%" + quotedKey + ":\"\"%"),
			cb.notLike(identifiers, "%" + quotedKey + ": \"\"%"));
	}

	private static void addCategories(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root, CriteriaBuilder cb) {
		boolean hasCategories = !condition.categories().isEmpty();
		if (!hasCategories && !condition.includeUncategorized()) {
			return;
		}
		Path<?> categoryPath = root.get("category");
		if (!hasCategories) {
			predicates.add(cb.isNull(categoryPath));
			return;
		}
		Predicate inCategories = categoryPath.in(condition.categories());
		predicates.add(condition.includeUncategorized()
			? cb.or(inCategories, cb.isNull(categoryPath))
			: inCategories);
	}

	private static void addVendors(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root) {
		if (condition.vendors().isEmpty()) {
			return;
		}
		predicates.add(root.get("sourcingInfo").get("vendor").in(condition.vendors()));
	}

	private static void addStockStatuses(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root, CriteriaBuilder cb) {
		List<StockStatus> statuses = condition.stockStatuses();
		if (statuses.isEmpty()) {
			return;
		}
		Path<StockStatus> statusPath = root.get("stockStatus");
		Path<Integer> stockPath = root.get("logisticsInfo").get("stock");
		List<Predicate> alternatives = new ArrayList<>();
		alternatives.add(cb.and(cb.isNotNull(statusPath), statusPath.in(statuses)));
		if (statuses.contains(StockStatus.IN_STOCK)) {
			alternatives.add(cb.and(cb.isNull(statusPath), cb.greaterThan(stockPath, 0)));
		}
		if (statuses.contains(StockStatus.OUT_OF_STOCK)) {
			alternatives.add(cb.and(cb.isNull(statusPath),
				cb.or(cb.isNull(stockPath), cb.lessThanOrEqualTo(stockPath, 0))));
		}
		predicates.add(cb.or(alternatives.toArray(new Predicate[0])));
	}

	private static void addInStockOnly(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root, CriteriaBuilder cb) {
		if (!condition.inStockOnly()) {
			return;
		}
		Path<Integer> stockPath = root.get("logisticsInfo").get("stock");
		predicates.add(cb.greaterThan(stockPath, 0));
	}
}
