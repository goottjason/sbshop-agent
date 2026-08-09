package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.domain.order.OrderLineItem;

/**
 * 3계층 동기화에서 <b>마켓마다 다른 것만</b> 담는 정책.
 *
 * <p>{@link MarketLineItemSyncDispatcher}가 골격(매칭·채택·배송 upsert·미러·분할 보류·경고)을
 * 갖고, 마켓별로 갈리는 세 가지만 여기로 뺐다. 11번가와 쿠팡을 전환하며 골격이 거의 그대로
 * 두 번 복제됐고, 차이는 결국 이 셋뿐이었다.
 *
 * <p>진행상태 반영·송장 미러·정산 보존 같은 규칙은 <b>정책에 없다</b> — 마켓과 무관하게 같아야
 * 하는 규율이므로 골격이 소유한다. 그래야 규율을 한 곳에서 고칠 수 있다.
 */
public interface MarketLineItemSyncPolicy {

	/** 로그 접두 태그(예: {@code "ELEVEN_STREET"}). 기존 로그 포맷을 보존한다. */
	String logTag();

	/**
	 * 상품주문에서 SB 상품 ID를 해석한다. 마켓마다 근거가 다르다 —
	 * 11번가는 {@code sellerPrdCd → findBySbCode}, 쿠팡은 {@code vendorItemId → market_registration}
	 * (+ {@code sellerProductId} 역조회와 식별자 보강).
	 *
	 * <p><b>골격은 이 메서드를 상품주문당 한 번만 부른다.</b> 쿠팡 구현은 저장 부수효과가 있어
	 * 두 번 부르면 보강 저장이 중복된다(2026-08-06 실제로 그렇게 됐다).
	 *
	 * @return 매핑된 상품 ID, 매핑 불가면 {@code null}(그때 분할 보류 판정에 쓰인다)
	 */
	Long resolveProductId(MarketLineItemDto dto);

	/**
	 * 상품주문 1건에 대응하는 새 라인아이템을 만든다.
	 *
	 * <p>정산액 산출이 마켓마다 다르다 — 11번가는 마켓 실측값({@code stlPlnAmt})을 우선하고,
	 * 없을 때만 요율로 추정한다. 송장은 넣지 않는다(배송이 단일 원본, D-133).
	 *
	 * @param productId 골격이 이미 해석해 둔 값 — 구현은 다시 해석하지 않는다
	 */
	OrderLineItem createLineItem(MarketLineItemDto dto, Long orderId, Long productId);

	/**
	 * 이 상품주문의 정산액. 산출 근거가 없으면 {@code null}.
	 *
	 * <p>D-160에서 분리했다. 종전에는 {@link #createLineItem} 안에만 있어서 <b>생성 시점에 한 번</b>만
	 * 계산됐고, 그래서 거짓 취소가 정산액을 0으로 내린 뒤에는 되돌릴 방법이 없었다(2026-08-08 라이브 사고).
	 * 골격이 복구 규율을 소유하려면 금액 산출을 따로 부를 수 있어야 한다.
	 *
	 * <p>{@code null}을 돌려주는 것은 정직한 답이다 — 근거가 없으면 값을 지어내지 않는다.
	 */
	java.math.BigDecimal settlementAmount(MarketLineItemDto dto);
}
