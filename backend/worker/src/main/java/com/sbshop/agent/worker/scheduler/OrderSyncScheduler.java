package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.worker.service.EmailFetcherService;
import com.sbshop.agent.core.application.order.service.SmartStoreOrderSyncService;
import com.sbshop.agent.core.application.order.service.CoupangOrderSyncService;
import com.sbshop.agent.core.application.order.service.ElevenstOrderSyncService;
import com.sbshop.agent.core.application.order.service.EsmplusOrderSyncService;
import com.sbshop.agent.core.application.order.service.CustomsOrderSyncService;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSyncScheduler {

	private static final String EMAIL = "EMAIL";
	private static final String COUPANG = "COUPANG";
	private static final String SMART_STORE = "SMART_STORE";
	private static final String ELEVEN_STREET = "ELEVEN_STREET";
	private static final String GMARKET = "GMARKET";
	private static final String COUPANG_SETTLEMENT = "COUPANG_SETTLEMENT";
	private static final String CUSTOMS = "CUSTOMS";

	private final EmailFetcherService emailFetcherService;
	private final SmartStoreOrderSyncService smartStoreOrderSyncService;
	private final CoupangOrderSyncService coupangOrderSyncService;
	private final ElevenstOrderSyncService elevenstOrderSyncService;
	private final EsmplusOrderSyncService esmplusOrderSyncService;
	private final CustomsOrderSyncService customsOrderSyncService;
	private final SyncStatusService syncStatusService;

	// 매시 0분, 30분 - 이메일 IMAP 주문 수집
	@Scheduled(cron = "0 0/30 * * * ?") // TODO: 리팩토링 완료 후 활성화
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
	@Scheduled(cron = "0 5/30 * * * ?") // TODO: 리팩토링 완료 후 활성화
	public void syncCoupangOrders() {
		log.info("쿠팡 주문 동기화 시작...");
		syncStatusService.markRunning(COUPANG);
		try {
			coupangOrderSyncService.syncCoupangOrders();
			syncStatusService.markCompleted(COUPANG);
			log.info("쿠팡 주문 동기화 완료.");
		} catch (Exception e) {
			syncStatusService.markFailed(COUPANG, e.getMessage());
			log.error("쿠팡 주문 동기화 실패: {}", e.getMessage());
		}
	}

	// 매시 10분, 40분 - ESM+(G마켓/옥션) 주문 동기화
	@Scheduled(cron = "0 10/30 * * * ?") // TODO: 리팩토링 완료 후 활성화
	public void syncEsmplusOrders() {
		log.info("ESM+(G마켓/옥션) 주문 동기화 시작...");
		syncStatusService.markRunning(GMARKET);
		try {
			esmplusOrderSyncService.syncEsmplusOrders();
			syncStatusService.markCompleted(GMARKET);
			log.info("ESM+(G마켓/옥션) 주문 동기화 완료.");
		} catch (Exception e) {
			syncStatusService.markFailed(GMARKET, e.getMessage());
			log.error("ESM+(G마켓/옥션) 주문 동기화 실패: {}", e.getMessage());
		}
	}

	// 매시 15분, 45분 - 스마트스토어 주문 동기화
	@Scheduled(cron = "0 15/30 * * * ?") // TODO: 리팩토링 완료 후 활성화
	public void syncSmartStoreOrders() {
		log.info("스마트스토어 주문 동기화 시작...");
		syncStatusService.markRunning(SMART_STORE);
		try {
			smartStoreOrderSyncService.syncSmartStoreOrders();
			syncStatusService.markCompleted(SMART_STORE);
			log.info("스마트스토어 주문 동기화 완료.");
		} catch (Exception e) {
			syncStatusService.markFailed(SMART_STORE, e.getMessage());
			log.error("스마트스토어 주문 동기화 실패: {}", e.getMessage());
		}
	}

	// 매시 20분, 50분 - 11번가 주문 동기화
	@Scheduled(cron = "0 20/30 * * * ?") // TODO: 리팩토링 완료 후 활성화
	public void syncElevenstOrders() {
		log.info("11번가 주문 동기화 시작...");
		syncStatusService.markRunning(ELEVEN_STREET);
		try {
			elevenstOrderSyncService.syncElevenstOrders();
			syncStatusService.markCompleted(ELEVEN_STREET);
			log.info("11번가 주문 동기화 완료.");
		} catch (Exception e) {
			syncStatusService.markFailed(ELEVEN_STREET, e.getMessage());
			log.error("11번가 주문 동기화 실패: {}", e.getMessage());
		}
	}

	// 매일 새벽 2시 - 쿠팡 정산 데이터 동기화
	@Scheduled(cron = "0 0 2 * * ?") // TODO: 리팩토링 완료 후 활성화
	public void syncCoupangSettlement() {
		log.info("쿠팡 정산 데이터 동기화 시작...");
		syncStatusService.markRunning(COUPANG_SETTLEMENT);
		try {
			coupangOrderSyncService.syncCoupangSettlement();
			syncStatusService.markCompleted(COUPANG_SETTLEMENT);
			log.info("쿠팡 정산 데이터 동기화 완료.");
		} catch (Exception e) {
			syncStatusService.markFailed(COUPANG_SETTLEMENT, e.getMessage());
			log.error("쿠팡 정산 데이터 동기화 실패: {}", e.getMessage());
		}
	}

	// 매 시간 정각 - 통관 상태 동기화 (GSI Express 검증)
	@Scheduled(cron = "0 0 * * * ?")
	public void syncCustomsStatus() {
		log.info("통관 상태 동기화 시작...");
		syncStatusService.markRunning(CUSTOMS);
		try {
			customsOrderSyncService.syncCustomsStatus();
			syncStatusService.markCompleted(CUSTOMS);
			log.info("통관 상태 동기화 완료.");
		} catch (Exception e) {
			syncStatusService.markFailed(CUSTOMS, e.getMessage());
			log.error("통관 상태 동기화 실패: {}", e.getMessage());
		}
	}
}
