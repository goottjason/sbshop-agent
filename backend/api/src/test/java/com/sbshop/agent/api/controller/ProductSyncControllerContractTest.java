package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductSyncService;
import com.sbshop.agent.core.config.InternalAccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@ExtendWith(MockitoExtension.class)
class ProductSyncControllerContractTest {

	private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

	@Mock
	ProductSyncService productSyncService;
	@Mock
	ActionLogService actionLogService;

	private ProductSyncController controller(String configuredToken) {
		return new ProductSyncController(productSyncService, actionLogService,
			new InternalAccessGuard(configuredToken));
	}

	@Test
	@DisplayName("동기화 시작(200) → {success:true, message:\"NEW/PREPARING …\"} 트리 보존")
	void syncStarted_bodyTreeUnchanged() throws Exception {
		ResponseEntity<?> res = controller("").syncAllProductStock(null);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		JsonNode expected = mapper.readTree(
			"{\"success\":true,\"message\":\"NEW/PREPARING 상태 주문 상품 재고 동기화 시작\"}");
		JsonNode actual = mapper.valueToTree(res.getBody());
		assertThat(actual).isEqualTo(expected);
	}

	@Test
	@DisplayName("가드 차단(403) → {success:false, message:\"forbidden: invalid internal token\"} 트리 보존")
	void forbidden_bodyTreeUnchanged() throws Exception {
		ResponseEntity<?> res = controller("s3cr3t").syncAllProductStock("wrong");

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		JsonNode expected = mapper.readTree(
			"{\"success\":false,\"message\":\"forbidden: invalid internal token\"}");
		JsonNode actual = mapper.valueToTree(res.getBody());
		assertThat(actual).isEqualTo(expected);
	}

	@Test
	@DisplayName("디스패치 실패(500) → {success:false, message:<예외 메시지>} 트리 보존")
	void dispatchFailure_bodyTreeUnchanged() throws Exception {
		doThrow(new IllegalStateException("boom")).when(productSyncService)
			.syncStockForPreparingOrdersAsync();

		ResponseEntity<?> res = controller("").syncAllProductStock(null);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		JsonNode expected = mapper.readTree(
			"{\"success\":false,\"message\":\"boom\"}");
		JsonNode actual = mapper.valueToTree(res.getBody());
		assertThat(actual).isEqualTo(expected);
	}
}
