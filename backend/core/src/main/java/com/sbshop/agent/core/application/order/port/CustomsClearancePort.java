package com.sbshop.agent.core.application.order.port;

import com.sbshop.agent.core.application.order.dto.CustomsVerificationResult;
import com.sbshop.agent.core.domain.order.Order;

import java.util.List;
import java.util.Map;

public interface CustomsClearancePort {
	Map<Long, CustomsVerificationResult> verifyBulk(List<Order> orders);
}
