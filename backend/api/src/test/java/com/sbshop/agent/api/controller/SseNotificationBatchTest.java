package com.sbshop.agent.api.controller;

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
}
