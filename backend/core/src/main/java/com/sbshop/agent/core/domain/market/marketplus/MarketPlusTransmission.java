package com.sbshop.agent.core.domain.market.marketplus;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** Browser-observed transmission evidence, never proof of current listing state or field equality. */
@Entity
@Table(name = "sb_marketplus_transmission", uniqueConstraints = @UniqueConstraint(columnNames = "fingerprint"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketPlusTransmission {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false)
	private Long registrationId;
	@Column(nullable = false)
	private Long productId;
	@Column(nullable = false, length = 64)
	private String fingerprint;
	@Column(nullable = false, length = 100)
	private String mallId;
	@Column(nullable = false)
	private int shopNo;
	@Column(nullable = false, length = 20)
	private String market;
	@Column(nullable = false, length = 200)
	private String sellerAccount;
	@Column(name = "cafe24_product_no", nullable = false, length = 30)
	private String cafe24ProductNo;
	@Column(name = "cafe24_product_code", nullable = false, length = 30)
	private String cafe24ProductCode;
	@Column(nullable = false, length = 200)
	private String externalId;
	@Column(nullable = false, length = 100)
	private String transferType;
	@Column(nullable = false, length = 20)
	private String outcome;
	@Column(nullable = false, length = 50)
	private String reasonCode;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String detail;
	@Column(nullable = false)
	private Instant requestedAt;
	@Column(nullable = false)
	private Instant completedAt;
	@Column(nullable = false)
	private Instant capturedAt;
	@Column(nullable = false)
	private Instant recordedAt;
	@Column(nullable = false, length = 200)
	private String actor;
	@Column(nullable = false, length = 40)
	private String source;
	@Column(nullable = false, length = 30)
	private String coverage;

	@Builder
	public MarketPlusTransmission(Long registrationId, Long productId, String fingerprint, String mallId, int shopNo,
		String market, String sellerAccount, String cafe24ProductNo, String cafe24ProductCode, String externalId,
		String transferType, String outcome, String reasonCode, String detail, Instant requestedAt, Instant completedAt,
		Instant capturedAt, String actor) {
		this.registrationId = registrationId;
		this.productId = productId;
		this.fingerprint = fingerprint;
		this.mallId = mallId;
		this.shopNo = shopNo;
		this.market = market;
		this.sellerAccount = sellerAccount;
		this.cafe24ProductNo = cafe24ProductNo;
		this.cafe24ProductCode = cafe24ProductCode;
		this.externalId = externalId;
		this.transferType = transferType;
		this.outcome = outcome;
		this.reasonCode = reasonCode;
		this.detail = detail;
		this.requestedAt = requestedAt;
		this.completedAt = completedAt;
		this.capturedAt = capturedAt;
		this.recordedAt = Instant.now();
		this.actor = actor;
		this.source = "LIVE_CHROME_MARKETPLUS";
		this.coverage = "CURRENT_PAGE";
	}
}
