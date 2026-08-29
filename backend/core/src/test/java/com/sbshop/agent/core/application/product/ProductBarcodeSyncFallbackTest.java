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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductBarcodeSyncFallbackTest {

	@Mock
	private ProductRepository productRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private MarketClient client;

	private ProductBarcodeSyncUseCase useCase() {
		return new ProductBarcodeSyncUseCase(productRepository, marketRegistrationRepository,
			marketClientRouter, new ObjectMapper());
	}

	@Test
	@DisplayName("바코드 전송을 지원하지 않는 마켓은 재게시로 폴백하지 않는다 — 거짓 성공을 만든다")
	void unsupportedMarket_doesNotFallBackToRepublish() {
		Product product = mock(Product.class);
		when(product.getProductSpec()).thenReturn(ProductSpec.builder().barcode("9400501001116").build());
		when(product.getSbCode()).thenReturn("201126IHB018");
		when(productRepository.findById(1L)).thenReturn(Optional.of(product));

		MarketRegistration reg = mock(MarketRegistration.class);
		when(reg.getMarketType()).thenReturn(MarketType.ELEVEN_STREET);
		when(reg.getIsSynced()).thenReturn(true);
		when(reg.extractDeleteCode()).thenReturn("3181899155");
		when(marketRegistrationRepository.findByProductId(1L)).thenReturn(List.of(reg));
		when(marketClientRouter.hasClient(MarketType.ELEVEN_STREET)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.ELEVEN_STREET)).thenReturn(client);
		doThrow(new UnsupportedOperationException("11번가 바코드 전송 미지원"))
			.when(client).syncBarcode(any(), anyString(), any());

		List<ProductBarcodeSyncUseCase.ProductOutcome> out = useCase().sync(List.of(1L), false);

		verify(client, never()).syncImagesAndHtml(any(), anyString(), any(), anyList(), anyString());
		assertThat(out.get(0).markets().get(0).result()).isEqualTo("UNSUPPORTED");
	}

	@Test
	@DisplayName("미동기 등록에는 전송하지 않는다 — 마켓에 없는 상품에 보내봐야 거부만 당한다")
	void skipsUnsyncedRegistration() {
		Product product = mock(Product.class);
		when(product.getProductSpec()).thenReturn(ProductSpec.builder().barcode("9400501001116").build());
		when(product.getSbCode()).thenReturn("200907WA006");
		when(productRepository.findById(44L)).thenReturn(Optional.of(product));

		MarketRegistration reg = mock(MarketRegistration.class);
		when(reg.getMarketType()).thenReturn(MarketType.COUPANG);
		when(reg.getIsSynced()).thenReturn(false);
		when(marketRegistrationRepository.findByProductId(44L)).thenReturn(List.of(reg));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);

		List<ProductBarcodeSyncUseCase.ProductOutcome> out = useCase().sync(List.of(44L), false);

		verify(marketClientRouter, never()).getClient(any(MarketType.class));
		assertThat(out.get(0).markets().get(0).result()).isEqualTo("SKIPPED");
	}
}
