package com.sbshop.agent.core.application.order.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.domain.order.OrderLineItem;

/**
 * 마켓이 준 상품주문들을 <b>기존 라인아이템에 짝지어</b> 무엇을 갱신하고 무엇을 새로 만들지 정한다.
 *
 * <p>배열 인덱스로 짝짓지 않는다. 마켓이 순서를 바꾸면 엉뚱한 상품에 송장·상태가 붙는다
 * (Cafe24 현행 방식의 결함, 설계 §5.2).
 *
 * <h2>레거시 행 채택이 곧 백필이다 (D-132)</h2>
 *
 * <p>운영 DB의 라인아이템 240행 전부가 {@code market_line_item_no}가 NULL이다(2026-08-06 실측).
 * 어댑터가 상품주문 식별자를 채우기 시작하면 이 행들은 정확키 매칭에 걸리지 않아
 * 같은 상품주문에 라인아이템이 두 벌 생기고, 소싱처·실구매가·구매상태 같은 <b>우리 고유
 * 정보는 아무도 보지 않는 옛 행에 남는다.</b>
 *
 * <p>별도 백필 배치를 두지 않는다 — 동기화가 30일 창을 매 사이클 다시 훑으므로, 매칭 시점에
 * 레거시 행을 채택해 키를 부여하는 것으로 충분하다(설계 §5.4). 30일 이전 주문은 손대지 않는다.
 *
 * <h2>매칭 순서</h2>
 *
 * <ol>
 * <li><b>정확키</b> — 양쪽 {@code marketLineItemNo}가 같다. 가장 강한 근거.</li>
 * <li><b>상품 ID</b> — 키가 없는 레거시 행 중 상품이 일치하는 것을 채택한다.
 *     정나영 건(1행 → 2건)에서 순번 1을 고르는 근거다.</li>
 * <li><b>카디널리티</b> — 주문 전체가 기존 1행 : 상품주문 1건이면 무조건 채택한다.
 *     현재 240행 전부가 이 형태이고, 상품 ID가 어긋나도(마켓 sbCode 재매핑 등) 종전
 *     동작이 그냥 재할당이었으므로 채택이 동작 보존이다. 거부하면 중복 행이 생겨 퇴행한다.</li>
 * </ol>
 *
 * <p>3번을 "남은 것이 1:1일 때"가 아니라 <b>"주문 전체가 1:1일 때"</b>로 좁힌 것이 중요하다.
 * 남은 것으로 판정하면, 상품이 명백히 어긋나는 두 건을 마지막에 억지로 짝지어 버린다.
 * 상품 정보가 양쪽에 있는데 일치하지 않으면 그것은 "모르겠다"가 아니라 "다르다"다.
 *
 * <p><b>기존 행은 최대 한 번만 소비된다.</b> 두 상품주문이 한 행을 함께 채택하면 서로 덮어써
 * D-130이 그대로 재발한다.
 */
public final class OrderLineItemMatcher {

	private OrderLineItemMatcher() {}

	/**
	 * 매칭 대상 상품주문 하나.
	 *
	 * @param dto               마켓이 준 상품주문
	 * @param resolvedProductId 이 상품주문에서 해석된 SB 상품 ID. 해석은 마켓별
	 *                          {@code findBySbCode} 로직이라 호출자가 미리 넘긴다. 미매핑이면 null.
	 */
	public record Incoming(MarketLineItemDto dto, Long resolvedProductId) {
	}

	/** 기존 행 하나에 상품주문 하나가 짝지어졌다. */
	public record Adoption(OrderLineItem lineItem, MarketLineItemDto dto) {
	}

	/**
	 * @param matched   기존 행에 반영할 짝
	 * @param toCreate  짝지을 기존 행이 없어 새로 만들 상품주문. 구매정보가 없으므로
	 *                  미구매로 노출되는 것이 정확하다(설계 §5.4-2)
	 * @param unclaimed 아무 상품주문도 짝지어지지 않은 기존 행. <b>지우지 않는다</b> —
	 *                  우리 고유 정보가 붙어 있을 수 있다
	 * @param warnings  사람이 판단해야 할 것(설계 §5.4-3). 운영 화면이 아니라 로그로 남긴다
	 */
	public record MatchResult(
		List<Adoption> matched,
		List<MarketLineItemDto> toCreate,
		List<OrderLineItem> unclaimed,
		List<String> warnings) {
	}

