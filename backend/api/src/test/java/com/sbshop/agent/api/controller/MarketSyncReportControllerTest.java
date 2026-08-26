package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sbshop.agent.api.exception.GlobalExceptionHandler;
import com.sbshop.agent.core.application.market.MarketCatalogReconciliationService;
import com.sbshop.agent.core.application.market.dto.MarketSyncBucket;
import com.sbshop.agent.core.application.market.dto.MarketSyncMarketReport;
import com.sbshop.agent.core.application.market.dto.MarketSyncOutcome;
import com.sbshop.agent.core.application.market.dto.MarketSyncReport;
import com.sbshop.agent.core.application.market.dto.MarketSyncReportRequest;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketSyncReportControllerTest {

	@Mock
	private MarketCatalogReconciliationService marketCatalogReconciliationService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new MarketSyncReportController(marketCatalogReconciliationService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(
				JsonMapper.builder().addModule(new JavaTimeModule()).build()))
			.build();
		when(marketCatalogReconciliationService.reconcile(any())).thenReturn(emptyReport());
	}

	@Test
	@DisplayName("파라미터 없이 호출하면 전 마켓·기본 limit·deep 미적용으로 대조한다")
	void defaultsWhenNoParams() throws Exception {
		mockMvc.perform(get("/api/v1/products/market-sync/report")).andExpect(status().isOk());

		MarketSyncReportRequest request = captureRequest();
		assertThat(request.markets()).isEqualTo(MarketSyncReportRequest.DEFAULT_MARKETS);
		assertThat(request.sampleLimit()).isEqualTo(MarketSyncReportRequest.DEFAULT_SAMPLE_LIMIT);
		assertThat(request.deep()).isFalse();
	}

	@Test
	@DisplayName("markets는 콤마 구분·대소문자·공백을 허용하고 요청 순서를 유지한다")
	void parsesMarketsParam() throws Exception {
		mockMvc.perform(get("/api/v1/products/market-sync/report").param("markets", " coupang , SMART_STORE "))
			.andExpect(status().isOk());

		assertThat(captureRequest().markets()).containsExactly(MarketType.COUPANG, MarketType.SMART_STORE);
	}

	@Test
	@DisplayName("알 수 없는 마켓명은 400으로 거부하고 서비스를 호출하지 않는다")
	void rejectsUnknownMarket() throws Exception {
		mockMvc.perform(get("/api/v1/products/market-sync/report").param("markets", "GOOGLE"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").exists());

		verify(marketCatalogReconciliationService, never()).reconcile(any());
	}

	@Test
	@DisplayName("limit·deep·deepLimit·throttleMs가 서비스 요청으로 전달된다")
	void forwardsTuningParams() throws Exception {
		mockMvc.perform(get("/api/v1/products/market-sync/report")
			.param("limit", "50")
			.param("deep", "true")
			.param("deepLimit", "7")
			.param("throttleMs", "150")).andExpect(status().isOk());

		MarketSyncReportRequest request = captureRequest();
		assertThat(request.sampleLimit()).isEqualTo(50);
		assertThat(request.deep()).isTrue();
		assertThat(request.deepLimit()).isEqualTo(7);
		assertThat(request.throttleMs()).isEqualTo(150L);
	}

	@Test
	@DisplayName("기본 throttleMs 는 250 — 상품당 2콜이라 200 이면 쿠팡 초당 5회 한도에 여유가 0이다")
	void defaultThrottleLeavesHeadroom() throws Exception {
		mockMvc.perform(get("/api/v1/products/market-sync/report")).andExpect(status().isOk());

		assertThat(captureRequest().throttleMs()).isEqualTo(250L);
		assertThat(MarketSyncReportRequest.DEFAULT_THROTTLE_MS).isEqualTo(250L);
	}

	@Test
	@DisplayName("liveInventory·liveLimit 은 기본 꺼짐이고 파라미터로만 켜진다")
	void liveInventoryOffByDefault() throws Exception {
		mockMvc.perform(get("/api/v1/products/market-sync/report")).andExpect(status().isOk());
		assertThat(captureRequest().liveInventory()).isFalse();
	}

	@Test
	@DisplayName("liveInventory=true·liveLimit 이 서비스 요청으로 전달되고 상한으로 잘린다")
	void forwardsLiveInventoryParams() throws Exception {
		mockMvc.perform(get("/api/v1/products/market-sync/report")
			.param("liveInventory", "true")
			.param("liveLimit", "999999")).andExpect(status().isOk());

		MarketSyncReportRequest request = captureRequest();
		assertThat(request.liveInventory()).isTrue();
		assertThat(request.liveLimit()).isEqualTo(MarketSyncReportRequest.MAX_LIVE_LIMIT);
	}

	@Test
	@DisplayName("limit 상한을 넘기면 상한값으로 잘라 무한 응답을 막는다")
	void clampsLimit() throws Exception {
		mockMvc.perform(get("/api/v1/products/market-sync/report").param("limit", "99999"))
			.andExpect(status().isOk());

		assertThat(captureRequest().sampleLimit()).isEqualTo(MarketSyncReportRequest.MAX_SAMPLE_LIMIT);
	}

	@Test
	@DisplayName("응답은 마켓별 버킷 건수·상태 분포·실패 사유를 그대로 싣는다")
	void serializesMarketSummary() throws Exception {
		when(marketCatalogReconciliationService.reconcile(any())).thenReturn(sampleReport());

		mockMvc.perform(get("/api/v1/products/market-sync/report"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.markets[0].market").value("COUPANG"))
			.andExpect(jsonPath("$.markets[0].bucketCounts.STALE_LOCAL").value(3))
			.andExpect(jsonPath("$.markets[0].marketStatusCounts.DELETED").value(2))
			.andExpect(jsonPath("$.markets[1].outcome").value("FAILED"))
			.andExpect(jsonPath("$.markets[1].failureReason").value("-997 등록된 API 정보가 존재하지 않습니다"));
	}

	@Test
	@DisplayName("응답 키는 배치 트리거 관례(batchId·count·message)와 겹치지 않는다")
	void doesNotCollideWithBatchTriggerKeys() throws Exception {
		mockMvc.perform(get("/api/v1/products/market-sync/report"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.batchId").doesNotExist())
			.andExpect(jsonPath("$.count").doesNotExist())
			.andExpect(jsonPath("$.message").doesNotExist())
			.andExpect(jsonPath("$.generatedAt").exists());
	}

	private MarketSyncReportRequest captureRequest() {
		ArgumentCaptor<MarketSyncReportRequest> captor = ArgumentCaptor.forClass(MarketSyncReportRequest.class);
		verify(marketCatalogReconciliationService).reconcile(captor.capture());
		return captor.getValue();
	}

	private MarketSyncReport emptyReport() {
		return new MarketSyncReport(LocalDateTime.now(), 20, false, 1L, List.of());
	}

	private MarketSyncReport sampleReport() {
		MarketSyncMarketReport coupang = new MarketSyncMarketReport(
			MarketType.COUPANG.name(), MarketType.COUPANG.getLabel(), MarketSyncOutcome.COMPLETED, null,
			1262, 1261, 1, 1200, 0, 0, 1197,
			Map.of(MarketSyncBucket.MATCHED, 1197, MarketSyncBucket.STALE_LOCAL, 3),
			Map.of("APPROVED", 1198, "DELETED", 2),
			Map.of(), 0, false, 12L, List.of(), null);
		MarketSyncMarketReport eleven = new MarketSyncMarketReport(
			MarketType.ELEVEN_STREET.name(), MarketType.ELEVEN_STREET.getLabel(), MarketSyncOutcome.FAILED,
			"-997 등록된 API 정보가 존재하지 않습니다", 2286, 2286, 0, 0, 0, 0, 0,
			Map.of(), Map.of(), Map.of(), 0, false, 3L, List.of(), null);
		return new MarketSyncReport(LocalDateTime.now(), 20, false, 15L, List.of(coupang, eleven));
	}
}
