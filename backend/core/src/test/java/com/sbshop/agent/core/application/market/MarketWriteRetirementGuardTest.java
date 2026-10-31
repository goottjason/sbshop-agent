package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketConnectionState;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.retirement.ProductRetirementRepository;
import com.sbshop.agent.core.domain.product.retirement.RetirementStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MarketWriteRetirementGuardTest {

	private final MarketRegistrationRepository registrations = mock(MarketRegistrationRepository.class);
	private final ProductRetirementRepository retirements = mock(ProductRetirementRepository.class);
	private final MarketClient adapter = mock(MarketClient.class);
	private MarketClient client;

	@BeforeEach
	void setUp() {
		when(adapter.getSupportedMarket()).thenReturn(MarketType.ELEVEN_STREET);
		MarketRegistration reg = MarketRegistration.builder().productId(1L).marketType(MarketType.ELEVEN_STREET)
			.marketIdentifiers("{\"prdNo\":\"3200000001\"}").build();
		when(registrations.findIdentifierCandidates(MarketType.ELEVEN_STREET, "3200000001")).thenReturn(List.of(reg));
		when(registrations.findByProductIdAndMarketType(1L, MarketType.ELEVEN_STREET)).thenReturn(Optional.of(reg));
		when(retirements.existsByProductIdInAndRetirementStatus(anyList(), eq(RetirementStatus.IN_PROGRESS)))
			.thenReturn(true);
		client = new MarketClientRouter(List.of(adapter), new MarketConnectionWriteGuard(registrations, retirements))
			.getClient(MarketType.ELEVEN_STREET);
	}

	@Test
	void priceStockAndStatusWritesAreRefusedWhileProductIsRetiring() {
		Product product = mock(Product.class);
		when(product.getId()).thenReturn(1L);

		assertThatThrownBy(() -> client.syncPriceAndStock("3200000001", Map.of(), 10000, 300, false))
			.hasMessageContaining("퇴출");
		assertThatThrownBy(() -> client.syncPriceAndStock("3200000001", Map.of(), 10000, 300, false, product))
			.hasMessageContaining("퇴출");
		assertThatThrownBy(() -> client.writeSalePrice("3200000001", null, "SB-T001", BigDecimal.TEN, "acct",
			() -> {}, false)).hasMessageContaining("퇴출");
		assertThatThrownBy(() -> client.writeStockQuantity("3200000001", null, "SB-T001", 5, "acct", () -> {}))
			.hasMessageContaining("퇴출");
		assertThatThrownBy(() -> client.writePreparedProductFields("3200000001", null, "SB-T001", null, () -> {}))
			.hasMessageContaining("퇴출");

		verify(adapter, never()).syncPriceAndStock(anyString(), anyMap(), any(), anyInt(), anyBoolean());
		verify(adapter, never()).syncPriceAndStock(anyString(), anyMap(), any(), anyInt(), anyBoolean(), any());
		verify(adapter, never()).writeStockQuantity(anyString(), any(), anyString(), anyInt(), anyString(), any());
	}

	@Test
	void everyMarketCreateOrModifyIsRefusedWhileProductIsRetiring() {
		Product product = mock(Product.class);
		when(product.getId()).thenReturn(1L);
		when(registrations.findByProductIdAndMarketType(1L, MarketType.ELEVEN_STREET)).thenReturn(Optional.empty());

		List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> calls = List.of(
			() -> client.publish(product),
			() -> client.publish(product, null),
			() -> client.submitPreparedPublication(product, "op-1", "{}"),
			() -> client.submitPreparedPublication(product, "op-1", "{}", () -> {}),
			() -> client.finalizePreparedPublication("3200000001", "SB-T001", "{}", () -> {}),
			() -> client.requestApproval("3200000001"),
			() -> client.removeSellerImmediateDiscount("3200000001", false),
			() -> client.syncProductFields(product, "3200000001", Map.of(), java.util.Set.of()),
			() -> client.syncImagesAndHtml(product, "3200000001", Map.of(), List.of(), "<p></p>"),
			() -> client.syncBarcode(product, "3200000001", Map.of()),
			() -> client.repairListingAttributes(product, "3200000001", true),
			() -> client.repairProductNotice(product, "3200000001"));
		for (var call : calls)
			assertThatThrownBy(call).hasMessageContaining("퇴출");

		verify(adapter, never()).publish(any());
		verify(adapter, never()).requestApproval(anyString());
		verify(adapter, never()).syncImagesAndHtml(any(), anyString(), anyMap(), anyList(), anyString());
		verify(adapter, never()).repairProductNotice(any(), anyString());
	}

	@Test
	void onlyDeletionAndReadsStayOpenDuringRetirement() {
		var allowed = new java.util.HashSet<>(MarketConnectionWriteGuard.WRITES);
		allowed.removeAll(MarketConnectionWriteGuard.RETIREMENT_BLOCKED);
		org.assertj.core.api.Assertions.assertThat(allowed).containsExactly("deleteFromMarket");
		org.assertj.core.api.Assertions.assertThat(MarketConnectionWriteGuard.RETIREMENT_BLOCKED)
			.contains("submitPreparedPublication", "finalizePreparedPublication", "writeStockQuantity",
				"writePreparedProductFields")
			.doesNotContain("deleteFromMarket", "stopSelling", "inspectListing");
	}

	@Test
	void readsAreNotBlockedDuringRetirement() {
		assertThatCode(() -> client.inspectListing("3200000001")).doesNotThrowAnyException();
		assertThatCode(() -> client.extractMarketItem("3200000001")).doesNotThrowAnyException();
		verify(adapter).inspectListing("3200000001");
	}

	@Test
	void retirementItselfCanStillDeleteAndStopSelling() {
		assertThatCode(() -> client.deleteFromMarket("3200000001")).doesNotThrowAnyException();
		assertThatCode(() -> client.stopSelling("3200000001")).doesNotThrowAnyException();

		verify(adapter).deleteFromMarket("3200000001");
		verify(adapter).stopSelling("3200000001");
	}

	@Test
	void productsNotRetiringStayWritable() {
		when(retirements.existsByProductIdInAndRetirementStatus(anyList(), eq(RetirementStatus.IN_PROGRESS)))
			.thenReturn(false);

		assertThatCode(() -> client.syncPriceAndStock("3200000001", Map.of(), 10000, 300, false))
			.doesNotThrowAnyException();
		verify(adapter).syncPriceAndStock("3200000001", Map.of(), 10000, 300, false);
	}

	@Test
	void cafe24CanBeDeletedOnceItsMarketplaceChildrenAreDetached() {
		MarketClient cafeAdapter = mock(MarketClient.class);
		when(cafeAdapter.getSupportedMarket()).thenReturn(MarketType.CAFE24);
		MarketRegistration cafe = MarketRegistration.builder().productId(2L).marketType(MarketType.CAFE24)
			.marketIdentifiers("{\"product_no\":\"7867\",\"gmarket_goodsNo\":\"3490000001\"}").build();
		cafe.detachConnection(MarketType.GMARKET, MarketConnectionState.DETACHED_DELETED);
		cafe.detachConnection(MarketType.AUCTION, MarketConnectionState.DETACHED_DELETED);
		when(registrations.findIdentifierCandidates(MarketType.CAFE24, "7867")).thenReturn(List.of(cafe));
		MarketClient cafeClient = new MarketClientRouter(List.of(cafeAdapter),
			new MarketConnectionWriteGuard(registrations, retirements)).getClient(MarketType.CAFE24);

		assertThatCode(() -> cafeClient.deleteFromMarket("7867")).doesNotThrowAnyException();
		verify(cafeAdapter).deleteFromMarket("7867");
		assertThatThrownBy(() -> cafeClient.syncPriceAndStock("7867", Map.of(), 10000, 300, false))
			.isInstanceOf(IllegalStateException.class);
	}
}
