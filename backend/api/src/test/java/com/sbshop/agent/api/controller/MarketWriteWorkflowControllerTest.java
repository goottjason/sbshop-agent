package com.sbshop.agent.api.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sbshop.agent.api.exception.GlobalExceptionHandler;
import com.sbshop.agent.core.application.market.sync.*;
import com.sbshop.agent.core.application.product.edit.ProductEditConflictException;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MarketWriteWorkflowControllerTest {
	private final MarketPriceSyncService prices = mock(MarketPriceSyncService.class);
	private final MarketPublicationService publications = mock(MarketPublicationService.class);
	private final MockMvc mvc = MockMvcBuilders
		.standaloneSetup(new MarketPriceSyncController(prices), new MarketPublicationController(publications))
		.setControllerAdvice(new GlobalExceptionHandler()).build();

	@Test
	void pricePreviewAndCommitUseAuthenticatedActor() throws Exception {
		mvc.perform(
			post("/api/v1/products/price-sync/reviews").principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
				.content("{\"productIds\":[1],\"markets\":[\"SMART_STORE\"],\"actor\":\"forged\"}"))
			.andExpect(status().isOk());
		verify(prices).preview(List.of(1L), Set.of(MarketType.SMART_STORE), "admin");
		mvc.perform(post("/api/v1/products/price-sync/reviews/review/commit").principal(() -> "admin"))
			.andExpect(status().isOk());
		verify(prices).commit("review", "admin");
	}

	@Test
	void publicationPreparationAndRecheckNeverTrustBodyActor() throws Exception {
		mvc.perform(post("/api/v1/products/registrations/reviews").principal(() -> "admin")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"selected\":[{\"productId\":1,\"market\":\"SMART_STORE\"}],\"actor\":\"forged\"}"))
			.andExpect(status().isOk());
		verify(publications).prepare(List.of(new MarketPublicationService.Pair(1L, MarketType.SMART_STORE)), "admin");
		mvc.perform(post("/api/v1/products/registrations/job/recheck").principal(() -> "admin")
			.contentType(MediaType.APPLICATION_JSON).content("{\"listingId\":\"456\"}")).andExpect(status().isOk());
		verify(publications).recheck("job", "456", "admin");
	}

	@Test
	void malformedOrUnknownMarketIs400WithoutCallingWorkflow() throws Exception {
		mvc.perform(post("/api/v1/products/price-sync/reviews").principal(() -> "admin")
			.contentType(MediaType.APPLICATION_JSON).content("{\"productIds\":[1],\"markets\":[\"INVALID\"]}"))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/products/registrations/reviews").principal(() -> "admin")
			.contentType(MediaType.APPLICATION_JSON).content("{")).andExpect(status().isBadRequest());
		verifyNoInteractions(prices, publications);
	}

	@Test void staleReviewReturnsConflict() throws Exception {
  when(prices.commit("old","admin")).thenThrow(new ProductEditConflictException("expired"));
  mvc.perform(post("/api/v1/products/price-sync/reviews/old/commit").principal(()->"admin")).andExpect(status().isConflict());
 }
}
