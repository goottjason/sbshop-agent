package com.sbshop.agent.core.domain.product.edit;

import jakarta.persistence.*;
import lombok.*;

/** Durable pending work. A database edit is never recorded as a marketplace success. */
@Entity
@Table(name = "sb_product_change_target")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductChangeTarget {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false)
	private Long historyId;
	@Column(nullable = false)
	private Long productId;
	@Column(nullable = false)
	private Long registrationId;
	@Column(nullable = false)
	private long productRevision;
	@Column(nullable = false, length = 50)
	private String market;
	@Column(nullable = false, length = 30)
	private String state;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String snapshot;
	private Long priceTaskId;
	private Long stockTaskId;
	private Long fieldTaskId;
	@Column(nullable = false)
	private boolean batchManaged;

	/** A supplier batch owns submission and pause/retry decisions; normal dispatchers must skip this row. */
	public void manageByBatch() {
		if (!"PENDING_DISPATCH".equals(state))
			throw new IllegalStateException("접수 전 변경 대상만 배치 관리로 전환할 수 있습니다.");
		batchManaged = true;
		state = "BATCH_MANAGED";
	}

	public void dispatchedToFields(Long id) {
		fieldTaskId = id;
		state = "DISPATCHED";
	}

	public void fieldOutcome(String value) {
		state = value;
	}

	public void dispatchedToStock(Long id) {
		stockTaskId = id;
		state = "DISPATCHED";
	}

	public void stockOutcome(String value) {
		state = value;
	}

	public void dispatchedToPrice(Long id) {
		priceTaskId = id;
		state = "DISPATCHED";
	}

	public void priceOutcome(String value) {
		state = batchManaged && "PENDING_DISPATCH".equals(value) ? "BATCH_MANAGED" : value;
	}

	public void cancelForDetachedConnection() {
		if ("PENDING_DISPATCH".equals(state) || "BATCH_MANAGED".equals(state))
			state = "CANCELLED_DETACHED";
	}

	public ProductChangeTarget(Long historyId, Long productId, Long registrationId, long productRevision, String market,
		String snapshot) {
		this.historyId = historyId;
		this.productId = productId;
		this.registrationId = registrationId;
		this.productRevision = productRevision;
		this.market = market;
		this.snapshot = snapshot;
		this.state = "PENDING_DISPATCH";
	}
}
