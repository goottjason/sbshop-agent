package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import java.util.*;
import org.junit.jupiter.api.Test;

class MarketConnectionWriteGuardTest {
	@Test
	void routerChecksFreshStateEvenIfClientWasObtainedBeforeDetachment() {
		var repo = mock(MarketRegistrationRepository.class);
		var adapter = mock(MarketClient.class);
		when(adapter.getSupportedMarket()).thenReturn(MarketType.SMART_STORE);
		var reg = MarketRegistration.builder().productId(1L).marketType(MarketType.SMART_STORE)
			.marketIdentifiers("{\"originProductNo\":\"45\"}").build();
		when(repo.findIdentifierCandidates(MarketType.SMART_STORE, "45")).thenReturn(List.of(reg));
		var client = new MarketClientRouter(List.of(adapter), new MarketConnectionWriteGuard(repo))
			.getClient(MarketType.SMART_STORE);
		reg.detachConnection(MarketType.SMART_STORE, MarketConnectionState.DETACHED_PROHIBITED);
		assertThatThrownBy(() -> client.syncPriceAndStock("45", Map.of(), 10000, 300, false))
			.hasMessageContaining("연결 해제");
		assertThatThrownBy(() -> client.requestApproval("45")).hasMessageContaining("연결 해제");
		assertThatThrownBy(() -> client.deleteFromMarket("45")).hasMessageContaining("연결 해제");
		verify(adapter, never()).syncPriceAndStock(anyString(), anyMap(), any(), anyInt(), anyBoolean());
		verify(adapter, never()).requestApproval(anyString());
		client.checkPresence("45");
		verify(adapter).checkPresence("45");
	}

	@Test
	void publishingCannotBypassDetachedStateAndOtherProductsRemainWritable() {
		var repo = mock(MarketRegistrationRepository.class);
		var product = mock(Product.class);
		when(product.getId()).thenReturn(1L);
		var reg = MarketRegistration.builder().productId(1L).marketType(MarketType.COUPANG)
			.marketIdentifiers("{\"sellerProductId\":\"45\"}").build();
		reg.detachConnection(MarketType.COUPANG, MarketConnectionState.DETACHED_DELETED);
		when(repo.findByProductIdAndMarketType(1L, MarketType.COUPANG)).thenReturn(Optional.of(reg));
		var guard = new MarketConnectionWriteGuard(repo);
		assertThatThrownBy(() -> guard.requireWritable(MarketType.COUPANG, new Object[] {product}))
			.hasMessageContaining("연결 해제");
		when(repo.findIdentifierCandidates(MarketType.COUPANG, "5")).thenReturn(List.of(reg));
		assertThatCode(() -> guard.requireWritable(MarketType.COUPANG, new Object[] {"5"})).doesNotThrowAnyException();
	}

	@Test
	void allCurrentMutatingMethodsAreGuarded() {
		var names = Arrays.stream(MarketClient.class.getMethods()).map(java.lang.reflect.Method::getName)
			.filter(n -> n.startsWith("sync") || n.startsWith("publish") || n.startsWith("delete")
				|| n.startsWith("repair") || n.startsWith("remove") || n.startsWith("requestApproval"))
			.distinct().toList();
		assertThat(MarketConnectionWriteGuard.WRITES).containsAll(names);
	}
}
