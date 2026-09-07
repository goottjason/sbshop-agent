package com.sbshop.agent.core.application.market.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.MarketSalePriceResolver;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.client.dto.MarketPriceRead;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.repository.*;
import com.sbshop.agent.core.domain.market.sync.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.core.domain.product.service.SalePriceRounding;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
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
import org.springframework.transaction.annotation.*;

@DataJpaTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:pricesync;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MarketPriceSyncIntegrationTest.App.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MarketPriceSyncIntegrationTest {
	@SpringBootApplication
	@EntityScan("com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class,
		MarketPriceTaskRepository.class, MarketInspectionGateRepository.class})
	@Import(MarketPriceSyncService.class)
	static class App {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper();
		}
	}

	@Autowired
	MarketPriceSyncService service;
	@Autowired
	MarketPriceTaskRepository tasks;
	@Autowired
	MarketPriceReviewRepository reviews;
	@Autowired
	MarketPriceAttemptRepository attempts;
	@Autowired
	MarketInspectionGateRepository gates;
	@Autowired
	ProductRepository products;
	@Autowired
	MarketRegistrationRepository registrations;
	@Autowired
	JdbcTemplate jdbc;
	@MockitoBean
	MarketClientRouter clients;
	@MockitoBean
	MarketSalePriceResolver prices;
	MarketClient client;
	Product product;
	MarketRegistration reg;
	static final MarketType MARKET = MarketType.SMART_STORE;

	@Autowired
	com.sbshop.agent.core.domain.product.edit.ProductChangeTargetRepository changeTargets;
	@Autowired
	com.sbshop.agent.core.domain.product.edit.ProductChangeHistoryRepository changeHistories;

	@BeforeEach
	void setup() {
		changeTargets.deleteAll();
		changeHistories.deleteAll();
		attempts.deleteAll();
		tasks.deleteAll();
		reviews.deleteAll();
		gates.deleteAll();
		registrations.deleteAll();
		products.deleteAll();
		client = mock(MarketClient.class);
		when(clients.hasClient(MARKET)).thenReturn(true);
		when(clients.getClient(MARKET)).thenReturn(client);
		when(client.inspectionAccountReference()).thenReturn("account-A");
		when(prices.explainForProduct(any(), any(), any()))
			.thenReturn(new MarketSalePriceResolver.Explanation(MarketSalePriceResolver.Basis.CALCULATED,
				SalePriceRounding.fromPrice(new BigDecimal("12300"), new BigDecimal("10000"))));
		product = products.saveAndFlush(Product.create("SB-" + UUID.randomUUID(),
			new ProductCreateCommand("https://example.com/item", new BigDecimal("10000"), "상품", "original", "브랜드", "US",
				new BigDecimal("0.3"), new BigDecimal("25"), MeasureUnit.G, List.of(), List.of(), "html", "FOOD", true,
				1, new BigDecimal("20"), VendorType.IHB, null)));
		reg = registrations.saveAndFlush(MarketRegistration.builder().productId(product.getId()).marketType(MARKET)
			.marketIdentifiers("{\"originProductNo\":\"123\"}").build());
	}

	MarketPriceSyncService.Review preview() {
		return service.preview(List.of(product.getId()), Set.of(MARKET), "admin");
	}

	MarketPriceSyncService.Review queue() {
		var r = preview();
		return service.commit(r.id(), "admin");
	}

	void observed(int price){when(client.readSalePrice("123",null)).thenReturn(new MarketPriceRead(BigDecimal.valueOf(price),true,"fixture","account-A"));}

	void release() {
		jdbc.update("update sb_market_inspection_gate set next_allowed_at=?,lease_until=?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
		jdbc.update("update sb_market_price_task set next_run_at=?,lease_until=?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
	}

	@Test
	void commitIsIdempotentActorBoundAndConcurrentReviewSkipped() {
		var a = preview();
		var b = preview();
		service.commit(a.id(), "admin");
		assertThat(service.commit(a.id(), "admin").items()).hasSize(1);
		assertThat(tasks.count()).isEqualTo(1);
		assertThatThrownBy(() -> service.commit(a.id(), "other")).isInstanceOf(RuntimeException.class);
		assertThat(service.commit(b.id(), "admin").items().getFirst().state()).isEqualTo("SKIPPED");
	}

	@Test
	void alreadyMatchingPriceRequiresNoWriteAndDoesNotConfirmWholeProduct() {
		observed(12300);
		var r = queue();
		service.processOne(MARKET);
		assertThat(service.get(r.id()).items().getFirst().state()).isEqualTo("CONFIRMED_PRICE");
		verify(client, never()).writeSalePrice(any(), any(), any());
		assertThat(registrations.findById(reg.getId()).orElseThrow().getIsSynced()).isNotEqualTo(true);
	}

	@Test
	void timeoutAfterApplicationIsReadBeforeRetryAndNotDuplicated() {
		observed(12000);
		var r = queue();
		doAnswer(call -> {
			observed(12300);
			throw new MarketTransferFailure("TRANSPORT_ERROR", "timeout", null, null);
		}).when(client).writeSalePrice(any(), any(), any());
		service.processOne(MARKET);
		assertThat(service.get(r.id()).items().getFirst().state()).isEqualTo("VERIFY");
		release();
		service.processOne(MARKET);
		assertThat(service.get(r.id()).items().getFirst().state()).isEqualTo("CONFIRMED_PRICE");
		verify(client, times(1)).writeSalePrice(any(), any(), any());
		assertThat(attempts.findAll()).extracting(MarketPriceAttempt::getPhase).contains("WRITE_STARTED",
			"CONFIRMED_PRICE");
	}

	@Test
	void successfulHttpDoesNotHideRepeatedMismatches() {
		observed(12000);
		var r = queue();
		for (int i = 0; i < 4; i++) {
			release();
			service.processOne(MARKET);
		}
		assertThat(service.get(r.id()).items().getFirst().state()).isEqualTo("FAILED_MISMATCH");
		verify(client, times(3)).writeSalePrice(any(), any(), any());
	}

	@Test
	void lostLeaseCannotWriteOrConfirm() {
		observed(12000);
		var r = queue();
		var old = service.claim(MARKET);
		release();
		var newer = service.claim(MARKET);
		assertThat(service.beginWrite(old, BigDecimal.TEN)).isFalse();
		service.finish(old, "CONFIRMED_PRICE", "old", BigDecimal.TEN, null);
		assertThat(service.get(r.id()).items().getFirst().state()).isEqualTo("CHECK");
		assertThat(service.beginWrite(newer, BigDecimal.TEN)).isTrue();
	}

	@Test
	void productRevisionChangeOrDetachmentCancelsOldIntent() {
		observed(12000);
		var r = queue();
		jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
		service.processOne(MARKET);
		assertThat(service.get(r.id()).items().getFirst().state()).isEqualTo("STALE");
		verify(client, never()).writeSalePrice(any(), any(), any());
	}

	@Test
	void disconnectDuringReadNeverWrites() {
		var r = queue();
		when(client.readSalePrice("123", null)).thenAnswer(call -> {
			jdbc.update("update sb_market_registration set connection_state='DETACHED_PROHIBITED' where id=?",
				reg.getId());
			return new MarketPriceRead(BigDecimal.TEN, true, "fixture", "account-A");
		});
		service.processOne(MARKET);
		assertThat(service.get(r.id()).items().getFirst().state()).isEqualTo("STALE");
		verify(client, never()).writeSalePrice(any(), any(), any());
	}

	@Test
	void shared429CooldownBlocksFollowingWorkAndPreservesRetryAfter() {
		var r = queue();
		var until = Instant.now().plusSeconds(900);
		when(client.readSalePrice("123", null))
			.thenThrow(new MarketTransferFailure("HTTP_429", "rate limit", until, null));
		service.processOne(MARKET);
		assertThat(service.claim(MARKET)).isNull();
		assertThat(gates.findById(MarketInspectionGate.SMART_STORE_SCOPE).orElseThrow().getNextAllowedAt())
			.isAfterOrEqualTo(until);
		assertThat(service.get(r.id()).items().getFirst().state()).isEqualTo("VERIFY");
	}

	@Test
	void stoppedOrWrongAccountNeverWrites() {
		var r = queue();
		when(client.readSalePrice("123", null))
			.thenReturn(new MarketPriceRead(BigDecimal.TEN, false, "판매 중지", "account-A"));
		service.processOne(MARKET);
		assertThat(service.get(r.id()).items().getFirst().state()).isEqualTo("BLOCKED");
		verify(client, never()).writeSalePrice(any(), any(), any());
	}

	@Test
	void expiredPreviewDoesNotCreateWork() {
		var r = preview();
		jdbc.update("update sb_market_price_review set expires_at=? where id=?", java.sql.Timestamp.from(Instant.EPOCH),
			r.id());
		assertThatThrownBy(() -> service.commit(r.id(), "admin")).isInstanceOf(RuntimeException.class);
		assertThat(tasks.count()).isZero();
	}

	@Test
	void savedPriceEditAutomaticallyQueuesAndConfirmsOnlyItsPriceTargets() {
		var history = changeHistories.saveAndFlush(new com.sbshop.agent.core.domain.product.edit.ProductChangeHistory(
			UUID.randomUUID().toString(), product.getId(), 0, product.getRevision(), "admin", "[]", "{}"));
		var target = changeTargets.saveAndFlush(
			new com.sbshop.agent.core.domain.product.edit.ProductChangeTarget(history.getId(), product.getId(),
				reg.getId(), product.getRevision(), MARKET.name(), "{\"changes\":[{\"field\":\"salePrice\"}]}"));
		observed(12300);
		service.dispatchSavedPrices();
		service.dispatchSavedPrices();
		assertThat(tasks.count()).isEqualTo(1);
		assertThat(changeTargets.findById(target.getId()).orElseThrow().getState()).isEqualTo("DISPATCHED");
		service.processOne(MARKET);
		assertThat(changeTargets.findById(target.getId()).orElseThrow().getState()).isEqualTo("CONFIRMED_PRICE");
	}

	@Test
	void fieldTargetIsLeftForFieldDispatcherWithoutEnteringPriceQueue() {
		var history = changeHistories.saveAndFlush(new com.sbshop.agent.core.domain.product.edit.ProductChangeHistory(
			UUID.randomUUID().toString(), product.getId(), 0, product.getRevision(), "admin", "[]", "{}"));
		var target = changeTargets.saveAndFlush(
			new com.sbshop.agent.core.domain.product.edit.ProductChangeTarget(history.getId(), product.getId(),
				reg.getId(), product.getRevision(), MARKET.name(), "{\"changes\":[{\"field\":\"barcode\"}]}"));
		service.dispatchSavedPrices();
		assertThat(tasks.count()).isZero();
		assertThat(changeTargets.findById(target.getId()).orElseThrow().getState()).isEqualTo("PENDING_DISPATCH");
	}

	@Test
	void latePrice429PreservesNewerLeaseAndPreventsItsWriteUntilCooldown() {
		var r = queue();
		var old = service.claim(MARKET);
		release();
		var newer = service.claim(MARKET);
		Instant until = Instant.now().plusSeconds(900);
		service.finish(old, "VERIFY", "late 429", null, new MarketTransferFailure("HTTP_429", "limit", until, null));
		var gate = gates.findById(MarketInspectionGate.SMART_STORE_SCOPE).orElseThrow();
		assertThat(gate.getLeaseToken()).isEqualTo(newer.token());
		assertThat(gate.getNextAllowedAt()).isAfterOrEqualTo(until);
		assertThat(service.beginWrite(newer, BigDecimal.TEN)).isFalse();
		assertThat(tasks.findByReviewIdOrderById(r.id()).getFirst().getWrites()).isZero();
		assertThat(service.claim(MARKET)).isNull();
	}

}
