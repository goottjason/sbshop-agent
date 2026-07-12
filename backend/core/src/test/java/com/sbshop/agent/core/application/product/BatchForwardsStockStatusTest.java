package com.sbshop.agent.core.application.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.argThat;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * SP-B Task 4: 배치/크롤 경로가 크롤 결과의 StockStatus를 syncPriceStock에 전달하는지 검증.
 */
@ExtendWith(MockitoExtension.class)
class BatchForwardsStockStatusTest {

    @Mock private ProductReader productReader;
    @Mock private ProductWriter productWriter;
    @Mock private ProductRepository productRepository;
    @Mock private ProductStockCrawlerPort productStockCrawlerPort;
    @Mock private ProcessStatusService processStatusService;
    @Mock private MarginCalculator marginCalculator;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ProductMarketSyncService productMarketSyncService;
    @Mock private Product product;

    private BatchPriceStockService service;

    private static final Long PRODUCT_ID = 42L;

    @BeforeEach
    void setUp() {
        service = new BatchPriceStockService(productReader, productWriter, productRepository,
            productStockCrawlerPort, processStatusService, marginCalculator, eventPublisher,
            productMarketSyncService);

        lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        lenient().when(product.getSourcingUrl()).thenReturn("https://example.com/item/42");
        lenient().when(product.getLogisticsInfo()).thenReturn(null);
        lenient().when(product.getSbCode()).thenReturn("SB-042");
        lenient().when(marginCalculator.calculateSalePrice(any(), any(Integer.class), any(), any()))
            .thenReturn(new BigDecimal("9900"));
        lenient().when(productMarketSyncService.syncPriceStock(any(), any(), any(StockStatus.class)))
            .thenReturn(new MarketRepublishResult(List.of(), List.of(), new java.util.LinkedHashMap<>()));
    }

    @Test
    @DisplayName("크롤러가 OUT_OF_STOCK을 반환하면 syncPriceStock에 StockStatus.OUT_OF_STOCK을 전달한다")
    void crawlBatch_outOfStock_forwardsStockStatusToSync() throws InterruptedException {
        StockCheckResult crawlResult = new StockCheckResult(
            StockStatus.OUT_OF_STOCK, new BigDecimal("5000"), 0, null);
        when(productStockCrawlerPort.checkStockWithDetails("https://example.com/item/42"))
            .thenReturn(crawlResult);

        service.crawlAndUpdatePriceStock("batch-1", List.of(PRODUCT_ID),
            new BigDecimal("0.2"), BigDecimal.ZERO, BigDecimal.ZERO,
            com.sbshop.agent.core.domain.actionlog.ActionLogConstants.BATCH_CRAWL_UPDATE);

        // @Async — small sleep to let the async thread finish
        Thread.sleep(500);

        verify(productMarketSyncService).syncPriceStock(eq(PRODUCT_ID), any(), eq(StockStatus.OUT_OF_STOCK));
    }

    @Test
    @DisplayName("크롤러가 IN_STOCK을 반환하면 syncPriceStock에 StockStatus.IN_STOCK을 전달한다")
    void crawlBatch_inStock_forwardsStockStatusToSync() throws InterruptedException {
        StockCheckResult crawlResult = new StockCheckResult(
            StockStatus.IN_STOCK, new BigDecimal("5000"), 100, null);
        when(productStockCrawlerPort.checkStockWithDetails("https://example.com/item/42"))
            .thenReturn(crawlResult);

        service.crawlAndUpdatePriceStock("batch-1", List.of(PRODUCT_ID),
            new BigDecimal("0.2"), BigDecimal.ZERO, BigDecimal.ZERO,
            com.sbshop.agent.core.domain.actionlog.ActionLogConstants.BATCH_CRAWL_UPDATE);

        Thread.sleep(500);

        verify(productMarketSyncService).syncPriceStock(eq(PRODUCT_ID), any(), eq(StockStatus.IN_STOCK));
    }

    @Test
    @DisplayName("수동 배치 stock=0 입력 시 stockStatus를 OUT_OF_STOCK으로 갱신하고 DB에 재고숫자 0을 쓰지 않는다")
    void manualUpdate_soldOut_setsStatusAndDoesNotWriteZeroStock() throws InterruptedException {
        BigDecimal price = new BigDecimal("9900");
        lenient().when(product.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
        lenient().when(product.getSalePrice()).thenReturn(new BigDecimal("8800")); // different → priceChanged

        service.manualUpdatePriceStock("batch-manual", List.of(PRODUCT_ID),
            List.of(price), List.of(0));

        // @Async — small sleep to let the async thread finish
        Thread.sleep(500);

        // 1. sync is called with OUT_OF_STOCK
        verify(productMarketSyncService).syncPriceStock(eq(PRODUCT_ID), any(), eq(StockStatus.OUT_OF_STOCK));

        // 2. stockStatus is updated on the product domain object
        verify(product).updateStockStatus(StockStatus.OUT_OF_STOCK);

        // 3. ProductUpdateCommand passed to product.update(...) must have null at stock slot (index 10)
        verify(product).update(argThat(cmd -> cmd.stock() == null));
    }
}
