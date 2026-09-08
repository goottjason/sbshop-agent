package com.sbshop.agent.core.domain.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.MarketConnectionState;
import com.sbshop.agent.core.domain.product.edit.ProductChangeTarget;
import com.sbshop.agent.core.domain.product.content.ProductContentSnapshot;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.dto.ProductContentAgeField;
import com.sbshop.agent.core.domain.product.dto.ProductSearchCondition;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.persistence.criteria.Expression;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.data.jpa.domain.Specification;

public final class ProductSpecifications {

	private static final Map<MarketType, String> DERIVED_IDENTIFIER_KEYS = Map.of(
		MarketType.GMARKET, MarketRegistration.GMARKET_IDENTIFIER_KEY,
		MarketType.AUCTION, MarketRegistration.AUCTION_IDENTIFIER_KEY);

	private ProductSpecifications() {}

	public static Specification<Product> matching(ProductSearchCondition condition) {
		return matching(condition, null);
	}

	public static Specification<Product> matching(ProductSearchCondition condition,
		com.sbshop.agent.core.domain.market.marketplus.MarketPlusSearchScope marketPlusScope) {
		return matching(condition, marketPlusScope, null);
	}

	public static Specification<Product> matching(ProductSearchCondition condition,
		com.sbshop.agent.core.domain.market.marketplus.MarketPlusSearchScope marketPlusScope, String workspaceSort) {
		Instant cutoff = condition.contentAgeDays() == null ? null
			: Instant.now().minus(condition.contentAgeDays(), ChronoUnit.DAYS);
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			predicates.add(cb.isNull(root.get("deletedAt")));
			addKeyword(predicates, condition, root, cb);
			if (!condition.sbCodes().isEmpty()) {
				predicates.add(cb.upper(root.get("sbCode")).in(condition.sbCodes()));
			}
			if (!condition.brands().isEmpty()) {
				predicates.add(root.get("brand").in(condition.brands()));
			}
			addMarketFilter(predicates, condition, root, query, cb);
			addMarkets(predicates, condition, root, query, cb);
			addConnectionConditions(predicates, condition, root, query, cb);
			addCategories(predicates, condition, root, cb);
			addVendors(predicates, condition, root);
			addStockStatuses(predicates, condition, root, cb);
			addInStockOnly(predicates, condition, root, cb);
			addSourceGone(predicates, condition, root, cb);
			addContentAge(predicates, condition.contentAgeField(), cutoff, root, query, cb);
			if (condition.anyMarketSyncIssue())
				predicates.add(cb.or(MarketSyncIssueSpecifications.matching(root, query, cb),
					MarketPlusIssueSpecifications.matching(root, query, cb,
						com.sbshop.agent.core.domain.market.marketplus.MarketPlusIssueFilter.ANY_ISSUE,
						marketPlusScope)));
			if (condition.marketPlusIssue() != com.sbshop.agent.core.domain.market.marketplus.MarketPlusIssueFilter.ALL)
				predicates.add(MarketPlusIssueSpecifications.matching(root, query, cb, condition.marketPlusIssue(),
					marketPlusScope));
			if (workspaceSort != null && query.getResultType() != Long.class && query.getResultType() != long.class)
				addWorkspaceOrder(workspaceSort, condition.contentAgeField(), root, query, cb);
			return cb.and(predicates.toArray(new Predicate[0]));
		};
	}

	private static void addContentAge(List<Predicate> predicates, ProductContentAgeField field, Instant cutoff,
		Root<Product> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
		if (cutoff == null)
			return;
		var alternatives = new ArrayList<Predicate>();
		if (field != ProductContentAgeField.DETAIL_HTML)
			alternatives.add(oldOrUnapplied(latestApplied("imagesAppliedAt", root, query, cb), cutoff, cb));
		if (field != ProductContentAgeField.IMAGES)
			alternatives.add(oldOrUnapplied(latestApplied("detailAppliedAt", root, query, cb), cutoff, cb));
		predicates.add(cb.or(alternatives.toArray(Predicate[]::new)));
	}

	private static Predicate oldOrUnapplied(Expression<Instant> last, Instant cutoff, CriteriaBuilder cb) {
		return cb.or(cb.isNull(last), cb.lessThanOrEqualTo(last, cutoff));
	}

	private static Subquery<Instant> latestApplied(String field, Root<Product> product, CriteriaQuery<?> query,
		CriteriaBuilder cb) {
		Subquery<Instant> subquery = query.subquery(Instant.class);
		Root<ProductContentSnapshot> snapshot = subquery.from(ProductContentSnapshot.class);
		subquery.select(cb.greatest(snapshot.<Instant>get(field)))
			.where(cb.equal(snapshot.get("productId"), product.get("id")), cb.isNotNull(snapshot.get(field)),
				cb.equal(snapshot.get("sourceUrl"), product.get("sourcingInfo").get("sourceUrl")),
				cb.equal(snapshot.get("vendor"), product.get("sourcingInfo").get("vendor").as(String.class)));
		return subquery;
	}

	private static void addWorkspaceOrder(String sort, ProductContentAgeField field, Root<Product> root,
		CriteriaQuery<?> query, CriteriaBuilder cb) {
		Expression<Instant> oldest;
		if (field == ProductContentAgeField.IMAGES)
			oldest = latestApplied("imagesAppliedAt", root, query, cb);
		else if (field == ProductContentAgeField.DETAIL_HTML)
			oldest = latestApplied("detailAppliedAt", root, query, cb);
		else {
			var images = latestApplied("imagesAppliedAt", root, query, cb);
			var detail = latestApplied("detailAppliedAt", root, query, cb);
			oldest = cb.<Instant>selectCase()
				.when(cb.or(cb.isNull(images), cb.isNull(detail)), cb.nullLiteral(Instant.class))
				.when(cb.lessThanOrEqualTo(images, detail), images).otherwise(detail);
		}
		var orders = new ArrayList<jakarta.persistence.criteria.Order>();
		if ("workspacePriority".equals(sort))
			orders.add(cb.asc(cb.<Integer>selectCase().when(actionRequired(root, query, cb), 0).otherwise(1)));
		orders.add(cb.asc(cb.<Integer>selectCase().when(cb.isNull(oldest), 0).otherwise(1)));
		orders.add(cb.asc(oldest));
		orders.add(cb.asc(root.get("id")));
		query.orderBy(orders);
	}

	private static Predicate actionRequired(Root<Product> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
		Subquery<Long> failed = query.subquery(Long.class);
		Root<MarketRegistration> registration = failed.from(MarketRegistration.class);
		var identifiers = java.util.Arrays.stream(MarketType.values()).map(market -> cb.and(
			cb.equal(registration.get("marketType"), market),
			cb.or(java.util.Arrays.stream(MarketRegistration.marketCodeKeys(market))
				.map(key -> identifierPresent(cb, registration.get("marketIdentifiers"), key))
				.toArray(Predicate[]::new))))
			.toArray(Predicate[]::new);
		failed.select(registration.get("id")).where(cb.equal(registration.get("productId"), root.get("id")),
			cb.equal(registration.get("connectionState"), MarketConnectionState.LINKED),
			cb.isNotNull(registration.get("lastSyncError")), cb.or(identifiers));
		return cb.or(pendingChanges(root, query, cb), cb.exists(failed), cb.isNotNull(root.get("sourceGoneAt")),
			cb.and(cb.isNotNull(root.get("lastCrawlError")),
				cb.greaterThan(cb.length(cb.trim(root.get("lastCrawlError"))), 0)));
	}

	/** 폐기 후보(원본 소멸)만 / 정상만 걸러낸다. 판정 기준은 {@code sourceGoneAt} 의 존재다. */
	private static void addSourceGone(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root, CriteriaBuilder cb) {
		switch (condition.sourceGone()) {
			case GONE_ONLY -> predicates.add(cb.isNotNull(root.get("sourceGoneAt")));
			case ALIVE_ONLY -> predicates.add(cb.isNull(root.get("sourceGoneAt")));
			default -> {}
		}
	}

	private static void addKeyword(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root, CriteriaBuilder cb) {
		if (condition.keyword() == null) {
			return;
		}
		// 검색어의 %, _는 SQL 와일드카드가 아닌 상품명에 들어 있는 문자로 취급한다.
		String pattern = "%" + condition.keyword().toLowerCase(Locale.ROOT)
			.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
		predicates.add(cb.or(
			cb.like(cb.lower(root.get("productName")), pattern, '!'),
			cb.like(cb.lower(root.get("sbCode")), pattern, '!'),
			cb.like(cb.lower(root.get("brand")), pattern, '!'),
			cb.like(cb.lower(root.get("productSpec").get("barcode")), pattern, '!')));
	}

	private static void addMarketFilter(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
		if (condition.marketFilterType() == null) {
			return;
		}
		Predicate registered = registeredIn(condition.marketFilterType(), root, query, cb);
		predicates.add(condition.marketFilterRegistered() ? registered : cb.not(registered));
	}

	private static void addMarkets(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
		if (!condition.markets().isEmpty()) {
			predicates.add(cb.or(condition.markets().stream()
				.map(market -> registeredIn(market, root, query, cb)).toArray(Predicate[]::new)));
		}
	}

	private static void addConnectionConditions(List<Predicate> predicates, ProductSearchCondition condition,
		Root<Product> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
		condition.registeredMarkets().forEach(market -> predicates.add(registeredIn(market, root, query, cb)));
		condition.missingMarkets().forEach(market -> predicates.add(cb.not(registeredIn(market, root, query, cb))));
		if (condition.pendingChangesOnly()) {
			predicates.add(pendingChanges(root, query, cb));
		}
	}

	private static Predicate pendingChanges(Root<Product> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
		Subquery<Long> subquery = query.subquery(Long.class);
		Root<ProductChangeTarget> target = subquery.from(ProductChangeTarget.class);
		subquery.select(target.get("id")).where(cb.equal(target.get("productId"), root.get("id")),
			target.get("state").in("PENDING_DISPATCH", "BATCH_MANAGED", "DISPATCHED", "ACTION_REQUIRED",
				"AWAITING_REVIEW"));
		return cb.exists(subquery);
	}

	private static Predicate registeredIn(MarketType market, Root<Product> product,
		CriteriaQuery<?> query, CriteriaBuilder cb) {
		Subquery<Long> subquery = query.subquery(Long.class);
		Root<MarketRegistration> registration = subquery.from(MarketRegistration.class);
		Predicate direct = cb.and(
			cb.equal(registration.get("marketType"), market),
			cb.equal(registration.get("connectionState"), MarketConnectionState.LINKED),
			cb.or(java.util.Arrays.stream(MarketRegistration.marketCodeKeys(market))
				.map(key -> identifierPresent(cb, registration.get("marketIdentifiers"), key))
				.toArray(Predicate[]::new)));
		String derivedKey = DERIVED_IDENTIFIER_KEYS.get(market);
		Predicate connected = direct;
		if (derivedKey != null) {
			String stateField = market == MarketType.GMARKET ? "gmarketConnectionState" : "auctionConnectionState";
			connected = cb.or(direct, cb.and(
				cb.equal(registration.get("marketType"), MarketType.CAFE24),
				cb.equal(registration.get(stateField), MarketConnectionState.LINKED),
				identifierPresent(cb, registration.get("marketIdentifiers"), derivedKey)));
		}
		subquery.select(registration.get("id"))
			.where(cb.equal(registration.get("productId"), product.get("id")), connected);
		return cb.exists(subquery);
	}

	private static Predicate identifierPresent(CriteriaBuilder cb, Path<String> identifiers, String key) {
		return cb.isTrue(cb.function("sb_market_has_identifier", Boolean.class, identifiers, cb.literal(key)));
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
