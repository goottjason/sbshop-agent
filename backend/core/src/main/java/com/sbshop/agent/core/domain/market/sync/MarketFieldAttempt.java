package com.sbshop.agent.core.domain.market.sync;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_market_field_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketFieldAttempt {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false)
	private Long taskId;
	@Column(nullable = false, length = 30)
	private String phase;
	@Column(nullable = false, length = 1000)
	private String detail;
	@Column(nullable = false)
	private Instant recordedAt;
	@Column(columnDefinition = "TEXT")
	private String observedValues;

	public MarketFieldAttempt(Long taskId, String phase, String detail, String observedValues, Instant at) {
		this.taskId = taskId;
		this.phase = phase;
		this.detail = detail.substring(0, Math.min(detail.length(), 1000));
		recordedAt = at;
		this.observedValues = observedValues;
	}
}
