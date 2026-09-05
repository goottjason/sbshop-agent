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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchSoldOutSkipsCafe24ResendTest {
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

	private static final Long PRODUCT_ID = 42L;
	private static final String URL = "https://example.com/item/42";

	@BeforeEach
	void setUp() {
		service = new BatchPriceStockService(productReader, productWriter, productRepository,
			stockCrawlerRouter, processStatusService, marginCalculator, eventPublisher,
			productMarketSyncService, marketFeeService, vendorPricePolicyService);

		lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		lenient().when(product.getSourcingUrl()).thenReturn(URL);
		lenient().when(product.getLogisticsInfo()).thenReturn(null);
		lenient().when(product.getSbCode()).thenReturn("SB-042");
		lenient().when(marketFeeService.feeRate(any())).thenReturn(new BigDecimal("11"));
		lenient().when(vendorPricePolicyService.find(any())).thenReturn(Optional.of(
			com.sbshop.agent.core.domain.pricing.VendorPricePolicy.builder()
				.shipBaseAmount(BigDecimal.ZERO).build()));
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
	@DisplayName("품절이 유지되는데 가격만 바뀌면 changed=false 로 넘겨 카페24 재전송을 막는다")
	void crawlBatch_staysSoldOut_priceOnly_passesChangedFalse() throws InterruptedException {
		when(product.getStockStatus()).thenReturn(StockStatus.OUT_OF_STOCK);
		when(product.getSalePrice()).thenReturn(new BigDecimal("8800"));
		when(stockCrawlerRouter.checkStockWithDetails(any(), eq(URL)))
			.thenReturn(new StockCheckResult(StockStatus.OUT_OF_STOCK, new BigDecimal("5000"), 0, null));

		service.crawlAndUpdatePriceStock("batch-1", List.of(PRODUCT_ID),
			new BigDecimal("0.2"), BigDecimal.ZERO, BigDecimal.ZERO,
			ActionLogConstants.BATCH_CRAWL_UPDATE);

		Thread.sleep(500);

		verify(productMarketSyncService).syncPriceStockPerMarket(eq(PRODUCT_ID), any(),
			eq(StockStatus.OUT_OF_STOCK), eq(false));
	}

	@Test
	@DisplayName("품절에서 재입고로 상태가 바뀌면 changed=true 로 넘겨 반드시 전송한다")
	void crawlBatch_soldOutToInStock_passesChangedTrue() throws InterruptedException {
		when(product.getStockStatus()).thenReturn(StockStatus.OUT_OF_STOCK);
		when(product.getSalePrice()).thenReturn(new BigDecimal("9900"));
		when(stockCrawlerRouter.checkStockWithDetails(any(), eq(URL)))
			.thenReturn(new StockCheckResult(StockStatus.IN_STOCK, new BigDecimal("5000"), 10, null));

		service.crawlAndUpdatePriceStock("batch-2", List.of(PRODUCT_ID),
			new BigDecimal("0.2"), BigDecimal.ZERO, BigDecimal.ZERO,
			ActionLogConstants.BATCH_CRAWL_UPDATE);

		Thread.sleep(500);

		verify(productMarketSyncService).syncPriceStockPerMarket(eq(PRODUCT_ID), any(),
			eq(StockStatus.IN_STOCK), eq(true));
	}

	@Test
	@DisplayName("판매중 상품의 가격이 바뀌면 changed=true 로 넘겨 그대로 전송한다")
	void crawlBatch_inStock_priceChanged_passesChangedTrue() throws InterruptedException {
		when(product.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
		when(product.getSalePrice()).thenReturn(new BigDecimal("8800"));
		when(stockCrawlerRouter.checkStockWithDetails(any(), eq(URL)))
			.thenReturn(new StockCheckResult(StockStatus.IN_STOCK, new BigDecimal("5000"), 10, null));

		service.crawlAndUpdatePriceStock("batch-3", List.of(PRODUCT_ID),
			new BigDecimal("0.2"), BigDecimal.ZERO, BigDecimal.ZERO,
			ActionLogConstants.BATCH_CRAWL_UPDATE);

		Thread.sleep(500);

		verify(productMarketSyncService).syncPriceStockPerMarket(eq(PRODUCT_ID), any(),
			eq(StockStatus.IN_STOCK), eq(true));
	}

	@Test
	@DisplayName("수동 배치도 품절 유지 중 가격만 바뀌면 changed=false 로 넘긴다")
	void manualUpdate_staysSoldOut_priceOnly_passesChangedFalse() throws InterruptedException {
		when(product.getStockStatus()).thenReturn(StockStatus.OUT_OF_STOCK);
		when(product.getSalePrice()).thenReturn(new BigDecimal("8800"));

		service.manualUpdatePriceStock("batch-manual", List.of(
			new PriceStockItem(PRODUCT_ID, new BigDecimal("9900"), 0)));

		Thread.sleep(500);

		verify(productMarketSyncService).syncPriceStock(eq(PRODUCT_ID), any(),
			eq(StockStatus.OUT_OF_STOCK), eq(false));
	}
}
