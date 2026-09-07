package com.sbshop.agent.core.domain.market.sync;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_market_field_review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketFieldReview {
	@Id
	@Column(length = 36)
	private String id;
	@Column(nullable = false, length = 200)
	private String actor;
	@Column(nullable = false)
	private Instant createdAt;
	@Column(nullable = false)
	private Instant expiresAt;
	@Column(nullable = false)
	private boolean automatic;
	@Column(nullable = false)
	private boolean approvalConsent;
	private Instant committedAt;

	public MarketFieldReview(String id, String actor, boolean automatic, Instant now) {
		this.id = id;
		this.actor = actor;
		this.automatic = automatic;
		createdAt = now;
		expiresAt = now.plusSeconds(1800);
	}

	public void prepared(Instant now) {
		if (committedAt == null)
			expiresAt = now.plusSeconds(1800);
	}

	public void commit(boolean consent, Instant now) {
		approvalConsent = consent;
		committedAt = now;
	}
}
