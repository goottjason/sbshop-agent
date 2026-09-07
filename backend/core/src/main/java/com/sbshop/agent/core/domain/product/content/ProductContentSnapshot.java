package com.sbshop.agent.core.domain.product.content;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity
@Table(name = "sb_product_content_snapshot")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductContentSnapshot {
	public enum State {
		QUEUED, COLLECTING, READY, PARTIAL, FAILED, UNSUPPORTED
	}

	@Id
	@Column(length = 36)
	private String id;
	@Column(nullable = false, length = 36)
	private String collectionId;
	@Column(nullable = false)
	private Long productId;
	@Column(length = 100)
	private String sbCode;
	@Column(nullable = false)
	private long revision;
	@Column(length = 64)
	private String connectionFingerprint;
	@Column(length = 1000)
	private String sourceUrl;
	@Column(length = 20)
	private String vendor;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private State state;
	@Column(length = 1000)
	private String reason;
	@Column(nullable = false)
	private Instant requestedAt;
	private Instant collectedAt;
	private Instant expiresAt;
	private Instant imagesCollectedAt;
	private Instant detailCollectedAt;
	private Instant imagesAppliedAt;
	private Instant detailAppliedAt;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String captured;
	@Column(columnDefinition = "TEXT")
	private String proposed;
	@Column(length = 36)
	private String claimToken;

	public ProductContentSnapshot(String id, String collectionId, Long productId, String sbCode, long revision,
		String connectionFingerprint, String sourceUrl, String vendor, Instant now, String captured) {
		this.id = id;
		this.collectionId = collectionId;
		this.productId = productId;
		this.sbCode = sbCode;
		this.revision = revision;
		this.connectionFingerprint = connectionFingerprint;
		this.sourceUrl = sourceUrl;
		this.vendor = vendor;
		this.requestedAt = now;
		this.captured = captured;
		this.state = State.QUEUED;
	}

	public void fail(State state, String reason) {
		this.state = state;
		this.reason = reason;
		this.claimToken = null;
	}

	public void claim(String token) {
		this.claimToken = token;
		this.state = State.COLLECTING;
	}

	public void complete(String proposed, boolean images, boolean detail, Instant now) {
		this.proposed = proposed;
		this.claimToken = null;
		this.state = images && detail ? State.READY : images || detail ? State.PARTIAL : State.FAILED;
		this.reason = state == State.READY ? "수집 완료 · 검토 후 DB 적용이 필요합니다."
			: state == State.PARTIAL ? "일부 항목만 수집했습니다. 성공한 항목을 확인하세요." : "이미지와 상세정보를 수집하지 못했습니다.";
		if (images || detail) {
			this.collectedAt = now;
			this.expiresAt = now.plusSeconds(86400);
		}
		if (images)
			this.imagesCollectedAt = now;
		if (detail)
			this.detailCollectedAt = now;
	}

	public void applied(boolean images, boolean detail, Instant now) {
		if (images && imagesAppliedAt == null)
			imagesAppliedAt = now;
		if (detail && detailAppliedAt == null)
			detailAppliedAt = now;
	}
}
