package com.sbshop.agent.core.application.process;

import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * F-BATCH-1: 배치 완료 시 jobType 동시실행 가드를 해제한다.
 *
 * <p>가드는 {@link ProcessStatusService#startBatch}(트리거)에서 획득하고, 실제 배치는 @Async라 분리되므로
 * 완료 시점의 {@link BatchCompletedEvent}(성공·실패 공통 발행)를 수신해 해제한다. 이렇게 "트리거~완료"
 * 구간 전체를 가드가 덮는다. 해제 자체는 batchId 기준 멱등이므로 중복/미등록 이벤트에도 안전하다.
 *
 * <p>배치는 api JVM 단일 실행이므로 이 리스너와 {@link ProcessStatusService} 가드 상태는 같은 JVM에서
 * 산다. worker JVM에서 우연히 수신되어도 해당 batchId 미등록 → no-op이라 무해하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchGuardReleaseListener {

	private final ProcessStatusService processStatusService;

	@EventListener
	public void onBatchCompleted(BatchCompletedEvent event) {
		processStatusService.releaseBatch(event.getBatchId());
	}
}
