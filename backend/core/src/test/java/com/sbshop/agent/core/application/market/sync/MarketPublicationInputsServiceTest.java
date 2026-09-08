package com.sbshop.agent.core.application.market.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MarketPublicationInputsServiceTest {
	final ProductRepository products = mock(ProductRepository.class);
	final MarketRegistrationRepository registrations = mock(MarketRegistrationRepository.class);
	final MarketClientRouter clients = mock(MarketClientRouter.class);
	final MarketClient client = mock(MarketClient.class);
	final Product product = mock(Product.class);
	final MarketPublicationService reads = mock(MarketPublicationService.class);
	final MarketPublicationInputsService service = new MarketPublicationInputsService(products, registrations, clients, reads);

	void setup(){when(products.findById(1L)).thenReturn(Optional.of(product));when(clients.getClient(MarketType.COUPANG)).thenReturn(client);when(client.inspectionAccountReference()).thenReturn("account");}

	@Test
	void readsMetadataAndExplicitPastCandidateWithoutMutatingOrSendingWholeMarketDocument() {
		setup();
		var schema = Map.<String, Object>of("categoryId", "73199");
		when(client.describePublication(product, null)).thenReturn(schema);
		var registration = mock(MarketRegistration.class);
		when(registration.getId()).thenReturn(5L);
		when(registration.getMarketDetailedInfo()).thenReturn("private market record");
		when(registrations.findByProductIdAndMarketType(1L, MarketType.COUPANG)).thenReturn(Optional.of(registration));
		var context = new MarketPublishContext("73137", "과거", null, List.of(), Map.of("제품명", "과거 제품명"), Map.of());
		when(client.previousPublicationContext(product, null, "private market record"))
			.thenReturn(Optional.of(context));
		var result = service.inputs(1L, MarketType.COUPANG, null);
		assertThat(result.schema()).isEqualTo(schema);
		assertThat(result.previousEvidence()).hasSize(1);
		assertThat(result.previousEvidence().getFirst().categoryId()).isEqualTo("73137");
		assertThat(result.previousEvidence().getFirst().context()).isSameAs(context);
		verify(products, never()).save(any());
		verify(registrations, never()).save(any());
		verify(client, never()).publish(any());
	}

	@Test
	void accountChangeCannotReturnMetadataOrPastEvidenceAsCurrent() {
		setup();
		when(client.inspectionAccountReference()).thenReturn("a", "b");
		when(client.describePublication(product, "73199")).thenReturn(Map.of("categoryId", "73199"));
		assertThatThrownBy(() -> service.inputs(1L, MarketType.COUPANG, "73199")).hasMessageContaining("계정");
		verifyNoInteractions(registrations);
	}

	@Test
	void invalidIdsMarketsAndCategoryPathsStopBeforeAnyLookup() {
		assertThatThrownBy(() -> service.inputs(1L, MarketType.CAFE24, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.inputs(1L, MarketType.COUPANG, "1/../../other"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.inputs(-1L, MarketType.COUPANG, null))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(products, clients, registrations);
	}
}
