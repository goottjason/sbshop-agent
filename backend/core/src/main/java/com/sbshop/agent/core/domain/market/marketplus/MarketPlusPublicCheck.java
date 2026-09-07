package com.sbshop.agent.core.domain.market.marketplus;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_marketplus_public_check", uniqueConstraints = @UniqueConstraint(columnNames = {"collection_id",
	"product_id", "market"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketPlusPublicCheck {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 36)
	private String collectionId;
	@Column(nullable = false)
	private Long productId;
	@Column(length = 200)
	private String sbCode;
	@Column(nullable = false, length = 20)
	private String market;
	@Column(columnDefinition = "TEXT")
	private String targetJson;
	@Column(length = 100)
	private String mallId;
	private Long registrationRevision;
	@Column(nullable = false, length = 30)
	private String state;
	@Column(nullable = false, length = 1000)
	private String reason;
	@Column(nullable = false)
	private int attempts;
	private Instant nextRunAt;
	@Column(length = 36)
	private String leaseToken;
	@Column(length = 200)
	private String workerActor;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String leaseHistory = "{}";
	private Instant leaseUntil;
	private Long observationId;
	private Instant checkedAt;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String events = "";
	@Column(columnDefinition = "TEXT")
	private String observedValues;

	public MarketPlusPublicCheck(String collectionId, Long productId, String sbCode, String market, String targetJson,
		String mallId, Long registrationRevision, Instant now, String skip) {
		this.collectionId = collectionId;
		this.productId = productId;
		this.sbCode = sbCode;
		this.market = market;
		this.targetJson = targetJson;
		this.mallId = mallId;
		this.registrationRevision = registrationRevision;
		this.state = skip == null ? "QUEUED" : "SKIPPED";
		this.reason = skip == null ? "공개가격 조회 대기" : skip;
		this.nextRunAt = skip == null ? now : null;
	}

	public void claim(String token, String actor, Instant now, String history) {
		leaseHistory = history;
		attempts++;
		state = "RUNNING";
		reason = "공개 상품·판매 계정·표시가격 확인 중";
		leaseToken = token;
		workerActor = actor;
		leaseUntil = now.plusSeconds(120);
		nextRunAt = null;
	}

	public boolean owns(String token, String actor) {
		return token != null && token.equals(leaseToken) && actor.equals(workerActor);
	}

	public void finish(String state, String reason, Instant now, Instant next, Long observationId, String values) {
		this.state = state;
		this.reason = reason;
		this.checkedAt = now;
		this.nextRunAt = next;
		this.observationId = observationId;
		this.observedValues = values;
		this.leaseUntil = null;
		events += now + " · " + attempts + "회 · " + reason + "\n";
	}
}
