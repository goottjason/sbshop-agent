package com.sbshop.agent.core.domain.product.edit;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_product_change_history", uniqueConstraints = @UniqueConstraint(name = "uk_product_change_review", columnNames = {
	"review_id", "product_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductChangeHistory {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "review_id", nullable = false, length = 36)
	private String reviewId;
	@Column(name = "product_id", nullable = false)
	private Long productId;
	@Column(nullable = false)
	private long beforeRevision;
	@Column(nullable = false)
	private long afterRevision;
	@Column(nullable = false, length = 200)
	private String actor;
	@Column(nullable = false)
	private Instant createdAt;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String changes;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String reviewDetails;

	public ProductChangeHistory(String reviewId, Long productId, long beforeRevision, long afterRevision, String actor,
		String changes, String reviewDetails) {
		this.reviewId = reviewId;
		this.productId = productId;
		this.beforeRevision = beforeRevision;
		this.afterRevision = afterRevision;
		this.reviewDetails = reviewDetails;
		this.actor = actor;
		this.changes = changes;
		this.createdAt = Instant.now();
	}
}
