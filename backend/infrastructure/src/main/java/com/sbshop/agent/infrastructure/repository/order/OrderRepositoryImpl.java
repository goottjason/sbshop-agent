package com.sbshop.agent.infrastructure.repository.order;

import static com.sbshop.agent.core.domain.order.QOrder.order;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
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
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderRepositoryCustom;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.QProduct;
import java.time.LocalDateTime;
import java.util.Arrays;
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

	private static final List<ClaimType> ACTIVE_CLAIM_TYPES =
		Arrays.stream(ClaimType.values()).filter(ClaimType::isActive).toList();
	private static final List<ClaimStage> ACTIVE_CLAIM_STAGES =
		Arrays.stream(ClaimStage.values()).filter(ClaimStage::isActive).toList();

	private final JPAQueryFactory queryFactory;
	private final ProductRepository productRepository;
	private final ShipmentRepository shipmentRepository;

	@Override
	public Page<OrderDetailDto> searchOrderGrid(OrderSearchCondition condition,
		Pageable pageable) {

		QOrderLineItem qLineItem = QOrderLineItem.orderLineItem;
		QProduct qProduct = QProduct.product;
		QMarketRegistration qReg = QMarketRegistration.marketRegistration;

		JPAQuery<Order> query = queryFactory
			.select(order)
			.from(order)
			.where(
				marketTypeIn(condition.getMarketTypes()),
				lineMatches(condition.getShippingStatuses(), condition.getClaimTypes(), condition.getClaimStages()),
				purchaseStatusIn(condition.getPurchaseStatuses()),
				customsStatusIn(condition.getCustomsStatuses()),
				keywordContains(condition.getKeyword(), qLineItem, qProduct),
				dateBetween(condition.getStartDate(), condition.getEndDate()),
				stockStatusExists(condition.getStockStatuses()),
				vendorExists(condition.getVendors()))
			.orderBy(order.orderDate.desc())
			.offset(pageable.getOffset())
			.limit(pageable.getPageSize());

		List<Order> orders = query.fetch();

		List<Long> orderIds = orders.stream().map(Order::getId).toList();

		List<OrderLineItem> lineItems = orderIds.isEmpty() ? List.of()
			: queryFactory
				.selectFrom(qLineItem)
				.where(qLineItem.orderId.in(orderIds))
				.fetch();

		List<Long> productIds = lineItems.stream()
			.map(OrderLineItem::getProductId)
			.filter(id -> id != null)
			.distinct()
			.toList();

		Map<Long, Product> productMap = productIds.isEmpty() ? Map.of()
			: productRepository.findAllById(productIds).stream()
				.collect(Collectors.toMap(Product::getId, p -> p));

		List<Long> regProductIds = productIds.isEmpty() ? List.of() : productIds;
		List<MarketRegistration> registrations = regProductIds.isEmpty() ? List.of()
			: queryFactory
				.selectFrom(qReg)
				.where(qReg.productId.in(regProductIds))
				.fetch();

		Map<Long, List<MarketRegistration>> regsByProductId = registrations.stream()
			.collect(Collectors.groupingBy(MarketRegistration::getProductId));

		Map<Long, List<OrderLineItem>> itemsByOrderId = lineItems.stream()
			.collect(Collectors.groupingBy(OrderLineItem::getOrderId));

		List<Long> shipmentIds = lineItems.stream()
			.map(OrderLineItem::getShipmentId)
			.filter(id -> id != null)
			.distinct()
			.toList();

		Map<Long, Shipment> shipmentMap = shipmentIds.isEmpty() ? Map.of()
			: shipmentRepository.findAllById(shipmentIds).stream()
				.collect(Collectors.toMap(Shipment::getId, sh -> sh));

		List<OrderDetailDto> dtoList = orders.stream().map(o -> {
			List<OrderLineItem> orderItems = itemsByOrderId.getOrDefault(o.getId(), List.of());

			List<OrderLineItemDetailDto> items = orderItems.stream().map(li -> {
				Product p = li.getProductId() != null ? productMap.get(li.getProductId()) : null;

				MarketRegistration reg = li.getProductId() != null
					? regsByProductId.getOrDefault(li.getProductId(), List.of()).stream()
						.filter(r -> r.getMarketType() == o.getMarketType())
						.findFirst()
						.orElse(null)
					: null;

				return OrderLineItemDetailDto.builder()
					.lineItem(li)
					.product(p)
					.marketRegistration(reg)
					.shipment(li.getShipmentId() != null ? shipmentMap.get(li.getShipmentId()) : null)
					.build();
			}).toList();

			return OrderDetailDto.builder()
				.order(o)
				.lineItems(items)
				.build();
		}).toList();

		JPAQuery<Long> countQuery = queryFactory
			.select(order.count())
			.from(order)
			.where(
				marketTypeIn(condition.getMarketTypes()),
				lineMatches(condition.getShippingStatuses(), condition.getClaimTypes(), condition.getClaimStages()),
				purchaseStatusIn(condition.getPurchaseStatuses()),
				customsStatusIn(condition.getCustomsStatuses()),
				keywordContains(condition.getKeyword(), qLineItem, qProduct),
				dateBetween(condition.getStartDate(), condition.getEndDate()),
				stockStatusExists(condition.getStockStatuses()),
				vendorExists(condition.getVendors()));

		return PageableExecutionUtils.getPage(dtoList, pageable, countQuery::fetchOne);
	}

	BooleanExpression dateBetween(LocalDateTime startDate, LocalDateTime endDate) {
		if (startDate != null && endDate != null) {
			return order.orderDate.between(startDate, endDate);
		}
		if (startDate != null) {
			return order.orderDate.goe(startDate);
		}
		if (endDate != null) {
			return order.orderDate.loe(endDate);
		}
		return null;
	}

	private BooleanExpression marketTypeIn(List<MarketType> marketTypes) {
		return marketTypes != null && !marketTypes.isEmpty() ? order.marketType.in(marketTypes) : null;
	}

	private BooleanExpression lineMatches(List<ShippingStatus> statuses, List<ClaimType> claimTypes,
		List<ClaimStage> claimStages) {
		QOrderLineItem subLineItem = QOrderLineItem.orderLineItem;
		BooleanBuilder onSameLine = new BooleanBuilder()
			.and(statuses != null && !statuses.isEmpty() ? subLineItem.shippingData.shippingStatus.in(statuses) : null)
			.and(claimTypeIn(claimTypes))
			.and(claimStages != null && !claimStages.isEmpty() ? subLineItem.claimData.claimStage.in(claimStages) : null);
		if (!onSameLine.hasValue()) {
			return null;
		}
		return JPAExpressions
			.selectOne()
			.from(subLineItem)
			.where(subLineItem.orderId.eq(order.id).and(onSameLine))
			.exists();
	}

	private BooleanExpression purchaseStatusIn(List<PurchaseStatus> statuses) {
		if (statuses == null || statuses.isEmpty()) {
			return null;
		}
		QOrderLineItem subLineItem = QOrderLineItem.orderLineItem;
		return JPAExpressions
			.selectOne()
			.from(subLineItem)
			.where(subLineItem.orderId.eq(order.id)
				.and(subLineItem.purchaseStatus.in(statuses)))
			.exists();
	}

	BooleanExpression claimTypeIn(List<ClaimType> claimTypes) {
		if (claimTypes == null || claimTypes.isEmpty()) {
			return null;
		}
		QOrderLineItem subLineItem = QOrderLineItem.orderLineItem;
		List<ClaimType> claimed = claimTypes.stream().filter(ClaimType::isActive).toList();
		BooleanExpression ofType = claimed.isEmpty() ? null : subLineItem.claimData.claimType.in(claimed);
		BooleanExpression noClaim = claimTypes.contains(ClaimType.NONE) ? noActiveClaim(subLineItem) : null;
		if (ofType == null) {
			return noClaim;
		}
		return noClaim == null ? ofType : ofType.or(noClaim);
	}

	private static BooleanExpression noActiveClaim(QOrderLineItem li) {
		return li.claimData.claimType.isNull()
			.or(li.claimData.claimType.notIn(ACTIVE_CLAIM_TYPES))
			.or(li.claimData.claimStage.isNull())
			.or(li.claimData.claimStage.notIn(ACTIVE_CLAIM_STAGES));
	}

	private BooleanExpression customsStatusIn(List<CustomsStatus> statuses) {
		return statuses != null && !statuses.isEmpty()
			? order.customsData.customsStatus.in(statuses) : null;
	}

	private BooleanExpression keywordContains(String keyword, QOrderLineItem qLineItem, QProduct qProduct) {
		if (keyword == null || keyword.isBlank()) {
			return null;
		}
		BooleanExpression orderFields = order.marketOrderNo.contains(keyword)
			.or(order.recipientName.contains(keyword))
			.or(order.recipientPhone.contains(keyword))
			.or(order.ordererName.contains(keyword))
			.or(order.customsData.customsClearanceNo.contains(keyword));

		BooleanExpression lineItemFields = qLineItem.shippingData.trackingNo.contains(keyword);

		BooleanExpression productFields = qProduct.productName.contains(keyword)
			.or(qProduct.originalName.contains(keyword))
			.or(qProduct.sbCode.contains(keyword));

		QOrderLineItem subLineItem = QOrderLineItem.orderLineItem;
		QProduct subProduct = QProduct.product;
		BooleanExpression hasMatchingLineItem = JPAExpressions
			.selectOne()
			.from(subLineItem)
			.leftJoin(subProduct).on(subLineItem.productId.eq(subProduct.id))
			.where(subLineItem.orderId.eq(order.id)
				.and(lineItemFields.or(productFields)))
			.exists();

		return orderFields.or(hasMatchingLineItem);
	}

	private BooleanExpression stockStatusExists(List<String> stockStatuses) {
		if (stockStatuses == null || stockStatuses.isEmpty()) {
			return null;
		}
		QOrderLineItem sli = QOrderLineItem.orderLineItem;
		QProduct sp = QProduct.product;
		return JPAExpressions.selectOne().from(sli)
			.leftJoin(sp).on(sp.id.eq(sli.productId))
			.where(sli.orderId.eq(order.id), sp.stockStatus.stringValue().in(stockStatuses))
			.exists();
	}

	private BooleanExpression vendorExists(List<String> vendors) {
		if (vendors == null || vendors.isEmpty()) {
			return null;
		}
		QOrderLineItem sli = QOrderLineItem.orderLineItem;
		return JPAExpressions.selectOne().from(sli)
			.where(sli.orderId.eq(order.id), sli.sourcingData.sourcingVendor.in(vendors))
			.exists();
	}
}
