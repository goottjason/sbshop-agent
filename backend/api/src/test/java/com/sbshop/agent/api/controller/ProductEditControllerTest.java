package com.sbshop.agent.api.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sbshop.agent.api.exception.GlobalExceptionHandler;
import com.sbshop.agent.core.application.product.edit.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProductEditControllerTest {
	ProductEditService edits = mock(ProductEditService.class);
	MockMvc mvc;
	String token = UUID.randomUUID().toString();

	@BeforeEach
	void setup() {
		mvc = MockMvcBuilders.standaloneSetup(new ProductEditController(edits))
			.setControllerAdvice(new GlobalExceptionHandler()).build();
	}

	@Test void commitsOnlyServerReviewAndAuthenticatedActor() throws Exception {
        when(edits.commit(token, "admin")).thenReturn(new ProductEditService.CommitResult(token, List.of(new ProductEditService.CommitItem(1L, "SB1", "SAVED", 42L, "DB 저장 완료"))));
        mvc.perform(post("/api/v1/products/changes/commit").principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
            .content("{\"reviewId\":\"" + token + "\",\"actor\":\"other\",\"salePrice\":1}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].historyId").value(42)).andExpect(jsonPath("$.items[0].state").value("SAVED"));
        verify(edits).commit(token, "admin");
    }

	@Test void singlePreviewRequiresVersionAndUsesActorFromPrincipal() throws Exception {
        when(edits.previewSingle(eq(1L), eq(3L), any(), eq("admin"))).thenReturn(new ProductEditService.Review(token, Instant.now().plusSeconds(1800), List.of()));
        mvc.perform(post("/api/v1/products/1/changes/preview").principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":3,\"values\":{\"memo\":\"확인\"}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviewId").value(token));
        verify(edits).previewSingle(eq(1L), eq(3L), argThat(v -> v.get("memo").asText().equals("확인")), eq("admin"));
    }

	@Test void conflictReturns409() throws Exception {
        when(edits.previewSingle(anyLong(), anyLong(), any(), anyString())).thenThrow(new ProductEditConflictException("stale"));
        mvc.perform(post("/api/v1/products/1/changes/preview").principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":3,\"values\":{\"memo\":\"확인\"}}"))
            .andExpect(status().isConflict());
    }

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"values\":{}}", "{\"expectedRevision\":-1,\"values\":{}}",
		"{\"expectedRevision\":0,\"values\":[]}"})
	void malformedSingleReviewsReturn400WithoutCallingService(String body) throws Exception {
		mvc.perform(post("/api/v1/products/1/changes/preview").principal(() -> "admin")
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
		verifyNoInteractions(edits);
	}

	@Test
	void numericPreviewDoesNotAcceptInvalidField() throws Exception {
		mvc.perform(
			post("/api/v1/products/changes/preview").principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
				.content(
					"{\"productIds\":[1],\"changes\":[{\"field\":\"DATABASE_ID\",\"operation\":\"SET\",\"value\":1}]}"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(edits);
	}

	@Test
	void invalidCommitTokenIsRejectedBeforeService() throws Exception {
		mvc.perform(post("/api/v1/products/changes/commit").principal(() -> "admin")
			.contentType(MediaType.APPLICATION_JSON).content("{\"reviewId\":\"bad\"}"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(edits);
	}

	@Test
	void bulkValuesUseAuthenticatedActorAndOnlyExplicitValues() throws Exception {
		when(edits.previewValues(any(), eq("admin"))).thenReturn(new ProductEditService.Review(token, Instant.now().plusSeconds(1800), List.of()));
		mvc.perform(post("/api/v1/products/changes/values-preview").principal(() -> "admin").contentType(MediaType.APPLICATION_JSON)
			.content("{\"productIds\":[1,2],\"values\":{\"memo\":\"확인\"},\"actor\":\"other\"}"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.reviewId").value(token));
		verify(edits).previewValues(argThat(request -> request.productIds().equals(List.of(1L, 2L)) && request.values().size() == 1 && request.values().get("memo").asText().equals("확인")), eq("admin"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"productIds\":[],\"values\":{\"memo\":\"x\"}}",
		"{\"productIds\":[1,1],\"values\":{\"memo\":\"x\"}}",
		"{\"productIds\":[1],\"values\":{}}", "{\"productIds\":[1],\"values\":{\"revision\":1}}",
		"{\"productIds\":[1],\"values\":{\"salePrice\":1}}",
		"{\"productIds\":[1],\"values\":{\"memo\":null}}",
		"{\"productIds\":[1],\"values\":{\"memo\":{\"nested\":true}}}",
		"{\"productIds\":[1],\"values\":{\"category\":\"WRONG\"}}", "{\"productIds\":[1],\"values\":{\"name\":\"\"}}",
		"{\"productIds\":[1],\"values\":{\"hostedImages\":[\"javascript:alert(1)\"]}}",
		"{\"productIds\":[1],\"values\":{\"sourceUrl\":\"/relative\"}}",
		"{\"productIds\":[1.9],\"values\":{\"memo\":\"잘못된 상품 ID\"}}",
		"{\"productIds\":[\"1\"],\"values\":{\"memo\":\"문자열 상품 ID\"}}"})
	void invalidBulkValuesAreRejectedBeforeService(String body) throws Exception {
		mvc.perform(post("/api/v1/products/changes/values-preview").principal(() -> "admin")
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(edits);
	}

	@Test
	void bulkValuesRejectMoreThanFiveHundredProducts() throws Exception {
		String ids = java.util.stream.LongStream.rangeClosed(1, 501).mapToObj(Long::toString)
			.collect(java.util.stream.Collectors.joining(","));
		mvc.perform(post("/api/v1/products/changes/values-preview").principal(() -> "admin")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"productIds\":[" + ids + "],\"values\":{\"memo\":\"x\"}}"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(edits);
	}

	@Test
	void bulkCatalogAdvertisesOnlyAllowedNonNumericFields() throws Exception {
		mvc.perform(get("/api/v1/products/changes/values-preview/fields").principal(() -> "admin"))
			.andExpect(status().isOk()).andExpect(jsonPath("$[?(@.field=='memo')].maxLength").value(2000))
			.andExpect(jsonPath("$[?(@.field=='salePrice')]").isEmpty())
			.andExpect(jsonPath("$[?(@.field=='revision')]").isEmpty());
	}
}
