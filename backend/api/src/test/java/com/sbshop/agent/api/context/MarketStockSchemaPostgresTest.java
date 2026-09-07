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

/** PostgreSQL enforces the active-task partial index; Hibernate may not create or repair this schema. */
@Testcontainers
class MarketStockSchemaPostgresTest {
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	private void migrate() throws Exception {
		try (
			var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword());
			var statement = connection.createStatement()) {
			statement.execute(Files.readString(Path.of("../docs/ddl/2026-09-07-market-stock-sync.sql")));
		}
	}

	private void baseline() throws Exception {
		try (
			var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword());
			var statement = connection.createStatement()) {
			statement.execute(
				"CREATE TABLE sb_product (id bigint PRIMARY KEY); CREATE TABLE sb_market_registration (id bigint PRIMARY KEY)");
			statement.execute(Files.readString(Path.of("../docs/ddl/2026-09-06-product-edit-review.sql")));
			statement.execute(Files.readString(Path.of("../docs/ddl/2026-09-06-market-price-sync.sql")));
		}
	}

	private MarketStockTask task(String review, Instant now) {
		return new MarketStockTask(review, 1L, "SB-STOCK", 10L, 7, 3, "COUPANG", "123", "456", "{}",
			"fixture-account", 300, null, now);
	}

	@Test
	void reviewedDdlPreservesUncertainWriteAndPreventsDuplicateActiveTasks() throws Exception {
		baseline();
		migrate();
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
		try (var factory = new MetadataSources(registry).addAnnotatedClass(MarketStockReview.class)
			.addAnnotatedClass(MarketStockTask.class).addAnnotatedClass(MarketStockAttempt.class)
			.addAnnotatedClass(ProductChangeTarget.class)
			.buildMetadata().buildSessionFactory()) {
			Instant now = Instant.parse("2026-09-07T14:00:00Z");
			String id = UUID.randomUUID().toString();
			Long taskId;
			Long targetId;
			try (var session = factory.openSession()) {
				var transaction = session.beginTransaction();
				var review = new MarketStockReview(id, "reviewer", "[]", now);
				review.commit(now);
				session.persist(review);
				var task = task(id, now);
				task.claim(UUID.randomUUID().toString(), now.plusSeconds(180));
				task.observed(12, "456", now);
				task.beginWrite(now);
				session.persist(task);
				session.flush();
				taskId = task.getId();
				session.persist(
					new MarketStockAttempt(taskId, "WRITE_STARTED", "Receipt unknown; read before retry", now));
				session.createNativeMutationQuery("insert into sb_product(id) values (1)").executeUpdate();
				session.createNativeMutationQuery("insert into sb_market_registration(id) values (10)").executeUpdate();
				session.createNativeMutationQuery(
					"insert into sb_product_change_history(id,review_id,product_id,before_revision,after_revision,actor,created_at,changes,review_details) values (1,:review,1,6,7,'reviewer',:now,'[]','{}')")
					.setParameter("review", id).setParameter("now", now).executeUpdate();
				var target = new ProductChangeTarget(1L, 1L, 10L, 7, "COUPANG", "{}");
				target.dispatchedToStock(taskId);
				session.persist(target);
				session.flush();
				targetId = target.getId();
				transaction.commit();
			}
			migrate();
			try (var session = factory.openSession()) {
				var stored = session.find(MarketStockTask.class, taskId);
				assertThat(stored.getState()).isEqualTo("VERIFY");
				assertThat(stored.getExpectedQuantity()).isEqualTo(300);
				assertThat(stored.getObservedQuantity()).isEqualTo(12);
				assertThat(stored.getResolvedOptionId()).isEqualTo("456");
				assertThat(stored.getWrites()).isEqualTo(1);
				assertThat(stored.getLeaseUntil()).isEqualTo(now.plusSeconds(180));
				assertThat(session.find(MarketStockReview.class, id).getCommittedAt()).isEqualTo(now);
				assertThat(session.find(ProductChangeTarget.class, targetId).getStockTaskId()).isEqualTo(taskId);
				assertThat(session.find(ProductChangeTarget.class, targetId).getPriceTaskId()).isNull();
				assertThat(
					session.createQuery("select count(a) from MarketStockAttempt a", Long.class).getSingleResult())
					.isEqualTo(1);
			}
			try (var session = factory.openSession()) {
				var transaction = session.beginTransaction();
				assertThatThrownBy(() -> {
					session.persist(task(id, now));
					session.flush();
				})
					.isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
				transaction.rollback();
			}
			try (var session = factory.openSession()) {
				var transaction = session.beginTransaction();
				var stored = session.find(MarketStockTask.class, taskId);
				stored.observed(300, "456", now.plusSeconds(30));
				stored.finish("CONFIRMED_QUANTITY", "Observed 300", now.plusSeconds(30), now.plusSeconds(30));
				session.flush();
				session.persist(task(id, now.plusSeconds(60)));
				transaction.commit();
			}
			try (var session = factory.openSession()) {
				assertThat(session.createQuery("select count(t) from MarketStockTask t", Long.class).getSingleResult())
					.isEqualTo(2);
				assertThat(session.find(MarketStockTask.class, taskId).getObservedQuantity()).isEqualTo(300);
			}
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}
}
