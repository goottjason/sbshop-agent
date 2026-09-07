package com.sbshop.agent.api.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sbshop.agent.api.exception.GlobalExceptionHandler;
import com.sbshop.agent.core.application.market.marketplus.MarketPlusTransmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketPlusTransmissionControllerTest {
	final MarketPlusTransmissionService service = mock(MarketPlusTransmissionService.class);
	final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MarketPlusTransmissionController(service))
		.setControllerAdvice(new GlobalExceptionHandler()).build();
	static final String PATH = "/api/v1/marketplus/transmissions/import";
	static final String BODY = """
		{"schemaVersion":1,"source":"LIVE_CHROME_MARKETPLUS","coverage":"CURRENT_PAGE","mallId":"testmall","shopNo":1,
		"capturedAt":"2026-09-06T07:03:00Z","actor":"forged","rows":[{"market":"GMARKET","sellerAccount":"seller",
		"cafe24ProductNo":"10186","cafe24ProductCode":"P0000PBU","externalId":"3490115053","transferType":"상품수정",
		"outcome":"SUCCESS","detail":"[성공] 완료","requestedAt":"2026-09-06T07:02:00Z","completedAt":"2026-09-06T07:02:00Z"}]}
		""";

	@Test
	void bindsActorToAuthenticatedPrincipal() throws Exception {
		mvc.perform(post(PATH).principal(() -> "admin").contentType(MediaType.APPLICATION_JSON).content(BODY))
			.andExpect(status().isOk());
		verify(service).ingest(any(), eq("admin"));
	}

	@Test
	void wrongSchemaNeverCallsService() throws Exception {
		mvc.perform(post(PATH).principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
			.content(BODY.replace("CURRENT_PAGE", "ALL_PRODUCTS")))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void statusAndFailureTextCannotConflict() throws Exception {
		mvc.perform(post(PATH).principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
			.content(BODY.replace("[성공]", "[실패]")))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void readsOnlySelectedProductHistory() throws Exception {
		mvc.perform(get("/api/v1/products/5/marketplus-transmissions").principal(() -> "admin"))
			.andExpect(status().isOk());
		verify(service).history(5L);
	}

	@Test
	void readinessReturnsUnavailableReasonWithoutCredentials() throws Exception {
		when(service.readiness()).thenReturn(new MarketPlusTransmissionService.Readiness(false, java.util.List.of("카페24 계정의 활성 상태 확인이 필요합니다.")));
		mvc.perform(get("/api/v1/marketplus/transmissions/readiness")).andExpect(status().isOk())
			.andExpect(jsonPath("$.ready").value(false)).andExpect(jsonPath("$.reasons[0]").value("카페24 계정의 활성 상태 확인이 필요합니다."))
			.andExpect(jsonPath("$.clientId").doesNotExist()).andExpect(jsonPath("$.accessToken").doesNotExist());
		verify(service).readiness();
		verifyNoMoreInteractions(service);
	}

	@Test
	void unavailableHistoryReturnsErrorRatherThanEmptyHistory() throws Exception {
		when(service.history(5L)).thenThrow(new IllegalStateException("카페24 계정의 활성 상태 확인이 필요합니다."));
		mvc.perform(get("/api/v1/products/5/marketplus-transmissions")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("카페24 계정의 활성 상태 확인이 필요합니다."));
	}
}
