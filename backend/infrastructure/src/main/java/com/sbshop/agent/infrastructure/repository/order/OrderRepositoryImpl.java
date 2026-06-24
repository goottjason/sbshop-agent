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

		// 1. QueryDSL 엔티티 매핑
		QOrderLineItem qLineItem = QOrderLineItem.orderLineItem;
		QProduct qProduct = QProduct.product;
		QMarketRegistration qReg = QMarketRegistration.marketRegistration;

		// 2. 주문 목록 조회
		JPAQuery<Order> query = queryFactory
			.selectFrom(order)
			.leftJoin(qLineItem).on(qLineItem.orderId.eq(order.id))   // 라인아이템 LEFT JOIN
			.leftJoin(qProduct).on(qLineItem.productId.eq(qProduct.id)) // 상품 LEFT JOIN
			.where(
				marketTypeIn(condition.getMarketTypes()),       // 마켓 타입 필터
				shippingStatusIn(condition.getShippingStatuses()), // 배송상태 필터
				keywordContains(condition.getKeyword()),          // 키워드 검색
				dateBetween(condition.getStartDate(), condition.getEndDate())) // 기간 필터
			.distinct()
			.orderBy(order.orderDate.desc()) // 주문일시 내림차순
			.offset(pageable.getOffset())    // 페이징 offset
			.limit(pageable.getPageSize());  // 페이징 limit

		// 3. 주문 목록 실행
		List<Order> orders = query.fetch();

		// 4. 라인아이템, 상품, 마켓연동 정보 조회 (단일 조인 쿼리)
		List<Long> orderIds = orders.stream().map(Order::getId).toList();

		// 5. 라인아이템 + 상품 + 마켓연동 데이터 조회
		List<com.querydsl.core.Tuple> tuples = orderIds.isEmpty() ? List.of()
			: queryFactory
				.select(qLineItem, qProduct, qReg)
				.from(qLineItem)
				.leftJoin(qProduct).on(qLineItem.productId.eq(qProduct.id))   // 상품 LEFT JOIN
				.leftJoin(qReg).on(qReg.productId.eq(qLineItem.productId))   // 마켓연동 LEFT JOIN
				.where(qLineItem.orderId.in(orderIds)) // 해당 주문들의 라인아이템만 조회
				.fetch();

		// 6. 주문ID별로 튜플 그룹핑
		Map<Long, List<com.querydsl.core.Tuple>> tuplesByOrderId = tuples.stream()
			.collect(Collectors.groupingBy(t -> t.get(qLineItem).getOrderId()));

		// 7. 계층형 DTO 구조 생성
		List<OrderDetailDto> dtoList = orders.stream().map(o -> {

			// 7-1. 해당 주문의 튜플 조회
			List<com.querydsl.core.Tuple> orderTuples = tuplesByOrderId.getOrDefault(o.getId(), List.of());

			// 7-2. 라인아이템별로 그룹핑 (동일 상품의 마켓연동 여러 건 처리)
			Map<Long, List<com.querydsl.core.Tuple>> byLineItemId = orderTuples.stream()
				.collect(Collectors.groupingBy(t -> t.get(qLineItem).getId()));

			// 7-3. 라인아이템 상세 DTO 생성
			List<OrderLineItemDetailDto> items = byLineItemId.values().stream().map(liTuples -> {
				com.querydsl.core.Tuple first = liTuples.get(0);
				OrderLineItem li = first.get(qLineItem);   // 라인아이템
				Product p = first.get(qProduct);            // 상품

				// 7-4. 마켓 타입에 맞는 마켓연동 정보 조회
				MarketRegistration reg = liTuples.stream()
					.map(t -> t.get(qReg))
					.filter(r -> r != null && r.getMarketType() == o.getMarketType())
					.findFirst()
					.orElse(null);

				// 7-5. 라인아이템 상세 DTO 반환
				return OrderLineItemDetailDto.builder()
					.lineItem(li)
					.product(p)
					.marketRegistration(reg)
					.build();
			}).toList();

			// 7-6. 주문 상세 DTO 반환
			return OrderDetailDto.builder()
				.order(o)
				.lineItems(items)
				.build();
		}).toList();

		// 8. 전체 개수 조회 (카운트 쿼리)
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

		// 9. 페이징 적용하여 결과 반환
		return PageableExecutionUtils.getPage(dtoList, pageable, countQuery::fetchOne);
	}

	/** 마켓 타입 필터 */
	private BooleanExpression marketTypeIn(List<MarketType> marketTypes) {
		return marketTypes != null && !marketTypes.isEmpty() ? order.marketType.in(marketTypes) : null;
	}

	/** 배송상태 필터 */
	private BooleanExpression shippingStatusIn(List<ShippingStatus> statuses) {
		return statuses != null && !statuses.isEmpty()
			? QOrderLineItem.orderLineItem.shippingData.shippingStatus.in(statuses) : null;
	}

	/** 키워드 검색 (주문번호, 수취인, 주문자, 통관번호, 상품명, 송장번호) */
	private BooleanExpression keywordContains(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return null;
		}
		return order.marketOrderNo.contains(keyword)           // 주문번호
			.or(order.recipientName.contains(keyword))         // 수취인 이름
			.or(order.recipientPhone.contains(keyword))        // 수취인 전화번호
			.or(order.ordererName.contains(keyword))           // 주문자 이름
			.or(order.customsData.customsClearanceNo.contains(keyword)) // 통관번호
			.or(QProduct.product.productName.productName.contains(keyword))  // 상품명
			.or(QProduct.product.productName.originalName.contains(keyword)) // 원어명
			.or(QProduct.product.sbCode.contains(keyword))    // SB코드
			.or(QOrderLineItem.orderLineItem.shippingData.trackingNo.contains(keyword)); // 송장번호
	}

	/** 기간 필터 */
	private BooleanExpression dateBetween(LocalDateTime startDate, LocalDateTime endDate) {
		if (startDate != null && endDate != null) {
			return order.orderDate.between(startDate, endDate);
		}
		return null;
	}
}
