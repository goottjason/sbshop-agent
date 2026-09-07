package com.sbshop.agent.core.domain.market.inspection;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_market_inspection_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketInspectionBatch {
	@Id
	@Column(length = 36)
	private String id;
	@Column(nullable = false, length = 200)
	private String actor;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String requestIdentity;
	@Column(nullable = false, length = 50)
	private String source;
	@Column(nullable = false, length = 50)
	private String market = "SMART_STORE";

	public void market(com.sbshop.agent.core.domain.order.enums.MarketType value) {
		market = value.name();
	}

	@Column(nullable = false)
	private Instant createdAt;
	@Column(length = 36)
	private String sweepId;

	public void attachToSweep(String id) {
		this.sweepId = id;
	}

	public MarketInspectionBatch(String id, String actor, String requestIdentity, String source, Instant now) {
		this.id = id;
		this.actor = actor;
		this.requestIdentity = requestIdentity;
		this.source = source;
		createdAt = now;
	}
}
