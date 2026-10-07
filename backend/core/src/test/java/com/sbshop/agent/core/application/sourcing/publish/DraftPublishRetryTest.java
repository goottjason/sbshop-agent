package com.sbshop.agent.core.application.sourcing.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.MarketRegistrationTxService;
import com.sbshop.agent.core.application.product.ProductCreateUseCase;
import com.sbshop.agent.core.application.product.dto.BulkProductCreateResult;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.sourcing.MarketDraft;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DraftPublishRetryTest {
	@Mock
	private ProductCreateUseCase productCreateUseCase;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private MarketRegistrationTxService registrationTxService;
	@Mock
	private DraftPublishTxService draftPublishTxService;
	@Mock
	private ProductReader productReader;
	@Mock
	private MarketClient marketClient;
	@Mock
	private MarketRegistrationRepository registrations;
	@Spy
	private ObjectMapper objectMapper = new ObjectMapper();
	@InjectMocks
	private DraftPublishUseCase useCase;

	@Test
	void publishedDraftCannotCreateDuplicateProductOrListing() {
		ProductDraft draft = draft();
		draft.markPublished(41L);
		when(draftPublishTxService.requireDraft(7L)).thenReturn(draft);

		assertThatThrownBy(() -> useCase.publish(7L))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("등록");

		verifyNoInteractions(productCreateUseCase, marketClientRouter, registrationTxService);
		verify(draftPublishTxService, never()).markPublishing(any());
	}

	@Test
	void publishingDraftCannotStartASecondPublication() {
		ProductDraft draft = draft();
		draft.markPublishing();
		when(draftPublishTxService.requireDraft(7L)).thenReturn(draft);

		assertThatThrownBy(() -> useCase.publish(7L)).isInstanceOf(IllegalStateException.class);

		verifyNoInteractions(productCreateUseCase, marketClientRouter, registrationTxService);
	}

	@Test
	void failedDraftReusesProductAndDoesNotRepublishSuccessfulMarket() {
		ProductDraft draft = draft();
		draft.markFailed(41L);
		draft.findMarketDraft(MarketType.COUPANG).orElseThrow().markPublished("{\"sellerProductId\":\"123\"}");
		draft.putMarketDraft(market(MarketType.SMART_STORE));
		when(draftPublishTxService.requireDraft(7L)).thenReturn(draft);
		Product product = product();
		when(productReader.findById(41L)).thenReturn(Optional.of(product));
		when(marketClientRouter.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.SMART_STORE)).thenReturn(marketClient);
		MarketRegistration registration = MarketRegistration.builder()
			.productId(41L).marketType(MarketType.SMART_STORE).marketIdentifiers("{}").build();
		when(registrationTxService.savePending(41L, MarketType.SMART_STORE, "상품"))
			.thenReturn(registration);
		when(marketClient.publish(eq(product), any())).thenReturn(Map.of("originProductNo", "456"));

		MarketRegistration confirmed = MarketRegistration.builder().productId(41L).marketType(MarketType.COUPANG)
			.marketIdentifiers("{\"sellerProductId\":\"123\"}").build();
		confirmed.markSynced();
		when(registrations.findByProductIdAndMarketType(41L, MarketType.COUPANG)).thenReturn(Optional.of(confirmed));

		DraftPublishUseCase.PublishResult result = useCase.publish(7L);

		assertThat(result.productId()).isEqualTo(41L);
		assertThat(result.outcomes()).hasSize(2).allMatch(DraftPublishUseCase.MarketOutcome::ok);
		verifyNoInteractions(productCreateUseCase);
		verify(marketClientRouter, never()).getClient(MarketType.COUPANG);
		verify(marketClient).publish(eq(product), any());
		verify(draftPublishTxService).finish(eq(7L), eq(41L), eq(true), any());
	}

	@Test
	void failedProductCreationLeavesDraftRetryable() {
		when(draftPublishTxService.requireDraft(7L)).thenReturn(draft());
		when(productCreateUseCase.createBulk(any())).thenReturn(new BulkProductCreateResult(
			List.of(), List.of(new BulkProductCreateResult.Failure(0, "상품", "DB unavailable"))));

		assertThatThrownBy(() -> useCase.publish(7L)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("DB unavailable");

		verify(draftPublishTxService).finish(7L, null, false, List.of());
		verifyNoInteractions(marketClientRouter, registrationTxService);
	}

	@Test
	void missingPreviouslyCreatedProductDoesNotTriggerDuplicateCreation() {
		ProductDraft draft = draft();
		draft.markFailed(41L);
		when(draftPublishTxService.requireDraft(7L)).thenReturn(draft);
		when(productReader.findById(41L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> useCase.publish(7L)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("41");

		verifyNoInteractions(productCreateUseCase, marketClientRouter);
	}

	@Test
	void uncertainPreviousMarketAttemptRequiresReconciliationBeforeRetry() {
		ProductDraft draft = draft();
		draft.markFailed(41L);
		when(draftPublishTxService.requireDraft(7L)).thenReturn(draft);
		when(productReader.findById(41L)).thenReturn(Optional.of(product()));
		when(registrations.findByProductIdAndMarketType(41L, MarketType.COUPANG))
			.thenReturn(Optional.of(MarketRegistration.builder().productId(41L)
				.marketType(MarketType.COUPANG).marketIdentifiers("{}").build()));

		DraftPublishUseCase.PublishResult result = useCase.publish(7L);

		assertThat(result.successCount()).isZero();
		assertThat(result.outcomes().getFirst().error()).contains("확인");
		verifyNoInteractions(productCreateUseCase, registrationTxService, marketClient);
		verify(marketClientRouter, never()).getClient(any());
	}

	@Test
	void reloadsTheProductLinkedByAnEarlierAttemptAfterClaimingTheDraft() {
		ProductDraft initiallyReady = draft();
		ProductDraft claimed = draft();
		claimed.markFailed(41L);
		claimed.findMarketDraft(MarketType.COUPANG).orElseThrow().markPublished("{\"sellerProductId\":\"123\"}");
		claimed.markPublishing();
		when(draftPublishTxService.requireDraft(7L)).thenReturn(initiallyReady, claimed);
		when(productReader.findById(41L)).thenReturn(Optional.of(product()));

		MarketRegistration confirmed = MarketRegistration.builder().productId(41L).marketType(MarketType.COUPANG)
			.marketIdentifiers("{\"sellerProductId\":\"123\"}").build();
		confirmed.markSynced();
		when(registrations.findByProductIdAndMarketType(41L, MarketType.COUPANG)).thenReturn(Optional.of(confirmed));

		DraftPublishUseCase.PublishResult result = useCase.publish(7L);

		assertThat(result.productId()).isEqualTo(41L);
		assertThat(result.successCount()).isEqualTo(1);
		verifyNoInteractions(productCreateUseCase, marketClientRouter, registrationTxService);
	}

	@Test
	void sendsTheReviewedMarketNameInThePublishContext() {
		ProductDraft draft = draft();
		draft.markFailed(41L);
		when(draftPublishTxService.requireDraft(7L)).thenReturn(draft);
		when(productReader.findById(41L)).thenReturn(Optional.of(product()));
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(marketClient);
		when(marketClient.publish(any(), any())).thenReturn(Map.of("sellerProductId", "123"));

		useCase.publish(7L);

		var context = org.mockito.ArgumentCaptor.forClass(
			com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext.class);
		verify(marketClient).publish(any(), context.capture());
		assertThat(objectMapper.valueToTree(context.getValue()).path("productName").asText()).isEqualTo("상품");
	}

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
	void previousSuccessfulMarketCannotHideADeletedOrDetachedConnection(boolean detached) {
		ProductDraft draft = draft();
		draft.markFailed(41L);
		draft.findMarketDraft(MarketType.COUPANG).orElseThrow().markPublished("{\"sellerProductId\":\"123\"}");
		when(draftPublishTxService.requireDraft(7L)).thenReturn(draft);
		when(productReader.findById(41L)).thenReturn(Optional.of(product()));
		MarketRegistration previous = MarketRegistration.builder().productId(41L)
			.marketType(MarketType.COUPANG).marketIdentifiers("{\"sellerProductId\":\"123\"}").build();
		previous.markSynced();
		if (detached)
			previous.detachConnection(MarketType.COUPANG,
				com.sbshop.agent.core.domain.market.MarketConnectionState.DETACHED_DELETED);
		else
			previous.markAbsentFromMarket(com.sbshop.agent.core.domain.market.UnsyncReason.DELETED_ON_MARKET);
		when(registrations.findByProductIdAndMarketType(41L, MarketType.COUPANG)).thenReturn(Optional.of(previous));

		DraftPublishUseCase.PublishResult result = useCase.publish(7L);

		assertThat(result.successCount()).isZero();
		assertThat(result.outcomes().getFirst().error()).contains("확인");
		verifyNoInteractions(marketClient, registrationTxService, productCreateUseCase);
	}

	private ProductDraft draft() {
		ProductDraft draft = ProductDraft.builder().baseNameKo("상품").brand("브랜드")
			.vendor("IHB").bundleQty(1).marginRate(new BigDecimal("20"))
			.costPrice(BigDecimal.TEN).build();
		ReflectionTestUtils.setField(draft, "id", 7L);
		draft.applyEnrichment("<p>상세</p>", "[\"https://images.example/a.jpg\"]", "");
		draft.acknowledgeCustoms(true);
		draft.putMarketDraft(market(MarketType.COUPANG));
		return draft;
	}

	private MarketDraft market(MarketType type) {
		MarketDraft market = MarketDraft.builder().marketType(type).productName("상품")
			.categoryId("1").salePrice(new BigDecimal("10000")).build();
		market.applyValidation("[]", true);
		return market;
	}

	private Product product() {
		Product product = Product.create("261007IHB001", new ProductCreateCommand(
			"https://kr.iherb.com/pr/product/1", BigDecimal.TEN, "상품", "Product", "브랜드", "USA",
			BigDecimal.ONE, BigDecimal.ONE, MeasureUnit.EA, List.of(), List.of(), "<p>상세</p>",
			"supplements", true, 1, new BigDecimal("20"), VendorType.IHB, null));
		ReflectionTestUtils.setField(product, "id", 41L);
		return product;
	}
}
