package com.sbshop.agent.core.domain.market;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** Append-only evidence. No credentials or full marketplace payloads are stored here. */
@Entity
@Table(name = "sb_market_connection_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketConnectionEvent {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false)
	private Long registrationId;
	@Column(nullable = false)
	private Long productId;
	@Column(nullable = false, length = 50)
	private String market;
	@Column(nullable = false, length = 200)
	private String externalId;
	@Column(nullable = false, length = 200)
	private String actor;
	@Column(nullable = false, length = 40)
	private String source;
	@Column(nullable = false, length = 40)
	private String result;
	@Column(nullable = false, length = 40)
	private String observedState;
	@Column(nullable = false)
	private Instant observedAt;
	@Column(nullable = false)
	private Instant recordedAt;
	@Column(nullable = false)
	private long reviewedRevision;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String evidence;

	public MarketConnectionEvent(Long registrationId, Long productId, String market, String externalId,
		String actor, String source, String result, String observedState, Instant observedAt, long reviewedRevision,
		String evidence) {
		this.registrationId = registrationId;
		this.productId = productId;
		this.market = market;
		this.externalId = externalId;
		this.actor = actor;
		this.source = source;
		this.result = result;
		this.observedState = observedState;
		this.observedAt = observedAt;
		this.recordedAt = Instant.now();
		this.reviewedRevision = reviewedRevision;
		this.evidence = evidence;
	}
}
