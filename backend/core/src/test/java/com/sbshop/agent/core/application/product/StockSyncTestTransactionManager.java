package com.sbshop.agent.core.application.product;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** Exercises TransactionTemplate boundaries without a database in the legacy unit tests. */
final class StockSyncTestTransactionManager extends AbstractPlatformTransactionManager {
	protected Object doGetTransaction() {
		return new Object();
	}

	protected void doBegin(Object transaction, TransactionDefinition definition) {}

	protected void doCommit(DefaultTransactionStatus status) {}

	protected void doRollback(DefaultTransactionStatus status) {}
}
