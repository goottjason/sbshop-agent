package com.sbshop.agent.core.domain.market.inspection;

import jakarta.persistence.*;
import java.time.*;
import lombok.*;

/** Daily enrollment cursor. Batch creation and cursor advancement commit together. */
@Entity
@Table(name = "sb_market_inspection_sweep", uniqueConstraints = @UniqueConstraint(columnNames = {"market", "run_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MarketInspectionSweep {
	@Id
	@Column(length = 36)
	private String id;
	@Column(nullable = false)
	private LocalDate runDate;
	@Column(nullable = false, length = 50)
	private String market;
	@Column(nullable = false, length = 200)
	private String accountReference;
	@Column(nullable = false)
	private long upperRegistrationId;
	@Column(nullable = false)
	private long cursorRegistrationId;
	@Column(nullable = false)
	private long enrolledCount;
	@Column(nullable = false)
	private int batchCount;
	@Column(nullable = false, length = 30)
	private String state;
	@Column(nullable = false)
	private Instant startedAt;
	@Column(nullable = false)
	private Instant updatedAt;
	private Instant finishedAt;

	public MarketInspectionSweep(String id, LocalDate day, String account, long upperId, Instant now) {
		this(id, day, account, upperId, now, "SMART_STORE");
	}

	public MarketInspectionSweep(String id, LocalDate day, String account, long upperId, Instant now, String market) {
		this.id = id;
		this.market = market;
		runDate = day;
		accountReference = account;
		upperRegistrationId = upperId;
		state = "ENROLLING";
		startedAt = now;
		updatedAt = now;
	}

	public void advance(long cursor, int count, Instant now) {
		if (cursor <= cursorRegistrationId || cursor > upperRegistrationId || count <= 0)
			throw new IllegalArgumentException("잘못된 정기 조회 진행 위치입니다.");
		cursorRegistrationId = cursor;
		enrolledCount += count;
		batchCount++;
		updatedAt = now;
	}

	public void enrolled(Instant now) {
		state = "ENQUEUED";
		updatedAt = now;
	}

	public void finished(Instant now) {
		state = "FINISHED";
		finishedAt = now;
		updatedAt = now;
	}
}
