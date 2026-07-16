package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.worker.service.EmailFetcherService;
import com.sbshop.agent.core.application.order.service.SmartStoreOrderSyncService;
import com.sbshop.agent.core.application.order.service.CoupangOrderSyncService;
import com.sbshop.agent.core.application.order.service.ElevenstOrderSyncService;
import com.sbshop.agent.core.application.order.service.CustomsOrderSyncService;
import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSyncScheduler {

	// EMAIL은 sync 서비스가 아니라 스케줄러가 직접 EmailFetcherService를 호출하므로 여기서 상태를 기록한다.
	// 수동 트리거(EmailFetchController)도 동일 키(SyncMarketKeys.EMAIL)를 써 상태 계약을 일관되게 유지한다(F-MISC-19).
	// 나머지 마켓 상태 기록은 각 sync 서비스가 자기 async 스레드 안에서 직접 수행한다(F-SYNC-2).
	private static final String EMAIL = SyncMarketKeys.EMAIL;

	private final EmailFetcherService emailFetcherService;
	private final SmartStoreOrderSyncService smartStoreOrderSyncService;
	private final CoupangOrderSyncService coupangOrderSyncService;
	private final ElevenstOrderSyncService elevenstOrderSyncService;
	private final com.sbshop.agent.core.application.order.service.Cafe24OrderSyncService cafe24OrderSyncService;
	private final CustomsOrderSyncService customsOrderSyncService;
	private final SyncStatusService syncStatusService;

	// 매시 0분, 30분 - 이메일 IMAP 주문 수집
	@Scheduled(cron = "0 0/30 * * * ?")
	public void syncOrders() {
		log.info("IMAP 이메일 주문 동기화 시작...");
		syncStatusService.markRunning(EMAIL);
		try {
			emailFetcherService.fetchAndProcessEmails();
			syncStatusService.markCompleted(EMAIL);
			log.info("IMAP 이메일 주문 동기화 완료.");
		} catch (Exception e) {
			syncStatusService.markFailed(EMAIL, e.getMessage());
			log.error("IMAP 이메일 주문 동기화 실패: {}", e.getMessage());
		}
	}

	// 매시 5분, 35분 - 쿠팡 주문 동기화
	@Scheduled(cron = "0 5/30 * * * ?")
	public void syncCoupangOrders() {
		// 상태 기록은 CoupangOrderSyncService가 async 스레드에서 직접 수행(F-SYNC-2). 스케줄러는 호출만.
		log.info("쿠팡 주문 동기화 트리거...");
		coupangOrderSyncService.syncCoupangOrders();
	}

	// 매시 10분, 40분 - G마켓/옥션 주문 동기화 (Selenium ESM+ → Cafe24 주문 API로 선회)
	@Scheduled(cron = "0 10/30 * * * ?")
	public void syncEsmplusOrders() {
		// 상태 기록은 Cafe24OrderSyncService가 async 스레드에서 직접 수행(F-SYNC-2). 스케줄러는 호출만.
		log.info("G마켓/옥션(Cafe24 주문API) 동기화 트리거...");
		cafe24OrderSyncService.syncCafe24Orders();
	}

	// 매시 15분, 45분 - 스마트스토어 주문 동기화
	@Scheduled(cron = "0 15/30 * * * ?")
	public void syncSmartStoreOrders() {
		// 상태 기록은 SmartStoreOrderSyncService가 async 스레드에서 직접 수행(F-SYNC-2). 스케줄러는 호출만.
		log.info("스마트스토어 주문 동기화 트리거...");
		smartStoreOrderSyncService.syncSmartStoreOrders();
	}

	// 매시 20분, 50분 - 11번가 주문 동기화
	@Scheduled(cron = "0 20/30 * * * ?")
	public void syncElevenstOrders() {
		// 상태 기록은 ElevenstOrderSyncService가 async 스레드에서 직접 수행(F-SYNC-2). 스케줄러는 호출만.
		log.info("11번가 주문 동기화 트리거...");
		elevenstOrderSyncService.syncElevenstOrders();
	}

	// 매일 새벽 2시 - 쿠팡 정산 데이터 동기화
	@Scheduled(cron = "0 0 2 * * ?")
	public void syncCoupangSettlement() {
		// 상태 기록은 CoupangOrderSyncService가 async 스레드에서 직접 수행(F-SYNC-2). 스케줄러는 호출만.
		log.info("쿠팡 정산 데이터 동기화 트리거...");
		coupangOrderSyncService.syncCoupangSettlement();
	}

	// 매 시간 정각 - 통관 상태 동기화 (GSI Express 검증)
	@Scheduled(cron = "0 0 * * * ?")
	public void syncCustomsStatus() {
		// 상태 기록은 CustomsOrderSyncService가 직접 수행(F-SYNC-2). 스케줄러는 호출만(예외는 서비스가 로깅 후 rethrow).
		log.info("통관 상태 동기화 트리거...");
		try {
			customsOrderSyncService.syncCustomsStatus();
		} catch (Exception e) {
			log.error("통관 상태 동기화 실패: {}", e.getMessage());
		}
	}
}
