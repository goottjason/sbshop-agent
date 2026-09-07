package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.*;
import com.sbshop.agent.core.domain.market.inspection.MarketInspectionSweep;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.UUID;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MarketDailyInspectionSchemaPostgresTest {
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	void ddl(String file) throws Exception {
		try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			var s = c.createStatement()) {
			s.execute(Files.readString(Path.of("../docs/ddl/" + file)));
		}
	}

	@Test
	void migrationPreservesLegacySmartstoreCursorAndAllowsOneSweepPerMarketAndDay() throws Exception {
		try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			var s = c.createStatement()) {
			s.execute(
				"create table sb_market_inspection_gate(id varchar(50) primary key); create table sb_market_inspection_batch(id varchar(36) primary key,created_at timestamptz); create table sb_market_registration(id bigint primary key,market_type varchar(50),connection_state varchar(30))");
		}
		ddl("2026-09-06-market-inspection-daily.sql");
		String legacy = UUID.randomUUID().toString();
		try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			var s = c.prepareStatement(
				"insert into sb_market_inspection_sweep values (?,date '2026-09-08','legacy-account',100,20,20,1,'ENROLLING',now(),now(),null)")) {
			s.setString(1, legacy);
			s.executeUpdate();
		}
		ddl("2026-09-08-market-inspection-daily-multiple-markets.sql");
		ddl("2026-09-08-market-inspection-daily-multiple-markets.sql");
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
		try (var f = new MetadataSources(registry).addAnnotatedClass(MarketInspectionSweep.class).buildMetadata()
			.buildSessionFactory()) {
			try (var s = f.openSession()) {
				var old = s.find(MarketInspectionSweep.class, legacy);
				assertThat(old.getMarket()).isEqualTo("SMART_STORE");
				assertThat(old.getCursorRegistrationId()).isEqualTo(20);
				assertThat(old.getAccountReference()).isEqualTo("legacy-account");
			}
			for (var market : java.util.List.of("COUPANG", "CAFE24"))
				try (var s = f.openSession()) {
					var tx = s.beginTransaction();
					s.persist(new MarketInspectionSweep(UUID.randomUUID().toString(), LocalDate.of(2026, 9, 8),
						market + "-account", 100, Instant.now(), market));
					tx.commit();
				}
			try (var s = f.openSession()) {
				assertThat(s.createQuery("select count(s) from MarketInspectionSweep s", Long.class).getSingleResult())
					.isEqualTo(3);
			}
			try (var s = f.openSession()) {
				var tx = s.beginTransaction();
				assertThatThrownBy(() -> {
					s.persist(new MarketInspectionSweep(UUID.randomUUID().toString(), LocalDate.of(2026, 9, 8),
						"another-account", 100, Instant.now(), "COUPANG"));
					s.flush();
				}).isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
				tx.rollback();
			}
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}
}
