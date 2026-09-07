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
	"spring.datasource.url=jdbc:h2:mem:publication;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
	"spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
	"spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MarketPublicationIntegrationTest.App.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MarketPublicationIntegrationTest {
	@SpringBootApplication
	@EntityScan("com.sbshop.agent.core.domain")
	@EnableJpaRepositories(basePackageClasses = {ProductRepository.class, MarketRegistrationRepository.class,
		MarketPriceTaskRepository.class, MarketInspectionGateRepository.class,
		com.sbshop.agent.core.domain.market.publication.MarketPublicationTaskRepository.class})
	@Import({MarketPriceSyncService.class, MarketPublicationService.class})
	static class App {
		@Bean
		ObjectMapper mapper() {
			return new ObjectMapper();
		}
	}

	@Autowired
	MarketPriceSyncService priceService;
	@Autowired
	MarketPublicationService service;
	@Autowired
	com.sbshop.agent.core.domain.market.publication.MarketPublicationTaskRepository publicationTasks;
	@Autowired
	MarketConnectionEventRepository events;
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

	@BeforeEach
	void setup() {
		publicationTasks.deleteAll();
		events.deleteAll();
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

	MarketPublicationService.View preview(){
  when(client.preparePublication(any(),any())).thenReturn(new com.sbshop.agent.core.domain.market.client.dto.PreparedMarketPublication("{\"frozen\":true}","account-A","상품","50001","식품 > 영양제",new BigDecimal("12300"),300,"https://example.com/image.jpg"));
  return service.prepare(List.of(new MarketPublicationService.Pair(product.getId(),MARKET)),"admin").prepared().getFirst();
 }

	MarketPublicationService.View queue() {
		var r = preview();
		return service.commit(r.id(), "admin");
	}

	void deleted() {
		jdbc.update("update sb_market_registration set connection_state='DETACHED_DELETED' where id=?", reg.getId());
	}

	void observed(int price){when(client.readSalePrice("123",null)).thenReturn(new MarketPriceRead(BigDecimal.valueOf(price),true,"fixture","account-A"));}

	void release() {
		jdbc.update("update sb_market_inspection_gate set next_allowed_at=?,lease_until=?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
		jdbc.update("update sb_market_publication_task set next_run_at=?,lease_until=?",
			java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH));
	}

	@Test
	void candidatesIncludeDeletedButExcludePermanentBanAndUnconfirmedExistingRequest() {
		assertThat(service.candidates(List.of(product.getId()), Set.of(MARKET)).getFirst().selectable()).isFalse();
		deleted();
		assertThat(service.candidates(List.of(product.getId()), Set.of(MARKET)).getFirst().selectable()).isTrue();
		jdbc.update("update sb_market_registration set connection_state='DETACHED_PROHIBITED' where id=?", reg.getId());
		assertThat(service.candidates(List.of(product.getId()), Set.of(MARKET)).getFirst().reason())
			.contains("영구 판매금지");
		registrations.deleteAll();
		assertThat(service.candidates(List.of(product.getId()), Set.of(MARKET)).getFirst().selectable()).isTrue();
	}

	@Test
	void confirmedRegistrationUsesFrozenPayloadAndArchivesOldIdWithoutWholeProductSuccess() {
		deleted();
		var r = queue();
		when(client.submitPreparedPublication(any(), eq(r.id()), eq("{\"frozen\":true}")))
			.thenReturn(Map.of("originProductNo", "456"));
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("VERIFY");
		when(client.verifyPreparedPublication(eq("456"), eq(product.getSbCode()), eq("{\"frozen\":true}")))
			.thenReturn(true);
		release();
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("REGISTERED");
		var linked = registrations.findById(reg.getId()).orElseThrow();
		assertThat(linked.extractLiveLookupId()).isEqualTo("456");
		assertThat(linked.getMarketIdentifiers()).contains("123", "previousIdentifiers", "DETACHED_DELETED");
		assertThat(linked.getPublicationOperationId()).isNull();
		assertThat(linked.getIsSynced()).isFalse();
		assertThat(events.count()).isEqualTo(1);
	}

	@Test
	void timeoutNeverRepeatsCreationAndOperatorIdOnlyTriggersReadback() {
		deleted();
		var r = queue();
		when(client.submitPreparedPublication(any(), any(), any()))
			.thenThrow(new MarketTransferFailure("TRANSPORT_ERROR", "timeout", null, null));
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("UNKNOWN_CREATE");
		release();
		service.processOne();
		verify(client, times(1)).submitPreparedPublication(any(), any(), any());
		assertThat(registrations.findById(reg.getId()).orElseThrow().getPublicationOperationId()).isEqualTo(r.id());
		assertThatThrownBy(() -> service.recheck(r.id(), "123", "admin")).isInstanceOf(IllegalArgumentException.class);
		service.recheck(r.id(), "456", "admin");
		when(client.verifyPreparedPublication(any(), any(), any())).thenReturn(true);
		release();
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("REGISTERED");
		verify(client, times(1)).submitPreparedPublication(any(), any(), any());
	}

	@Test
	void crashedPostIntentDoesNotRepostAndStaleLeaseCannotClaimSuccess() {
		deleted();
		var r = queue();
		var old = service.claim();
		assertThat(old.post()).isTrue();
		release();
		assertThat(service.claim()).isNull();
		assertThat(service.get(r.id()).state()).isEqualTo("UNKNOWN_CREATE");
		service.finish(old, "REGISTERED", "late", Map.of("originProductNo", "456"), null);
		assertThat(service.get(r.id()).state()).isEqualTo("UNKNOWN_CREATE");
		verify(client, never()).submitPreparedPublication(any(), any(), any());
	}

	@Test
	void pendingIntentLocksIdentityAndLegacyPublishUntilVerified() {
		deleted();
		var r = queue();
		var row = registrations.findById(reg.getId()).orElseThrow();
		assertThat(new com.sbshop.agent.core.application.product.edit.ProductEditPolicy().rule("name", List.of(row))
			.editable()).isFalse();
		var guard = new com.sbshop.agent.core.application.market.MarketConnectionWriteGuard(registrations);
		assertThatThrownBy(() -> guard.requireWritable(MARKET, new Object[] {product}))
			.isInstanceOf(IllegalStateException.class);
		assertThatCode(() -> guard.requirePublicationIntent(MARKET, product, r.id())).doesNotThrowAnyException();
	}

	@Test
	void reviewIsActorBoundIdempotentAndRejectsChangedProduct() {
		deleted();
		var r = preview();
		assertThatThrownBy(() -> service.commit(r.id(), "other")).isInstanceOf(RuntimeException.class);
		jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
		assertThatThrownBy(() -> service.commit(r.id(), "admin")).isInstanceOf(RuntimeException.class);
		var fresh = queue();
		assertThat(service.commit(fresh.id(), "admin").state()).isEqualTo("QUEUED");
	}

	@Test
	void changedUnsentProductReleasesIntentButAnUncertainPostDoesNot() {
		deleted();
		var r = queue();
		jdbc.update("update sb_product set revision=revision+1 where id=?", product.getId());
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("STALE");
		assertThat(registrations.findById(reg.getId()).orElseThrow().getPublicationOperationId()).isNull();
		verify(client, never()).submitPreparedPublication(any(), any(), any());
	}

	@Test
	void prohibitedWhileVerifyingNeverReconnects() {
		deleted();
		var r = queue();
		when(client.submitPreparedPublication(any(), any(), any())).thenReturn(Map.of("originProductNo", "456"));
		service.processOne();
		jdbc.update("update sb_market_registration set connection_state='DETACHED_PROHIBITED' where id=?", reg.getId());
		release();
		service.processOne();
		assertThat(service.get(r.id()).state()).isEqualTo("ACTION_REQUIRED");
		assertThat(registrations.findById(reg.getId()).orElseThrow().getConnectionState())
			.isEqualTo(MarketConnectionState.DETACHED_PROHIBITED);
	}
}
