package com.sbshop.agent.infrastructure.repository.order;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepositoryCustom;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

import static com.sbshop.agent.core.domain.order.QOrder.order;
import static com.sbshop.agent.core.domain.order.QOrderLineItem.orderLineItem;

@Repository
@RequiredArgsConstructor
public class OrderLineItemRepositoryImpl implements OrderLineItemRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	@Override
	public List<Long> findProductIdsByShippingStatus(ShippingStatus status) {
		return queryFactory
			.select(orderLineItem.productId)
			.distinct()
			.from(orderLineItem)
			.join(order).on(orderLineItem.orderId.eq(order.id))
			.where(orderLineItem.shippingData.shippingStatus.eq(status)
				.and(orderLineItem.productId.isNotNull()))
			.fetch();
	}

	@Override
	public List<OrderLineItem> findIherbItemsNeedingEmailProcessing() {
		return queryFactory
			.select(orderLineItem)
			.from(orderLineItem)
			.where(iherbWithOrderNo().and(shipmentEmailNeeded()))
			.fetch();
	}

	@Override
	public List<OrderLineItem> findIherbItemsNeedingPurchaseAmount() {
		return queryFactory
			.select(orderLineItem)
			.from(orderLineItem)
			.where(iherbWithOrderNo().and(purchaseAmountMissing()))
			.fetch();
	}

	/** iHerb 소싱 + 구매주문번호 보유 — 이메일 검색의 공통 전제. */
	private BooleanExpression iherbWithOrderNo() {
		return orderLineItem.sourcingData.sourcingVendor.eq("IHB")
			.and(orderLineItem.sourcingData.sourcingOrderNo.isNotNull())
			.and(orderLineItem.sourcingData.sourcingOrderNo.ne(""));
	}

	/**
	 * 실구매가 미기록 조건. 배송상태를 참조하지 않는다.
	 * 0 이하도 미기록으로 본다 — EmailFetcherService의 멱등 가드(amount &gt; 0 이면 스킵)와 같은 경계.
	 */
	// 테스트 접근을 위해 package-private
	BooleanExpression purchaseAmountMissing() {
		return orderLineItem.sourcingData.sourcingAmount.isNull()
			.or(orderLineItem.sourcingData.sourcingAmount.loe(BigDecimal.ZERO));
	}

	/**
	 * 발송메일에서 송장을 받아야 하는 건 — <b>종결 전 상태면 전부</b>.
	 *
	 * <p>D-144: 종전에는 {@code trackingSentToMarket}이 아닌 건만 담았다. 그 플래그는 "마켓이 송장을
	 * 갖고 있다"는 뜻인데(D-129), 마켓에서 유입된 <b>가송장</b>도 그 플래그를 참으로 만든다 —
	 * 라이브에서 무관한 두 주문(쿠팡 홍경희 · 스토어 안규걸)에 같은 번호 {@code 363092185283}이
	 * 붙어 있었고, 그 번호는 우리 메일함 어디에도 없었다. 큐에서 빠지면 <b>그 주문번호로 메일을
	 * 검색조차 하지 않으므로</b> 진짜 발송메일(iHerb {@code 344163905} → {@code 315399527822})이
	 * 도착해도 영영 교정되지 않는다. 실측 활성 16건 중 12건이 DB 송장 ≠ 발송메일 송장이었다.
	 *
	 * <p>iHerb 주문번호가 있다는 것은 <b>iHerb가 보낸 발송메일이 송장의 진실</b>이라는 뜻이다.
	 * 마켓이 무엇을 갖고 있든 그것은 교정 대상이지 게이트가 아니다. 같은 송장이면
	 * {@code EmailFetcherService.processIherbShipment}가 스킵하므로 재처리 비용도 없다.
	 *
	 * <p>종결(DELIVERED·CANCELED·RETURNED·EXCHANGED)은 담지 않는다 — 배송이 끝난 뒤의 송장 교정은
	 * 마켓이 받아주지 않고, 과거 기록을 바꾸는 일이라 사람의 판단이 필요하다.
	 */
	BooleanExpression shipmentEmailNeeded() {
		return orderLineItem.shippingData.shippingStatus.in(
			ShippingStatus.PREPARING, ShippingStatus.DISPATCHED, ShippingStatus.SHIPPED);
	}
}
