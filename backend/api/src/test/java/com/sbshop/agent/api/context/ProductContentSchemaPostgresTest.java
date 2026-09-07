package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.*;

import com.sbshop.agent.core.domain.product.content.*;
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

/** Validates the reviewed DDL rather than letting Hibernate silently repair missing columns. */
@Testcontainers
class ProductContentSchemaPostgresTest {
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	void migrate() throws Exception {
		String sql = Files.readString(Path.of("../docs/ddl/2026-09-07-product-content-refresh.sql"));
		try (
			var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword());
			var statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	@Test
	void reviewedDdlValidatesAllContentEntitiesAndCanBeReappliedWithoutLosingHistory() throws Exception {
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
		try (var factory = new MetadataSources(registry)
			.addAnnotatedClass(ProductContentCollection.class).addAnnotatedClass(ProductContentSnapshot.class)
			.addAnnotatedClass(ProductContentReview.class).addAnnotatedClass(ProductContentLane.class)
			.buildMetadata().buildSessionFactory()) {
			String collectionId = UUID.randomUUID().toString();
			String requestId = UUID.randomUUID().toString();
			String snapshotId = UUID.randomUUID().toString();
			String reviewId = UUID.randomUUID().toString();
			Instant now = Instant.parse("2026-09-07T12:30:00Z");
			try (var session = factory.openSession()) {
				var transaction = session.beginTransaction();
				session.persist(new ProductContentCollection(collectionId, requestId, "reviewer", now, "[1]"));
				var snapshot = new ProductContentSnapshot(snapshotId, collectionId, 1L, "SB-CONTENT", 7,
					"f".repeat(64), "https://www.iherb.com/pr/test/12345", "IHB", now, "{}");
				snapshot.claim(UUID.randomUUID().toString());
				snapshot.complete("{}", true, true, now);
				snapshot.applied(true, false, now);
				session.persist(snapshot);
				session.persist(new ProductContentReview(reviewId, "reviewer", now, now.plusSeconds(1800), "[]"));
				var lane = session.find(ProductContentLane.class, "IHB");
				assertThat(lane).isNotNull();
				lane.throttle(now);
				transaction.commit();
			}
			migrate();
			try (var session = factory.openSession()) {
				var stored = session.find(ProductContentSnapshot.class, snapshotId);
				assertThat(stored.getState()).isEqualTo(ProductContentSnapshot.State.READY);
				assertThat(stored.getImagesCollectedAt()).isEqualTo(now);
				assertThat(stored.getDetailCollectedAt()).isEqualTo(now);
				assertThat(stored.getImagesAppliedAt()).isEqualTo(now);
				assertThat(stored.getDetailAppliedAt()).isNull();
				assertThat(session.find(ProductContentCollection.class, collectionId).getRequestId())
					.isEqualTo(requestId);
				assertThat(session.find(ProductContentReview.class, reviewId).getActor()).isEqualTo("reviewer");
				assertThat(session.find(ProductContentLane.class, "IHB").getNextAllowedAt())
					.isEqualTo(now.plusSeconds(300));
			}
			try (var session = factory.openSession()) {
				var transaction = session.beginTransaction();
				session.persist(
					new ProductContentCollection(UUID.randomUUID().toString(), requestId, "reviewer", now, "[2]"));
				assertThatThrownBy(session::flush)
					.isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
				transaction.rollback();
			}
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}
}
