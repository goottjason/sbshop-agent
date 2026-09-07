package com.sbshop.agent.api.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sbshop.agent.api.exception.GlobalExceptionHandler;
import com.sbshop.agent.core.application.product.source.ProductSourceService;
import com.sbshop.agent.core.application.product.edit.ProductEditService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductSourceRefreshControllerTest {
	ProductSourceService service = mock(ProductSourceService.class);
	MockMvc mvc;
	String id = UUID.randomUUID().toString();

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new ProductSourceRefreshController(service))
			.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	@Test void collectionPassesOnlyAuthenticatedActorAndServerUsesStoredProductUrls() throws Exception {
		when(service.collect(any(), eq("admin"))).thenReturn(new ProductSourceService.Collection(id, Instant.now(), List.of()));
		mvc.perform(post("/api/v1/products/source-refresh/collections").principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
			.content("{\"requestId\":\"" + id + "\",\"productIds\":[1,2],\"actor\":\"other\",\"sourceUrl\":\"http://localhost\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
		verify(service).collect(argThat(request -> request.productIds().equals(List.of(1L, 2L))), eq("admin"));
	}

	@Test void commitAcceptsOnlyReviewIdAndUsesPrincipal() throws Exception {
		when(service.commit(id, "admin")).thenReturn(new ProductEditService.CommitResult(id, List.of()));
		mvc.perform(post("/api/v1/products/source-refresh/commit").principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
			.content("{\"reviewId\":\"" + id + "\",\"actor\":\"other\",\"detailHtml\":\"injected\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.reviewId").value(id));
		verify(service).commit(id, "admin");
	}

	@Test void readRoutesPassPrincipalToOwnershipCheck() throws Exception {
		when(service.collection(id, "admin")).thenReturn(new ProductSourceService.Collection(id, Instant.now(), List.of()));
		when(service.history(7L, "admin")).thenReturn(List.of());
		mvc.perform(get("/api/v1/products/source-refresh/collections/" + id).principal(() -> "admin")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/products/source-refresh/7/history").principal(() -> "admin")).andExpect(status().isOk());
		verify(service).collection(id, "admin"); verify(service).history(7L, "admin");
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"requestId\":\"bad\",\"productIds\":[1]}",
		"{\"requestId\":\"11111111-1111-1111-1111-111111111111\",\"productIds\":[1,1]}",
		"{\"requestId\":\"11111111-1111-1111-1111-111111111111\",\"productIds\":[]}"})
	void invalidCollectionIsRejectedBeforeService(String body) throws Exception {
		mvc.perform(post("/api/v1/products/source-refresh/collections").principal(() -> "admin")
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void invalidReviewFieldCannotCreateAnEditPlan() throws Exception {
		mvc.perform(
			post("/api/v1/products/source-refresh/reviews").principal(() -> "admin")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"items\":[{\"snapshotId\":\"" + id + "\",\"fields\":[\"IMAGES\"]}]}"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}
}
