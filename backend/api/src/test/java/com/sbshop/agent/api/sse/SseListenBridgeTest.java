package com.sbshop.agent.api.sse;

import com.sbshop.agent.core.application.order.event.SyncCompletedEvent;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SseListenBridgeTest {

	@Test
	void handlePayload_validSuccess_republishesReconstructedEvent() {
		ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
		SseListenBridge bridge = new SseListenBridge(null, publisher);

		bridge.handlePayload("COUPANG|true|");

		ArgumentCaptor<SyncCompletedEvent> captor = ArgumentCaptor.forClass(SyncCompletedEvent.class);
		verify(publisher).publishEvent(captor.capture());
		SyncCompletedEvent published = captor.getValue();
		assertThat(published.getMarketType()).isEqualTo(MarketType.COUPANG);
		assertThat(published.isSuccess()).isTrue();
	}

	@Test
	void handlePayload_validFailure_republishesWithErrorMessage() {
		ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
		SseListenBridge bridge = new SseListenBridge(null, publisher);

		bridge.handlePayload("SMART_STORE|false|인증 실패");

		ArgumentCaptor<SyncCompletedEvent> captor = ArgumentCaptor.forClass(SyncCompletedEvent.class);
		verify(publisher).publishEvent(captor.capture());
		SyncCompletedEvent published = captor.getValue();
		assertThat(published.getMarketType()).isEqualTo(MarketType.SMART_STORE);
		assertThat(published.isSuccess()).isFalse();
		assertThat(published.getErrorMessage()).isEqualTo("인증 실패");
	}

	@Test
	void handlePayload_brokenPayload_ignoredNoPublishNoThrow() {
		ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
		SseListenBridge bridge = new SseListenBridge(null, publisher);

		assertThatCode(() -> bridge.handlePayload("garbage-no-delimiters")).doesNotThrowAnyException();

		verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void handlePayload_nullPayload_ignoredNoPublishNoThrow() {
		ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
		SseListenBridge bridge = new SseListenBridge(null, publisher);

		assertThatCode(() -> bridge.handlePayload(null)).doesNotThrowAnyException();

		verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
	}
}
