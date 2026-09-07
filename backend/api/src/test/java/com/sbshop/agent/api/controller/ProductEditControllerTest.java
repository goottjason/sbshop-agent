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
}
