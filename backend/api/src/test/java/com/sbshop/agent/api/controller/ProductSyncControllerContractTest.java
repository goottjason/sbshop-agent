package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductSyncService;
import com.sbshop.agent.core.config.InternalAccessGuard;

/**
 * F-MISC-11: POST /api/v1/products/sync/stock 응답을 명시적 타입
 * {@code ResponseEntity<Map<String, Object>>}으로 표현하되 JSON 응답 계약(키·값)을 보존한다.
 *
 * <p>계약 특성화 테스트: 응답 바디를 Spring Boot 웹 계층 기본 ObjectMapper로 직렬화한 JSON 트리가
 * 아래 고정 형태와 정확히 동일해야 한다. {@code ResponseEntity<?>} → {@code ResponseEntity<Map<String,Object>>}
 * 시그니처 명시화로 바디 바이트가 바뀌면 프론트 계약이 깨진 것이므로 실패한다.
 *
 * <p>상태코드·서비스 호출 여부는 {@link ProductSyncControllerGuardTest}가 다룬다. 여기서는 바디 트리 불변에 집중한다.
 */
@ExtendWith(MockitoExtension.class)
class ProductSyncControllerContractTest {

	/** Spring Boot 웹 계층 기본 매퍼를 복제(직렬화 규칙 동일). */
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
