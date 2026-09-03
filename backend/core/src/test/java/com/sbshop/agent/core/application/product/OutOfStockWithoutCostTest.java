package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.dto.PricingInputs;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutOfStockWithoutCostTest {
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

	private static final Long PRODUCT_ID = 1558L;
	private static final String KEY = "1558";
	private static final String SB_CODE = "220220IHB115";
	private static final String URL = "https://kr.iherb.com/pr/x/1558";

	@BeforeEach
	void setUp() {
		service = new BatchPriceStockService(productReader, productWriter, productRepository,
			stockCrawlerRouter, processStatusService, marginCalculator, eventPublisher,
			productMarketSyncService, marketFeeService, vendorPricePolicyService);

		lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		lenient().when(product.getSbCode()).thenReturn(SB_CODE);
		lenient().when(product.getSourcingUrl()).thenReturn(URL);
		lenient().when(product.getLogisticsInfo()).thenReturn(null);
		lenient().when(product.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
		lenient().when(marketFeeService.feeRate(any())).thenReturn(new BigDecimal("11"));
		lenient().when(vendorPricePolicyService.find(any())).thenReturn(Optional.of(
			com.sbshop.agent.core.domain.pricing.VendorPricePolicy.builder()
				.shipBaseAmount(BigDecimal.ZERO).build()));
		lenient()
			.when(productMarketSyncService.syncPriceStockPerMarket(any(), any(), any(StockStatus.class), anyBoolean()))
			.thenReturn(new MarketRepublishResult(
				List.of(MarketType.COUPANG, MarketType.SMART_STORE), List.of(), new LinkedHashMap<>()));
	}

	private void crawlReturns(StockStatus status, BigDecimal costPrice) {
		when(stockCrawlerRouter.checkStockWithDetails(any(), eq(URL)))
			.thenReturn(new StockCheckResult(status, costPrice, 0, null));
	}

	private void runBatch() {
		service.crawlAndUpdatePriceStock("b1", List.of(PRODUCT_ID),
			new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5000"),
			ActionLogConstants.BATCH_CRAWL_UPDATE);
	}

	@Test
	@DisplayName("D-283: 품절이라 원가가 없어도 재고 0 을 마켓에 보낸다")
	void outOfStockWithoutCost_stillPushesStockToMarkets() {
		crawlReturns(StockStatus.OUT_OF_STOCK, null);

		runBatch();

		ArgumentCaptor<StockStatus> status = ArgumentCaptor.forClass(StockStatus.class);
		verify(productMarketSyncService).syncPriceStockPerMarket(
			eq(PRODUCT_ID), any(PricingInputs.class), status.capture(), anyBoolean());
		assertThat(status.getValue()).isEqualTo(StockStatus.OUT_OF_STOCK);
	}

	@Test
	@DisplayName("D-283: 원가를 모르므로 가격은 보내지 않는다")
	void outOfStockWithoutCost_sendsNoPrice() {
		crawlReturns(StockStatus.OUT_OF_STOCK, null);

		runBatch();

		ArgumentCaptor<PricingInputs> inputs = ArgumentCaptor.forClass(PricingInputs.class);
		verify(productMarketSyncService).syncPriceStockPerMarket(
			eq(PRODUCT_ID), inputs.capture(), any(StockStatus.class), anyBoolean());
		assertThat(inputs.getValue().buyPrice()).isNull();
	}

	@Test
	@DisplayName("D-283: 품절은 실패가 아니다 — 성공으로 집계한다")
	void outOfStockWithoutCost_isNotAFailure() {
		crawlReturns(StockStatus.OUT_OF_STOCK, null);

		runBatch();

		verify(processStatusService).markSuccess(eq("b1"), eq(KEY), anyString(), anyString());
		verify(processStatusService, never()).markFailed(eq("b1"), eq(KEY), anyString());
	}

	@Test
	@DisplayName("D-283: 크롤이 성공했으므로 크롤 실패로 기록하지 않는다")
	void outOfStockWithoutCost_recordsCrawlSuccess() {
		crawlReturns(StockStatus.OUT_OF_STOCK, null);

		runBatch();

		verify(product).recordCrawlSuccess();
		verify(product, never()).recordCrawlFailure(anyString());
	}

	@Test
	@DisplayName("D-283: 기존 원가·판매가를 지우지 않는다 — 모르는 값은 건드리지 않는다")
	void outOfStockWithoutCost_doesNotWipeStoredPrices() {
		crawlReturns(StockStatus.OUT_OF_STOCK, null);

		runBatch();

		ArgumentCaptor<ProductUpdateCommand> cmd = ArgumentCaptor.forClass(ProductUpdateCommand.class);
		verify(product).update(cmd.capture());
		assertThat(cmd.getValue().costPrice()).isNull();
		assertThat(cmd.getValue().salePrice()).isNull();
	}

	@Test
	@DisplayName("D-283: 재고가 있는데 원가가 없으면 이상 신호이므로 여전히 실패다")
	void inStockWithoutCost_remainsFailure() {
		crawlReturns(StockStatus.IN_STOCK, null);

		runBatch();

		verify(processStatusService).markFailed(eq("b1"), eq(KEY), anyString());
		verify(productMarketSyncService, never()).syncPriceStockPerMarket(
			any(), any(), any(StockStatus.class), anyBoolean());
	}
}
