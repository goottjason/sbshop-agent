package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.dto.PriceStockItem;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.event.BatchCompletedEvent;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchPartialFailureTest {
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

	private BatchPriceStockService service;

	private static final Long PRODUCT_ID = 3020L;
	private static final String KEY = "3020";
	private static final String SB_CODE = "231114IHB021";
	private static final String URL = "https://example.com/item/3020";

	private static MarketRepublishResult allGreen() {
		return new MarketRepublishResult(List.of(MarketType.COUPANG), List.of(), new LinkedHashMap<>());
	}

	private static MarketRepublishResult storeRejected() {
		Map<MarketType, String> failed = new LinkedHashMap<>();
		failed.put(MarketType.SMART_STORE, "판매가 항목은 10원 단위로 입력해 주세요.");
		return new MarketRepublishResult(
			List.of(MarketType.COUPANG, MarketType.ELEVEN_STREET), List.of(MarketType.CAFE24), failed);
	}

	@BeforeEach
	void setUp() {
		service = new BatchPriceStockService(productReader, productWriter, productRepository,
			stockCrawlerRouter, processStatusService, marginCalculator, eventPublisher,
			productMarketSyncService, marketFeeService, vendorPricePolicyService);

		lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		lenient().when(product.getSbCode()).thenReturn(SB_CODE);
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
	}

	private void crawlReturns(StockStatus status) {
		when(stockCrawlerRouter.checkStockWithDetails(any(), eq(URL)))
			.thenReturn(new StockCheckResult(status, new BigDecimal("5691"), 0, null));
	}

	private void runCrawlBatch() {
		service.crawlAndUpdatePriceStock("b1", List.of(PRODUCT_ID),
			new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5000"),
			ActionLogConstants.BATCH_CRAWL_UPDATE);
	}

	@Test
	@DisplayName("D-269-A: 마켓 하나가 거부되면 성공이 아니라 부분실패로 집계한다")
	void crawlBatch_marketRejected_marksPartialFailed() {
		crawlReturns(StockStatus.IN_STOCK);
		when(productMarketSyncService.syncPriceStockPerMarket(any(), any(), any(StockStatus.class), anyBoolean()))
			.thenReturn(storeRejected());

		runCrawlBatch();

		verify(processStatusService).markPartialFailed(eq("b1"), eq(KEY), contains("SMART_STORE"), anyString());
		verify(processStatusService, never()).markSuccess(eq("b1"), eq(KEY), anyString(), anyString());
	}

	@Test
	@DisplayName("D-269-A: 모든 마켓이 성공하면 그대로 성공으로 집계한다")
	void crawlBatch_allMarketsGreen_marksSuccess() {
		crawlReturns(StockStatus.IN_STOCK);
		when(productMarketSyncService.syncPriceStockPerMarket(any(), any(), any(StockStatus.class), anyBoolean()))
			.thenReturn(allGreen());

		runCrawlBatch();

		verify(processStatusService).markSuccess(eq("b1"), eq(KEY), anyString(), anyString());
		verify(processStatusService, never()).markPartialFailed(eq("b1"), eq(KEY), anyString(), anyString());
	}

	@Test
	@DisplayName("D-269-A: 소스 링크가 죽은 상품도 마켓 거부가 있으면 부분실패다")
	void crawlBatch_sourceGoneWithMarketRejection_marksPartialFailed() {
		when(stockCrawlerRouter.checkStockWithDetails(any(), eq(URL)))
			.thenReturn(new StockCheckResult(StockStatus.OUT_OF_STOCK, null, 0, null, true, null,
				com.sbshop.agent.core.domain.product.enums.SourceGoneReason.LINK_DEAD));
		when(productMarketSyncService.syncPriceStockPerMarket(any(), any(), any(StockStatus.class), anyBoolean()))
			.thenReturn(storeRejected());

		runCrawlBatch();

		verify(processStatusService).markPartialFailed(eq("b1"), eq(KEY), contains("SMART_STORE"), anyString());
		verify(processStatusService, never()).markSuccess(eq("b1"), eq(KEY), anyString(), anyString());
	}

	@Test
	@DisplayName("D-269-A: 수동 배치도 마켓 거부가 있으면 부분실패로 집계한다")
	void manualBatch_marketRejected_marksPartialFailed() {
		lenient().when(product.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
		lenient().when(product.getSalePrice()).thenReturn(new BigDecimal("18800"));
		when(productMarketSyncService.syncPriceStock(any(), any(), any(StockStatus.class), anyBoolean()))
			.thenReturn(storeRejected());

		service.manualUpdatePriceStock("b2", List.of(
			new PriceStockItem(PRODUCT_ID, new BigDecimal("20500"), 50)));

		verify(processStatusService).markPartialFailed(eq("b2"), eq(KEY), contains("SMART_STORE"), anyString());
		verify(processStatusService, never()).markSuccess(eq("b2"), eq(KEY), anyString(), anyString());
	}

	@Test
	@DisplayName("D-269-A: 부분실패가 있으면 배치 완료 이벤트도 성공이라고 말하지 않는다")
	void crawlBatch_partialFailure_eventIsNotSuccess() {
		crawlReturns(StockStatus.IN_STOCK);
		when(productMarketSyncService.syncPriceStockPerMarket(any(), any(), any(StockStatus.class), anyBoolean()))
			.thenReturn(storeRejected());

		runCrawlBatch();

		ArgumentCaptor<BatchCompletedEvent> captor = ArgumentCaptor.forClass(BatchCompletedEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());
		BatchCompletedEvent event = captor.getValue();
		assertThat(event.isSuccess()).isFalse();
		assertThat(event.getMessage()).contains("부분실패 1건");
	}
}
