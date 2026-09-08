package com.sbshop.agent.core.application.product.batch;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Source lane -> owning run lock; already admitted collections may drain, queued collections cannot start paused. */
public final class ProductSupplierBatchSourceGate {
	private ProductSupplierBatchSourceGate() {}

	public static boolean mayStart(JdbcTemplate jdbc, String collectionId) {
		if (!TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("소싱 배치 admission은짧은claim transaction이필요합니다.");
		var owners = jdbc.queryForList(
			"SELECT DISTINCT s.batch_id FROM sb_supplier_batch_stage s JOIN sb_product_source_collection c ON c.request_id=s.operation_id WHERE c.id=? AND s.stage='CRAWL'",
			String.class, collectionId);
		if (owners.isEmpty())
			return true;
		if (owners.size() != 1)
			return false;
		var states = jdbc.queryForList("SELECT state FROM sb_supplier_batch_run WHERE id=? FOR UPDATE", String.class,
			owners.getFirst());
		return states.size() == 1 && states.getFirst().equals("RUNNING");
	}
}
