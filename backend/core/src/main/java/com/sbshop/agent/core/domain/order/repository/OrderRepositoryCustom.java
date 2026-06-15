package com.sbshop.agent.core.domain.order.repository;

import com.sbshop.agent.core.domain.order.dto.OrderSearchCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.sbshop.agent.core.domain.order.dto.OrderGridDto;

public interface OrderRepositoryCustom {
	Page<OrderGridDto> searchOrderGrid(OrderSearchCondition condition,
		Pageable pageable);
}
