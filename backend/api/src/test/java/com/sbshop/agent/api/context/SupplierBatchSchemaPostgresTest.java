package com.sbshop.agent.api.context;

import static org.assertj.core.api.Assertions.*;

import com.sbshop.agent.core.domain.product.batch.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Validates deployed DDL, including PostgreSQL-only uniqueness and restart durability. */
@Testcontainers
class SupplierBatchSchemaPostgresTest {

	@Container
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

	private void sql(String sql) throws Exception {
		try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
			postgres.getPassword()); var statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private void migrate() throws Exception {
		sql(Files.readString(Path.of("../docs/ddl/2026-09-08-supplier-batch-orchestration.sql")));
	}

	@Test
	void repeatedMigrationPreservesFrozenWorkAndPreventsConflictingRuns() throws Exception {
		sql("create table sb_product_change_target(id bigint primary key); insert into sb_product_change_target values (1)");
		migrate();
		sql("update sb_product_change_target set batch_managed=true where id=1");
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
			.addAnnotatedClass(ProductSupplierBatchRun.class).addAnnotatedClass(ProductSupplierBatchItem.class)
			.addAnnotatedClass(ProductSupplierBatchStage.class).addAnnotatedClass(ProductSupplierBatchAttempt.class)
			.addAnnotatedClass(ProductSupplierBatchRetry.class).buildMetadata().buildSessionFactory()) {
			var now = Instant.parse("2026-09-08T03:00:00Z");
			var id = UUID.randomUUID().toString();
			Long itemId;
			Long stageId;
			try (var session = factory.openSession()) {
				var transaction = session.beginTransaction();
				var run = new ProductSupplierBatchRun(id, UUID.randomUUID().toString(), "reviewer", "IHB",
					"PRICE_STOCK", "{}", "{\"marginRate\":10,\"couponRate\":20,\"minMarginPrice\":1500}",
					"[\"COUPANG\"]", 2114, now);
				run.accounts("{\"COUPANG\":\"frozen-account\"}");
				run.pause(now);
				run.paused(now);
				session.persist(run);
				var item = new ProductSupplierBatchItem(id, 1L, "SB-FROZEN", "Frozen product", null, now);
				session.persist(item);
				session.flush();
				itemId = item.getId();
				var stage = new ProductSupplierBatchStage(id, itemId, "MARKET", "COUPANG", "PRICE", now);
				stage.start(now);
				stage.reference(UUID.randomUUID().toString());
				stage.values("12300", "12000");
				stage.waitUntil("전송 결과 재조회 대기", now.plusSeconds(60));
				session.persist(stage);
				session.flush();
				stageId = stage.getId();
				session.persist(new ProductSupplierBatchAttempt(stage, now));
				session.persist(new ProductSupplierBatchRetry(id, UUID.randomUUID().toString(), "reviewer", "{}", now));
				transaction.commit();
			}
			sql("insert into sb_supplier_batch_item(batch_id,product_id,sb_code,state,detail,attempts,updated_at) "
				+ "select '" + id + "',n,'SB-'||n,'WAITING','대기',0,now() from generate_series(2,2114) n");
			migrate();
			migrate();
			try (var session = factory.openSession()) {
				assertThat(session.find(ProductSupplierBatchRun.class, id).getAccounts()).contains("frozen-account");
				assertThat(session.find(ProductSupplierBatchRun.class, id).getState()).isEqualTo("PAUSED");
				assertThat(session.find(ProductSupplierBatchItem.class, itemId).getSbCode()).isEqualTo("SB-FROZEN");
				var stage = session.find(ProductSupplierBatchStage.class, stageId);
				assertThat(stage.getReferenceId()).isNotBlank();
				assertThat(stage.getExpected()).isEqualTo("12300");
				assertThat(stage.getObserved()).isEqualTo("12000");
				assertThat(stage.getNextRunAt()).isEqualTo(now.plusSeconds(60));
				assertThat(session.createQuery("select count(i) from ProductSupplierBatchItem i", Long.class)
					.getSingleResult()).isEqualTo(2114);
				assertThat(session.createQuery("select count(a) from ProductSupplierBatchAttempt a", Long.class)
					.getSingleResult()).isEqualTo(1);
				assertThat(session.createQuery("select count(r) from ProductSupplierBatchRetry r", Long.class)
					.getSingleResult()).isEqualTo(1);
				assertThat(session
					.createNativeQuery("select batch_managed from sb_product_change_target where id=1", Boolean.class)
					.getSingleResult()).isTrue();
			}
			var anotherId = UUID.randomUUID().toString();
			String duplicateRun = "insert into sb_supplier_batch_run(id,request_id,actor,vendor,mode,state,request_payload,policy,markets,total,created_at,updated_at) "
				+ "values('" + anotherId + "','" + UUID.randomUUID()
				+ "','reviewer','IHB','STOCK','RUNNING','{}','{}','[]',1,now(),now())";
			assertThatThrownBy(() -> sql(duplicateRun)).isInstanceOf(SQLException.class)
				.hasMessageContaining("uk_supplier_batch_active_vendor");
			assertThatThrownBy(() -> sql(
				"insert into sb_supplier_batch_item(batch_id,product_id,state,detail,attempts,updated_at) values('" + id
					+ "',1,'WAITING','',0,now())"))
				.isInstanceOf(SQLException.class).hasMessageContaining("uk_supplier_batch_product");
			assertThatThrownBy(() -> sql(
				"insert into sb_supplier_batch_stage(batch_id,item_id,stage,market,field,state,detail,retryable,attempts,operation_id,next_run_at) values('missing-run',"
					+ itemId + ",'MARKET','COUPANG','STOCK','WAITING','',false,0,'" + UUID.randomUUID() + "',now())"))
				.isInstanceOf(SQLException.class).hasMessageContaining("foreign key");
			sql("update sb_supplier_batch_run set state='COMPLETED',finished_at=now() where id='" + id + "'");
			sql(duplicateRun);
			assertThatThrownBy(() -> sql("update sb_supplier_batch_run set state='RUNNING' where id='" + id + "'"))
				.isInstanceOf(SQLException.class).hasMessageContaining("uk_supplier_batch_active_vendor");
		} finally {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}
}
