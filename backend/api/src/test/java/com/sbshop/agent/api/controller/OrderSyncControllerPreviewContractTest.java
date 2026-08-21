package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@ExtendWith(MockitoExtension.class)
class OrderSyncControllerPreviewContractTest {

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
		when(cafe24OrderApiPort.fetchOrders(ArgumentMatchers.anyString(),
			ArgumentMatchers.anyString(), ArgumentMatchers.anyInt(),
			ArgumentMatchers.anyInt())).thenReturn(orders);

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
		when(cafe24OrderApiPort.fetchOrders(ArgumentMatchers.anyString(),
			ArgumentMatchers.anyString(), ArgumentMatchers.anyInt(),
			ArgumentMatchers.anyInt())).thenThrow(new IllegalStateException("boom"));

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
