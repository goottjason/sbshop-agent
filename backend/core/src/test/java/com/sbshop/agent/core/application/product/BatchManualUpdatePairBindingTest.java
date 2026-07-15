package com.sbshop.agent.core.application.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.process.ProcessStatusService;
import com.sbshop.agent.core.application.product.dto.PriceStockItem;
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
 * F-BATCH-M1: 수동 가격/재고 배치가 productId·price·stock을 쌍(PriceStockItem)으로 받아
 * 각 값이 자기 상품에 묶여 적용되는지 검증. 기존 병렬배열(index 매핑)에서는 입력 순서가
 * 어긋나면 엉뚱한 상품에 값이 적용되는 데이터 오염이 발생했다.
 */
@ExtendWith(MockitoExtension.class)
class BatchManualUpdatePairBindingTest {

    @Mock private ProductReader productReader;
    @Mock private ProductWriter productWriter;
    @Mock private ProductRepository productRepository;
    @Mock private com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort productStockCrawlerPort;
    @Mock private ProcessStatusService processStatusService;
    @Mock private MarginCalculator marginCalculator;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ProductMarketSyncService productMarketSyncService;

    @Mock private Product product1;
    @Mock private Product product2;

    private BatchPriceStockService service;

    private static final Long PID_1 = 1L;
    private static final Long PID_2 = 2L;

    @BeforeEach
    void setUp() {
        service = new BatchPriceStockService(productReader, productWriter, productRepository,
            productStockCrawlerPort, processStatusService, marginCalculator, eventPublisher,
            productMarketSyncService);

        lenient().when(productReader.findById(PID_1)).thenReturn(Optional.of(product1));
        lenient().when(productReader.findById(PID_2)).thenReturn(Optional.of(product2));
        lenient().when(product1.getSbCode()).thenReturn("SB-001");
        lenient().when(product2.getSbCode()).thenReturn("SB-002");
        lenient().when(product1.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
        lenient().when(product2.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
        lenient().when(product1.getSalePrice()).thenReturn(new BigDecimal("1000"));
        lenient().when(product2.getSalePrice()).thenReturn(new BigDecimal("2000"));
        lenient().when(productMarketSyncService.syncPriceStock(any(), any(), any(StockStatus.class)))
            .thenReturn(new MarketRepublishResult(List.of(), List.of(), new java.util.LinkedHashMap<>()));
    }

    @Test
    @DisplayName("items 순서를 섞어도 각 상품에 자기 price/stock이 적용된다 (엉뚱한 상품에 안 감)")
    void shuffledItems_eachProductGetsItsOwnValue() {
        BigDecimal price1 = new BigDecimal("1500"); // for PID_1
        BigDecimal price2 = new BigDecimal("2500"); // for PID_2

        // 순서를 뒤집어 전달: [product2, product1]
        service.manualUpdatePriceStock("batch-manual", List.of(
            new PriceStockItem(PID_2, price2, 0),   // 재고 0 → OUT_OF_STOCK
            new PriceStockItem(PID_1, price1, 5)));  // 재고 5 → IN_STOCK

        // 각 상품에는 자기 값만 적용되어야 한다.
        // product1: price1, IN_STOCK
        verify(product1).update(argThat(cmd -> price1.equals(cmd.salePrice())));
        verify(product1).updateStockStatus(StockStatus.IN_STOCK);
        verify(productMarketSyncService).syncPriceStock(eq(PID_1), eq(1500), eq(StockStatus.IN_STOCK));

        // product2: price2, OUT_OF_STOCK
        verify(product2).update(argThat(cmd -> price2.equals(cmd.salePrice())));
        verify(product2).updateStockStatus(StockStatus.OUT_OF_STOCK);
        verify(productMarketSyncService).syncPriceStock(eq(PID_2), eq(2500), eq(StockStatus.OUT_OF_STOCK));
    }

    @Test
    @DisplayName("item의 price/stock이 null이면 해당 항목만 부분수정한다 (기존 동작 보존)")
    void nullFields_partialUpdatePreserved() {
        // product1: price만 지정(stock=null) → 상태 미변경
        lenient().when(product1.getSalePrice()).thenReturn(new BigDecimal("1000"));

        service.manualUpdatePriceStock("batch-manual", List.of(
            new PriceStockItem(PID_1, new BigDecimal("1234"), null)));

        // stock=null이므로 상태는 기존값 유지(IN_STOCK), price만 변경
        verify(product1).update(argThat(cmd -> new BigDecimal("1234").equals(cmd.salePrice())));
        verify(product1).updateStockStatus(StockStatus.IN_STOCK);
    }
}
