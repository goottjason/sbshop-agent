package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/v1/notifications")
@CrossOrigin(origins = "*")
public class SseNotificationController {

	// 전역 SSE 연결 목록 (동시성 제어용)
	private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

	@GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter subscribe() {
		// 1. SSE 연결 객체 생성 (타임아웃 24시간)
		SseEmitter emitter = new SseEmitter(86400000L);

		// 2. 발송 목록에 클라이언트 추가
		emitters.add(emitter);

		// 3. 연결 종료 시 목록에서 제거
		emitter.onCompletion(() -> emitters.remove(emitter));

		// 4. 연결 타임아웃 시 목록에서 제거
		emitter.onTimeout(() -> emitters.remove(emitter));

		// 5. 연결 오류 발생 시 목록에서 제거
		emitter.onError((e) -> emitters.remove(emitter));

		try {
			// 6. 연결 성공 알림 이벤트 발송 (클라이언트에 상태 전달)
			emitter.send(SseEmitter.event().name("INIT").data("Connected to SSE"));
		} catch (IOException e) {
			// 7. 초기 이벤트 발송 실패 시 즉시 제거
			emitters.remove(emitter);
		}

		// 8. 연결된 Emitter 반환
		return emitter;
	}

	static String syncEventName(boolean success) {
		return success ? "SYNC_COMPLETED" : "SYNC_FAILED";
	}

	static String syncPayload(com.sbshop.agent.core.domain.order.enums.MarketType marketType,
			boolean success, String errorMessage) {
		return success
			? marketType.name() + "|success"
			: marketType.name() + "|fail|" + errorMessage;
	}

	@EventListener
	public void onSyncCompleted(SyncCompletedEvent event) {
		String name = syncEventName(event.isSuccess());
		String data = syncPayload(event.getMarketType(), event.isSuccess(), event.getErrorMessage());
		broadcast(name, data);
	}

	static String batchEventName(boolean success) {
		return success ? "BATCH_COMPLETED" : "BATCH_FAILED";
	}

	static String batchPayload(String batchId, boolean success) {
		return batchId + "|" + success;
	}

	@EventListener
	public void onBatchCompleted(com.sbshop.agent.core.application.product.event.BatchCompletedEvent event) {
		String name = batchEventName(event.isSuccess());
		String data = batchPayload(event.getBatchId(), event.isSuccess());
		broadcast(name, data);
	}

	// D-089: 배치 시작 이벤트. payload는 batchId 그 자체(프론트가 startTracking에 바로 사용).
	static String batchStartedEventName() {
		return "BATCH_STARTED";
	}

	static String batchStartedPayload(String batchId) {
		return batchId;
	}

	@EventListener
	public void onBatchStarted(com.sbshop.agent.core.application.product.event.BatchStartedEvent event) {
		broadcast(batchStartedEventName(), batchStartedPayload(event.getBatchId()));
	}

	// 등록된 모든 클라이언트에 동일 이벤트를 발송하고, 발송 실패한 클라이언트는 목록에서 제거한다.
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
