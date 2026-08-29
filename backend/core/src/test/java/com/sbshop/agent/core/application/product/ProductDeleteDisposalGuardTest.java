package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.SyncErrorType;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductDeleteDisposalGuardTest {

	private final MarketRegistrationRepository marketRegistrationRepository = mock(MarketRegistrationRepository.class);
	private final MarketClientRouter marketClientRouter = mock(MarketClientRouter.class);
	private final ProductDeleteTxService productDeleteTxService = mock(ProductDeleteTxService.class);
	private final MarketClient client = mock(MarketClient.class);

	private static final Long PRODUCT_ID = 1L;

	@Test
	@DisplayName("8a: 마켓 삭제가 하나라도 실패하면 상품을 폐기하지 않는다 — 마켓에 남는데 우리만 잊는 상태를 만들지 않는다")
	void marketDeleteFailure_blocksDisposal() {
		MarketRegistration reg = registration(MarketType.COUPANG);
		ProductManageUseCase useCase = useCase(reg);
		doThrow(new IllegalStateException("쿠팡 삭제 거부")).when(client).deleteFromMarket(anyString());

		ProductDeleteResult result = useCase.deleteProduct(PRODUCT_ID);

		assertThat(result.disposed()).isFalse();
		assertThat(result.failed()).containsKey(MarketType.COUPANG);
		verify(productDeleteTxService, never()).deleteWithRegistrations(any(), anyList());
		assertThat(reg.getIsSynced()).isTrue();
		assertThat(reg.getLastSyncError()).isEqualTo(SyncErrorType.TRANSIENT_ERROR);
	}

	@Test
	@DisplayName("8a: 삭제 API 가 없는 마켓은 수동 처리 대상이다 — 조용히 스킵하고 폐기하면 유령이 남는다")
	void unsupportedDelete_isManualAndBlocksDisposal() {
		MarketRegistration reg = registration(MarketType.COUPANG);
		ProductManageUseCase useCase = useCase(reg);
		doThrow(new UnsupportedOperationException("쿠팡 삭제 API 미구현"))
			.when(client).deleteFromMarket(anyString());

		ProductDeleteResult result = useCase.deleteProduct(PRODUCT_ID);

		assertThat(result.disposed()).isFalse();
		assertThat(result.manual()).containsKey(MarketType.COUPANG);
		assertThat(result.failed()).isEmpty();
		verify(productDeleteTxService, never()).deleteWithRegistrations(any(), anyList());
	}

	@Test
	@DisplayName("8a: 마켓 상품코드를 모르면 수동 처리다 — 무엇을 지울지 모르는데 지웠다고 세면 안 된다")
	void noMarketItemId_isManual() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(PRODUCT_ID).marketType(MarketType.GMARKET)
			.marketIdentifiers("{\"gmarket_goodsNo\":\"222\"}").build();
		reg.markSynced();
		ProductManageUseCase useCase = useCase(reg);

		ProductDeleteResult result = useCase.deleteProduct(PRODUCT_ID);

		assertThat(result.disposed()).isFalse();
		assertThat(result.manual()).containsKey(MarketType.GMARKET);
		verify(productDeleteTxService, never()).deleteWithRegistrations(any(), anyList());
	}

	@Test
	@DisplayName("8a: 전 마켓 삭제가 성공해야만 폐기한다 — 성공한 등록행은 DELETED_ON_MARKET 으로 남긴다")
	void allMarketsDeleted_disposes() {
		MarketRegistration reg = registration(MarketType.COUPANG);
		ProductManageUseCase useCase = useCase(reg);

		ProductDeleteResult result = useCase.deleteProduct(PRODUCT_ID);

		assertThat(result.disposed()).isTrue();
		assertThat(result.deleted()).containsExactly(MarketType.COUPANG);
		assertThat(reg.getUnsyncReason()).isEqualTo(UnsyncReason.DELETED_ON_MARKET);
		verify(productDeleteTxService).deleteWithRegistrations(any(), anyList());
	}

	private MarketRegistration registration(MarketType market) {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(PRODUCT_ID).marketType(market)
			.marketIdentifiers("{\"sellerProductId\":\"111\",\"gmarket_goodsNo\":\"222\"}").build();
		reg.markSynced();
		return reg;
	}

	private ProductManageUseCase useCase(MarketRegistration reg) {
		Product product = mock(Product.class);
		com.sbshop.agent.core.domain.product.component.ProductReader productReader = mock(
			com.sbshop.agent.core.domain.product.component.ProductReader.class);
		when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(List.of(reg));
		when(marketClientRouter.hasClient(reg.getMarketType())).thenReturn(true);
		when(marketClientRouter.getClient(reg.getMarketType())).thenReturn(client);
		return new ProductManageUseCase(productReader,
			mock(com.sbshop.agent.core.domain.product.component.ProductWriter.class),
			mock(com.sbshop.agent.core.domain.product.client.ImageStorageClient.class),
			mock(com.sbshop.agent.core.domain.product.component.HtmlImageReplacer.class),
			marketRegistrationRepository, marketClientRouter,
			mock(ProductMarketSyncService.class), productDeleteTxService,
			mock(com.sbshop.agent.core.application.actionlog.ActionLogService.class));
	}
}
