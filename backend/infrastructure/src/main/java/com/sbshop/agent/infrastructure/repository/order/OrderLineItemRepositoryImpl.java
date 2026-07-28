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

	private BooleanExpression shipmentEmailNeeded() {
		// PREPARING 상태: 최초 발송 대기. iHerb 주문번호 존재(외부 조건)가 곧 "구매함"의 신호이므로
		// PurchaseStatus는 게이팅에 쓰지 않는다(구매상태는 유저 수동 관리 필드).
		BooleanExpression preparing = orderLineItem.shippingData.shippingStatus.eq(ShippingStatus.PREPARING);
		// DISPATCHED(쿠팡 DEPARTURE 등 송장 등록됨)/SHIPPED(배송중) 이지만 우리 시스템이 마켓 전송을
		// 확정(trackingSentToMarket)하지 못한 건: 진짜 송장 전송·교정 필요(재시도). 쿠팡 동기화가 만든
		// DISPATCHED 건이 파이프라인에서 누락되던 문제를 해소한다.
		BooleanExpression hasInvoiceNotSynced = orderLineItem.shippingData.shippingStatus
			.in(ShippingStatus.DISPATCHED, ShippingStatus.SHIPPED)
			.and(orderLineItem.shippingData.trackingSentToMarket.isFalse()
				.or(orderLineItem.shippingData.trackingSentToMarket.isNull()));
		return preparing.or(hasInvoiceNotSynced);
	}
}
