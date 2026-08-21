package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.dto.MarketPublishOutcome;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.component.ProductSanitizer;
import com.sbshop.agent.core.domain.product.component.ProductValidator;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductPublishOrphanPreventionTest {
	@Mock
	private ProductReader productReader;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private MarketRegistrationTxService registrationTxService;
	@Mock
	private ProductSanitizer productSanitizer;
	@Mock
	private ProductValidator productValidator;
	@Mock
	private MarketSalePriceResolver marketSalePriceResolver;
	@Mock
	private Product product;
	@Mock
	private MarketClient client;

	private ProductPublishUseCase useCase;

	private static final Long PRODUCT_ID = 1L;
	private static final MarketType MARKET = MarketType.COUPANG;

	@BeforeEach
	void setUp() {
		useCase = new ProductPublishUseCase(productReader, marketClientRouter,
			registrationTxService, new ObjectMapper(), productSanitizer, productValidator,
			marketSalePriceResolver);

		lenient().when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		lenient().when(product.getProductName()).thenReturn("테스트 상품");
		lenient().when(marketClientRouter.hasClient(MARKET)).thenReturn(true);
		lenient().when(marketClientRouter.getClient(MARKET)).thenReturn(client);
	}

	@Test
	@DisplayName("정상: PENDING 선-저장 → publish → identifiers+SYNCED 갱신 순서로 진행된다")
	void happyPath_savesPendingThenPublishesThenMarksSynced() {
		MarketRegistration pending = MarketRegistration.builder()
			.productId(PRODUCT_ID).marketType(MARKET).marketDetailedInfo("{}").build();
		when(registrationTxService.savePending(PRODUCT_ID, MARKET, "테스트 상품")).thenReturn(pending);
		when(client.publish(eq(product), any())).thenReturn(Map.of("vendorItemId", "V123"));

		useCase.publishToMarket(PRODUCT_ID, MARKET);

		InOrder order = inOrder(registrationTxService, client);
		order.verify(registrationTxService).savePending(PRODUCT_ID, MARKET, "테스트 상품");
		order.verify(client).publish(eq(product), any());
		order.verify(registrationTxService).markPublished(eq(pending), anyString());
	}

	@Test
	@DisplayName("publish 성공 후 갱신 저장이 실패해도 마켓 identifiers가 복구 가능하게 표면화된다(고아 아님)")
	void publishSucceedsButUpdateFails_identifiersSurfacedAndErrorPropagated() {
		MarketRegistration pending = MarketRegistration.builder()
			.productId(PRODUCT_ID).marketType(MARKET).marketDetailedInfo("{}").build();
		when(registrationTxService.savePending(PRODUCT_ID, MARKET, "테스트 상품")).thenReturn(pending);
		when(client.publish(eq(product), any())).thenReturn(Map.of("vendorItemId", "V999"));

		doThrow(new RuntimeException("DB save failed"))
			.when(registrationTxService).markPublished(any(), anyString());

		assertThatThrownBy(() -> useCase.publishToMarket(PRODUCT_ID, MARKET))
			.isInstanceOf(RuntimeException.class);

		verify(client).publish(eq(product), any());
		verify(registrationTxService).savePending(PRODUCT_ID, MARKET, "테스트 상품");
	}

	@Test
	@DisplayName("지원하지 않는 마켓이면 PENDING 저장도 외부 publish도 하지 않는다")
	void unsupportedMarket_noPendingNoPublish() {
		MarketClientRouter router = mock(MarketClientRouter.class);
		when(router.hasClient(MARKET)).thenReturn(false);
		when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		ProductPublishUseCase uc = new ProductPublishUseCase(productReader, router,
			registrationTxService, new ObjectMapper(), productSanitizer, productValidator,
			marketSalePriceResolver);

		assertThatThrownBy(() -> uc.publishToMarket(PRODUCT_ID, MARKET))
			.isInstanceOf(IllegalArgumentException.class);

		verify(registrationTxService, never()).savePending(any(), any(), anyString());
		verify(client, never()).publish(any());
	}

	@Test
	@DisplayName("게시 성공 시 마켓 identifiers를 담은 결과를 반환한다 — 프론트가 재조회 없이 배지를 링크로 바꿔야 한다")
	void publishToMarket_returnsOutcomeWithIdentifiers() {
		MarketRegistration pending = MarketRegistration.builder()
			.productId(PRODUCT_ID).marketType(MARKET).marketDetailedInfo("{}").build();
		when(registrationTxService.savePending(PRODUCT_ID, MARKET, "테스트 상품")).thenReturn(pending);
		when(client.publish(eq(product), any())).thenReturn(Map.of("vendorItemId", "V123"));

		MarketPublishOutcome outcome = useCase.publishToMarket(PRODUCT_ID, MARKET);

		assertThat(outcome.marketType()).isEqualTo(MARKET);
		assertThat(outcome.synced()).isTrue();
		assertThat(outcome.identifiers()).isNotEmpty();
	}
}
