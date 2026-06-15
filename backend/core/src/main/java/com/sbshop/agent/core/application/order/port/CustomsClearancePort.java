package com.sbshop.agent.core.application.order.port;

import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;

import java.util.List;
import java.util.Map;

public interface CustomsClearancePort {
	/**
	 * Checks multiple orders' customs clearance information in bulk.
	 * @param orders List of orders to verify
	 * @return Map of Order ID to its verified CustomsStatus
	 */
	Map<Long, CustomsStatus> verifyBulk(List<Order> orders);
}
