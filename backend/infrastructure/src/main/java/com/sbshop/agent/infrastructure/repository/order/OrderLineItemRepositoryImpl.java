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
		BooleanExpression purchased = orderLineItem.shippingData.shippingStatus.eq(ShippingStatus.PURCHASED);
		// SHIPPED + marketplaceSynced가 false 또는 null: 마켓 미동기화 (재시도 필요)
		BooleanExpression shippedNotSynced = orderLineItem.shippingData.shippingStatus.eq(ShippingStatus.SHIPPED)
			.and(orderLineItem.shippingData.marketplaceSynced.isFalse()
				.or(orderLineItem.shippingData.marketplaceSynced.isNull()));
		return purchased.or(shippedNotSynced);
	}
}
