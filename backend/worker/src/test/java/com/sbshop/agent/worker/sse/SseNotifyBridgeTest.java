package com.sbshop.agent.worker.sse;

import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.application.sync.SseBridgeCodec;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SseNotifyBridgeTest {

	@Test
	void onSyncCompleted_success_notifiesChannelWithSerializedPayload() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		SseNotifyBridge bridge = new SseNotifyBridge(jdbcTemplate);

		bridge.onSyncCompleted(new SyncCompletedEvent(this, MarketType.COUPANG, true, null));

		ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).update(eq("SELECT pg_notify(?, ?)"), args.capture());
		Object[] captured = args.getValue();
		assertThatCode(() -> {
			org.assertj.core.api.Assertions.assertThat(captured[0]).isEqualTo(SseBridgeCodec.CHANNEL);
			org.assertj.core.api.Assertions.assertThat(captured[1])
				.isEqualTo(SseBridgeCodec.serialize(MarketType.COUPANG, true, null));
		}).doesNotThrowAnyException();
	}

	@Test
	void onSyncCompleted_failure_notifiesWithErrorPayload() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		SseNotifyBridge bridge = new SseNotifyBridge(jdbcTemplate);

		bridge.onSyncCompleted(new SyncCompletedEvent(this, MarketType.SMART_STORE, false, "boom"));

		verify(jdbcTemplate).update(eq("SELECT pg_notify(?, ?)"),
			eq(SseBridgeCodec.CHANNEL),
			eq(SseBridgeCodec.serialize(MarketType.SMART_STORE, false, "boom")));
	}

	@Test
	void onSyncCompleted_jdbcThrows_swallowedNotPropagated() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		doThrow(new RuntimeException("db down"))
			.when(jdbcTemplate).update(org.mockito.ArgumentMatchers.anyString(), (Object[]) org.mockito.ArgumentMatchers.any());
		SseNotifyBridge bridge = new SseNotifyBridge(jdbcTemplate);

		assertThatCode(() -> bridge.onSyncCompleted(new SyncCompletedEvent(this, MarketType.COUPANG, true, null)))
			.doesNotThrowAnyException();
	}
}
