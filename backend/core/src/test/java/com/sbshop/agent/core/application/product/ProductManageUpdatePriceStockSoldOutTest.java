package com.sbshop.agent.core.application.product;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.component.HtmlImageReplacer;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductManageUpdatePriceStockSoldOutTest {
	@Mock
	private ProductReader productReader;
	@Mock
	private ProductWriter productWriter;
	@Mock
	private ImageStorageClient imageStorageClient;
	@Mock
	private HtmlImageReplacer htmlImageReplacer;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private ProductMarketSyncService productMarketSyncService;

	private ProductManageUseCase useCase;

	private static final Long PRODUCT_ID = 1L;

	@Mock
	private Product product;

	@BeforeEach
	void setUp() {
		useCase = new ProductManageUseCase(productReader, productWriter, imageStorageClient,
			htmlImageReplacer, marketRegistrationRepository, marketClientRouter, productMarketSyncService, null, null);

		lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
	}

	@Test
	@DisplayName("soldOut=true 이면 stockStatus를 OUT_OF_STOCK으로 갱신하고 syncPriceStock(StockStatus.OUT_OF_STOCK) 를 호출한다")
	void updatePriceStock_soldOutTrue_setsOutOfStockAndCallsSync() {
		BigDecimal price = new BigDecimal("1000");

		useCase.updatePriceStock(PRODUCT_ID, price, true);

		verify(product).updateStockStatus(StockStatus.OUT_OF_STOCK);
		verify(productMarketSyncService).syncPriceStock(PRODUCT_ID, 1000, StockStatus.OUT_OF_STOCK);
		verify(productWriter).save(product);
	}

	@Test
	@DisplayName("soldOut=false 이면 stockStatus를 IN_STOCK으로 갱신하고 syncPriceStock(StockStatus.IN_STOCK) 를 호출한다")
	void updatePriceStock_soldOutFalse_setsInStockAndCallsSync() {
		BigDecimal price = new BigDecimal("2000");

		useCase.updatePriceStock(PRODUCT_ID, price, false);

		verify(product).updateStockStatus(StockStatus.IN_STOCK);
		verify(productMarketSyncService).syncPriceStock(PRODUCT_ID, 2000, StockStatus.IN_STOCK);
		verify(productWriter).save(product);
	}
}
