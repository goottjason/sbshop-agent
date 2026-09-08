package com.sbshop.agent.core.application.market.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MarketElevenstPublicationInputsServiceTest {
	final ProductRepository products = mock(ProductRepository.class);
	final MarketRegistrationRepository registrations = mock(MarketRegistrationRepository.class);
	final MarketClientRouter clients = mock(MarketClientRouter.class);
	final MarketClient client = mock(MarketClient.class);
	final Product product = mock(Product.class);
	final MarketPublicationService reads = mock(MarketPublicationService.class);
	final MarketPublicationInputsService service = new MarketPublicationInputsService(products, registrations, clients, reads);
	final Map<String, Object> inputOnly = Map.of("stage", "INPUT_ONLY", "executable", false, "categoryId", "");

	void setup() {
		when(reads.withPreparationReadScope(eq(MarketType.ELEVEN_STREET), any())).thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(1)).get());
		when(products.findById(1L)).thenReturn(Optional.of(product));
		when(product.getRevision()).thenReturn(8L);
		when(clients.getClient(MarketType.ELEVEN_STREET)).thenReturn(client);
		when(client.inspectionAccountReference()).thenReturn("account");
	}

	@Test
	void elevenstMetadataAllowsUnverifiedCategoryAndNeverReusesRawPastRecord() {
		setup();
		when(client.describePublication(product, null)).thenReturn(inputOnly);
		var result = service.inputs(1L, MarketType.ELEVEN_STREET, null);
		assertThat(result.schema()).containsAllEntriesOf(inputOnly).containsEntry("accountReference", "account");
		assertThat(result.previousEvidence()).isEmpty();
		assertThat(MarketPublicationService.SUPPORTED).doesNotContain(MarketType.ELEVEN_STREET);
		verifyNoInteractions(registrations);
	}

	@Test
	void inputReviewChecksCurrentRevisionAndAccountWithoutPersistingOrEnqueueing() {
		setup();
		var context = MarketPublishContext.empty();
		when(client.reviewPublicationInputs(product, context)).thenReturn(inputOnly);
		assertThat(service.reviewElevenstInputs(1L, 8, "account", context)).isEqualTo(inputOnly);
		verify(products, times(2)).findById(1L);
		verify(products, never()).save(any());
		verifyNoInteractions(registrations);
		verify(client, never()).publish(any());
	}

	@Test
	void staleRevisionBeforeOrDuringReviewCannotExportAsCurrent() {
		setup();
		assertThatThrownBy(() -> service.reviewElevenstInputs(1L, 7, "account", MarketPublishContext.empty())).hasMessageContaining("상품이 변경");
		verify(client, never()).reviewPublicationInputs(any(), any());
		when(client.reviewPublicationInputs(any(), any())).thenReturn(inputOnly);
		when(product.getRevision()).thenReturn(8L, 9L);
		assertThatThrownBy(() -> service.reviewElevenstInputs(1L, 8, "account", MarketPublishContext.empty())).hasMessageContaining("점검 중 상품");
	}

	@Test
	void accountChangeAndExecutableMetadataAreRejected() {
		setup();
		when(client.reviewPublicationInputs(any(), any())).thenReturn(inputOnly);
		when(client.inspectionAccountReference()).thenReturn("account", "b");
		assertThatThrownBy(() -> service.reviewElevenstInputs(1L, 8, "account", MarketPublishContext.empty())).hasMessageContaining("계정이 변경");
		when(client.inspectionAccountReference()).thenReturn("a");
		when(client.describePublication(any(), any())).thenReturn(Map.of("stage", "INPUT_ONLY", "executable", true));
		assertThatThrownBy(() -> service.inputs(1L, MarketType.ELEVEN_STREET, null)).hasMessageContaining("입력 준비와 등록 실행");
		verifyNoInteractions(registrations);
	}
	@Test
	void accountChangedSinceMetadataReadCannotCheckReusedAddressNumbers() {
		setup();
		assertThatThrownBy(() -> service.reviewElevenstInputs(1L, 8, "previous-account", MarketPublishContext.empty())).hasMessageContaining("주소 선택 후");
		verify(client, never()).reviewPublicationInputs(any(), any());
		verifyNoInteractions(reads, registrations);
	}

}
