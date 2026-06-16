package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.worker.service.EmailFetcherService;
import com.sbshop.agent.core.application.order.service.SmartStoreOrderSyncService;
import com.sbshop.agent.core.application.order.service.CoupangOrderSyncService;
import com.sbshop.agent.core.application.order.service.EsmplusOrderSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSyncScheduler {

	private final EmailFetcherService emailFetcherService;
	private final SmartStoreOrderSyncService smartStoreOrderSyncService;
	private final CoupangOrderSyncService coupangOrderSyncService;
	private final EsmplusOrderSyncService esmplusOrderSyncService;

	// Run every 5 minutes (300000 ms)
	@Scheduled(fixedDelay = 300000)
	public void syncOrders() {
		log.info("Starting scheduled IMAP order sync...");
		emailFetcherService.fetchAndProcessEmails();
		log.info("Finished scheduled IMAP order sync.");
	}

	// Run every 1 hour (3600000 ms) - 스마트스토어 주문 동기화
	// 통관번호는 구매확정 전 상태에서만 API 노출되므로 주기적 동기화 필요
	@Scheduled(fixedDelay = 3600000)
	public void syncSmartStoreOrders() {
		log.info("Starting scheduled SmartStore order sync...");
		try {
			smartStoreOrderSyncService.syncSmartStoreOrders();
			log.info("Finished scheduled SmartStore order sync.");
		} catch (Exception e) {
			log.error("Scheduled SmartStore order sync failed: {}", e.getMessage());
		}
	}

	// 매일 새벽 2시 - 쿠팡 정산 데이터 동기화
	// 인식일(배송완료+7일) 이후 확정된 정산금액을 자동 업데이트
	@Scheduled(cron = "0 0 2 * * ?")
	public void syncCoupangSettlement() {
		log.info("Starting scheduled Coupang settlement sync...");
		try {
			coupangOrderSyncService.syncCoupangSettlement();
			log.info("Finished scheduled Coupang settlement sync.");
		} catch (Exception e) {
			log.error("Scheduled Coupang settlement sync failed: {}", e.getMessage());
		}
	}

	// Run every 30 minutes - ESM+(G마켓/옥션) 주문 동기화
	// Selenium 기반 스크래핑이므로 주기적으로 새로운 주문 수집
	@Scheduled(fixedDelay = 1800000)
	public void syncEsmplusOrders() {
		log.info("Starting scheduled ESM+(G마켓/옥션) order sync...");
		try {
			esmplusOrderSyncService.syncEsmplusOrders();
			log.info("Finished scheduled ESM+(G마켓/옥션) order sync.");
		} catch (Exception e) {
			log.error("Scheduled ESM+(G마켓/옥션) order sync failed: {}", e.getMessage());
		}
	}
}
