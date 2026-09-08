package com.sbshop.agent.core.domain.product.batch;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.*;

@Entity
@Table(name = "sb_supplier_batch_stage", uniqueConstraints = @UniqueConstraint(name = "uk_supplier_batch_stage", columnNames = {
	"item_id", "stage", "market", "field"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSupplierBatchStage {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 36)
	private String batchId;
	@Column(nullable = false)
	private Long itemId;
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
	@Column(nullable = false)
	private boolean retryable;
	@Column(nullable = false)
	private int attempts;
	@Column(nullable = false, length = 36)
	private String operationId;
	@Column(length = 36)
	private String referenceId;
	private Long targetId;
	private Long taskId;
	@Column(length = 1000)
	private String expected;
	@Column(length = 1000)
	private String observed;
	private Instant startedAt;
	private Instant finishedAt;
	@Column(nullable = false)
	private Instant nextRunAt;

	public ProductSupplierBatchStage(String batchId, Long itemId, String stage, String market, String field,
		Instant now) {
		this.batchId = batchId;
		this.itemId = itemId;
		this.stage = stage;
		this.market = market == null ? "" : market;
		this.field = field == null ? "" : field;
		state = "WAITING";
		detail = "선행 단계 대기";
		operationId = UUID.randomUUID().toString();
		nextRunAt = now;
	}

	public boolean terminal() {
		return Set.of("SUCCEEDED", "UNCHANGED", "FAILED", "BLOCKED", "SKIPPED").contains(state);
	}

	public boolean successful() {
		return Set.of("SUCCEEDED", "UNCHANGED").contains(state);
	}

	public void start(Instant now) {
		if (startedAt == null) {
			attempts++;
			startedAt = now;
		}
		state = "RUNNING";
		detail = "처리 중";
		nextRunAt = now;
	}

	public void reference(String id) {
		referenceId = id;
	}

	public void target(Long id) {
		targetId = id;
	}

	public void task(Long id) {
		taskId = id;
	}

	public void values(String expected, String observed) {
		this.expected = trim(expected);
		this.observed = trim(observed);
	}

	public void waitUntil(String detail, Instant next) {
		state = "RUNNING";
		this.detail = trim(detail);
		nextRunAt = next;
	}

	public void outcome(String state, String detail, boolean retryable, Instant now) {
		this.state = state;
		this.detail = trim(detail);
		this.retryable = retryable;
		finishedAt = terminal() ? now : null;
		nextRunAt = now;
	}

	public void retry(Instant now) {
		state = "WAITING";
		detail = "이 단계 재시도 대기";
		retryable = false;
		startedAt = null;
		finishedAt = null;
		nextRunAt = now;
		operationId = UUID.randomUUID().toString();
		if (stage.equals("CRAWL") || stage.equals("MARKET")) {
			referenceId = null;
			taskId = null;
		}
	}

	public void unblock(Instant now) {
		state = "WAITING";
		detail = "선행 단계 재시도 완료 대기";
		finishedAt = null;
		nextRunAt = now;
	}

	private static String trim(String value) {
		return value == null ? "" : value.substring(0, Math.min(1000, value.length()));
	}
}
