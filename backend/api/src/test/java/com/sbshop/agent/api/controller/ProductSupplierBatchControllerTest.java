package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sbshop.agent.core.application.product.batch.ProductSupplierBatchService;
import java.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductSupplierBatchControllerTest {
	private ProductSupplierBatchService service;
	private MockMvc mvc;
	private final Principal actor = () -> "batch-reviewer";
	private static final String RUN = "271bc1e6-bdae-40bf-bfb0-bf0b6dbb7dc1";

	@BeforeEach
	void setUp() {
		service = mock(ProductSupplierBatchService.class);
		mvc = MockMvcBuilders.standaloneSetup(new ProductSupplierBatchController(service)).build();
	}

	@Test
	void startUsesSupplierPolicyAndAuthenticatedActorWithoutClientProductList() throws Exception {
		mvc.perform(post("/api/v1/supplier-batches").principal(actor).contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"requestId":"271bc1e6-bdae-40bf-bfb0-bf0b6dbb7dc1","vendor":"IHB","mode":"PRICE_STOCK",
				"marginRate":10,"couponRate":20,"minMarginPrice":1500,"markets":["COUPANG","ELEVEN_STREET"]}
				""")).andExpect(status().isOk());
		var captured = ArgumentCaptor.forClass(ProductSupplierBatchService.CreateRequest.class);
		verify(service).create(captured.capture(), eq("batch-reviewer"));
		assertThat(captured.getValue().vendor().name()).isEqualTo("IHB");
		assertThat(captured.getValue().mode().name()).isEqualTo("PRICE_STOCK");
		assertThat(captured.getValue().marginRate()).isEqualByComparingTo("10");
		assertThat(captured.getValue().couponRate()).isEqualByComparingTo("20");
		assertThat(captured.getValue().minMarginPrice()).isEqualByComparingTo("1500");
		assertThat(captured.getValue().markets()).hasSize(2);
	}

	@Test
	void targetedRetryPreservesExactStageMarketFieldAndIdempotencyKey() throws Exception {
		mvc.perform(post("/api/v1/supplier-batches/" + RUN + "/retry").principal(actor)
			.contentType(MediaType.APPLICATION_JSON).content("""
				{"requestId":"971bc1e6-bdae-40bf-bfb0-bf0b6dbb7dc1","itemId":71,"stage":"MARKET",
				"market":"ELEVEN_STREET","field":"PRICE"}
				""")).andExpect(status().isOk());
		var captured = ArgumentCaptor.forClass(ProductSupplierBatchService.RetryRequest.class);
		verify(service).retry(eq(RUN), captured.capture(), eq("batch-reviewer"));
		var request = captured.getValue();
		assertThat(request.itemId()).isEqualTo(71L);
		assertThat(request.stage().toString()).isEqualTo("MARKET");
		assertThat(request.market().name()).isEqualTo("ELEVEN_STREET");
		assertThat(request.field().toString()).isEqualTo("PRICE");
		assertThat(request.requestId()).isEqualTo("971bc1e6-bdae-40bf-bfb0-bf0b6dbb7dc1");
	}

	@Test
	void pauseAndResumeAreExplicitMutationsWithActor() throws Exception {
		mvc.perform(post("/api/v1/supplier-batches/" + RUN + "/pause").principal(actor)).andExpect(status().isOk());
		mvc.perform(post("/api/v1/supplier-batches/" + RUN + "/resume").principal(actor)).andExpect(status().isOk());
		verify(service).pause(RUN, "batch-reviewer");
		verify(service).resume(RUN, "batch-reviewer");
		verifyNoMoreInteractions(service);
	}

	@Test
	void deletePassesAuthenticatedActorAndReturnsNoContent() throws Exception {
		mvc.perform(delete("/api/v1/supplier-batches/" + RUN).principal(actor))
			.andExpect(status().isNoContent());
		verify(service).delete(RUN, "batch-reviewer");
	}

	@Test
	void readRoutesRetainPagingSearchAndNeverStartOrRetryWork() throws Exception {
		mvc.perform(get("/api/v1/supplier-batches/options")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/supplier-batches").param("page", "3").param("size", "10")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/supplier-batches/" + RUN)).andExpect(status().isOk());
		mvc.perform(get("/api/v1/supplier-batches/" + RUN + "/items")
			.param("page", "2").param("size", "50").param("keyword", "IHB").param("filter", "FAILED"))
			.andExpect(status().isOk());
		mvc.perform(get("/api/v1/supplier-batches/" + RUN + "/items/71")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/supplier-batches/" + RUN + "/retry-options")).andExpect(status().isOk());
		verify(service).options();
		verify(service).recent(3, 10);
		verify(service).get(RUN);
		verify(service).items(RUN, 2, 50, "IHB", "FAILED");
		verify(service).detail(RUN, 71L);
		verify(service).retryOptions(RUN);
		verifyNoMoreInteractions(service);
	}

	@Test
	void malformedMutationBodyNeverStartsBatch() throws Exception {
		mvc.perform(post("/api/v1/supplier-batches").principal(actor)
			.contentType(MediaType.APPLICATION_JSON).content("{not-json}"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}
}
