package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.MarketRepublishResult;
import com.sbshop.agent.core.application.product.ProductFieldSyncUseCase;
import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductFieldSyncControllerTest {

	@Mock
	private ProductFieldSyncUseCase useCase;
	@InjectMocks
	private ProductFieldSyncController controller;

	@Test
	@DisplayName("D-294: 필드·마켓을 받아 동기화를 실행하고 batchId 와 마켓별 결과를 돌려준다")
	void runsSyncAndReturnsOutcome() {
		when(useCase.sync(eq(7L), eq(Set.of(MarketEditField.BRAND)), eq(Set.of(MarketType.SMART_STORE))))
			.thenReturn(new ProductFieldSyncUseCase.FieldSyncOutcome("fs-9",
				new MarketRepublishResult(List.of(MarketType.SMART_STORE), List.of(), Map.of())));

		ResponseEntity<Map<String, Object>> res = controller.syncFields(7L,
			new ProductFieldSyncController.FieldSyncRequest(List.of("BRAND"), List.of("SMART_STORE")));

		assertThat(res.getStatusCode().value()).isEqualTo(200);
		assertThat(res.getBody()).containsEntry("batchId", "fs-9");
		assertThat(res.getBody().get("synced")).isEqualTo(List.of("SMART_STORE"));
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
}
