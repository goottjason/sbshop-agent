package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
import com.sbshop.agent.core.application.order.port.Cafe24OrderApiPort;
import com.sbshop.agent.core.application.order.service.Cafe24OrderSyncService;
import com.sbshop.agent.core.application.order.service.CoupangOrderSyncService;
import com.sbshop.agent.core.application.order.service.CustomsOrderSyncService;
import com.sbshop.agent.core.application.order.service.ElevenstOrderSyncService;
import com.sbshop.agent.core.application.order.service.SmartStoreOrderSyncService;
import com.sbshop.agent.core.application.sync.SyncStatusService;

/**
 * F-SYNC-16: preview·carriers 진단 엔드포인트를 명시적 타입으로 표현하되 JSON 응답 계약을 보존한다.
 *
 * <p>계약 특성화 테스트: 응답 바디를 Spring Boot 웹 계층 기본 ObjectMapper로 직렬화한 JSON 트리가
 * 아래 고정 형태와 정확히 동일해야 한다. 원시 {@link JsonNode} 페이로드(orders/carriers)는 외부
 * 응답에 따라 가변이므로 record 화하지 않고 그대로 실려야 하며, 성공/실패 envelope 형태도 불변이어야 한다.
 * 타입 시그니처를 바꿔도 이 트리가 깨지면 프론트 계약이 바뀐 것이므로 실패한다.
 */
@ExtendWith(MockitoExtension.class)
class OrderSyncControllerPreviewContractTest {

	/** Spring Boot 웹 계층 기본 매퍼를 복제(직렬화 규칙 동일). */
	private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();

	@Mock
	CoupangOrderSyncService coupangOrderSyncService;
	@Mock
	SmartStoreOrderSyncService smartStoreOrderSyncService;
	@Mock
	ElevenstOrderSyncService elevenstOrderSyncService;
	@Mock
	Cafe24OrderSyncService cafe24OrderSyncService;
	@Mock
	Cafe24OrderApiPort cafe24OrderApiPort;
	@Mock
	CustomsOrderSyncService customsOrderSyncService;
	@Mock
	SyncStatusService syncStatusService;
	@Mock
	ActionLogService actionLogService;

	private OrderSyncController controller() {
		return new OrderSyncController(coupangOrderSyncService, smartStoreOrderSyncService,
			elevenstOrderSyncService, cafe24OrderSyncService, cafe24OrderApiPort,
			customsOrderSyncService, syncStatusService, actionLogService);
	}

	@Test
	@DisplayName("preview 성공 → {success:true, orders:<원시 JsonNode 그대로>} 트리 보존")
	void preview_success_bodyTreeUnchanged() throws Exception {
		JsonNode orders = mapper.readTree("[{\"order_id\":\"A1\",\"order_place_id\":\"gmarket\"}]");
		when(cafe24OrderApiPort.fetchOrders(org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
			org.mockito.ArgumentMatchers.anyInt())).thenReturn(orders);

		ResponseEntity<?> res = controller().previewCafe24Orders();

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		JsonNode expected = mapper.readTree(
			"{\"success\":true,\"orders\":[{\"order_id\":\"A1\",\"order_place_id\":\"gmarket\"}]}");
		JsonNode actual = mapper.valueToTree(res.getBody());
		assertThat(actual).isEqualTo(expected);
	}

	@Test
	@DisplayName("preview 실패 → {success:false, message, rootCause} 트리 보존")
	void preview_failure_bodyTreeUnchanged() throws Exception {
		when(cafe24OrderApiPort.fetchOrders(org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
			org.mockito.ArgumentMatchers.anyInt())).thenThrow(new IllegalStateException("boom"));

		ResponseEntity<?> res = controller().previewCafe24Orders();

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		JsonNode expected = mapper.readTree(
			"{\"success\":false,\"message\":\"boom\",\"rootCause\":\"boom\"}");
		JsonNode actual = mapper.valueToTree(res.getBody());
		assertThat(actual).isEqualTo(expected);
	}

	@Test
	@DisplayName("carriers 성공 → {success:true, carriers:<원시 JsonNode 그대로>} 트리 보존")
	void carriers_success_bodyTreeUnchanged() throws Exception {
		JsonNode carriers = mapper.readTree(
			"[{\"carrier_id\":1,\"shipping_company_code\":\"0001\"}]");
		when(cafe24OrderApiPort.fetchCarriers()).thenReturn(carriers);

		ResponseEntity<?> res = controller().previewCafe24Carriers();

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
		JsonNode expected = mapper.readTree(
			"{\"success\":true,\"carriers\":[{\"carrier_id\":1,\"shipping_company_code\":\"0001\"}]}");
		JsonNode actual = mapper.valueToTree(res.getBody());
		assertThat(actual).isEqualTo(expected);
	}

	@Test
	@DisplayName("carriers 실패 → {success:false, message, rootCause} 트리 보존")
	void carriers_failure_bodyTreeUnchanged() throws Exception {
		when(cafe24OrderApiPort.fetchCarriers()).thenThrow(new IllegalStateException("carrier-fail"));

		ResponseEntity<?> res = controller().previewCafe24Carriers();

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		JsonNode expected = mapper.readTree(
			"{\"success\":false,\"message\":\"carrier-fail\",\"rootCause\":\"carrier-fail\"}");
		JsonNode actual = mapper.valueToTree(res.getBody());
		assertThat(actual).isEqualTo(expected);
	}
}
