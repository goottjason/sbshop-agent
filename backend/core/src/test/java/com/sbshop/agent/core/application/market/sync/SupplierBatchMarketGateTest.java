package com.sbshop.agent.core.application.market.sync;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class SupplierBatchMarketGateTest {
	private JdbcTemplate jdbc;
	private TransactionTemplate tx;

	@BeforeEach
	void database() {
		var data = new DriverManagerDataSource("jdbc:h2:mem:batchgate" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa",
			"");
		jdbc = new JdbcTemplate(data);
		tx = new TransactionTemplate(new DataSourceTransactionManager(data));
		jdbc.execute("create table sb_supplier_batch_run(id varchar(36) primary key,state varchar(30))");
		jdbc.execute(
			"create table sb_supplier_batch_stage(batch_id varchar(36),stage varchar(20),reference_id varchar(36))");
	}

	@Test
	void unownedProductManagementReviewsRemainRunnable() {
		assertThat((Boolean)tx.execute(s -> SupplierBatchMarketGate.mayStart(jdbc, "normal-review"))).isTrue();
	}

	@Test
	void pauseBlocksNewRequestsAndResumeAllowsTheSamePersistedTask() {
		jdbc.update("insert into sb_supplier_batch_run values('run','RUNNING')");
		jdbc.update("insert into sb_supplier_batch_stage values('run','MARKET','child-review')");
		assertThat((Boolean)tx.execute(s -> SupplierBatchMarketGate.mayStart(jdbc, "child-review"))).isTrue();
		for (String state : new String[] {"PAUSING", "PAUSED", "COMPLETED"}) {
			jdbc.update("update sb_supplier_batch_run set state=? where id='run'", state);
			assertThat((Boolean)tx.execute(s -> SupplierBatchMarketGate.mayStart(jdbc, "child-review"))).as(state)
				.isFalse();
			assertThat((Boolean)tx.execute(s -> SupplierBatchMarketGate.mayStart(jdbc, "unrelated-review"))).isTrue();
		}
		jdbc.update("update sb_supplier_batch_run set state='RUNNING' where id='run'");
		assertThat((Boolean)tx.execute(s -> SupplierBatchMarketGate.mayStart(jdbc, "child-review"))).isTrue();
	}

	@Test
	void matchingSourceReferenceIsNotInterpretedAsMarketOwnership() {
		jdbc.update("insert into sb_supplier_batch_run values('run','PAUSED')");
		jdbc.update("insert into sb_supplier_batch_stage values('run','CRAWL','same-reference')");
		assertThat((Boolean)tx.execute(s -> SupplierBatchMarketGate.mayStart(jdbc, "same-reference"))).isTrue();
	}

	@Test
	void ownershipReadRequiresATransactionToRetainThePauseLockUntilAdmissionCommits() {
		assertThatThrownBy(() -> SupplierBatchMarketGate.mayStart(jdbc, "review"))
			.isInstanceOf(IllegalStateException.class);
	}
}
