package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.*;
import com.sbshop.agent.core.domain.market.marketplus.MarketPlusPublicCheck;
import com.sbshop.agent.core.domain.market.marketplus.MarketPlusPublicCollection;
import com.sbshop.agent.core.domain.market.marketplus.MarketPlusPublicObservation;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MarketPlusPublicCheckSchemaPostgresTest {
	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	void sql(String sql) throws Exception {
		try (var c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			var s = c.createStatement()) {
			s.execute(sql);
		}
	}

	void ddl(String file) throws Exception {
		sql(Files.readString(Path.of("../docs/ddl/" + file)));
	}

	@Test
	void realDdlValidatesThreeEntitiesAndPreservesObservationColumnsLeaseHistoryCooldownAndUniqueness()
		throws Exception {
		sql("create table sb_market_inspection_gate(id varchar(50) primary key,next_allowed_at timestamptz not null,lease_token varchar(36),lease_until timestamptz)");
		ddl("2026-09-07-marketplus-public-observations.sql");
		ddl("2026-09-08-marketplus-public-check-queue.sql");
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
		try (var factory = new MetadataSources(registry).addAnnotatedClass(MarketPlusPublicCollection.class)
			.addAnnotatedClass(MarketPlusPublicCheck.class).addAnnotatedClass(MarketPlusPublicObservation.class)
			.buildMetadata().buildSessionFactory()) {
			String id = UUID.randomUUID().toString(), request = UUID.randomUUID().toString(),
				lease = UUID.randomUUID().toString();
			Instant now = Instant.parse("2026-09-08T00:00:00Z");
			Long checkId, observationId;
			String history = "{\"" + lease + "\":\"relay\"}";
			try (var session = factory.openSession()) {
				var tx = session.beginTransaction();
				session.persist(new MarketPlusPublicCollection(id, request, "admin", "f".repeat(64), now));
				var observation = new MarketPlusPublicObservation(null, "o".repeat(64), 1L, 7, 42L, "AUCTION",
					"fixture-mall", "fixture-seller", "10186", "P0000PBU", "D888859044",
					"https://itempage3.auction.co.kr/DetailView.aspx?ItemNo=D888859044", "{\"salePrice\":\"12300\"}",
					"NOT_VERIFIED", now, now, "admin");
				session.persist(observation);
				var check = new MarketPlusPublicCheck(id, 1L, "SB-FIXTURE", "AUCTION", "{\"target\":true}",
					"fixture-mall", 2L, now, null);
				check.claim(lease, "relay", now, history);
				session.persist(check);
				tx.commit();
				checkId = check.getId();
				observationId = observation.getId();
			}
			sql("update sb_market_inspection_gate set next_allowed_at=timestamp with time zone '2099-01-01T00:00:00Z',lease_token='other-worker',lease_until=timestamp with time zone '2099-01-01T00:00:00Z' where id='AUCTION_PUBLIC_READ'");
			ddl("2026-09-07-marketplus-public-observations.sql");
			ddl("2026-09-08-marketplus-public-check-queue.sql");
			try (var session = factory.openSession()) {
				var observation = session.find(MarketPlusPublicObservation.class, observationId);
				assertThat(observation.getCafe24ProductNo()).isEqualTo("10186");
				assertThat(observation.getCafe24ProductCode()).isEqualTo("P0000PBU");
				assertThat(observation.getObservedValues()).isEqualTo("{\"salePrice\":\"12300\"}");
				assertThat(observation.getProductRevision()).isEqualTo(7);
				assertThat(observation.getExternalId()).isEqualTo("D888859044");
				assertThat(observation.getCapturedAt()).isEqualTo(now);
				var collection = session.find(MarketPlusPublicCollection.class, id);
				var check = session.find(MarketPlusPublicCheck.class, checkId);
				assertThat(collection.getRequestId()).isEqualTo(request);
				assertThat(check.getState()).isEqualTo("RUNNING");
				assertThat(check.getLeaseToken()).isEqualTo(lease);
				assertThat(check.getLeaseHistory()).isEqualTo(history);
				assertThat(check.getAttempts()).isEqualTo(1);
				assertThat(check.getTargetJson()).isEqualTo("{\"target\":true}");
			}
			// These are the actual DDL columns; generated-schema tests cannot prove digit-boundary naming.
			try (
				var c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword());
				var statement = c.prepareStatement(
					"select cafe24_product_no,cafe24_product_code from sb_marketplus_public_observation where id=?")) {
				statement.setLong(1, observationId);
				try (var rows = statement.executeQuery()) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.getString(1)).isEqualTo("10186");
					assertThat(rows.getString(2)).isEqualTo("P0000PBU");
				}
			}
			try (var session = factory.openSession()) {
				var tx = session.beginTransaction();
				var check = session.find(MarketPlusPublicCheck.class, checkId);
				check.finish("OBSERVED", "실제 저장된 관측 연결", now.plusSeconds(1), null, observationId,
					"{\"salePrice\":\"12300\"}");
				tx.commit();
			}
			try (var session = factory.openSession()) {
				assertThat(session.find(MarketPlusPublicCheck.class, checkId).getObservationId())
					.isEqualTo(observationId);
			}

			try (
				var c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword());
				var statement = c.createStatement();
				var rows = statement.executeQuery(
					"select next_allowed_at,lease_token from sb_market_inspection_gate where id='AUCTION_PUBLIC_READ'")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getTimestamp(1).toInstant()).isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));
				assertThat(rows.getString(2)).isEqualTo("other-worker");
			}
			assertThatThrownBy(() -> sql(
				"insert into sb_marketplus_public_collection(id,request_id,actor,request_hash,created_at) values('duplicate','"
					+ request + "','admin','hash',now())"))
				.isInstanceOf(SQLException.class)
				.satisfies(e -> assertThat(((SQLException)e).getSQLState()).isEqualTo("23505"));
			assertThatThrownBy(() -> sql(
				"insert into sb_marketplus_public_check(collection_id,product_id,market,state,reason,attempts,events) values('"
					+ id + "',1,'AUCTION','QUEUED','duplicate',0,'')"))
				.isInstanceOf(SQLException.class)
				.satisfies(e -> assertThat(((SQLException)e).getSQLState()).isEqualTo("23505"));
			assertThatThrownBy(() -> sql(
				"insert into sb_marketplus_public_check(collection_id,product_id,market,state,reason,attempts,events) values('missing-collection',2,'GMARKET','QUEUED','orphan',0,'')"))
				.isInstanceOf(SQLException.class)
				.satisfies(e -> assertThat(((SQLException)e).getSQLState()).isEqualTo("23503"));
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}
}
