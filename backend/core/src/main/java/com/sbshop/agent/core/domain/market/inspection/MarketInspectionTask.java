package com.sbshop.agent.core.domain.market.inspection;

import com.sbshop.agent.core.application.market.MarketConnectionService.Snapshot;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_market_inspection_task", uniqueConstraints = @UniqueConstraint(columnNames = {"batch_id",
	"product_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketInspectionTask {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 36)
	private String batchId;
	@Column(nullable = false)
	private Long productId;
	@Column(nullable = false, length = 50)
	private String market = "SMART_STORE";

	public void market(MarketType value) {
		market = value.name();
	}

	@Column(length = 100)
	private String sbCode;
	private Long registrationId;
	private long registrationRevision;
	@Column(length = 200)
	private String externalId;
	@Column(columnDefinition = "TEXT")
	private String identifiers;
	@Column(length = 200)
	private String accountReference;
	@Column(nullable = false, length = 30)
	private String state;
	@Column(nullable = false)
	private int attempts;
	@Column(nullable = false)
	private Instant nextRunAt;
	@Column(length = 36)
	private String leaseToken;
	private Instant leaseUntil;
	private Long eventId;
	@Column(length = 200)
	private String code;
	@Column(length = 1000)
	private String detail;
	@Column(length = 40)
	private String observedState;
	@Column(nullable = false)
	private Instant updatedAt;

	public MarketInspectionTask(String batchId, Long productId, String sbCode, Snapshot snapshot, String account,
		String skip, Instant now) {
		this.batchId = batchId;
		this.productId = productId;
		this.sbCode = sbCode;
		accountReference = account;
		if (snapshot != null) {
			market = snapshot.market().name();
			registrationId = snapshot.registrationId();
			registrationRevision = snapshot.revision();
			externalId = snapshot.externalId();
			identifiers = snapshot.identifiers();
		}
		state = skip == null ? "QUEUED" : "SKIPPED";
		detail = skip;
		nextRunAt = now;
		updatedAt = now;
	}

	public Snapshot snapshot() {
		return new Snapshot(registrationId, productId, registrationRevision, MarketType.valueOf(market), externalId,
			identifiers);
	}

	public void claim(String token, Instant until, Instant now) {
		state = "RUNNING";
		leaseToken = token;
		leaseUntil = until;
		attempts++;
		updatedAt = now;
	}

	public boolean owns(String token, Instant now) {
		return "RUNNING".equals(state) && token.equals(leaseToken) && leaseUntil.isAfter(now);
	}

	public void finish(String state, String code, String detail, String observed, Long eventId, Instant next,
		Instant now) {
		this.state = state;
		this.code = abbreviate(code, 200);
		this.detail = abbreviate(detail, 1000);
		observedState = observed;
		this.eventId = eventId;
		nextRunAt = next;
		updatedAt = now;
		leaseToken = null;
		leaseUntil = null;
	}

	private static String abbreviate(String value, int length) {
		return value == null ? null : value.substring(0, Math.min(value.length(), length));
	}
}
