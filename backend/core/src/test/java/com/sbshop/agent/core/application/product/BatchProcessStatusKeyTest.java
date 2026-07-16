package com.sbshop.agent.core.application.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
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
 * PART A 회귀 방지: ProcessStatus 진행현황 행의 KEY는 startBatch가 심은 productId(String.valueOf)여야 한다.
 * 이전 버그는 sbCode를 KEY로 사용해 updateStep의 productCode.equals(...) 필터가 매칭되지 않아
 * 모든 행이 PENDING에 머물렀다.
 */
@ExtendWith(MockitoExtension.class)
class BatchProcessStatusKeyTest {

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

    private static final Long PRODUCT_ID = 132L;
    private static final String PRODUCT_ID_KEY = "132";
    private static final String SB_CODE = "IHB1234";

    @BeforeEach
    void setUp() {
        service = new BatchPriceStockService(productReader, productWriter, productRepository,
            productStockCrawlerPort, processStatusService, marginCalculator, eventPublisher,
            productMarketSyncService);

        lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
        lenient().when(product.getSbCode()).thenReturn(SB_CODE);
        lenient().when(product.getLogisticsInfo()).thenReturn(null);
        lenient().when(marginCalculator.calculateSalePrice(any(), any(Integer.class), any(), any()))
            .thenReturn(new BigDecimal("9900"));
        lenient().when(productMarketSyncService.syncPriceStock(any(), any(), any(StockStatus.class)))
            .thenReturn(new MarketRepublishResult(List.of(), List.of(), new java.util.LinkedHashMap<>()));
        lenient().when(productMarketSyncService.syncPriceStock(any(), any(), any(StockStatus.class), anyBoolean()))
            .thenReturn(new MarketRepublishResult(List.of(), List.of(), new java.util.LinkedHashMap<>()));
    }

    @Test
    @DisplayName("크롤 배치 성공 시 markSuccess의 KEY는 sbCode가 아니라 productId(String.valueOf)여야 한다")
    void crawlBatch_success_marksWithProductIdKey() {
        lenient().when(product.getSourcingUrl()).thenReturn("https://example.com/item/132");
        when(productStockCrawlerPort.checkStockWithDetails("https://example.com/item/132"))
            .thenReturn(new StockCheckResult(StockStatus.IN_STOCK, new BigDecimal("5000"), 100, null));

        service.crawlAndUpdatePriceStock("batch-1", List.of(PRODUCT_ID),
            new BigDecimal("0.2"), BigDecimal.ZERO, BigDecimal.ZERO,
            com.sbshop.agent.core.domain.actionlog.ActionLogConstants.BATCH_CRAWL_UPDATE);

        verify(processStatusService).markSuccess(eq("batch-1"), eq(PRODUCT_ID_KEY), anyString());
        verify(processStatusService, never()).markSuccess(eq("batch-1"), eq(SB_CODE), anyString());
    }

    @Test
    @DisplayName("크롤 배치 실패(소싱 URL 없음) 시 markFailed의 KEY도 productId여야 한다")
    void crawlBatch_noSourceUrl_marksFailedWithProductIdKey() {
        lenient().when(product.getSourcingUrl()).thenReturn(null);

        service.crawlAndUpdatePriceStock("batch-1", List.of(PRODUCT_ID),
            new BigDecimal("0.2"), BigDecimal.ZERO, BigDecimal.ZERO,
            com.sbshop.agent.core.domain.actionlog.ActionLogConstants.BATCH_CRAWL_UPDATE);

        verify(processStatusService).markFailed(eq("batch-1"), eq(PRODUCT_ID_KEY), anyString());
        verify(processStatusService, never()).markFailed(eq("batch-1"), eq(SB_CODE), anyString());
    }

    @Test
    @DisplayName("수동 배치 성공 시 markSuccess의 KEY는 productId여야 한다")
    void manualBatch_success_marksWithProductIdKey() {
        lenient().when(product.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
        lenient().when(product.getSalePrice()).thenReturn(new BigDecimal("8800"));

        service.manualUpdatePriceStock("batch-manual", List.of(
            new com.sbshop.agent.core.application.product.dto.PriceStockItem(
                PRODUCT_ID, new BigDecimal("9900"), 50)));

        verify(processStatusService).markSuccess(eq("batch-manual"), eq(PRODUCT_ID_KEY), anyString());
        verify(processStatusService, never()).markSuccess(eq("batch-manual"), eq(SB_CODE), anyString());
    }
}
