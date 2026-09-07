package com.sbshop.agent.core.domain.product.content;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_product_content_lane")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductContentLane {
	@Id
	@Column(length = 20)
	private String id;
	@Column(length = 36)
	private String snapshotId;
	private Instant leaseUntil;
	private Instant nextAllowedAt;

	public ProductContentLane(String id) {
		this.id = id;
	}

	public void claim(String snapshotId, Instant now) {
		this.snapshotId = snapshotId;
		this.leaseUntil = now.plusSeconds(600);
	}

	public void release(Instant now) {
		this.snapshotId = null;
		this.leaseUntil = null;
		deferUntil(now.plusSeconds(5));
	}

	public void throttle(Instant now) {
		throttle(now, null);
	}

	public void throttle(Instant now, Instant serverRetryAfter) {
		Instant minimum = now.plusSeconds(300);
		deferUntil(serverRetryAfter != null && serverRetryAfter.isAfter(minimum) ? serverRetryAfter : minimum);
	}

	private void deferUntil(Instant next) {
		if (nextAllowedAt == null || next.isAfter(nextAllowedAt))
			nextAllowedAt = next;
	}
}
