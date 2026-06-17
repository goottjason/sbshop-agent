package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.order.dto.CustomsVerificationResult;
import com.sbshop.agent.core.application.order.port.CustomsClearancePort;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomsOrderSyncService {

	private final OrderRepository orderRepository;
	private final CustomsClearancePort customsClearancePort;

	@Transactional
	public void syncCustomsStatus() {
		log.info("Starting customs status sync process via GSI Scraper...");

		// 1. 대기중(PENDING), 불일치(INVALID), 전화번호불일치(VALID_PHONE_MISMATCH) 상태인 주문만 조회
		List<Order> targetOrders = orderRepository.findByCustomsData_CustomsStatusIn(
			List.of(CustomsStatus.PENDING, CustomsStatus.INVALID, CustomsStatus.VALID_PHONE_MISMATCH));

		log.info("Found {} candidate orders with PENDING/INVALID/VALID_PHONE_MISMATCH customs status",
			targetOrders.size());

		if (targetOrders.isEmpty()) {
			return;
		}

		// 2. GSI Express 사이트를 통한 벌크 검증 (30개씩 끊어서 요청)
		int batchSize = 30;
		for (int i = 0; i < targetOrders.size(); i += batchSize) {
			int end = Math.min(i + batchSize, targetOrders.size());
			List<Order> batch = targetOrders.subList(i, end);

			log.info("Verifying customs status for batch {} to {} (out of {})", i + 1, end, targetOrders.size());
			Map<Long, CustomsVerificationResult> resultMap = customsClearancePort.verifyBulk(batch);

			// 4. 결과 반영 (상태 + 검증된 사람)
			for (Order order : batch) {
				CustomsVerificationResult result = resultMap.getOrDefault(order.getId(),
					CustomsVerificationResult.pending());
				order.updateCustomsStatus(result.getStatus(), result.getVerifiedPerson());
			}

			// 배치 사이에 약간의 딜레이를 주어 서버에 무리가 가지 않도록 함
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("Customs sync batch thread interrupted");
			}
		}

		log.info("Customs status sync process completed.");
	}
}
