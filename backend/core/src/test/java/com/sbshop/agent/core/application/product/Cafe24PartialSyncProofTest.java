package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import java.util.*;
import org.junit.jupiter.api.Test;

class Cafe24PartialSyncProofTest {
	private final MarketRegistrationRepository registrations = mock(MarketRegistrationRepository.class);
	private final MarketClientRouter router = mock(MarketClientRouter.class);
	private final MarketClient client = mock(MarketClient.class);
	private final ProductRepository products = mock(ProductRepository.class);

	private MarketRegistration registration() {
		var reg = MarketRegistration.builder().productId(1L).marketType(MarketType.CAFE24)
			.marketIdentifiers("{\"product_no\":\"7034\"}").marketDetailedInfo("{}").build();
		when(registrations.findByProductId(1L)).thenReturn(List.of(reg));
		when(router.hasClient(MarketType.CAFE24)).thenReturn(true);
		when(router.getClient(MarketType.CAFE24)).thenReturn(client);
		return reg;
	}

	private ProductBarcodeSyncUseCase barcodeUseCase() {
		Product p = mock(Product.class);
		when(p.getSbCode()).thenReturn("200630WA013");
		when(p.getProductSpec()).thenReturn(ProductSpec.builder().barcode("00012345678905").build());
		when(products.findById(1L)).thenReturn(Optional.of(p));
		return new ProductBarcodeSyncUseCase(products, registrations, router, new ObjectMapper());
	}

	@Test
	void nativeStockProofCannotConfirmWholeProductOrClearContentFailure() {
		var reg = registration();
		reg.recordSyncError(SyncErrorType.VALIDATION_FAILED, "HTML 반영 실패");
		when(client.syncPriceAndStock(any(), any(), any(), anyInt(), anyBoolean(), any()))
			.thenReturn(Map.of("_sbshop_verified_fields", Map.of("fields", List.of("quantity"))));
		var service = new ProductMarketSyncService(registrations, router, mock(MarketSalePriceResolver.class),
			mock(ProductReader.class));
		var result = service.syncPriceStock(1L, null, StockStatus.IN_STOCK);
		assertThat(result.synced()).containsExactly(MarketType.CAFE24);
		assertThat(reg.getIsSynced()).isFalse();
		assertThat(reg.getLastSyncedAt()).isNull();
		assertThat(reg.getLastSyncErrorMessage()).isEqualTo("HTML 반영 실패");
		assertThat(reg.getMarketDetailedInfo()).contains("quantity");
	}

	@Test
	void barcodeProofPreservesOtherFieldFailureAndPreviousFullSyncTime() {
		var reg = registration();
		reg.markSynced();
		var before = reg.getLastSyncedAt();
		reg.recordSyncError(SyncErrorType.VALIDATION_FAILED, "HTML 반영 실패");
		when(client.syncBarcode(any(), eq("7034"), any())).thenReturn(true);
		var result = barcodeUseCase().sync(List.of(1L), false);
		assertThat(result.get(0).markets().get(0).result()).isEqualTo("SENT");
		assertThat(reg.identifier("barcode")).isEqualTo("00012345678905");
		assertThat(reg.getLastSyncedAt()).isEqualTo(before);
		assertThat(reg.getLastSyncErrorMessage()).isEqualTo("HTML 반영 실패");
	}

	@Test
	void detachedChildMarketBlocksLegacyBarcodeEntryPoint() {
		var reg = registration();
		reg.markSynced();
		reg.detachConnection(MarketType.GMARKET, MarketConnectionState.DETACHED_PROHIBITED);
		var result = barcodeUseCase().sync(List.of(1L), false);
		assertThat(result.get(0).markets().get(0).result()).isEqualTo("SKIPPED");
		verify(client, never()).syncBarcode(any(), any(), any());
		verify(registrations, never()).save(any());
	}

	@Test
	void pendingPublicationBlocksLegacyBarcodeEntryPoint() {
		var reg = MarketRegistration.builder().productId(1L).marketType(MarketType.CAFE24)
			.marketIdentifiers("{}").marketDetailedInfo("{}").build();
		reg.beginReviewedPublication("pending-publication");
		reg.markSynced();
		when(registrations.findByProductId(1L)).thenReturn(List.of(reg));
		var result = barcodeUseCase().sync(List.of(1L), false);
		assertThat(result.get(0).markets().get(0).detail()).contains("신규 등록 결과");
		verifyNoInteractions(router);
	}
}
