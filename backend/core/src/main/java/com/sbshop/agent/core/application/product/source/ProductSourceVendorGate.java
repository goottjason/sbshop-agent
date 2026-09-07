package com.sbshop.agent.core.application.product.source;

import com.sbshop.agent.core.application.product.content.*;
import com.sbshop.agent.core.domain.product.content.*;
import java.time.Instant;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** One durable sourcing permit per vendor, shared by content and price/stock workers. */
public final class ProductSourceVendorGate {
	private ProductSourceVendorGate() {}

	public static String id(String vendor) {
		return "SOURCE_" + vendor;
	}

	// Called within the caller's short claim transaction, after its workflow-lane lock.
	public static boolean claim(ProductContentLaneRepository lanes, String vendor, String token, Instant now) {
		ProductContentLane gate = lanes.findLocked(id(vendor)).orElse(null);
		if (gate == null || gate.getNextAllowedAt() != null && now.isBefore(gate.getNextAllowedAt())
			|| gate.getSnapshotId() != null && gate.getLeaseUntil() != null && now.isBefore(gate.getLeaseUntil()))
			return false;
		gate.claim(token, now);
		return true;
	}

	// Late 429s still extend the vendor cooldown, but cannot release a newer worker's permit.
	public static boolean finish(ProductContentLaneRepository lanes, String vendor, String token, Instant now,
		boolean throttled, Instant serverAt) {
		ProductContentLane gate = lanes.findLocked(id(vendor)).orElseThrow();
		if (throttled)
			gate.throttle(now, serverAt);
		boolean owns = Objects.equals(token, gate.getSnapshotId());
		boolean valid = owns && gate.getLeaseUntil() != null && now.isBefore(gate.getLeaseUntil());
		if (owns)
			gate.release(now);
		return valid;
	}

	public static void beforeHttp(ProductContentLaneRepository lanes, PlatformTransactionManager transactions,
		String vendor, String token) {
		if (TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("소싱 HTTP 요청 중 DB transaction을 유지할 수 없습니다.");
		TransactionTemplate tx = new TransactionTemplate(transactions);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		tx.executeWithoutResult(status -> {
			ProductContentLane gate = lanes.findLocked(id(vendor)).orElseThrow();
			Instant now = Instant.now();
			if (gate.getNextAllowedAt() != null && now.isBefore(gate.getNextAllowedAt()))
				throw new ProductContentThrottledException(gate.getNextAllowedAt());
			if (!Objects.equals(token, gate.getSnapshotId()) || gate.getLeaseUntil() == null
				|| !now.isBefore(gate.getLeaseUntil()))
				throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_REQUEST_FAILED);
		});
	}
}
