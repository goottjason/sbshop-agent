package com.sbshop.agent.core.domain.product.content;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_product_content_review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductContentReview {
	@Id
	@Column(length = 36)
	private String id;
	@Column(nullable = false, length = 200)
	private String actor;
	@Column(nullable = false)
	private Instant createdAt;
	@Column(nullable = false)
	private Instant expiresAt;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String payload;

	public ProductContentReview(String id, String actor, Instant now, Instant expiresAt, String payload) {
		this.id = id;
		this.actor = actor;
		this.createdAt = now;
		this.expiresAt = expiresAt;
		this.payload = payload;
	}
}
