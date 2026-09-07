package com.sbshop.agent.core.domain.market.inspection;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** Shared by this queue's Smartstore reads, across application instances and restarts. */
@Entity
@Table(name = "sb_market_inspection_gate")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketInspectionGate {
	public static final String SMART_STORE_SCOPE = "SMART_STORE_ORIGIN_READ";
	@Id
	@Column(length = 50)
	private String id;
	@Column(length = 36)
	private String leaseToken;
	private Instant leaseUntil;
	@Column(nullable = false)
	private Instant nextAllowedAt;
	@Column(length = 200)
	private String verifiedAccountReference;
	private Instant accountConfirmedAt;
	@Column(length = 300)
	private String accountConfirmationEvidence;

	public void confirmInitialAccount(String reference, Instant now) {
		if (verifiedAccountReference != null || reference == null || reference.isBlank())
			return;
		verifiedAccountReference = reference;
		accountConfirmedAt = now;
		accountConfirmationEvidence = "USER_Q24_2026-09-06: 기존 스마트스토어 상품은 현재 연동된 한 계정의 상품이라고 사용자 확인";
	}

	public MarketInspectionGate(String id, Instant now) {
		this.id = id;
		nextAllowedAt = now;
	}

	public boolean available(Instant now) {
		return !nextAllowedAt.isAfter(now) && (leaseUntil == null || !leaseUntil.isAfter(now));
	}

	public void claim(String token, Instant until) {
		leaseToken = token;
		leaseUntil = until;
	}

	public boolean owns(String token, Instant now) {
		return token.equals(leaseToken) && leaseUntil != null && leaseUntil.isAfter(now);
	}

	/** A late 429 must delay subsequent work without stealing a newer worker's lease. */
	public void deferUntil(Instant next) {
		if (next != null && next.isAfter(nextAllowedAt))
			nextAllowedAt = next;
	}

	public void release(Instant next) {
		leaseToken = null;
		leaseUntil = null;
		deferUntil(next);
	}
}
