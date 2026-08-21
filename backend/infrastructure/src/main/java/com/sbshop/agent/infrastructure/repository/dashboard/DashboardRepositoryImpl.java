package com.sbshop.agent.infrastructure.repository.dashboard;

import static com.sbshop.agent.core.domain.order.QOrder.order;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AggRow;
import com.sbshop.agent.core.domain.dashboard.DashboardRepository;
import com.sbshop.agent.core.domain.order.QOrderLineItem;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.product.QProduct;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DashboardRepositoryImpl implements DashboardRepository {

	private final JPAQueryFactory queryFactory;

	@Override
	public List<AggRow> findRowsBetween(LocalDateTime start, LocalDateTime end) {
		QOrderLineItem li = QOrderLineItem.orderLineItem;
		QProduct p = QProduct.product;
		List<Tuple> rows = queryFactory
			.select(order.id, order.orderDate, order.marketType,
				li.shippingData.shippingStatus,
				li.settlementData.settlementAmount, li.sourcingData.sourcingAmount,
				li.sourcingData.logisticsCost, li.productId,
				p.sbCode, p.productName, li.sourcingData.sourcingVendor, p.stockStatus)
			.from(order)
			.join(li).on(li.orderId.eq(order.id))
			.leftJoin(p).on(p.id.eq(li.productId))
			.where(order.orderDate.goe(start), order.orderDate.loe(end))
			.fetch();
		return rows.stream().map(t -> new AggRow(
			t.get(order.id), t.get(order.orderDate), t.get(order.marketType),
			t.get(li.shippingData.shippingStatus),
			toLong(t.get(li.settlementData.settlementAmount)),
			toLong(t.get(li.sourcingData.sourcingAmount)),
			toLong(t.get(li.sourcingData.logisticsCost)),
			t.get(li.productId), t.get(p.sbCode), t.get(p.productName),
			t.get(li.sourcingData.sourcingVendor),
			t.get(p.stockStatus) != null ? t.get(p.stockStatus).name() : null)).toList();
	}

	private static long toLong(java.math.BigDecimal n) {
		return n == null ? 0L : n.longValue();
	}

	@Override
	public int countByShippingStatusIn(List<ShippingStatus> statuses) {
		QOrderLineItem li = QOrderLineItem.orderLineItem;
		Long c = queryFactory.select(order.id.countDistinct())
			.from(order).join(li).on(li.orderId.eq(order.id))
			.where(li.shippingData.shippingStatus.in(statuses)).fetchOne();
		return c == null ? 0 : c.intValue();
	}

	@Override
	public int countCustomsIssue(List<CustomsStatus> statuses) {
		Long c = queryFactory.select(order.id.countDistinct()).from(order)
			.where(order.customsData.customsStatus.in(statuses)).fetchOne();
		return c == null ? 0 : c.intValue();
	}

	@Override
	public int countOutOfStock() {
		QOrderLineItem li = QOrderLineItem.orderLineItem;
		QProduct p = QProduct.product;
		BooleanExpression open = li.shippingData.shippingStatus.in(
			ShippingStatus.NEW, ShippingStatus.PREPARING, ShippingStatus.DISPATCHED);
		Long c = queryFactory.select(order.id.countDistinct()).from(order)
			.join(li).on(li.orderId.eq(order.id))
			.join(p).on(p.id.eq(li.productId))
			.where(open, p.stockStatus.eq(StockStatus.OUT_OF_STOCK)).fetchOne();
		return c == null ? 0 : c.intValue();
	}

	@Override
	public int countDelayed(LocalDateTime newOnOrBefore, LocalDateTime preparingOnOrBefore) {
		QOrderLineItem li = QOrderLineItem.orderLineItem;
		BooleanExpression delayed = li.shippingData.shippingStatus.eq(ShippingStatus.NEW)
			.and(order.orderDate.loe(newOnOrBefore))
			.or(li.shippingData.shippingStatus.eq(ShippingStatus.PREPARING)
				.and(order.orderDate.loe(preparingOnOrBefore)));
		Long c = queryFactory.select(order.id.countDistinct()).from(order)
			.join(li).on(li.orderId.eq(order.id)).where(delayed).fetchOne();
		return c == null ? 0 : c.intValue();
	}
}
