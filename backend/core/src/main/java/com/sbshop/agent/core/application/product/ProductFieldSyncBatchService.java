package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductFieldSyncBatchService {

	private static final long THROTTLE_MS = 700L;

	private final ProductRepository productRepository;
	private final ProductFieldSyncUseCase fieldSyncUseCase;
	private final ProcessStatusService processStatusService;
	private final ApplicationEventPublisher eventPublisher;

	public List<Long> findTargets(int limit) {
		List<Long> ids = productRepository.findFieldSyncTargetIds();
		if (limit > 0 && ids.size() > limit) {
			return ids.subList(0, limit);
		}
		return ids;
	}

	@Async("productBatchExecutor")
	public void runBatch(String batchId, List<Long> productIds,
		Set<MarketEditField> fields, Set<MarketType> markets) {
		int failCount = 0;
		int partialCount = 0;
		for (Long productId : productIds) {
			try {
				MarketRepublishResult result = fieldSyncUseCase.syncOne(batchId, productId, fields, markets);
				if (!result.failed().isEmpty()) {
					partialCount++;
				}
				Thread.sleep(THROTTLE_MS);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				log.error("[필드동기화 배치] 실패: productId={}", productId, e);
				processStatusService.markFailed(batchId, String.valueOf(productId), e.getMessage());
				failCount++;
			}
		}
		String message = failCount == 0 && partialCount == 0
			? "필드 반영 배치 완료"
			: "필드 반영 배치 완료(실패 %d건, 부분실패 %d건)".formatted(failCount, partialCount);
		log.info("[필드동기화 배치] batchId={} {}", batchId, message);
		eventPublisher.publishEvent(new BatchCompletedEvent(this, batchId,
			ActionLogConstants.BATCH_FIELD_SYNC, failCount, partialCount, message));
	}
}
