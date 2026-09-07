package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sbshop.agent.core.application.market.marketplus.MarketPlusTransmissionService;
import com.sbshop.agent.core.domain.market.marketplus.*;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.dto.ProductSearchCondition;
import com.sbshop.agent.core.domain.product.edit.ProductChangeTargetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;

class ProductMarketPlusSearchTest {
	final ProductReader reader = mock(ProductReader.class);
	final MarketPlusTransmissionService marketPlus = mock(MarketPlusTransmissionService.class);
	final ProductSearchUseCase service = new ProductSearchUseCase(reader, mock(ProductChangeTargetRepository.class),
		marketPlus);
	final PageRequest page = PageRequest.of(0, 50);
	final ProductSearchCondition issue = ProductSearchCondition.builder()
		.marketPlusIssue(MarketPlusIssueFilter.ANY_ISSUE).build();
	final MarketPlusSearchScope scope = new MarketPlusSearchScope("mall", "seller-g", "seller-a");

	@Test
	void normalSearchDoesNotRequireMarketPlusConfiguration() {
		var condition = ProductSearchCondition.none();
		when(reader.search(condition, page)).thenReturn(Page.empty(page));
		assertThat(service.searchProducts(condition, page)).isEmpty();
		verifyNoInteractions(marketPlus);
	}

	@Test
	void unreadyIssueSearchFailsBeforeReadingProducts() {
		when(marketPlus.requireSearchScope()).thenThrow(new IllegalStateException("계정 확인 필요"));
		assertThatThrownBy(() -> service.searchProducts(issue, page)).isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(reader);
	}

	@Test
	void resolvedAccountScopeIsPassedToDatabaseSearch() {
		when(marketPlus.requireSearchScope()).thenReturn(scope);
		Page<Product> result = Page.empty(page);
		when(reader.search(issue, page, scope)).thenReturn(result);
		assertThat(service.searchProducts(issue, page)).isSameAs(result);
		verify(marketPlus, times(2)).requireSearchScope();
		verify(reader, never()).search(any(), any());
	}

	@Test
	void accountChangeDuringQueryDoesNotReturnFormerAccountResult() {
		when(marketPlus.requireSearchScope()).thenReturn(scope, new MarketPlusSearchScope("replacement", "seller-g", "seller-a"));
		when(reader.search(issue, page, scope)).thenReturn(Page.empty(page));
		assertThatThrownBy(() -> service.searchProducts(issue, page)).isInstanceOf(IllegalStateException.class).hasMessageContaining("계정이 변경");
	}
}
