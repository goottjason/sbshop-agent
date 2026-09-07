package com.sbshop.agent.core.domain.market.publication;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_market_publication_task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketPublicationTask {
	@Id
	@Column(length = 36)
	private String id;
	@Column(nullable = false)
	private Long productId;
	private Long registrationId;
	@Column(nullable = false)
	private long productRevision;
	@Column(nullable = false, length = 50)
	private String market;
	@Column(nullable = false, length = 200)
	private String actor;
	@Column(nullable = false, length = 255)
	private String sbCode;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String connectionSnapshot;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String prepared;
	@Column(nullable = false, length = 30)
	private String state;
	@Column(nullable = false, length = 1000)
	private String detail;
	@Column(columnDefinition = "TEXT")
	private String returnedIdentifiers;
	private String listingId;
	@Column(nullable = false)
	private Instant createdAt;
	@Column(nullable = false)
	private Instant expiresAt;
	private Instant committedAt;
	@Column(nullable = false)
	private Instant nextRunAt;
	private Instant finishedAt;
	private Instant checkedAt;
	@Column(length = 36)
	private String leaseToken;
	private Instant leaseUntil;
	@Column(nullable = false)
	private int attempts;

	public MarketPublicationTask(String id, Long productId, Long registrationId, long revision, String market,
		String actor, String sbCode, String connectionSnapshot, String prepared, String reason, Instant now) {
		this.id = id;
		this.productId = productId;
		this.registrationId = registrationId;
		productRevision = revision;
		this.market = market;
		this.actor = actor;
		this.sbCode = sbCode;
		this.connectionSnapshot = connectionSnapshot;
		this.prepared = prepared;
		state = "PREVIEW";
		detail = reason;
		createdAt = now;
		expiresAt = now.plusSeconds(1800);
		nextRunAt = now;
	}

	public void commit(Long regId, Instant now) {
		registrationId = regId;
		committedAt = now;
		state = "QUEUED";
		nextRunAt = now;
		detail = "검토한 등록 요청 전송 대기 중입니다.";
	}

	public void resetVerification(Instant now) {
		attempts = 0;
		finish("VERIFY", "입력한 원상품 번호를 재조회합니다. 신규 등록 요청은 전송하지 않습니다.", now, now);
	}

	public void claim(String token, Instant until, boolean post) {
		leaseToken = token;
		leaseUntil = until;
		nextRunAt = until;
		attempts++;
		if (post) {
			state = "POST_STARTED";
			detail = "등록 전송 시작 기록. 중단 후 자동으로 재전송하지 않습니다.";
		}
	}

	public boolean owns(String token, Instant now) {
		return token.equals(leaseToken) && leaseUntil != null && leaseUntil.isAfter(now);
	}

	public void identifiers(String json, String id) {
		returnedIdentifiers = json;
		listingId = id;
	}

	public void finish(String state, String detail, Instant now, Instant next) {
		this.state = state;
		this.detail = detail.substring(0, Math.min(1000, detail.length()));
		checkedAt = now;
		nextRunAt = next;
		leaseToken = null;
		leaseUntil = null;
		if (java.util.Set.of("REGISTERED", "STALE", "UNKNOWN_CREATE", "ACTION_REQUIRED").contains(state))
			finishedAt = now;
	}
}
