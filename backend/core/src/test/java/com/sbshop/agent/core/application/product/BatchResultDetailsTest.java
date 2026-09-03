package com.sbshop.agent.core.application.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchResultDetailsTest {
	@Mock
	private ProductReader productReader;
	@Mock
	private ProductWriter productWriter;
	@Mock
	private ProductRepository productRepository;
	@Mock
	private StockCrawlerRouter stockCrawlerRouter;
	@Mock
	private ProcessStatusService processStatusService;
	@Mock
	private MarginCalculator marginCalculator;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private ProductMarketSyncService productMarketSyncService;
	@Mock
	private MarketFeeService marketFeeService;
	@Mock
	private VendorPricePolicyService vendorPricePolicyService;
	@Mock
	private Product product;

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Long PRODUCT_ID = 3020L;
	private static final String KEY = "3020";
	private static final String URL = "https://example.com/item/3020";
	private static final String STORE_REASON =
		"400 Bad Request: {\"message\":\"판매가 항목은 10원 단위로 입력해 주세요.\"}";

	private BatchPriceStockService service;

	@BeforeEach
	void setUp() {
		service = new BatchPriceStockService(productReader, productWriter, productRepository,
			stockCrawlerRouter, processStatusService, marginCalculator, eventPublisher,
			productMarketSyncService, marketFeeService, vendorPricePolicyService);

		lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		lenient().when(product.getSbCode()).thenReturn("231114IHB021");
		lenient().when(product.getLogisticsInfo()).thenReturn(null);
		lenient().when(product.getSourcingUrl()).thenReturn(URL);
		lenient().when(marketFeeService.feeRate(any())).thenReturn(new BigDecimal("11"));
		lenient().when(vendorPricePolicyService.find(any())).thenReturn(Optional.of(
			com.sbshop.agent.core.domain.pricing.VendorPricePolicy.builder()
				.shipBaseAmount(BigDecimal.ZERO).build()));
		lenient().when(marginCalculator.calculateSalePrice(any(), any(Integer.class), any(), any()))
			.thenReturn(new BigDecimal("20500"));
		lenient().when(marginCalculator.calculateSalePrice(any(), any(Integer.class), any(), any(), any(), any()))
			.thenReturn(new BigDecimal("20500"));
		lenient().when(stockCrawlerRouter.checkStockWithDetails(any(), eq(URL)))
			.thenReturn(new StockCheckResult(StockStatus.IN_STOCK, new BigDecimal("5691"), 3, null));
	}

	private void syncReturns(MarketRepublishResult result) {
		when(productMarketSyncService.syncPriceStockPerMarket(any(), any(), any(StockStatus.class), anyBoolean()))
			.thenReturn(result);
	}

	private void runBatch() {
		service.crawlAndUpdatePriceStock("b1", List.of(PRODUCT_ID),
			new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5000"),
			ActionLogConstants.BATCH_CRAWL_UPDATE);
	}

	@Test
	@DisplayName("D-269-B: 마켓별 결과를 화면이 파싱 없이 읽도록 구조화해 details 에 남긴다")
	void marketOutcomeIsStoredAsStructuredDetails() throws Exception {
		Map<MarketType, String> failed = new LinkedHashMap<>();
		failed.put(MarketType.SMART_STORE, STORE_REASON);
		syncReturns(new MarketRepublishResult(
			List.of(MarketType.COUPANG, MarketType.ELEVEN_STREET), List.of(MarketType.CAFE24), failed));

		runBatch();

		ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
		verify(processStatusService).markPartialFailed(eq("b1"), eq(KEY), anyString(), details.capture());

		JsonNode node = MAPPER.readTree(details.getValue());
		assertThat(node.get("synced")).hasSize(2);
		assertThat(node.get("skipped").get(0).asText()).isEqualTo("CAFE24");
		assertThat(node.get("failed")).hasSize(1);
		assertThat(node.get("failed").get(0).get("market").asText()).isEqualTo("SMART_STORE");
		assertThat(node.get("failed").get(0).get("reason").asText()).isEqualTo(STORE_REASON);
	}

	@Test
	@DisplayName("D-269-B: 사유에 따옴표와 중괄호가 있어도 details 는 깨지지 않는 JSON 이다")
	void detailsSurviveQuotesInReason() throws Exception {
		Map<MarketType, String> failed = new LinkedHashMap<>();
		failed.put(MarketType.SMART_STORE, STORE_REASON);
		syncReturns(new MarketRepublishResult(List.of(), List.of(), failed));

		runBatch();

		ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
		verify(processStatusService).markPartialFailed(eq("b1"), eq(KEY), anyString(), details.capture());

		assertThat(MAPPER.readTree(details.getValue()).get("failed").get(0).get("reason").asText())
			.isEqualTo(STORE_REASON);
	}

	@Test
	@DisplayName("D-269-B: 모두 성공해도 어느 마켓에 갔는지 details 에 남긴다")
	void successAlsoRecordsWhichMarketsReceivedIt() throws Exception {
		syncReturns(new MarketRepublishResult(List.of(MarketType.COUPANG), List.of(), new LinkedHashMap<>()));

		runBatch();

		ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
		verify(processStatusService).markSuccess(eq("b1"), eq(KEY), anyString(), details.capture());

		JsonNode node = MAPPER.readTree(details.getValue());
		assertThat(node.get("synced").get(0).asText()).isEqualTo("COUPANG");
		assertThat(node.get("failed")).isEmpty();
	}
}
