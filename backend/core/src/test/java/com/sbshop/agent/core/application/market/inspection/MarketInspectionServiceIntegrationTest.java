package com.sbshop.agent.core.application.market.inspection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.market.MarketConnectionService;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.repository.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:inspection;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MarketInspectionServiceIntegrationTest.TestApp.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MarketInspectionServiceIntegrationTest {
	@SpringBootApplication
	@EntityScan("com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class,
		MarketInspectionTaskRepository.class})
	@Import({MarketInspectionService.class, MarketConnectionService.class, DailyMarketInspectionService.class})
	static class TestApp {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper();
		}
	}

	@Autowired
	MarketInspectionService service;
	@Autowired
	MarketConnectionService connections;
	@MockitoSpyBean
	MarketInspectionTaskRepository tasks;
	@Autowired
	DailyMarketInspectionService daily;
	@Autowired
	MarketInspectionSweepRepository sweeps;
	@Autowired
	MarketInspectionBatchRepository batches;
	@Autowired
	MarketInspectionGateRepository gates;
	@Autowired
	ProductRepository products;
	@Autowired
	MarketRegistrationRepository registrations;
	@MockitoSpyBean
	MarketConnectionEventRepository events;
	@MockitoBean
	MarketClientRouter clients;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager transactions;
	MarketClient adapter;

	@BeforeEach
	void before() {
		tasks.deleteAll();
		batches.deleteAll();
		sweeps.deleteAll();
		gates.deleteAll();
		events.deleteAll();
		registrations.deleteAll();
		products.deleteAll();
		adapter = mock(MarketClient.class);
		when(clients.hasClient(MarketType.SMART_STORE)).thenReturn(true);
		when(clients.getClient(MarketType.SMART_STORE)).thenReturn(adapter);
		when(adapter.inspectionAccountReference()).thenReturn("account-A");
		ReflectionTestUtils.setField(connections, "verifiedAccountReference", "account-A");
		ReflectionTestUtils.setField(service, "singleAccountConfirmed", false);
		ReflectionTestUtils.setField(daily, "enabled", false);
		ReflectionTestUtils.setField(daily, "pageSize", 2);
	}

	Product product() {
		return products.saveAndFlush(Product.create("SB-" + UUID.randomUUID(),
			new ProductCreateCommand("https://example.com/item", new BigDecimal("10000"), "상품", "original", "브랜드", "US",
				new BigDecimal("0.3"), new BigDecimal("25"), MeasureUnit.G, List.of(), List.of(), "html", "FOOD", true,
				1, new BigDecimal("20"), VendorType.IHB, null)));
	}

	MarketRegistration link(Product p) {
		return registrations
			.saveAndFlush(MarketRegistration.builder().productId(p.getId()).marketType(MarketType.SMART_STORE)
				.marketIdentifiers("{\"originProductNo\":\"" + p.getId() + "\"}").build());
	}

	String key() {
		return UUID.randomUUID().toString();
	}

	MarketListingObservation observed(MarketListingObservation.State state, String code) {
		return new MarketListingObservation(state, code, "fixture", "account-A", "GET /origin", Instant.now());
	}

	void releaseTime() {
		jdbc.update("update sb_market_inspection_gate set next_allowed_at = ?, lease_until = ?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
		jdbc.update(
			"update sb_market_inspection_task set next_run_at = ?, lease_until = ? where state in ('RUNNING','RETRY_WAIT')",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
	}

	@Test
	void idempotentRequestPreservesTargetsAndRejectsOtherActorOrSelection() {
		var p = product();
		link(p);
		var missing = product();
		String id = key();
		var first = service.create(List.of(p.getId(), missing.getId(), 999999L, p.getId()), id, "admin");
		assertThat(first.total()).isEqualTo(3);
		assertThat(first.pending()).isEqualTo(1);
		assertThat(first.skipped()).isEqualTo(2);
		assertThat(service.create(List.of(999999L, missing.getId(), p.getId()), id, "admin").items())
			.isEqualTo(first.items());
		assertThat(tasks.count()).isEqualTo(3);
		assertThatThrownBy(() -> service.create(List.of(p.getId()), id, "admin"))
			.isInstanceOf(com.sbshop.agent.core.application.product.edit.ProductEditConflictException.class);
		assertThatThrownBy(() -> service.create(List.of(999999L, missing.getId(), p.getId()), id, "other"))
			.isInstanceOf(com.sbshop.agent.core.application.product.edit.ProductEditConflictException.class);
		assertThat(service.create(List.of(p.getId()), key(), "admin").skipped()).isEqualTo(1);
		assertThat(service.recent()).allSatisfy(batch -> assertThat(batch.items()).isEmpty());
	}

	@Test
	void onlyOneWorkerCanClaimAndExpiredWorkerCannotCommit() throws Exception {
		var p = product();
		var reg = link(p);
		service.create(List.of(p.getId()), key(), "admin");
		try (var pool = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			var a = pool.submit(() -> {
				start.await();
				return service.claim();
			});
			var b = pool.submit(() -> {
				start.await();
				return service.claim();
			});
			start.countDown();
			var first = a.get(10, TimeUnit.SECONDS);
			var second = b.get(10, TimeUnit.SECONDS);
			assertThat(Arrays.asList(first, second)).filteredOn(Objects::nonNull).hasSize(1);
			var old = first == null ? second : first;
			releaseTime();
			var resumed = service.claim();
			assertThat(resumed.token()).isNotEqualTo(old.token());
			service.finish(old, observed(MarketListingObservation.State.DELETED, "DELETE"));
			assertThat(events.count()).isZero();
			assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState())
				.isEqualTo(MarketConnectionState.LINKED);
			service.finish(resumed, observed(MarketListingObservation.State.PROHIBITED, "PROHIBITION"));
			assertThat(events.count()).isEqualTo(1);
			assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState())
				.isEqualTo(MarketConnectionState.DETACHED_PROHIBITED);
		}
	}

	@Test
	void rateLimitPausesOtherTasksAndPreservesRetryAfter() {
		var a = product();
		link(a);
		var b = product();
		link(b);
		var batch = service.create(List.of(a.getId(), b.getId()), key(), "admin");
		var claim = service.claim();
		Instant after = Instant.now().plusSeconds(900);
		service.finish(claim, new MarketListingObservation(MarketListingObservation.State.UNKNOWN,
			"HTTP_429/GW.RATE_LIMIT", "호출 제한", "account-A", "GET /origin", Instant.now(), after));
		var item = service.get(batch.id()).items().getFirst();
		assertThat(item.state()).isEqualTo("RETRY_WAIT");
		assertThat(item.nextRunAt()).isAfterOrEqualTo(after.minusMillis(1));
		assertThat(service.claim()).isNull();
		assertThat(service.get(batch.id()).confirmed()).isZero();
		assertThat(events.count()).isEqualTo(1);
	}

	@Test
	void persistenceFailureRollsBackDetachmentAndCompletionTogether() {
		var p = product();
		var reg = link(p);
		var batch = service.create(List.of(p.getId()), key(), "admin");
		var claim = service.claim();
		doThrow(new IllegalStateException("fixture storage failure")).when(events).save(any());
		assertThatThrownBy(() -> service.finish(claim, observed(MarketListingObservation.State.DELETED, "DELETE")))
			.hasMessageContaining("storage failure");
		assertThat(service.get(batch.id()).items().getFirst().state()).isEqualTo("RUNNING");
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState())
			.isEqualTo(MarketConnectionState.LINKED);
		reset(events);
	}

	@Test
	void genericFailureIsNotSuccessfulAndCanBeRetriedWithFreshBinding() {
		var p = product();
		var reg = link(p);
		var batch = service.create(List.of(p.getId()), key(), "admin");
		service.finish(service.claim(), observed(MarketListingObservation.State.UNKNOWN, "HTTP_403/NO_PERMISSION"));
		assertThat(service.get(batch.id()).needsAttention()).isEqualTo(1);
		assertThat(service.get(batch.id()).pending()).isZero();
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState())
			.isEqualTo(MarketConnectionState.LINKED);
		String retryId = key();
		var retry = service.retry(batch.id(), retryId, "admin");
		assertThat(retry.pending()).isEqualTo(1);
		assertThat(service.retry(batch.id(), retryId, "admin").id()).isEqualTo(retry.id());
	}

	@Test
	void changedRegistrationIsStaleAndAccountChangesPreventLookup() {
		var p = product();
		var reg = link(p);
		var batch = service.create(List.of(p.getId()), key(), "admin");
		var claim = service.claim();
		new TransactionTemplate(transactions).executeWithoutResult(s -> registrations.findById(reg.getId())
			.orElseThrow().updateMarketIdentifiers("{\"originProductNo\":\"999\"}"));
		service.finish(claim, observed(MarketListingObservation.State.DELETED, "DELETE"));
		assertThat(service.get(batch.id()).items().getFirst().state()).isEqualTo("STALE");
		releaseTime();
		service.retry(batch.id(), key(), "admin");
		when(adapter.inspectionAccountReference()).thenReturn("account-B");
		service.processOne();
		verify(adapter, never()).inspectListing(anyString());
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState())
			.isEqualTo(MarketConnectionState.LINKED);
	}

	@Test
	void absenceNeedsVerifiedAccountAndRetryAfterConfirmationCanDetach() {
		var p = product();
		var reg = link(p);
		ReflectionTestUtils.setField(connections, "verifiedAccountReference", "");
		var batch = service.create(List.of(p.getId()), key(), "admin");
		service.finish(service.claim(), observed(MarketListingObservation.State.DELETED, "HTTP_404/NOT_FOUND"));
		assertThat(service.get(batch.id()).items().getFirst().state()).isEqualTo("ACCOUNT_REVIEW_REQUIRED");
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState())
			.isEqualTo(MarketConnectionState.LINKED);
		ReflectionTestUtils.setField(connections, "verifiedAccountReference", "account-A");
		releaseTime();
		var retry = service.retry(batch.id(), key(), "admin");
		service.finish(service.claim(), observed(MarketListingObservation.State.DELETED, "HTTP_404/NOT_FOUND"));
		assertThat(service.get(retry.id()).detached()).isEqualTo(1);
	}

	@Test
	void retriesAreBoundedAndStockoutNeverDetaches() {
		var p = product();
		var reg = link(p);
		var batch = service.create(List.of(p.getId()), key(), "admin");
		for (int i = 0; i < 5; i++) {
			service.finish(service.claim(), observed(MarketListingObservation.State.UNKNOWN, "HTTP_503/UNAVAILABLE"));
			releaseTime();
		}
		assertThat(service.get(batch.id()).items().getFirst().attempts()).isEqualTo(5);
		assertThat(service.get(batch.id()).items().getFirst().state()).isEqualTo("UNKNOWN");
		assertThat(service.claim()).isNull();
		var retry = service.retry(batch.id(), key(), "admin");
		service.finish(service.claim(), observed(MarketListingObservation.State.OUT_OF_STOCK, "OUTOFSTOCK"));
		assertThat(service.get(retry.id()).confirmed()).isEqualTo(1);
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState())
			.isEqualTo(MarketConnectionState.LINKED);
	}

	@Test
	void repeatedWorkerInterruptionStopsAfterFiveClaims() {
		var p = product();
		link(p);
		var batch = service.create(List.of(p.getId()), key(), "admin");
		for (int i = 0; i < 5; i++) {
			assertThat(service.claim()).isNotNull();
			releaseTime();
		}
		assertThat(service.claim()).isNull();
		var result = service.get(batch.id());
		assertThat(result.pending()).isZero();
		assertThat(result.needsAttention()).isEqualTo(1);
		assertThat(result.items().getFirst().code()).isEqualTo("INTERRUPTED");
		assertThat(events.count()).isZero();
	}

	private final Instant dailyStart = LocalDate.of(2026, 9, 6).atTime(3, 0).atZone(ZoneId.of("Asia/Seoul"))
		.toInstant();

	void enableDaily() {
		ReflectionTestUtils.setField(connections, "verifiedAccountReference", "");
		ReflectionTestUtils.setField(service, "singleAccountConfirmed", true);
		ReflectionTestUtils.setField(daily, "enabled", true);
		service.ensureGate();
	}

	void finishPending() {
		while (tasks.activeCount() > 0) {
			releaseTime();
			service.finish(service.claim(), observed(MarketListingObservation.State.PRESENT, "SALE"));
		}
	}

	@Test
	void q24PinsAccountOnceAndAccountChangeCannotReuseAbsenceProof() {
		var p = product();
		var reg = link(p);
		enableDaily();
		var gate = gates.findById(MarketInspectionGate.SMART_STORE_SCOPE).orElseThrow();
		assertThat(gate.getVerifiedAccountReference()).isEqualTo("account-A");
		assertThat(gate.getAccountConfirmedAt()).isNotNull();
		assertThat(gate.getAccountConfirmationEvidence()).contains("USER_Q24_2026-09-06");
		var batch = service.create(List.of(p.getId()), key(), "admin");
		service.finish(service.claim(), observed(MarketListingObservation.State.DELETED, "HTTP_404/NOT_FOUND"));
		assertThat(service.get(batch.id()).detached()).isEqualTo(1);
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState())
			.isEqualTo(MarketConnectionState.DETACHED_DELETED);
		when(adapter.inspectionAccountReference()).thenReturn("account-B");
		ReflectionTestUtils.setField(connections, "verifiedAccountReference", "account-B");
		service.ensureGate();
		assertThat(gates.findById(MarketInspectionGate.SMART_STORE_SCOPE).orElseThrow().getVerifiedAccountReference())
			.isEqualTo("account-A");
		assertThat(connections.accountVerified("account-B")).isFalse();
		daily.tickAt(dailyStart);
		assertThat(sweeps.count()).isZero();
		assertThat(daily.status().accountVerified()).isFalse();
		assertThat(daily.status().nextDueAt()).isNull();
	}

	@Test
	void dailyStartsAfterThreeResumesCursorAndBoundsNewProductsUntilTomorrow() {
		for (int i = 0; i < 3; i++)
			link(product());
		enableDaily();
		daily.tickAt(dailyStart.minusSeconds(1));
		assertThat(sweeps.count()).isZero();
		daily.tickAt(dailyStart.plusSeconds(3600)); // Restart after the scheduled time catches up.
		var initial = sweeps.findTopByOrderByRunDateDesc().orElseThrow();
		assertThat(initial.getEnrolledCount()).isEqualTo(2);
		assertThat(initial.getState()).isEqualTo("ENROLLING");
		var addedLater = product();
		link(addedLater);
		daily.tickAt(dailyStart.plusSeconds(7200));
		var enrolled = sweeps.findById(initial.getId()).orElseThrow();
		assertThat(enrolled.getEnrolledCount()).isEqualTo(3);
		assertThat(enrolled.getState()).isEqualTo("ENQUEUED");
		assertThat(tasks.findAll()).noneMatch(t -> t.getProductId().equals(addedLater.getId()));
		assertThat(daily.batches(initial.getId())).hasSize(2).allSatisfy(b -> assertThat(b.items()).isEmpty());
		daily.tickAt(dailyStart.plusSeconds(86400));
		assertThat(sweeps.count()).isEqualTo(1); // Yesterday's unfinished work wins.
		finishPending();
		daily.tickAt(dailyStart.plusSeconds(86400));
		daily.tickAt(dailyStart.plusSeconds(86460));
		var tomorrow = sweeps.findTopByOrderByRunDateDesc().orElseThrow();
		assertThat(tomorrow.getEnrolledCount()).isEqualTo(4);
		assertThat(sweeps.findById(initial.getId()).orElseThrow().getState()).isEqualTo("FINISHED");
		finishPending();
		daily.tickAt(dailyStart.plusSeconds(86520));
		daily.tickAt(dailyStart.plusSeconds(90000));
		assertThat(sweeps.count()).isEqualTo(2); // No second sweep on the same day.
		assertThat(daily.status().latest().totals().confirmed()).isEqualTo(4);
	}

	@Test
	void concurrentDailyEnrollmentNeverDuplicatesDateOrProducts() throws Exception {
		for (int i = 0; i < 3; i++)
			link(product());
		enableDaily();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			var a = pool.submit(() -> {
				start.await();
				daily.tickAt(dailyStart);
				return true;
			});
			var b = pool.submit(() -> {
				start.await();
				daily.tickAt(dailyStart);
				return true;
			});
			start.countDown();
			assertThat(a.get(10, TimeUnit.SECONDS)).isTrue();
			assertThat(b.get(10, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(sweeps.count()).isEqualTo(1);
		assertThat(tasks.findAll()).hasSize(3).extracting(MarketInspectionTask::getProductId).doesNotHaveDuplicates();
		assertThat(daily.status().latest().enrolled()).isEqualTo(3);
	}

	@Test
	void failedEnrollmentRollsBackCursorBatchAndTasksThenResumes() {
		for (int i = 0; i < 3; i++)
			link(product());
		enableDaily();
		daily.tickAt(dailyStart);
		var before = sweeps.findTopByOrderByRunDateDesc().orElseThrow();
		doThrow(new IllegalStateException("fixture enqueue failure")).when(tasks).save(any());
		assertThatThrownBy(() -> daily.tickAt(dailyStart.plusSeconds(60))).hasMessageContaining("enqueue failure");
		assertThat(sweeps.findById(before.getId()).orElseThrow().getCursorRegistrationId())
			.isEqualTo(before.getCursorRegistrationId());
		assertThat(batches.count()).isEqualTo(1);
		assertThat(tasks.count()).isEqualTo(2);
		reset(tasks);
		daily.tickAt(dailyStart.plusSeconds(120));
		assertThat(tasks.count()).isEqualTo(3);
		assertThat(sweeps.findById(before.getId()).orElseThrow().getEnrolledCount()).isEqualTo(3);
	}

	@Test
	void fullQueueWaitsWithoutLosingEnrollmentPosition() {
		link(product());
		enableDaily();
		doReturn(20000L).when(tasks).activeCountForMarket("SMART_STORE");
		daily.tickAt(dailyStart);
		var waiting = sweeps.findTopByOrderByRunDateDesc().orElseThrow();
		assertThat(waiting.getCursorRegistrationId()).isZero();
		assertThat(batches.count()).isZero();
		reset(tasks);
		daily.tickAt(dailyStart.plusSeconds(60));
		assertThat(sweeps.findById(waiting.getId()).orElseThrow().getEnrolledCount()).isEqualTo(1);
		assertThat(tasks.count()).isEqualTo(1);
	}

	@Test
	void dailyOnlyEnrollsLiveSmartstoreLinksAndDoesNotClaimUnknownAsSuccess() {
		var live = product();
		link(live);
		var missingId = product();
		registrations.saveAndFlush(MarketRegistration.builder().productId(missingId.getId())
			.marketType(MarketType.SMART_STORE).marketIdentifiers("{}").build());
		var deleted = product();
		link(deleted);
		new TransactionTemplate(transactions)
			.executeWithoutResult(s -> products.findById(deleted.getId()).orElseThrow().markDeleted());
		var detached = product();
		var detachedReg = link(detached);
		jdbc.update("update sb_market_registration set connection_state = 'DETACHED_DELETED' where id = ?",
			detachedReg.getId());
		registrations.saveAndFlush(MarketRegistration.builder().productId(product().getId())
			.marketType(MarketType.COUPANG).marketIdentifiers("{}").build());
		enableDaily();
		daily.tickAt(dailyStart);
		daily.tickAt(dailyStart.plusSeconds(60));
		assertThat(tasks.findAll()).hasSize(2).extracting(MarketInspectionTask::getProductId)
			.containsExactlyInAnyOrder(live.getId(), missingId.getId());
		service.finish(service.claim(), observed(MarketListingObservation.State.UNKNOWN, "HTTP_403/NO_PERMISSION"));
		daily.tickAt(dailyStart.plusSeconds(120));
		var status = daily.status();
		assertThat(status.latest().state()).isEqualTo("FINISHED");
		assertThat(status.latest().totals().confirmed()).isZero();
		assertThat(status.latest().totals().needsAttention()).isEqualTo(1);
		assertThat(status.latest().totals().skipped()).isEqualTo(1);
	}

	@Test
	void dailySkipsAlreadySelectedWorkAndDisabledScheduleDoesNotEnroll() {
		var p = product();
		link(p);
		service.create(List.of(p.getId()), key(), "admin");
		daily.tickAt(dailyStart);
		assertThat(sweeps.count()).isZero();
		enableDaily();
		daily.tickAt(dailyStart);
		daily.tickAt(dailyStart.plusSeconds(60));
		assertThat(tasks.activeCount()).isEqualTo(1);
		assertThat(daily.status().latest().totals().skipped()).isEqualTo(1);
		assertThat(daily.status().latest().totals().confirmed()).isZero();
		assertThat(daily.status().latest().state()).isEqualTo("FINISHED");
	}

	@Test
	void separateMarketsHaveIndependentTasksAnd429DoesNotConsumeAnotherMarketsGate() {
		var p = product();
		link(p);
		var eleven = mock(MarketClient.class);
		when(clients.hasClient(MarketType.ELEVEN_STREET)).thenReturn(true);
		when(clients.getClient(MarketType.ELEVEN_STREET)).thenReturn(eleven);
		when(eleven.inspectionAccountReference()).thenReturn("eleven-A");
		registrations.saveAndFlush(MarketRegistration.builder().productId(p.getId())
			.marketType(MarketType.ELEVEN_STREET).marketIdentifiers("{\"prdNo\":\"123\"}").build());
		var smart = service.create(List.of(p.getId()), key(), "admin");
		var other = service.create(List.of(p.getId()), key(), "admin", MarketType.ELEVEN_STREET);
		assertThat(other.pending()).isEqualTo(1);
		assertThat(other.market()).isEqualTo("ELEVEN_STREET");
		service.finish(service.claim(), observed(MarketListingObservation.State.UNKNOWN, "HTTP_429"));
		assertThat(service.claim()).isNull();
		var claim = service.claim(MarketType.ELEVEN_STREET);
		assertThat(claim.snapshot().market()).isEqualTo(MarketType.ELEVEN_STREET);
		assertThat(claim.snapshot().externalId()).isEqualTo("123");
		service.finish(claim, new MarketListingObservation(MarketListingObservation.State.PROHIBITED, "108", "판매금지",
			"eleven-A", "GET /prodmarket/123", Instant.now()));
		assertThat(service.get(other.id()).detached()).isEqualTo(1);
		assertThat(service.get(smart.id()).pending()).isEqualTo(1);
		assertThat(registrations.findByProductIdAndMarketType(p.getId(), MarketType.SMART_STORE).orElseThrow()
			.getConnectionState()).isEqualTo(MarketConnectionState.LINKED);
	}

	@Test
	void legacySmartstoreRequestIdentityStillResumesAndOtherMarketCannotReuseIt() {
		var p = product();
		link(p);
		String id = key();
		service.create(List.of(p.getId()), id, "admin");
		jdbc.update("update sb_market_inspection_batch set request_identity = ? where id = ?",
			"SELECTION:" + List.of(p.getId()), id);
		assertThat(service.create(List.of(p.getId()), id, "admin").pending()).isEqualTo(1);
		assertThatThrownBy(() -> service.create(List.of(p.getId()), id, "admin", MarketType.CAFE24))
			.isInstanceOf(com.sbshop.agent.core.application.product.edit.ProductEditConflictException.class);
		assertThat(tasks.count()).isEqualTo(1);
	}

	MarketClient dailyMarket(MarketType market, Product p) {
		var c = mock(MarketClient.class);
		when(clients.hasClient(market)).thenReturn(true);
		when(clients.getClient(market)).thenReturn(c);
		when(c.inspectionAccountReference()).thenReturn(market.name() + "-account");
		registrations.saveAndFlush(MarketRegistration.builder().productId(p.getId()).marketType(market)
			.marketIdentifiers(
				market == MarketType.COUPANG ? "{\"sellerProductId\":\"123\"}"
					: market == MarketType.ELEVEN_STREET ? "{\"prdNo\":\"123\"}" : "{\"product_no\":\"123\"}")
			.build());
		return c;
	}

	@Test
	void sameDateHasIndependentAllDirectMarketSweepsWithoutExtendingQ24() {
		var p = product();
		link(p);
		dailyMarket(MarketType.COUPANG, p);
		dailyMarket(MarketType.CAFE24, p);
		dailyMarket(MarketType.ELEVEN_STREET, p);
		enableDaily();
		for (var m : DailyMarketInspectionService.SUPPORTED)
			daily.tickAt(dailyStart, m);
		assertThat(sweeps.count()).isEqualTo(4);
		assertThat(tasks.count()).isEqualTo(4);
		for (var m : DailyMarketInspectionService.SUPPORTED) {
			daily.tickAt(dailyStart.plusSeconds(60), m);
			assertThat(daily.status(m).latest().enrolled()).isEqualTo(1);
		}
		assertThat(sweeps.count()).isEqualTo(4);
		assertThat(tasks.count()).isEqualTo(4);
		assertThat(daily.status(MarketType.SMART_STORE).accountVerified()).isTrue();
		assertThat(daily.status(MarketType.COUPANG).accountVerified()).isFalse();
		assertThat(daily.status(MarketType.CAFE24).accountVerified()).isFalse();
		assertThat(gates.findById("COUPANG_ORIGIN_READ").orElseThrow().getVerifiedAccountReference()).isNull();
		assertThat(gates.findById("CAFE24_ORIGIN_READ").orElseThrow().getVerifiedAccountReference()).isNull();
	}

	@Test
	void coupangDailyAbsenceKeepsConnectionUntilHistoricalAccountConfirmed() {
		var p = product();
		dailyMarket(MarketType.COUPANG, p);
		enableDaily();
		daily.tickAt(dailyStart, MarketType.COUPANG);
		var claim = service.claim(MarketType.COUPANG);
		service.finish(claim, new MarketListingObservation(MarketListingObservation.State.DELETED, "HTTP_404_NOT_FOUND",
			"부재", "COUPANG-account", "GET /seller-products/123", Instant.now()));
		assertThat(registrations.findByProductIdAndMarketType(p.getId(), MarketType.COUPANG).orElseThrow()
			.getConnectionState()).isEqualTo(MarketConnectionState.LINKED);
		assertThat(daily.status(MarketType.COUPANG).latest().totals().needsAttention()).isEqualTo(1);
		assertThat(daily.status(MarketType.COUPANG).latest().totals().detached()).isZero();
	}

	@Test
	void coupangExplicitDeletedDetachesButCafe24StoppedRemainsConnected() {
		var p = product();
		dailyMarket(MarketType.COUPANG, p);
		dailyMarket(MarketType.CAFE24, p);
		enableDaily();
		daily.tickAt(dailyStart, MarketType.COUPANG);
		daily.tickAt(dailyStart, MarketType.CAFE24);
		service.finish(service.claim(MarketType.COUPANG),
			new MarketListingObservation(MarketListingObservation.State.DELETED, "상품삭제", "명시 삭제", "COUPANG-account",
				"GET /seller-products/123", Instant.now()));
		service.finish(service.claim(MarketType.CAFE24),
			new MarketListingObservation(MarketListingObservation.State.STOPPED, "SELLING_F", "판매 중지 원인 미확인",
				"CAFE24-account", "GET /products/123", Instant.now()));
		assertThat(registrations.findByProductIdAndMarketType(p.getId(), MarketType.COUPANG).orElseThrow()
			.getConnectionState()).isEqualTo(MarketConnectionState.DETACHED_DELETED);
		assertThat(
			registrations.findByProductIdAndMarketType(p.getId(), MarketType.CAFE24).orElseThrow().getConnectionState())
			.isEqualTo(MarketConnectionState.LINKED);
		assertThat(daily.status(MarketType.COUPANG).latest().totals().detached()).isEqualTo(1);
		assertThat(daily.status(MarketType.CAFE24).latest().totals().detached()).isZero();
	}

	@Test
	void changedCoupangAccountPausesOnlyItsExistingDailyCursor() {
		var first = product();
		var cp = dailyMarket(MarketType.COUPANG, first);
		dailyMarket(MarketType.CAFE24, first);
		for (int i = 0; i < 2; i++) {
			var p = product();
			registrations.saveAndFlush(MarketRegistration.builder().productId(p.getId()).marketType(MarketType.COUPANG)
				.marketIdentifiers("{\"sellerProductId\":\"" + p.getId() + "\"}").build());
		}
		enableDaily();
		daily.tickAt(dailyStart, MarketType.COUPANG);
		var before = daily.status(MarketType.COUPANG).latest();
		when(cp.inspectionAccountReference()).thenReturn("new-coupang-account");
		daily.tickAt(dailyStart.plusSeconds(60), MarketType.COUPANG);
		daily.tickAt(dailyStart.plusSeconds(60), MarketType.CAFE24);
		assertThat(daily.status(MarketType.COUPANG).latest().enrolled()).isEqualTo(before.enrolled());
		assertThat(daily.status(MarketType.COUPANG).detail()).contains("계정이 변경");
		assertThat(daily.status(MarketType.CAFE24).latest().enrolled()).isEqualTo(1);
	}

	@Test
	void saturatedSmartstoreQueueDoesNotBlockCoupangDailyEnrollment() {
		var p = product();
		link(p);
		dailyMarket(MarketType.COUPANG, p);
		enableDaily();
		doReturn(20000L).when(tasks).activeCountForMarket("SMART_STORE");
		daily.tickAt(dailyStart, MarketType.SMART_STORE);
		daily.tickAt(dailyStart, MarketType.COUPANG);
		assertThat(daily.status().latest().enrolled()).isZero();
		assertThat(daily.status(MarketType.COUPANG).latest().enrolled()).isEqualTo(1);
	}

	@Test
	void otherMarketDailyEnqueueFailureRollsBackOnlyItsSweepAndTasks() {
		var p = product();
		dailyMarket(MarketType.COUPANG, p);
		dailyMarket(MarketType.CAFE24, p);
		enableDaily();
		jdbc.execute(
			"alter table sb_market_inspection_batch add constraint daily_market_fixture check (market <> 'COUPANG')");
		try {
			assertThatThrownBy(() -> daily.tickAt(dailyStart, MarketType.COUPANG)).isInstanceOf(RuntimeException.class);
			daily.tickAt(dailyStart, MarketType.CAFE24);
			assertThat(sweeps.findTopByMarketOrderByRunDateDesc("COUPANG")).isEmpty();
			assertThat(tasks.count()).isEqualTo(1);
			assertThat(daily.status(MarketType.CAFE24).latest().enrolled()).isEqualTo(1);
		} finally {
			jdbc.execute("alter table sb_market_inspection_batch drop constraint daily_market_fixture");
		}
	}
}
