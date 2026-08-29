package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.exception.DuplicatePublishException;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.UnsyncReason;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductPublishDuplicateGuardTest {
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

	private MarketRegistration liveRegistration() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(PRODUCT_ID).marketType(MARKET)
			.marketIdentifiers("{\"sellerProductId\":\"11111\",\"vendorItemId\":\"22222\"}")
			.marketDetailedInfo("{}").build();
		reg.markSynced();
		return reg;
	}

	@Test
	@DisplayName("D-223: 이미 살아있는 등록(synced+식별자)에 재게시하면 거부한다 — 유령 리스팅 방지")
	void aliveRegistration_publishRejected() {
		when(registrationTxService.savePending(PRODUCT_ID, MARKET, "테스트 상품"))
			.thenReturn(liveRegistration());

		assertThatThrownBy(() -> useCase.publishToMarket(PRODUCT_ID, MARKET))
			.isInstanceOf(DuplicatePublishException.class)
			.hasMessageContaining("11111");

		verify(client, never()).publish(any(), any());
		verify(registrationTxService, never()).markPublished(any(), anyString());
	}

	@Test
	@DisplayName("D-223: force=true 면 살아있어도 게시한다 — 명시적 강제만 허용")
	void aliveRegistration_forceAllowsPublish() {
		when(registrationTxService.savePending(PRODUCT_ID, MARKET, "테스트 상품"))
			.thenReturn(liveRegistration());
		when(client.publish(eq(product), any())).thenReturn(Map.of("sellerProductId", "33333"));

		useCase.publishToMarket(PRODUCT_ID, MARKET, null, true);

		verify(client).publish(eq(product), any());
	}

	@Test
	@DisplayName("D-223: 마켓에서 삭제된 등록(DELETED_ON_MARKET)은 가드를 통과한다 — 이게 정상 재등록이다")
	void deletedOnMarket_publishAllowed() {
		MarketRegistration dead = liveRegistration();
		dead.markSyncFailed(UnsyncReason.DELETED_ON_MARKET);
		when(registrationTxService.savePending(PRODUCT_ID, MARKET, "테스트 상품")).thenReturn(dead);
		when(client.publish(eq(product), any())).thenReturn(Map.of("sellerProductId", "33333"));

		useCase.publishToMarket(PRODUCT_ID, MARKET);

		verify(client).publish(eq(product), any());
	}

	@Test
	@DisplayName("D-223: 식별자를 덮어쓸 때 이전 식별자를 previousIdentifiers 로 보존한다 — 유령이 남아도 추적 가능")
	void previousIdentifiersArchivedOnOverwrite() {
		MarketRegistration reg = liveRegistration();
		reg.replaceIdentifiersArchivingPrevious("{\"sellerProductId\":\"33333\"}");

		assertThat(reg.identifier("sellerProductId")).isEqualTo("33333");
		assertThat(reg.getMarketIdentifiers()).contains("previousIdentifiers").contains("11111");
	}

	@Test
	@DisplayName("D-223: 최초 등록(식별자 없음)은 가드에 걸리지 않는다")
	void freshRegistration_publishAllowed() {
		MarketRegistration pending = MarketRegistration.builder()
			.productId(PRODUCT_ID).marketType(MARKET).marketIdentifiers("{}")
			.marketDetailedInfo("{}").build();
		when(registrationTxService.savePending(PRODUCT_ID, MARKET, "테스트 상품")).thenReturn(pending);
		when(client.publish(eq(product), any())).thenReturn(Map.of("sellerProductId", "44444"));

		useCase.publishToMarket(PRODUCT_ID, MARKET);

		verify(client).publish(eq(product), any());
	}
}
