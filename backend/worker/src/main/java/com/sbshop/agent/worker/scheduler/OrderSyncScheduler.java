package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.order.service.Cafe24OrderSyncService;
import com.sbshop.agent.core.application.order.service.CoupangOrderSyncService;
import com.sbshop.agent.core.application.order.service.CustomsOrderSyncService;
import com.sbshop.agent.core.application.order.service.ElevenstOrderSyncService;
import com.sbshop.agent.core.application.order.service.SmartStoreOrderSyncService;
import com.sbshop.agent.core.application.sync.SyncMarketKeys;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.worker.service.EmailFetcherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSyncScheduler {

	private static final String EMAIL = SyncMarketKeys.EMAIL;

	private final EmailFetcherService emailFetcherService;
	private final SmartStoreOrderSyncService smartStoreOrderSyncService;
	private final CoupangOrderSyncService coupangOrderSyncService;
	private final ElevenstOrderSyncService elevenstOrderSyncService;
	private final Cafe24OrderSyncService cafe24OrderSyncService;
	private final CustomsOrderSyncService customsOrderSyncService;
	private final SyncStatusService syncStatusService;

	@Scheduled(cron = "0 0/30 * * * ?", zone = "Asia/Seoul")
	public void syncOrders() {
		log.info("IMAP 이메일 주문 동기화 시작...");
		syncStatusService.markRunning(EMAIL);
		try {
			boolean executed = emailFetcherService.fetchAndProcessEmails();
			if (executed) {
				syncStatusService.markCompleted(EMAIL);
				log.info("IMAP 이메일 주문 동기화 완료.");
			} else {
				log.info("IMAP 이메일 주문 동기화 스킵 — 다른 실행이 진행 중.");
			}
		} catch (Exception e) {
			syncStatusService.markFailed(EMAIL, e.getMessage());
			log.error("IMAP 이메일 주문 동기화 실패: {}", e.getMessage());
		}
	}

	@Scheduled(cron = "0 5/30 * * * ?", zone = "Asia/Seoul")
	public void syncCoupangOrders() {
		log.info("쿠팡 주문 동기화 트리거...");
		coupangOrderSyncService.syncCoupangOrders();
	}

	@Scheduled(cron = "0 10/30 * * * ?", zone = "Asia/Seoul")
	public void syncEsmplusOrders() {
		log.info("G마켓/옥션(Cafe24 주문API) 동기화 트리거...");
		cafe24OrderSyncService.syncCafe24Orders();
	}

	@Scheduled(cron = "0 15/30 * * * ?", zone = "Asia/Seoul")
	public void syncSmartStoreOrders() {
		log.info("스마트스토어 주문 동기화 트리거...");
		smartStoreOrderSyncService.syncSmartStoreOrders();
	}

	@Scheduled(cron = "0 20/30 * * * ?", zone = "Asia/Seoul")
	public void syncElevenstOrders() {
		log.info("11번가 주문 동기화 트리거...");
		elevenstOrderSyncService.syncElevenstOrders();
	}

	@Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Seoul")
	public void syncCoupangSettlement() {
		log.info("쿠팡 정산 데이터 동기화 트리거...");
		coupangOrderSyncService.syncCoupangSettlement();
	}

	@Scheduled(cron = "0 0 * * * ?", zone = "Asia/Seoul")
	public void syncCustomsStatus() {
		log.info("통관 상태 동기화 트리거...");
		try {
			customsOrderSyncService.syncCustomsStatus();
		} catch (Exception e) {
			log.error("통관 상태 동기화 실패: {}", e.getMessage());
		}
	}
}
