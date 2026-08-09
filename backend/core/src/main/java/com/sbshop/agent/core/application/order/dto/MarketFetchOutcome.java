package com.sbshop.agent.core.application.order.dto;

import java.util.List;

/**
 * 마켓 주문 조회의 결과와 <b>그 조회가 온전했는지</b>.
 *
 * <p>{@code complete=false}는 일부 조회가 실패해 <b>주문이 응답에서 빠졌을 수 있다</b>는 뜻이다.
 * 부재로 상태를 단정하는 판정(취소·클레임 감지)은 그때 근거를 잃는다 — 못 본 것과 사라진 것은 다르다.
 *
 * <p>종전에는 어댑터가 부분 실패를 경고 로그로만 남기고 조용히 반환했다. 호출자가 그것을 알 수
 * 없으니 "응답에 없다 = 마켓에서 사라졌다"로 읽었고, 2026-08-08 쿠팡에서 실제로 멀쩡한 주문이
 * 취소로 바뀌었다(D-160).
 *
 * <p>마켓마다 따로 두지 않는다 — 이 코드베이스는 같은 규율을 마켓 수만큼 복제했다가 같은 버그를
 * 마켓 수만큼 고쳐야 했던 이력이 있다({@code MarketLineItemSyncDispatcher} 참조).
 */
public record MarketFetchOutcome(List<MarketOrderDto> orders, boolean complete) {

	/** 온전한 조회. */
	public static MarketFetchOutcome complete(List<MarketOrderDto> orders) {
		return new MarketFetchOutcome(orders, true);
	}

	/** 일부 조회가 실패한 결과 — 빠진 주문이 있을 수 있다. */
	public static MarketFetchOutcome partial(List<MarketOrderDto> orders) {
		return new MarketFetchOutcome(orders, false);
	}
}
