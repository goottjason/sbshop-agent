package com.sbshop.agent.api.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sbshop.agent.api.exception.GlobalExceptionHandler;
import com.sbshop.agent.core.application.market.MarketConnectionService;
import com.sbshop.agent.core.application.product.edit.ProductEditConflictException;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketConnectionControllerTest {
	private final MarketConnectionService service = mock(MarketConnectionService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MarketConnectionController(service))
		.setControllerAdvice(new GlobalExceptionHandler()).build();
	private static final String PATH = "/api/v1/products/1/connections/2/GMARKET/prohibition";
	private static final String BODY = "{\"expectedRevision\":3,\"externalId\":\"007\",\"sellerAccount\":\"seller\",\"reason\":\"ban confirmed\",\"confirmedPermanent\":true,\"actor\":\"forged\"}";

	@Test
    void confirmationUsesPrincipalAndReviewedIdentity() throws Exception {
        when(service.confirmProhibition(1L, 2L, MarketType.GMARKET, 3L, "007", "seller", "ban confirmed", "admin"))
            .thenReturn(new MarketConnectionService.Result(9L, "DETACHED", "DETACHED_PROHIBITED", "confirmed"));
        mvc.perform(post(PATH).principal(() -> "admin").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DETACHED_PROHIBITED"));
        verify(service).confirmProhibition(1L, 2L, MarketType.GMARKET, 3L, "007", "seller", "ban confirmed", "admin");
    }

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"expectedRevision\":-1,\"externalId\":\"007\",\"confirmedPermanent\":true}",
		"{\"expectedRevision\":3,\"externalId\":\"007\",\"confirmedPermanent\":false}"})
	void malformedConfirmationDoesNotCallService(String body) throws Exception {
		mvc.perform(post(PATH).principal(() -> "admin").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
    void staleIdentityIsConflict() throws Exception {
        when(service.confirmProhibition(anyLong(), anyLong(), any(), anyLong(), anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new ProductEditConflictException("changed"));
        mvc.perform(post(PATH).principal(() -> "admin").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isConflict());
    }

	@Test
	void inspectionDoesNotAcceptClientSuppliedDeletionVerdict() throws Exception {
		mvc.perform(post("/api/v1/products/1/connections/2/SMART_STORE/inspect").principal(() -> "admin")
			.contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DELETED\"}")).andExpect(status().isOk());
		verify(service).inspect(1L, 2L, MarketType.SMART_STORE, "admin");
	}
}
