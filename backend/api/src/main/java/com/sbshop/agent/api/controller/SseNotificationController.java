package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
import com.sbshop.agent.core.application.product.event.BatchStartedEvent;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
@CrossOrigin(origins = "*")
public class SseNotificationController {
	private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

	@GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter subscribe() {
		SseEmitter emitter = new SseEmitter(86400000L);
		emitters.add(emitter);
		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError((e) -> emitters.remove(emitter));
		try {
			emitter.send(SseEmitter.event().name("INIT").data("Connected to SSE"));
		} catch (IOException e) {
			emitters.remove(emitter);
		}
		return emitter;
	}

	@EventListener
	public void onSyncCompleted(SyncCompletedEvent event) {
		String name = syncEventName(event.isSuccess());
		String data = syncPayload(event.getMarketType(), event.isSuccess(), event.getErrorMessage());
		broadcast(name, data);
	}

	@EventListener
	public void onBatchCompleted(BatchCompletedEvent event) {
		String name = batchEventName(event.getFailCount(), event.getPartialCount());
		String data = batchPayload(event.getBatchId(), event.isSuccess());
		broadcast(name, data);
	}

	@EventListener
	public void onBatchStarted(BatchStartedEvent event) {
		broadcast(batchStartedEventName(), batchStartedPayload(event.getBatchId()));
	}

	static String syncEventName(boolean success) {
		return success ? "SYNC_COMPLETED" : "SYNC_FAILED";
	}

	static String syncPayload(MarketType marketType,
		boolean success, String errorMessage) {
		return success
			? marketType.name() + "|success"
			: marketType.name() + "|fail|" + errorMessage;
	}

	static String batchEventName(int failCount, int partialCount) {
		if (failCount > 0) {
			return "BATCH_FAILED";
		}
		return partialCount > 0 ? "BATCH_PARTIAL" : "BATCH_COMPLETED";
	}

	static String batchPayload(String batchId, boolean success) {
		return batchId + "|" + success;
	}

	static String batchStartedEventName() {
		return "BATCH_STARTED";
	}

	static String batchStartedPayload(String batchId) {
		return batchId;
	}

	private void broadcast(String name, String data) {
		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event().name(name).data(data));
			} catch (IOException e) {
				emitters.remove(emitter);
			}
		}
	}
}
