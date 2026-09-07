package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.*;
import com.sbshop.agent.core.domain.market.sync.*;
import com.sbshop.agent.core.domain.product.edit.ProductChangeTarget;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MarketFieldSchemaPostgresTest {
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	void ddl(String file) throws Exception {
		try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			var s = c.createStatement()) {
			s.execute(Files.readString(Path.of("../docs/ddl/" + file)));
		}
	}

	MarketFieldTask task(String review, Instant now) {
		return new MarketFieldTask(review, null, 1L, "SB-FIELD", 10L, 7, 3, "COUPANG", "123", "456", "{}",
			"fixture-account", "[\"brand\"]", null, now);
	}

	@Test
	void migrationPreservesPreparedPayloadWriteIntentApprovalConsentAndRejectsDuplicateActiveWork() throws Exception {
		try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			var s = c.createStatement()) {
			s.execute(
				"create table sb_product(id bigint primary key); create table sb_market_registration(id bigint primary key)");
		}
		ddl("2026-09-06-product-edit-review.sql");
		ddl("2026-09-06-market-price-sync.sql");
		ddl("2026-09-07-market-stock-sync.sql");
		ddl("2026-09-07-market-field-sync.sql");
		var registry = new StandardServiceRegistryBuilder()
			.applySetting("hibernate.connection.url", postgres.getJdbcUrl())
			.applySetting("hibernate.connection.username", postgres.getUsername())
			.applySetting("hibernate.connection.password", postgres.getPassword())
			.applySetting("hibernate.hbm2ddl.auto", "validate")
			.applySetting("hibernate.implicit_naming_strategy",
				"org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy")
			.applySetting("hibernate.physical_naming_strategy",
				"org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
			.build();
		try (var factory = new MetadataSources(registry).addAnnotatedClass(MarketFieldReview.class)
			.addAnnotatedClass(MarketFieldTask.class).addAnnotatedClass(MarketFieldAttempt.class)
			.addAnnotatedClass(ProductChangeTarget.class).buildMetadata().buildSessionFactory()) {
			Instant now = Instant.parse("2026-09-07T14:00:00Z");
			String reviewId = UUID.randomUUID().toString();
			Long taskId;
			try (var s = factory.openSession()) {
				var tx = s.beginTransaction();
				var review = new MarketFieldReview(reviewId, "reviewer", false, now);
				review.commit(true, now);
				s.persist(review);
				var t = task(reviewId, now);
				t.prepared("456", "{\"brand\":\"new\"}", "{\"brand\":\"new\"}", true, false, now);
				t.queue(now);
				t.claim("lease", now.plusSeconds(180));
				t.observed("{\"brand\":\"old\"}", "APPROVED", now);
				t.beginWrite(now);
				s.persist(t);
				s.flush();
				taskId = t.getId();
				s.persist(
					new MarketFieldAttempt(taskId, "WRITE_STARTED", "uncertain write", "{\"brand\":\"old\"}", now));
				tx.commit();
			}
			ddl("2026-09-07-market-field-sync.sql");
			try (var s = factory.openSession()) {
				var t = s.find(MarketFieldTask.class, taskId);
				assertThat(t.getState()).isEqualTo("VERIFY");
				assertThat(t.getWrites()).isOne();
				assertThat(t.getPreparedPayload()).isEqualTo("{\"brand\":\"new\"}");
				assertThat(t.getObservedValues()).isEqualTo("{\"brand\":\"old\"}");
				assertThat(t.getLeaseUntil()).isEqualTo(now.plusSeconds(180));
				assertThat(s.find(MarketFieldReview.class, reviewId).isApprovalConsent()).isTrue();
				assertThat(s.createQuery("select count(a) from MarketFieldAttempt a", Long.class).getSingleResult())
					.isOne();
			}
			for (String state : java.util.List.of("PREPARE", "DRAFT", "CHECK", "VERIFY", "AWAITING_APPROVAL")) {
				try (var s = factory.openSession()) {
					var tx = s.beginTransaction();
					assertThatThrownBy(() -> {
						var duplicate = task(reviewId, now);
						duplicate.finish(state, "duplicate", now, now);
						s.persist(duplicate);
						s.flush();
					}).isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
					tx.rollback();
				}
			}
			try (var s = factory.openSession()) {
				var tx = s.beginTransaction();
				s.find(MarketFieldTask.class, taskId).finish("CONFIRMED_FIELDS", "separate read proof",
					now.plusSeconds(60), now.plusSeconds(60));
				s.flush();
				s.persist(task(reviewId, now.plusSeconds(90)));
				tx.commit();
			}
			try (var s = factory.openSession()) {
				assertThat(s.createQuery("select count(t) from MarketFieldTask t", Long.class).getSingleResult())
					.isEqualTo(2);
				assertThat(s.find(MarketFieldTask.class, taskId).getFinishedAt()).isEqualTo(now.plusSeconds(60));
			}
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}
}
