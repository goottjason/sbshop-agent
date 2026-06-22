package com.sbshop.agent.infrastructure.repository.order;

import static com.sbshop.agent.core.domain.order.QOrder.order;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sbshop.agent.core.application.order.dto.OrderDetailDto;
import com.sbshop.agent.core.application.order.dto.OrderDetailDto.OrderLineItemDetailDto;
import com.sbshop.agent.core.application.order.dto.OrderSearchCondition;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.QMarketRegistration;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.QOrderLineItem;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderRepositoryCustom;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.QProduct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	@Override
	public Page<OrderDetailDto> searchOrderGrid(OrderSearchCondition condition,
		Pageable pageable) {
		QOrderLineItem qLineItem = QOrderLineItem.orderLineItem;
		QProduct qProduct = QProduct.product;
		QMarketRegistration qReg = QMarketRegistration.marketRegistration;

		// 1. Fetch Orders
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

		// 2. Fetch LineItems + Products + MarketRegistrations for these Orders (single join query)
		List<Long> orderIds = orders.stream().map(Order::getId).toList();

		List<com.querydsl.core.Tuple> tuples = orderIds.isEmpty() ? List.of()
			: queryFactory
				.select(qLineItem, qProduct, qReg)
				.from(qLineItem)
				.leftJoin(qProduct).on(qLineItem.productId.eq(qProduct.id))
				.leftJoin(qReg).on(qReg.productId.eq(qLineItem.productId))
				.where(qLineItem.orderId.in(orderIds))
				.fetch();

		// 3. Group tuples by orderId
		Map<Long, List<com.querydsl.core.Tuple>> tuplesByOrderId = tuples.stream()
			.collect(Collectors.groupingBy(t -> t.get(qLineItem).getOrderId()));

		// 4. Construct hierarchical DTOs
		List<OrderDetailDto> dtoList = orders.stream().map(o -> {
			List<com.querydsl.core.Tuple> orderTuples = tuplesByOrderId.getOrDefault(o.getId(), List.of());
			// Group by lineItem id to handle products with multiple market registrations
			Map<Long, List<com.querydsl.core.Tuple>> byLineItemId = orderTuples.stream()
				.collect(Collectors.groupingBy(t -> t.get(qLineItem).getId()));
			List<OrderLineItemDetailDto> items = byLineItemId.values().stream().map(liTuples -> {
				com.querydsl.core.Tuple first = liTuples.get(0);
				OrderLineItem li = first.get(qLineItem);
				Product p = first.get(qProduct);
				MarketRegistration reg = liTuples.stream()
					.map(t -> t.get(qReg))
					.filter(r -> r != null && r.getMarketType() == o.getMarketType())
					.findFirst()
					.orElse(null);
				return OrderLineItemDetailDto.builder()
					.lineItem(li)
					.product(p)
					.marketRegistration(reg)
					.build();
			}).toList();
			return OrderDetailDto.builder()
				.order(o)
				.lineItems(items)
				.build();
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

	private BooleanExpression marketTypeIn(java.util.List<MarketType> marketTypes) {
		return marketTypes != null && !marketTypes.isEmpty() ? order.marketType.in(marketTypes) : null;
	}

	private BooleanExpression shippingStatusIn(List<ShippingStatus> statuses) {
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
			.or(order.customsData.customsClearanceNo.contains(keyword))
			.or(QProduct.product.productName.productName.contains(keyword))
			.or(QProduct.product.productName.originalName.contains(keyword))
			.or(QProduct.product.sbCode.contains(keyword))
			.or(QOrderLineItem.orderLineItem.shippingData.trackingNo.contains(keyword));
	}

	private BooleanExpression dateBetween(LocalDateTime startDate, LocalDateTime endDate) {
		if (startDate != null && endDate != null) {
			return order.orderDate.between(startDate, endDate);
		}
		return null;
	}
}
