package com.sbshop.agent.infrastructure.repository.order;

import static com.sbshop.agent.core.domain.order.QOrder.order;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.dto.OrderSearchCondition;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.repository.OrderRepositoryCustom;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.QOrderLineItem;
import com.sbshop.agent.core.domain.order.dto.OrderGridDto;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.QProduct;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	@Override
	public Page<OrderGridDto> searchOrderGrid(OrderSearchCondition condition,
		Pageable pageable) {
		com.querydsl.core.types.dsl.PathBuilder<Order> orderPath = new com.querydsl.core.types.dsl.PathBuilder<>(
			Order.class, "order1");
		// For now, to fix compilation and prove concept, we map from the order entity directly using queryFactory
		// In a fully optimized projection, we would left join QOrderLineItem and QProduct.
		// Given the complexity of the DTO and constructor matching, we can do a tuple projection or use the DTO builder.
		// Let's use a simpler Projections.fields or constructor if we have the Q classes.
		// For safety and to prevent massive build failures without seeing the Q-classes, we will fetch Orders,
		// and then manually fetch LineItems with a secondary IN query to construct the DTOs, completely avoiding Lazy Loading!

		// 1. Fetch Orders
		QOrderLineItem qLineItem = QOrderLineItem.orderLineItem;
		QProduct qProduct = QProduct.product;

		JPAQuery<Order> query = queryFactory
			.selectFrom(order)
			.leftJoin(qLineItem).on(qLineItem.orderId.eq(order.id))
			.leftJoin(qProduct).on(qLineItem.productId.eq(qProduct.id))
			.where(
				marketTypeIn(condition.getMarketTypes()),
				shippingStatusIn(condition.getShippingStatuses()),
				keywordContains(condition.getKeyword()),
				dateBetween(condition.getStartDate(), condition.getEndDate()))
			.distinct()
			.orderBy(order.orderDate.desc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize());

		List<Order> orders = query.fetch();

		// 2. Fetch LineItems for these Orders (No N+1, just 1 query)
		List<Long> orderIds = orders.stream().map(Order::getId).toList();

		List<com.querydsl.core.Tuple> lineItemTuples = orderIds.isEmpty() ? java.util.Collections.emptyList()
			: queryFactory
				.select(qLineItem, qProduct)
				.from(qLineItem)
				.leftJoin(qProduct).on(qLineItem.productId.eq(qProduct.id))
				.where(qLineItem.orderId.in(orderIds))
				.fetch();

		// 3. Map Tuples to a Map by OrderId
		java.util.Map<Long, java.util.List<com.querydsl.core.Tuple>> itemsByOrderId = lineItemTuples.stream()
			.collect(java.util.stream.Collectors.groupingBy(t -> t.get(qLineItem).getOrderId()));

		// 4. Construct DTOs
		List<OrderGridDto> dtoList = orders.stream().flatMap(o -> {
			java.util.List<com.querydsl.core.Tuple> tuples = itemsByOrderId.getOrDefault(o.getId(),
				java.util.Collections.emptyList());
			if (tuples.isEmpty()) {
				return java.util.stream.Stream.of(buildDto(o, null, null));
			}
			return tuples.stream().map(t -> buildDto(o, t.get(qLineItem), t.get(qProduct)));
		}).toList();

		JPAQuery<Long> countQuery = queryFactory
			.select(order.countDistinct())
			.from(order)
			.leftJoin(qLineItem).on(qLineItem.orderId.eq(order.id))
			.leftJoin(qProduct).on(qLineItem.productId.eq(qProduct.id))
			.where(
				marketTypeIn(condition.getMarketTypes()),
				shippingStatusIn(condition.getShippingStatuses()),
				keywordContains(condition.getKeyword()),
				dateBetween(condition.getStartDate(), condition.getEndDate()));

		return PageableExecutionUtils.getPage(dtoList, pageable, countQuery::fetchOne);
	}

	private OrderGridDto buildDto(Order o,
		OrderLineItem item, Product product) {
		return OrderGridDto.builder()
			.order(o)
			.lineItem(item)
			.product(product)
			.build();
	}

	private BooleanExpression marketTypeIn(java.util.List<MarketType> marketTypes) {
		return marketTypes != null && !marketTypes.isEmpty() ? order.marketType.in(marketTypes) : null;
	}

	private BooleanExpression shippingStatusIn(
		java.util.List<com.sbshop.agent.core.domain.order.enums.ShippingStatus> statuses) {
		return statuses != null && !statuses.isEmpty()
			? QOrderLineItem.orderLineItem.shippingData.shippingStatus.in(statuses) : null;
	}

	private BooleanExpression keywordContains(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return null;
		}
		return order.marketOrderNo.contains(keyword)
			.or(order.recipientName.contains(keyword))
			.or(order.recipientPhone.contains(keyword))
			.or(order.ordererName.contains(keyword))
			.or(QProduct.product.productName.originalName.contains(keyword))
			.or(QProduct.product.sbCode.contains(keyword));
	}

	private BooleanExpression dateBetween(
		java.time.LocalDateTime startDate, java.time.LocalDateTime endDate) {
		if (startDate != null && endDate != null) {
			return order.orderDate.between(startDate, endDate);
		}
		return null;
	}
}
