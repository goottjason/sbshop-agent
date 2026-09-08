package com.sbshop.agent.core.domain.product.batch;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_supplier_batch_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSupplierBatchAttempt {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 36)
	private String batchId;
	@Column(nullable = false)
	private Long itemId;
	@Column(nullable = false)
	private Long stageId;
	@Column(nullable = false, length = 20)
	private String stage;
	@Column(nullable = false, length = 50)
	private String market;
	@Column(nullable = false, length = 20)
	private String field;
	@Column(nullable = false, length = 30)
	private String state;
	@Column(nullable = false, length = 1000)
	private String detail;
	@Column(length = 36)
	private String referenceId;
	@Column(nullable = false)
	private Instant recordedAt;

	public ProductSupplierBatchAttempt(ProductSupplierBatchStage s, Instant now) {
		batchId = s.getBatchId();
		itemId = s.getItemId();
		stageId = s.getId();
		stage = s.getStage();
		market = s.getMarket();
		field = s.getField();
		state = s.getState();
		detail = s.getDetail();
		referenceId = s.getReferenceId();
		recordedAt = now;
	}
}
