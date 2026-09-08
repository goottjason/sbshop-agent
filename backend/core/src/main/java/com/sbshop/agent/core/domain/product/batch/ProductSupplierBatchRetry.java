package com.sbshop.agent.core.domain.product.batch;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_supplier_batch_retry", uniqueConstraints = @UniqueConstraint(name = "uk_supplier_batch_retry", columnNames = {
	"batch_id", "request_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSupplierBatchRetry {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 36)
	private String batchId;
	@Column(nullable = false, length = 36)
	private String requestId;
	@Column(nullable = false, length = 200)
	private String actor;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String payload;
	@Column(nullable = false)
	private Instant createdAt;

	public ProductSupplierBatchRetry(String batchId, String requestId, String actor, String payload, Instant now) {
		this.batchId = batchId;
		this.requestId = requestId;
		this.actor = actor;
		this.payload = payload;
		createdAt = now;
	}
}
