package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomsOrderSyncService {
	private static final int VERIFICATION_BATCH_SIZE = 30;
	private static final long BATCH_DELAY_MS = 1000L;

	private final OrderRepository orderRepository;
	private final SyncStatusService syncStatusService;
	private final CustomsBatchProcessor customsBatchProcessor;

	public void syncCustomsStatus() {
		log.info("GSI Scraper를 통한 통관 상태 동기화 프로세스 시작...");
		syncStatusService.markRunning(SyncMarketKeys.CUSTOMS);
		try {
			List<Order> targetOrders = orderRepository.findByCustomsData_CustomsStatusIn(
				List.of(
					CustomsStatus.PENDING,
					CustomsStatus.INVALID_PCCC,
					CustomsStatus.INVALID_PHONE,
					CustomsStatus.INVALID_ZIPCODE));

			log.info("PENDING/INVALID_* 통관 상태를 가진 후보 주문 {}건 발견",
				targetOrders.size());

			if (targetOrders.isEmpty()) {
				syncStatusService.markCompleted(
					SyncMarketKeys.CUSTOMS);
				return;
			}

			int batchSize = VERIFICATION_BATCH_SIZE;
			for (int i = 0; i < targetOrders.size(); i += batchSize) {
				int end = Math.min(i + batchSize, targetOrders.size());
				List<Order> batch = targetOrders.subList(i, end);

				log.info("배치 {} ~ {} / {}건의 통관 상태 검증 중", i + 1, end, targetOrders.size());
				customsBatchProcessor.processBatch(batch);

				try {
					Thread.sleep(BATCH_DELAY_MS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					log.warn("통관 동기화 배치 스레드 중단됨");
				}
			}

			log.info("통관 상태 동기화 프로세스 완료.");
			syncStatusService.markCompleted(
				SyncMarketKeys.CUSTOMS);
		} catch (RuntimeException e) {
			log.error("통관 상태 동기화 실패: {}", e.getMessage(), e);
			syncStatusService.markFailed(
				SyncMarketKeys.CUSTOMS, e.getMessage());
			throw e;
		}
	}
}
