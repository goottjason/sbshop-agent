package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.Test;

class SseNotificationBatchTest {

	@Test
	void batchEventName_clean_returnsBatchCompleted() {
		assertThat(SseNotificationController.batchEventName(0, 0)).isEqualTo("BATCH_COMPLETED");
	}

	@Test
	void batchEventName_partialOnly_returnsBatchPartial() {
		assertThat(SseNotificationController.batchEventName(0, 3)).isEqualTo("BATCH_PARTIAL");
	}

	@Test
	void batchEventName_anyHardFailure_returnsBatchFailed() {
		assertThat(SseNotificationController.batchEventName(2, 0)).isEqualTo("BATCH_FAILED");
		assertThat(SseNotificationController.batchEventName(2, 3)).isEqualTo("BATCH_FAILED");
	}

	@Test
	void batchPayload_success_formatsCorrectly() {
		assertThat(SseNotificationController.batchPayload("B-1", true)).isEqualTo("B-1|true");
	}

	@Test
	void batchPayload_failure_formatsCorrectly() {
		assertThat(SseNotificationController.batchPayload("B-2", false)).isEqualTo("B-2|false");
	}

	@Test
	void batchStartedEventName_returnsBatchStarted() {
		assertThat(SseNotificationController.batchStartedEventName()).isEqualTo("BATCH_STARTED");
	}

	@Test
	void batchStartedPayload_isBatchId() {
		assertThat(SseNotificationController.batchStartedPayload("B-3")).isEqualTo("B-3");
	}

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
