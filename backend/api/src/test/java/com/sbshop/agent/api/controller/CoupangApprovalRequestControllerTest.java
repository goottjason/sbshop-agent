package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sbshop.agent.api.exception.GlobalExceptionHandler;
import com.sbshop.agent.core.application.market.MarketApprovalRequestService;
import com.sbshop.agent.core.application.market.dto.MarketApprovalReport;
import com.sbshop.agent.core.application.market.dto.MarketApprovalRequestCommand;
import com.sbshop.agent.core.domain.market.client.dto.MarketApprovalResult;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CoupangApprovalRequestControllerTest {

	@Mock
	private MarketApprovalRequestService marketApprovalRequestService;

	private MockMvc mockMvc;

	private static final String PATH = "/api/v1/products/coupang/approval-requests";

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new CoupangApprovalRequestController(marketApprovalRequestService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(
				JsonMapper.builder().addModule(new JavaTimeModule()).build()))
			.build();
	}

	@Test
	@DisplayName("D-215: 명시한 ID만 승인 요청하고 건별 결과를 그대로 돌려준다")
	void requestsExplicitIdsAndReturnsPerItemResults() throws Exception {
		when(marketApprovalRequestService.request(eq(MarketType.COUPANG), any())).thenReturn(
			MarketApprovalReport.of(MarketType.COUPANG, 600L, 12L, List.of(
				MarketApprovalResult.requested("14300000001", "임시저장", "SUCCESS", "성공"),
				MarketApprovalResult.skipped("14300000002", "승인완료", "대상 상태가 아닙니다"))));

		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
				.content("{\"sellerProductIds\":[\"14300000001\",\"14300000002\"],\"throttleMs\":900}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.submitted").value(2))
			.andExpect(jsonPath("$.requested").value(1))
			.andExpect(jsonPath("$.skipped").value(1))
			.andExpect(jsonPath("$.items[0].marketItemId").value("14300000001"))
			.andExpect(jsonPath("$.items[0].outcome").value("REQUESTED"))
			.andExpect(jsonPath("$.items[0].priorStatus").value("임시저장"))
			.andExpect(jsonPath("$.items[0].called").value(true))
			.andExpect(jsonPath("$.items[1].outcome").value("SKIPPED"))
			.andExpect(jsonPath("$.items[1].note").value("대상 상태가 아닙니다"));

		ArgumentCaptor<MarketApprovalRequestCommand> captor =
			ArgumentCaptor.forClass(MarketApprovalRequestCommand.class);
		verify(marketApprovalRequestService).request(eq(MarketType.COUPANG), captor.capture());
		assertThat(captor.getValue().marketItemIds()).containsExactly("14300000001", "14300000002");
		assertThat(captor.getValue().throttleMs()).isEqualTo(900L);
	}

	@Test
	@DisplayName("D-215: 상한을 넘는 대상은 400 으로 거부하고 마켓을 건드리지 않는다")
	void rejectsOverLimit() throws Exception {
		String ids = IntStream.rangeClosed(0, MarketApprovalRequestCommand.MAX_TARGETS)
			.mapToObj(i -> "\"" + (14300000000L + i) + "\"")
			.collect(Collectors.joining(","));

		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sellerProductIds\":[" + ids + "]}"))
			.andExpect(status().isBadRequest());

		verify(marketApprovalRequestService, never()).request(any(), any());
	}

	@Test
	@DisplayName("D-215: 대상 없는 요청은 400 — 전건 스윕으로 해석될 여지를 없앤다")
	void rejectsMissingTargets() throws Exception {
		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
			.content("{\"sellerProductIds\":[]}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());

		verify(marketApprovalRequestService, never()).request(any(), any());
	}

	@Test
	@DisplayName("D-215: 쓰로틀 미지정이면 기본값으로 내려간다")
	void defaultsThrottle() throws Exception {
		when(marketApprovalRequestService.request(eq(MarketType.COUPANG), any()))
			.thenReturn(MarketApprovalReport.of(MarketType.COUPANG, MarketApprovalRequestCommand.DEFAULT_THROTTLE_MS,
				1L, List.of()));

		mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
				.content("{\"sellerProductIds\":[\"14300000001\"]}"))
			.andExpect(status().isOk());

		ArgumentCaptor<MarketApprovalRequestCommand> captor =
			ArgumentCaptor.forClass(MarketApprovalRequestCommand.class);
		verify(marketApprovalRequestService).request(eq(MarketType.COUPANG), captor.capture());
		assertThat(captor.getValue().throttleMs()).isEqualTo(MarketApprovalRequestCommand.DEFAULT_THROTTLE_MS);
	}
}
