package com.sbshop.agent.core.domain.product;

import com.sbshop.agent.core.domain.market.MarketConnectionState;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.marketplus.MarketPlusIssueFilter;
import com.sbshop.agent.core.domain.market.marketplus.MarketPlusSearchScope;
import com.sbshop.agent.core.domain.market.marketplus.MarketPlusTransmission;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import jakarta.persistence.criteria.*;
import java.util.ArrayList;
import java.util.List;

/** Same latest-observation and conflict precedence as the grid, before count/pagination. */
final class MarketPlusIssueSpecifications {
	private MarketPlusIssueSpecifications() {}

	static Predicate matching(Root<Product> product, CriteriaQuery<?> query, CriteriaBuilder cb,
		MarketPlusIssueFilter filter, MarketPlusSearchScope scope) {
		if (scope == null)
			throw new IllegalArgumentException("마켓플러스 검색의 판매 계정 확인이 필요합니다.");
		List<Predicate> markets = new ArrayList<>();
		for (MarketType market : List.of(MarketType.GMARKET, MarketType.AUCTION)) {
			Predicate failed = latestFailure(product, query, cb, scope, market, false);
			Predicate conflicted = latestFailure(product, query, cb, scope, market, true);
			markets.add(switch (filter) {
				case ANY_ISSUE -> failed;
				case FAILURE -> cb.and(failed, cb.not(conflicted));
				case CONFLICT -> conflicted;
				default -> throw new IllegalArgumentException("마켓플러스 전송 이슈 조건을 확인하세요.");
			});
		}
		return cb.or(markets.toArray(Predicate[]::new));
	}

	private static Predicate latestFailure(Root<Product> product, CriteriaQuery<?> query, CriteriaBuilder cb,
		MarketPlusSearchScope scope, MarketType market, boolean conflictOnly) {
		Subquery<Long> failures = query.subquery(Long.class);
		Root<MarketPlusTransmission> e = failures.from(MarketPlusTransmission.class);
		Root<MarketRegistration> r = failures.from(MarketRegistration.class);
		String account = market == MarketType.GMARKET ? scope.gmarketAccount() : scope.auctionAccount();
		String state = market == MarketType.GMARKET ? "gmarketConnectionState" : "auctionConnectionState";
		String identifier = market == MarketType.GMARKET ? MarketRegistration.GMARKET_IDENTIFIER_KEY
			: MarketRegistration.AUCTION_IDENTIFIER_KEY;
		List<Predicate> conditions = new ArrayList<>(List.of(
			cb.equal(e.get("productId"), product.get("id")), cb.equal(r.get("productId"), product.get("id")),
			cb.equal(r.get("id"), e.get("registrationId")), cb.equal(r.get("marketType"), MarketType.CAFE24),
			cb.equal(r.get("connectionState"), MarketConnectionState.LINKED),
			cb.equal(r.get(state), MarketConnectionState.LINKED),
			cb.equal(e.get("mallId"), scope.mallId()), cb.equal(e.get("shopNo"), 1),
			cb.equal(e.get("market"), market.name()),
			cb.equal(e.get("sellerAccount"), account), cb.equal(e.get("outcome"), "FAILURE"),
			identifierEquals(cb, r, "product_no", e.get("cafe24ProductNo")),
			identifierEquals(cb, r, "product_code", e.get("cafe24ProductCode")),
			identifierEquals(cb, r, identifier, e.get("externalId"))));
		Subquery<Long> newer = failures.subquery(Long.class);
		Root<MarketPlusTransmission> n = newer.from(MarketPlusTransmission.class);
		newer.select(n.get("id")).where(sameOperation(cb, e, n),
			cb.greaterThan(n.get("completedAt"), e.get("completedAt")));
		conditions.add(cb.not(cb.exists(newer)));
		if (conflictOnly) {
			Subquery<Long> success = failures.subquery(Long.class);
			Root<MarketPlusTransmission> s = success.from(MarketPlusTransmission.class);
			success.select(s.get("id")).where(sameOperation(cb, e, s),
				cb.equal(s.get("completedAt"), e.get("completedAt")), cb.equal(s.get("outcome"), "SUCCESS"));
			conditions.add(cb.exists(success));
		}
		failures.select(e.get("id")).where(conditions.toArray(Predicate[]::new));
		return cb.exists(failures);
	}

	private static Predicate identifierEquals(CriteriaBuilder cb, Root<MarketRegistration> r, String key,
		Expression<String> expected) {
		return cb.isTrue(cb.function("sb_market_identifier_equals", Boolean.class, r.get("marketIdentifiers"),
			cb.literal(key), expected));
	}

	private static Predicate sameOperation(CriteriaBuilder cb, Root<MarketPlusTransmission> a,
		Root<MarketPlusTransmission> b) {
		return cb.and(List
			.of("registrationId", "productId", "mallId", "shopNo", "market", "sellerAccount", "cafe24ProductNo",
				"cafe24ProductCode", "externalId", "transferType")
			.stream()
			.map(field -> cb.equal(a.get(field), b.get(field))).toArray(Predicate[]::new));
	}
}
