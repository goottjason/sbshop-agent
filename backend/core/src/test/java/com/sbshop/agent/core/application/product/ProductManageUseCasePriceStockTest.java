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

/**
 * F-PROD-7: 가격/재고 수정에서 soldOut=null이 조용히 "판매중(IN_STOCK)"으로 처리되던 결함 검증.
 *
 * <p>기존 결함 — soldOut을 넘기지 않고 가격만 수정하려던 요청이 null→false로 붕괴돼 재고상태를
 * IN_STOCK으로 강제 갱신하고, 그 상태를 마켓에까지 전파했다(품절 상품이 조용히 판매재개됨).
 * 수정 후: soldOut=null이면 재고상태를 변경하지 않고(현행 유지), 마켓에도 현재 재고상태를 전파한다.
 */
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
			htmlImageReplacer, marketRegistrationRepository, marketClientRouter, productMarketSyncService);
		when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
	}

	private MarketRepublishResult noMarketResult() {
		return new MarketRepublishResult(List.of(), List.of(), Map.of());
	}

	@Test
	@DisplayName("soldOut=null이면 재고상태를 변경하지 않고, 현재 재고상태를 마켓에 전파한다(판매재개 오전파 방지)")
	void updatePriceStock_nullSoldOut_keepsCurrentStockStatus() {
		// 현재 품절 상품 — 가격만 수정하려는 요청(soldOut=null)이 판매중으로 바뀌면 안 됨.
		when(product.getStockStatus()).thenReturn(StockStatus.OUT_OF_STOCK);
		when(productMarketSyncService.syncPriceStock(eq(PRODUCT_ID), any(), any()))
			.thenReturn(noMarketResult());

		useCase.updatePriceStock(PRODUCT_ID, new BigDecimal("10000"), null);

		// 재고상태 강제 변경이 일어나선 안 된다.
		verify(product, never()).updateStockStatus(any());
		// 마켓에는 현재(품절) 상태가 전파돼야 한다(판매중으로 뒤집히면 안 됨).
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
}
