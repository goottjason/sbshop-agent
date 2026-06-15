package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.order.SyncCompletedEvent;
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
		// 1. SSE 연결 객체 생성 (타임아웃 60초)
		SseEmitter emitter = new SseEmitter(60000L);

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

	@EventListener
	public void onSyncCompleted(SyncCompletedEvent event) {
		// 1. 등록된 모든 클라이언트 순회
		for (SseEmitter emitter : emitters) {
			try {
				// 2. 성공/실패 구분하여 이벤트 발송
				if (event.isSuccess()) {
					emitter.send(SseEmitter.event()
						.name("SYNC_COMPLETED")
						.data(event.getMarketType().name() + "|success"));
				} else {
					emitter.send(SseEmitter.event()
						.name("SYNC_FAILED")
						.data(event.getMarketType().name() + "|fail|" + event.getErrorMessage()));
				}
			} catch (IOException e) {
				// 3. 이벤트 발송 실패 시 대상 클라이언트 제거 (비정상 종료 처리)
				emitters.remove(emitter);
			}
		}
	}
}
