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

/**
 * F-PSRC-14: 마켓 게시 후 저장 실패 시 조용한 고아 방지.
 * <p>
 * publishToMarket이 되돌릴 수 없는 외부 client.publish() 호출 후 DB save가 실패해도
 * 마켓에 올라간 상품이 DB에서 조용히 사라지지 않아야 한다. 목표 흐름 —
 * PENDING 등록행 선-저장(외부호출 전 상태 확보) → publish → identifiers+SYNCED 갱신.
 * 갱신이 실패해도 PENDING 행이 남아 복구 가능한 미완료 상태가 된다.
 */
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
	private Product product;
	@Mock
	private MarketClient client;

	private ProductPublishUseCase useCase;

	private static final Long PRODUCT_ID = 1L;
	private static final MarketType MARKET = MarketType.COUPANG;

	@BeforeEach
	void setUp() {
		useCase = new ProductPublishUseCase(productReader, marketClientRouter,
			registrationTxService, new ObjectMapper(), productSanitizer, productValidator);

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
		when(client.publish(product)).thenReturn(Map.of("vendorItemId", "V123"));

		useCase.publishToMarket(PRODUCT_ID, MARKET);

		// PENDING 선-저장이 외부 publish보다 먼저, 갱신은 publish 이후여야 한다.
		InOrder order = inOrder(registrationTxService, client);
		order.verify(registrationTxService).savePending(PRODUCT_ID, MARKET, "테스트 상품");
		order.verify(client).publish(product);
		order.verify(registrationTxService).markPublished(eq(pending), anyString());
	}

	@Test
	@DisplayName("publish 성공 후 갱신 저장이 실패해도 마켓 identifiers가 복구 가능하게 표면화된다(고아 아님)")
	void publishSucceedsButUpdateFails_identifiersSurfacedAndErrorPropagated() {
		MarketRegistration pending = MarketRegistration.builder()
			.productId(PRODUCT_ID).marketType(MARKET).marketDetailedInfo("{}").build();
		when(registrationTxService.savePending(PRODUCT_ID, MARKET, "테스트 상품")).thenReturn(pending);
		when(client.publish(product)).thenReturn(Map.of("vendorItemId", "V999"));
		// 게시 성공 후 갱신 저장에서 예외 주입
		doThrow(new RuntimeException("DB save failed"))
			.when(registrationTxService).markPublished(any(), anyString());

		// 실패는 조용히 삼켜지지 않고 표면화되어야 한다.
		assertThatThrownBy(() -> useCase.publishToMarket(PRODUCT_ID, MARKET))
			.isInstanceOf(RuntimeException.class);

		// 외부 게시는 실제로 일어났고(되돌릴 수 없음), PENDING 행은 선-저장되어 DB에 남는다.
		verify(client).publish(product);
		verify(registrationTxService).savePending(PRODUCT_ID, MARKET, "테스트 상품");
	}

	@Test
	@DisplayName("지원하지 않는 마켓이면 PENDING 저장도 외부 publish도 하지 않는다")
	void unsupportedMarket_noPendingNoPublish() {
		MarketClientRouter router = mock(MarketClientRouter.class);
		when(router.hasClient(MARKET)).thenReturn(false);
		when(productReader.findById(PRODUCT_ID)).thenReturn(Optional.of(product));
		ProductPublishUseCase uc = new ProductPublishUseCase(productReader, router,
			registrationTxService, new ObjectMapper(), productSanitizer, productValidator);

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
		when(client.publish(product)).thenReturn(Map.of("vendorItemId", "V123"));

		MarketPublishOutcome outcome = useCase.publishToMarket(PRODUCT_ID, MARKET);

		assertThat(outcome.marketType()).isEqualTo(MARKET);
		assertThat(outcome.synced()).isTrue();
		assertThat(outcome.identifiers()).isNotEmpty();
	}
}
