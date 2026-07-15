package com.sbshop.agent.core.application.order.service;

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

	private final OrderRepository orderRepository;
	private final com.sbshop.agent.core.application.sync.SyncStatusService syncStatusService;
	private final CustomsBatchProcessor customsBatchProcessor;

	/**
	 * 통관 상태 동기화 프로세스
	 *
	 * 흐름:
	 * 1. 통관번호가 없거나(空白), 이미 VALID인 주문은 스킵
	 * 2. 통관번호가 있고 PENDING이거나 INVALID 상태인 주문만 대상
	 * 3. GSI Express에서 벌크 검증 (30개씩)
	 * 4. 결과를 주문에 반영 (상태 + 검증된 사람)
	 *
	 * <p>F-SYNC-19: 이 오케스트레이션 메서드는 <b>@Transactional이 아니다</b>. 배치 사이의
	 * {@code Thread.sleep()}이 하나의 긴 트랜잭션을 물어 DB 커넥션·락을 장기 점유하던 문제를 없애기 위해,
	 * 실제 검증·상태 갱신·저장은 배치 단위 짧은 트랜잭션({@link CustomsBatchProcessor#processBatch})으로
	 * 분리하고, sleep은 트랜잭션 밖(이 루프)에서 수행한다. 배치마다 독립 커밋되므로 중간 실패 시에도
	 * 이전 배치의 진행은 보존된다(F-SYNC-20).
	 */
	public void syncCustomsStatus() {
		log.info("GSI Scraper를 통한 통관 상태 동기화 프로세스 시작...");
		// F-SYNC-2: 동기 메서드지만 일관성을 위해 자기 상태를 스스로 기록(스케줄러에서 이동).
		syncStatusService.markRunning(com.sbshop.agent.core.application.sync.SyncMarketKeys.CUSTOMS);
		try {
			// 1. 통관 검증 대상: PENDING 또는 INVALID 상태인 주문
			//    - PENDING: 통관번호 입력 후 아직 검증 안 된 경우
			//    - INVALID_*: 이전 검증에서 실패한 경우 (재검증)
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
					com.sbshop.agent.core.application.sync.SyncMarketKeys.CUSTOMS);
				return;
			}

			// 2. GSI Express 사이트를 통한 벌크 검증 (30개씩 끊어서 요청)
			//    각 배치는 CustomsBatchProcessor의 @Transactional 메서드에서 독립 커밋되며,
			//    이 루프(오케스트레이션) 자체는 트랜잭션 밖이라 sleep이 트랜잭션을 물지 않는다.
			int batchSize = 30;
			for (int i = 0; i < targetOrders.size(); i += batchSize) {
				int end = Math.min(i + batchSize, targetOrders.size());
				List<Order> batch = targetOrders.subList(i, end);

				log.info("배치 {} ~ {} / {}건의 통관 상태 검증 중", i + 1, end, targetOrders.size());
				customsBatchProcessor.processBatch(batch);

				// 배치 사이에 약간의 딜레이를 주어 서버에 무리가 가지 않도록 함 (트랜잭션 밖)
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					log.warn("통관 동기화 배치 스레드 중단됨");
				}
			}

			log.info("통관 상태 동기화 프로세스 완료.");
			syncStatusService.markCompleted(
				com.sbshop.agent.core.application.sync.SyncMarketKeys.CUSTOMS);
		} catch (RuntimeException e) {
			log.error("통관 상태 동기화 실패: {}", e.getMessage(), e);
			syncStatusService.markFailed(
				com.sbshop.agent.core.application.sync.SyncMarketKeys.CUSTOMS, e.getMessage());
			throw e;
		}
	}
}
