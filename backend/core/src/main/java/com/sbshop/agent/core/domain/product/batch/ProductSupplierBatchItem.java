package com.sbshop.agent.core.domain.product.batch;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_supplier_batch_item", uniqueConstraints = @UniqueConstraint(name = "uk_supplier_batch_product", columnNames = {
	"batch_id", "product_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSupplierBatchItem {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 36)
	private String batchId;
	@Column(nullable = false)
	private Long productId;
	@Column(length = 100)
	private String sbCode;
	@Column(length = 1000)
	private String productName;
	@Column(length = 2000)
	private String thumbnailUrl;
	@Column(nullable = false, length = 30)
	private String state;
	@Column(nullable = false, length = 1000)
	private String detail;
	@Column(nullable = false)
	private int attempts;
	@Column(length = 36)
	private String sourceSnapshotId;
	@Column(length = 36)
	private String editReviewId;
	private Long savedRevision;
	private Long historyId;
	@Column(columnDefinition = "TEXT")
	private String calculation;
	@Column(nullable = false)
	private Instant updatedAt;

	public ProductSupplierBatchItem(String batchId, Long productId, String sb, String name, String thumbnail,
		Instant now) {
		this.batchId = batchId;
		this.productId = productId;
		sbCode = sb;
		productName = name;
		thumbnailUrl = thumbnail;
		state = "WAITING";
		detail = "순차 처리 대기";
		updatedAt = now;
	}

	public void start(Instant now) {
		state = "RUNNING";
		detail = "수집·DB 저장 진행 중";
		attempts++;
		updatedAt = now;
	}

	public void source(String id) {
		sourceSnapshotId = id;
	}

	public void reviewed(String id, String calculation) {
		editReviewId = id;
		this.calculation = calculation;
	}

	public void saved(Long revision, Long historyId) {
		savedRevision = revision;
		this.historyId = historyId;
	}

	public void outcome(String state, String detail, Instant now) {
		this.state = state;
		this.detail = detail;
		updatedAt = now;
	}

	public void retry(Instant now) {
		state = "RUNNING";
		detail = "실패 단계 재시도 대기";
		updatedAt = now;
	}
}
