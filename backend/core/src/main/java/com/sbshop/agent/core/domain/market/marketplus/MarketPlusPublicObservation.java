package com.sbshop.agent.core.domain.market.marketplus;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

/** Typed public-page observations; neither a transmission receipt nor approval of a marketplace write. */
@Entity
@Table(name = "sb_marketplus_public_observation", uniqueConstraints = @UniqueConstraint(columnNames = "fingerprint"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MarketPlusPublicObservation {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 64)
	private String fingerprint;
	@Column(nullable = false)
	private Long productId;
	@Column(nullable = false)
	private long productRevision;
	@Column(nullable = false)
	private Long registrationId;
	@Column(nullable = false, length = 20)
	private String market;
	@Column(nullable = false, length = 100)
	private String mallId;
	@Column(nullable = false, length = 200)
	private String sellerAccount;
	@Column(nullable = false, length = 30)
	private String cafe24ProductNo;
	@Column(nullable = false, length = 30)
	private String cafe24ProductCode;
	@Column(nullable = false, length = 200)
	private String externalId;
	@Column(nullable = false, length = 500)
	private String sourceUrl;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String observedValues;
	@Column(nullable = false, length = 40)
	private String quantityBasis;
	@Column(nullable = false)
	private Instant capturedAt;
	@Column(nullable = false)
	private Instant recordedAt;
	@Column(nullable = false, length = 200)
	private String actor;
}
