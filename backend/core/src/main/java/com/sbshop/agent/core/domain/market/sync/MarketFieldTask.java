package com.sbshop.agent.core.domain.market.sync;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;
import lombok.*;

@Entity
@Table(name = "sb_market_field_task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketFieldTask {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(nullable = false, length = 36)
	private String reviewId;
	private Long changeTargetId;
	@Column(nullable = false)
	private Long productId;
	private String sbCode;
	private Long registrationId;
	@Column(nullable = false)
	private long productRevision;
	@Column(nullable = false)
	private long registrationRevision;
	@Column(nullable = false, length = 50)
	private String market;
	private String listingId;
	private String optionId;
	private String resolvedOptionId;
	@Column(columnDefinition = "TEXT")
	private String identifiers;
	private String accountReference;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String fields;
	@Column(columnDefinition = "TEXT")
	private String expectedValues;
	@Column(columnDefinition = "TEXT")
	private String observedValues;
	@Column(columnDefinition = "TEXT")
	private String preparedPayload;
	@Column(nullable = false)
	private boolean requiresApproval;
	@Column(length = 30)
	private String observedApproval;
	@Column(nullable = false, length = 30)
	private String state;
	@Column(nullable = false, length = 1000)
	private String detail;
	@Column(nullable = false)
	private Instant createdAt;
	@Column(nullable = false)
	private Instant nextRunAt;
	private Instant checkedAt;
	private Instant finishedAt;
	@Column(length = 36)
	private String leaseToken;
	private Instant leaseUntil;
	@Column(nullable = false)
	private int reads;
	@Column(nullable = false)
	private int writes;
	@Column(nullable = false)
	private int failures;
	@Column(nullable = false)
	private boolean writeRejected;
	@Column(length = 1000)
	private String receipt;

	public MarketFieldTask(String reviewId, Long targetId, Long productId, String sbCode, Long registrationId,
		long revision, long registrationRevision, String market, String listingId, String optionId, String identifiers,
		String account, String fields, String skip, Instant now) {
		this.reviewId = reviewId;
		changeTargetId = targetId;
		this.productId = productId;
		this.sbCode = sbCode;
		this.registrationId = registrationId;
		productRevision = revision;
		this.registrationRevision = registrationRevision;
		this.market = market;
		this.listingId = listingId;
		this.optionId = optionId;
		this.identifiers = identifiers;
		accountReference = account;
		this.fields = fields;
		createdAt = now;
		nextRunAt = now;
		state = skip == null ? "PREPARE" : "SKIPPED";
		detail = skip == null ? "현재 상품과 마켓별 전송값을 준비합니다." : trim(skip);
		if (skip != null)
			finishedAt = now;
	}

	public boolean active() {
		return Set.of("PREPARE", "DRAFT", "CHECK", "VERIFY", "AWAITING_APPROVAL").contains(state);
	}

	public void claim(String token, Instant until) {
		leaseToken = token;
		leaseUntil = until;
		nextRunAt = until;
		reads++;
	}

	public boolean owns(String token, Instant now) {
		return token.equals(leaseToken) && leaseUntil != null && leaseUntil.isAfter(now);
	}

	public void prepared(String option, String expected, String payload, boolean approval, boolean automatic,
		Instant now) {
		resolvedOptionId = option;
		expectedValues = expected;
		preparedPayload = payload;
		requiresApproval = approval;
		failures = 0;
		finish(automatic && !approval ? "CHECK" : "DRAFT", approval ? "마켓 심사를 요청하는 필드 변경입니다. 준비된 값을 확인하고 심사 요청에 동의하세요."
			: automatic ? "저장된 필드 변경의 마켓 현재 값을 조회합니다." : "마켓에 보낼 필드 값을 준비했습니다. 검토 후 반영하세요.", now, now);
	}

	public void queue(Instant now) {
		finish("CHECK", "검토한 필드의 마켓 현재 값을 먼저 조회합니다.", now, now);
	}

	public void beginWrite(Instant now) {
		writes++;
		state = "VERIFY";
		detail = "필드 전송 의도 기록. 별도 재조회 전에는 성공이 아닙니다.";
		leaseUntil = now.plusSeconds(180);
		nextRunAt = leaseUntil;
	}

	public void observed(String values, String approval, Instant now) {
		observedValues = values;
		observedApproval = approval;
		checkedAt = now;
	}

	public void failure() {
		failures++;
	}

	public void resetFailures() {
		failures = 0;
	}

	public void rejectWrite() {
		writeRejected = true;
	}

	public void receipt(String value) {
		receipt = trim(value);
	}

	public void schedule(Instant next) {
		nextRunAt = next;
	}

	public void finish(String state, String detail, Instant now, Instant next) {
		this.state = state;
		this.detail = trim(detail);
		nextRunAt = next;
		leaseToken = null;
		leaseUntil = null;
		if (!active())
			finishedAt = now;
	}

	private static String trim(String text) {
		return text == null ? "응답 내용 없음" : text.substring(0, Math.min(1000, text.length()));
	}
}
