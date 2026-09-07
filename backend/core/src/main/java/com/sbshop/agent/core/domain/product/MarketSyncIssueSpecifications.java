package com.sbshop.agent.core.domain.product;

import com.sbshop.agent.core.domain.market.MarketConnectionState;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.sync.*;
import jakarta.persistence.criteria.*;
import java.util.*;

/** Latest failed work for the current product revision and exact current connection, before pagination. */
final class MarketSyncIssueSpecifications {
	static Predicate matching(Root<Product> product, CriteriaQuery<?> query, CriteriaBuilder cb) {
		return cb.or(failed(product, query, cb, MarketPriceTask.class),
			failed(product, query, cb, MarketStockTask.class),
			failed(product, query, cb, MarketFieldTask.class));
	}

	private static <T> Predicate failed(Root<Product> product, CriteriaQuery<?> query, CriteriaBuilder cb,
		Class<T> type) {
		Subquery<Long> failure = query.subquery(Long.class);
		Root<T> task = failure.from(type);
		Root<MarketRegistration> registration = failure.from(MarketRegistration.class);
		Subquery<Long> newer = failure.subquery(Long.class);
		Root<T> next = newer.from(type);
		List<Predicate> same = new ArrayList<>(List.of(cb.equal(next.get("productId"), task.get("productId")),
			cb.equal(next.get("market"), task.get("market")),
			cb.equal(next.get("registrationId"), task.get("registrationId")),
			cb.equal(next.get("identifiers"), task.get("identifiers")),
			cb.greaterThan(next.get("id"), task.get("id"))));
		if (type == MarketFieldTask.class)
			same.add(cb.equal(next.get("fields"), task.get("fields")));
		newer.select(next.get("id")).where(same.toArray(Predicate[]::new));
		failure.select(task.get("id")).where(cb.equal(task.get("productId"), product.get("id")),
			cb.equal(task.get("productRevision"), product.get("revision")),
			cb.equal(registration.get("id"), task.get("registrationId")),
			cb.equal(registration.get("connectionState"), MarketConnectionState.LINKED),
			cb.equal(registration.get("marketIdentifiers"), task.get("identifiers")),
			task.get("state").in("BLOCKED", "UNKNOWN", "FAILED_MISMATCH", "REJECTED", "ACTION_REQUIRED"),
			cb.not(cb.exists(newer)));
		return cb.exists(failure);
	}
}
