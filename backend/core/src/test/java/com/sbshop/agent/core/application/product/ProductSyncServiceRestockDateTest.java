package com.sbshop.agent.core.application.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * D-065: 재고 동기화 시 restockDate null 소거 방어.
 * 크롤이 restockDate를 일시적으로 null로 반환해도, 여전히 품절(OUT_OF_STOCK) 상태라면
 * DB의 기존 재입고일을 덮어쓰지 않아야 한다(재입고일 (-) 버그 방지). 재입고 완료(IN_STOCK)
 * 시에는 재입고일이 의미 없으므로 null로 지우는 것이 정상이다.
 */
@ExtendWith(MockitoExtension.class)
class ProductSyncServiceRestockDateTest {

	@Mock private ProductRepository productRepository;
	@Mock private ProductStockCrawlerPort productStockCrawlerPort;

	private ProductSyncService service;
	private static final Long PRODUCT_ID = 1L;
	private static final String SOURCE_URL = "https://www.iherb.com/pr/x/12345";

	@BeforeEach
	void setUp() {
		service = new ProductSyncService(productRepository, productStockCrawlerPort);
	}

	@Test
	@DisplayName("품절 유지 중 크롤 restockDate=null이면 기존 재입고일을 덮어쓰지 않는다")
	void keepsRestockDateWhenCrawlReturnsNullAndOutOfStock() {
		Product product = org.mockito.Mockito.mock(Product.class);
		when(product.getSourcingUrl()).thenReturn(SOURCE_URL);
		when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		when(productStockCrawlerPort.checkStockWithDetails(SOURCE_URL))
			.thenReturn(new StockCheckResult(StockStatus.OUT_OF_STOCK, BigDecimal.TEN, 0, null));

		service.syncProductStock(PRODUCT_ID);

		verify(product, never()).updateRestockDate(any());
	}

	@Test
	@DisplayName("재입고 완료(IN_STOCK)면 restockDate=null을 반영해 기존 재입고일을 지운다")
	void clearsRestockDateWhenInStock() {
		Product product = org.mockito.Mockito.mock(Product.class);
		when(product.getSourcingUrl()).thenReturn(SOURCE_URL);
		when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		when(productStockCrawlerPort.checkStockWithDetails(SOURCE_URL))
			.thenReturn(new StockCheckResult(StockStatus.IN_STOCK, BigDecimal.TEN, 100, null));

		service.syncProductStock(PRODUCT_ID);

		verify(product).updateRestockDate(null);
	}

	@Test
	@DisplayName("크롤이 새 restockDate를 주면 품절 상태에서도 갱신한다")
	void updatesRestockDateWhenCrawlProvidesValue() {
		LocalDate newDate = LocalDate.of(2026, 8, 1);
		Product product = org.mockito.Mockito.mock(Product.class);
		when(product.getSourcingUrl()).thenReturn(SOURCE_URL);
		when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		when(productStockCrawlerPort.checkStockWithDetails(SOURCE_URL))
			.thenReturn(new StockCheckResult(StockStatus.OUT_OF_STOCK, BigDecimal.TEN, 0, newDate));

		service.syncProductStock(PRODUCT_ID);

		verify(product).updateRestockDate(newDate);
	}
}
