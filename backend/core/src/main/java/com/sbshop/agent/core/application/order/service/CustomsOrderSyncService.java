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

	/**
	 * 통관 상태 동기화 프로세스
	 *
	 * 흐름:
	 * 1. 통관번호가 없거나(空白), 이미 VALID인 주문은 스킵
	 * 2. 통관번호가 있고 PENDING이거나 INVALID 상태인 주문만 대상
	 * 3. GSI Express에서 벌크 검증 (30개씩)
	 * 4. 결과를 주문에 반영 (상태 + 검증된 사람)
	 */
	@Transactional
	public void syncCustomsStatus() {
		log.info("Starting customs status sync process via GSI Scraper...");

		// 1. 통관 검증 대상: PENDING 또는 INVALID 상태인 주문
		//    - PENDING: 통관번호 입력 후 아직 검증 안 된 경우
		//    - INVALID_*: 이전 검증에서 실패한 경우 (재검증)
		List<Order> targetOrders = orderRepository.findByCustomsData_CustomsStatusIn(
			List.of(
				CustomsStatus.PENDING,
				CustomsStatus.INVALID_PCCC,
				CustomsStatus.INVALID_PHONE,
				CustomsStatus.INVALID_ZIPCODE
			));

		log.info("Found {} candidate orders with PENDING/INVALID_* customs status",
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

			// 3. 결과 반영 (상태 + 검증된 사람)
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
