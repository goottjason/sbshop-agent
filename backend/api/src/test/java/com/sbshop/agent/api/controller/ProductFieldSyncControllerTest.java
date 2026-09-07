package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.ProductFieldSyncUseCase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ProductFieldSyncControllerTest {

	@Mock
	private ProductFieldSyncUseCase useCase;
	@InjectMocks
	private ProductFieldSyncController controller;

	@Test
	@DisplayName("기존 즉시 반영 경로는 409로 새 검토 경로를 안내하며 마켓을 호출하지 않는다")
	void legacySyncRequiresDurableReviewWithoutCallingMarkets() {

		ResponseEntity<Map<String, Object>> res = controller.syncFields(7L,
			new ProductFieldSyncController.FieldSyncRequest(List.of("BRAND"), List.of("SMART_STORE")));

		assertThat(res.getStatusCode().value()).isEqualTo(409);
		assertThat(res.getBody()).containsEntry("code", "FIELD_REVIEW_REQUIRED");
		org.mockito.Mockito.verifyNoInteractions(useCase);
	}

	@Test
	@DisplayName("D-294: 알 수 없는 필드명은 400 이다 — 조용히 무시하지 않는다")
	void unknownFieldIsRejected() {
		ResponseEntity<Map<String, Object>> res = controller.syncFields(7L,
			new ProductFieldSyncController.FieldSyncRequest(List.of("NOPE"), List.of("SMART_STORE")));

		assertThat(res.getStatusCode().value()).isEqualTo(400);
	}

	@Test
	@DisplayName("D-294: 필드나 마켓이 비면 400 이다")
	void emptyRequestIsRejected() {
		ResponseEntity<Map<String, Object>> res = controller.syncFields(7L,
			new ProductFieldSyncController.FieldSyncRequest(List.of(), List.of("SMART_STORE")));

		assertThat(res.getStatusCode().value()).isEqualTo(400);
	}

	@Test
	void legacyBatchCannotCreateJobsAndMalformedEnumsReturn400() {
		var service = org.mockito.Mockito
			.mock(com.sbshop.agent.core.application.product.ProductFieldSyncBatchService.class);
		var status = org.mockito.Mockito.mock(com.sbshop.agent.core.application.process.ProcessStatusService.class);
		var controller = new ProductFieldSyncController(useCase, service, status);
		assertThat(controller
			.batchSyncFields(
				new ProductFieldSyncController.BatchFieldSyncRequest(List.of("BRAND"), List.of("SMART_STORE"), 300))
			.getStatusCode().value()).isEqualTo(409);
		assertThat(controller
			.batchSyncFields(
				new ProductFieldSyncController.BatchFieldSyncRequest(List.of("NOPE"), List.of("SMART_STORE"), 300))
			.getStatusCode().value()).isEqualTo(400);
		org.mockito.Mockito.verifyNoInteractions(useCase, service, status);
	}

}
