package com.sbshop.agent.api.controller;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sbshop.agent.core.application.product.retirement.ProductRetirementService;
import com.sbshop.agent.core.application.product.retirement.ProductRetirementService.EsmConfirmation;
import com.sbshop.agent.core.application.product.retirement.ProductRetirementService.StartResult;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ProductRetirementControllerTest {

	@Mock
	private ProductRetirementService service;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new ProductRetirementController(service)).build();
	}

	@Test
	void startDefaultsToDryRun() throws Exception {
		when(service.start(List.of(1L, 2L), true)).thenReturn(List.of(new StartResult(1L, "SB1", "WOULD_START")));

		mockMvc.perform(post("/internal/retirement/start").contentType(MediaType.APPLICATION_JSON).content("[1,2]"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dryRun").value(true))
			.andExpect(jsonPath("$.results[0].result").value("WOULD_START"));
	}

	@Test
	void advanceDefaultsToDryRunWithNaverSafeThrottle() throws Exception {
		when(service.advance(20, 1200L, true)).thenReturn(List.of());

		mockMvc.perform(post("/internal/retirement/advance")).andExpect(status().isOk())
			.andExpect(jsonPath("$.dryRun").value(true));

		verify(service).advance(20, 1200L, true);
	}

	@Test
	void concurrentAdvanceIsConflict() throws Exception {
		when(service.advance(20, 1200L, false))
			.thenThrow(new ProductRetirementService.RetirementAlreadyRunningException());

		mockMvc.perform(post("/internal/retirement/advance").param("dryRun", "false"))
			.andExpect(status().isConflict());
	}

	@Test
	void otherFailuresAreNotReportedAsAlreadyRunning() {
		when(service.advance(20, 1200L, false)).thenThrow(new IllegalStateException("퇴출 보고 직렬화 실패"));

		org.assertj.core.api.Assertions.assertThatThrownBy(
			() -> mockMvc.perform(post("/internal/retirement/advance").param("dryRun", "false")))
			.hasRootCauseMessage("퇴출 보고 직렬화 실패");
	}

	@Test
	void candidatesAndEsmEndpoints() throws Exception {
		when(service.candidates(VendorType.IHB, 100)).thenReturn(List.of());
		when(service.confirmEsmAbsent(anyList())).thenReturn(new EsmConfirmation(List.of("SB1"), List.of("SB9")));
		when(service.summary()).thenReturn(new ProductRetirementService.Summary(Map.of("DONE", 1L), Map.of()));

		mockMvc.perform(get("/internal/retirement/candidates").param("vendor", "ihb")).andExpect(status().isOk());
		mockMvc.perform(post("/internal/retirement/esm-confirm").contentType(MediaType.APPLICATION_JSON)
				.content("[\"SB1\",\"SB9\"]"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.confirmed[0]").value("SB1"))
			.andExpect(jsonPath("$.unmatched[0]").value("SB9"));
		mockMvc.perform(get("/internal/retirement/summary")).andExpect(status().isOk())
			.andExpect(jsonPath("$.statuses.DONE").value(1));
	}
}
