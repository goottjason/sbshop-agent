package com.sbshop.agent.core.domain.product.source;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_product_source_collection", uniqueConstraints = @UniqueConstraint(name = "uk_source_request", columnNames = {
	"actor", "request_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSourceCollection {
	@Id
	@Column(length = 36)
	private String id;
	@Column(nullable = false, length = 36)
	private String requestId;
	@Column(nullable = false, length = 200)
	private String actor;
	@Column(nullable = false)
	private Instant createdAt;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String productIds;

	public ProductSourceCollection(String id, String requestId, String actor, Instant createdAt, String productIds) {
		this.id = id;
		this.requestId = requestId;
		this.actor = actor;
		this.createdAt = createdAt;
		this.productIds = productIds;
	}
}
