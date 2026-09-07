package com.sbshop.agent.api.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sbshop.agent.api.exception.GlobalExceptionHandler;
import com.sbshop.agent.core.application.market.inspection.MarketInspectionService;
import com.sbshop.agent.core.application.product.edit.ProductEditConflictException;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketInspectionControllerTest {
	private final MarketInspectionService service = mock(MarketInspectionService.class);
	private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MarketInspectionController(service))
		.setControllerAdvice(new GlobalExceptionHandler()).build();
	private final String root = "/api/v1/products/connection-inspections";

	@Test
	void creationUsesAuthenticatedActorAndStableRequestId() throws Exception {
		String id = UUID.randomUUID().toString();
		mvc.perform(post(root).principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
			.content("{\"productIds\":[1,2],\"requestId\":\"" + id + "\",\"actor\":\"forged\"}"))
			.andExpect(status().isOk());
		verify(service).create(List.of(1L, 2L), id, "admin");
	}

	@Test
	void retryPassesOriginalBatchAndSameClientRequest() throws Exception {
		mvc.perform(post(root + "/original/retry").principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
			.content("{\"requestId\":\"retry-key\"}")).andExpect(status().isOk());
		verify(service).retry("original", "retry-key", "admin");
	}

	@Test
	void malformedBodyIs400() throws Exception {
		mvc.perform(post(root).principal(() -> "admin").contentType(MediaType.APPLICATION_JSON).content("{"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test void requestConflictIs409AndNotFoundIs404() throws Exception {
        when(service.create(any(), any(), any())).thenThrow(new ProductEditConflictException("conflict"));
        mvc.perform(post(root).principal(() -> "admin").contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[1],\"requestId\":\"key\"}"))
            .andExpect(status().isConflict());
        when(service.get("missing")).thenThrow(new ResourceNotFoundException("missing"));
        mvc.perform(get(root + "/missing")).andExpect(status().isNotFound());
    }
}
