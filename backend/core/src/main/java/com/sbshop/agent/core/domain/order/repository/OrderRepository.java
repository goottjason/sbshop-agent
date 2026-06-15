package com.sbshop.agent.core.domain.order.repository;

import com.sbshop.agent.core.domain.order.Order;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long>, OrderRepositoryCustom {
	Optional<Order> findByMarketOrderNo(String marketOrderNo);

	java.util.List<Order> findByMarketType(
		com.sbshop.agent.core.domain.order.enums.MarketType marketType);

	java.util.List<Order> findByCustomsData_CustomsStatusIn(
		java.util.List<com.sbshop.agent.core.domain.order.enums.CustomsStatus> statuses);
}
