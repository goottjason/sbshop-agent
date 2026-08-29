package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
class ProductBarcodeSyncThrottleTest {

	@Mock
	private ProductRepository productRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private MarketClient client;

	@Test
	@DisplayName("D-234: 일괄 전송에 스로틀을 걸 수 있다 — 마켓 요청 한도를 넘기지 않기 위해 필요하다")
	void throttleDelaysBetweenProducts() {
		setUpProducts(3);

		long started = System.currentTimeMillis();
		useCase().sync(List.of(1L, 2L, 3L), false, 120L);
		long elapsed = System.currentTimeMillis() - started;

		assertThat(elapsed).isGreaterThanOrEqualTo(200L);
	}

	@Test
	@DisplayName("D-234: 스로틀 0 이면 지연하지 않는다 — 기존 소량 호출을 느리게 만들지 않는다")
	void zeroThrottleDoesNotDelay() {
		setUpProducts(3);

		long started = System.currentTimeMillis();
		useCase().sync(List.of(1L, 2L, 3L), false, 0L);

		assertThat(System.currentTimeMillis() - started).isLessThan(200L);
	}

	private ProductBarcodeSyncUseCase useCase() {
		return new ProductBarcodeSyncUseCase(productRepository, marketRegistrationRepository,
			marketClientRouter, new ObjectMapper());
	}

	private void setUpProducts(int count) {
		for (long id = 1; id <= count; id++) {
			Product product = mock(Product.class);
			when(product.getProductSpec()).thenReturn(ProductSpec.builder().barcode("9400501001116").build());
			when(product.getSbCode()).thenReturn("SB" + id);
			when(productRepository.findById(id)).thenReturn(Optional.of(product));
			MarketRegistration reg = MarketRegistration.builder()
				.productId(id).marketType(MarketType.COUPANG)
				.marketIdentifiers("{\"sellerProductId\":\"1\"}").marketDetailedInfo("{}").build();
			reg.markSynced();
			when(marketRegistrationRepository.findByProductId(id)).thenReturn(List.of(reg));
		}
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(client);
		when(client.syncBarcode(any(), anyString(), any())).thenReturn(false);
	}
}
