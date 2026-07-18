package com.sbshop.agent.infrastructure.repository.order;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepositoryCustom;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

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
			.where(
				orderLineItem.sourcingData.sourcingVendor.eq("IHB")
					.and(orderLineItem.sourcingData.sourcingOrderNo.isNotNull())
					.and(orderLineItem.sourcingData.sourcingOrderNo.ne(""))
					.and(shipmentEmailNeeded()))
			.fetch();
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
