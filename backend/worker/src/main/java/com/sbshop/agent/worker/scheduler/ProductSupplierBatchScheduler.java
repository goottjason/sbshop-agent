package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.product.batch.ProductSupplierBatchRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductSupplierBatchScheduler {
	private final ProductSupplierBatchRunner runner;

	@Scheduled(scheduler = "supplierBatchTaskScheduler", fixedDelayString = "${products.supplier-batch.worker-delay-ms:1000}", initialDelayString = "${products.supplier-batch.worker-initial-delay-ms:30000}")
	public void tick() {
		try {
			runner.tick();
		} catch (Exception error) {
			log.error("소싱 배치 단계 처리 실패. 영속 작업은 다음 실행에서 복구합니다.", error);
		}
	}
}
