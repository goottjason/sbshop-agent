package com.sbshop.agent.core.domain.product.edit;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_product_edit_review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductEditReview {
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

	public ProductEditReview(String id, String actor, Instant now, String payload) {
		this.id = id;
		this.actor = actor;
		this.createdAt = now;
		this.expiresAt = now.plusSeconds(1800);
		this.payload = payload;
	}
}
