package com.sbshop.agent.core.application.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.component.HtmlImageReplacer;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductManageUseCasePriceStockTest {
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
		when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
	}

	@Test
	@DisplayName("soldOut=null이면 재고상태를 변경하지 않고, 현재 재고상태를 마켓에 전파한다(판매재개 오전파 방지)")
	void updatePriceStock_nullSoldOut_keepsCurrentStockStatus() {
		when(product.getStockStatus()).thenReturn(StockStatus.OUT_OF_STOCK);
		when(productMarketSyncService.syncPriceStock(eq(PRODUCT_ID), any(), any()))
			.thenReturn(noMarketResult());

		useCase.updatePriceStock(PRODUCT_ID, new BigDecimal("10000"), null);

		verify(product, never()).updateStockStatus(any());

		verify(productMarketSyncService).syncPriceStock(PRODUCT_ID, 10000, StockStatus.OUT_OF_STOCK);
	}

	@Test
	@DisplayName("soldOut=true면 품절로 재고상태를 갱신하고 마켓에 전파한다")
	void updatePriceStock_soldOutTrue_setsOutOfStock() {
		when(productMarketSyncService.syncPriceStock(eq(PRODUCT_ID), any(), any()))
			.thenReturn(noMarketResult());

		useCase.updatePriceStock(PRODUCT_ID, new BigDecimal("10000"), Boolean.TRUE);

		verify(product).updateStockStatus(StockStatus.OUT_OF_STOCK);
		verify(productMarketSyncService).syncPriceStock(PRODUCT_ID, 10000, StockStatus.OUT_OF_STOCK);
	}

	@Test
	@DisplayName("soldOut=false면 판매중으로 재고상태를 갱신하고 마켓에 전파한다")
	void updatePriceStock_soldOutFalse_setsInStock() {
		when(productMarketSyncService.syncPriceStock(eq(PRODUCT_ID), any(), any()))
			.thenReturn(noMarketResult());

		useCase.updatePriceStock(PRODUCT_ID, new BigDecimal("10000"), Boolean.FALSE);

		verify(product).updateStockStatus(StockStatus.IN_STOCK);
		verify(productMarketSyncService).syncPriceStock(PRODUCT_ID, 10000, StockStatus.IN_STOCK);
	}

	private MarketRepublishResult noMarketResult() {
		return new MarketRepublishResult(List.of(), List.of(), Map.of());
	}
}
