package com.sbshop.agent.core.application.market.marketplus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.marketplus.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

class MarketPlusPublicObservationServiceTest {
	ProductRepository products = mock(ProductRepository.class);
	MarketRegistrationRepository registrations = mock(MarketRegistrationRepository.class);
	MarketPlusPublicObservationRepository observations = mock(MarketPlusPublicObservationRepository.class);
	MarketPlusTransmissionService transmissions = mock(MarketPlusTransmissionService.class);
	MarketPlusPublicObservationService service = new MarketPlusPublicObservationService(products, registrations,
		observations, transmissions, new ObjectMapper());
	Product product = mock(Product.class);
	MarketRegistration reg;
	Instant captured;

	@BeforeEach
	void setup() {
		captured = Instant.now().minusSeconds(1);
		when(product.getRevision()).thenReturn(5L);
		when(products.findForEdit(1L)).thenReturn(Optional.of(product));
		when(products.findById(1L)).thenReturn(Optional.of(product));
		reg = MarketRegistration.builder().productId(1L).marketType(MarketType.CAFE24)
			.marketIdentifiers(
				"{\"product_no\":\"10186\",\"product_code\":\"P0000PBU\",\"auction_goodsNo\":\"D888859044\"}")
			.build();
		ReflectionTestUtils.setField(reg, "id", 2L);
		when(registrations.findForConnectionUpdate(2L)).thenReturn(Optional.of(reg));
		when(registrations.findByProductId(1L)).thenReturn(List.of(reg));
		when(transmissions.requireSearchScope()).thenReturn(new MarketPlusSearchScope("mall", "seller-g", "seller-a"));
		when(observations.saveAndFlush(any())).thenAnswer(inv -> {
			var row = inv.getArgument(0, MarketPlusPublicObservation.class);
			ReflectionTestUtils.setField(row, "id", 80L);
			return row;
		});
	}

	MarketPlusPublicObservationService.Request request() {
		return new MarketPlusPublicObservationService.Request(2L, 5L, "10186", "P0000PBU",
			capture("seller-a", captured));
	}

	MarketPlusPublicObservationService.Capture capture(String seller, Instant at) {
		return new MarketPlusPublicObservationService.Capture(1, "LIVE_CHROME_PUBLIC_MARKET", MarketType.AUCTION,
			"D888859044", seller,
			at, "https://itempage3.auction.co.kr/DetailView.aspx?ItemNo=D888859044",
			Map.of("salePrice", "95500", "salesQuantity", "500"), "PUBLIC_NO_OPTION_REMAINING", "UNVERIFIED");
	}

	@Test
	void storesOnlyScopedNumericObservationAndNeverChangesProductOrConnection() {
		assertThat(service.ingest(1L, request(), "admin").state()).isEqualTo("RECORDED");
		verify(observations).saveAndFlush(argThat(row -> row.getProductRevision() == 5 && row.getActor().equals("admin")
			&& row.getObservedValues().contains("95500") && row.getSellerAccount().equals("seller-a")));
		verify(products, never()).save(any());
		verify(registrations, never()).save(any());
		assertThat(reg.getConnectionState()).isEqualTo(MarketConnectionState.LINKED);
	}

	@Test
	void responseLossRetryRecoversSameObservationEvenAfterRevisionOrTimeMoves() {
		service.ingest(1L, request(), "admin");
		var captor = org.mockito.ArgumentCaptor.forClass(MarketPlusPublicObservation.class);
		verify(observations).saveAndFlush(captor.capture());
		var saved = captor.getValue();
		when(observations.findByFingerprint(saved.getFingerprint())).thenReturn(Optional.of(saved));
		when(product.getRevision()).thenReturn(6L);
		assertThat(service.ingest(1L, request(), "admin").state()).isEqualTo("DUPLICATE");
		verify(observations, times(1)).saveAndFlush(any());
	}

	@Test void staleVersionCaptureAndReplacedOrDetachedConnectionRejectWithoutStorage() {
        when(product.getRevision()).thenReturn(6L);
        assertThatThrownBy(() -> service.ingest(1L, request(), "admin")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DB 상품");
        when(product.getRevision()).thenReturn(5L); captured = Instant.now().minusSeconds(400);
        assertThatThrownBy(() -> service.ingest(1L, request(), "admin")).hasMessageContaining("5분");
        captured = Instant.now(); reg.enrichIdentifier("auction_goodsNo", "OTHER");
        assertThatThrownBy(() -> service.ingest(1L, request(), "admin")).hasMessageContaining("연결");
        reg.enrichIdentifier("auction_goodsNo", "D888859044");
        ReflectionTestUtils.setField(reg, "auctionConnectionState", MarketConnectionState.DETACHED_PROHIBITED);
        assertThatThrownBy(() -> service.ingest(1L, request(), "admin")).hasMessageContaining("연결");
        verify(observations, never()).saveAndFlush(any());
    }

	@Test
	void sellerAndAuthenticatedActorAreRequired() {
		var wrong = new MarketPlusPublicObservationService.Request(2L, 5L, "10186", "P0000PBU",
			capture("other", captured));
		assertThatThrownBy(() -> service.ingest(1L, wrong, "admin")).hasMessageContaining("판매 계정");
		assertThatThrownBy(() -> service.ingest(1L, request(), "")).hasMessageContaining("인증");
		verify(observations, never()).saveAndFlush(any());
	}

	@Test
	void historicalEvidenceRemainsVisibleButIsNotCurrentAfterRevisionOrAccountChanges() {
		service.ingest(1L, request(), "admin");
		var captor = org.mockito.ArgumentCaptor.forClass(MarketPlusPublicObservation.class);
		verify(observations).saveAndFlush(captor.capture());
		when(observations.findTop100ByProductIdOrderByCapturedAtDescIdDesc(1L)).thenReturn(List.of(captor.getValue()));
		assertThat(service.history(1L).getFirst().currentConnection()).isTrue();
		when(product.getRevision()).thenReturn(6L);
		assertThat(service.history(1L).getFirst().currentRevision()).isFalse();
		when(transmissions.requireSearchScope())
			.thenReturn(new MarketPlusSearchScope("mall", "seller-g", "replacement"));
		assertThat(service.history(1L).getFirst().currentConnection()).isFalse();
	}

	@Test
	void contextExcludesMissingIdentifiersAndDetachedChildren() {
		assertThat(service.context(1L)).singleElement().satisfies(t -> {
			assertThat(t.expectedRevision()).isEqualTo(5);
			assertThat(t.sellerAccount()).isEqualTo("seller-a");
		});
		ReflectionTestUtils.setField(reg, "auctionConnectionState", MarketConnectionState.DETACHED_DELETED);
		assertThat(service.context(1L)).isEmpty();
	}

	@Test
	void missingContextAndNullCaptureFieldsFailAsValidationErrors() {
		assertThatThrownBy(() -> new MarketPlusPublicObservationService.Request(2L, null, "10186", "P0000PBU",
			capture("seller-a", captured))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MarketPlusPublicObservationService.Capture(1, "LIVE_CHROME_PUBLIC_MARKET", null,
			"x", "seller", captured, "url", Map.of("salePrice", "1"), null, "UNVERIFIED"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new MarketPlusPublicObservationService.Capture(1, "LIVE_CHROME_PUBLIC_MARKET",
			MarketType.AUCTION, "D888859044", "seller-a", captured,
			"https://itempage3.auction.co.kr/DetailView.aspx?ItemNo=D888859044",
			Map.of("salePrice", "1", "detailHtml", "<script/>"), "NOT_VERIFIED", "UNVERIFIED"))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
