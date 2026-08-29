package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductSoftDeleteTest {

	private final MarketRegistrationRepository marketRegistrationRepository = mock(MarketRegistrationRepository.class);
	private final ProductWriter productWriter = mock(ProductWriter.class);

	@Test
	@DisplayName("8b: 폐기는 상품을 지우지 않고 deleted_at 을 남긴다 — 주문 추적이 끊기면 안 된다")
	void disposalMarksDeletedInsteadOfRemoving() {
		Product product = mock(Product.class);
		MarketRegistration reg = MarketRegistration.builder()
			.productId(1L).marketType(MarketType.COUPANG)
			.marketIdentifiers("{\"sellerProductId\":\"111\"}").build();
		reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);

		new ProductDeleteTxService(marketRegistrationRepository, productWriter)
			.deleteWithRegistrations(product, List.of(reg));

		verify(product).markDeleted();
		verify(productWriter).save(product);
		verify(productWriter, never()).delete(any());
	}

	@Test
	@DisplayName("8b: 등록행은 지우지 않는다 — 식별자는 과거 주문 추적의 근거다 (D-222 원칙 ②)")
	void registrationsAreKept() {
		Product product = mock(Product.class);
		MarketRegistration reg = MarketRegistration.builder()
			.productId(1L).marketType(MarketType.COUPANG).build();

		new ProductDeleteTxService(marketRegistrationRepository, productWriter)
			.deleteWithRegistrations(product, List.of(reg));

		verify(marketRegistrationRepository, never()).deleteAll(anyList());
	}

	@Test
	@DisplayName("8b: 폐기된 상품에는 게시하지 않는다 — 지운 상품이 마켓에 다시 올라가면 안 된다")
	void publishRejectsDeletedProduct() {
		com.sbshop.agent.core.domain.product.component.ProductReader productReader = mock(
			com.sbshop.agent.core.domain.product.component.ProductReader.class);
		Product deleted = mock(Product.class);
		when(deleted.isDeleted()).thenReturn(true);
		when(productReader.findById(1L)).thenReturn(java.util.Optional.of(deleted));
		MarketClientRouter router = mock(MarketClientRouter.class);

		ProductPublishUseCase useCase = new ProductPublishUseCase(productReader, router,
			mock(MarketRegistrationTxService.class), new com.fasterxml.jackson.databind.ObjectMapper(),
			mock(com.sbshop.agent.core.domain.product.component.ProductSanitizer.class),
			mock(com.sbshop.agent.core.domain.product.component.ProductValidator.class),
			mock(MarketSalePriceResolver.class));

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> useCase.publishToMarket(1L, MarketType.COUPANG))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("폐기");
	}

	@Test
	@DisplayName("8b: 폐기된 상품은 바코드 전송 대상이 아니다")
	void barcodeSyncSkipsDeletedProduct() {
		com.sbshop.agent.core.domain.product.ProductRepository productRepository = mock(
			com.sbshop.agent.core.domain.product.ProductRepository.class);
		Product deleted = mock(Product.class);
		when(deleted.isDeleted()).thenReturn(true);
		when(deleted.getSbCode()).thenReturn("SB001");
		when(productRepository.findById(1L)).thenReturn(java.util.Optional.of(deleted));

		List<ProductBarcodeSyncUseCase.ProductOutcome> out = new ProductBarcodeSyncUseCase(
			productRepository, marketRegistrationRepository, mock(MarketClientRouter.class),
			new com.fasterxml.jackson.databind.ObjectMapper()).sync(List.of(1L), false);

		assertThat(out.get(0).markets()).anySatisfy(m -> {
			assertThat(m.result()).isEqualTo("SKIPPED");
			assertThat(m.detail()).contains("폐기");
		});
	}

	@Test
	@DisplayName("8b: markDeleted 는 시각을 남기고 isDeleted 가 true 가 된다")
	void markDeletedSetsTimestamp() throws Exception {
		java.lang.reflect.Constructor<Product> ctor = Product.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		Product product = ctor.newInstance();

		assertThat(product.isDeleted()).isFalse();
		product.markDeleted();
		assertThat(product.isDeleted()).isTrue();
	}
}
