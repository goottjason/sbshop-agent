package com.sbshop.agent.core.domain.product.batch;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_supplier_batch_run", uniqueConstraints = @UniqueConstraint(name = "uk_supplier_batch_request", columnNames = {
	"actor", "request_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSupplierBatchRun {
	@Id
	@Column(length = 36)
	private String id;
	@Column(nullable = false, length = 36)
	private String requestId;
	@Column(nullable = false, length = 200)
	private String actor;
	@Column(nullable = false, length = 20)
	private String vendor;
	@Column(nullable = false, length = 20)
	private String mode;
	@Column(nullable = false, length = 30)
	private String state;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String requestPayload;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String policy;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String markets;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String accounts = "{}";
	@Column(nullable = false)
	private int total;
	@Column(nullable = false)
	private Instant createdAt;
	@Column(nullable = false)
	private Instant updatedAt;
	private Instant finishedAt;
	private Long activeItemId;
	@Column(length = 36)
	private String leaseToken;
	private Instant leaseUntil;
	@Version
	private long revision;

	public ProductSupplierBatchRun(String id, String requestId, String actor, String vendor, String mode,
		String requestPayload, String policy, String markets, int total, Instant now) {
		this.id = id;
		this.requestId = requestId;
		this.actor = actor;
		this.vendor = vendor;
		this.mode = mode;
		this.requestPayload = requestPayload;
		this.policy = policy;
		this.markets = markets;
		this.total = total;
		createdAt = now;
		updatedAt = now;
		state = total == 0 ? "COMPLETED" : "RUNNING";
		finishedAt = total == 0 ? now : null;
	}

	public void claim(String token, Instant now) {
		leaseToken = token;
		leaseUntil = now.plusSeconds(180);
		updatedAt = now;
	}

	public void accounts(String value) {
		accounts = value;
	}

	public boolean owns(String token) {
		return token != null && token.equals(leaseToken);
	}

	public boolean leased(Instant now) {
		return leaseToken != null && leaseUntil != null && leaseUntil.isAfter(now);
	}

	public void release(Instant now) {
		leaseToken = null;
		leaseUntil = null;
		updatedAt = now;
	}

	public void activeItem(Long id) {
		activeItemId = id;
	}

	public void pause(Instant now) {
		if (state.equals("RUNNING")) {
			state = "PAUSING";
			updatedAt = now;
		}
	}

	public void paused(Instant now) {
		state = "PAUSED";
		updatedAt = now;
	}

	public void resume(Instant now) {
		if (state.equals("PAUSED") || state.equals("PAUSING")) {
			state = "RUNNING";
			updatedAt = now;
		}
	}

	public void reopen(Instant now) {
		if (state.equals("COMPLETED")) {
			state = "PAUSED";
			finishedAt = null;
		}
		updatedAt = now;
	}

	public void complete(Instant now) {
		state = "COMPLETED";
		finishedAt = now;
		updatedAt = now;
		activeItemId = null;
	}
}
