package com.sbshop.agent.core.application.market.sync;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Linearize a new market request against its owning supplier batch's pause command. */
final class SupplierBatchMarketGate {
	private SupplierBatchMarketGate() {}

	static boolean mayStart(JdbcTemplate jdbc, String reviewId) {
		if (!TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("배치 요청 허용 검사는 transaction 안에서 수행해야 합니다.");
		var states = jdbc.queryForList("""
			select r.state from sb_supplier_batch_run r
			where r.id in (select s.batch_id from sb_supplier_batch_stage s
			where s.stage='MARKET' and s.reference_id=?) for update
			""", String.class, reviewId);
		// A normal product-management task has no supplier-batch owner.
		return states.stream().allMatch("RUNNING"::equals);
	}
}
