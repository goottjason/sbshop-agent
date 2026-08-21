package com.sbshop.agent.core.application.order.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.domain.order.OrderLineItem;

public final class OrderLineItemMatcher {
	private OrderLineItemMatcher() {}

	public record Incoming(MarketLineItemDto dto, Long resolvedProductId) {
	}

	public record Adoption(OrderLineItem lineItem, MarketLineItemDto dto) {
	}

	public record MatchResult(
		List<Adoption> matched,
		List<MarketLineItemDto> toCreate,
		List<OrderLineItem> unclaimed,
		List<String> warnings) {
	}

	public static MatchResult matchAndAdopt(List<OrderLineItem> existing, List<Incoming> incoming) {
		List<OrderLineItem> candidates = new ArrayList<>(existing);
		candidates.sort(Comparator.comparing(
			OrderLineItem::getId, Comparator.nullsLast(Comparator.naturalOrder())));

		boolean[] consumed = new boolean[candidates.size()];
		List<Adoption> matched = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<Incoming> pending = new ArrayList<>(incoming);

		boolean split = incoming.size() > existing.size();

		matchByExactKey(candidates, consumed, pending, matched);
		matchByProductId(candidates, consumed, pending, matched, warnings);
		matchBySoleCardinality(candidates, consumed, pending, matched, existing.size(), incoming.size());

		for (Adoption adoption : matched) {
			adopt(adoption, split, warnings);
		}

		List<MarketLineItemDto> toCreate = new ArrayList<>();
		for (Incoming inc : pending) {
			toCreate.add(inc.dto());
		}
		List<OrderLineItem> unclaimed = new ArrayList<>();
		for (int i = 0; i < candidates.size(); i++) {
			if (!consumed[i]) {
				unclaimed.add(candidates.get(i));
			}
		}

		return new MatchResult(matched, toCreate, unclaimed, warnings);
	}

	private static void matchByExactKey(List<OrderLineItem> candidates, boolean[] consumed,
		List<Incoming> pending, List<Adoption> matched) {
		for (Incoming inc : new ArrayList<>(pending)) {
			String key = inc.dto().getMarketLineItemNo();
			if (key == null || key.isBlank()) {
				continue;
			}
			for (int i = 0; i < candidates.size(); i++) {
				if (consumed[i] || !key.equals(candidates.get(i).getMarketLineItemNo())) {
					continue;
				}
				consumed[i] = true;
				matched.add(new Adoption(candidates.get(i), inc.dto()));
				pending.remove(inc);
				break;
			}
		}
	}

	private static void matchByProductId(List<OrderLineItem> candidates, boolean[] consumed,
		List<Incoming> pending, List<Adoption> matched, List<String> warnings) {
		for (Incoming inc : new ArrayList<>(pending)) {
			Long productId = inc.resolvedProductId();
			if (productId == null) {
				continue;
			}
			List<Integer> hits = new ArrayList<>();
			for (int i = 0; i < candidates.size(); i++) {
				OrderLineItem row = candidates.get(i);
				if (!consumed[i] && row.getMarketLineItemNo() == null
					&& productId.equals(row.getProductId())) {
					hits.add(i);
				}
			}
			if (hits.isEmpty()) {
				continue;
			}
			if (hits.size() > 1) {
				warnings.add("⚠ 확인 필요: 상품 " + productId + " 의 레거시 라인아이템 후보가 "
					+ hits.size() + "건이라 채택이 모호하다. id 최소값을 채택했다(상품주문 "
					+ inc.dto().getMarketLineItemNo() + ").");
			}
			int picked = hits.get(0);
			consumed[picked] = true;
			matched.add(new Adoption(candidates.get(picked), inc.dto()));
			pending.remove(inc);
		}
	}

	private static void matchBySoleCardinality(List<OrderLineItem> candidates, boolean[] consumed,
		List<Incoming> pending, List<Adoption> matched, int existingCount, int incomingCount) {
		if (existingCount != 1 || incomingCount != 1 || pending.isEmpty()) {
			return;
		}
		for (int i = 0; i < candidates.size(); i++) {
			if (!consumed[i] && candidates.get(i).getMarketLineItemNo() == null) {
				consumed[i] = true;
				matched.add(new Adoption(candidates.get(i), pending.get(0).dto()));
				pending.clear();
				return;
			}
		}
	}

	private static void adopt(Adoption adoption, boolean split, List<String> warnings) {
		OrderLineItem row = adoption.lineItem();
		String key = adoption.dto().getMarketLineItemNo();

		String existingTracking = row.getShippingData() != null
			? row.getShippingData().getTrackingNo() : null;
		if (split && existingTracking != null && !existingTracking.isBlank()) {
			warnings.add("⚠ 확인 필요: 라인아이템 " + row.getId() + " 이 상품주문 " + key
				+ " 으로 쪼개지는데 송장(" + existingTracking + ")을 이미 갖고 있다."
				+ " 다른 상품주문의 송장일 수 있으므로 지우지 않았다.");
		}

		if (key != null && !key.isBlank() && row.getMarketLineItemNo() == null) {
			row.assignMarketLineItemNo(key);
		}
	}
}
