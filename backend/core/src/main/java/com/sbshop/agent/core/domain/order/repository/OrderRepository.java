package com.sbshop.agent.core.domain.order.repository;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long>, OrderRepositoryCustom {
	Optional<Order> findByMarketOrderNo(String marketOrderNo);

	List<Order> findByMarketType(MarketType marketType);

	List<Order> findByCustomsData_CustomsStatusIn(List<CustomsStatus> statuses);

	long countByMarketTypeInAndOrderDateGreaterThanEqual(List<MarketType> marketTypes, LocalDateTime from);
}
