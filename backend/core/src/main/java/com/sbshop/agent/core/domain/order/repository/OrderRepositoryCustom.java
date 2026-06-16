package com.sbshop.agent.core.domain.order.repository;

import com.sbshop.agent.core.application.order.dto.OrderSearchCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.sbshop.agent.core.application.order.dto.OrderDetailDto;

public interface OrderRepositoryCustom {
	Page<OrderDetailDto> searchOrderGrid(OrderSearchCondition condition,
		Pageable pageable);
}
