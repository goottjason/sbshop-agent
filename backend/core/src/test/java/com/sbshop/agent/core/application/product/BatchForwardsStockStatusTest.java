package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.dto.PriceStockItem;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchForwardsStockStatusTest {
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
	private Product product;

	private BatchPriceStockService service;

	private static final Long PRODUCT_ID = 42L;

	@Mock
	private VendorPricePolicyService vendorPricePolicyService;

	@BeforeEach
	void setUp() {
		service = new BatchPriceStockService(productReader, productWriter, productRepository,
			stockCrawlerRouter, processStatusService, marginCalculator, eventPublisher,
			productMarketSyncService, marketFeeService, vendorPricePolicyService);

		lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		lenient().when(product.getSourcingUrl()).thenReturn("https://example.com/item/42");
		lenient().when(product.getLogisticsInfo()).thenReturn(null);
		lenient().when(product.getSbCode()).thenReturn("SB-042");
		lenient().when(marketFeeService.feeRate(any())).thenReturn(new BigDecimal("11"));
		lenient().when(vendorPricePolicyService.find(any())).thenReturn(java.util.Optional.of(
			com.sbshop.agent.core.domain.pricing.VendorPricePolicy.builder()
				.shipBaseAmount(java.math.BigDecimal.ZERO).build()));

		lenient().when(marginCalculator.quoteSalePrice(any(), any(Integer.class), any(), any(), any(), any()))
			.thenReturn(new com.sbshop.agent.core.domain.product.service.SalePriceRounding.Result(
				new BigDecimal("9900"), null, new BigDecimal("9900")));
		lenient().when(marginCalculator.calculateSalePrice(any(), any(Integer.class), any(), any()))
			.thenReturn(new BigDecimal("9900"));
		lenient().when(productMarketSyncService.syncPriceStock(any(), any(), any(StockStatus.class), anyBoolean()))
			.thenReturn(new MarketRepublishResult(List.of(), List.of(), new LinkedHashMap<>()));
		lenient()
			.when(productMarketSyncService.syncPriceStockPerMarket(any(), any(), any(StockStatus.class), anyBoolean()))
			.thenReturn(new MarketRepublishResult(List.of(), List.of(), new LinkedHashMap<>()));
	}

	@Test
	@DisplayName("크롤러가 OUT_OF_STOCK을 반환하면 syncPriceStock에 StockStatus.OUT_OF_STOCK을 전달한다")
	void crawlBatch_outOfStock_forwardsStockStatusToSync() throws InterruptedException {
		StockCheckResult crawlResult = new StockCheckResult(
			StockStatus.OUT_OF_STOCK, new BigDecimal("5000"), 0, null);
		when(stockCrawlerRouter.checkStockWithDetails(any(), eq("https://example.com/item/42")))
			.thenReturn(crawlResult);

		service.crawlAndUpdatePriceStock("batch-1", List.of(PRODUCT_ID),
			new BigDecimal("0.2"), BigDecimal.ZERO, BigDecimal.ZERO,
			ActionLogConstants.BATCH_CRAWL_UPDATE);

		Thread.sleep(500);

		verify(productMarketSyncService).syncPriceStockPerMarket(eq(PRODUCT_ID), any(), eq(StockStatus.OUT_OF_STOCK),
			anyBoolean());
	}

	@Test
	@DisplayName("크롤러가 IN_STOCK을 반환하면 syncPriceStock에 StockStatus.IN_STOCK을 전달한다")
	void crawlBatch_inStock_forwardsStockStatusToSync() throws InterruptedException {
		StockCheckResult crawlResult = new StockCheckResult(
			StockStatus.IN_STOCK, new BigDecimal("5000"), 100, null);
		when(stockCrawlerRouter.checkStockWithDetails(any(), eq("https://example.com/item/42")))
			.thenReturn(crawlResult);

		service.crawlAndUpdatePriceStock("batch-1", List.of(PRODUCT_ID),
			new BigDecimal("0.2"), BigDecimal.ZERO, BigDecimal.ZERO,
			ActionLogConstants.BATCH_CRAWL_UPDATE);

		Thread.sleep(500);

		verify(productMarketSyncService).syncPriceStockPerMarket(eq(PRODUCT_ID), any(), eq(StockStatus.IN_STOCK),
			anyBoolean());
	}

	@Test
	@DisplayName("수동 배치 stock=0 입력 시 stockStatus를 OUT_OF_STOCK으로 갱신하고 DB에 재고숫자 0을 쓰지 않는다")
	void manualUpdate_soldOut_setsStatusAndDoesNotWriteZeroStock() throws InterruptedException {
		BigDecimal price = new BigDecimal("9900");
		lenient().when(product.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
		lenient().when(product.getSalePrice()).thenReturn(new BigDecimal("8800"));

		service.manualUpdatePriceStock("batch-manual", List.of(
			new PriceStockItem(PRODUCT_ID, price, 0)));

		Thread.sleep(500);

		verify(productMarketSyncService).syncPriceStock(eq(PRODUCT_ID), any(), eq(StockStatus.OUT_OF_STOCK),
			anyBoolean());

		verify(product).updateStockStatus(StockStatus.OUT_OF_STOCK);

		verify(product).update(argThat(cmd -> cmd.stock() == null));
	}
}
