package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.CustomsVerificationResult;
import com.sbshop.agent.core.application.order.port.CustomsClearancePort;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomsBatchProcessor {
	private final OrderRepository orderRepository;
	private final CustomsClearancePort customsClearancePort;

	@Transactional
	public void processBatch(List<Order> batch) {
		Map<Long, CustomsVerificationResult> resultMap = customsClearancePort.verifyBulk(batch);
		for (Order order : batch) {
			CustomsVerificationResult result = resultMap.getOrDefault(order.getId(),
				CustomsVerificationResult.pending());
			order.updateCustomsStatus(result.getStatus(), result.getVerifiedPerson());
		}
		orderRepository.saveAll(batch);
	}
}
