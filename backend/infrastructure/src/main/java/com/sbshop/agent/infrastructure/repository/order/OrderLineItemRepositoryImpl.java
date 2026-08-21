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

	private BooleanExpression iherbWithOrderNo() {
		return orderLineItem.sourcingData.sourcingVendor.eq("IHB")
			.and(orderLineItem.sourcingData.sourcingOrderNo.isNotNull())
			.and(orderLineItem.sourcingData.sourcingOrderNo.ne(""));
	}

	BooleanExpression purchaseAmountMissing() {
		return orderLineItem.sourcingData.sourcingAmount.isNull()
			.or(orderLineItem.sourcingData.sourcingAmount.loe(BigDecimal.ZERO));
	}

	BooleanExpression shipmentEmailNeeded() {
		return orderLineItem.shippingData.shippingStatus.in(
			ShippingStatus.PREPARING, ShippingStatus.DISPATCHED, ShippingStatus.SHIPPED);
	}
}
