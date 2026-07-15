package com.sbshop.agent.worker.sse;

import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.application.sync.SseBridgeCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * worker JVM에서 발행된 {@link SyncCompletedEvent}를 Postgres NOTIFY로 api JVM에 브리지한다(F-MISC-16).
 *
 * <p>worker 스케줄러가 트리거한 동기화의 완료 이벤트는 같은 JVM 안의 @EventListener로만 전달돼
 * 다른 JVM인 api의 SSE 리스너에 닿지 않았다. 여기서 이벤트를 {@code pg_notify}로 채널에 실어 보내면
 * api 쪽 LISTEN 소비자가 받아 SSE로 재발행한다.
 *
 * <p>이 컴포넌트는 worker 모듈에만 존재한다(api엔 없음). 따라서 api 로컬 이벤트(수동 트리거)는
 * NOTIFY를 타지 않고, worker 이벤트만 브리지되어 클라이언트로의 이중 전달이 없다. Batch 이벤트는
 * api JVM에서 실행돼 이미 SSE로 전달되므로 브리지 대상이 아니다.
 *
 * <p>알림 발행 실패가 동기화 자체를 깨뜨려선 안 되므로 예외는 로깅 후 삼킨다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseNotifyBridge {

	private static final String NOTIFY_SQL = "SELECT pg_notify(?, ?)";

	private final JdbcTemplate jdbcTemplate;

	@EventListener
	public void onSyncCompleted(SyncCompletedEvent event) {
		try {
			String payload = SseBridgeCodec.serialize(event.getMarketType(), event.isSuccess(), event.getErrorMessage());
			jdbcTemplate.update(NOTIFY_SQL, SseBridgeCodec.CHANNEL, payload);
		} catch (Exception e) {
			// 알림 실패는 동기화를 깨지 않도록 삼킨다(브리지는 부가 기능).
			log.warn("SSE NOTIFY 브리지 발행 실패 (market={}): {}", event.getMarketType(), e.getMessage());
		}
	}
}
