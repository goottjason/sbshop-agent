package com.sbshop.agent.api.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sbshop.agent.api.exception.GlobalExceptionHandler;
import com.sbshop.agent.core.application.market.marketplus.MarketPlusPublicObservationService;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketPlusPublicObservationControllerTest {
	MarketPlusPublicObservationService service = mock(MarketPlusPublicObservationService.class);
	MockMvc mvc;

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new MarketPlusPublicObservationController(service))
			.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	String body() {
		return """
			{"registrationId":2,"expectedRevision":5,"cafe24ProductNo":"10186","cafe24ProductCode":"P0000PBU","actor":"forged",
			"observation":{"schemaVersion":1,"source":"LIVE_CHROME_PUBLIC_MARKET","market":"AUCTION","externalId":"D888859044",
			"sellerAccount":"seller-a","capturedAt":"%s","url":"https://itempage3.auction.co.kr/DetailView.aspx?ItemNo=D888859044",
			"values":{"salePrice":"95500"},"quantityBasis":"NOT_VERIFIED","listingState":"UNVERIFIED"}}
			"""
			.formatted(Instant.now());
	}

	@Test void usesAuthenticatedActorAndTypedObservation() throws Exception {
        when(service.ingest(eq(1L), any(), eq("admin"))).thenReturn(new MarketPlusPublicObservationService.Result(8L, "RECORDED", "관측 저장"));
        mvc.perform(post("/api/v1/products/1/marketplus-public-observations").principal(() -> "admin").contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RECORDED"));
        verify(service).ingest(eq(1L), argThat(r -> r.expectedRevision() == 5L && r.observation().values().size() == 1), eq("admin"));
    }

	@ParameterizedTest
	@ValueSource(strings = {"null", "5.5", "\"5\""})
	void absentAndCoercedRevisionCannotBindToProductVersion(String value) throws Exception {
		mvc.perform(post("/api/v1/products/1/marketplus-public-observations").principal(() -> "admin")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body().replace("\"expectedRevision\":5", "\"expectedRevision\":" + value)))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void htmlAndClaimsOfVerifiedListingAreRejected() throws Exception {
		for (String input : new String[] {
			body().replace("\"salePrice\":\"95500\"", "\"salePrice\":\"95500\",\"detailHtml\":\"<script/>\""),
			body().replace("\"listingState\":\"UNVERIFIED\"", "\"listingState\":\"DELETED\""), "{}"})
			mvc.perform(post("/api/v1/products/1/marketplus-public-observations").principal(() -> "admin")
				.contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}
}