	/**
	 * 짝을 짓고, 채택한 레거시 행에는 상품주문 식별자를 <b>부여한다</b>(백필).
	 *
	 * <p>이름에 {@code adopt}가 들어간 것은 순수 함수가 아니라는 표시다 — 키 부여가 이 작업의
	 * 목적 자체이고, 부여하지 않으면 다음 사이클에도 정확키 경로를 타지 못한다.
	 * 상품주문 식별자가 없는 평면 DTO(전환 전 마켓)일 때는 키를 위조하지 않고 null로 남긴다(D-131).
	 */
	public static MatchResult matchAndAdopt(List<OrderLineItem> existing, List<Incoming> incoming) {
		// id 순으로 고정한다 — findByOrderId는 순서를 보장하지 않고, 후보가 여럿일 때
		// 사이클마다 다른 행을 채택하면 재현 불가능한 데이터가 남는다. id 없는 행(미영속)은 뒤로.
		List<OrderLineItem> candidates = new ArrayList<>(existing);
		candidates.sort(Comparator.comparing(
			OrderLineItem::getId, Comparator.nullsLast(Comparator.naturalOrder())));

		boolean[] consumed = new boolean[candidates.size()];
		List<Adoption> matched = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<Incoming> pending = new ArrayList<>(incoming);

		// 쪼개짐 판정 — 기존보다 상품주문이 많으면 한 행이 여러 상품의 정보를 섞어 갖고 있었다는 뜻이다.
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

	/** 1단계 — 양쪽 상품주문 식별자가 같은 짝. */
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

	/** 2단계 — 키 없는 레거시 행 중 상품이 일치하는 것을 채택한다. */
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

	/**
	 * 3단계 — 주문 전체가 기존 1행 : 상품주문 1건이면 채택한다.
	 *
	 * <p>현재 240행 전부의 형태다. 상품 ID가 없거나(sbCode 미매핑) 어긋나도 채택한다 —
	 * 종전 동작이 상품 ID 재할당이었으므로 이것이 동작 보존이고, 거부하면 중복 행이 생긴다.
	 */
	private static void matchBySoleCardinality(List<OrderLineItem> candidates, boolean[] consumed,
		List<Incoming> pending, List<Adoption> matched, int existingCount, int incomingCount) {
		if (existingCount != 1 || incomingCount != 1 || pending.isEmpty()) {
			return;
		}
		for (int i = 0; i < candidates.size(); i++) {
			// 레거시 행만 대상이다. 키가 이미 붙은 행이 1단계에서 안 걸렸다면 그것은
			// 마켓이 더는 보내지 않는 다른 상품주문이라는 뜻이므로 전용해선 안 된다.
			if (!consumed[i] && candidates.get(i).getMarketLineItemNo() == null) {
				consumed[i] = true;
				matched.add(new Adoption(candidates.get(i), pending.get(0).dto()));
				pending.clear();
				return;
			}
		}
	}

	/** 채택을 확정한다 — 키를 부여하고, 섞인 흔적이 있으면 경고로 남긴다. */
	private static void adopt(Adoption adoption, boolean split, List<String> warnings) {
		OrderLineItem row = adoption.lineItem();
		String key = adoption.dto().getMarketLineItemNo();

		String existingTracking = row.getShippingData() != null
			? row.getShippingData().getTrackingNo() : null;
		if (split && existingTracking != null && !existingTracking.isBlank()) {
			// 정나영 건의 실제 형태 — 한 행이 순번1의 상품·정산액과 순번2의 송장을 함께 갖고 있었다.
			// 어느 상품주문의 송장인지 자동으로 알 수 없으므로 지우지 않고 사람이 판단하게 남긴다.
			warnings.add("⚠ 확인 필요: 라인아이템 " + row.getId() + " 이 상품주문 " + key
				+ " 으로 쪼개지는데 송장(" + existingTracking + ")을 이미 갖고 있다."
				+ " 다른 상품주문의 송장일 수 있으므로 지우지 않았다.");
		}

		if (key != null && !key.isBlank() && row.getMarketLineItemNo() == null) {
			row.assignMarketLineItemNo(key);
		}
	}
}
