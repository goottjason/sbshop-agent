package com.sbshop.agent.core.domain.order.repository;

import com.sbshop.agent.core.domain.order.OrderLineItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderLineItemRepository extends JpaRepository<OrderLineItem, Long>, OrderLineItemRepositoryCustom {
	List<OrderLineItem> findByOrderId(Long orderId);

	List<OrderLineItem> findByOrderIdIn(List<Long> orderIds);

	List<OrderLineItem> findBySourcingData_SourcingOrderNo(String sourcingOrderNo);

	/** 동기화 매칭 키 — 배열 순서가 아니라 마켓 상품주문번호로 찾는다. */
	java.util.Optional<OrderLineItem> findByOrderIdAndMarketLineItemNo(Long orderId, String marketLineItemNo);

	List<OrderLineItem> findByShipmentId(Long shipmentId);

	/** 배송에 속하지 않은 라인아이템 — 3계층 전환 이전에 저장된 옛 행이다(6단계 백필 대상). */
	List<OrderLineItem> findByShipmentIdIsNull();

	/**
	 * 최근 판매 실적을 브랜드별로 집계한다 — 신규 상품 추천의 "자사 이력" 신호.
	 * 우리 고객이 이미 사는 브랜드는 새 상품도 팔릴 확률이 높다.
	 *
	 * @return [브랜드, 주문건수, 판매수량]
	 */
	@org.springframework.data.jpa.repository.Query("""
		SELECT p.brand, COUNT(li), COALESCE(SUM(li.quantity), 0)
		FROM OrderLineItem li
		JOIN com.sbshop.agent.core.domain.product.Product p ON p.id = li.productId
		WHERE li.createdAt >= :since AND p.brand IS NOT NULL AND p.brand <> ''
		GROUP BY p.brand
		""")
	List<Object[]> aggregateBrandSalesSince(
		@org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since);

	/**
	 * 최근 판매 실적을 상품 카테고리별로 집계한다.
	 *
	 * @return [카테고리, 주문건수, 판매수량]
	 */
	@org.springframework.data.jpa.repository.Query("""
		SELECT p.category, COUNT(li), COALESCE(SUM(li.quantity), 0)
		FROM OrderLineItem li
		JOIN com.sbshop.agent.core.domain.product.Product p ON p.id = li.productId
		WHERE li.createdAt >= :since AND p.category IS NOT NULL
		GROUP BY p.category
		""")
	List<Object[]> aggregateCategorySalesSince(
		@org.springframework.data.repository.query.Param("since") java.time.LocalDateTime since);
}
