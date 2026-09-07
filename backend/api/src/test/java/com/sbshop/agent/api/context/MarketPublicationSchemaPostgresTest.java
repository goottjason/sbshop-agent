package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.*;
import com.sbshop.agent.core.domain.market.publication.MarketPublicationTask;
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
class MarketPublicationSchemaPostgresTest {
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	void sql(String sql) throws Exception {
		try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			var s = c.createStatement()) {
			s.execute(sql);
		}
	}

	void ddl(String name) throws Exception {
		sql(Files.readString(Path.of("../docs/ddl/" + name)));
	}

	@Test
	void migrationPreservesUncertainPostOwnershipAndIndependentSetupWriteCount() throws Exception {
		sql("create table sb_product(id bigint primary key);create table sb_market_registration(id bigint primary key)");
		ddl("2026-09-06-market-reviewed-publication.sql");
		sql("insert into sb_market_publication_task(id,product_id,product_revision,market,actor,sb_code,connection_snapshot,prepared,state,detail,created_at,expires_at,next_run_at) values('legacy',1,0,'SMART_STORE','admin','SB1','{}','{}','UNKNOWN_CREATE','unknown',now(),now()+interval '30 minutes',now())");
		ddl("2026-09-08-reviewed-publication-multiple-markets.sql");
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
		try (var factory = new MetadataSources(registry).addAnnotatedClass(MarketPublicationTask.class).buildMetadata()
			.buildSessionFactory()) {
			String id = UUID.randomUUID().toString();
			Instant now = Instant.parse("2026-09-08T00:00:00Z");
			try (var s = factory.openSession()) {
				var tx = s.beginTransaction();
				assertThat(s.find(MarketPublicationTask.class, "legacy").isPostAuthorized()).isTrue();
				var task = new MarketPublicationTask(id, 2L, 10L, 3, "CAFE24", "admin", "SB2", "{}", "frozen", "ready",
					now);
				task.commit(10L, now);
				task.authorizePost();
				task.authorizeSetup();
				task.authorizeSetup();
				task.identifiers("{\"product_no\":\"123\",\"receiptOwnership\":\"fixture\"}", "123");
				task.finish("VERIFY", "read pending", now, now.plusSeconds(60));
				s.persist(task);
				tx.commit();
			}
			ddl("2026-09-08-reviewed-publication-multiple-markets.sql");
			try (var s = factory.openSession()) {
				var task = s.find(MarketPublicationTask.class, id);
				assertThat(task.isPostAuthorized()).isTrue();
				assertThat(task.getSetupWrites()).isEqualTo(2);
				assertThat(task.getReturnedIdentifiers()).contains("receiptOwnership");
				assertThat(task.getState()).isEqualTo("VERIFY");
				assertThat(task.getPrepared()).isEqualTo("frozen");
				assertThat(s.find(MarketPublicationTask.class, "legacy").getState()).isEqualTo("UNKNOWN_CREATE");
			}
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}
}
