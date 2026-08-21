package com.sbshop.agent.core.domain.order.repository;

import com.sbshop.agent.core.domain.order.OrderLineItem;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderLineItemRepository extends JpaRepository<OrderLineItem, Long>, OrderLineItemRepositoryCustom {
	List<OrderLineItem> findByOrderId(Long orderId);

	List<OrderLineItem> findByOrderIdIn(List<Long> orderIds);

	List<OrderLineItem> findBySourcingData_SourcingOrderNo(String sourcingOrderNo);

	Optional<OrderLineItem> findByOrderIdAndMarketLineItemNo(Long orderId, String marketLineItemNo);

	List<OrderLineItem> findByShipmentId(Long shipmentId);

	List<OrderLineItem> findByShipmentIdIsNull();

	@Query("""
		SELECT p.brand, COUNT(li), COALESCE(SUM(li.quantity), 0)
		FROM OrderLineItem li
		JOIN com.sbshop.agent.core.domain.product.Product p ON p.id = li.productId
		WHERE li.createdAt >= :since AND p.brand IS NOT NULL AND p.brand <> ''
		GROUP BY p.brand
		""")
	List<Object[]> aggregateBrandSalesSince(
		@Param("since")
		LocalDateTime since);

	@Query("""
		SELECT p.category, COUNT(li), COALESCE(SUM(li.quantity), 0)
		FROM OrderLineItem li
		JOIN com.sbshop.agent.core.domain.product.Product p ON p.id = li.productId
		WHERE li.createdAt >= :since AND p.category IS NOT NULL
		GROUP BY p.category
		""")
	List<Object[]> aggregateCategorySalesSince(
		@Param("since")
		LocalDateTime since);
}
