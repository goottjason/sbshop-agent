package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageStorageClient;
import com.sbshop.agent.core.domain.product.component.HtmlImageReplacer;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class ProductManageUseCaseDeleteTest {
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
	@Mock
	private ProductDeleteTxService productDeleteTxService;
	@Mock
	private ActionLogService actionLogService;

	private ProductManageUseCase useCase;

	private static final Long PRODUCT_ID = 1L;

	@Mock
	private Product product;

	@BeforeEach
	void setUp() {
		useCase = new ProductManageUseCase(productReader, productWriter, imageStorageClient,
			htmlImageReplacer, marketRegistrationRepository, marketClientRouter, productMarketSyncService,
			productDeleteTxService, actionLogService);
		lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
	}

	@Test
	@DisplayName("전 마켓 삭제 성공 → 등록행·Product 삭제, deleted에 전 마켓")
	void deleteProduct_allMarketsDeleted() {
		MarketClient coupangClient = Mockito.mock(MarketClient.class);
		MarketClient cafe24Client = Mockito.mock(MarketClient.class);
		List<MarketRegistration> regs = List.of(
			reg(MarketType.COUPANG, "{\"sellerProductId\":\"CP123\"}"),
			reg(MarketType.CAFE24, "{\"product_no\":\"C24\"}"));
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(regs);
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.hasClient(MarketType.CAFE24)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);
		when(marketClientRouter.getClient(MarketType.CAFE24)).thenReturn(cafe24Client);

		ProductDeleteResult result = useCase.deleteProduct(PRODUCT_ID);

		verify(coupangClient).deleteFromMarket("CP123");
		verify(cafe24Client).deleteFromMarket("C24");
		verify(productDeleteTxService).deleteWithRegistrations(product, regs);
		assertThat(result.deleted()).containsExactlyInAnyOrder(MarketType.COUPANG, MarketType.CAFE24);
		assertThat(result.skipped()).isEmpty();
		assertThat(result.failed()).isEmpty();
	}

	@Test
	@DisplayName("8a 결정: 일부 마켓 삭제 실패 → 폐기 보류. 마켓에 남는데 우리만 잊는 상태를 만들지 않는다")
	void deleteProduct_partialFailureBlocksDisposal() {
		MarketClient coupangClient = Mockito.mock(MarketClient.class);
		MarketClient cafe24Client = Mockito.mock(MarketClient.class);
		List<MarketRegistration> regs = List.of(
			reg(MarketType.COUPANG, "{\"sellerProductId\":\"CP123\"}"),
			reg(MarketType.CAFE24, "{\"product_no\":\"C24\"}"));
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(regs);
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.hasClient(MarketType.CAFE24)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);
		when(marketClientRouter.getClient(MarketType.CAFE24)).thenReturn(cafe24Client);
		doThrow(new RuntimeException("쿠팡 삭제 거부(주문이력)")).when(coupangClient).deleteFromMarket("CP123");

		ProductDeleteResult result = useCase.deleteProduct(PRODUCT_ID);

		verify(productDeleteTxService, never()).deleteWithRegistrations(product, regs);
		verify(cafe24Client).deleteFromMarket("C24");
		assertThat(result.disposed()).isFalse();
		assertThat(result.deleted()).containsExactly(MarketType.CAFE24);
		assertThat(result.failed()).containsKey(MarketType.COUPANG);
		assertThat(result.failed().get(MarketType.COUPANG)).contains("주문이력");
	}

	@Test
	@DisplayName("8a 결정: 자동 삭제할 수 없는 마켓은 수동 처리 대상이다 — 조용히 스킵하고 폐기하지 않는다")
	void deleteProduct_marketsWithoutClientAreManual() {
		List<MarketRegistration> regs = List.of(
			reg(MarketType.GMARKET, "{\"goodsNo\":\"G1\"}"),
			reg(MarketType.AUCTION, "{\"itemNo\":\"A1\"}"));
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(regs);
		when(marketClientRouter.hasClient(MarketType.GMARKET)).thenReturn(false);
		when(marketClientRouter.hasClient(MarketType.AUCTION)).thenReturn(false);

		ProductDeleteResult result = useCase.deleteProduct(PRODUCT_ID);

		verify(marketClientRouter, never()).getClient(MarketType.GMARKET);
		verify(marketClientRouter, never()).getClient(MarketType.AUCTION);
		verify(productDeleteTxService, never()).deleteWithRegistrations(product, regs);
		assertThat(result.disposed()).isFalse();
		assertThat(result.manual()).containsKeys(MarketType.GMARKET, MarketType.AUCTION);
		assertThat(result.deleted()).isEmpty();
	}

	@Test
	@DisplayName("외부 마켓 삭제 호출은 DB 삭제(트랜잭션) 이전에 일어난다(InOrder)")
	void deleteProduct_marketDeleteBeforeDbDelete() {
		MarketClient coupangClient = Mockito.mock(MarketClient.class);
		List<MarketRegistration> regs = List.of(reg(MarketType.COUPANG, "{\"sellerProductId\":\"CP123\"}"));
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(regs);
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);

		useCase.deleteProduct(PRODUCT_ID);

		InOrder order = inOrder(coupangClient, productDeleteTxService);
		order.verify(coupangClient).deleteFromMarket("CP123");
		order.verify(productDeleteTxService).deleteWithRegistrations(product, regs);
	}

	@Test
	@DisplayName("삭제 완료 시 ActionLog(PRODUCT_DELETE)에 실패 마켓+marketItemId를 기록한다")
	void deleteProduct_recordsActionLogWithFailedMarket() {
		MarketClient coupangClient = Mockito.mock(MarketClient.class);
		List<MarketRegistration> regs = List.of(reg(MarketType.COUPANG, "{\"sellerProductId\":\"CP123\"}"));
		when(marketRegistrationRepository.findByProductId(PRODUCT_ID)).thenReturn(regs);
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);
		doThrow(new RuntimeException("쿠팡 삭제 거부")).when(coupangClient).deleteFromMarket("CP123");

		useCase.deleteProduct(PRODUCT_ID);

		ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
		verify(actionLogService).record(eq(ActionLogConstants.PRODUCT_DELETE), any(),
			any(ActionStatus.class), msg.capture());

		assertThat(msg.getValue()).contains("CP123");
	}

	@Test
	@DisplayName("상품 미존재 → 404(ResourceNotFoundException) 회귀 유지")
	void deleteProduct_notFound() {
		when(productReader.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> useCase.deleteProduct(99L))
			.isInstanceOf(ResourceNotFoundException.class);

		verify(productDeleteTxService, never()).deleteWithRegistrations(any(), anyList());
	}

	private MarketRegistration reg(MarketType type, String identifiersJson) {
		return MarketRegistration.builder()
			.productId(PRODUCT_ID)
			.marketType(type)
			.marketIdentifiers(identifiersJson)
			.marketDetailedInfo("{}")
			.build();
	}
}
