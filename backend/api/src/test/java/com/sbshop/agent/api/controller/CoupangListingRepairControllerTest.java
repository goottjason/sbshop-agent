package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sbshop.agent.core.application.market.CoupangListingRepairUseCase;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CoupangListingRepairControllerTest {

	@Mock
	private CoupangListingRepairUseCase useCase;

	private MockMvc mockMvc;

	private static final String PATH = "/internal/coupang/listing-repair";

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new CoupangListingRepairController(useCase)).build();
	}

	@Test
	@DisplayName("D-340: 기본은 미리보기(dryRun=true)·500ms 쓰로틀로 지정 ID 를 넘긴다")
	void defaultsToDryRun() throws Exception {
		when(useCase.repair(any())).thenReturn(List.of(
			new CoupangListingRepairUseCase.Outcome(1L, "SB1", "14300000001", "DRY_RUN", null, null)));

		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
				.content("{\"sellerProductIds\":[\"14300000001\"]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.dryRun").value(true))
			.andExpect(jsonPath("$.results[0].result").value("DRY_RUN"));

		ArgumentCaptor<CoupangListingRepairUseCase.Command> captor =
			ArgumentCaptor.forClass(CoupangListingRepairUseCase.Command.class);
		verify(useCase).repair(captor.capture());
		assertThat(captor.getValue().dryRun()).isTrue();
		assertThat(captor.getValue().throttleMs()).isEqualTo(500L);
		assertThat(captor.getValue().sellerProductIds()).containsExactly("14300000001");
	}

	@Test
	@DisplayName("D-340: 브랜드 지정과 dryRun=false 를 그대로 넘긴다")
	void passesBrandAndSubmit() throws Exception {
		when(useCase.repair(any())).thenReturn(List.of());

		mockMvc.perform(post(PATH + "?dryRun=false&throttleMs=900").contentType(MediaType.APPLICATION_JSON)
				.content("{\"brand\":\"NOW Foods\"}"))
			.andExpect(status().isOk());

		ArgumentCaptor<CoupangListingRepairUseCase.Command> captor =
			ArgumentCaptor.forClass(CoupangListingRepairUseCase.Command.class);
		verify(useCase).repair(captor.capture());
		assertThat(captor.getValue().brand()).isEqualTo("NOW Foods");
		assertThat(captor.getValue().dryRun()).isFalse();
		assertThat(captor.getValue().throttleMs()).isEqualTo(900L);
	}

	@Test
	@DisplayName("D-341: 브랜드·제조사 변경 내역(fieldChanges)이 응답에 나온다")
	void exposesFieldChanges() throws Exception {
		var repair = new com.sbshop.agent.core.domain.market.client.dto.ListingAttributeRepair("14300000001", "승인반려",
			List.of(), List.of(), List.of(),
			com.sbshop.agent.core.domain.market.client.dto.ListingAttributeRepairOutcome.DRY_RUN, null,
			List.of("brand: 자체브랜드→닥터스베스트"));
		when(useCase.repair(any())).thenReturn(List.of(
			new CoupangListingRepairUseCase.Outcome(1L, "SB1", "14300000001", "DRY_RUN", repair, null)));

		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sellerProductIds\":[\"14300000001\"]}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.results[0].repair.fieldChanges[0]").value("brand: 자체브랜드→닥터스베스트"));
	}

	@Test
	@DisplayName("D-340: ID·브랜드가 모두 비면 400")
	void rejectsEmptyTargets() throws Exception {
		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sellerProductIds\":[],\"brand\":\" \"}"))
			.andExpect(status().isBadRequest());

		verify(useCase, never()).repair(any());
	}
}
