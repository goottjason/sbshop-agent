package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.UnsyncReason;
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
class ProductBarcodeSyncIdentifierTest {

	@Mock
	private ProductRepository productRepository;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private MarketClient client;

	private static final String BARCODE = "9400501001116";

	private ProductBarcodeSyncUseCase useCase() {
		return new ProductBarcodeSyncUseCase(productRepository, marketRegistrationRepository,
			marketClientRouter, new ObjectMapper());
	}

	private Product product() {
		Product product = mock(Product.class);
		when(product.getProductSpec()).thenReturn(ProductSpec.builder().barcode(BARCODE).build());
		when(product.getSbCode()).thenReturn("201126IHB018");
		when(productRepository.findById(1L)).thenReturn(Optional.of(product));
		return product;
	}

	private MarketRegistration registration() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(1L).marketType(MarketType.COUPANG)
			.marketIdentifiers("{\"sellerProductId\":\"11898889204\",\"barcode\":\"\"}")
			.marketDetailedInfo("{}").build();
		reg.markSynced();
		when(marketRegistrationRepository.findByProductId(1L)).thenReturn(List.of(reg));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(client);
		return reg;
	}

	@Test
	@DisplayName("D-228: 바코드 전송 성공 시 등록행 식별자에도 실제 바코드를 남긴다 — 마켓 재조회 없이 추적 가능")
	void successfulPush_recordsBarcodeInIdentifiers() {
		product();
		MarketRegistration reg = registration();

		useCase().sync(List.of(1L), false);

		assertThat(reg.identifier("barcode")).isEqualTo(BARCODE);
	}

	@Test
	@DisplayName("D-228: dryRun 은 식별자를 건드리지 않는다")
	void dryRun_leavesIdentifiersUntouched() {
		product();
		MarketRegistration reg = registration();

		useCase().sync(List.of(1L), true);

		assertThat(reg.identifier("barcode")).isNull();
	}

	@Test
	@DisplayName("D-224: 바코드 전송이 '심사중'으로 실패해도 is_synced 를 뒤집지 않는다 — 상품은 마켓에 멀쩡히 있다")
	void transientFailure_doesNotFlipIsSynced() {
		product();
		MarketRegistration reg = registration();
		doThrow(new IllegalStateException("해당 상품은 심사가 진행중입니다."))
			.when(client).syncBarcode(any(), anyString(), any());

		useCase().sync(List.of(1L), false);

		assertThat(reg.getIsSynced()).isTrue();
		assertThat(reg.getUnsyncReason()).isNull();
	}

	@Test
	@DisplayName("D-224: 검증 실패도 is_synced 를 뒤집지 않는다 — 데이터가 틀린 것이지 상품이 없는 게 아니다")
	void validationFailure_doesNotFlipIsSynced() {
		product();
		MarketRegistration reg = registration();
		doThrow(new IllegalStateException("유효하지 않은 구매 옵션 값입니다"))
			.when(client).syncBarcode(any(), anyString(), any());

		useCase().sync(List.of(1L), false);

		assertThat(reg.getIsSynced()).isTrue();
		assertThat(reg.getUnsyncReason()).isNull();
	}

	@Test
	@DisplayName("D-224: 바코드 전송이 '이미 삭제된 상품'으로 실패하면 DELETED_ON_MARKET 을 기록한다 — 이때만 뒤집는다")
	void deletedOnMarketFailure_recordsUnsyncReason() {
		product();
		MarketRegistration reg = registration();
		doThrow(new IllegalStateException("해당 상품은 이미 삭제된 상품입니다"))
			.when(client).syncBarcode(any(), anyString(), any());

		useCase().sync(List.of(1L), false);

		assertThat(reg.getIsSynced()).isFalse();
		assertThat(reg.getUnsyncReason()).isEqualTo(UnsyncReason.DELETED_ON_MARKET);
	}
}
