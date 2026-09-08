package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.market.sync.MarketPriceSyncService;
import com.sbshop.agent.core.application.market.sync.MarketStockSyncService;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Independent market loops; price/stock share the existing per-market lease and 429 gate. */
@Component
@RequiredArgsConstructor
@Slf4j
public class BatchMarketSyncScheduler {
	private final MarketPriceSyncService prices;
	private final MarketStockSyncService stocks;
	private final ConcurrentHashMap<MarketType, Boolean> stockFirst = new ConcurrentHashMap<>();

	@Scheduled(fixedDelayString = "${products.market-sync.poll-ms:3000}", scheduler = "batchMarketTaskScheduler")
	public void coupang() {
		process(MarketType.COUPANG);
	}

	@Scheduled(fixedDelayString = "${products.market-sync.poll-ms:3000}", scheduler = "batchMarketTaskScheduler")
	public void elevenst() {
		process(MarketType.ELEVEN_STREET);
	}

	@Scheduled(fixedDelayString = "${products.market-sync.poll-ms:3000}", scheduler = "batchMarketTaskScheduler")
	public void smartStore() {
		process(MarketType.SMART_STORE);
	}

	@Scheduled(fixedDelayString = "${products.market-sync.poll-ms:3000}", scheduler = "batchMarketTaskScheduler")
	public void cafe24() {
		process(MarketType.CAFE24);
	}

	void process(MarketType market) {
		boolean first = stockFirst.compute(market, (key, previous) -> !Boolean.TRUE.equals(previous));
		if (first) {
			stock(market);
			price(market);
		} else {
			price(market);
			stock(market);
		}
	}

	private void price(MarketType market) {
		try {
			prices.processOne(market);
		} catch (Exception error) {
			log.error("가격 작업 처리 실패: {}", market, error);
		}
	}

	private void stock(MarketType market) {
		try {
			stocks.processOne(market);
		} catch (Exception error) {
			log.error("재고 작업 처리 실패: {}", market, error);
		}
	}
}
