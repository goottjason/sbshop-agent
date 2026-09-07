package com.sbshop.agent.core.domain.market.marketplus;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_marketplus_public_collection", uniqueConstraints = @UniqueConstraint(columnNames = {"actor",
	"request_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MarketPlusPublicCollection {
	@Id
	@Column(length = 36)
	private String id;
	@Column(nullable = false, length = 36)
	private String requestId;
	@Column(nullable = false, length = 200)
	private String actor;
	@Column(nullable = false, length = 64)
	private String requestHash;
	@Column(nullable = false)
	private Instant createdAt;
}
