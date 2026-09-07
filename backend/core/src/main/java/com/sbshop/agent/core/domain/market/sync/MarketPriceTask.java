package com.sbshop.agent.core.domain.market.sync;

import jakarta.persistence.*;
import java.time.Instant;
import java.math.BigDecimal;
import lombok.*;

@Entity
@Table(name = "sb_market_price_task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketPriceTask {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 36)
	private String reviewId;
	@Column(nullable = false)
	private Long productId;
	private String sbCode;
	private Long registrationId;
	@Column(nullable = false)
	private long productRevision;
	@Column(nullable = false, length = 50)
	private String market;
	private String listingId;
	private String optionId;
	@Column(columnDefinition = "TEXT")
	private String identifiers;
	private String accountReference;
	@Column(precision = 19, scale = 2)
	private BigDecimal expectedPrice;
	@Column(precision = 19, scale = 2)
	private BigDecimal observedPrice;
	@Column(nullable = false, length = 30)
	private String state;
	@Column(nullable = false, length = 1000)
	private String detail;
	@Column(nullable = false)
	private Instant createdAt;
	@Column(nullable = false)
	private Instant nextRunAt;
	private Instant checkedAt;
	private Instant finishedAt;
	@Column(length = 36)
	private String leaseToken;
	private Instant leaseUntil;
	@Column(nullable = false)
	private int reads;
	@Column(nullable = false)
	private int writes;
	@Column(length = 1000)
	private String receipt;

	public MarketPriceTask(String reviewId, Long productId, String sbCode, Long registrationId, long revision,
		String market, String listingId, String optionId, String identifiers, String account, BigDecimal price,
		String skip, Instant now) {
		this.reviewId = reviewId;
		this.productId = productId;
		this.sbCode = sbCode;
		this.registrationId = registrationId;
		productRevision = revision;
		this.market = market;
		this.listingId = listingId;
		this.optionId = optionId;
		this.identifiers = identifiers;
		accountReference = account;
		expectedPrice = price;
		createdAt = now;
		nextRunAt = now;
		state = skip == null ? "CHECK" : "SKIPPED";
		detail = skip == null ? "현재 마켓 가격을 먼저 조회합니다." : skip;
		if (skip != null)
			finishedAt = now;
	}

	public void claim(String token, Instant until) {
		leaseToken = token;
		leaseUntil = until;
		nextRunAt = until;
		reads++;
	}

	public boolean owns(String token, Instant now) {
		return token.equals(leaseToken) && leaseUntil != null && leaseUntil.isAfter(now);
	}

	/** Commit this intent before the network call. On crash, the next worker must READ first. */
	public void beginWrite(Instant now) {
		leaseUntil = now.plusSeconds(180);
		nextRunAt = leaseUntil;
		writes++;
		state = "VERIFY";
		detail = "가격 전송 시작 기록. 실제 반영 여부는 재조회 대기 중입니다.";
		checkedAt = now;
	}

	public void receipt(String value) {
		receipt = trim(value);
	}

	public void observed(BigDecimal value, Instant now) {
		observedPrice = value;
		checkedAt = now;
	}

	public void finish(String state, String detail, Instant now, Instant next) {
		this.state = state;
		this.detail = trim(detail);
		nextRunAt = next;
		leaseToken = null;
		leaseUntil = null;
		if (!java.util.Set.of("CHECK", "VERIFY").contains(state))
			finishedAt = now;
	}

	private static String trim(String s) {
		return s == null ? "응답 내용 없음" : s.substring(0, Math.min(1000, s.length()));
	}
}
