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
}
