package com.sbshop.agent.core.application.process;

import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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
