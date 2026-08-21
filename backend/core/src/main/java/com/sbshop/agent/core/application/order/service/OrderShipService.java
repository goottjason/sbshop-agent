package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.BulkShipResult;
import com.sbshop.agent.core.application.order.dto.OrderShipOutcome;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderShipService {
	private final OrderShipProcessor orderShipProcessor;

	public BulkShipResult bulkShipOrders(List<Long> orderIds) {
		int successCount = 0;
		int skippedCount = 0;
		List<Long> failedIds = new ArrayList<>();
		List<String> errors = new ArrayList<>();

		if (orderIds == null) {
			return BulkShipResult.builder()
				.successCount(0).failedCount(0).skippedCount(0)
				.failedIds(failedIds).errors(null)
				.build();
		}

		for (Long orderId : orderIds) {
			OrderShipOutcome outcome = orderShipProcessor.shipSingleOrder(orderId);
			if (outcome.isFailed()) {
				failedIds.add(orderId);
				errors.add(outcome.getErrorMessage());
			} else if (outcome.isShipped()) {
				successCount++;
			} else {
				skippedCount++;
			}
		}

		return BulkShipResult.builder()
			.successCount(successCount)
			.failedCount(failedIds.size())
			.skippedCount(skippedCount)
			.failedIds(failedIds)
			.errors(errors.isEmpty() ? null : errors)
			.build();
	}
}
