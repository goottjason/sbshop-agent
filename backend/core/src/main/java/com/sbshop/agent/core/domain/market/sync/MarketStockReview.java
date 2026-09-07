package com.sbshop.agent.core.domain.market.sync;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_market_stock_review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketStockReview {
	@Id
	@Column(length = 36)
	private String id;
	@Column(nullable = false, length = 200)
	private String actor;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String snapshot;
	@Column(nullable = false)
	private Instant createdAt;
	@Column(nullable = false)
	private Instant expiresAt;
	private Instant committedAt;

	public MarketStockReview(String id, String actor, String snapshot, Instant now) {
		this.id = id;
		this.actor = actor;
		this.snapshot = snapshot;
		createdAt = now;
		expiresAt = now.plusSeconds(1800);
	}

	public void commit(Instant now) {
		committedAt = now;
	}
}
