package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SseNotificationBatchTest {

	@Test
	void batchEventName_success_returnsBatchCompleted() {
		assertThat(SseNotificationController.batchEventName(true)).isEqualTo("BATCH_COMPLETED");
	}

	@Test
	void batchEventName_failure_returnsBatchFailed() {
		assertThat(SseNotificationController.batchEventName(false)).isEqualTo("BATCH_FAILED");
	}

	@Test
	void batchPayload_success_formatsCorrectly() {
		assertThat(SseNotificationController.batchPayload("B-1", true)).isEqualTo("B-1|true");
	}

	@Test
	void batchPayload_failure_formatsCorrectly() {
		assertThat(SseNotificationController.batchPayload("B-2", false)).isEqualTo("B-2|false");
	}

	// --- D-089: 배치 시작 브로드캐스트(타 클라이언트가 진행 중 배치를 발견하도록) ---

	@Test
	void batchStartedEventName_returnsBatchStarted() {
		assertThat(SseNotificationController.batchStartedEventName()).isEqualTo("BATCH_STARTED");
	}

	@Test
	void batchStartedPayload_isBatchId() {
		// 프론트가 별도 파싱 없이 startTracking(batchId)할 수 있도록 payload는 batchId 그 자체.
		assertThat(SseNotificationController.batchStartedPayload("B-3")).isEqualTo("B-3");
	}

	// --- 동작 불변 특성화 테스트: sync 리스너의 이벤트명/페이로드 매핑 ---

	@Test
	void syncEventName_success_returnsSyncCompleted() {
		assertThat(SseNotificationController.syncEventName(true)).isEqualTo("SYNC_COMPLETED");
	}

	@Test
	void syncEventName_failure_returnsSyncFailed() {
		assertThat(SseNotificationController.syncEventName(false)).isEqualTo("SYNC_FAILED");
	}

	@Test
	void syncPayload_success_formatsMarketAndSuccess() {
		assertThat(SseNotificationController.syncPayload(MarketType.COUPANG, true, null))
			.isEqualTo("COUPANG|success");
	}

	@Test
	void syncPayload_failure_formatsMarketFailAndErrorMessage() {
		assertThat(SseNotificationController.syncPayload(MarketType.SMART_STORE, false, "boom"))
			.isEqualTo("SMART_STORE|fail|boom");
	}
}
