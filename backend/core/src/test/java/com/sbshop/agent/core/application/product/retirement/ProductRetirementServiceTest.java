package com.sbshop.agent.core.application.product.retirement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductDeleteTxService;
import com.sbshop.agent.core.application.product.retirement.ProductRetirementService.RetirementMarketResult;
import com.sbshop.agent.core.application.product.retirement.ProductRetirementService.RetirementReport;
import com.sbshop.agent.core.domain.market.MarketConnectionState;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.SyncErrorType;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation;
import com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.vo.ClaimData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.retirement.ProductRetirement;
import com.sbshop.agent.core.domain.product.retirement.ProductRetirementRepository;
import com.sbshop.agent.core.domain.product.retirement.RetirementStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductRetirementServiceTest {

	private static final Long PRODUCT_ID = 10L;
	private static final String ACCOUNT = "verified-account";

	private final ProductRetirementRepository retirementRepository = mock(ProductRetirementRepository.class);
	private final ProductRepository productRepository = mock(ProductRepository.class);
	private final MarketRegistrationRepository registrationRepository = mock(MarketRegistrationRepository.class);
	private final OrderLineItemRepository lineItemRepository = mock(OrderLineItemRepository.class);
	private final MarketClientRouter router = mock(MarketClientRouter.class);
	private final ProductDeleteTxService deleteTxService = mock(ProductDeleteTxService.class);
	private final ActionLogService actionLogService = mock(ActionLogService.class);
	private final Map<MarketType, MarketClient> clients = new EnumMap<>(MarketType.class);
	private final List<MarketRegistration> registrations = new ArrayList<>();
	private final List<OrderLineItem> lineItems = new ArrayList<>();

	private Product product;
	private ProductRetirement retirement;
	private ProductRetirementService service;

	@BeforeEach
	void setUp() {
		product = product(PRODUCT_ID, "SB-T001");
		retirement = ProductRetirement.start(PRODUCT_ID);
		for (MarketType market : MarketType.values()) {
			MarketClient client = mock(MarketClient.class);
			when(client.inspectionAccountReference()).thenReturn(ACCOUNT);
			clients.put(market, client);
			when(router.hasClient(market)).thenReturn(market != MarketType.GMARKET && market != MarketType.AUCTION);
			when(router.getClient(market)).thenReturn(client);
		}
		when(retirementRepository.findDue(any(), any())).thenAnswer(inv -> List.of(retirement));
		when(retirementRepository.findByProductId(PRODUCT_ID)).thenAnswer(inv -> Optional.ofNullable(retirement));
		when(productRepository.findById(PRODUCT_ID)).thenAnswer(inv -> Optional.of(product));
		when(registrationRepository.findByProductId(PRODUCT_ID)).thenReturn(registrations);
		when(lineItemRepository.findByProductId(PRODUCT_ID)).thenReturn(lineItems);
		service = new ProductRetirementService(retirementRepository, productRepository, registrationRepository,
			lineItemRepository, router, deleteTxService, actionLogService);
	}

	@Test
	void cafe24IsNotDeletedUntilEsmAbsenceIsConfirmed() {
		MarketRegistration cafe = register(MarketType.CAFE24,
			"{\"product_no\":\"7867\",\"gmarket_goodsNo\":\"3490000001\",\"auction_goodsNo\":\"D800000001\"}");
		confirmsAbsent(MarketType.CAFE24);

		RetirementReport report = advanceOne();

		assertThat(report.markets().get(MarketType.CAFE24).result()).isEqualTo(RetirementMarketResult.ESM_PENDING);
		assertThat(report.disposed()).isFalse();
		verify(clients.get(MarketType.CAFE24), never()).deleteFromMarket(anyString());
		assertThat(cafe.connectionStateFor(MarketType.CAFE24)).isEqualTo(MarketConnectionState.LINKED);
		assertThat(retirement.getRetirementStatus()).isEqualTo(RetirementStatus.IN_PROGRESS);
		assertThat(retirement.getAttempts()).isEqualTo(1);
	}

	@Test
	void alreadyDeletedCafe24StillWaitsForEsmConfirmationBeforeDisposal() {
		MarketRegistration cafe = register(MarketType.CAFE24, "{\"product_no\":\"7867\"}");
		cafe.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);

		RetirementReport report = advanceOne();

		assertThat(report.markets().get(MarketType.CAFE24).result()).isEqualTo(RetirementMarketResult.ESM_PENDING);
		verify(deleteTxService, never()).deleteWithRegistrations(any(), anyList());
	}

	@Test
	void missingReadbackAbsenceKeepsProduct() {
		MarketRegistration store = register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		observes(MarketType.SMART_STORE, State.PRESENT, ACCOUNT);

		RetirementReport report = advanceOne();

		assertThat(report.markets().get(MarketType.SMART_STORE).result()).isEqualTo(RetirementMarketResult.FAILED);
		assertThat(report.disposed()).isFalse();
		verify(clients.get(MarketType.SMART_STORE)).deleteFromMarket("12345");
		verify(deleteTxService, never()).deleteWithRegistrations(any(), anyList());
		assertThat(store.connectionStateFor(MarketType.SMART_STORE)).isEqualTo(MarketConnectionState.LINKED);
		assertThat(store.getIsSynced()).isTrue();
		assertThat(retirement.getRetirementStatus()).isEqualTo(RetirementStatus.IN_PROGRESS);
	}

	@Test
	void absenceObservedOnDifferentAccountIsNotAccepted() {
		MarketRegistration store = register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		observes(MarketType.SMART_STORE, State.DELETED, "other-account");

		RetirementReport report = advanceOne();

		assertThat(report.markets().get(MarketType.SMART_STORE).result()).isEqualTo(RetirementMarketResult.FAILED);
		assertThat(store.connectionStateFor(MarketType.SMART_STORE)).isEqualTo(MarketConnectionState.LINKED);
		verify(deleteTxService, never()).deleteWithRegistrations(any(), anyList());
	}

	@Test
	void elevenstStoppedAfterDeleteCountsAsDoneWithoutDetaching() {
		MarketRegistration eleven = register(MarketType.ELEVEN_STREET, "{\"prdNo\":\"3200000001\"}");
		MarketClient client = clients.get(MarketType.ELEVEN_STREET);
		doThrow(new IllegalStateException("11번가 삭제 미확인")).when(client).deleteFromMarket("3200000001");
		observes(MarketType.ELEVEN_STREET, State.STOPPED, ACCOUNT);

		RetirementReport report = advanceOne();

		assertThat(report.markets().get(MarketType.ELEVEN_STREET).result())
			.isEqualTo(RetirementMarketResult.ELEVENST_STOPPED);
		assertThat(report.disposed()).isTrue();
		verify(client, never()).stopSelling(anyString());
		assertThat(eleven.connectionStateFor(MarketType.ELEVEN_STREET)).isEqualTo(MarketConnectionState.LINKED);
		verify(deleteTxService).deleteWithRegistrations(any(), anyList());
	}

	@Test
	void elevenstStillSellingIsStoppedThenReinspected() {
		register(MarketType.ELEVEN_STREET, "{\"prdNo\":\"3200000001\"}");
		MarketClient client = clients.get(MarketType.ELEVEN_STREET);
		doThrow(new IllegalStateException("11번가 삭제 미확인")).when(client).deleteFromMarket("3200000001");
		when(client.inspectListing("3200000001")).thenReturn(observation(State.PRESENT, ACCOUNT),
			observation(State.STOPPED, ACCOUNT));

		RetirementReport report = advanceOne();

		verify(client).stopSelling("3200000001");
		assertThat(report.markets().get(MarketType.ELEVEN_STREET).result())
			.isEqualTo(RetirementMarketResult.ELEVENST_STOPPED);
	}

	@Test
	void elevenstThatStaysOnSaleIsReportedFailed() {
		register(MarketType.ELEVEN_STREET, "{\"prdNo\":\"3200000001\"}");
		MarketClient client = clients.get(MarketType.ELEVEN_STREET);
		doThrow(new IllegalStateException("11번가 삭제 미확인")).when(client).deleteFromMarket("3200000001");
		observes(MarketType.ELEVEN_STREET, State.PRESENT, ACCOUNT);

		RetirementReport report = advanceOne();

		assertThat(report.markets().get(MarketType.ELEVEN_STREET).result()).isEqualTo(RetirementMarketResult.FAILED);
		assertThat(report.disposed()).isFalse();
	}

	@Test
	void coupangUnderReviewWaitsAndKeepsPresence() {
		MarketRegistration coupang = register(MarketType.COUPANG, "{\"sellerProductId\":\"111\"}");
		MarketClient client = clients.get(MarketType.COUPANG);
		doThrow(new IllegalStateException("승인대기중/심사중인 상품은 삭제할 수 없습니다.")).when(client).deleteFromMarket("111");
		observes(MarketType.COUPANG, State.PRESENT, ACCOUNT);

		RetirementReport report = advanceOne();

		assertThat(report.markets().get(MarketType.COUPANG).result())
			.isEqualTo(RetirementMarketResult.COUPANG_REVIEW_PENDING);
		assertThat(coupang.getIsSynced()).isTrue();
		assertThat(coupang.getLastSyncError()).isEqualTo(SyncErrorType.BLOCKED_BY_MARKET);
		verify(registrationRepository).save(coupang);
		assertThat(report.disposed()).isFalse();
	}

	@Test
	void coupangOtherFailureIsFailed() {
		register(MarketType.COUPANG, "{\"sellerProductId\":\"111\"}");
		doThrow(new IllegalStateException("연결 오류")).when(clients.get(MarketType.COUPANG)).deleteFromMarket("111");
		observes(MarketType.COUPANG, State.UNKNOWN, ACCOUNT);

		RetirementReport report = advanceOne();

		assertThat(report.markets().get(MarketType.COUPANG).result()).isEqualTo(RetirementMarketResult.FAILED);
		assertThat(report.markets().get(MarketType.COUPANG).message()).contains("연결 오류");
	}

	@Test
	void openOrderBlocksTheRoundWithoutTouchingMarkets() {
		register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		lineItems.add(line(ShippingStatus.PREPARING, ClaimType.NONE, ClaimStage.NONE));

		RetirementReport report = advanceOne();

		assertThat(report.stage()).isEqualTo("BLOCKED_OPEN_ORDER");
		assertThat(report.markets()).isEmpty();
		verify(clients.get(MarketType.SMART_STORE), never()).deleteFromMarket(anyString());
		verify(clients.get(MarketType.SMART_STORE), never()).inspectListing(anyString());
		assertThat(retirement.getRetirementStatus()).isEqualTo(RetirementStatus.IN_PROGRESS);
	}

	@Test
	void requestedClaimIsAnOpenOrderEvenAfterDelivery() {
		lineItems.add(line(ShippingStatus.DELIVERED, ClaimType.RETURN, ClaimStage.REQUESTED));

		assertThat(advanceOne().stage()).isEqualTo("BLOCKED_OPEN_ORDER");
	}

	@Test
	void refundedShippedLineIsNotOpen() {
		lineItems.add(line(ShippingStatus.SHIPPED, ClaimType.RETURN, ClaimStage.DONE));
		lineItems.add(line(ShippingStatus.CONFIRMED, ClaimType.NONE, ClaimStage.NONE));

		RetirementReport report = advanceOne();

		assertThat(report.stage()).isNotEqualTo("BLOCKED_OPEN_ORDER");
		assertThat(report.disposed()).isTrue();
	}

	@Test
	void startRejectsProductWithOpenOrder() {
		retirement = null;
		lineItems.add(line(ShippingStatus.NEW, ClaimType.NONE, ClaimStage.NONE));

		var results = service.start(List.of(PRODUCT_ID), false);

		assertThat(results.get(0).result()).isEqualTo("OPEN_ORDER");
		verify(retirementRepository, never()).save(any());
	}

	@Test
	void startCreatesSkipsAndResumes() {
		retirement = null;
		assertThat(service.start(List.of(PRODUCT_ID), false).get(0).result()).isEqualTo("STARTED");
		verify(retirementRepository).save(any(ProductRetirement.class));

		retirement = ProductRetirement.start(PRODUCT_ID);
		assertThat(service.start(List.of(PRODUCT_ID), false).get(0).result()).isEqualTo("ALREADY_IN_PROGRESS");

		retirement.cancel();
		assertThat(service.start(List.of(PRODUCT_ID), false).get(0).result()).isEqualTo("RESUMED");
		assertThat(retirement.getRetirementStatus()).isEqualTo(RetirementStatus.IN_PROGRESS);
	}

	@Test
	void startDryRunWritesNothing() {
		retirement = null;

		assertThat(service.start(List.of(PRODUCT_ID), true).get(0).result()).isEqualTo("WOULD_START");
		verify(retirementRepository, never()).save(any());
	}

	@Test
	void startRejectsMoreThanFiveHundred() {
		List<Long> ids = new ArrayList<>();
		for (long i = 0; i < 501; i++)
			ids.add(i);

		assertThatThrownBy(() -> service.start(ids, true)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void dryRunCallsNoMarketAndWritesNothing() {
		register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		register(MarketType.CAFE24, "{\"product_no\":\"7867\"}");

		RetirementReport report = service.advance(10, 0, true).get(0);

		assertThat(report.markets().get(MarketType.SMART_STORE).result()).isEqualTo(RetirementMarketResult.WOULD_DELETE);
		assertThat(report.markets().get(MarketType.CAFE24).result()).isEqualTo(RetirementMarketResult.ESM_PENDING);
		clients.values().forEach(client -> {
			verify(client, never()).deleteFromMarket(anyString());
			verify(client, never()).inspectListing(anyString());
		});
		verify(registrationRepository, never()).save(any());
		verify(retirementRepository, never()).save(any());
		verify(deleteTxService, never()).deleteWithRegistrations(any(), anyList());
		verifyNoInteractions(actionLogService);
		assertThat(retirement.getAttempts()).isZero();
	}

	@Test
	void dryRunNeverDisposesEvenWhenEverythingIsAlreadyDone() {
		MarketRegistration gone = register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		gone.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);

		RetirementReport report = service.advance(10, 0, true).get(0);

		assertThat(report.stage()).isEqualTo("WOULD_DISPOSE");
		assertThat(report.disposed()).isFalse();
		verify(deleteTxService, never()).deleteWithRegistrations(any(), anyList());
		verify(product, never()).recordDeletionFollowup(anyString());
		assertThat(retirement.getRetirementStatus()).isEqualTo(RetirementStatus.IN_PROGRESS);
	}

	@Test
	void everyMarketConfirmedDisposesProduct() {
		MarketRegistration store = register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		MarketRegistration coupang = register(MarketType.COUPANG, "{\"sellerProductId\":\"111\"}");
		MarketRegistration cafe = register(MarketType.CAFE24, "{\"product_no\":\"7867\"}");
		MarketRegistration gone = register(MarketType.ELEVEN_STREET, "{\"prdNo\":\"3200000001\"}");
		gone.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
		retirement.confirmEsm(LocalDateTime.now());
		confirmsAbsent(MarketType.SMART_STORE);
		confirmsAbsent(MarketType.COUPANG);
		confirmsAbsent(MarketType.CAFE24);

		RetirementReport report = advanceOne();

		assertThat(report.disposed()).isTrue();
		assertThat(report.markets().get(MarketType.ELEVEN_STREET).result())
			.isEqualTo(RetirementMarketResult.ALREADY_DONE);
		for (MarketRegistration reg : List.of(store, coupang, cafe)) {
			assertThat(report.markets().get(reg.getMarketType()).result()).isEqualTo(RetirementMarketResult.DELETED);
			assertThat(reg.connectionStateFor(reg.getMarketType())).isEqualTo(MarketConnectionState.DETACHED_DELETED);
			assertThat(reg.getUnsyncReason()).isEqualTo(UnsyncReason.DELETED_ON_MARKET);
		}
		verify(clients.get(MarketType.ELEVEN_STREET), never()).deleteFromMarket(anyString());
		var snapshot = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(product).recordDeletionFollowup(snapshot.capture());
		assertThat(snapshot.getValue()).contains("SMART_STORE", "DELETED");
		verify(deleteTxService).deleteWithRegistrations(product, registrations);
		assertThat(retirement.getRetirementStatus()).isEqualTo(RetirementStatus.DONE);
		verify(actionLogService).record(anyString(), any(), any(), anyString());
	}

	@Test
	void missingDeleteCodeOnLiveListingNeedsManualCheck() {
		MarketRegistration store = register(MarketType.SMART_STORE, "{}");

		RetirementReport report = advanceOne();

		assertThat(store.getIsSynced()).isTrue();
		assertThat(report.markets().get(MarketType.SMART_STORE).result()).isEqualTo(RetirementMarketResult.MANUAL);
		assertThat(report.disposed()).isFalse();
	}

	@Test
	void confirmEsmAbsentIgnoresCodesOutsideActiveRetirements() {
		MarketRegistration cafe = register(MarketType.CAFE24,
			"{\"product_no\":\"7867\",\"gmarket_goodsNo\":\"3490000001\",\"auction_goodsNo\":\"D800000001\"}");
		Product outsider = product(99L, "SB-T099");
		MarketRegistration outsiderCafe = MarketRegistration.builder().productId(99L).marketType(MarketType.CAFE24)
			.marketIdentifiers("{\"product_no\":\"1\",\"gmarket_goodsNo\":\"3490000099\"}").build();
		when(productRepository.findBySbCodeIn(anyList())).thenReturn(List.of(product, outsider));
		when(retirementRepository.findByProductIdIn(anyList())).thenReturn(List.of(retirement));
		when(registrationRepository.findByProductId(99L)).thenReturn(List.of(outsiderCafe));

		var result = service.confirmEsmAbsent(List.of("SB-T001", "SB-T099", "SB-NONE"));

		assertThat(result.confirmed()).containsExactly("SB-T001");
		assertThat(result.unmatched()).containsExactlyInAnyOrder("SB-T099", "SB-NONE");
		assertThat(retirement.getEsmConfirmedAt()).isNotNull();
		assertThat(cafe.connectionStateFor(MarketType.GMARKET)).isEqualTo(MarketConnectionState.DETACHED_DELETED);
		assertThat(cafe.connectionStateFor(MarketType.AUCTION)).isEqualTo(MarketConnectionState.DETACHED_DELETED);
		assertThat(outsiderCafe.connectionStateFor(MarketType.GMARKET)).isEqualTo(MarketConnectionState.LINKED);
		verify(registrationRepository, never()).save(outsiderCafe);
	}

	@Test
	void esmTargetsListCafe24ChildIdentifiersOfUnconfirmedRetirements() {
		register(MarketType.CAFE24,
			"{\"product_no\":\"7867\",\"gmarket_goodsNo\":\"3490000001\",\"auction_goodsNo\":\"D800000001\"}");
		when(retirementRepository.findByRetirementStatusAndEsmConfirmedAtIsNull(RetirementStatus.IN_PROGRESS))
			.thenReturn(List.of(retirement));
		when(registrationRepository.findByProductIdIn(anyList())).thenReturn(registrations);
		when(productRepository.findAllById(anyList())).thenReturn(List.of(product));

		var targets = service.esmTargets();

		assertThat(targets).hasSize(1);
		assertThat(targets.get(0).sbCode()).isEqualTo("SB-T001");
		assertThat(targets.get(0).gmarketGoodsNo()).isEqualTo("3490000001");
		assertThat(targets.get(0).auctionGoodsNo()).isEqualTo("D800000001");
	}

	@Test
	void cancelStopsRetirementWithoutUndoingMarkets() {
		var results = service.cancel(List.of(PRODUCT_ID));

		assertThat(results.get(0).result()).isEqualTo("CANCELED");
		assertThat(retirement.getRetirementStatus()).isEqualTo(RetirementStatus.CANCELED);
		clients.values().forEach(org.mockito.Mockito::verifyNoInteractions);
	}

	@Test
	void concurrentAdvanceIsRejected() throws Exception {
		register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		MarketClient client = clients.get(MarketType.SMART_STORE);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(inv -> {
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			return null;
		}).when(client).deleteFromMarket("12345");
		observes(MarketType.SMART_STORE, State.DELETED, ACCOUNT);
		Thread first = new Thread(() -> service.advance(10, 0, false));
		first.start();
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		assertThatThrownBy(() -> service.advance(10, 0, false)).isInstanceOf(IllegalStateException.class);

		release.countDown();
		first.join(5000);
		assertThat(service.advance(10, 0, true)).isNotNull();
	}

	@Test
	void cancelArrivingDuringTheRoundStopsDisposalAndIsNotOverwritten() {
		register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		confirmsAbsent(MarketType.SMART_STORE);
		ProductRetirement stale = retirement;
		doAnswer(inv -> {
			ProductRetirement canceled = ProductRetirement.start(PRODUCT_ID);
			canceled.cancel();
			retirement = canceled;
			return null;
		}).when(clients.get(MarketType.SMART_STORE)).deleteFromMarket("12345");

		RetirementReport report = advanceOne();

		assertThat(report.disposed()).isFalse();
		assertThat(report.stage()).isEqualTo("CANCELED_DURING_RUN");
		verify(deleteTxService, never()).deleteWithRegistrations(any(), anyList());
		verify(retirementRepository, never()).save(stale);
		verify(retirementRepository, never()).save(retirement);
		assertThat(retirement.getRetirementStatus()).isEqualTo(RetirementStatus.CANCELED);
	}

	@Test
	void retirementCanceledBeforeItsTurnIsSkipped() {
		register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		ProductRetirement stale = retirement;
		when(retirementRepository.findDue(any(), any())).thenReturn(List.of(stale));
		ProductRetirement canceled = ProductRetirement.start(PRODUCT_ID);
		canceled.cancel();
		retirement = canceled;

		RetirementReport report = advanceOne();

		assertThat(report.stage()).isEqualTo("SKIPPED_NOT_IN_PROGRESS");
		verify(clients.get(MarketType.SMART_STORE), never()).deleteFromMarket(anyString());
		verify(retirementRepository, never()).save(any());
	}

	@Test
	void esmConfirmationArrivingDuringTheRoundIsKept() {
		register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		observes(MarketType.SMART_STORE, State.PRESENT, ACCOUNT);
		doAnswer(inv -> {
			ProductRetirement confirmed = ProductRetirement.start(PRODUCT_ID);
			confirmed.confirmEsm(LocalDateTime.now());
			retirement = confirmed;
			return null;
		}).when(clients.get(MarketType.SMART_STORE)).deleteFromMarket("12345");

		advanceOne();

		var saved = org.mockito.ArgumentCaptor.forClass(ProductRetirement.class);
		verify(retirementRepository).save(saved.capture());
		assertThat(saved.getValue().getEsmConfirmedAt()).isNotNull();
		assertThat(saved.getValue().getAttempts()).isEqualTo(1);
	}

	@Test
	void retirementOfVanishedProductIsClosed() {
		when(product.isDeleted()).thenReturn(true);

		RetirementReport report = advanceOne();

		assertThat(report.stage()).isEqualTo("PRODUCT_GONE");
		assertThat(retirement.getRetirementStatus()).isEqualTo(RetirementStatus.CANCELED);
		verify(retirementRepository).save(retirement);
	}

	@Test
	void unexpectedErrorOnOneProductDoesNotStopTheRound() {
		Product other = product(20L, "SB-T020");
		ProductRetirement second = ProductRetirement.start(20L);
		when(retirementRepository.findDue(any(), any())).thenReturn(List.of(retirement, second));
		when(retirementRepository.findByProductId(20L)).thenReturn(Optional.of(second));
		when(productRepository.findById(20L)).thenReturn(Optional.of(other));
		when(registrationRepository.findByProductId(20L)).thenReturn(List.of());
		when(lineItemRepository.findByProductId(20L)).thenReturn(List.of());
		when(lineItemRepository.findByProductId(PRODUCT_ID)).thenThrow(new IllegalStateException("DB 연결 끊김"));

		List<RetirementReport> reports = service.advance(10, 0, false);

		assertThat(reports).hasSize(2);
		assertThat(reports.get(0).stage()).isEqualTo("FAILED");
		assertThat(reports.get(0).error()).contains("DB 연결 끊김");
		assertThat(reports.get(1).disposed()).isTrue();
		verify(actionLogService).record(anyString(), any(), any(), anyString());
		assertThat(retirement.getAttempts()).isEqualTo(1);
		assertThat(retirement.getLastAttemptAt()).isNotNull();
		assertThat(retirement.getLastReport()).contains("FAILED", "DB 연결 끊김");
		verify(retirementRepository).save(retirement);
	}

	@Test
	void failureWhileRecordingAnUnexpectedErrorStillContinuesTheRound() {
		Product other = product(20L, "SB-T020");
		ProductRetirement second = ProductRetirement.start(20L);
		when(retirementRepository.findDue(any(), any())).thenReturn(List.of(retirement, second));
		when(retirementRepository.findByProductId(20L)).thenReturn(Optional.of(second));
		when(productRepository.findById(20L)).thenReturn(Optional.of(other));
		when(registrationRepository.findByProductId(20L)).thenReturn(List.of());
		when(lineItemRepository.findByProductId(20L)).thenReturn(List.of());
		when(lineItemRepository.findByProductId(PRODUCT_ID)).thenThrow(new IllegalStateException("DB 연결 끊김"));
		when(retirementRepository.save(retirement)).thenThrow(new IllegalStateException("저장도 실패"));

		List<RetirementReport> reports = service.advance(10, 0, false);

		assertThat(reports).hasSize(2);
		assertThat(reports.get(0).stage()).isEqualTo("FAILED");
		assertThat(reports.get(1).disposed()).isTrue();
	}

	@Test
	void concurrentRunRejectionHasItsOwnType() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(lineItemRepository.findByProductId(PRODUCT_ID)).thenAnswer(inv -> {
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			return List.of();
		});
		Thread first = new Thread(() -> service.advance(10, 0, false));
		first.start();
		assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

		assertThatThrownBy(() -> service.advance(10, 0, false))
			.isInstanceOf(ProductRetirementService.RetirementAlreadyRunningException.class);

		release.countDown();
		first.join(5000);
	}

	@Test
	void cancelBetweenMarketsStopsBeforeTheNextMarketCall() {
		register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		register(MarketType.COUPANG, "{\"sellerProductId\":\"111\"}");
		confirmsAbsent(MarketType.SMART_STORE);
		confirmsAbsent(MarketType.COUPANG);
		doAnswer(inv -> {
			ProductRetirement canceled = ProductRetirement.start(PRODUCT_ID);
			canceled.cancel();
			retirement = canceled;
			return null;
		}).when(clients.get(MarketType.SMART_STORE)).deleteFromMarket("12345");

		RetirementReport report = advanceOne();

		assertThat(report.stage()).isEqualTo("CANCELED_DURING_RUN");
		verify(clients.get(MarketType.COUPANG), never()).deleteFromMarket(anyString());
		assertThat(report.markets()).containsOnlyKeys(MarketType.SMART_STORE);
		verify(deleteTxService, never()).deleteWithRegistrations(any(), anyList());
	}

	@Test
	void esmConfirmationArrivingBeforeCafe24TurnIsUsedInTheSameRound() {
		register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		register(MarketType.CAFE24, "{\"product_no\":\"7867\"}");
		confirmsAbsent(MarketType.SMART_STORE);
		confirmsAbsent(MarketType.CAFE24);
		doAnswer(inv -> {
			ProductRetirement confirmed = ProductRetirement.start(PRODUCT_ID);
			confirmed.confirmEsm(LocalDateTime.now());
			retirement = confirmed;
			return null;
		}).when(clients.get(MarketType.SMART_STORE)).deleteFromMarket("12345");

		RetirementReport report = advanceOne();

		verify(clients.get(MarketType.CAFE24)).deleteFromMarket("7867");
		assertThat(report.disposed()).isTrue();
		assertThat(retirement.getRetirementStatus()).isEqualTo(RetirementStatus.DONE);
	}

	@Test
	void optimisticLockConflictSkipsOnlyThatProduct() {
		Product other = product(20L, "SB-T020");
		ProductRetirement second = ProductRetirement.start(20L);
		when(retirementRepository.findDue(any(), any())).thenReturn(List.of(retirement, second));
		when(retirementRepository.findByProductId(20L)).thenReturn(Optional.of(second));
		when(productRepository.findById(20L)).thenReturn(Optional.of(other));
		when(registrationRepository.findByProductId(20L)).thenReturn(List.of());
		when(lineItemRepository.findByProductId(20L)).thenReturn(List.of());
		register(MarketType.SMART_STORE, "{\"originProductNo\":\"12345\"}");
		observes(MarketType.SMART_STORE, State.PRESENT, ACCOUNT);
		when(retirementRepository.save(retirement)).thenThrow(
			new org.springframework.orm.ObjectOptimisticLockingFailureException(ProductRetirement.class, 1L));

		List<RetirementReport> reports = service.advance(10, 0, false);

		assertThat(reports).hasSize(2);
		assertThat(reports.get(0).stage()).isEqualTo("SKIPPED_CONFLICT");
		assertThat(reports.get(0).markets()).containsKey(MarketType.SMART_STORE);
		assertThat(reports.get(1).disposed()).isTrue();
	}

	@Test
	void summaryCountsStatusesAndLastStages() {
		when(retirementRepository.countByRetirementStatus(RetirementStatus.IN_PROGRESS)).thenReturn(2L);
		when(retirementRepository.countByRetirementStatus(RetirementStatus.DONE)).thenReturn(5L);
		ProductRetirement waiting = ProductRetirement.start(11L);
		waiting.recordAttempt("{\"stage\":\"ESM_PENDING\"}", LocalDateTime.now());
		when(retirementRepository.findByRetirementStatus(RetirementStatus.IN_PROGRESS))
			.thenReturn(List.of(waiting, ProductRetirement.start(12L)));

		var summary = service.summary();

		assertThat(summary.statuses()).containsEntry("IN_PROGRESS", 2L).containsEntry("DONE", 5L)
			.containsEntry("CANCELED", 0L);
		assertThat(summary.inProgressStages()).containsEntry("ESM_PENDING", 1L).containsEntry("NOT_ATTEMPTED", 1L);
	}

	private RetirementReport advanceOne() {
		List<RetirementReport> reports = service.advance(10, 0, false);
		assertThat(reports).hasSize(1);
		return reports.get(0);
	}

	private MarketRegistration register(MarketType market, String identifiers) {
		MarketRegistration reg = MarketRegistration.builder().productId(PRODUCT_ID).marketType(market)
			.marketIdentifiers(identifiers).build();
		reg.markSynced();
		registrations.add(reg);
		return reg;
	}

	private void confirmsAbsent(MarketType market) {
		observes(market, State.DELETED, ACCOUNT);
	}

	private void observes(MarketType market, State state, String account) {
		when(clients.get(market).inspectListing(anyString())).thenReturn(observation(state, account));
	}

	private static MarketListingObservation observation(State state, String account) {
		return new MarketListingObservation(state, "CHECK", "확인", account, "/product", Instant.now());
	}

	private static OrderLineItem line(ShippingStatus shipping, ClaimType claimType, ClaimStage claimStage) {
		return OrderLineItem.builder().orderId(1L).productId(PRODUCT_ID).quantity(1)
			.shippingData(ShippingData.builder().shippingStatus(shipping).build())
			.claimData(ClaimData.builder().claimType(claimType).claimStage(claimStage).build())
			.build();
	}

	private static Product product(Long id, String sbCode) {
		Product product = mock(Product.class);
		when(product.getId()).thenReturn(id);
		when(product.getSbCode()).thenReturn(sbCode);
		when(product.isDeleted()).thenReturn(false);
		return product;
	}
}
